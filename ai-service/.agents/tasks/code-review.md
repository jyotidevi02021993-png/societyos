# SocietyOS AI Service — Behavioral Code Review

**2026-10-03 · Pass 1 (first review)**

The AI service is a FastAPI application consisting of four feature modules (llm, knowledge, helpdesk, triage, estate) backed by a thin platform layer. This review covers correctness of DB access patterns, PII handling, JWT validation, domain event publishing, embedding dimensions, Kafka consumer idempotency, error codes, and the fake LLM provider.

Watch for: (1) **duplicate `jwt_audience` field** in `Settings` — the second definition silently overrides the first, forcing `SOS_JWT_AUDIENCE` to default to `"ai-service"` rather than `None`; this will break deployments that intentionally omit audience validation. (2) **Triage inbox-event split** — the `inbox_event` INSERT that gates idempotency for the triage consumer is committed in a separate transaction from the `triage_complaint()` DB writes; a crash between them permanently skips the event. (3) **`_get_recent_history` uses `Tenant.platform()`** — the RLS write_society_id is empty, so the conversation_message query returns zero rows if the table has RLS enforced, silently degrading chat context with no error.

**Verdict**: NEEDS_CHANGES

---

## High-level view

The LLM gateway's cap enforcement is sound: the prior race condition (two reads before any write) was fixed; a single transaction now issues `SELECT FOR UPDATE` on `llm_usage`, re-checks the cap, and writes the usage upsert atomically. PII redaction covers both Indian mobile numbers and email addresses, and the redacted text is what lands in `llm_call_log` and `conversation_message`. The `_decode_token` path validates RS256 signatures, handles multi-key JWKS, and delegates audience checking to PyJWT — except the duplicate field in Settings means the effective default is always `"ai-service"` rather than `None`, which is arguably safer but different from the documented intent and breaks the explicit opt-out path.

Knowledge and estate consumers correctly atomise the `inbox_event` INSERT and the domain upsert in a single `db.tx()` block. The triage consumer breaks that invariant: idempotency is committed first, then `triage_complaint()` opens a second transaction. A shutdown between those two loses the event. The helpdesk consumer is idempotency-only (no further writes), so its two-step pattern is harmless but inconsistent.

The `_get_recent_history` helper in `HelpdeskService` fetches conversation context using `Tenant.platform()`, which sets `app.write_society_id` to the empty string. Whether this succeeds depends on RLS policy: if the `conversation_message` policy uses `USING (society_id = ANY(app_society_ids()))` and `app_society_ids()` returns empty on a blank setting, the query silently returns no rows. The chat still works, but every response is generated without history — a silent degradation that is hard to detect in production.

The embedding dimension is fixed at 384 via `EMBEDDING_DIM = 384` in `config.py`, and every code path that inserts or queries vectors uses `vector_literal()` with `%s::vector` casting — correct for pgvector. Role guards are present on all four protected routes (confirmed fixed per verify.md). `AIDisabledError` and `TokenCapExceededError` are caught at the app level with 503 responses. The fake provider returns deterministic, purpose-keyed responses sufficient for test coverage.

---

<details>
<summary>Issues (3)</summary>

1. **Duplicate `jwt_audience` field** — `config.py` defines `jwt_audience` twice; the second definition (`Field("ai-service", ...)`) silently overrides the first (`Field(None, ...)`). Deployments cannot opt out of audience validation by leaving `SOS_JWT_AUDIENCE` unset — the effective default is always `"ai-service"`, which will reject tokens that have no `aud` claim. Remove the first (nullable) definition and document the non-None default clearly, or remove the second and make the opt-out explicit.

2. **Triage consumer inbox/triage split** — `TriageService._handle_message()` commits the `inbox_event` INSERT in one `db.tx()` block, then calls `triage_complaint()` in a second `db.tx()`. A crash or exception after the first commit permanently marks the event as processed while skipping the actual triage write. Inline the idempotency check and the `triage_suggestion` upsert into the same transaction, as the knowledge and estate consumers do.

3. **`_get_recent_history` uses `Tenant.platform()`** — `Tenant.platform()` has no `active_society_id` and empty `readable_society_ids`, so `app.society_ids` is set to `{}`. If `conversation_message` has RLS enabled (which it will in production), this query returns zero rows silently. Use `Tenant.system(society_id)` with the conversation's owning society, or pass the tenant through from the caller.

</details>

<details>
<summary>Details</summary>

### Duplicate `jwt_audience` in Settings

`config.py` declares `jwt_audience` twice:

```python
jwt_audience: str | None = Field(None, alias="SOS_JWT_AUDIENCE")
# ...
jwt_audience: str = Field("ai-service", alias="SOS_JWT_AUDIENCE")
```

Python class bodies execute top-to-bottom; Pydantic sees the second declaration and discards the first. The effective default is the non-nullable `"ai-service"`. The `_decode_token` function has logic to set `options={'verify_aud': False}` when `settings.jwt_audience is None` — that branch is now unreachable. Any token without an `aud` claim matching `"ai-service"` is rejected, including tokens issued by the Java identity-service in default dev configurations that omit `aud`. The verify.md specifically documents this fix as making the setting configurable with `None` as the default; the duplicate field undoes that. (confirmed — both lines are visible in config.py)

### Triage inbox/triage split

In `TriageService._handle_message()`:

```python
with self._db.tx(Tenant.system(society_id)) as conn:  # Tx A
    result = conn.execute(
        "INSERT INTO inbox_event ... ON CONFLICT DO NOTHING", ...)
    if result.rowcount == 0:
        return
# Tx A committed here

loop.run_until_complete(
    self.triage_complaint(...)   # opens Tx B internally
)
```

If the process is killed after Tx A commits but before `triage_complaint` writes, the event is permanently recorded as processed but the `triage_suggestion` row is never created. The `ticket-service` consumer of `ai.classification.suggested` never receives the event. Complaints processed during that window remain manually triaged with no AI suggestion — a silent correctness gap, not just a transient failure.

The knowledge and estate consumers both inline their upsert inside the same `db.tx()` that holds the `inbox_event` INSERT (confirmed), so the pattern is established. The fix is to replicate it: open one transaction, insert `inbox_event`, run the triage DB writes, publish the domain event, commit.

The wrinkle is that `triage_complaint` also calls `self._llm.complete()` which must happen outside any DB transaction (network I/O should not hold a connection — this is noted in `llm/__init__.py`). The resolution: fetch the LLM result outside the transaction, then enter a single transaction for the idempotency insert, suggestion upsert, and event publish.

### `_get_recent_history` RLS gap

```python
def _get_recent_history(self, conversation_id: uuid.UUID) -> list[dict]:
    with self._db.tx(Tenant.platform()) as conn:
        rows = conn.execute(
            "SELECT role, content_redacted FROM conversation_message
             WHERE conversation_id = %s ...",
            (conversation_id,),
        ).fetchall()
    return list(reversed([dict(r) for r in rows]))
```

`Tenant.platform()` produces a tenant with `readable_society_ids=()`, which causes `db.tx()` to `SET LOCAL app.society_ids = '{}'`. The RLS policy on `conversation_message` — `USING (society_id = ANY(app_society_ids()))` — evaluates to `society_id = ANY('{}')`, which is always false. The query returns empty. The chat then proceeds without history, so every response is context-free. There is no error, no log warning, and no visible degradation from the client side.

The comment in the code says "the conversation_id filter is enough for correctness in practice" — this is incorrect once RLS is enforced in production. The `conversation_id` is a WHERE predicate, but RLS is an invisible additional filter that runs on the app role before the WHERE clause is evaluated.

### LLM cap enforcement — confirmed correct

The `SELECT ... FOR UPDATE` on `llm_usage` inside the same transaction as `_upsert_usage` closes the concurrent-spend race. The flow — provider call outside tx → lock row → re-check → write — correctly prevents two simultaneous requests from both reading an under-limit count before either write lands. The error-path log also correctly uses `Tenant.system(society_id)` and only fires on provider error, not on cap exceeded.

### PII redaction coverage — confirmed

`_PHONE_RE` covers `+91`-prefixed and bare 10-digit numbers starting with 6–9. `_EMAIL_RE` covers standard email patterns. Both are applied to prompt text before it enters `llm_call_log.prompt_redacted` and `conversation_message.content_redacted`. The raw message text is used only for the KB search query and LLM prompt payload, neither of which is persisted. (confirmed)

### EMBEDDING_DIM and vector casting — confirmed

`EMBEDDING_DIM = 384` is imported into `knowledge/__init__.py` and used as the loop bound in `HashingEmbeddings.embed()`. Every INSERT to `kb_chunk.embedding` uses `%s::vector` with `vector_literal()` output. Every cosine-distance query passes the query vector as `%s::vector`. The pgvector extension will enforce dimension consistency at runtime. (confirmed)

### Role guards — confirmed fixed

All four routes listed in verify.md now call `t.has_role(...)` before touching the DB. The 403 responses use an RFC 7807-shaped dict body, consistent with the rest of the service's error format. (confirmed)

### Fake LLM responses — confirmed useful

`_FAKE_RESPONSES` keys match the three `purpose` strings used by the service: `HELPDESK`, `TRIAGE`, and `ESTATE_HEALTH`. The TRIAGE response is valid JSON that `_llm_triage()` can parse. The ESTATE_HEALTH response is valid JSON that `generate_summary()` can parse. A fallback default (`'I am here to help with your query.'`) handles any unknown purpose. (confirmed)

### Kafka consumer library — confirmed (confluent-kafka, not aiokafka)

All four consumers use `confluent_kafka.Consumer` in a synchronous poll loop run via `loop.run_in_executor(None, self._poll_loop)`. The project's review brief asked about `aiokafka`; this service uses `confluent-kafka==2.15.1` instead. This is not a defect — it is a design choice. The blocking poll loop is thread-correct, and the soft import (`try: from confluent_kafka import ...`) allows the service to start without Kafka when `SOS_KAFKA_ENABLED=false`.

### Domain events via outbox — confirmed

`publish(conn, DomainEvent(...))` is the only path to the outbox. The function verifies `conn.info.transaction_status != IDLE` at runtime, preventing accidental publish outside a transaction. No direct `KafkaTemplate`-equivalent exists in Python; the architecture rule cannot be violated here by construction.

</details>

---

<details>
<summary>File map</summary>

| File | What changed |
|---|---|
| `app/__init__.py` | App factory, JWT middleware, `AIDisabledError`/`TokenCapExceededError` handlers |
| `app/config.py` | Service settings (includes duplicate `jwt_audience` field — **blocking**) |
| `app/llm/__init__.py` | LLM gateway, cap enforcement, PII redaction, role-guarded settings/usage routes |
| `app/knowledge/__init__.py` | KB document ingestion, vector search, community notice consumer |
| `app/helpdesk/__init__.py` | RAG chatbot, conversation management, ticket consumer (idempotency-only) |
| `app/triage/__init__.py` | Complaint triage (LLM + rules fallback), triage consumer — **split inbox/triage tx** |
| `app/estate/__init__.py` | Estate signal management, health summary generation, multi-topic consumer |
| `app/platform/db.py` | DB pool, `db.tx()` context manager, RLS SET LOCAL, `vector_literal()` |
| `app/platform/events.py` | `publish()`, CloudEvents envelope, outbox INSERT |
| `app/platform/ids.py` | `uuid7()` generator |
| `app/platform/tenant.py` | `Tenant` dataclass, `run_as()` context manager, `has_role()` |
| `requirements.txt` | Pinned dependencies (fastapi, uvicorn, psycopg, pyjwt, confluent-kafka) |

</details>
