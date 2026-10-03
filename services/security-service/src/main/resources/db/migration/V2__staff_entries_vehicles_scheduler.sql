-- Staff entries in the unified gate log, resident vehicle movements, and db-scheduler's table.

ALTER TABLE entry_log ADD COLUMN staff_id UUID;
CREATE INDEX ix_entry_log_staff ON entry_log (society_id, staff_id) WHERE staff_id IS NOT NULL;

ALTER TABLE staff_attendance ADD COLUMN entry_id UUID REFERENCES entry_log (id) ON DELETE SET NULL;

-- Resident vehicles passing the gate (matched against the flat_vehicle copy).
CREATE TABLE vehicle_movement (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  vehicle_id   UUID,                                         -- flat_vehicle id; NULL when unknown
  flat_id      UUID,
  reg_no       TEXT NOT NULL,
  direction    TEXT NOT NULL CHECK (direction IN ('IN', 'OUT')),
  matched_by   TEXT NOT NULL CHECK (matched_by IN ('REG_NO', 'RFID', 'NONE')),
  gate_id      UUID,
  guard_id     UUID,
  at           TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  UUID,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_vehicle_movement_at ON vehicle_movement (society_id, at DESC);
SELECT sos_enable_tenant_rls('vehicle_movement');

CREATE INDEX ix_flat_vehicle_rfid ON flat_vehicle (society_id, rfid_tag) WHERE removed_at IS NULL AND rfid_tag IS NOT NULL;

-- Retention also covers deliveries and vehicle movements.
CREATE OR REPLACE FUNCTION security_known_societies()
  RETURNS TABLE (society_id UUID)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
  SELECT s.society_id FROM society_settings s
  UNION SELECT e.society_id FROM entry_log e
  UNION SELECT v.society_id FROM visitor v
  UNION SELECT a.society_id FROM staff_attendance a
  UNION SELECT m.society_id FROM vehicle_movement m
$$;

-- db-scheduler (ADR-0004): infrastructure table, no RLS. Tasks open one transaction per society.
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

-- Passes past valid_to leave ACTIVE (EXPIRED), which frees their 6-digit code for reuse.
ALTER TABLE gate_pass DROP CONSTRAINT gate_pass_status_check;
ALTER TABLE gate_pass ADD CONSTRAINT gate_pass_status_check
  CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'CANCELLED', 'EXPIRED'));
CREATE INDEX ix_gate_pass_active_valid_to ON gate_pass (valid_to) WHERE status = 'ACTIVE';

CREATE FUNCTION security_societies_with_expired_passes(p_now TIMESTAMPTZ)
  RETURNS TABLE (society_id UUID)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
  SELECT DISTINCT p.society_id FROM gate_pass p WHERE p.status = 'ACTIVE' AND p.valid_to <= p_now
$$;
