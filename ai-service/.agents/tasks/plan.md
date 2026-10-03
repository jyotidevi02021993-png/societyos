# SocietyOS AI Service — Implementation Plan

> Grounded in exploration of: `app/config.py`, `app/platform/{db,tenant,events,ids,migrations}.py`,
> `migrations/V1__ai.sql`, installed packages in `.venv`, and the monorepo steering doc.

---

## Environment Facts

- **Python**: 3.12, venv at `ai-service/.venv`
- **Installed packages**: `fastapi 0.141`, `psycopg 3.3.6` + `psycopg_pool`, `pydantic-settings 2.15`,
  `anthropic 1.8`, `confluent_kafka 2.15`, `pyjwt 2.15`, `cryptography 50`, `pytest 9.1`,
  `uvicorn 0.54`, `httpx 0.28`, `anyio 4.15`
- **No aiokafka installed** — Kafka consumers must use `confluent_kafka` (async wrapper via `asyncio.get_event_loop().run_in_executor` or `confluent_kafka.Consumer` in a thread).
- **No `pytest-asyncio`** — async tests need `anyio` with `pytest.mark.anyio` or sync wrappers.
- **No `sentence-transformers`** — embeddings default to `hashing` provider (always use `EMBEDDINGS_PROVIDER=hashing` in tests).
- **Run command**: `python -m pytest tests/` from `ai-service/` with venv active.
- **Import smoke**: `python -c "from app import create_app; print('import OK')"` from `ai-service/`.

---

## Implementation Sequence

Dependencies flow: `llm` → `knowledge` (uses llm for embeddings via config, but no direct import) → `helpdesk` (imports llm + knowledge) → `triage` (imports llm) → `estate` (imports llm) → `app` (imports all).

---

## Plan

- [ ] 1. **Implement `app/llm/__init__.py` — LLM Gateway**

  The gateway is the only module that calls the Anthropic API or returns fake responses. All other
  modules go through it. It also enforces the daily token cap and writes `llm_usage` +
  `llm_call_log` rows inside `db.tx()`.

  **Design decisions:**
  - `LLMGateway` is a plain class instantiated once in `create_app` and passed via FastAPI
    dependency injection (`Annotated[LLMGateway, Depends(...)]`). No global singleton — makes
    testing simple.
  - PII redaction (phone numbers and emails) is applied to both `prompt` and `response` before
    writing to `llm_call_log`. It is also a module-level function `redact_pii(text: str) -> str`
    so `helpdesk` and `triage` can call it before storing `conversation_message`.
  - Fake provider returns deterministic strings keyed on `purpose` — no randomness, no API key.
  - Token cap check and usage upsert happen atomically inside `db.tx()` with
    `INSERT ... ON CONFLICT (society_id, day) DO UPDATE SET input_tokens = llm_usage.input_tokens + EXCLUDED.input_tokens`.

  **File**: `app/llm/__init__.py`

  **Signatures to implement**:
  ```python
  import re, uuid
  from dataclasses import dataclass
  from app.platform.db import Database
  from app.platform.ids import uuid7
  from app.config import Settings

  # --- PII redaction (used by helpdesk and triage too) ---
  _PHONE_RE = re.compile(r'(\+91[\-\s]?)?[6-9]\d{9}')
  _EMAIL_RE = re.compile(r'[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}')

  def redact_pii(text: str) -> str:
      """Strip Indian phone numbers and email addresses. Returns redacted copy."""
      text = _PHONE_RE.sub('[PHONE]', text)
      text = _EMAIL_RE.sub('[EMAIL]', text)
      return text

  @dataclass
  class LLMResponse:
      text: str
      input_tokens: int
      output_tokens: int
      model: str

  class LLMGateway:
      def __init__(self, settings: Settings, db: Database) -> None: ...

      async def complete(
          self,
          *,
          purpose: str,           # 'HELPDESK' | 'TRIAGE' | 'ESTATE_HEALTH'
          society_id: uuid.UUID,
          prompt: str,
          system: str | None = None,
      ) -> LLMResponse:
          """Check token cap, call provider, upsert llm_usage, insert llm_call_log.
          Raises HTTP 503 if AI is disabled or cap exceeded (outcome='CAPPED').
          Raises HTTP 503 on provider error (outcome='ERROR').
          All writes use db.tx() with Tenant.system(society_id).
          """
          ...

      def _check_and_reserve(self, conn, society_id: uuid.UUID, estimated_input: int) -> None:
          """SELECT ai_settings + llm_usage for today; raise 503 if disabled or over cap."""
          ...

      def _upsert_usage(self, conn, society_id: uuid.UUID, input_tokens: int, output_tokens: int) -> None:
          """INSERT ... ON CONFLICT DO UPDATE to atomically accumulate daily usage."""
          ...

      def _log_call(self, conn, society_id: uuid.UUID, purpose: str, model: str,
                    prompt_redacted: str, response_redacted: str | None,
                    input_tokens: int, output_tokens: int, outcome: str) -> None:
          """Insert into llm_call_log. prompt_redacted already has PII stripped."""
          ...

      async def _fake_complete(self, purpose: str, prompt: str) -> LLMResponse:
          """Deterministic responses: HELPDESK returns a canned answer, TRIAGE returns
          '{"category":"General","department":"Admin","priority":"P3","confidence":0.85}',
          ESTATE_HEALTH returns a canned JSON summary."""
          ...

      async def _anthropic_complete(self, prompt: str, system: str | None) -> LLMResponse:
          """Call anthropic.Anthropic(api_key=...).messages.create(). httpx timeout from settings."""
          ...
  ```

  **Files**: `app/llm/__init__.py`

  **Verify**: 
  ```
  cd "e:\socity app\societyos\ai-service"
  .venv\Scripts\python -c "from app.llm import LLMGateway, redact_pii; print(redact_pii('call 9876543210 or test@example.com'))"
  ```
  Expected output: `call [PHONE] or [EMAIL]`

---

- [ ] 2. **Implement `app/knowledge/__init__.py` — Knowledge Base (RAG)**

  Handles document ingestion (chunking + embedding), vector similarity search, and a Kafka
  consumer for `community.notice.published` events.

  **Design decisions:**
  - Embeddings: use `EMBEDDINGS_PROVIDER` setting. `hashing` provider uses a 384-dim
    deterministic hash (sum of UTF-8 bytes mod 1 per dimension, normalised to [-1, 1] range)
    — no model download needed. `sentence-transformers` provider would call
    `sentence_transformers.SentenceTransformer(settings.embeddings_model).encode(text)` but
    since the package is not installed, guard with `try/import` and fall back to hashing.
  - Chunking: sliding window at `settings.chunk_chars` characters, split on sentence
    boundaries (`. ` / `\n`) where possible. Overlap of ~10% between consecutive chunks.
  - Vector search: `SELECT ... ORDER BY embedding <=> %s::vector LIMIT %s` with cosine distance.
    Filter by `audience_roles` (empty means all) and `audience_tower_ids` (empty means all).
  - Kafka consumer uses `confluent_kafka.Consumer` in a background thread managed by asyncio
    `run_in_executor`. Idempotency via `inbox_event` table.
  - `KnowledgeService` is instantiated in `create_app` and kept on `app.state`.

  **File**: `app/knowledge/__init__.py`

  **Signatures to implement**:
  ```python
  import uuid, hashlib
  from dataclasses import dataclass
  from app.platform.db import Database, vector_literal
  from app.platform.ids import uuid7
  from app.platform import tenant as tenant_ctx
  from app.platform.tenant import Tenant
  from app.config import Settings, EMBEDDING_DIM

  @dataclass
  class ChunkResult:
      chunk_id: uuid.UUID
      document_id: uuid.UUID
      title: str
      content: str
      score: float          # cosine similarity (1 - distance)

  def embed(text: str, settings: Settings) -> list[float]:
      """Return a 384-dim float list. Uses hashing provider always (sentence-transformers not installed)."""
      ...

  def chunk_text(text: str, chunk_chars: int) -> list[str]:
      """Split text into chunks of ~chunk_chars, preferring sentence boundaries."""
      ...

  class KnowledgeService:
      def __init__(self, settings: Settings, db: Database) -> None: ...

      def ingest_document(
          self,
          *,
          society_id: uuid.UUID,
          source_type: str,       # 'NOTICE' | 'FAQ' | 'BYLAW' | 'DOCUMENT'
          source_id: uuid.UUID | None,
          title: str,
          body: str,
          audience_roles: list[str],
          audience_tower_ids: list[uuid.UUID],
      ) -> uuid.UUID:
          """Upsert kb_document (by society+source_type+source_id), delete+re-insert kb_chunk rows.
          All in one db.tx(). Returns document_id.
          Uses INSERT ... ON CONFLICT (society_id, source_type, source_id) DO UPDATE for upsert.
          """
          ...

      def search(
          self,
          *,
          query: str,
          society_id: uuid.UUID,
          user_roles: list[str],
          tower_id: uuid.UUID | None,
          top_k: int | None = None,
      ) -> list[ChunkResult]:
          """Embed query, cosine search kb_chunk, filter audience, return top-k above min_score."""
          ...

      def archive_document(self, *, society_id: uuid.UUID, document_id: uuid.UUID) -> None:
          """Set kb_document.status = 'ARCHIVED' inside db.tx()."""
          ...

      async def start_consumer(self) -> None:
          """Start confluent_kafka.Consumer in asyncio executor; group 'ai-knowledge'.
          Listens on 'sos.community.events.v1'.
          On 'community.notice.published': call ingest_document with Tenant.system(society_id).
          Idempotency: INSERT INTO inbox_event (consumer, event_id) ON CONFLICT DO NOTHING; skip if conflict.
          """
          ...

      async def stop_consumer(self) -> None:
          """Signal the consumer loop to stop and await its thread."""
          ...
  ```

  **Files**: `app/knowledge/__init__.py`

  **Verify**:
  ```
  cd "e:\socity app\societyos\ai-service"
  .venv\Scripts\python -c "from app.knowledge import KnowledgeService, embed, chunk_text; v=embed('hello',__import__('app.config',fromlist=['get_settings']).get_settings()); print(len(v), 'dims')"
  ```
  Expected: `384 dims`

---

- [ ] 3. **Implement `app/helpdesk/__init__.py` — Helpdesk Chat**

  RAG chatbot: takes a user message, searches the knowledge base, builds a prompt with context
  chunks, calls the LLM, stores the redacted conversation, and optionally emits a domain event.

  **Design decisions:**
  - PII is stripped from user messages AND LLM responses before any DB write using
    `llm.redact_pii()`. The column is `content_redacted`.
  - Citations are stored as `[{"chunk_id": "...", "document_id": "...", "title": "..."}]` JSONB.
  - Proposal field is set when the LLM response includes a structured action (e.g. raise complaint).
    Parse with a simple heuristic: if response JSON contains `{"action": ...}` wrap it in proposal.
  - A new `conversation` row is auto-created on the first message if no `conversation_id` is
    supplied. Returns `conversation_id` so the client can thread subsequent messages.
  - Domain event `ai.conversation.message.created` is published via `events.publish()` inside
    the same `db.tx()` as the message insert.
  - Kafka consumer listens on `sos.ticket.events.v1` for `ticket.complaint.created` — no action
    needed beyond idempotency (future: auto-suggest KB articles). Consumer group `ai-helpdesk`.

  **File**: `app/helpdesk/__init__.py`

  **Signatures to implement**:
  ```python
  import uuid
  from dataclasses import dataclass
  from app.platform.db import Database
  from app.platform.events import DomainEvent, publish
  from app.platform.ids import uuid7
  from app.platform import tenant as tenant_ctx
  from app.config import Settings
  from app.llm import LLMGateway, redact_pii
  from app.knowledge import KnowledgeService, ChunkResult

  @dataclass
  class ChatResponse:
      conversation_id: uuid.UUID
      message_id: uuid.UUID
      content: str
      citations: list[dict]
      proposal: dict | None

  class HelpdeskService:
      def __init__(self, settings: Settings, db: Database,
                   llm: LLMGateway, knowledge: KnowledgeService) -> None: ...

      async def chat(
          self,
          *,
          society_id: uuid.UUID,
          user_id: uuid.UUID,
          user_roles: list[str],
          tower_id: uuid.UUID | None,
          conversation_id: uuid.UUID | None,
          message: str,
      ) -> ChatResponse:
          """
          1. Redact PII from message.
          2. If no conversation_id: INSERT conversation row inside db.tx().
          3. INSERT conversation_message (role=USER, content_redacted=redacted_message).
          4. knowledge.search() for RAG context chunks.
          5. Build system prompt + user prompt with context.
          6. llm.complete(purpose='HELPDESK', society_id=society_id, prompt=...).
          7. Redact PII from LLM response.
          8. Parse citations and proposal from response.
          9. INSERT conversation_message (role=ASSISTANT, content_redacted=..., citations=..., proposal=...).
          10. events.publish(conn, DomainEvent('ai.conversation.message.created', conversation_id, {...}))
              inside the same db.tx() as step 9.
          All DB writes use db.tx(Tenant.system(society_id)).
          """
          ...

      def get_history(
          self,
          *,
          society_id: uuid.UUID,
          conversation_id: uuid.UUID,
          limit: int = 20,
      ) -> list[dict]:
          """SELECT conversation_message ORDER BY created_at. Returns list of role+content dicts."""
          ...

      async def start_consumer(self) -> None:
          """confluent_kafka.Consumer group 'ai-helpdesk', topic 'sos.ticket.events.v1'.
          On 'ticket.complaint.created': idempotency check only (no-op for now).
          Idempotency: inbox_event INSERT ON CONFLICT DO NOTHING.
          """
          ...

      async def stop_consumer(self) -> None: ...
  ```

  **Files**: `app/helpdesk/__init__.py`

  **Verify**:
  ```
  cd "e:\socity app\societyos\ai-service"
  .venv\Scripts\python -c "from app.helpdesk import HelpdeskService; print('helpdesk import OK')"
  ```

---

- [ ] 4. **Implement `app/triage/__init__.py` — Complaint Triage**

  Classifies a complaint text into a `triage_category` (or uses built-in defaults), assigns
  priority, and writes a `triage_suggestion` row. Two methods: LLM and RULES.

  **Design decisions:**
  - Try LLM first (`method='LLM'`). If `confidence >= settings.triage_auto_apply_threshold`,
    mark method='LLM'. If LLM unavailable (503/cap) fall back to RULES.
  - RULES method: score each `triage_category` by keyword overlap with the complaint text
    (case-insensitive substring count). Category with the highest score wins.
    If no society categories exist, use a hardcoded default taxonomy (plumbing/electrical/
    security/general).
  - LLM prompt asks for JSON `{"category": "...", "department": "...", "priority": "P1-P4",
    "confidence": 0.0–1.0}`. Parse with `json.loads`; validate fields strictly.
  - `triage_suggestion` has `UNIQUE(society_id, complaint_id)` — use
    `INSERT ... ON CONFLICT (society_id, complaint_id) DO UPDATE` for re-triage.
  - Domain event `ai.classification.suggested` published inside `db.tx()`. Ticket-service
    consumes this event (see CATALOGUE.md).
  - Kafka consumer on `sos.ticket.events.v1` for `ticket.complaint.created`. Group `ai-triage`.
    Consumer calls `triage_complaint()` using `Tenant.system(society_id)`.

  **File**: `app/triage/__init__.py`

  **Signatures to implement**:
  ```python
  import uuid, json
  from dataclasses import dataclass
  from app.platform.db import Database
  from app.platform.events import DomainEvent, publish
  from app.platform.ids import uuid7
  from app.platform.tenant import Tenant
  from app.config import Settings
  from app.llm import LLMGateway, redact_pii

  _DEFAULT_CATEGORIES = [
      {"name": "Plumbing", "department": "Maintenance", "priority": "P3",
       "keywords": ["water", "pipe", "leak", "tap", "drain", "plumbing"]},
      {"name": "Electrical", "department": "Maintenance", "priority": "P2",
       "keywords": ["light", "power", "electric", "switch", "fuse", "wiring"]},
      {"name": "Security", "department": "Security", "priority": "P1",
       "keywords": ["theft", "break", "trespass", "suspicious", "cctv", "guard"]},
      {"name": "Housekeeping", "department": "Housekeeping", "priority": "P3",
       "keywords": ["garbage", "clean", "dirty", "sweep", "trash", "waste"]},
      {"name": "General", "department": "Admin", "priority": "P3", "keywords": []},
  ]

  @dataclass
  class TriageResult:
      suggestion_id: uuid.UUID
      category_name: str
      department: str
      priority: str     # 'P1'|'P2'|'P3'|'P4'
      confidence: float
      method: str       # 'LLM'|'RULES'

  class TriageService:
      def __init__(self, settings: Settings, db: Database, llm: LLMGateway) -> None: ...

      async def triage_complaint(
          self,
          *,
          society_id: uuid.UUID,
          complaint_id: uuid.UUID,
          text: str,
      ) -> TriageResult:
          """
          1. Load triage_category rows for society via db.tx().
          2. Try LLM triage; on 503 or parse error fall back to RULES.
          3. Upsert triage_suggestion via INSERT ... ON CONFLICT DO UPDATE.
          4. Publish ai.classification.suggested event inside db.tx().
          Returns TriageResult.
          """
          ...

      async def _llm_triage(
          self, society_id: uuid.UUID, text: str, categories: list[dict]
      ) -> dict:
          """Build prompt with category list, call llm.complete(purpose='TRIAGE', ...),
          parse JSON response. Raises ValueError on bad JSON."""
          ...

      def _rules_triage(self, text: str, categories: list[dict]) -> dict:
          """Keyword-overlap scoring. Returns best matching category dict."""
          ...

      async def start_consumer(self) -> None:
          """confluent_kafka.Consumer group 'ai-triage', topic 'sos.ticket.events.v1'.
          On 'ticket.complaint.created': extract complaint_id, society_id, description
          from event data; call triage_complaint with Tenant.system(society_id).
          Idempotency: inbox_event INSERT ON CONFLICT DO NOTHING.
          """
          ...

      async def stop_consumer(self) -> None: ...
  ```

  **Files**: `app/triage/__init__.py`

  **Verify**:
  ```
  cd "e:\socity app\societyos\ai-service"
  .venv\Scripts\python -c "from app.triage import TriageService; print('triage import OK')"
  ```

---

- [ ] 5. **Implement `app/estate/__init__.py` — Estate Health**

  Materialises `estate_signal` rows from Kafka events (ticket, asset, workflow, utility,
  compliance), and generates `estate_health_summary` on demand or on a trigger.

  **Design decisions:**
  - Signal upsert uses `INSERT ... ON CONFLICT (society_id, kind, ref_id) DO UPDATE SET
    resolved_at = EXCLUDED.resolved_at, priority = EXCLUDED.priority, updated_at = now()`.
    This handles both new signals and resolution events (set `resolved_at`).
  - Health summary generation: count open signals by kind+priority, compute a score (100 -
    weighted penalty per open signal), determine status (GOOD ≥ 70, WATCH ≥ 40, CRITICAL < 40).
    If LLM available, ask it to narrate the headline + summary from the metrics JSON. Otherwise
    use RULES method with a template string.
  - `generate_summary()` is called via HTTP endpoint (POST /v1/estate/{society_id}/health) and
    also from within the Kafka consumer after every 10 new signals (simple counter).
  - Kafka consumer group `ai-estate`. Listens on four topics:
    - `sos.ticket.events.v1` → `ticket.complaint.created` (kind=COMPLAINT),
      `workflow.sla.breached` (kind=SLA_BREACH)
    - `sos.asset.events.v1` → `asset.pmtask.overdue` (kind=BREAKDOWN)
    - `sos.utility.events.v1` → `utility.reading.anomaly` (kind=READING_ANOMALY)
    - `sos.compliance.events.v1` → `compliance.cert.expiring` (kind=CERT_EXPIRING),
      `compliance.cert.expired` (kind=CERT_EXPIRED)
  - Since `confluent_kafka.Consumer` subscribes to a list of topics, use one consumer with
    `subscribe([list of topics])`.

  **File**: `app/estate/__init__.py`

  **Signatures to implement**:
  ```python
  import uuid, json
  from dataclasses import dataclass
  from datetime import datetime
  from app.platform.db import Database
  from app.platform.events import DomainEvent, publish
  from app.platform.ids import uuid7
  from app.platform.tenant import Tenant
  from app.config import Settings
  from app.llm import LLMGateway

  # Weight table for health score: (kind, priority) → penalty points (deducted from 100)
  _PENALTIES = {
      ('CERT_EXPIRED', None): 15, ('CERT_EXPIRING', None): 5,
      ('COMPLAINT', 'P1'): 10, ('COMPLAINT', 'P2'): 5, ('COMPLAINT', 'P3'): 2,
      ('SLA_BREACH', None): 8, ('BREAKDOWN', None): 10,
      ('INCIDENT', None): 12, ('READING_ANOMALY', None): 4,
      ('CHECKLIST_FAILED', None): 3, ('STOCK_LOW', None): 2,
  }

  @dataclass
  class HealthSummary:
      summary_id: uuid.UUID
      score: int
      status: str          # 'GOOD'|'WATCH'|'CRITICAL'
      headline: str
      summary: str
      highlights: list[dict]
      metrics: dict
      method: str          # 'LLM'|'RULES'

  class EstateService:
      def __init__(self, settings: Settings, db: Database, llm: LLMGateway) -> None: ...

      def upsert_signal(
          self,
          *,
          society_id: uuid.UUID,
          kind: str,
          ref_id: uuid.UUID,
          priority: str | None,
          label: str | None,
          occurred_at: datetime,
          resolved_at: datetime | None,
      ) -> None:
          """INSERT INTO estate_signal ... ON CONFLICT DO UPDATE. Uses db.tx(Tenant.system(society_id))."""
          ...

      async def generate_summary(self, *, society_id: uuid.UUID) -> HealthSummary:
          """
          1. Query open estate_signal counts grouped by kind+priority via db.tx().
          2. Compute score = max(0, 100 - sum of penalties for open signals).
          3. Determine status: GOOD >= 70, WATCH >= 40, CRITICAL < 40.
          4. Build metrics dict and highlights list (top 5 signal groups by penalty).
          5. Try LLM narration (purpose='ESTATE_HEALTH'); on failure use RULES template.
          6. INSERT estate_health_summary via db.tx().
          7. Publish ai.estate.health.summarised event.
          Returns HealthSummary.
          """
          ...

      def get_latest_summary(self, *, society_id: uuid.UUID) -> HealthSummary | None:
          """SELECT estate_health_summary ORDER BY as_of DESC LIMIT 1 via db.tx()."""
          ...

      async def start_consumer(self) -> None:
          """confluent_kafka.Consumer group 'ai-estate'.
          subscribe(['sos.ticket.events.v1','sos.asset.events.v1',
                     'sos.utility.events.v1','sos.compliance.events.v1']).
          Route by ce_type header to upsert_signal calls.
          Idempotency: inbox_event per event_id. After every 10 new signals trigger generate_summary.
          Uses Tenant.system(society_id) extracted from ce_societyid header.
          """
          ...

      async def stop_consumer(self) -> None: ...
  ```

  **Files**: `app/estate/__init__.py`

  **Verify**:
  ```
  cd "e:\socity app\societyos\ai-service"
  .venv\Scripts\python -c "from app.estate import EstateService; print('estate import OK')"
  ```

---

- [ ] 6. **Implement `app/__init__.py` — FastAPI App Factory**

  The `create_app()` function wires everything together: migrations, DB pool, service
  instantiation, JWT middleware, routers, and lifespan.

  **Design decisions:**
  - Use FastAPI's `lifespan` context manager (not deprecated `on_event`). Open DB pool on
    startup, start Kafka consumers (if `settings.kafka_enabled`), stop consumers and close
    pool on shutdown.
  - JWT middleware: a `fastapi.middleware` class that intercepts every request, reads the
    `Authorization: Bearer <token>` header, validates the RS256 JWT using `jwt.decode` with
    JWKS keys fetched from `settings.jwks_uri` (cached in memory for `settings.permission_cache_seconds`),
    extracts `sub` (user_id), `sid` (active_society_id), `sids` (readable), `roles`, and
    calls `tenant_ctx.run_as(Tenant(...))` via a contextvar for the duration of the request.
    Public endpoints (health check) skip JWT validation.
  - Use `pyjwt` (`import jwt`) with `cryptography` for RS256. Fetch JWKS via `httpx.get()` at
    startup and cache. Use `jwt.algorithms.RSAAlgorithm.from_jwk(key_dict)` to build the key.
  - Routers (one per module) are defined inside their respective `__init__.py` as module-level
    `router = APIRouter(prefix='/v1', tags=[...])` and registered with `app.include_router()`.
  - Error handler: register a global `RequestValidationError` → 422 handler and a catch-all
    `Exception` → 500 handler, both returning RFC 7807 `application/problem+json`.
  - All endpoints in the routers below are added in this step alongside the factory.

  **Routers to add in each module** (add them as part of this step, since the factory registers them):

  **`app/llm/__init__.py`** — add:
  ```python
  router = APIRouter(prefix='/v1/llm', tags=['llm'])

  @router.get('/settings/{society_id}')        # GET ai_settings for society
  @router.put('/settings/{society_id}')        # PUT ai_settings (manager only)
  @router.get('/usage/{society_id}')           # GET llm_usage last 30 days
  ```

  **`app/knowledge/__init__.py`** — add:
  ```python
  router = APIRouter(prefix='/v1/knowledge', tags=['knowledge'])

  @router.post('/documents')                   # POST ingest a document (title+body+meta)
  @router.delete('/documents/{document_id}')   # PATCH archive a document
  @router.post('/search')                      # POST {query, top_k} → list[ChunkResult]
  ```

  **`app/helpdesk/__init__.py`** — add:
  ```python
  router = APIRouter(prefix='/v1/helpdesk', tags=['helpdesk'])

  @router.post('/conversations')               # POST {message} → ChatResponse (new conv)
  @router.post('/conversations/{conv_id}/messages')  # POST continue conversation
  @router.get('/conversations/{conv_id}/messages')   # GET message history
  ```

  **`app/triage/__init__.py`** — add:
  ```python
  router = APIRouter(prefix='/v1/triage', tags=['triage'])

  @router.post('/complaints/{complaint_id}')   # POST {text} → TriageResult (manual trigger)
  @router.get('/complaints/{complaint_id}')    # GET existing triage_suggestion
  @router.get('/categories')                   # GET triage_category list for society
  @router.post('/categories')                  # POST create custom category
  ```

  **`app/estate/__init__.py`** — add:
  ```python
  router = APIRouter(prefix='/v1/estate', tags=['estate'])

  @router.post('/{society_id}/health')         # POST trigger summary generation → HealthSummary
  @router.get('/{society_id}/health/latest')   # GET latest estate_health_summary
  @router.get('/{society_id}/signals')         # GET open estate_signal list (paginated)
  ```

  **`app/__init__.py`** full implementation:
  ```python
  from contextlib import asynccontextmanager
  from fastapi import FastAPI, Request
  from fastapi.responses import JSONResponse
  from fastapi.exceptions import RequestValidationError

  from app.config import get_settings
  from app.platform.db import Database
  from app.platform.migrations import migrate
  from app.llm import LLMGateway, router as llm_router
  from app.knowledge import KnowledgeService, router as knowledge_router
  from app.helpdesk import HelpdeskService, router as helpdesk_router
  from app.triage import TriageService, router as triage_router
  from app.estate import EstateService, router as estate_router

  def create_app() -> FastAPI:
      settings = get_settings()

      @asynccontextmanager
      async def lifespan(app: FastAPI):
          # Migrations
          if settings.run_migrations:
              migrate(settings.db_owner_url)
          # DB pool
          db = Database(settings.db_url, settings.db_pool_max)
          db.open()
          # Services
          llm = LLMGateway(settings, db)
          knowledge = KnowledgeService(settings, db)
          helpdesk = HelpdeskService(settings, db, llm, knowledge)
          triage = TriageService(settings, db, llm)
          estate = EstateService(settings, db, llm)
          app.state.db = db
          app.state.llm = llm
          app.state.knowledge = knowledge
          app.state.helpdesk = helpdesk
          app.state.triage = triage
          app.state.estate = estate
          # Kafka consumers
          if settings.kafka_enabled:
              await knowledge.start_consumer()
              await helpdesk.start_consumer()
              await triage.start_consumer()
              await estate.start_consumer()
          yield
          # Shutdown
          if settings.kafka_enabled:
              await knowledge.stop_consumer()
              await helpdesk.stop_consumer()
              await triage.stop_consumer()
              await estate.stop_consumer()
          db.close()

      app = FastAPI(title='SocietyOS AI Service', version='1.0.0', lifespan=lifespan)

      # JWT middleware (RS256 validation, tenant context binding)
      _register_jwt_middleware(app, settings)

      # RFC 7807 error handlers
      @app.exception_handler(RequestValidationError)
      async def validation_handler(req, exc):
          return JSONResponse(status_code=422, content={
              "type": "about:blank", "title": "Validation Error",
              "status": 422, "detail": str(exc)})

      @app.exception_handler(Exception)
      async def generic_handler(req, exc):
          return JSONResponse(status_code=500, content={
              "type": "about:blank", "title": "Internal Server Error",
              "status": 500, "detail": "An unexpected error occurred"})

      # Health
      @app.get('/health', tags=['ops'])
      def health():
          return {'status': 'UP', 'service': settings.service_name}

      # Routers
      app.include_router(llm_router)
      app.include_router(knowledge_router)
      app.include_router(helpdesk_router)
      app.include_router(triage_router)
      app.include_router(estate_router)

      return app

  def _register_jwt_middleware(app: FastAPI, settings) -> None:
      """
      Middleware that:
      1. Skips /health.
      2. Reads Authorization: Bearer token.
      3. Fetches JWKS from settings.jwks_uri on first call; caches public keys.
      4. Decodes token with pyjwt (RS256, audience=None, verify issuer from settings.issuer).
      5. Extracts sub→user_id, sid→active_society_id, sids→readable_society_ids, roles.
      6. Calls tenant_ctx.run_as(Tenant(...)) — implemented as a contextvar set for request scope
         by storing tenant on request.state and running middleware with contextvars.copy_context().
      Returns 401 on missing/invalid token.
      """
      ...
  ```

  **Files**: `app/__init__.py`, plus router additions to `app/llm/__init__.py`,
  `app/knowledge/__init__.py`, `app/helpdesk/__init__.py`, `app/triage/__init__.py`,
  `app/estate/__init__.py`

  **Verify**:
  ```
  cd "e:\socity app\societyos\ai-service"
  .venv\Scripts\python -c "from app import create_app; app = create_app(); print('import OK')"
  ```
  Expected: `import OK` (no DB connection attempted at import time — only in lifespan).

---

- [ ] 7. **Write unit tests**

  Create tests that cover all business logic without requiring a running DB, Kafka, or Anthropic
  key. Use `pytest` (sync) + `anyio` for async tests.

  **Test files to create**:

  `tests/unit/test_llm_pii.py` — Tests for `redact_pii`:
  - Indian mobile numbers (`9876543210`, `+919876543210`, `+91 98765 43210`)
  - Email addresses (`user@example.com`, `test.user+tag@sub.domain.in`)
  - Mixed text with both phone and email
  - Text with no PII passes through unchanged

  `tests/unit/test_knowledge_embed.py` — Tests for `embed` and `chunk_text`:
  - `embed()` returns a list of exactly 384 floats
  - `embed()` is deterministic (same text → same vector)
  - `chunk_text()` with `chunk_chars=100` splits a 500-char string into multiple chunks
  - No chunk exceeds `chunk_chars * 1.1` characters
  - All content is preserved (concatenated chunks contain all words)

  `tests/unit/test_triage_rules.py` — Tests for `TriageService._rules_triage`:
  - "water pipe leaking" → category "Plumbing"
  - "light not working in lobby" → category "Electrical"
  - "suspicious person at gate" → category "Security"
  - Empty categories list falls back to `_DEFAULT_CATEGORIES`

  `tests/unit/test_estate_score.py` — Tests for health score calculation logic
  (extract the scoring function from `EstateService.generate_summary` into a module-level
  `compute_score(signals: list[dict]) -> tuple[int, str]` function):
  - No open signals → score 100, status GOOD
  - 1 P1 complaint → score 90, status GOOD
  - Multiple signals summing penalty > 60 → status CRITICAL
  - Score never goes below 0

  `tests/unit/test_app_factory.py` — Tests for `create_app()`:
  - `create_app()` returns a `FastAPI` instance
  - `/health` route returns 200 `{"status": "UP"}`
    (use `starlette.testclient.TestClient`, which does not require a running DB since
    lifespan is not triggered by TestClient unless `with TestClient(app) as client:`)

  **Files**: `tests/unit/test_llm_pii.py`, `tests/unit/test_knowledge_embed.py`,
  `tests/unit/test_triage_rules.py`, `tests/unit/test_estate_score.py`,
  `tests/unit/test_app_factory.py`

  Also create `tests/conftest.py` with a `settings` fixture:
  ```python
  import pytest
  from app.config import Settings

  @pytest.fixture
  def settings():
      return Settings(
          llm_provider='fake',
          kafka_enabled=False,
          run_migrations=False,
          embeddings_provider='hashing',
      )
  ```

  **Verify**:
  ```
  cd "e:\socity app\societyos\ai-service"
  .venv\Scripts\python -m pytest tests/unit/ -v
  ```
  Expected: all tests pass. No DB or network calls made.

---

## Implementation Notes

### Kafka Consumer Pattern (confluent_kafka, no aiokafka)

Since `aiokafka` is not installed, each consumer runs in a background thread via
`asyncio.get_event_loop().run_in_executor(None, self._poll_loop)`. The `_poll_loop` method
calls `consumer.poll(timeout=1.0)` in a `while not self._stop_event.is_set()` loop.
`self._stop_event = threading.Event()`.

```python
import threading, asyncio
from confluent_kafka import Consumer, KafkaException

class SomeService:
    def __init__(self, ...):
        self._consumer: Consumer | None = None
        self._stop_event = threading.Event()
        self._consumer_task = None

    async def start_consumer(self):
        self._stop_event.clear()
        loop = asyncio.get_event_loop()
        self._consumer_task = loop.run_in_executor(None, self._poll_loop)

    async def stop_consumer(self):
        self._stop_event.set()
        if self._consumer_task:
            await self._consumer_task

    def _poll_loop(self):
        conf = {
            'bootstrap.servers': self._settings.kafka_bootstrap,
            'group.id': 'ai-<module>',
            'auto.offset.reset': 'earliest',
            'enable.auto.commit': False,
        }
        consumer = Consumer(conf)
        consumer.subscribe(['sos.some.events.v1'])
        try:
            while not self._stop_event.is_set():
                msg = consumer.poll(1.0)
                if msg is None or msg.error():
                    continue
                self._handle_message(msg)
                consumer.commit(msg)
        finally:
            consumer.close()

    def _handle_message(self, msg):
        headers = dict(msg.headers() or [])
        ce_type = headers.get('ce_type', b'').decode()
        ce_society = headers.get('ce_societyid', b'').decode()
        event_id = headers.get('ce_id', b'').decode()
        # Idempotency
        with self._db.tx(Tenant.system(uuid.UUID(ce_society))) as conn:
            result = conn.execute(
                "INSERT INTO inbox_event (consumer, event_id) VALUES (%s, %s) ON CONFLICT DO NOTHING",
                ('ai-<module>', event_id)
            )
            if result.rowcount == 0:
                return  # already processed
            # ... handle event ...
```

### DB Usage Pattern

All DB access goes through `db.tx()`. Never use `db._pool` directly. The context manager
handles the transaction boundary; `conn` inside the block is a `psycopg.Connection` with
`dict_row` row factory.

```python
with self._db.tx() as conn:
    rows = conn.execute("SELECT * FROM kb_document WHERE status = 'ACTIVE'").fetchall()
    # rows is list[dict]
```

For system-actor operations (Kafka consumers, scheduled jobs):
```python
with self._db.tx(Tenant.system(society_id)) as conn:
    ...
```

### Vector Search SQL Pattern

```sql
SELECT
    c.id AS chunk_id,
    c.document_id,
    d.title,
    c.content,
    1 - (c.embedding <=> %s::vector) AS score
FROM kb_chunk c
JOIN kb_document d ON d.id = c.document_id
WHERE d.status = 'ACTIVE'
  AND (d.audience_roles = '{}' OR d.audience_roles && %s::text[])
ORDER BY c.embedding <=> %s::vector
LIMIT %s
```

Parameters: `(vector_literal(query_embedding), roles_array, vector_literal(query_embedding), top_k)`

### HTTP Error Pattern (RFC 7807)

```python
from fastapi import HTTPException

raise HTTPException(
    status_code=503,
    detail={
        "type": "about:blank",
        "title": "AI Disabled",
        "status": 503,
        "detail": "AI is disabled for this society.",
        "code": "AI_DISABLED",
    }
)
```

Use 422 for validation errors, 404 for not found, 409 for conflict, 503 for AI disabled/capped.

---

## Final Verification

After all steps are complete:

```bash
cd "e:\socity app\societyos\ai-service"

# 1. Import smoke test
.venv\Scripts\python -c "from app import create_app; print('import OK')"

# 2. Unit tests (no DB/Kafka/API key required)
.venv\Scripts\python -m pytest tests/unit/ -v

# 3. Full test suite
.venv\Scripts\python -m pytest tests/ -v
```

Expected: `import OK` and all unit tests pass.
