-- vendor-service: vendor master, agents, RFQs and quotes, purchase orders, GRNs, vendor invoices
-- (3-way match) and vendor payments. Every table is tenant-scoped with RLS.

-- ---------------------------------------------------------------- vendor master
CREATE TABLE vendor (
  id                    UUID PRIMARY KEY,
  society_id            UUID NOT NULL,
  code                  TEXT NOT NULL,
  name                  TEXT NOT NULL,
  category              TEXT NOT NULL,
  work_scopes           TEXT[] NOT NULL DEFAULT '{}',
  gstin                 TEXT,
  pan_enc               TEXT,                 -- FieldCrypto
  contact_name          TEXT,
  contact_phone_enc     TEXT,                 -- FieldCrypto
  contact_email_enc     TEXT,                 -- FieldCrypto
  address               TEXT,
  agreement_ref         TEXT,
  agreement_valid_until DATE,
  risk_level            TEXT NOT NULL DEFAULT 'LOW' CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
  status                TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLACKLISTED')),
  rating_count          INT NOT NULL DEFAULT 0,
  rating_total          INT NOT NULL DEFAULT 0,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by            UUID,
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by            UUID,
  version               BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_vendor_code ON vendor (society_id, code);
CREATE INDEX ix_vendor_category ON vendor (society_id, category);
SELECT sos_enable_tenant_rls('vendor');

CREATE TABLE vendor_kyc_document (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  vendor_id    UUID NOT NULL REFERENCES vendor (id),
  kind         TEXT NOT NULL CHECK (kind IN ('GST', 'PAN', 'AGREEMENT', 'INSURANCE', 'ESI_PF', 'NDA', 'LICENCE', 'OTHER')),
  media_id     UUID NOT NULL,
  valid_until  DATE,
  verified_at  TIMESTAMPTZ,
  verified_by  UUID,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_vendor_kyc_document_vendor ON vendor_kyc_document (society_id, vendor_id);
SELECT sos_enable_tenant_rls('vendor_kyc_document');

CREATE TABLE vendor_rating (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  vendor_id    UUID NOT NULL REFERENCES vendor (id),
  po_id        UUID,
  score        INT NOT NULL CHECK (score BETWEEN 1 AND 5),
  comment      TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_vendor_rating_vendor ON vendor_rating (society_id, vendor_id);
SELECT sos_enable_tenant_rls('vendor_rating');

-- A person working for a vendor (or in-house). A linked user id gives vendor-portal access.
CREATE TABLE agent (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  vendor_id    UUID REFERENCES vendor (id),
  user_id      UUID,
  code         TEXT NOT NULL,
  name         TEXT NOT NULL,
  role         TEXT NOT NULL,
  skills       TEXT[] NOT NULL DEFAULT '{}',
  status       TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_agent_code ON agent (society_id, code);
CREATE UNIQUE INDEX ux_agent_user ON agent (society_id, user_id) WHERE user_id IS NOT NULL;
SELECT sos_enable_tenant_rls('agent');

-- ---------------------------------------------------------------- RFQ and quotes
CREATE TABLE rfq (
  id              UUID PRIMARY KEY,
  society_id      UUID NOT NULL,
  number          TEXT NOT NULL,
  title           TEXT NOT NULL,
  description     TEXT,
  store_id        UUID,
  invited_vendor_ids UUID[] NOT NULL DEFAULT '{}',
  due_on          DATE,
  status          TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'AWARDED', 'CANCELLED')),
  awarded_quote_id UUID,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID,
  version         BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_rfq_number ON rfq (society_id, number);
SELECT sos_enable_tenant_rls('rfq');

CREATE TABLE rfq_line (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  rfq_id       UUID NOT NULL REFERENCES rfq (id),
  line_no      INT NOT NULL,
  item_code    TEXT,
  spare_id     UUID,
  description  TEXT NOT NULL,
  qty          INT NOT NULL CHECK (qty > 0),
  unit         TEXT NOT NULL DEFAULT 'NOS',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_rfq_line_no ON rfq_line (rfq_id, line_no);
SELECT sos_enable_tenant_rls('rfq_line');

CREATE TABLE quote (
  id            UUID PRIMARY KEY,
  society_id    UUID NOT NULL,
  rfq_id        UUID NOT NULL REFERENCES rfq (id),
  vendor_id     UUID NOT NULL REFERENCES vendor (id),
  valid_until   DATE,
  delivery_days INT,
  notes         TEXT,
  subtotal_paise BIGINT NOT NULL,
  tax_paise     BIGINT NOT NULL,
  total_paise   BIGINT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by    UUID,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by    UUID,
  version       BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_quote_vendor ON quote (rfq_id, vendor_id);
SELECT sos_enable_tenant_rls('quote');

CREATE TABLE quote_line (
  id               UUID PRIMARY KEY,
  society_id       UUID NOT NULL,
  quote_id         UUID NOT NULL REFERENCES quote (id),
  rfq_line_id      UUID NOT NULL REFERENCES rfq_line (id),
  unit_price_paise BIGINT NOT NULL CHECK (unit_price_paise >= 0),
  gst_percent      INT NOT NULL DEFAULT 18 CHECK (gst_percent BETWEEN 0 AND 28),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT NOT NULL DEFAULT 0
);
SELECT sos_enable_tenant_rls('quote_line');

-- ---------------------------------------------------------------- purchase orders
CREATE TABLE purchase_order (
  id                   UUID PRIMARY KEY,
  society_id           UUID NOT NULL,
  number               TEXT NOT NULL,
  vendor_id            UUID NOT NULL REFERENCES vendor (id),
  rfq_id               UUID,
  quote_id             UUID,
  store_id             UUID,
  title                TEXT NOT NULL,
  status               TEXT NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED',
                                                        'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED')),
  subtotal_paise       BIGINT NOT NULL,
  tax_paise            BIGINT NOT NULL,
  total_paise          BIGINT NOT NULL,
  expected_on          DATE,
  submitted_at         TIMESTAMPTZ,
  workflow_instance_id UUID,
  decided_at           TIMESTAMPTZ,
  decided_by           UUID,
  decision_comment     TEXT,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by           UUID,
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by           UUID,
  version              BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_purchase_order_number ON purchase_order (society_id, number);
CREATE INDEX ix_purchase_order_vendor ON purchase_order (society_id, vendor_id, created_at DESC);
CREATE INDEX ix_purchase_order_status ON purchase_order (society_id, status, created_at DESC);
SELECT sos_enable_tenant_rls('purchase_order');

CREATE TABLE po_line (
  id               UUID PRIMARY KEY,
  society_id       UUID NOT NULL,
  po_id            UUID NOT NULL REFERENCES purchase_order (id),
  line_no          INT NOT NULL,
  item_code        TEXT,
  spare_id         UUID,
  description      TEXT NOT NULL,
  qty              INT NOT NULL CHECK (qty > 0),
  unit             TEXT NOT NULL DEFAULT 'NOS',
  unit_price_paise BIGINT NOT NULL CHECK (unit_price_paise >= 0),
  gst_percent      INT NOT NULL DEFAULT 18 CHECK (gst_percent BETWEEN 0 AND 28),
  received_qty     INT NOT NULL DEFAULT 0 CHECK (received_qty >= 0 AND received_qty <= qty),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_po_line_no ON po_line (po_id, line_no);
SELECT sos_enable_tenant_rls('po_line');

-- ---------------------------------------------------------------- goods received notes
CREATE TABLE grn (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  number       TEXT NOT NULL,
  po_id        UUID NOT NULL REFERENCES purchase_order (id),
  store_id     UUID,
  received_on  DATE NOT NULL,
  challan_ref  TEXT,
  note         TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_grn_number ON grn (society_id, number);
CREATE INDEX ix_grn_po ON grn (society_id, po_id);
SELECT sos_enable_tenant_rls('grn');

CREATE TABLE grn_line (
  id            UUID PRIMARY KEY,
  society_id    UUID NOT NULL,
  grn_id        UUID NOT NULL REFERENCES grn (id),
  po_line_id    UUID NOT NULL REFERENCES po_line (id),
  received_qty  INT NOT NULL CHECK (received_qty >= 0),
  accepted_qty  INT NOT NULL CHECK (accepted_qty >= 0 AND accepted_qty <= received_qty),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by    UUID,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by    UUID,
  version       BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_grn_line_po_line ON grn_line (society_id, po_line_id);
SELECT sos_enable_tenant_rls('grn_line');

-- ---------------------------------------------------------------- vendor invoices and payments
CREATE TABLE vendor_invoice (
  id                UUID PRIMARY KEY,
  society_id        UUID NOT NULL,
  number            TEXT NOT NULL,              -- internal VINV-2026-000001
  vendor_invoice_no TEXT NOT NULL,              -- the vendor's own number
  vendor_id         UUID NOT NULL REFERENCES vendor (id),
  po_id             UUID NOT NULL REFERENCES purchase_order (id),
  invoice_date      DATE NOT NULL,
  subtotal_paise    BIGINT NOT NULL,
  tax_paise         BIGINT NOT NULL,
  total_paise       BIGINT NOT NULL,
  paid_paise        BIGINT NOT NULL DEFAULT 0,
  media_id          UUID,
  status            TEXT NOT NULL CHECK (status IN ('MATCHED', 'MISMATCH', 'APPROVED', 'REJECTED', 'PARTIALLY_PAID', 'PAID')),
  match_issues      TEXT[] NOT NULL DEFAULT '{}',
  matched_at        TIMESTAMPTZ,
  decided_at        TIMESTAMPTZ,
  decided_by        UUID,
  decision_comment  TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by        UUID,
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by        UUID,
  version           BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_vendor_invoice_number ON vendor_invoice (society_id, number);
CREATE UNIQUE INDEX ux_vendor_invoice_vendor_no ON vendor_invoice (society_id, vendor_id, vendor_invoice_no);
CREATE INDEX ix_vendor_invoice_po ON vendor_invoice (society_id, po_id);
SELECT sos_enable_tenant_rls('vendor_invoice');

CREATE TABLE vendor_invoice_line (
  id               UUID PRIMARY KEY,
  society_id       UUID NOT NULL,
  invoice_id       UUID NOT NULL REFERENCES vendor_invoice (id),
  po_line_id       UUID NOT NULL REFERENCES po_line (id),
  qty              INT NOT NULL CHECK (qty > 0),
  unit_price_paise BIGINT NOT NULL CHECK (unit_price_paise >= 0),
  tax_paise        BIGINT NOT NULL CHECK (tax_paise >= 0),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       UUID,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       UUID,
  version          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_vendor_invoice_line_po_line ON vendor_invoice_line (society_id, po_line_id);
SELECT sos_enable_tenant_rls('vendor_invoice_line');

CREATE TABLE vendor_payment (
  id           UUID PRIMARY KEY,
  society_id   UUID NOT NULL,
  invoice_id   UUID NOT NULL REFERENCES vendor_invoice (id),
  amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
  paid_on      DATE NOT NULL,
  mode         TEXT NOT NULL CHECK (mode IN ('NEFT', 'RTGS', 'UPI', 'CHEQUE', 'CASH', 'OTHER')),
  reference    TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by   UUID,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   UUID,
  version      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_vendor_payment_invoice ON vendor_payment (society_id, invoice_id);
SELECT sos_enable_tenant_rls('vendor_payment');
