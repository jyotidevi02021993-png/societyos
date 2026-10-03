# AI Service — Verification (review iteration)

## Import smoke test
```
$ python -c "from app import create_app; print('import OK')"
import OK
```

## Unit tests
```
$ python -m pytest tests/ -x -q
.............................................                            [100%]
45 passed in 0.53s
```

## Review findings fixed

### token-cap-race (blocking) — FIXED
`LLMGateway.complete()` was restructured:
- Old flow: `_check_enabled_and_cap()` (committed Tx A) → provider call → usage upsert (Tx B).
  Concurrent requests all read the pre-write usage value, spending beyond the cap.
- New flow: provider call happens outside any DB transaction. Then a single Tx opens with
  `SELECT ... FOR UPDATE` on the `llm_usage` row, re-checks `used >= cap`, and writes the
  usage upsert if the cap is not exceeded — all in one atomic transaction.
  The `_check_enabled_and_cap` helper was replaced by `_get_cap()` (reads ai_settings only,
  no locking needed for an on/off flag).

### no-role-guard (blocking) — FIXED
Added `t.has_role(...)` checks to all four routes that were missing them:
- `PUT /v1/llm/settings/{society_id}` — requires ADMIN, MANAGER, or SUPER_ADMIN
- `GET /v1/llm/usage/{society_id}` — requires ADMIN, MANAGER, or SUPER_ADMIN
- `POST /v1/triage/categories` — requires ADMIN, MANAGER, or SUPER_ADMIN
- `POST /v1/estate/{society_id}/health` — requires ADMIN, MANAGER, or SUPER_ADMIN
All return HTTP 403 `application/json` with RFC 7807 body on insufficient role.

### knowledge-idempotency-gap (blocking) — FIXED
`KnowledgeService._handle_message()` previously committed the `inbox_event` INSERT in its
own transaction, then called `self.ingest_document()` in a second transaction. A crash
between those two would leave the event permanently skipped on replay.

The fix inlines the document upsert (INSERT kb_document, DELETE + re-INSERT kb_chunk) directly
into the same `db.tx()` block that holds the `inbox_event` INSERT. Both commit together or
both roll back; the event is retried on the next Kafka poll if either fails.

### jwt-aud-disabled (blocking) — FIXED
Added `jwt_audience: str | None = Field(None, alias="SOS_JWT_AUDIENCE")` to `Settings`.
`_decode_token()` now uses this value as the `audience` parameter to `pyjwt.decode()`.
When set (e.g. `SOS_JWT_AUDIENCE=ai-service`), tokens issued for a different audience are
rejected. When left unset (default for local dev), `options={'verify_aud': False}` is passed
so existing dev setups continue to work. The omission is now explicit and configurable.

### cap-capped-log-flood (minor) — FIXED
The CAPPED log row that was written inside `_check_enabled_and_cap` on every cap-exceeded
request has been removed. The old design committed a `llm_call_log` row for every request
that hit the cap, causing high write volume under a flood. Cap-exceeded requests now raise
`TokenCapExceededError` immediately without any DB write. The error is visible to callers
through the 503 response.
