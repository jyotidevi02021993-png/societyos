-- billing_db: charge heads (tariffs), bill runs, bills, payments, receipts, double-entry ledger,
-- expenses and budgets, plus local read copies of society data. Money is BIGINT paise everywhere.
-- Every tenant table carries society_id and the standard audit columns, and has RLS enabled.

-- ---------------------------------------------------------------------------------------------
-- Local read copies (from sos.society.events.v1)
-- ---------------------------------------------------------------------------------------------

-- id = society id. billing_due_day / late_fee_grace_days come from society.settings.updated;
-- the GST and late-fee rule columns are billing's own configuration (PUT /v1/billing/settings).
CREATE TABLE billing_settings (
  id                            UUID PRIMARY KEY,
  society_id                    UUID        NOT NULL,
  billing_due_day               INT         NOT NULL DEFAULT 10 CHECK (billing_due_day BETWEEN 1 AND 28),
  late_fee_grace_days           INT         NOT NULL DEFAULT 15 CHECK (late_fee_grace_days BETWEEN 0 AND 60),
  gst_registered                BOOLEAN     NOT NULL DEFAULT FALSE,
  gst_rate_bps                  INT         NOT NULL DEFAULT 1800 CHECK (gst_rate_bps BETWEEN 0 AND 2800),
  gst_exemption_threshold_paise BIGINT      NOT NULL DEFAULT 750000 CHECK (gst_exemption_threshold_paise >= 0),
  late_fee_kind                 TEXT        NOT NULL DEFAULT 'NONE' CHECK (late_fee_kind IN ('NONE', 'FIXED', 'PERCENT')),
  late_fee_value                BIGINT      NOT NULL DEFAULT 0 CHECK (late_fee_value >= 0),
  reminder_days_before          INT         NOT NULL DEFAULT 3 CHECK (reminder_days_before BETWEEN 0 AND 15),
  created_at                    TIMESTAMPTZ NOT NULL,
  created_by                    UUID,
  updated_at                    TIMESTAMPTZ NOT NULL,
  updated_by                    UUID,
  version                       BIGINT      NOT NULL DEFAULT 0,
  CHECK (id = society_id)
);
SELECT sos_enable_tenant_rls('billing_settings');

-- id = flat id (society.flat.created / updated): the billing roster.
CREATE TABLE flat_ref (
  id          UUID PRIMARY KEY,
  society_id  UUID        NOT NULL,
  tower_id    UUID,
  tower_name  TEXT,
  number      TEXT,
  label       TEXT        NOT NULL,
  floor       INT,
  area_sqft   INT,
  flat_type   TEXT,
  status      TEXT        NOT NULL DEFAULT 'VACANT',
  created_at  TIMESTAMPTZ NOT NULL,
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL,
  updated_by  UUID,
  version     BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_flat_ref_label ON flat_ref (society_id, label);
SELECT sos_enable_tenant_rls('flat_ref');

-- id = membership id (society.membership.created / ended): who may see and pay a flat's bills.
CREATE TABLE flat_member (
  id          UUID PRIMARY KEY,
  society_id  UUID        NOT NULL,
  flat_id     UUID        NOT NULL,
  user_id     UUID,
  kind        TEXT        NOT NULL,
  is_primary  BOOLEAN     NOT NULL DEFAULT FALSE,
  ended_at    TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL,
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL,
  updated_by  UUID,
  version     BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_flat_member_user ON flat_member (society_id, user_id) WHERE ended_at IS NULL;
CREATE INDEX ix_flat_member_flat ON flat_member (society_id, flat_id) WHERE ended_at IS NULL;
SELECT sos_enable_tenant_rls('flat_member');

-- ---------------------------------------------------------------------------------------------
-- Tariffs
-- ---------------------------------------------------------------------------------------------

-- basis FIXED: rate_paise per flat; PER_SQFT: rate_paise per sq ft of area;
-- FLAT_TYPE: flat_type_rates {"2BHK": 250000, ...}, rate_paise as the fallback for other types.
CREATE TABLE charge_head (
  id                 UUID PRIMARY KEY,
  society_id         UUID        NOT NULL,
  code               TEXT        NOT NULL,
  name               TEXT        NOT NULL,
  basis              TEXT        NOT NULL CHECK (basis IN ('FIXED', 'PER_SQFT', 'FLAT_TYPE')),
  rate_paise         BIGINT      NOT NULL DEFAULT 0 CHECK (rate_paise >= 0),
  flat_type_rates    JSONB       NOT NULL DEFAULT '{}'::jsonb,
  gst_applicable     BOOLEAN     NOT NULL DEFAULT TRUE,
  applies_to_vacant  BOOLEAN     NOT NULL DEFAULT TRUE,
  active             BOOLEAN     NOT NULL DEFAULT TRUE,
  sort_order         INT         NOT NULL DEFAULT 0,
  created_at         TIMESTAMPTZ NOT NULL,
  created_by         UUID,
  updated_at         TIMESTAMPTZ NOT NULL,
  updated_by         UUID,
  version            BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_charge_head_code ON charge_head (society_id, upper(code));
SELECT sos_enable_tenant_rls('charge_head');

-- One-off charges waiting for the next bill run (facility bookings, manual charges, recoverables).
CREATE TABLE pending_charge (
  id              UUID PRIMARY KEY,
  society_id      UUID        NOT NULL,
  flat_id         UUID        NOT NULL,
  source_type     TEXT        NOT NULL CHECK (source_type IN ('BOOKING', 'MANUAL', 'RECOVERABLE')),
  source_ref      UUID,
  description     TEXT        NOT NULL,
  amount_paise    BIGINT      NOT NULL CHECK (amount_paise > 0),
  gst_applicable  BOOLEAN     NOT NULL DEFAULT FALSE,
  status          TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'BILLED', 'CANCELLED')),
  bill_id         UUID,
  created_at      TIMESTAMPTZ NOT NULL,
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL,
  updated_by      UUID,
  version         BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_pending_charge_source ON pending_charge (society_id, source_type, source_ref)
  WHERE source_ref IS NOT NULL;
CREATE INDEX ix_pending_charge_flat ON pending_charge (society_id, flat_id) WHERE status = 'PENDING';
SELECT sos_enable_tenant_rls('pending_charge');

-- ---------------------------------------------------------------------------------------------
-- Bill runs and bills
-- ---------------------------------------------------------------------------------------------

CREATE TABLE bill_run (
  id            UUID PRIMARY KEY,
  society_id    UUID        NOT NULL,
  period        TEXT        NOT NULL CHECK (period ~ '^[0-9]{6}$'),   -- YYYYMM
  status        TEXT        NOT NULL CHECK (status IN ('PREVIEW', 'PUBLISHED', 'DISCARDED')),
  bill_date     DATE        NOT NULL,
  due_date      DATE        NOT NULL,
  bill_count    INT         NOT NULL DEFAULT 0,
  amount_paise  BIGINT      NOT NULL DEFAULT 0,
  gst_paise     BIGINT      NOT NULL DEFAULT 0,
  total_paise   BIGINT      NOT NULL DEFAULT 0,
  warnings      JSONB       NOT NULL DEFAULT '[]'::jsonb,
  published_at  TIMESTAMPTZ,
  published_by  UUID,
  locked_at     TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL,
  created_by    UUID,
  updated_at    TIMESTAMPTZ NOT NULL,
  updated_by    UUID,
  version       BIGINT      NOT NULL DEFAULT 0
);
-- One live run (preview or published) per society and period.
CREATE UNIQUE INDEX ux_bill_run_period ON bill_run (society_id, period) WHERE status IN ('PREVIEW', 'PUBLISHED');
SELECT sos_enable_tenant_rls('bill_run');
-- A published run is closed: its totals never change.
CREATE TRIGGER trg_bill_run_locked BEFORE UPDATE OR DELETE ON bill_run
  FOR EACH ROW EXECUTE FUNCTION sos_prevent_locked_change();

-- balance = total + late fee + adjustments (debit +, credit -) - paid; always consistent.
CREATE TABLE bill (
  id                   UUID PRIMARY KEY,
  society_id           UUID        NOT NULL,
  bill_run_id          UUID        NOT NULL REFERENCES bill_run (id),
  flat_id              UUID        NOT NULL,
  flat_label           TEXT        NOT NULL,
  number               TEXT,
  period               TEXT        NOT NULL,
  bill_date            DATE        NOT NULL,
  due_date             DATE        NOT NULL,
  amount_paise         BIGINT      NOT NULL CHECK (amount_paise >= 0),
  gst_paise            BIGINT      NOT NULL CHECK (gst_paise >= 0),
  total_paise          BIGINT      NOT NULL,
  late_fee_paise       BIGINT      NOT NULL DEFAULT 0 CHECK (late_fee_paise >= 0),
  adjustment_paise     BIGINT      NOT NULL DEFAULT 0,
  paid_paise           BIGINT      NOT NULL DEFAULT 0 CHECK (paid_paise >= 0),
  balance_paise        BIGINT      NOT NULL CHECK (balance_paise >= 0),
  arrears_paise        BIGINT      NOT NULL DEFAULT 0,
  status               TEXT        NOT NULL CHECK (status IN ('DRAFT', 'DUE', 'PART_PAID', 'PAID', 'CANCELLED')),
  published_at         TIMESTAMPTZ,
  reminder_sent_at     TIMESTAMPTZ,
  overdue_notified_at  TIMESTAMPTZ,
  late_fee_applied_at  TIMESTAMPTZ,
  created_at           TIMESTAMPTZ NOT NULL,
  created_by           UUID,
  updated_at           TIMESTAMPTZ NOT NULL,
  updated_by           UUID,
  version              BIGINT      NOT NULL DEFAULT 0,
  CHECK (total_paise = amount_paise + gst_paise),
  CHECK (balance_paise = total_paise + late_fee_paise + adjustment_paise - paid_paise)
);
CREATE UNIQUE INDEX ux_bill_number ON bill (society_id, number) WHERE number IS NOT NULL;
CREATE UNIQUE INDEX ux_bill_run_flat ON bill (bill_run_id, flat_id);
CREATE INDEX ix_bill_flat ON bill (society_id, flat_id, due_date);
CREATE INDEX ix_bill_open ON bill (society_id, due_date) WHERE status IN ('DUE', 'PART_PAID');
SELECT sos_enable_tenant_rls('bill');

CREATE TABLE bill_line (
  id            UUID PRIMARY KEY,
  society_id    UUID        NOT NULL,
  bill_id       UUID        NOT NULL REFERENCES bill (id),
  kind          TEXT        NOT NULL CHECK (kind IN ('CHARGE', 'ONE_OFF')),
  code          TEXT,
  description   TEXT        NOT NULL,
  amount_paise  BIGINT      NOT NULL CHECK (amount_paise >= 0),
  gst_paise     BIGINT      NOT NULL CHECK (gst_paise >= 0),
  source_ref    UUID,
  sort_order    INT         NOT NULL DEFAULT 0,
  locked_at     TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL,
  created_by    UUID,
  updated_at    TIMESTAMPTZ NOT NULL,
  updated_by    UUID,
  version       BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_bill_line_bill ON bill_line (bill_id);
SELECT sos_enable_tenant_rls('bill_line');
-- Lines of a published bill are immutable; corrections are adjustments (credit / debit notes).
CREATE TRIGGER trg_bill_line_locked BEFORE UPDATE OR DELETE ON bill_line
  FOR EACH ROW EXECUTE FUNCTION sos_prevent_locked_change();

-- Credit notes (CN-…) and debit notes (DN-…) against a published bill.
CREATE TABLE bill_adjustment (
  id            UUID PRIMARY KEY,
  society_id    UUID        NOT NULL,
  bill_id       UUID        NOT NULL REFERENCES bill (id),
  flat_id       UUID        NOT NULL,
  number        TEXT        NOT NULL,
  kind          TEXT        NOT NULL CHECK (kind IN ('CREDIT', 'DEBIT')),
  amount_paise  BIGINT      NOT NULL CHECK (amount_paise > 0),
  reason        TEXT        NOT NULL,
  locked_at     TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  created_by    UUID,
  updated_at    TIMESTAMPTZ NOT NULL,
  updated_by    UUID,
  version       BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_bill_adjustment_number ON bill_adjustment (society_id, number);
CREATE INDEX ix_bill_adjustment_bill ON bill_adjustment (bill_id);
SELECT sos_enable_tenant_rls('bill_adjustment');
CREATE TRIGGER trg_bill_adjustment_locked BEFORE UPDATE OR DELETE ON bill_adjustment
  FOR EACH ROW EXECUTE FUNCTION sos_prevent_locked_change();

-- ---------------------------------------------------------------------------------------------
-- Payments and receipts
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payment (
  id                  UUID PRIMARY KEY,
  society_id          UUID        NOT NULL,
  flat_id             UUID        NOT NULL,
  bill_id             UUID REFERENCES bill (id),
  amount_paise        BIGINT      NOT NULL CHECK (amount_paise > 0),
  method              TEXT        NOT NULL CHECK (method IN ('ONLINE', 'CASH', 'CHEQUE', 'UPI', 'BANK_TRANSFER')),
  gateway             TEXT,
  gateway_order_id    TEXT UNIQUE,
  gateway_payment_id  TEXT UNIQUE,
  reference           TEXT,
  status              TEXT        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
  failure_reason      TEXT,
  paid_at             TIMESTAMPTZ,
  created_at          TIMESTAMPTZ NOT NULL,
  created_by          UUID,
  updated_at          TIMESTAMPTZ NOT NULL,
  updated_by          UUID,
  version             BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_payment_flat ON payment (society_id, flat_id, created_at DESC);
CREATE INDEX ix_payment_pending ON payment (created_at) WHERE status = 'PENDING';
SELECT sos_enable_tenant_rls('payment');

-- How a succeeded payment settled bills (oldest due first); the unallocated rest is an advance.
CREATE TABLE payment_allocation (
  id            UUID PRIMARY KEY,
  society_id    UUID        NOT NULL,
  payment_id    UUID        NOT NULL REFERENCES payment (id),
  bill_id       UUID        NOT NULL REFERENCES bill (id),
  amount_paise  BIGINT      NOT NULL CHECK (amount_paise > 0),
  created_at    TIMESTAMPTZ NOT NULL,
  created_by    UUID,
  updated_at    TIMESTAMPTZ NOT NULL,
  updated_by    UUID,
  version       BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_payment_allocation_payment ON payment_allocation (payment_id);
CREATE INDEX ix_payment_allocation_bill ON payment_allocation (bill_id);
SELECT sos_enable_tenant_rls('payment_allocation');

-- An issued receipt is locked on insert.
CREATE TABLE receipt (
  id            UUID PRIMARY KEY,
  society_id    UUID        NOT NULL,
  payment_id    UUID        NOT NULL UNIQUE REFERENCES payment (id),
  flat_id       UUID        NOT NULL,
  flat_label    TEXT        NOT NULL,
  number        TEXT        NOT NULL,
  amount_paise  BIGINT      NOT NULL CHECK (amount_paise > 0),
  method        TEXT        NOT NULL,
  reference     TEXT,
  issued_at     TIMESTAMPTZ NOT NULL,
  pdf_media_id  UUID,
  locked_at     TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  created_by    UUID,
  updated_at    TIMESTAMPTZ NOT NULL,
  updated_by    UUID,
  version       BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_receipt_number ON receipt (society_id, number);
CREATE INDEX ix_receipt_flat ON receipt (society_id, flat_id, issued_at DESC);
SELECT sos_enable_tenant_rls('receipt');
CREATE TRIGGER trg_receipt_locked BEFORE UPDATE OR DELETE ON receipt
  FOR EACH ROW EXECUTE FUNCTION sos_prevent_locked_change();

-- Gateway webhooks, deduplicated by (provider, event_id). Infrastructure table (no RLS): the
-- webhook arrives without a tenant; society_id is recorded once the order has been matched.
CREATE TABLE webhook_event (
  provider     TEXT        NOT NULL,
  event_id     TEXT        NOT NULL,
  society_id   UUID,
  received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  payload      JSONB       NOT NULL,
  PRIMARY KEY (provider, event_id)
);

-- ---------------------------------------------------------------------------------------------
-- Double-entry ledger
-- ---------------------------------------------------------------------------------------------

CREATE TABLE ledger_account (
  id          UUID PRIMARY KEY,
  society_id  UUID        NOT NULL,
  code        TEXT        NOT NULL,
  name        TEXT        NOT NULL,
  kind        TEXT        NOT NULL CHECK (kind IN ('ASSET', 'LIABILITY', 'INCOME', 'EXPENSE')),
  created_at  TIMESTAMPTZ NOT NULL,
  created_by  UUID,
  updated_at  TIMESTAMPTZ NOT NULL,
  updated_by  UUID,
  version     BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_ledger_account_code ON ledger_account (society_id, code);
SELECT sos_enable_tenant_rls('ledger_account');

CREATE TABLE ledger_entry (
  id            UUID PRIMARY KEY,
  society_id    UUID        NOT NULL,
  txn_id        UUID        NOT NULL,
  account_id    UUID        NOT NULL REFERENCES ledger_account (id),
  account_code  TEXT        NOT NULL,
  flat_id       UUID,
  debit_paise   BIGINT      NOT NULL DEFAULT 0 CHECK (debit_paise >= 0),
  credit_paise  BIGINT      NOT NULL DEFAULT 0 CHECK (credit_paise >= 0),
  ref_type      TEXT        NOT NULL,
  ref_id        UUID        NOT NULL,
  narration     TEXT        NOT NULL,
  entry_date    DATE        NOT NULL,
  at            TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  created_by    UUID,
  updated_at    TIMESTAMPTZ NOT NULL,
  updated_by    UUID,
  version       BIGINT      NOT NULL DEFAULT 0,
  CHECK ((debit_paise = 0) <> (credit_paise = 0))
);
CREATE INDEX ix_ledger_entry_flat ON ledger_entry (society_id, flat_id, at) WHERE flat_id IS NOT NULL;
CREATE INDEX ix_ledger_entry_date ON ledger_entry (society_id, entry_date);
CREATE INDEX ix_ledger_entry_txn ON ledger_entry (txn_id);
SELECT sos_enable_tenant_rls('ledger_entry');

-- Append-only: corrections are new balancing transactions.
CREATE FUNCTION billing_ledger_append_only() RETURNS trigger
  LANGUAGE plpgsql AS
$$
BEGIN
  RAISE EXCEPTION 'LEDGER_APPEND_ONLY: ledger entries cannot be changed' USING ERRCODE = 'check_violation';
END
$$;
CREATE TRIGGER trg_ledger_entry_append_only BEFORE UPDATE OR DELETE ON ledger_entry
  FOR EACH ROW EXECUTE FUNCTION billing_ledger_append_only();

-- SUM(debit) = SUM(credit) per txn_id, checked at commit (all entries of a txn are inserted first).
CREATE FUNCTION billing_ledger_balanced() RETURNS trigger
  LANGUAGE plpgsql AS
$$
DECLARE
  diff BIGINT;
BEGIN
  SELECT COALESCE(SUM(debit_paise), 0) - COALESCE(SUM(credit_paise), 0) INTO diff
    FROM ledger_entry WHERE txn_id = NEW.txn_id;
  IF diff <> 0 THEN
    RAISE EXCEPTION 'LEDGER_UNBALANCED: txn % is off by % paise', NEW.txn_id, diff USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER trg_ledger_entry_balanced AFTER INSERT ON ledger_entry
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION billing_ledger_balanced();

-- ---------------------------------------------------------------------------------------------
-- Expenses and budgets
-- ---------------------------------------------------------------------------------------------

CREATE TABLE expense (
  id             UUID PRIMARY KEY,
  society_id     UUID        NOT NULL,
  kind           TEXT        NOT NULL DEFAULT 'EXPENSE' CHECK (kind IN ('EXPENSE', 'VENDOR_PAYMENT')),
  category       TEXT        NOT NULL,
  description    TEXT,
  amount_paise   BIGINT      NOT NULL CHECK (amount_paise > 0),
  paid_from      TEXT        NOT NULL CHECK (paid_from IN ('BANK', 'CASH')),
  spent_on       DATE        NOT NULL,
  financial_year TEXT        NOT NULL,             -- e.g. 2026-27 (April to March)
  asset_id       UUID,
  vendor_id      UUID,
  tower_id       UUID,
  department     TEXT,
  reference      TEXT,
  created_at     TIMESTAMPTZ NOT NULL,
  created_by     UUID,
  updated_at     TIMESTAMPTZ NOT NULL,
  updated_by     UUID,
  version        BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_expense_spent ON expense (society_id, spent_on DESC);
CREATE INDEX ix_expense_budget ON expense (society_id, financial_year, category);
SELECT sos_enable_tenant_rls('expense');

CREATE TABLE budget (
  id              UUID PRIMARY KEY,
  society_id      UUID        NOT NULL,
  financial_year  TEXT        NOT NULL CHECK (financial_year ~ '^[0-9]{4}-[0-9]{2}$'),
  category        TEXT        NOT NULL,
  amount_paise    BIGINT      NOT NULL CHECK (amount_paise > 0),
  notes           TEXT,
  status          TEXT        NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED')),
  decided_by      UUID,
  decided_at      TIMESTAMPTZ,
  decision_note   TEXT,
  created_at      TIMESTAMPTZ NOT NULL,
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL,
  updated_by      UUID,
  version         BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_budget_category ON budget (society_id, financial_year, upper(category))
  WHERE status <> 'REJECTED';
SELECT sos_enable_tenant_rls('budget');

-- ---------------------------------------------------------------------------------------------
-- Scheduled jobs (ADR-0004)
-- ---------------------------------------------------------------------------------------------

-- Jobs work one society at a time; these narrow, read-only lookups return only society ids
-- across tenants. They run as the migration owner (BYPASSRLS; superuser in tests).
CREATE FUNCTION billing_societies_with_open_bills()
  RETURNS TABLE (society_id UUID)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
  SELECT DISTINCT b.society_id FROM bill b WHERE b.status IN ('DUE', 'PART_PAID')
$$;

CREATE FUNCTION billing_societies_with_pending_payments(p_before TIMESTAMPTZ)
  RETURNS TABLE (society_id UUID)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
  SELECT DISTINCT p.society_id FROM payment p WHERE p.status = 'PENDING' AND p.created_at <= p_before
$$;

-- A gateway webhook carries only the order id: find the society that owns it.
CREATE FUNCTION billing_society_of_order(p_order_id TEXT)
  RETURNS UUID
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
  SELECT p.society_id FROM payment p WHERE p.gateway_order_id = p_order_id
$$;

-- db-scheduler: infrastructure table, no RLS.
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
