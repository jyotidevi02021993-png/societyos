-- Excel bulk onboarding jobs (POST /v1/imports). The file itself is not kept; the report is.

CREATE TABLE import_job (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  file_name    TEXT NOT NULL,
  dry_run      BOOLEAN NOT NULL,
  status       TEXT NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'VALIDATION_FAILED', 'COMPLETED',
                                               'COMPLETED_WITH_ERRORS', 'FAILED')),
  report       JSONB,
  finished_at  TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_import_job_society_created ON import_job (society_id, created_at DESC, id DESC);
SELECT sos_enable_tenant_rls('import_job');
