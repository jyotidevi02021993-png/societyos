-- utility-service: meters and readings (WTP, STP, water, pumps, tanks, DG, transformer, lifts,
-- fire, energy), daily checklists and rounds, manager daily sign-off, read models.

CREATE TABLE meter (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  code            TEXT NOT NULL,
  name            TEXT NOT NULL,
  system          TEXT NOT NULL CHECK (system IN
                  ('WTP','STP','WATER','PUMP','TANK','DG','TRANSFORMER','LIFT','FIRE','ENERGY','OTHER')),
  asset_id        UUID,
  location_id     UUID,
  metric          TEXT NOT NULL,
  unit            TEXT NOT NULL,
  reading_mode    TEXT NOT NULL CHECK (reading_mode IN ('CUMULATIVE','INSTANT')),
  expected_min    NUMERIC(18,4),
  expected_max    NUMERIC(18,4),
  max_delta       NUMERIC(18,4) CHECK (max_delta IS NULL OR max_delta > 0),
  active          BOOLEAN NOT NULL DEFAULT true,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_meter_code UNIQUE (society_id, code),
  CHECK (expected_min IS NULL OR expected_max IS NULL OR expected_min <= expected_max)
);
CREATE INDEX ix_meter_system ON meter (society_id, system, name);
CREATE INDEX ix_meter_asset ON meter (society_id, asset_id) WHERE asset_id IS NOT NULL;
SELECT sos_enable_tenant_rls('meter');

-- Narrow time series, partitioned by month (doc 03 §5). Readings are immutable.
CREATE TABLE reading (
  id              UUID NOT NULL,
  society_id      UUID NOT NULL,
  meter_id        UUID NOT NULL,
  asset_id        UUID,
  metric          TEXT NOT NULL,
  value           NUMERIC(18,4) NOT NULL,
  unit            TEXT NOT NULL,
  at              TIMESTAMPTZ NOT NULL,
  delta           NUMERIC(18,4),
  source          TEXT NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','IOT','IMPORT')),
  recorded_by     UUID,
  anomaly         BOOLEAN NOT NULL DEFAULT false,
  anomaly_reason  TEXT,
  expected_min    NUMERIC(18,4),
  expected_max    NUMERIC(18,4),
  note            TEXT,
  photo_media_id  UUID,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id, at)
) PARTITION BY RANGE (at);

-- Monthly partitions for 2026-2028 plus a default partition; ops (pg_partman) adds later months.
DO $$
DECLARE m DATE := DATE '2026-01-01';
BEGIN
  WHILE m < DATE '2029-01-01' LOOP
    EXECUTE format('CREATE TABLE reading_%s PARTITION OF reading FOR VALUES FROM (%L) TO (%L)',
      to_char(m, 'YYYYMM'), m, (m + INTERVAL '1 month')::date);
    m := (m + INTERVAL '1 month')::date;
  END LOOP;
END
$$;
CREATE TABLE reading_default PARTITION OF reading DEFAULT;

CREATE INDEX ix_reading_meter_at ON reading (society_id, meter_id, at DESC);
CREATE INDEX ix_reading_anomaly ON reading (society_id, at) WHERE anomaly;
CREATE INDEX ix_reading_at_brin ON reading USING brin (at);
SELECT sos_enable_tenant_rls('reading');

CREATE TABLE checklist_template (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  code            TEXT NOT NULL,
  name            TEXT NOT NULL,
  system          TEXT NOT NULL CHECK (system IN
                  ('WTP','STP','WATER','PUMP','TANK','DG','TRANSFORMER','LIFT','FIRE','ENERGY','HOUSEKEEPING','SECURITY','OTHER')),
  frequency       TEXT NOT NULL CHECK (frequency IN ('DAILY','PER_SHIFT','WEEKLY','MONTHLY')),
  asset_id        UUID,
  location_id     UUID,
  items           JSONB NOT NULL,
  active          BOOLEAN NOT NULL DEFAULT true,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_checklist_template_code UNIQUE (society_id, code)
);
SELECT sos_enable_tenant_rls('checklist_template');

CREATE TABLE checklist_run (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  template_id     UUID NOT NULL REFERENCES checklist_template (id),
  template_name   TEXT NOT NULL,
  run_date        DATE NOT NULL,
  shift           TEXT NOT NULL CHECK (shift IN ('GENERAL','MORNING','EVENING','NIGHT')),
  asset_id        UUID,
  location_id     UUID,
  status          TEXT NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
  started_by      UUID,
  started_at      TIMESTAMPTZ NOT NULL,
  completed_by    UUID,
  completed_at    TIMESTAMPTZ,
  ok_count        INT NOT NULL DEFAULT 0,
  failed_count    INT NOT NULL DEFAULT 0,
  na_count        INT NOT NULL DEFAULT 0,
  remarks         TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_checklist_run UNIQUE (society_id, template_id, run_date, shift)
);
CREATE INDEX ix_checklist_run_date ON checklist_run (society_id, run_date);
SELECT sos_enable_tenant_rls('checklist_run');

CREATE TABLE checklist_response (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  run_id          UUID NOT NULL REFERENCES checklist_run (id),
  item_code       TEXT NOT NULL,
  item_label      TEXT NOT NULL,
  result          TEXT NOT NULL CHECK (result IN ('OK','NOT_OK','NA')),
  value           NUMERIC(18,4),
  text_value      TEXT,
  note            TEXT,
  photo_media_id  UUID,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_checklist_response UNIQUE (run_id, item_code)
);
SELECT sos_enable_tenant_rls('checklist_response');

CREATE TABLE manager_signoff (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  sign_date       DATE NOT NULL,
  manager_user_id UUID NOT NULL,
  remarks         TEXT,
  summary         JSONB NOT NULL,
  signed_at       TIMESTAMPTZ NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_manager_signoff UNIQUE (society_id, sign_date)
);
SELECT sos_enable_tenant_rls('manager_signoff');

-- Read models (id = the other service's id)
CREATE TABLE asset_ref (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  code            TEXT,
  name            TEXT NOT NULL,
  category_group  TEXT,
  location_id     UUID,
  status          TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('asset_ref');

CREATE TABLE location_ref (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  kind            TEXT,
  name            TEXT NOT NULL,
  tower_id        UUID,
  parent_id       UUID,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('location_ref');
