-- media-service: one row per stored object (original or thumbnail). Other services store only media_file.id.

CREATE TABLE media_file (
  id              UUID PRIMARY KEY,
  society_id      UUID        NOT NULL,
  parent_id       UUID REFERENCES media_file (id) ON DELETE CASCADE,  -- set for thumbnails
  purpose         TEXT        NOT NULL,
  owner_service   TEXT        NOT NULL,
  content_type    TEXT        NOT NULL,
  declared_size   BIGINT      NOT NULL CHECK (declared_size > 0),
  size_bytes      BIGINT,
  file_name       TEXT,
  object_key      TEXT        NOT NULL,
  status          TEXT        NOT NULL CHECK (status IN ('PENDING', 'UPLOADED', 'READY', 'REJECTED', 'EXPIRED', 'DELETED')),
  scan_status     TEXT        NOT NULL CHECK (scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'SKIPPED')),
  reject_reason   TEXT,
  width           INT,
  height          INT,
  thumbnail_id    UUID,
  upload_expires_at TIMESTAMPTZ NOT NULL,
  uploaded_at     TIMESTAMPTZ,
  processed_at    TIMESTAMPTZ,
  retain_until    TIMESTAMPTZ,
  deleted_at      TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT      NOT NULL DEFAULT 0,
  CONSTRAINT ux_media_file_object_key UNIQUE (object_key)
);
CREATE INDEX ix_media_file_status ON media_file (society_id, status, created_at);
CREATE INDEX ix_media_file_retain ON media_file (society_id, retain_until) WHERE retain_until IS NOT NULL AND status = 'READY';
CREATE INDEX ix_media_file_parent ON media_file (parent_id) WHERE parent_id IS NOT NULL;
SELECT sos_enable_tenant_rls('media_file');

-- Per-society retention copied from society.settings.updated (visitorRetentionDays).
CREATE TABLE media_society_settings (
  id                     UUID PRIMARY KEY,
  society_id             UUID NOT NULL,
  visitor_retention_days INT  NOT NULL CHECK (visitor_retention_days > 0),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_media_society_settings UNIQUE (society_id)
);
SELECT sos_enable_tenant_rls('media_society_settings');

-- Cross-tenant scans for db-scheduler jobs: return society ids only; the jobs then open one
-- RLS-scoped transaction per society.
CREATE OR REPLACE FUNCTION media_societies_with_work(p_now TIMESTAMPTZ)
  RETURNS TABLE (society_id UUID)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
  SELECT DISTINCT m.society_id FROM media_file m
   WHERE m.status = 'UPLOADED'
      OR (m.status = 'PENDING' AND m.upload_expires_at < p_now)
      OR (m.status = 'READY' AND m.retain_until < p_now)
$$;

-- db-scheduler (ADR-0004): infrastructure table, no RLS.
CREATE TABLE scheduled_tasks (
  task_name            TEXT NOT NULL,
  task_instance        TEXT NOT NULL,
  task_data            BYTEA,
  execution_time       TIMESTAMPTZ NOT NULL,
  picked               BOOLEAN NOT NULL,
  picked_by            TEXT,
  last_success         TIMESTAMPTZ,
  last_failure         TIMESTAMPTZ,
  consecutive_failures INT,
  last_heartbeat       TIMESTAMPTZ,
  version              BIGINT NOT NULL,
  priority             SMALLINT,
  PRIMARY KEY (task_name, task_instance)
);
CREATE INDEX ix_scheduled_tasks_execution_time ON scheduled_tasks (execution_time);
CREATE INDEX ix_scheduled_tasks_last_heartbeat ON scheduled_tasks (last_heartbeat);
CREATE INDEX ix_scheduled_tasks_priority ON scheduled_tasks (priority DESC, execution_time ASC);
