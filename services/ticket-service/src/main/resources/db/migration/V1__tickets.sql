CREATE TABLE work_item (
  id UUID PRIMARY KEY,
  society_id UUID NOT NULL,
  ticket_no TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('COMPLAINT','JOBCARD')),
  source_type TEXT CHECK (source_type IS NULL OR source_type IN ('COMPLAINT','BREAKDOWN','PM','CHECKLIST','INCIDENT')),
  source_id UUID,
  flat_id UUID,
  location_id UUID,
  asset_id UUID,
  category_name TEXT,
  priority TEXT NOT NULL CHECK (priority IN ('P1','P2','P3','P4')),
  description TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('OPEN','ASSIGNED','IN_PROGRESS','WAITING','COMPLETED','VERIFIED','CLOSED','REOPENED')),
  assignee_user_id UUID,
  vendor_id UUID,
  work_done TEXT,
  root_cause TEXT,
  labour_cost_paise BIGINT NOT NULL DEFAULT 0 CHECK (labour_cost_paise >= 0),
  spare_cost_paise BIGINT NOT NULL DEFAULT 0 CHECK (spare_cost_paise >= 0),
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  closed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by UUID,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_work_item_ticket_no UNIQUE (society_id, ticket_no)
);
CREATE INDEX ix_work_item_kind_status ON work_item (society_id, kind, status, created_at DESC);
CREATE INDEX ix_work_item_asset ON work_item (society_id, asset_id, created_at DESC) WHERE asset_id IS NOT NULL;
CREATE UNIQUE INDEX ux_work_item_source_job ON work_item (society_id, source_type, source_id)
  WHERE kind = 'JOBCARD' AND source_id IS NOT NULL;
SELECT sos_enable_tenant_rls('work_item');
