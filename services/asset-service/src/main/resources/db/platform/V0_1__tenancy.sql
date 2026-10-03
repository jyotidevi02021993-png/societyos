-- Platform migration shipped with platform-jpa: RLS helpers, locked-record trigger and
-- per-society document numbers. Runs before every service's own V1__ migrations.

-- Readable societies for this transaction (empty array when unset: zero rows, never an error).
CREATE OR REPLACE FUNCTION app_society_ids() RETURNS uuid[]
  LANGUAGE sql STABLE AS
$$ SELECT COALESCE(NULLIF(current_setting('app.society_ids', true), ''), '{}')::uuid[] $$;

-- The one society writes may target (NULL when unset: every insert/update is rejected).
CREATE OR REPLACE FUNCTION app_write_society_id() RETURNS uuid
  LANGUAGE sql STABLE AS
$$ SELECT NULLIF(current_setting('app.write_society_id', true), '')::uuid $$;

-- Usage in a service migration:  SELECT sos_enable_tenant_rls('job_card');
CREATE OR REPLACE FUNCTION sos_enable_tenant_rls(tbl regclass) RETURNS void
  LANGUAGE plpgsql AS
$$
BEGIN
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', tbl);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', tbl);
  EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %s', tbl);
  EXECUTE format(
    'CREATE POLICY tenant_isolation ON %s '
    'USING (society_id = ANY (app_society_ids())) '
    'WITH CHECK (society_id = app_write_society_id())', tbl);
END
$$;

-- Once locked_at is set the row is immutable; corrections are reversal entries.
-- Usage: CREATE TRIGGER trg_bill_locked BEFORE UPDATE OR DELETE ON bill
--          FOR EACH ROW EXECUTE FUNCTION sos_prevent_locked_change();
CREATE OR REPLACE FUNCTION sos_prevent_locked_change() RETURNS trigger
  LANGUAGE plpgsql AS
$$
BEGIN
  IF OLD.locked_at IS NOT NULL THEN
    RAISE EXCEPTION 'RECORD_LOCKED: % % is locked', TG_TABLE_NAME, OLD.id
      USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END
$$;

CREATE TABLE document_sequence (
  society_id UUID    NOT NULL,
  kind       TEXT    NOT NULL,
  year       INT     NOT NULL,
  next_value BIGINT  NOT NULL,
  PRIMARY KEY (society_id, kind, year)
);
SELECT sos_enable_tenant_rls('document_sequence');
