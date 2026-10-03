-- security_db schema: Gate & Security (docs/architecture/03 gate_db).
-- Read models (flat directory, residents, domestic staff, vehicles, settings, staff roles) are
-- fed by society and identity events. Every table is a tenant table with RLS.

-- ---- read models ---------------------------------------------------------------------------

CREATE TABLE society_settings (
  id                              UUID PRIMARY KEY,          -- = society id
  society_id                      UUID NOT NULL,
  gate_approval_timeout_seconds   INT  NOT NULL DEFAULT 120 CHECK (gate_approval_timeout_seconds BETWEEN 10 AND 3600),
  visitor_retention_days          INT  NOT NULL DEFAULT 180 CHECK (visitor_retention_days BETWEEN 1 AND 3650),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('society_settings');

CREATE TABLE flat_directory (
  id          UUID PRIMARY KEY,                              -- = flat id
  society_id  UUID NOT NULL,
  tower_id    UUID,
  tower_name  TEXT,
  number      TEXT NOT NULL,
  label       TEXT NOT NULL,                                 -- "A-1203"
  floor       INT,
  status      TEXT NOT NULL DEFAULT 'OCCUPIED' CHECK (status IN ('OCCUPIED', 'VACANT', 'UNDER_RENOVATION')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_flat_directory_label ON flat_directory (society_id, label);
SELECT sos_enable_tenant_rls('flat_directory');

CREATE TABLE flat_resident (
  id            UUID PRIMARY KEY,                            -- = membership id
  society_id    UUID NOT NULL,
  flat_id       UUID NOT NULL,
  user_id       UUID,
  resident_id   UUID,
  resident_name TEXT,
  kind          TEXT NOT NULL CHECK (kind IN ('OWNER', 'TENANT', 'FAMILY')),
  is_primary    BOOLEAN NOT NULL DEFAULT false,
  ended_at      TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_flat_resident_flat ON flat_resident (society_id, flat_id) WHERE ended_at IS NULL;
CREATE INDEX ix_flat_resident_user ON flat_resident (society_id, user_id) WHERE ended_at IS NULL;
SELECT sos_enable_tenant_rls('flat_resident');

CREATE TABLE domestic_staff (
  id              UUID PRIMARY KEY,                          -- = staff id
  society_id      UUID NOT NULL,
  name            TEXT NOT NULL,
  kind            TEXT NOT NULL,
  flat_ids        UUID[] NOT NULL DEFAULT '{}',
  kyc_status      TEXT,
  photo_media_id  UUID,
  status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED')),
  phone_enc       TEXT,                                      -- enrolled at the gate (AES-GCM)
  phone_hash      TEXT,                                      -- HMAC for lookup
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_domestic_staff_phone ON domestic_staff (society_id, phone_hash) WHERE phone_hash IS NOT NULL;
SELECT sos_enable_tenant_rls('domestic_staff');

CREATE TABLE flat_vehicle (
  id          UUID PRIMARY KEY,                              -- = vehicle id
  society_id  UUID NOT NULL,
  flat_id     UUID NOT NULL,
  reg_no      TEXT NOT NULL,
  kind        TEXT NOT NULL,
  rfid_tag    TEXT,
  removed_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_flat_vehicle_reg ON flat_vehicle (society_id, reg_no) WHERE removed_at IS NULL;
SELECT sos_enable_tenant_rls('flat_vehicle');

-- Staff role holders (GUARD, managers) from identity.role.* events: SOS and incident recipients.
CREATE TABLE staff_role (
  id          UUID PRIMARY KEY,                              -- = role assignment id
  society_id  UUID NOT NULL,
  user_id     UUID NOT NULL,
  role_code   TEXT NOT NULL,
  revoked_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_staff_role_code ON staff_role (society_id, role_code) WHERE revoked_at IS NULL;
SELECT sos_enable_tenant_rls('staff_role');

-- ---- gate ------------------------------------------------------------------------------------

CREATE TABLE visitor (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  name            TEXT NOT NULL,
  phone_enc       TEXT,
  phone_hash      TEXT,
  photo_media_id  UUID,
  last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_visitor_phone ON visitor (society_id, phone_hash) WHERE phone_hash IS NOT NULL;
CREATE INDEX ix_visitor_last_seen ON visitor (society_id, last_seen_at);
SELECT sos_enable_tenant_rls('visitor');

CREATE TABLE gate_pass (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  flat_id     UUID NOT NULL,
  kind        TEXT NOT NULL CHECK (kind IN ('GUEST', 'CAB', 'DELIVERY', 'SERVICE')),
  guest_name  TEXT,
  code        TEXT NOT NULL,                                 -- 6 digits, unique among active passes
  qr_token    TEXT NOT NULL,
  valid_from  TIMESTAMPTZ NOT NULL,
  valid_to    TIMESTAMPTZ NOT NULL,
  max_uses    INT NOT NULL CHECK (max_uses > 0),
  used_count  INT NOT NULL DEFAULT 0,
  status      TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'CANCELLED')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0,
  CHECK (valid_to > valid_from)
);
CREATE UNIQUE INDEX ux_gate_pass_code_active ON gate_pass (society_id, code) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX ux_gate_pass_qr ON gate_pass (qr_token);
CREATE INDEX ix_gate_pass_flat ON gate_pass (society_id, flat_id, valid_to);
CREATE INDEX ix_gate_pass_updated ON gate_pass (society_id, updated_at);
SELECT sos_enable_tenant_rls('gate_pass');

CREATE TABLE entry_log (
  id               UUID PRIMARY KEY,
  society_id       UUID NOT NULL,
  gate_id          UUID,
  flat_id          UUID NOT NULL,
  flat_label       TEXT,
  visitor_id       UUID REFERENCES visitor (id) ON DELETE SET NULL,
  visitor_name     TEXT,
  purpose          TEXT NOT NULL CHECK (purpose IN ('GUEST', 'CAB', 'DELIVERY', 'SERVICE', 'STAFF')),
  company          TEXT,
  vehicle_reg      TEXT,
  photo_media_id   UUID,
  pass_id          UUID REFERENCES gate_pass (id) ON DELETE SET NULL,
  status           TEXT NOT NULL CHECK (status IN ('REQUESTED', 'APPROVED', 'DENIED', 'EXPIRED', 'IN', 'OUT')),
  source           TEXT NOT NULL DEFAULT 'ONLINE' CHECK (source IN ('ONLINE', 'PASS', 'EDGE')),
  client_entry_id  TEXT,                                     -- edge agent's id, for offline dedupe
  requested_at     TIMESTAMPTZ NOT NULL,
  expires_at       TIMESTAMPTZ,
  decided_by       UUID,
  decided_at       TIMESTAMPTZ,
  in_at            TIMESTAMPTZ,
  out_at           TIMESTAMPTZ,
  guard_id         UUID,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_entry_log_requested ON entry_log (society_id, requested_at DESC, id DESC);
CREATE INDEX ix_entry_log_flat ON entry_log (society_id, flat_id, requested_at DESC);
CREATE INDEX ix_entry_log_pending ON entry_log (expires_at) WHERE status = 'REQUESTED';
CREATE UNIQUE INDEX ux_entry_log_client ON entry_log (society_id, client_entry_id) WHERE client_entry_id IS NOT NULL;
SELECT sos_enable_tenant_rls('entry_log');

CREATE TABLE delivery (
  id             UUID PRIMARY KEY,
  society_id     UUID NOT NULL,
  flat_id        UUID NOT NULL,
  company        TEXT NOT NULL,
  entry_id       UUID REFERENCES entry_log (id) ON DELETE SET NULL,
  leave_at_gate  BOOLEAN NOT NULL,
  status         TEXT NOT NULL CHECK (status IN ('AT_GATE', 'SENT_UP', 'COLLECTED')),
  gate_id        UUID,
  received_by    UUID,
  received_at    TIMESTAMPTZ NOT NULL,
  collected_at   TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_delivery_flat ON delivery (society_id, flat_id, received_at DESC);
SELECT sos_enable_tenant_rls('delivery');

CREATE TABLE staff_attendance (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  staff_id    UUID NOT NULL,
  gate_id     UUID,
  in_at       TIMESTAMPTZ NOT NULL,
  out_at      TIMESTAMPTZ,
  guard_id    UUID,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_staff_attendance_open ON staff_attendance (society_id, staff_id) WHERE out_at IS NULL;
CREATE INDEX ix_staff_attendance_in ON staff_attendance (society_id, in_at DESC);
SELECT sos_enable_tenant_rls('staff_attendance');

CREATE TABLE guard_shift (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  guard_user_id   UUID NOT NULL,
  gate_id         UUID,
  starts_at       TIMESTAMPTZ NOT NULL,
  ends_at         TIMESTAMPTZ NOT NULL,
  checked_in_at   TIMESTAMPTZ,
  checked_out_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0,
  CHECK (ends_at > starts_at)
);
CREATE INDEX ix_guard_shift_starts ON guard_shift (society_id, starts_at);
CREATE INDEX ix_guard_shift_guard ON guard_shift (society_id, guard_user_id, starts_at);
SELECT sos_enable_tenant_rls('guard_shift');

CREATE TABLE sos (
  id               UUID PRIMARY KEY,
  society_id       UUID NOT NULL,
  raised_by        UUID NOT NULL,
  flat_id          UUID,
  kind             TEXT NOT NULL CHECK (kind IN ('MEDICAL', 'FIRE', 'SECURITY', 'OTHER')),
  note             TEXT,
  at               TIMESTAMPTZ NOT NULL,
  acknowledged_by  UUID,
  acknowledged_at  TIMESTAMPTZ,
  resolved_by      UUID,
  resolved_at      TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_sos_at ON sos (society_id, at DESC);
SELECT sos_enable_tenant_rls('sos');

CREATE TABLE gate_incident (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  kind            TEXT NOT NULL,
  severity        TEXT NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
  location_text   TEXT,
  description     TEXT,
  photo_media_id  UUID,
  gate_id         UUID,
  reported_by     UUID NOT NULL,
  at              TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_gate_incident_at ON gate_incident (society_id, at DESC);
SELECT sos_enable_tenant_rls('gate_incident');

-- Scheduled jobs work one society at a time; these two narrow, read-only lookups return only
-- society ids across tenants. They run as the migration owner (BYPASSRLS; superuser in tests).
CREATE FUNCTION security_societies_with_expired_entries(p_now TIMESTAMPTZ)
  RETURNS TABLE (society_id UUID)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
  SELECT DISTINCT e.society_id FROM entry_log e WHERE e.status = 'REQUESTED' AND e.expires_at <= p_now
$$;

CREATE FUNCTION security_known_societies()
  RETURNS TABLE (society_id UUID)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
  SELECT s.society_id FROM society_settings s
  UNION SELECT e.society_id FROM entry_log e
  UNION SELECT v.society_id FROM visitor v
  UNION SELECT a.society_id FROM staff_attendance a
$$;
