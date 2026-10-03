-- marketplace-service (Phase 3 basics): providers, catalogue, offers, bookings, commission,
-- and a read model of flats/memberships fed by society-service events.

-- Service providers (plumber, cleaning agency, salon at home ...), verified by the society.
CREATE TABLE service_provider (
  id                UUID PRIMARY KEY,
  society_id        UUID        NOT NULL,
  name              TEXT        NOT NULL,
  categories        TEXT[]      NOT NULL DEFAULT '{}',
  description       TEXT,
  contact_phone_enc TEXT,                          -- AES-GCM (FieldCrypto); never in events/logs
  user_id           UUID,                          -- provider's login (confirms/completes bookings)
  status            TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
  verified          BOOLEAN     NOT NULL DEFAULT false,
  verified_at       TIMESTAMPTZ,
  verified_by       UUID,
  commission_bps    INT         NOT NULL CHECK (commission_bps BETWEEN 0 AND 10000),
  rating_sum        BIGINT      NOT NULL DEFAULT 0,
  rating_count      BIGINT      NOT NULL DEFAULT 0,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by        UUID,
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by        UUID,
  version           BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_service_provider_society ON service_provider (society_id, name);
CREATE INDEX ix_service_provider_user ON service_provider (society_id, user_id);
SELECT sos_enable_tenant_rls('service_provider');

-- What a provider sells, at a fixed price in paise.
CREATE TABLE service_item (
  id               UUID PRIMARY KEY,
  society_id       UUID        NOT NULL,
  provider_id      UUID        NOT NULL REFERENCES service_provider (id),
  category         TEXT        NOT NULL CHECK (category IN ('CLEANING', 'PLUMBING', 'ELECTRICAL', 'APPLIANCE_REPAIR',
                     'PEST_CONTROL', 'CARPENTRY', 'PAINTING', 'BEAUTY', 'LAUNDRY', 'OTHER')),
  name             TEXT        NOT NULL,
  description      TEXT,
  price_paise      BIGINT      NOT NULL CHECK (price_paise >= 0),
  currency         CHAR(3)     NOT NULL DEFAULT 'INR',
  duration_minutes INT         NOT NULL DEFAULT 60 CHECK (duration_minutes > 0),
  active           BOOLEAN     NOT NULL DEFAULT true,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_service_item_society_category ON service_item (society_id, category, active);
CREATE INDEX ix_service_item_provider ON service_item (society_id, provider_id);
SELECT sos_enable_tenant_rls('service_item');

-- Offers / coupons. PERCENT value is in basis points, FLAT value in paise.
CREATE TABLE offer (
  id                 UUID PRIMARY KEY,
  society_id         UUID        NOT NULL,
  code               TEXT        NOT NULL,
  description        TEXT,
  kind               TEXT        NOT NULL CHECK (kind IN ('PERCENT', 'FLAT')),
  value              BIGINT      NOT NULL CHECK (value > 0),
  max_discount_paise BIGINT,
  min_order_paise    BIGINT      NOT NULL DEFAULT 0,
  provider_id        UUID REFERENCES service_provider (id),
  category           TEXT,
  valid_from         TIMESTAMPTZ NOT NULL,
  valid_to           TIMESTAMPTZ NOT NULL,
  max_redemptions    INT,
  per_flat_limit     INT,
  redeemed_count     INT         NOT NULL DEFAULT 0,
  active             BOOLEAN     NOT NULL DEFAULT true,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by         UUID,
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by         UUID,
  version            BIGINT      NOT NULL DEFAULT 0,
  CHECK (valid_to > valid_from)
);
CREATE UNIQUE INDEX ux_offer_code ON offer (society_id, code);
SELECT sos_enable_tenant_rls('offer');

-- A resident's booking of a service for their flat.
CREATE TABLE service_booking (
  id               UUID PRIMARY KEY,
  society_id       UUID        NOT NULL,
  number           TEXT        NOT NULL,
  flat_id          UUID        NOT NULL,
  booked_by        UUID        NOT NULL,
  provider_id      UUID        NOT NULL REFERENCES service_provider (id),
  service_item_id  UUID        NOT NULL REFERENCES service_item (id),
  provider_name    TEXT        NOT NULL,
  service_name     TEXT        NOT NULL,
  quantity         INT         NOT NULL DEFAULT 1 CHECK (quantity BETWEEN 1 AND 100),
  scheduled_at     TIMESTAMPTZ NOT NULL,
  notes            TEXT,
  status           TEXT        NOT NULL CHECK (status IN ('REQUESTED', 'CONFIRMED', 'DECLINED', 'CANCELLED', 'COMPLETED')),
  price_paise      BIGINT      NOT NULL,
  discount_paise   BIGINT      NOT NULL DEFAULT 0,
  total_paise      BIGINT      NOT NULL,
  currency         CHAR(3)     NOT NULL DEFAULT 'INR',
  offer_id         UUID REFERENCES offer (id),
  commission_bps   INT         NOT NULL,
  commission_paise BIGINT      NOT NULL,
  confirmed_at     TIMESTAMPTZ,
  cancelled_at     TIMESTAMPTZ,
  cancelled_by     UUID,
  cancel_reason    TEXT,
  completed_at     TIMESTAMPTZ,
  rating           INT CHECK (rating BETWEEN 1 AND 5),
  review           TEXT,
  rated_at         TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_service_booking_number ON service_booking (society_id, number);
CREATE INDEX ix_service_booking_flat ON service_booking (society_id, flat_id, created_at DESC);
CREATE INDEX ix_service_booking_provider ON service_booking (society_id, provider_id, created_at DESC);
CREATE INDEX ix_service_booking_status ON service_booking (society_id, status, created_at DESC);
SELECT sos_enable_tenant_rls('service_booking');

-- Platform commission earned on a completed booking. Financial: never deleted.
CREATE TABLE commission (
  id           UUID PRIMARY KEY,
  society_id   UUID        NOT NULL,
  booking_id   UUID        NOT NULL REFERENCES service_booking (id),
  provider_id  UUID        NOT NULL REFERENCES service_provider (id),
  base_paise   BIGINT      NOT NULL,
  rate_bps     INT         NOT NULL,
  amount_paise BIGINT      NOT NULL,
  currency     CHAR(3)     NOT NULL DEFAULT 'INR',
  status       TEXT        NOT NULL DEFAULT 'ACCRUED' CHECK (status IN ('ACCRUED', 'SETTLED')),
  settled_at   TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_commission_booking ON commission (society_id, booking_id);
CREATE INDEX ix_commission_provider ON commission (society_id, provider_id, status);
SELECT sos_enable_tenant_rls('commission');

-- Read model: flats (society.flat.created/updated). Only the label is kept.
CREATE TABLE flat_directory (
  id         UUID PRIMARY KEY,                     -- = flatId
  society_id UUID        NOT NULL,
  label      TEXT,
  status     TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by UUID,
  version    BIGINT      NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('flat_directory');

-- Read model: who belongs to which flat (society.membership.created/ended). No names kept.
CREATE TABLE flat_member (
  id         UUID PRIMARY KEY,                     -- = membershipId
  society_id UUID        NOT NULL,
  flat_id    UUID        NOT NULL,
  user_id    UUID,
  kind       TEXT        NOT NULL,
  ended_at   TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by UUID,
  version    BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_flat_member_user ON flat_member (society_id, user_id) WHERE ended_at IS NULL;
SELECT sos_enable_tenant_rls('flat_member');
