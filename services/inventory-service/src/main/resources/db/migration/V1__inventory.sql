-- inventory-service: stores, items (spares and consumables), stock levels and the stock movement
-- ledger. Every table is tenant-scoped with RLS. A stock level never goes below zero.

CREATE TABLE store (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  code            TEXT NOT NULL,
  name            TEXT NOT NULL,
  location_id     UUID,
  keeper_user_id  UUID,
  is_default      BOOLEAN NOT NULL DEFAULT false,
  status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_store_code ON store (society_id, code);
CREATE UNIQUE INDEX ux_store_default ON store (society_id) WHERE is_default;
SELECT sos_enable_tenant_rls('store');

-- The catalogue. item.id is the "spareId" other services use (ticket job cards, vendor PO lines).
CREATE TABLE item (
  id                    UUID PRIMARY KEY,
  society_id            UUID NOT NULL,
  code                  TEXT NOT NULL,
  name                  TEXT NOT NULL,
  kind                  TEXT NOT NULL DEFAULT 'SPARE' CHECK (kind IN ('SPARE', 'CONSUMABLE', 'TOOL')),
  category              TEXT,
  unit                  TEXT NOT NULL DEFAULT 'NOS',
  default_reorder_level INT NOT NULL DEFAULT 0 CHECK (default_reorder_level >= 0),
  last_cost_paise       BIGINT NOT NULL DEFAULT 0 CHECK (last_cost_paise >= 0),
  status                TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by            UUID,
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by            UUID,
  version               BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_item_code ON item (society_id, code);
SELECT sos_enable_tenant_rls('item');

-- Current quantity per store and item, kept in step with the ledger in the same transaction.
CREATE TABLE stock_level (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  store_id        UUID NOT NULL REFERENCES store (id),
  item_id         UUID NOT NULL REFERENCES item (id),
  qty             INT NOT NULL CHECK (qty >= 0),
  avg_cost_paise  BIGINT NOT NULL DEFAULT 0 CHECK (avg_cost_paise >= 0),
  reorder_level   INT CHECK (reorder_level >= 0),       -- NULL: the item's default
  low_alerted     BOOLEAN NOT NULL DEFAULT false,       -- one alert until stock is back above the level
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_stock_level_store_item ON stock_level (society_id, store_id, item_id);
SELECT sos_enable_tenant_rls('stock_level');

-- Append-only ledger: corrections are new ADJUSTMENT rows, never updates.
CREATE TABLE stock_movement (
  id               UUID PRIMARY KEY,
  society_id       UUID NOT NULL,
  store_id         UUID NOT NULL REFERENCES store (id),
  item_id          UUID NOT NULL REFERENCES item (id),
  kind             TEXT NOT NULL CHECK (kind IN ('RECEIPT', 'ISSUE', 'RETURN', 'TRANSFER_OUT', 'TRANSFER_IN', 'ADJUSTMENT')),
  qty_delta        INT NOT NULL CHECK (qty_delta <> 0),
  balance_after    INT NOT NULL CHECK (balance_after >= 0),
  unit_cost_paise  BIGINT NOT NULL DEFAULT 0 CHECK (unit_cost_paise >= 0),
  ref_type         TEXT CHECK (ref_type IN ('GRN', 'JOBCARD', 'TRANSFER', 'ADJUSTMENT', 'OPENING')),
  ref_id           UUID,
  job_card_id      UUID,
  issued_to        UUID,
  reason           TEXT,
  at               TIMESTAMPTZ NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_stock_movement_store_item ON stock_movement (society_id, store_id, item_id, at DESC);
CREATE INDEX ix_stock_movement_job_card ON stock_movement (society_id, job_card_id) WHERE job_card_id IS NOT NULL;
-- A GRN line is booked once per store and item even if the event is redelivered.
CREATE UNIQUE INDEX ux_stock_movement_grn ON stock_movement (society_id, ref_id, store_id, item_id)
  WHERE ref_type = 'GRN' AND kind = 'RECEIPT';
SELECT sos_enable_tenant_rls('stock_movement');

CREATE OR REPLACE FUNCTION inventory_ledger_append_only() RETURNS trigger
  LANGUAGE plpgsql AS
$$
BEGIN
  RAISE EXCEPTION 'LEDGER_APPEND_ONLY: stock_movement rows are never changed'
    USING ERRCODE = 'check_violation';
END
$$;
CREATE TRIGGER trg_stock_movement_append_only BEFORE UPDATE OR DELETE ON stock_movement
  FOR EACH ROW EXECUTE FUNCTION inventory_ledger_append_only();
