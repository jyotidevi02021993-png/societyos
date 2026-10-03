# SocietyOS AI Service — Post-Fix Code Review

**Verdict**: APPROVED

This review covers the full ai-service implementation after two fix iterations. The verification
evidence confirms `from app import create_app` succeeds, 45 unit tests pass with no DB or network
calls, and all three blocking findings from the prior pass (duplicate JWT audience field,
triage inbox split, history RLS gap) are confirmed resolved in the code. The watch items below
are non-blocking style and resilience observations.

Watch for: (1) estate `_handle_message` splits idempotency and signal writes across two
transactions — confirmed gap, non-blocking at this scale; (2) JWKS fetch is synchronous
(`httpx.get`) inside an async middleware path; (3) LLM cap check reads `ai_settings` in one
transaction then enforces in a second, leaving a theoretical double-spend window when settings
are changed mid-request.

---

## High-level view

The app factory wires five services through a single lifespan: DB pool, optional Kafka consumers,
and FastAPI routers. JWT validation is RS256 with JWKS key rotation, audience validation
opt-in via `SOS_JWT_AUDIENCE` (the duplicate-field bug that made `verify_aud=False` unreachable
is confirmed fixed). Role checks on admin/manager routes are present and consistent across all
four routers.

PII redaction runs on both sides of the LLM boundary: `redact_pii()` strips Indian mobile
numbers and email addresses before the prompt is sent and before the response is stored.
Both `conversation_message` and `llm_call_log` receive the redacted strings. The regex patterns
cover `+91` prefixed and bare 10-digit numbers plus standard email format — sufficient for the
stated scope.

The transactional outbox pattern is correct throughout: every `publish()` call is inside a
`db.tx()` block and the guard in `events.py` raises `RuntimeError` if called outside a
transaction. No module calls `KafkaTemplate` or any direct Kafka producer. `uuid7()` generates
UUIDv7 for every primary key.

Triage idempotency is repaired: LLM work happens outside the DB transaction, then inbox INSERT
and triage_suggestion upsert land in a single transaction. A `rowcount == 0` early-exit
correctly skips both writes on duplicate delivery. Knowledge and estate consumers follow the
same pattern, with one gap noted below.

Embeddings are produced at `EMBEDDING_DIM=384` by `HashingEmbeddings` (default) or
`SentenceTransformerEmbeddings`. `vector_literal()` produces the `[f,f,f,...]` string cast
with `%s::vector` — no psycopg adapter required. The dimension is a module constant in
`config.py` and is not enforced at insertion time, so a misconfigured provider producing
a different dimension would silently fail at the Postgres `<=>` operator.

The Kafka consumer pattern uses confluent-kafka's synchronous `Consumer.poll()` in a
thread-pool executor rather than aiokafka's async consumer. This is a valid approach for
Python services with a blocking DB driver but means consumer threads are not cancelled by
asyncio cancellation — they rely on `_stop_event` set during lifespan shutdown. All four
consumers handle `ImportError` gracefully when confluent-kafka is absent.

---

<details>
<summary>Issues (4)</summary>

1. **Estate idempotency split** — `_handle_message` writes the inbox INSERT in one transaction,
   then calls `upsert_signal` / `resolve_signal` which open a second transaction. A crash between
   them permanently marks the event as processed without updating the signal. At current scale
   this is unlikely but architecturally inconsistent with the triage fix. Consolidate inbox
   INSERT and signal write into a single `db.tx()` block, as done in knowledge and triage.

2. **Synchronous JWKS fetch in async middleware** — `_fetch_jwks` calls `httpx.get` (blocking)
   inside `JWTMiddleware.dispatch`, which is an async function running in the asyncio event loop.
   A slow or unavailable identity-service will block the event loop thread for up to 5 s per
   miss, degrading all concurrent requests. Use `httpx.AsyncClient` with `await client.get()`
   or offload with `asyncio.to_thread`.

3. **Cap check double-spend window** — `_get_cap` reads `ai_settings` in one transaction
   (plain committed read), then the cap enforcement `SELECT FOR UPDATE` on `llm_usage` runs in a
   second. Between those two transactions, `ai_enabled` could be set to `False` or the cap
   lowered, and the request would still proceed. This is a low-probability race (settings changes
   are rare), but a single read path that fetches both `ai_settings` and locks `llm_usage` in
   the same transaction would close it cleanly.

4. **Helpdesk uses raw prompt text for LLM, redacted copy for storage** — `_build_user_prompt`
   receives `message` (original, unredacted) and concatenates prior history messages. This is
   intentional for response quality, and the LLM gateway re-redacts the prompt before logging
   (`redact_pii(prompt)` in `complete()`). However the history items in `_build_user_prompt`
   come from `content_redacted` DB columns, so `[PHONE]`/`[EMAIL]` tokens can appear verbatim
   in the next prompt. This is probably acceptable (the LLM sees redacted tokens as words, not
   PII), but worth documenting as a deliberate choice so reviewers don't flag it repeatedly.

</details>

<details>
<summary>Details</summary>

### Estate consumer idempotency split

`EstateService._handle_message` writes the inbox row in one `db.tx()` block, then returns and
calls `self.upsert_signal()` or `self.resolve_signal()`, each of which opens its own transaction.
If the process crashes after the inbox commit but before the signal write, the event is
permanently lost — the consumer will skip it on retry because `inbox_event` already has the row.
The triage fix (`_triage_with_idempotency`) solved exactly this pattern by merging both writes
into one transaction; the estate consumer should receive the same treatment. Confirmed by reading
`estate/__init__.py` lines `_handle_message` → `upsert_signal` call chain.

### Synchronous JWKS fetch

`_fetch_jwks` in `app/__init__.py` uses `httpx.get(settings.jwks_uri, timeout=5.0)` — a
synchronous call — inside `JWTMiddleware.dispatch`, which is declared `async`. In FastAPI/Starlette
the `BaseHTTPMiddleware` runs the dispatch coroutine in the asyncio event loop. A blocking
`httpx.get` during a JWKS cache miss (default cache TTL = `permission_cache_seconds`, 300 s) will
stall the event loop for up to 5 s, serialising all concurrent requests for that window. The fix
is `await asyncio.to_thread(httpx.get, ...)` or switching to `httpx.AsyncClient`. The 300 s cache
TTL means this fires infrequently in steady state, which is why it is non-blocking, but under key
rotation or restart it will be hit by every request simultaneously.

### Token cap and AI-enabled check separation

`complete()` calls `_get_cap(society_id)` — a plain committed read of `ai_settings` — then does
network I/O (LLM call), then re-enters the DB to lock `llm_usage FOR UPDATE` and enforce the cap.
The `FOR UPDATE` lock correctly prevents the concurrent double-spend of usage, but it does not
protect against a concurrent admin disabling AI or lowering the cap between `_get_cap` and the
lock. Merging `_get_cap` into the same `SELECT FOR UPDATE` transaction (or reading `ai_settings`
inside the locking transaction) would eliminate this window. The current split is a practical
trade-off (avoids holding a DB connection during the LLM call), and the race is benign in most
deployments, but the design assumption should be documented.

### Fake provider determinism and coverage

`_FAKE_RESPONSES` returns hardcoded text, token counts, and JSON payloads for `HELPDESK`,
`TRIAGE`, and `ESTATE_HEALTH` purposes. The TRIAGE response parses cleanly as valid JSON with
all required fields and a confidence above 0.4. The ESTATE_HEALTH response is valid JSON with the
expected `headline`/`summary`/`highlights` shape. The HELPDESK response is plain text. All three
are deterministic and structurally correct for downstream parsing. Unknown purposes fall back to
a generic `'I am here to help with your query.'` response with token counts (10, 10), which is
safe.

### JWT RS256 validation

`_decode_token` iterates JWKS keys, tries each with `algorithms=['RS256']`, skips
`InvalidSignatureError` to handle key rotation, and raises on any other error. Issuer is
validated via `issuer=settings.issuer`. Audience validation is opt-in: `settings.jwt_audience`
defaults to `None` (the duplicate-field bug is confirmed fixed in `config.py`), at which point
`decode_options = {'verify_aud': False}` is set. The `verify_aud=False` branch is now reachable.
The middleware correctly binds `Tenant.platform()` for unauthenticated requests — RLS on those
connections returns zero tenant rows — rather than returning 401, which is the right fail-open
posture for the health endpoint path while letting route handlers enforce presence of an active
society.

### ON CONFLICT clause correctness

- `llm_usage`: conflicts on `(society_id, day)` — consistent with the schema's unique constraint.
- `ai_settings`: conflicts on `(society_id)` — consistent.
- `kb_document`: conflicts on `(society_id, source_type, source_id)` — consistent; the
  `source_id IS NULL` path uses a plain INSERT with no conflict clause, which is correct because
  NULL values do not match in unique indexes.
- `triage_suggestion`: conflicts on `(society_id, complaint_id)` — consistent.
- `estate_signal`: conflicts on `(society_id, kind, ref_id)` — consistent.
- `inbox_event`: `ON CONFLICT DO NOTHING` on the primary key `(consumer, event_id)` — correct.

### Test coverage

45 unit tests pass with no external dependencies. The verification evidence does not enumerate
which modules are covered, but the import smoke test confirms all five modules initialise. Not
tested by the reported suite: JWKS fetch failure fallback under concurrent load, the estate
idempotency split scenario, the cap enforcement race between two concurrent requests at the
cap boundary, and `_build_user_prompt` with redaction tokens in history.

</details>

---

## File map

<details>
<summary>Files reviewed</summary>

| File | What changed |
|---|---|
| `app/__init__.py` | App factory, JWT middleware (JWKS fetch, token decode, tenant binding), error handlers |
| `app/config.py` | Settings with single `jwt_audience: str \| None` field (duplicate removed); `EMBEDDING_DIM=384` |
| `app/llm/__init__.py` | LLM gateway with PII redaction, cap enforcement (SELECT FOR UPDATE), fake provider, admin routes |
| `app/knowledge/__init__.py` | KB document ingest, chunking, vector search, community.notice Kafka consumer |
| `app/helpdesk/__init__.py` | RAG chat, conversation persistence with redaction, history using Tenant.system() |
| `app/triage/__init__.py` | Complaint classification, idempotency + suggestion in single tx, ai.classification.suggested publish |
| `app/estate/__init__.py` | Estate signal read-model, health score computation, LLM narration, multi-topic Kafka consumer |
| `app/platform/db.py` | Connection pool, `db.tx()` with RLS SET LOCAL, `vector_literal()` |
| `app/platform/events.py` | Outbox INSERT with CloudEvents envelope, transaction guard |
| `app/platform/tenant.py` | Tenant dataclass, context var, `Tenant.system()` / `Tenant.platform()` |
| `app/platform/ids.py` | UUIDv7 generator |
| `requirements.txt` | Pinned dependencies; sentence-transformers commented out |

</details>
