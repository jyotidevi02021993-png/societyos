CREATE TABLE asset (
  id               UUID PRIMARY KEY,
  society_id       UUID NOT NULL,
  asset_code       TEXT NOT NULL,
  name             TEXT NOT NULL,
  category         TEXT NOT NULL,
  location_id      UUID,
  make             TEXT,
  model            TEXT,
  serial_no        TEXT,
  photo_media_id   UUID,
  installation_date DATE,
  warranty_until   DATE,
  amc_until        DATE,
  pm_frequency     TEXT CHECK (pm_frequency IS NULL OR pm_frequency IN
                   ('DAILY','WEEKLY','FORTNIGHTLY','MONTHLY','QUARTERLY','HALF_YEARLY','YEARLY','USAGE_BASED')),
  status           TEXT NOT NULL DEFAULT 'WORKING' CHECK (status IN ('WORKING','BREAKDOWN','UNDER_REPAIR','RETIRED')),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_asset_code UNIQUE (society_id, asset_code),
  CHECK (installation_date IS NULL OR warranty_until IS NULL OR warranty_until >= installation_date),
  CHECK (installation_date IS NULL OR amc_until IS NULL OR amc_until >= installation_date)
);
CREATE INDEX ix_asset_category ON asset (society_id, category, name);
CREATE INDEX ix_asset_location ON asset (society_id, location_id) WHERE location_id IS NOT NULL;
CREATE INDEX ix_asset_warranty ON asset (society_id, warranty_until) WHERE warranty_until IS NOT NULL;
CREATE INDEX ix_asset_amc ON asset (society_id, amc_until) WHERE amc_until IS NOT NULL;
SELECT sos_enable_tenant_rls('asset');
