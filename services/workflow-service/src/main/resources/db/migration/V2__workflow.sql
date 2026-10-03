-- workflow_db schema (docs/architecture/03 §4): approval workflows, SLA policies and timers,
-- escalations. db-scheduler's table wakes the service up; sla_timer / approval_task are the truth.

CREATE TABLE workflow_definition (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  kind        TEXT NOT NULL,            -- subject type it approves: JOBCARD, PO, EXPENSE, BUDGET, ...
  name        TEXT NOT NULL,
  def_version INT  NOT NULL,
  definition  JSONB NOT NULL,           -- {thresholdPaise, steps:[{name, approverRole, approverUserId,
                                        --   appliesAbovePaise, requiredApprovals, escalateAfterMins, escalateToRole}]}
  active      BOOLEAN NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_workflow_definition_version UNIQUE (society_id, kind, def_version)
);
CREATE UNIQUE INDEX ux_workflow_definition_active ON workflow_definition (society_id, kind) WHERE active;
SELECT sos_enable_tenant_rls('workflow_definition');

CREATE TABLE workflow_instance (
  id                  UUID PRIMARY KEY,
  society_id          UUID NOT NULL,
  definition_id       UUID REFERENCES workflow_definition (id),
  definition_version  INT,
  subject_type        TEXT NOT NULL,
  subject_id          UUID NOT NULL,
  subject_ref         TEXT,
  amount_paise        BIGINT NOT NULL DEFAULT 0 CHECK (amount_paise >= 0),
  status              TEXT NOT NULL CHECK (status IN ('RUNNING','APPROVED','REJECTED','CANCELLED')),
  current_step        INT NOT NULL DEFAULT 0,
  started_at          TIMESTAMPTZ NOT NULL,
  ended_at            TIMESTAMPTZ,
  decided_by          UUID,
  comment             TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by          UUID,
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by          UUID,
  version             BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_workflow_instance_subject ON workflow_instance (society_id, subject_type, subject_id, started_at DESC);
CREATE UNIQUE INDEX ux_workflow_instance_running ON workflow_instance (society_id, subject_type, subject_id)
  WHERE status = 'RUNNING';
SELECT sos_enable_tenant_rls('workflow_instance');

CREATE TABLE approval_task (
  id                  UUID PRIMARY KEY,
  society_id          UUID NOT NULL,
  instance_id         UUID NOT NULL REFERENCES workflow_instance (id),
  step                INT  NOT NULL,
  step_name           TEXT NOT NULL,
  approver_role       TEXT,
  approver_user_id    UUID,
  required_approvals  INT  NOT NULL DEFAULT 1 CHECK (required_approvals >= 1),
  approvals_count     INT  NOT NULL DEFAULT 0,
  status              TEXT NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
  due_at              TIMESTAMPTZ,
  escalate_to_role    TEXT,
  escalation_level    INT  NOT NULL DEFAULT 0,
  decided_by          UUID,
  decided_at          TIMESTAMPTZ,
  comment             TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by          UUID,
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by          UUID,
  version             BIGINT NOT NULL DEFAULT 0,
  CHECK (approver_role IS NOT NULL OR approver_user_id IS NOT NULL)
);
CREATE INDEX ix_approval_task_pending_role ON approval_task (society_id, approver_role) WHERE status = 'PENDING';
CREATE INDEX ix_approval_task_pending_user ON approval_task (society_id, approver_user_id) WHERE status = 'PENDING';
CREATE INDEX ix_approval_task_instance ON approval_task (society_id, instance_id, step);
SELECT sos_enable_tenant_rls('approval_task');

-- One row per approver on a task ("2 of 5 committee members").
CREATE TABLE approval_decision (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  task_id     UUID NOT NULL REFERENCES approval_task (id),
  decided_by  UUID NOT NULL,
  decision    TEXT NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
  comment     TEXT,
  decided_at  TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_approval_decision_user UNIQUE (society_id, task_id, decided_by)
);
SELECT sos_enable_tenant_rls('approval_decision');

-- SLA policy per subject type × category × priority (NULL = any).
CREATE TABLE sla_policy (
  id                UUID PRIMARY KEY,
  society_id        UUID NOT NULL,
  subject_type      TEXT NOT NULL,
  category_name     TEXT,
  priority          TEXT CHECK (priority IS NULL OR priority IN ('P1','P2','P3','P4')),
  respond_mins      INT CHECK (respond_mins IS NULL OR respond_mins > 0),
  resolve_mins      INT NOT NULL CHECK (resolve_mins > 0),
  warn_percent      INT NOT NULL DEFAULT 80 CHECK (warn_percent BETWEEN 1 AND 99),
  escalation_chain  JSONB NOT NULL DEFAULT '[]',   -- [{afterMins, toRole}], minutes after the breach
  active            BOOLEAN NOT NULL DEFAULT true,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by        UUID,
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by        UUID,
  version           BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_sla_policy_scope ON sla_policy
  (society_id, subject_type, coalesce(lower(category_name), ''), coalesce(priority, '')) WHERE active;
SELECT sos_enable_tenant_rls('sla_policy');

CREATE TABLE sla_timer (
  id                   UUID PRIMARY KEY,
  society_id           UUID NOT NULL,
  subject_type         TEXT NOT NULL,
  subject_id           UUID NOT NULL,
  subject_ref          TEXT,
  kind                 TEXT NOT NULL CHECK (kind IN ('RESPOND','RESOLVE')),
  policy_id            UUID REFERENCES sla_policy (id),
  started_at           TIMESTAMPTZ NOT NULL,
  warn_at              TIMESTAMPTZ,
  due_at               TIMESTAMPTZ NOT NULL,
  warned_at            TIMESTAMPTZ,
  status               TEXT NOT NULL CHECK (status IN ('ACTIVE','STOPPED','FIRED')),
  fired_at             TIMESTAMPTZ,
  stopped_at           TIMESTAMPTZ,
  level                INT NOT NULL DEFAULT 0,       -- escalation steps taken after the breach
  escalation_chain     JSONB NOT NULL DEFAULT '[]',  -- copied from the policy when the timer starts
  next_escalation_at   TIMESTAMPTZ,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by           UUID,
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by           UUID,
  version              BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_sla_timer_subject ON sla_timer (society_id, subject_type, subject_id);
CREATE UNIQUE INDEX ux_sla_timer_live ON sla_timer (society_id, subject_type, subject_id, kind)
  WHERE status IN ('ACTIVE','FIRED');
SELECT sos_enable_tenant_rls('sla_timer');

CREATE TABLE escalation_log (
  id            UUID PRIMARY KEY,
  society_id    UUID NOT NULL,
  subject_type  TEXT NOT NULL,
  subject_id    UUID NOT NULL,
  level         INT  NOT NULL,
  to_role       TEXT NOT NULL,
  reason        TEXT NOT NULL CHECK (reason IN ('SLA','APPROVAL')),
  ref_id        UUID,
  at            TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by    UUID,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by    UUID,
  version       BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_escalation_log_subject ON escalation_log (society_id, subject_type, subject_id, at);
SELECT sos_enable_tenant_rls('escalation_log');

-- identity.role.assigned / .revoked read model: whom to notify for role-based approvals.
CREATE TABLE role_holder (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  user_id     UUID NOT NULL,
  role_code   TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_role_holder_role ON role_holder (society_id, role_code);
SELECT sos_enable_tenant_rls('role_holder');

-- db-scheduler (infrastructure, no RLS): task data is "<societyId>:<rowId>", never business data.
CREATE TABLE scheduled_tasks (
  task_name             TEXT NOT NULL,
  task_instance         TEXT NOT NULL,
  task_data             BYTEA,
  execution_time        TIMESTAMPTZ NOT NULL,
  picked                BOOLEAN NOT NULL,
  picked_by             TEXT,
  last_success          TIMESTAMPTZ,
  last_failure          TIMESTAMPTZ,
  consecutive_failures  INT,
  last_heartbeat        TIMESTAMPTZ,
  version               BIGINT NOT NULL,
  priority              SMALLINT,
  PRIMARY KEY (task_name, task_instance)
);
CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);
