-- notification-service schema: requests (dedupe), notifications (in-app inbox), deliveries with
-- their attempt log, preferences, templates and the read models it needs. Tenant tables have RLS.

-- ---------------------------------------------------------------- intake (dedupe on dedupeKey)
CREATE TABLE notification_request (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  dedupe_key      TEXT NOT NULL,
  source_event_id UUID,
  source_type     TEXT NOT NULL,                     -- e.g. billing.notification.requested
  category        TEXT NOT NULL,
  template        TEXT NOT NULL,
  priority        TEXT NOT NULL CHECK (priority IN ('HIGH', 'NORMAL')),
  recipients      INT  NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0,
  UNIQUE (society_id, dedupe_key)
);
SELECT sos_enable_tenant_rls('notification_request');
CREATE INDEX ix_notification_request_created ON notification_request (society_id, created_at);

-- ---------------------------------------------------------------- one notification per recipient
CREATE TABLE notification (
  id         UUID PRIMARY KEY,
  society_id UUID NOT NULL,
  request_id UUID NOT NULL REFERENCES notification_request (id) ON DELETE CASCADE,
  user_id    UUID NOT NULL,
  category   TEXT NOT NULL,
  template   TEXT NOT NULL,
  params     JSONB NOT NULL DEFAULT '{}',
  priority   TEXT NOT NULL,
  lang       TEXT NOT NULL DEFAULT 'en',
  title      TEXT NOT NULL,                          -- rendered for the in-app inbox
  body       TEXT NOT NULL,
  in_app     BOOLEAN NOT NULL DEFAULT false,
  read_at    TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('notification');
CREATE INDEX ix_notification_inbox ON notification (society_id, user_id, created_at DESC) WHERE in_app;
CREATE INDEX ix_notification_unread ON notification (society_id, user_id) WHERE in_app AND read_at IS NULL;
CREATE INDEX ix_notification_created ON notification (society_id, created_at);

-- ---------------------------------------------------------------- delivery per channel + attempt log
CREATE TABLE delivery (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  notification_id UUID NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
  user_id         UUID NOT NULL,
  channel         TEXT NOT NULL CHECK (channel IN ('PUSH', 'SMS', 'WHATSAPP', 'EMAIL', 'INAPP')),
  status          TEXT NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
  attempts        INT  NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ,
  deferred_reason TEXT,                               -- QUIET_HOURS
  last_error      TEXT,
  sent_at         TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0,
  UNIQUE (notification_id, channel)
);
SELECT sos_enable_tenant_rls('delivery');
CREATE INDEX ix_delivery_due ON delivery (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE delivery_attempt (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  delivery_id  UUID NOT NULL REFERENCES delivery (id) ON DELETE CASCADE,
  attempt_no   INT  NOT NULL,
  at           TIMESTAMPTZ NOT NULL,
  outcome      TEXT NOT NULL CHECK (outcome IN ('SENT', 'FAILED', 'SKIPPED')),
  provider     TEXT,
  provider_ref TEXT,
  error        TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('delivery_attempt');
CREATE INDEX ix_delivery_attempt_delivery ON delivery_attempt (delivery_id, attempt_no);

-- ---------------------------------------------------------------- preferences (per user, per society)
CREATE TABLE preference (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  user_id     UUID NOT NULL,
  quiet_start TIME,
  quiet_end   TIME,
  timezone    TEXT,
  language    TEXT,
  disabled    JSONB NOT NULL DEFAULT '{}',            -- {category or "*": [channel]}
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0,
  UNIQUE (society_id, user_id)
);
SELECT sos_enable_tenant_rls('preference');

-- ---------------------------------------------------------------- read models
-- Role holders per society (identity.role.assigned/revoked): resolves recipientRoles.
CREATE TABLE role_member (
  id         UUID PRIMARY KEY,                       -- = identity assignmentId
  society_id UUID NOT NULL,
  user_id    UUID NOT NULL,
  role_code  TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('role_member');
CREATE INDEX ix_role_member_role ON role_member (society_id, role_code);

-- Society settings used here (society.created / society.settings.updated).
CREATE TABLE society_settings (
  id                          UUID PRIMARY KEY,      -- = societyId
  society_id                  UUID NOT NULL,
  timezone                    TEXT,
  notification_retention_days INT NOT NULL DEFAULT 90,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('society_settings');

-- Global (reviewed): push tokens and language come from platform-level identity events.
CREATE TABLE device_token (
  id         UUID PRIMARY KEY,
  device_id  UUID NOT NULL UNIQUE,                   -- identity deviceId
  user_id    UUID NOT NULL,
  platform   TEXT NOT NULL,
  push_token TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version    BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_device_token_user ON device_token (user_id);

CREATE TABLE recipient_profile (
  id             UUID PRIMARY KEY,
  user_id        UUID NOT NULL UNIQUE,
  preferred_lang TEXT NOT NULL DEFAULT 'en',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version    BIGINT NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------- templates (global, en + hi)
CREATE TABLE notification_template (
  id         UUID PRIMARY KEY,
  code       TEXT NOT NULL,                          -- the request's template
  channel    TEXT NOT NULL,                          -- a channel, or ANY
  lang       TEXT NOT NULL CHECK (lang IN ('en', 'hi')),
  title      TEXT NOT NULL,
  body       TEXT NOT NULL,                          -- {{param}} placeholders
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version    BIGINT NOT NULL DEFAULT 0,
  UNIQUE (code, channel, lang)
);

-- ---------------------------------------------------------------- db-scheduler (ADR-0004)
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

-- Societies with deliveries due now, and societies holding notifications (retention purge).
-- Run as the owner so jobs find them before binding a tenant; they return ids only.
CREATE FUNCTION notification_societies_with_due_deliveries(p_now TIMESTAMPTZ)
  RETURNS TABLE (society_id UUID)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
  SELECT DISTINCT d.society_id FROM delivery d WHERE d.status = 'PENDING' AND d.next_attempt_at <= p_now
$$;

CREATE FUNCTION notification_societies_with_notifications()
  RETURNS TABLE (society_id UUID)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
  SELECT DISTINCT r.society_id FROM notification_request r
$$;
