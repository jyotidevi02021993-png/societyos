-- community-service schema: notices, polls, community events, facility bookings and the
-- read models copied from society-service events. Every tenant table has RLS.

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------- read models (society events)
CREATE TABLE facility_ref (
  id                     UUID PRIMARY KEY,           -- = society facilityId
  society_id             UUID NOT NULL,
  kind                   TEXT NOT NULL,
  name                   TEXT NOT NULL,
  capacity               INT  NOT NULL,
  chargeable             BOOLEAN NOT NULL DEFAULT false,
  charge_paise           BIGINT  NOT NULL DEFAULT 0,
  slot_minutes           INT  NOT NULL,
  max_advance_days       INT  NOT NULL,
  max_per_flat_per_week  INT  NOT NULL,
  status                 TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('facility_ref');

CREATE TABLE flat_ref (
  id         UUID PRIMARY KEY,                       -- = society flatId
  society_id UUID NOT NULL,
  tower_id   UUID,
  label      TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('flat_ref');
CREATE INDEX ix_flat_ref_tower ON flat_ref (society_id, tower_id);

CREATE TABLE membership_ref (
  id         UUID PRIMARY KEY,                       -- = society membershipId
  society_id UUID NOT NULL,
  flat_id    UUID NOT NULL,
  user_id    UUID NOT NULL,
  kind       TEXT NOT NULL,
  active     BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('membership_ref');
CREATE INDEX ix_membership_ref_user ON membership_ref (society_id, user_id) WHERE active;
CREATE INDEX ix_membership_ref_flat ON membership_ref (society_id, flat_id) WHERE active;

-- ---------------------------------------------------------------- notices
CREATE TABLE notice (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  title        TEXT NOT NULL,
  body         TEXT NOT NULL,
  audience     JSONB NOT NULL,                       -- {all, towerIds[], roles[]}
  attachments  JSONB NOT NULL DEFAULT '[]',          -- [mediaId]
  pinned       BOOLEAN NOT NULL DEFAULT false,
  publish_at   TIMESTAMPTZ NOT NULL,
  expires_at   TIMESTAMPTZ,
  status       TEXT NOT NULL CHECK (status IN ('SCHEDULED', 'PUBLISHED', 'WITHDRAWN')),
  published_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('notice');
CREATE INDEX ix_notice_feed ON notice (society_id, status, pinned DESC, publish_at DESC);
CREATE INDEX ix_notice_due ON notice (publish_at) WHERE status = 'SCHEDULED';

CREATE TABLE notice_read (
  id         UUID PRIMARY KEY,
  society_id UUID NOT NULL,
  notice_id  UUID NOT NULL REFERENCES notice (id) ON DELETE CASCADE,
  user_id    UUID NOT NULL,
  read_at    TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0,
  UNIQUE (notice_id, user_id)
);
SELECT sos_enable_tenant_rls('notice_read');

-- ---------------------------------------------------------------- polls
CREATE TABLE poll (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  question     TEXT NOT NULL,
  one_vote_per TEXT NOT NULL CHECK (one_vote_per IN ('FLAT', 'MEMBER')),
  multi_choice BOOLEAN NOT NULL DEFAULT false,
  max_choices  INT NOT NULL DEFAULT 1 CHECK (max_choices >= 1),
  opens_at     TIMESTAMPTZ NOT NULL,
  closes_at    TIMESTAMPTZ,
  status       TEXT NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
  closed_at    TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('poll');
CREATE INDEX ix_poll_due ON poll (closes_at) WHERE status = 'OPEN';

CREATE TABLE poll_option (
  id         UUID PRIMARY KEY,
  society_id UUID NOT NULL,
  poll_id    UUID NOT NULL REFERENCES poll (id) ON DELETE CASCADE,
  label      TEXT NOT NULL,
  position   INT  NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('poll_option');
CREATE INDEX ix_poll_option_poll ON poll_option (poll_id, position);

-- One ballot per flat (one_vote_per FLAT, ballot_key = flat id) or per member (= user id).
CREATE TABLE poll_ballot (
  id         UUID PRIMARY KEY,
  society_id UUID NOT NULL,
  poll_id    UUID NOT NULL REFERENCES poll (id) ON DELETE CASCADE,
  ballot_key UUID NOT NULL,
  flat_id    UUID NOT NULL,
  user_id    UUID NOT NULL,
  option_ids JSONB NOT NULL,                         -- [optionId]
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0,
  UNIQUE (poll_id, ballot_key)
);
SELECT sos_enable_tenant_rls('poll_ballot');

-- ---------------------------------------------------------------- community events + RSVP
CREATE TABLE community_event (
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  kind        TEXT NOT NULL,
  title       TEXT NOT NULL,
  description TEXT,
  location    TEXT,
  starts_at   TIMESTAMPTZ NOT NULL,
  ends_at     TIMESTAMPTZ NOT NULL,
  capacity    INT CHECK (capacity IS NULL OR capacity > 0),
  fee_paise   BIGINT NOT NULL DEFAULT 0 CHECK (fee_paise >= 0),
  status      TEXT NOT NULL CHECK (status IN ('SCHEDULED', 'CANCELLED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0,
  CHECK (ends_at > starts_at)
);
SELECT sos_enable_tenant_rls('community_event');
CREATE INDEX ix_community_event_starts ON community_event (society_id, starts_at);

CREATE TABLE event_registration (
  id         UUID PRIMARY KEY,
  society_id UUID NOT NULL,
  event_id   UUID NOT NULL REFERENCES community_event (id) ON DELETE CASCADE,
  flat_id    UUID NOT NULL,
  user_id    UUID NOT NULL,
  headcount  INT  NOT NULL CHECK (headcount >= 1),
  status     TEXT NOT NULL CHECK (status IN ('GOING', 'NOT_GOING')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0,
  UNIQUE (event_id, user_id)
);
SELECT sos_enable_tenant_rls('event_registration');

-- ---------------------------------------------------------------- facility bookings
CREATE TABLE booking (
  id            UUID PRIMARY KEY,
  society_id    UUID NOT NULL,
  facility_id   UUID NOT NULL,
  flat_id       UUID NOT NULL,
  user_id       UUID NOT NULL,
  starts_at     TIMESTAMPTZ NOT NULL,
  ends_at       TIMESTAMPTZ NOT NULL,
  guests        INT NOT NULL CHECK (guests >= 1),
  exclusive     BOOLEAN NOT NULL,                    -- whole facility (hall, court) vs shared (gym, pool)
  status        TEXT NOT NULL CHECK (status IN ('CONFIRMED', 'CANCELLED')),
  charge_paise  BIGINT NOT NULL DEFAULT 0 CHECK (charge_paise >= 0),
  cancelled_at  TIMESTAMPTZ,
  cancel_reason TEXT,
  during        TSTZRANGE GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID,
  version    BIGINT NOT NULL DEFAULT 0,
  CHECK (ends_at > starts_at),
  -- Double-booking protection for exclusive facilities, whatever the application does.
  CONSTRAINT booking_no_overlap EXCLUDE USING gist (facility_id WITH =, during WITH &&)
    WHERE (status = 'CONFIRMED' AND exclusive)
);
SELECT sos_enable_tenant_rls('booking');
CREATE INDEX ix_booking_flat ON booking (society_id, flat_id, starts_at);
CREATE INDEX ix_booking_facility ON booking (facility_id, starts_at);

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

-- Which societies have due work (scheduled notices to publish, polls to close). Runs as the
-- owner so the job can find them before binding a tenant; returns ids only.
CREATE FUNCTION community_societies_with_due_work(p_now TIMESTAMPTZ)
  RETURNS TABLE (society_id UUID)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
  SELECT n.society_id FROM notice n WHERE n.status = 'SCHEDULED' AND n.publish_at <= p_now
  UNION
  SELECT p.society_id FROM poll p WHERE p.status = 'OPEN' AND p.closes_at IS NOT NULL AND p.closes_at <= p_now
$$;
