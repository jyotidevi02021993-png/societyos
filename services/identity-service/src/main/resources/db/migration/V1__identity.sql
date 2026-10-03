-- identity_db schema (docs/architecture/03 §4).
-- app_user, device, refresh_token and signing_key are global (a person lives in many
-- societies); role, role_assignment and consent are tenant tables with RLS.

CREATE TABLE app_user (
  id                  UUID PRIMARY KEY,
  phone_e164          TEXT UNIQUE,
  email               TEXT UNIQUE,
  name                TEXT,
  status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED')),
  preferred_lang      TEXT NOT NULL DEFAULT 'en' CHECK (preferred_lang IN ('en', 'hi')),
  password_hash       TEXT,
  mfa_secret          TEXT,                       -- TOTP secret (base32); KMS-encrypted in prod
  platform_role       TEXT CHECK (platform_role IN ('SUPER_ADMIN')),
  permission_version  INT  NOT NULL DEFAULT 1,
  last_society_id     UUID,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  version             BIGINT NOT NULL DEFAULT 0,
  CHECK (phone_e164 IS NOT NULL OR email IS NOT NULL)
);

-- Permission catalogue: module:action
CREATE TABLE permission (
  code        TEXT PRIMARY KEY,
  module      TEXT NOT NULL,
  action      TEXT NOT NULL,
  description TEXT NOT NULL
);

-- Default role bundles, copied into every society when it is onboarded
CREATE TABLE role_template (
  code        TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  description TEXT NOT NULL,
  permissions TEXT[] NOT NULL
);

CREATE TABLE role (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  code        TEXT NOT NULL,
  name        TEXT NOT NULL,
  permissions TEXT[] NOT NULL DEFAULT '{}',
  is_system   BOOLEAN NOT NULL DEFAULT false,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_role_society_code UNIQUE (society_id, code)
);
SELECT sos_enable_tenant_rls('role');

CREATE TABLE role_assignment (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  user_id     UUID NOT NULL REFERENCES app_user (id),
  role_id     UUID NOT NULL REFERENCES role (id),
  source      TEXT NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'MEMBERSHIP', 'BOOTSTRAP')),
  source_ref  UUID,                              -- e.g. flat_membership id
  valid_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
  valid_to    TIMESTAMPTZ,
  revoked_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_role_assignment_active
  ON role_assignment (society_id, user_id, role_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_role_assignment_user ON role_assignment (user_id) WHERE revoked_at IS NULL;
SELECT sos_enable_tenant_rls('role_assignment');

CREATE TABLE device (
  id           UUID PRIMARY KEY,
  user_id      UUID NOT NULL REFERENCES app_user (id),
  platform     TEXT NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'WEB', 'EDGE')),
  name         TEXT,
  push_token   TEXT,
  bound_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ,
  revoked_at   TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  version      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_device_user ON device (user_id);

CREATE TABLE refresh_token (
  id           UUID PRIMARY KEY,
  user_id      UUID NOT NULL REFERENCES app_user (id),
  device_id    UUID NOT NULL REFERENCES device (id),
  token_hash   TEXT NOT NULL UNIQUE,              -- sha256 of the opaque token
  expires_at   TIMESTAMPTZ NOT NULL,
  rotated_at   TIMESTAMPTZ,
  revoked_at   TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  version      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_refresh_token_device ON refresh_token (device_id) WHERE revoked_at IS NULL;

CREATE TABLE signing_key (
  kid             TEXT PRIMARY KEY,
  private_key_pem TEXT NOT NULL,                 -- KMS-encrypted in prod
  public_key_pem  TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  retired_at      TIMESTAMPTZ
);

CREATE TABLE consent (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  user_id      UUID NOT NULL REFERENCES app_user (id),
  purpose      TEXT NOT NULL,
  policy_version TEXT NOT NULL,
  granted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  withdrawn_at TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('consent');

-- Login needs a user's societies and roles across tenants, which RLS forbids by design.
-- This one narrow, read-only function is the exception. It runs as the migration owner,
-- which must have BYPASSRLS (<svc>_owner in infra/docker/postgres/init; superuser in tests).
CREATE FUNCTION identity_user_societies(p_user UUID)
  RETURNS TABLE (society_id UUID, role_code TEXT)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
  SELECT ra.society_id, r.code
  FROM role_assignment ra
  JOIN role r ON r.id = ra.role_id
  WHERE ra.user_id = p_user
    AND ra.revoked_at IS NULL
    AND ra.valid_from <= now()
    AND (ra.valid_to IS NULL OR ra.valid_to > now())
$$;
-- Only identity roles can connect to identity_db, so EXECUTE stays granted to PUBLIC.
