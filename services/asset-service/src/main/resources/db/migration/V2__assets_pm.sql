-- Asset registry (extends V1), categories, QR, warranty/AMC, PM plans and tasks, history,
-- read models (location, vendor, society) and the db-scheduler table.

-- ---------------------------------------------------------------------------------------
-- Asset: Equipment Registration fields, QR token, cost roll-up
-- ---------------------------------------------------------------------------------------
ALTER TABLE asset DROP CONSTRAINT IF EXISTS asset_status_check;
ALTER TABLE asset ADD CONSTRAINT asset_status_check
  CHECK (status IN ('WORKING','BREAKDOWN','UNDER_REPAIR','RETIRED','DISPOSED'));

ALTER TABLE asset
  ADD COLUMN category_id            UUID,
  ADD COLUMN manufacturer           TEXT,
  ADD COLUMN supplier               TEXT,
  ADD COLUMN engine_no              TEXT,
  ADD COLUMN alternator_no          TEXT,
  ADD COLUMN capacity               TEXT,
  ADD COLUMN maintenance_agency     TEXT,
  ADD COLUMN vendor_id              UUID,
  ADD COLUMN purchase_date          DATE,
  ADD COLUMN cost_paise             BIGINT CHECK (cost_paise IS NULL OR cost_paise >= 0),
  ADD COLUMN expected_life_months   INT CHECK (expected_life_months IS NULL OR expected_life_months > 0),
  ADD COLUMN spec                   JSONB NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN photo_media_ids        UUID[] NOT NULL DEFAULT '{}',
  ADD COLUMN qr_token               TEXT,
  ADD COLUMN qr_printed_at          TIMESTAMPTZ,
  ADD COLUMN breakdown_count        INT NOT NULL DEFAULT 0,
  ADD COLUMN total_maintenance_cost_paise BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN disposed_on            DATE;

CREATE UNIQUE INDEX ux_asset_qr_token ON asset (qr_token) WHERE qr_token IS NOT NULL;
CREATE INDEX ix_asset_status ON asset (society_id, status);
CREATE INDEX ix_asset_updated ON asset (society_id, updated_at);

-- Society-specific sub-categories under the fixed category groups
CREATE TABLE asset_category (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  category_group TEXT NOT NULL,
  code         TEXT NOT NULL,
  name         TEXT NOT NULL,
  spec_fields  JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_asset_category_code UNIQUE (society_id, code)
);
SELECT sos_enable_tenant_rls('asset_category');

-- ---------------------------------------------------------------------------------------
-- History (timeline) of an asset
-- ---------------------------------------------------------------------------------------
CREATE TABLE asset_history (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  asset_id     UUID NOT NULL REFERENCES asset (id),
  at           TIMESTAMPTZ NOT NULL,
  kind         TEXT NOT NULL,
  ref_type     TEXT,
  ref_id       UUID,
  summary      TEXT NOT NULL,
  cost_paise   BIGINT NOT NULL DEFAULT 0,
  actor_id     UUID,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_asset_history_asset ON asset_history (society_id, asset_id, at DESC);
CREATE UNIQUE INDEX ux_asset_history_ref ON asset_history (society_id, asset_id, kind, ref_type, ref_id)
  WHERE ref_id IS NOT NULL;
SELECT sos_enable_tenant_rls('asset_history');

-- ---------------------------------------------------------------------------------------
-- Warranty and AMC
-- ---------------------------------------------------------------------------------------
CREATE TABLE warranty (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  asset_id     UUID NOT NULL REFERENCES asset (id),
  vendor_id    UUID,
  starts_on    DATE,
  ends_on      DATE NOT NULL,
  terms        TEXT,
  document_media_id UUID,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0,
  CHECK (starts_on IS NULL OR ends_on >= starts_on)
);
CREATE INDEX ix_warranty_asset ON warranty (society_id, asset_id);
CREATE INDEX ix_warranty_ends ON warranty (society_id, ends_on);
SELECT sos_enable_tenant_rls('warranty');

CREATE TABLE amc_contract (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  asset_id        UUID NOT NULL REFERENCES asset (id),
  vendor_id       UUID,
  contract_no     TEXT,
  starts_on       DATE NOT NULL,
  ends_on         DATE NOT NULL,
  value_paise     BIGINT NOT NULL DEFAULT 0 CHECK (value_paise >= 0),
  visits_per_year INT NOT NULL DEFAULT 0 CHECK (visits_per_year >= 0),
  coverage        TEXT NOT NULL DEFAULT 'COMPREHENSIVE' CHECK (coverage IN ('COMPREHENSIVE','NON_COMPREHENSIVE','LABOUR_ONLY')),
  status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CANCELLED')),
  document_media_id UUID,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0,
  CHECK (ends_on >= starts_on)
);
CREATE INDEX ix_amc_asset ON amc_contract (society_id, asset_id);
CREATE INDEX ix_amc_ends ON amc_contract (society_id, ends_on) WHERE status = 'ACTIVE';
SELECT sos_enable_tenant_rls('amc_contract');

CREATE TABLE amc_visit (
  id               UUID PRIMARY KEY,
  society_id       UUID NOT NULL,
  amc_id           UUID NOT NULL REFERENCES amc_contract (id),
  due_on           DATE,
  done_on          DATE NOT NULL,
  engineer_name    TEXT,
  service_entry_no TEXT,
  job_card_id      UUID,
  notes            TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_amc_visit_amc ON amc_visit (society_id, amc_id, done_on DESC);
SELECT sos_enable_tenant_rls('amc_visit');

-- One alert per (contract, threshold): the daily job may run many times a day
CREATE TABLE expiry_alert (
  id            UUID PRIMARY KEY,
  society_id    UUID NOT NULL,
  contract_kind TEXT NOT NULL CHECK (contract_kind IN ('WARRANTY','AMC')),
  contract_id   UUID NOT NULL,
  threshold_days INT NOT NULL,
  sent_on       DATE NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by    UUID,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by    UUID,
  version       BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_expiry_alert UNIQUE (society_id, contract_kind, contract_id, threshold_days)
);
SELECT sos_enable_tenant_rls('expiry_alert');

-- ---------------------------------------------------------------------------------------
-- Preventive maintenance
-- ---------------------------------------------------------------------------------------
CREATE TABLE pm_plan (
  id                    UUID PRIMARY KEY,
  society_id            UUID NOT NULL,
  asset_id              UUID NOT NULL REFERENCES asset (id),
  name                  TEXT NOT NULL,
  frequency             TEXT NOT NULL CHECK (frequency IN
                        ('DAILY','WEEKLY','FORTNIGHTLY','MONTHLY','QUARTERLY','HALF_YEARLY','YEARLY','USAGE_BASED')),
  anchor_on             DATE,
  lead_days             INT NOT NULL DEFAULT 0 CHECK (lead_days >= 0 AND lead_days <= 60),
  next_due_on           DATE,
  usage_metric          TEXT,
  usage_interval        NUMERIC(18,4) CHECK (usage_interval IS NULL OR usage_interval > 0),
  last_done_usage       NUMERIC(18,4),
  latest_usage          NUMERIC(18,4),
  checklist             JSONB NOT NULL DEFAULT '[]'::jsonb,
  checklist_template_id UUID,
  assignee_user_id      UUID,
  vendor_id             UUID,
  active                BOOLEAN NOT NULL DEFAULT true,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by            UUID,
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by            UUID,
  version               BIGINT NOT NULL DEFAULT 0,
  CHECK ((frequency = 'USAGE_BASED') = (usage_metric IS NOT NULL AND usage_interval IS NOT NULL)),
  CHECK (frequency = 'USAGE_BASED' OR anchor_on IS NOT NULL)
);
CREATE INDEX ix_pm_plan_asset ON pm_plan (society_id, asset_id);
CREATE INDEX ix_pm_plan_due ON pm_plan (society_id, next_due_on) WHERE active AND next_due_on IS NOT NULL;
CREATE INDEX ix_pm_plan_usage ON pm_plan (society_id, asset_id, usage_metric) WHERE active AND usage_metric IS NOT NULL;
SELECT sos_enable_tenant_rls('pm_plan');

CREATE TABLE pm_task (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  number          TEXT NOT NULL,
  pm_plan_id      UUID NOT NULL REFERENCES pm_plan (id),
  asset_id        UUID NOT NULL REFERENCES asset (id),
  due_on          DATE NOT NULL,
  trigger_kind    TEXT NOT NULL CHECK (trigger_kind IN ('CALENDAR','USAGE','MANUAL')),
  status          TEXT NOT NULL CHECK (status IN ('DUE','IN_PROGRESS','OVERDUE','DONE','MISSED','CANCELLED')),
  job_card_id     UUID,
  assignee_user_id UUID,
  started_at      TIMESTAMPTZ,
  started_by      UUID,
  completed_at    TIMESTAMPTZ,
  completed_by    UUID,
  results         JSONB NOT NULL DEFAULT '[]'::jsonb,
  ok_count        INT NOT NULL DEFAULT 0,
  failed_count    INT NOT NULL DEFAULT 0,
  remarks         TEXT,
  photo_media_ids UUID[] NOT NULL DEFAULT '{}',
  cost_paise      BIGINT NOT NULL DEFAULT 0 CHECK (cost_paise >= 0),
  usage_at_done   NUMERIC(18,4),
  overdue_notified BOOLEAN NOT NULL DEFAULT false,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_pm_task_number UNIQUE (society_id, number)
);
CREATE UNIQUE INDEX ux_pm_task_occurrence ON pm_task (pm_plan_id, due_on) WHERE trigger_kind = 'CALENDAR';
CREATE INDEX ix_pm_task_open ON pm_task (society_id, due_on) WHERE status IN ('DUE','IN_PROGRESS','OVERDUE');
CREATE INDEX ix_pm_task_asset ON pm_task (society_id, asset_id, due_on DESC);
CREATE INDEX ix_pm_task_updated ON pm_task (society_id, updated_at);
SELECT sos_enable_tenant_rls('pm_task');

-- Who receives warranty/AMC/PM alerts in a society (user ids from identity)
CREATE TABLE alert_recipient (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  user_id      UUID NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_alert_recipient UNIQUE (society_id, user_id)
);
SELECT sos_enable_tenant_rls('alert_recipient');

-- ---------------------------------------------------------------------------------------
-- Read models
-- ---------------------------------------------------------------------------------------
-- from society.location.created (id = location id)
CREATE TABLE location_ref (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  kind         TEXT,
  name         TEXT NOT NULL,
  tower_id     UUID,
  parent_id    UUID,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('location_ref');

-- from vendor.vendor.created/updated (id = vendor id)
CREATE TABLE vendor_ref (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  code         TEXT,
  name         TEXT NOT NULL,
  status       TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('vendor_ref');

-- Societies this service works for, so the scheduler can iterate them. Global (no RLS):
-- it holds only ids and time zones, filled from society.created and on first asset write.
CREATE TABLE society_ref (
  society_id   UUID PRIMARY KEY,
  name         TEXT,
  timezone     TEXT NOT NULL DEFAULT 'Asia/Kolkata',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- db-scheduler (ADR-0004)
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
CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);
