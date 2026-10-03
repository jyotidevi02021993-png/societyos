-- ai_db: helpdesk knowledge base (pgvector), conversations, triage, estate health, LLM gateway.
-- Every tenant table has society_id and RLS (sos_enable_tenant_rls). Text that came from
-- residents is stored only after PII redaction.

CREATE EXTENSION IF NOT EXISTS vector;

-- Per-society AI settings (Agreement §3.3: customer data is never used for training;
-- a society can switch AI off entirely).
CREATE TABLE ai_settings (
  id               UUID PRIMARY KEY,
  society_id       UUID        NOT NULL,
  ai_enabled       BOOLEAN     NOT NULL DEFAULT true,
  daily_token_cap  BIGINT,                         -- NULL: service default
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT      NOT NULL DEFAULT 0,
  CONSTRAINT ux_ai_settings_society UNIQUE (society_id)
);
SELECT sos_enable_tenant_rls('ai_settings');

-- Knowledge base: notices (from community events), FAQs and by-laws (added by managers).
CREATE TABLE kb_document (
  id                 UUID PRIMARY KEY,
  society_id         UUID        NOT NULL,
  source_type        TEXT        NOT NULL CHECK (source_type IN ('NOTICE', 'FAQ', 'BYLAW', 'DOCUMENT')),
  source_id          UUID,                         -- noticeId etc.; NULL for manual entries
  title              TEXT        NOT NULL,
  audience_roles     TEXT[]      NOT NULL DEFAULT '{}',  -- empty: every member
  audience_tower_ids UUID[]      NOT NULL DEFAULT '{}',  -- empty: every tower
  status             TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by         UUID,
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by         UUID,
  version            BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_kb_document_source ON kb_document (society_id, source_type, source_id)
  WHERE source_id IS NOT NULL;
SELECT sos_enable_tenant_rls('kb_document');

CREATE TABLE kb_chunk (
  id           UUID PRIMARY KEY,
  society_id   UUID        NOT NULL,
  document_id  UUID        NOT NULL REFERENCES kb_document (id) ON DELETE CASCADE,
  ordinal      INT         NOT NULL,
  content      TEXT        NOT NULL,
  embedding    vector(384) NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_kb_chunk_society_document ON kb_chunk (society_id, document_id);
CREATE INDEX ix_kb_chunk_embedding ON kb_chunk USING hnsw (embedding vector_cosine_ops);
SELECT sos_enable_tenant_rls('kb_chunk');

-- Helpdesk conversations (content is redacted before it is stored).
CREATE TABLE conversation (
  id          UUID PRIMARY KEY,
  society_id  UUID        NOT NULL,
  user_id     UUID        NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_conversation_society_user ON conversation (society_id, user_id, created_at DESC);
SELECT sos_enable_tenant_rls('conversation');

CREATE TABLE conversation_message (
  id               UUID PRIMARY KEY,
  society_id       UUID        NOT NULL,
  conversation_id  UUID        NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
  role             TEXT        NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
  content_redacted TEXT        NOT NULL,
  citations        JSONB       NOT NULL DEFAULT '[]',
  proposal         JSONB,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_conversation_message_conv ON conversation_message (society_id, conversation_id, created_at);
SELECT sos_enable_tenant_rls('conversation_message');

-- Complaint triage: the society's categories (optional; a default taxonomy is built in)
CREATE TABLE triage_category (
  id               UUID PRIMARY KEY,
  society_id       UUID        NOT NULL,
  name             TEXT        NOT NULL,
  department       TEXT        NOT NULL,
  default_priority TEXT        NOT NULL DEFAULT 'P3' CHECK (default_priority IN ('P1', 'P2', 'P3', 'P4')),
  keywords         TEXT[]      NOT NULL DEFAULT '{}',
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_triage_category_name ON triage_category (society_id, lower(name));
SELECT sos_enable_tenant_rls('triage_category');

CREATE TABLE triage_suggestion (
  id             UUID PRIMARY KEY,
  society_id     UUID        NOT NULL,
  complaint_id   UUID        NOT NULL,
  category_name  TEXT        NOT NULL,
  department     TEXT        NOT NULL,
  priority       TEXT        NOT NULL CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
  confidence     NUMERIC(4, 3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
  method         TEXT        NOT NULL CHECK (method IN ('LLM', 'RULES')),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by     UUID,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by     UUID,
  version        BIGINT      NOT NULL DEFAULT 0,
  CONSTRAINT ux_triage_suggestion_complaint UNIQUE (society_id, complaint_id)
);
SELECT sos_enable_tenant_rls('triage_suggestion');

-- Estate signals: a small read model built from other services' events, input to the
-- Estate Health Summary. Holds ids, kinds and counts only (no resident text).
CREATE TABLE estate_signal (
  id           UUID PRIMARY KEY,
  society_id   UUID        NOT NULL,
  kind         TEXT        NOT NULL CHECK (kind IN ('COMPLAINT', 'BREAKDOWN', 'INCIDENT', 'SLA_BREACH',
                                                   'READING_ANOMALY', 'CHECKLIST_FAILED', 'STOCK_LOW',
                                                   'CERT_EXPIRING', 'CERT_EXPIRED')),
  ref_id       UUID        NOT NULL,
  priority     TEXT,
  label        TEXT,                               -- category / metric / certificate name
  occurred_at  TIMESTAMPTZ NOT NULL,
  resolved_at  TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT      NOT NULL DEFAULT 0,
  CONSTRAINT ux_estate_signal_ref UNIQUE (society_id, kind, ref_id)
);
CREATE INDEX ix_estate_signal_open ON estate_signal (society_id, kind) WHERE resolved_at IS NULL;
SELECT sos_enable_tenant_rls('estate_signal');

CREATE TABLE estate_health_summary (
  id           UUID PRIMARY KEY,
  society_id   UUID        NOT NULL,
  as_of        TIMESTAMPTZ NOT NULL,
  score        INT         NOT NULL CHECK (score BETWEEN 0 AND 100),
  status       TEXT        NOT NULL CHECK (status IN ('GOOD', 'WATCH', 'CRITICAL')),
  headline     TEXT        NOT NULL,
  summary      TEXT        NOT NULL,
  highlights   JSONB       NOT NULL DEFAULT '[]',
  metrics      JSONB       NOT NULL,
  method       TEXT        NOT NULL CHECK (method IN ('LLM', 'RULES')),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_estate_health_summary_latest ON estate_health_summary (society_id, as_of DESC);
SELECT sos_enable_tenant_rls('estate_health_summary');

-- LLM gateway: per-society daily usage (cost caps) and a redacted call log.
CREATE TABLE llm_usage (
  id             UUID PRIMARY KEY,
  society_id     UUID        NOT NULL,
  day            DATE        NOT NULL,
  input_tokens   BIGINT      NOT NULL DEFAULT 0,
  output_tokens  BIGINT      NOT NULL DEFAULT 0,
  calls          INT         NOT NULL DEFAULT 0,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by     UUID,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by     UUID,
  version        BIGINT      NOT NULL DEFAULT 0,
  CONSTRAINT ux_llm_usage_day UNIQUE (society_id, day)
);
SELECT sos_enable_tenant_rls('llm_usage');

CREATE TABLE llm_call_log (
  id                 UUID PRIMARY KEY,
  society_id         UUID        NOT NULL,
  purpose            TEXT        NOT NULL,         -- HELPDESK | TRIAGE | ESTATE_HEALTH
  model              TEXT        NOT NULL,
  prompt_redacted    TEXT        NOT NULL,
  response_redacted  TEXT,
  input_tokens       INT         NOT NULL DEFAULT 0,
  output_tokens      INT         NOT NULL DEFAULT 0,
  outcome            TEXT        NOT NULL CHECK (outcome IN ('OK', 'ERROR', 'REFUSED', 'CAPPED')),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by         UUID,
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by         UUID,
  version            BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_llm_call_log_society ON llm_call_log (society_id, created_at DESC);
SELECT sos_enable_tenant_rls('llm_call_log');

-- Runtime role (production: ai_app). DML only, never owner, so RLS applies.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_app') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ai_app;
    GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO ai_app;
  END IF;
END
$$;
