-- audit-service: append-only audit trail of every domain event, monthly partitions, hash chain per society.
-- The runtime role may INSERT and SELECT only: UPDATE, DELETE and TRUNCATE are revoked on the table and
-- every partition, and a trigger refuses UPDATE/DELETE for everyone (the owner included). Retention works
-- by detaching/dropping whole partitions (DDL, migration role only).

CREATE TABLE audit_log (
  id              UUID        NOT NULL,
  society_id      UUID        NOT NULL,
  seq             BIGINT      NOT NULL,            -- per-society position in the hash chain
  event_id        UUID        NOT NULL,            -- CloudEvents id
  occurred_at     TIMESTAMPTZ NOT NULL,            -- event time (ms precision), partition key
  recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  source          TEXT,
  context         TEXT        NOT NULL,            -- topic context, e.g. security
  type            TEXT        NOT NULL,            -- e.g. security.entry.approved
  subject         TEXT,                            -- e.g. entry/0192...
  subject_type    TEXT,
  subject_id      UUID,
  actor_id        UUID,
  actor_type      TEXT,
  payload         JSONB       NOT NULL,            -- trimmed event data (no PII keys, long strings cut)
  payload_trimmed BOOLEAN     NOT NULL DEFAULT false,
  prev_hash       TEXT        NOT NULL,
  hash            TEXT        NOT NULL,
  PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX ix_audit_log_society_time ON audit_log (society_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_log_type ON audit_log (society_id, type, occurred_at DESC);
CREATE INDEX ix_audit_log_subject ON audit_log (society_id, subject_type, subject_id);
CREATE INDEX ix_audit_log_actor ON audit_log (society_id, actor_id, occurred_at DESC);
CREATE INDEX ix_audit_log_seq ON audit_log (society_id, seq);

-- Events with no society (identity.user.registered, role templates): small, not partitioned, no RLS,
-- no API yet (platform-admin only).
CREATE TABLE platform_audit_log (
  id              UUID PRIMARY KEY,
  event_id        UUID        NOT NULL UNIQUE,
  occurred_at     TIMESTAMPTZ NOT NULL,
  recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  source          TEXT,
  context         TEXT        NOT NULL,
  type            TEXT        NOT NULL,
  subject         TEXT,
  actor_id        UUID,
  actor_type      TEXT,
  payload         JSONB       NOT NULL,
  payload_trimmed BOOLEAN     NOT NULL DEFAULT false
);
CREATE INDEX ix_platform_audit_log_time ON platform_audit_log (occurred_at DESC);

-- Head of each society's chain; locked FOR UPDATE while appending so concurrent consumers serialise.
CREATE TABLE audit_chain (
  society_id UUID PRIMARY KEY,
  last_seq   BIGINT      NOT NULL,
  last_hash  TEXT        NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
SELECT sos_enable_tenant_rls('audit_chain');

-- Who exported what (exports are themselves auditable, doc 05 §7).
CREATE TABLE audit_export (
  id          UUID PRIMARY KEY,
  society_id  UUID        NOT NULL,
  format      TEXT        NOT NULL CHECK (format IN ('CSV')),
  filters     TEXT        NOT NULL,
  row_count   INT         NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_audit_export_time ON audit_export (society_id, created_at DESC);
SELECT sos_enable_tenant_rls('audit_export');

-- Chain hash: sha256 over prev_hash and the row's identifying fields and jsonb payload text.
CREATE OR REPLACE FUNCTION audit_row_hash(p_prev TEXT, p_seq BIGINT, p_event UUID, p_society UUID,
    p_at TIMESTAMPTZ, p_type TEXT, p_subject TEXT, p_actor UUID, p_payload JSONB)
  RETURNS TEXT LANGUAGE sql STABLE AS
$$
  SELECT encode(sha256(convert_to(concat_ws('|', p_prev, p_seq::text, p_event::text, p_society::text,
           to_char(p_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS'), p_type, coalesce(p_subject, ''),
           coalesce(p_actor::text, ''), p_payload::text), 'UTF8')), 'hex')
$$;

-- Nobody changes an audit row: not the app, not the owner.
CREATE OR REPLACE FUNCTION audit_refuse_change() RETURNS trigger
  LANGUAGE plpgsql AS
$$
BEGIN
  RAISE EXCEPTION 'AUDIT_APPEND_ONLY: % on % is not allowed', TG_OP, TG_TABLE_NAME
    USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER trg_audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION audit_refuse_change();
CREATE TRIGGER trg_platform_audit_log_append_only BEFORE UPDATE OR DELETE ON platform_audit_log
  FOR EACH ROW EXECUTE FUNCTION audit_refuse_change();

-- Takes UPDATE/DELETE/TRUNCATE away from every role but the owner (default privileges grant them to
-- the app role), and enables RLS: a partition queried directly must be as isolated as the parent.
CREATE OR REPLACE FUNCTION audit_lock_down(tbl regclass, with_rls BOOLEAN) RETURNS void
  LANGUAGE plpgsql AS
$$
DECLARE
  r RECORD;
BEGIN
  IF with_rls THEN
    PERFORM sos_enable_tenant_rls(tbl);
  END IF;
  EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %s FROM PUBLIC', tbl);
  FOR r IN
    SELECT DISTINCT g.grantee FROM information_schema.role_table_grants g
     WHERE g.table_schema = 'public' AND g.table_name = (SELECT relname FROM pg_class WHERE oid = tbl)
       AND g.privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE') AND g.grantee <> current_user
       AND g.grantee <> 'PUBLIC'
  LOOP
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %s FROM %I', tbl, r.grantee);
  END LOOP;
END
$$;

-- Creates the month's partition if missing. SECURITY DEFINER: the app role may call it (the
-- partition-maintenance job) but owns nothing. Returns NULL when the default partition already
-- holds rows for that month (a late replay); those rows stay in the default partition.
CREATE OR REPLACE FUNCTION audit_ensure_partition(p_month DATE) RETURNS TEXT
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
DECLARE
  start_d DATE := date_trunc('month', p_month)::date;
  end_d   DATE := (date_trunc('month', p_month) + interval '1 month')::date;
  part    TEXT := format('audit_log_%s', to_char(start_d, 'YYYY_MM'));
BEGIN
  IF to_regclass(part) IS NOT NULL THEN
    RETURN part;
  END IF;
  IF EXISTS (SELECT 1 FROM audit_log_default WHERE occurred_at >= start_d AND occurred_at < end_d) THEN
    RETURN NULL;
  END IF;
  EXECUTE format('CREATE TABLE %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)', part, start_d, end_d);
  PERFORM audit_lock_down(part::regclass, true);
  RETURN part;
END
$$;

CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;

SELECT audit_lock_down('audit_log', true);
SELECT audit_lock_down('audit_log_default', true);
SELECT audit_lock_down('platform_audit_log', false);

SELECT audit_ensure_partition((date_trunc('month', now()) + make_interval(months => m))::date)
  FROM generate_series(-1, 3) AS m;

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
