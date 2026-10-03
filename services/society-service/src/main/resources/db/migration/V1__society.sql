-- society_db schema (docs/architecture/03 §4). Every table is a tenant table with RLS;
-- the society row is its own tenant (society_id = id).
-- Floors are plain numbers on the flat (0..floors_count of the tower), not a separate table.

CREATE TABLE society (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  name        TEXT NOT NULL,
  legal_name  TEXT,
  address     TEXT,
  city        TEXT NOT NULL,
  state       TEXT NOT NULL,
  pin         TEXT,
  timezone    TEXT NOT NULL DEFAULT 'Asia/Kolkata',
  settings    JSONB NOT NULL DEFAULT '{}',
  status      TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0,
  CHECK (society_id = id)
);
SELECT sos_enable_tenant_rls('society');

CREATE TABLE tower (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  name         TEXT NOT NULL,
  code         TEXT NOT NULL,
  floors_count INT  NOT NULL CHECK (floors_count BETWEEN 0 AND 200),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_tower_society_code UNIQUE (society_id, code)
);
SELECT sos_enable_tenant_rls('tower');

CREATE TABLE flat (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  tower_id    UUID NOT NULL REFERENCES tower (id),
  number      TEXT NOT NULL,
  label       TEXT NOT NULL,                  -- "<tower code>-<number>", e.g. A-1203
  floor       INT  NOT NULL,
  area_sqft   INT  CHECK (area_sqft > 0),
  flat_type   TEXT,                           -- 1BHK, 2BHK, SHOP ...
  status      TEXT NOT NULL DEFAULT 'VACANT' CHECK (status IN ('OCCUPIED', 'VACANT', 'UNDER_RENOVATION')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_flat_tower_number UNIQUE (society_id, tower_id, number),
  CONSTRAINT ux_flat_label UNIQUE (society_id, label)
);
CREATE INDEX ix_flat_society_created ON flat (society_id, created_at DESC, id DESC);
SELECT sos_enable_tenant_rls('flat');

CREATE TABLE location (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  kind        TEXT NOT NULL CHECK (kind IN ('PUMP_ROOM', 'BASEMENT', 'ELECTRICAL_ROOM', 'PLANT_ROOM', 'DG_ROOM',
                                            'STP', 'WTP', 'LIFT_ROOM', 'TERRACE', 'GATE', 'COMMON_AREA',
                                            'PARKING', 'GARDEN', 'OTHER')),
  name        TEXT NOT NULL,
  tower_id    UUID REFERENCES tower (id),
  parent_id   UUID REFERENCES location (id),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_location_name UNIQUE NULLS NOT DISTINCT (society_id, parent_id, name)
);
SELECT sos_enable_tenant_rls('location');

CREATE TABLE facility (
  id            UUID PRIMARY KEY,
  society_id    UUID NOT NULL,
  kind          TEXT NOT NULL CHECK (kind IN ('CLUBHOUSE', 'GYM', 'POOL', 'COURT', 'GUEST_ROOM', 'HALL', 'OTHER')),
  name          TEXT NOT NULL,
  capacity      INT  NOT NULL CHECK (capacity > 0),
  booking_rules JSONB NOT NULL,
  chargeable    BOOLEAN NOT NULL DEFAULT false,
  charge_paise  BIGINT  NOT NULL DEFAULT 0 CHECK (charge_paise >= 0),
  status        TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by    UUID,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by    UUID,
  version       BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_facility_name UNIQUE (society_id, name)
);
SELECT sos_enable_tenant_rls('facility');

CREATE TABLE parking_slot (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  code        TEXT NOT NULL,
  kind        TEXT NOT NULL CHECK (kind IN ('COVERED', 'OPEN', 'BASEMENT', 'VISITOR')),
  flat_id     UUID REFERENCES flat (id),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_parking_slot_code UNIQUE (society_id, code)
);
CREATE INDEX ix_parking_slot_flat ON parking_slot (society_id, flat_id);
SELECT sos_enable_tenant_rls('parking_slot');

-- A person in this society; the phone lives in identity-service (user_id), never here.
CREATE TABLE resident (
  id               UUID PRIMARY KEY,
  society_id       UUID NOT NULL,
  user_id          UUID NOT NULL,
  name             TEXT NOT NULL,
  directory_opt_in BOOLEAN NOT NULL DEFAULT false,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_resident_user UNIQUE (society_id, user_id)
);
SELECT sos_enable_tenant_rls('resident');

CREATE TABLE flat_membership (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  flat_id     UUID NOT NULL REFERENCES flat (id),
  resident_id UUID NOT NULL REFERENCES resident (id),
  user_id     UUID NOT NULL,                   -- copy of resident.user_id for "my flats"
  kind        TEXT NOT NULL CHECK (kind IN ('OWNER', 'TENANT', 'FAMILY')),
  from_date   DATE NOT NULL,
  to_date     DATE,
  is_primary  BOOLEAN NOT NULL DEFAULT false,
  ended_at    TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0,
  CHECK (to_date IS NULL OR to_date >= from_date)
);
CREATE UNIQUE INDEX ux_flat_membership_active
  ON flat_membership (society_id, flat_id, resident_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX ux_flat_membership_primary
  ON flat_membership (society_id, flat_id, kind) WHERE ended_at IS NULL AND is_primary;
CREATE INDEX ix_flat_membership_user ON flat_membership (society_id, user_id) WHERE ended_at IS NULL;
SELECT sos_enable_tenant_rls('flat_membership');

CREATE TABLE vehicle (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  flat_id     UUID NOT NULL REFERENCES flat (id),
  reg_no      TEXT NOT NULL,                   -- normalised: upper case, no spaces or dashes
  kind        TEXT NOT NULL CHECK (kind IN ('CAR', 'BIKE', 'OTHER')),
  rfid_tag    TEXT,
  removed_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_vehicle_reg_active ON vehicle (society_id, reg_no) WHERE removed_at IS NULL;
CREATE UNIQUE INDEX ux_vehicle_rfid_active ON vehicle (society_id, rfid_tag) WHERE removed_at IS NULL AND rfid_tag IS NOT NULL;
CREATE INDEX ix_vehicle_flat ON vehicle (society_id, flat_id) WHERE removed_at IS NULL;
SELECT sos_enable_tenant_rls('vehicle');

CREATE TABLE domestic_staff (
  id             UUID PRIMARY KEY,
  society_id     UUID NOT NULL,
  name           TEXT NOT NULL,
  kind           TEXT NOT NULL CHECK (kind IN ('MAID', 'COOK', 'DRIVER', 'NANNY', 'OTHER')),
  phone_enc      TEXT NOT NULL,                -- AES-GCM (FieldCrypto)
  phone_hash     TEXT NOT NULL,                -- HMAC for lookups
  photo_media_id UUID,
  kyc_status     TEXT NOT NULL DEFAULT 'PENDING' CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
  status         TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED')),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by     UUID,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by     UUID,
  version        BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_domestic_staff_phone UNIQUE (society_id, phone_hash)
);
SELECT sos_enable_tenant_rls('domestic_staff');

-- Join table (JPA element collection): society_id comes from the transaction's write society.
CREATE TABLE domestic_staff_flat (
  staff_id    UUID NOT NULL REFERENCES domestic_staff (id) ON DELETE CASCADE,
  flat_id     UUID NOT NULL REFERENCES flat (id),
  society_id  UUID NOT NULL DEFAULT app_write_society_id(),
  PRIMARY KEY (staff_id, flat_id)
);
CREATE INDEX ix_domestic_staff_flat_flat ON domestic_staff_flat (society_id, flat_id);
SELECT sos_enable_tenant_rls('domestic_staff_flat');
