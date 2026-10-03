# Verification Report — AI Service Review Fix Iteration

## Date
Second iteration (review findings addressed)

## Review Findings Fixed

### finding: duplicate-jwt-audience (blocking)
**Root cause:** `app/config.py` had two `jwt_audience` field definitions on consecutive lines.
The second (`str = Field("ai-service", ...)`) silently overrode the first
(`str | None = Field(None, ...)`), making audience validation always required with a hardcoded
value. The `verify_aud=False` branch in `_decode_token` was unreachable.

**Fix:** Removed the second (hardcoded) definition. Kept the first with type `str | None` and
`default=None`, preserving the documented opt-in behaviour: set `SOS_JWT_AUDIENCE` to enable
audience validation; leave unset to skip it.

**File changed:** `app/config.py`

---

### finding: triage-inbox-triage-split (blocking)
**Root cause:** `TriageService._handle_message()` committed the `inbox_event` INSERT in
transaction A, then called `triage_complaint()` in a separate transaction B. A crash between
the two writes permanently marked the Kafka event as processed while the `triage_suggestion`
row was never written. Complaints processed during that window received no AI classification.

**Fix:** Extracted a new `_triage_with_idempotency()` async method. The LLM call (network I/O)
still happens outside any transaction. The idempotency INSERT (`inbox_event`) and the
`triage_suggestion` upsert now land together in a **single transaction**. If the inbox INSERT
returns `rowcount=0` (duplicate), the transaction exits immediately without running the upsert.
A crash after the LLM call but before the commit causes both the inbox INSERT and the upsert to
roll back, so the event is safely retried on the next poll.

**File changed:** `app/triage/__init__.py`

---

### finding: history-rls-gap (blocking)
**Root cause:** `HelpdeskService._get_recent_history()` opened a DB transaction with
`Tenant.platform()`, which sets `app.society_ids='{}'`. The RLS policy on
`conversation_message` filtered out all rows silently, so every helpdesk response was generated
without any prior conversation history.

**Fix:** Added `society_id: uuid.UUID` parameter to `_get_recent_history()`. Updated the DB
transaction to use `Tenant.system(society_id)` so the RLS policy resolves the correct society
rows. Updated the call-site in `chat()` to pass `society_id` through.

**File changed:** `app/helpdesk/__init__.py`

---

## Build / Test Output

```
# Import smoke test
python -c "from app import create_app; print('import OK')"
import OK

# Unit tests (no DB / Kafka / API key)
python -m pytest tests/unit/ -v
45 passed in 0.80s

# Full test suite
python -m pytest tests/ -x -q
45 passed in 0.39s
```

All tests pass. No DB or network calls are made during the test run.
