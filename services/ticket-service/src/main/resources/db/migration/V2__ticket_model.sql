-- ticket_db schema (docs/architecture/03 §4, maintenance_db). Replaces the V1 prototype table
-- work_item (never used outside development) with separate complaint, breakdown and job card
-- tables, their child rows, history and the read models fed by other services' events.

DROP TABLE IF EXISTS work_item;

-- Category routing: category -> department -> default assignee (technician or vendor).
CREATE TABLE ticket_category (
  id                        UUID PRIMARY KEY,
  society_id                UUID NOT NULL,
  name                      TEXT NOT NULL,
  department                TEXT,
  default_priority          TEXT NOT NULL DEFAULT 'P3' CHECK (default_priority IN ('P1','P2','P3','P4')),
  default_assignee_user_id  UUID,
  default_vendor_id         UUID,
  resolve_mins              INT CHECK (resolve_mins IS NULL OR resolve_mins > 0),
  active                    BOOLEAN NOT NULL DEFAULT true,
  created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by                UUID,
  updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by                UUID,
  version                   BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_ticket_category_name ON ticket_category (society_id, lower(name));
SELECT sos_enable_tenant_rls('ticket_category');

CREATE TABLE complaint (
  id                 UUID PRIMARY KEY,
  society_id         UUID NOT NULL,
  number             TEXT NOT NULL,
  flat_id            UUID,
  location_id        UUID,
  asset_id           UUID,
  raised_by          UUID,
  category_id        UUID,
  category_name      TEXT,
  priority           TEXT NOT NULL CHECK (priority IN ('P1','P2','P3','P4')),
  description        TEXT NOT NULL,
  status             TEXT NOT NULL CHECK (status IN
                       ('OPEN','IN_PROGRESS','RESOLVED','CLOSED','REOPENED','CANCELLED','REJECTED')),
  job_card_id        UUID,
  sla_due_at         TIMESTAMPTZ,
  sla_breached       BOOLEAN NOT NULL DEFAULT false,
  escalation_level   INT NOT NULL DEFAULT 0,
  escalated_to_role  TEXT,
  resolved_at        TIMESTAMPTZ,
  closed_at          TIMESTAMPTZ,
  rating             SMALLINT CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
  feedback           TEXT,
  reopened_count     INT NOT NULL DEFAULT 0,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by         UUID,
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by         UUID,
  version            BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_complaint_number UNIQUE (society_id, number)
);
CREATE INDEX ix_complaint_status ON complaint (society_id, status, created_at DESC);
CREATE INDEX ix_complaint_raised_by ON complaint (society_id, raised_by, created_at DESC);
CREATE INDEX ix_complaint_flat ON complaint (society_id, flat_id, created_at DESC) WHERE flat_id IS NOT NULL;
SELECT sos_enable_tenant_rls('complaint');

CREATE TABLE breakdown (
  id                      UUID PRIMARY KEY,
  society_id              UUID NOT NULL,
  number                  TEXT NOT NULL,
  asset_id                UUID,
  location_id             UUID,
  reported_by             UUID,
  fault                   TEXT NOT NULL,
  priority                TEXT NOT NULL CHECK (priority IN ('P1','P2','P3','P4')),
  status                  TEXT NOT NULL CHECK (status IN ('REPORTED','IN_REPAIR','RESOLVED')),
  source                  TEXT NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','CHECKLIST')),
  source_ref              TEXT,
  job_card_id             UUID,
  reported_at             TIMESTAMPTZ NOT NULL,
  expected_resolution_at  TIMESTAMPTZ,
  resolved_at             TIMESTAMPTZ,
  downtime_mins           INT,
  sla_breached            BOOLEAN NOT NULL DEFAULT false,
  escalation_level        INT NOT NULL DEFAULT 0,
  escalated_to_role       TEXT,
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by              UUID,
  updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by              UUID,
  version                 BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_breakdown_number UNIQUE (society_id, number)
);
CREATE INDEX ix_breakdown_status ON breakdown (society_id, status, reported_at DESC);
CREATE UNIQUE INDEX ux_breakdown_source_ref ON breakdown (society_id, source_ref) WHERE source_ref IS NOT NULL;
SELECT sos_enable_tenant_rls('breakdown');

-- Photos attached to a complaint or breakdown (media-service ids; the files never reach events).
CREATE TABLE ticket_media (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  ticket_type  TEXT NOT NULL CHECK (ticket_type IN ('COMPLAINT','BREAKDOWN')),
  ticket_id    UUID NOT NULL,
  media_id     UUID NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_ticket_media UNIQUE (society_id, ticket_type, ticket_id, media_id)
);
SELECT sos_enable_tenant_rls('ticket_media');

CREATE TABLE job_card (
  id                    UUID PRIMARY KEY,
  society_id            UUID NOT NULL,
  number                TEXT NOT NULL,
  source_type           TEXT NOT NULL CHECK (source_type IN ('COMPLAINT','BREAKDOWN','PM','CHECKLIST','INCIDENT')),
  source_id             UUID,
  flat_id               UUID,
  location_id           UUID,
  asset_id              UUID,
  category_name         TEXT,
  priority              TEXT NOT NULL CHECK (priority IN ('P1','P2','P3','P4')),
  fault                 TEXT NOT NULL,
  status                TEXT NOT NULL CHECK (status IN
                          ('OPEN','ASSIGNED','IN_PROGRESS','WAITING','COMPLETED','VERIFIED','CLOSED','REOPENED')),
  assignee_user_id      UUID,
  vendor_id             UUID,
  assigned_at           TIMESTAMPTZ,
  waiting_reason        TEXT CHECK (waiting_reason IS NULL OR waiting_reason IN ('SPARE','VENDOR','OTHER')),
  started_at            TIMESTAMPTZ,
  completed_at          TIMESTAMPTZ,
  work_done             TEXT,
  root_cause            TEXT,
  verified_by           UUID,
  verified_at           TIMESTAMPTZ,
  closed_by             UUID,
  closed_at             TIMESTAMPTZ,
  labour_cost_paise     BIGINT NOT NULL DEFAULT 0 CHECK (labour_cost_paise >= 0),
  spare_cost_paise      BIGINT NOT NULL DEFAULT 0 CHECK (spare_cost_paise >= 0),
  recoverable_flat_id   UUID,
  approval_status       TEXT NOT NULL DEFAULT 'NOT_REQUIRED'
                          CHECK (approval_status IN ('NOT_REQUIRED','PENDING','APPROVED','REJECTED')),
  approval_instance_id  UUID,
  resident_confirmed    BOOLEAN,
  sla_breached          BOOLEAN NOT NULL DEFAULT false,
  escalation_level      INT NOT NULL DEFAULT 0,
  escalated_to_role     TEXT,
  reopened_count        INT NOT NULL DEFAULT 0,
  locked_at             TIMESTAMPTZ,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by            UUID,
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by            UUID,
  version               BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_job_card_number UNIQUE (society_id, number)
);
CREATE INDEX ix_job_card_status ON job_card (society_id, status, created_at DESC);
CREATE INDEX ix_job_card_assignee ON job_card (society_id, assignee_user_id, status) WHERE assignee_user_id IS NOT NULL;
CREATE INDEX ix_job_card_asset ON job_card (society_id, asset_id, created_at DESC) WHERE asset_id IS NOT NULL;
-- One open job card per source (a closed one may be followed by a new card after a reopen).
CREATE UNIQUE INDEX ux_job_card_active_source ON job_card (society_id, source_type, source_id)
  WHERE source_id IS NOT NULL AND status <> 'CLOSED';
SELECT sos_enable_tenant_rls('job_card');
-- Closed job cards are locked: the row can no longer change (docs/architecture/06 §2).
CREATE TRIGGER trg_job_card_locked BEFORE UPDATE OR DELETE ON job_card
  FOR EACH ROW EXECUTE FUNCTION sos_prevent_locked_change();

-- Work log (JobCardLabour): who worked, how long, labour cost.
CREATE TABLE job_card_labour (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  job_card_id     UUID NOT NULL REFERENCES job_card (id),
  worker_user_id  UUID,
  note            TEXT NOT NULL,
  minutes         INT NOT NULL CHECK (minutes >= 0),
  cost_paise      BIGINT NOT NULL DEFAULT 0 CHECK (cost_paise >= 0),
  logged_at       TIMESTAMPTZ NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_job_card_labour_card ON job_card_labour (society_id, job_card_id, logged_at);
SELECT sos_enable_tenant_rls('job_card_labour');

-- Spares requested for / issued to a job card (issue confirmed by inventory.spare.issued).
CREATE TABLE job_card_spare (
  id               UUID PRIMARY KEY,
  society_id       UUID NOT NULL,
  job_card_id      UUID NOT NULL REFERENCES job_card (id),
  spare_id         UUID NOT NULL,
  store_id         UUID,
  qty              INT NOT NULL CHECK (qty > 0),
  unit_cost_paise  BIGINT NOT NULL DEFAULT 0 CHECK (unit_cost_paise >= 0),
  status           TEXT NOT NULL CHECK (status IN ('REQUESTED','ISSUED','CANCELLED')),
  note             TEXT,
  requested_by     UUID,
  issue_id         UUID,
  issued_at        TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_job_card_spare_card ON job_card_spare (society_id, job_card_id);
CREATE UNIQUE INDEX ux_job_card_spare_issue ON job_card_spare (society_id, issue_id) WHERE issue_id IS NOT NULL;
SELECT sos_enable_tenant_rls('job_card_spare');

CREATE TABLE job_card_evidence (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  job_card_id  UUID NOT NULL REFERENCES job_card (id),
  stage        TEXT NOT NULL CHECK (stage IN ('BEFORE','DURING','AFTER')),
  media_id     UUID NOT NULL,
  taken_at     TIMESTAMPTZ NOT NULL,
  lat          DOUBLE PRECISION,
  lng          DOUBLE PRECISION,
  taken_by     UUID,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_job_card_evidence UNIQUE (society_id, job_card_id, media_id)
);
SELECT sos_enable_tenant_rls('job_card_evidence');

-- History of every ticket (complaint, breakdown, job card): status changes, notes, escalations.
CREATE TABLE ticket_event (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  ticket_type  TEXT NOT NULL CHECK (ticket_type IN ('COMPLAINT','BREAKDOWN','JOBCARD')),
  ticket_id    UUID NOT NULL,
  at           TIMESTAMPTZ NOT NULL,
  actor_id     UUID,
  from_status  TEXT,
  to_status    TEXT,
  note         TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_ticket_event_ticket ON ticket_event (society_id, ticket_type, ticket_id, at);
SELECT sos_enable_tenant_rls('ticket_event');

-- Read models (id = the source aggregate id) -----------------------------------------------

CREATE TABLE flat_directory (   -- society.flat.created / .updated
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  label       TEXT NOT NULL,
  tower_name  TEXT,
  status      TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('flat_directory');

CREATE TABLE flat_member (      -- society.membership.created / .ended
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  flat_id     UUID NOT NULL,
  user_id     UUID NOT NULL,
  kind        TEXT,
  active      BOOLEAN NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_flat_member_user ON flat_member (society_id, user_id) WHERE active;
CREATE INDEX ix_flat_member_flat ON flat_member (society_id, flat_id) WHERE active;
SELECT sos_enable_tenant_rls('flat_member');

CREATE TABLE location_ref (     -- society.location.created
  id          UUID PRIMARY KEY,
  society_id  UUID NOT NULL,
  kind        TEXT,
  name        TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('location_ref');

CREATE TABLE asset_summary (    -- asset.asset.created / .updated / .status_changed
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  code         TEXT NOT NULL,
  name         TEXT NOT NULL,
  location_id  UUID,
  status       TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('asset_summary');

CREATE TABLE role_holder (      -- identity.role.assigned / .revoked (who to alert on escalation)
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
