# 03 — PostgreSQL data architecture

## 1. Database per service

- One **PostgreSQL 16 cluster per environment** (RDS/Aurora Multi-AZ in production),
  with **one database per service** (`identity_db`, `gate_db`, …).
- Each service has its own login role with privileges only on its own database. Two roles
  per service:
  - `<svc>_owner` runs Flyway migrations. Used only by the migration job.
  - `<svc>_app` is used at runtime: DML only, **not** table owner, so RLS applies to it.
- A service that grows hot (gate, operations readings) can move to its own cluster with
  no code change, only a connection string.
- `wal_level=logical` is on so Debezium can read the outbox (RDS parameter
  `rds.logical_replication=1`).
- Reports and MIS read from a **read replica**. Spring routes `@Transactional(readOnly = true)`
  to the replica datasource for services that opt in.

## 2. Conventions (every table)

```sql
id           UUID PRIMARY KEY,            -- UUIDv7 generated in Java
society_id   UUID NOT NULL,               -- tenant; first column of most indexes
created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
created_by   UUID,                        -- user id, NULL for system
updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
updated_by   UUID,
version      BIGINT NOT NULL DEFAULT 0    -- JPA @Version optimistic locking
```

- Soft delete only where the business needs it (`deleted_at`); financial rows are never deleted.
- Money is `BIGINT` in paise, with a `currency CHAR(3) DEFAULT 'INR'` column where needed.
- Enumerations are `TEXT` + `CHECK` constraint (easy to extend, no ALTER TYPE issues).
- Configurable structures (checklist items, equipment specs, workflow steps) are `JSONB`,
  validated in Java against a JSON Schema stored per society and category.
- Human-readable numbers: `document_sequence(society_id, kind, year, next_value)` with
  `UPDATE … RETURNING` gives `JC-2026-000123`, `CMP-…`, `PO-…`, `RCPT-…`.
- Naming: snake_case, plural-free table names (`job_card`), FKs `<table>_id`, indexes
  `ix_<table>_<cols>`, unique `ux_…`.

## 3. Multi-tenancy with row-level security

Every tenant table:

```sql
ALTER TABLE job_card ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_card FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON job_card
  USING      (society_id = ANY (current_setting('app.society_ids', true)::uuid[]))
  WITH CHECK (society_id =      current_setting('app.write_society_id', true)::uuid);
```

- `app.society_ids` is an **array**, so an FM company user can read several societies
  side by side (multi-site view). Writes are always against exactly one society
  (`app.write_society_id`).
- If the setting is missing, `current_setting(…, true)` returns NULL, so the query
  returns **no rows**. The failure mode is safe.
- The platform package sets both values with `SET LOCAL` **at the start of every
  transaction** (a `TenantTransactionListener` hooked into Spring's transaction manager runs
  `SELECT set_config('app.society_ids', ?, true), set_config('app.write_society_id', ?, true)`).
  `SET LOCAL` scope ends with the transaction, so pooled connections (HikariCP) never
  leak a tenant.
- Kafka consumers set the tenant from the event's `societyid` before handling it.
- Scheduled jobs iterate societies and open one transaction per society.
- Platform-level (Super Admin) cross-society jobs use a separate `<svc>_platform` role
  with `BYPASSRLS`, only in dedicated admin endpoints, and are always audited.
- Second layer in Java: a Hibernate `@Filter` on `society_id` and an ArchUnit test that
  every `@Entity` extends `TenantEntity`.

## 4. Core schemas per service (Phase 1)

Only key columns are shown; the conventions columns above are implied.

### identity_db
```
app_user(id, phone_e164 UNIQUE, email UNIQUE NULL, name, status, preferred_lang, mfa_secret_enc)
organisation(id, name, type)                -- FM company, developer
org_membership(org_id, user_id, role)
role(id, society_id NULL, code, name, is_template)
permission(code PK, module, action, description)
role_permission(role_id, permission_code)
role_assignment(id, user_id, society_id, role_id, valid_from, valid_to)
device(id, user_id, platform, push_token, bound_at, last_seen_at, revoked_at)
refresh_token(id, user_id, device_id, token_hash, expires_at, rotated_from, revoked_at)
consent(id, user_id, society_id, purpose, version, granted_at, withdrawn_at)
```
`app_user` is global (a person can be in many societies), so it is not RLS-filtered;
`role_assignment` and `consent` are.

### society_db
```
society(id, name, legal_name, address, city, state, pin, timezone, settings JSONB, status)
tower(id, society_id, name, floors_count)
floor(id, society_id, tower_id, number)
flat(id, society_id, tower_id, floor_id, number, area_sqft, type, status)
location(id, society_id, kind, name, tower_id NULL, parent_id NULL)   -- pump room, basement ...
facility(id, society_id, kind, name, capacity, booking_rules JSONB, chargeable)
parking_slot(id, society_id, code, kind, flat_id NULL)
resident(id, society_id, user_id, name, directory_opt_in)
flat_membership(id, society_id, flat_id, resident_id, kind OWNER|TENANT|FAMILY, from_date, to_date, is_primary)
vehicle(id, society_id, flat_id, reg_no, kind, rfid_tag)
domestic_staff(id, society_id, name, kind, phone_enc, photo_media_id, kyc_status)
domestic_staff_flat(staff_id, flat_id)
```

### gate_db
```
visitor(id, society_id, name, phone_hash, phone_enc, photo_media_id, purge_after)
gate_pass(id, society_id, flat_id, created_by, kind GUEST|CAB|DELIVERY|SERVICE, code_otp, qr_token,
          valid_from, valid_to, max_uses, used_count, status)
entry_log(id, society_id, gate_id, flat_id, visitor_id NULL, staff_id NULL, vehicle_reg NULL,
          pass_id NULL, status REQUESTED|APPROVED|DENIED|EXPIRED|IN|OUT, requested_at,
          decided_by, decided_at, in_at, out_at, guard_id, purge_after)
          PARTITION BY RANGE (requested_at)          -- monthly
delivery(id, society_id, flat_id, company, entry_id, leave_at_gate)
staff_attendance(id, society_id, staff_id, in_at, out_at, gate_id)
guard_shift(id, society_id, guard_user_id, gate_id, starts_at, ends_at, checked_in_at)
sos(id, society_id, raised_by, flat_id, kind, at, acknowledged_by, resolved_at)
flat_directory(flat_id PK, society_id, tower, number, residents JSONB, updated_at)  -- read model
```

### billing_db
```
billing_plan(id, society_id, name, basis FIXED|PER_SQFT, rate_paise, gst_rule JSONB, late_fee_rule JSONB)
bill_run(id, society_id, period YYYYMM, status, started_at, completed_at, totals JSONB)
bill(id, society_id, bill_run_id, flat_id, number, period, due_date, amount_paise, gst_paise,
     status DUE|PART_PAID|PAID|CANCELLED, locked_at)
bill_line(id, society_id, bill_id, kind, description, amount_paise, gst_paise, source_ref)
payment(id, society_id, bill_id NULL, flat_id, amount_paise, method, gateway, gateway_order_id UNIQUE,
        gateway_payment_id UNIQUE, status, paid_at)
receipt(id, society_id, payment_id, number, pdf_media_id)
ledger_account(id, society_id, code, name, kind)
ledger_entry(id, society_id, txn_id, account_id, debit_paise, credit_paise, ref_type, ref_id, at)
                                             -- append-only; SUM(debit) = SUM(credit) per txn_id (trigger)
expense(id, society_id, category, asset_id NULL, vendor_id NULL, tower_id NULL, department, amount_paise, at)
budget(id, society_id, year, category, amount_paise)
webhook_event(provider, event_id, received_at, payload JSONB, PRIMARY KEY(provider, event_id))
```

### asset_db
```
asset_category(id, society_id NULL, group, name, spec_schema JSONB)
asset(id, society_id, code UNIQUE per society, name, category_id, location_id, make, model, serial_no,
      manufacturer, supplier, po_ref, invoice_ref, cost_paise, purchase_date, install_date,
      capacity, voltage, power_kw, expected_life_months, status WORKING|BREAKDOWN|UNDER_REPAIR|DISPOSED,
      maintenance_agency, photo_media_id, spec JSONB, breakdown_count, total_maintenance_cost_paise)
qr_code(id, society_id, asset_id, token UNIQUE, printed_at)
warranty(id, society_id, asset_id, vendor_id, starts_on, ends_on, terms)
amc_contract(id, society_id, asset_id, vendor_id, starts_on, ends_on, value_paise, visits_per_year, sla JSONB)
amc_visit(id, society_id, amc_id, due_on, done_on, engineer_name, service_entry_no, job_card_id)
pm_plan(id, society_id, asset_id, frequency DAILY|WEEKLY|FORTNIGHTLY|MONTHLY|QUARTERLY|HALF_YEARLY|ANNUAL|USAGE,
        usage_metric NULL, usage_interval NULL, checklist_template_id, lead_days, next_due_on)
pm_task(id, society_id, pm_plan_id, asset_id, due_on, status, job_card_id NULL)
asset_history(id, society_id, asset_id, at, kind, ref_type, ref_id, summary, cost_paise)
vendor_ref(vendor_id PK, society_id, name)            -- read model from procurement
```

### maintenance_db
```
category(id, society_id, name, department, default_priority, default_assignee_role)
sla_policy(id, society_id, category_id NULL, priority P1..P4, respond_mins, resolve_mins, escalation_chain JSONB)
complaint(id, society_id, number, flat_id NULL, location_id NULL, asset_id NULL, raised_by, category_id,
          priority, description, status, ai_suggestion JSONB, resolved_at, rating, reopened_count)
breakdown(id, society_id, number, asset_id, location_id, reported_by, fault, priority, reported_at,
          expected_resolution_at, resolved_at, downtime_mins)
incident(id, society_id, number, kind, severity, location_id, raised_by, at, actions JSONB, rca TEXT, closed_at)
job_card(id, society_id, number, source_type COMPLAINT|BREAKDOWN|PM|CHECKLIST|INCIDENT, source_id,
         asset_id NULL, assignee_user_id NULL, vendor_id NULL, status, fault, root_cause, work_done,
         started_at, completed_at, verified_by, verified_at, closed_at,
         labour_cost_paise, spare_cost_paise, resident_confirmed BOOL NULL, locked_at)
job_card_spare(id, society_id, job_card_id, spare_id, qty, unit_cost_paise)
job_card_evidence(id, society_id, job_card_id, stage BEFORE|DURING|AFTER, media_id, taken_at, lat, lng, taken_by)
ticket_event(id, society_id, ticket_type, ticket_id, at, actor_id, from_status, to_status, note)
asset_summary(asset_id PK, society_id, code, name, location_name)     -- read model
flat_directory(flat_id PK, society_id, label)                         -- read model
```

### community_db
```
notice(id, society_id, title, body, audience JSONB, pinned, publish_at, expires_at, attachments JSONB)
poll(id, society_id, question, one_vote_per FLAT|MEMBER, opens_at, closes_at, status)
poll_option(id, poll_id, label)
vote(id, society_id, poll_id, option_id, flat_id, user_id, UNIQUE(poll_id, flat_id))
event(id, society_id, kind, title, starts_at, ends_at, location, capacity, fee_paise)
event_registration(id, society_id, event_id, flat_id, user_id, count, attended)
volunteer(id, society_id, event_id, user_id, role)
booking(id, society_id, facility_id, flat_id, during TSTZRANGE, status, charge_paise,
        EXCLUDE USING gist (facility_id WITH =, during WITH &&) WHERE (status IN ('HELD','CONFIRMED')))
facility_ref(facility_id PK, society_id, name, rules JSONB)            -- read model
```

### workflow_db
```
workflow_definition(id, society_id, kind, version, definition JSONB, active)
workflow_instance(id, society_id, definition_id, subject_type, subject_id, amount_paise, status, started_at, ended_at)
approval_task(id, society_id, instance_id, step, approver_role, approver_user_id NULL, status, decided_by, decided_at, comment)
sla_timer(id, society_id, subject_type, subject_id, level, due_at, status ACTIVE|STOPPED|FIRED)
escalation_log(id, society_id, subject_type, subject_id, level, to_role, at)
scheduled_tasks(...)                                                   -- db-scheduler table
```

Also in every service DB: `outbox_event`, `inbox_event` ([02](02-events-kafka.md)),
`flyway_schema_history`, and `scheduled_tasks` where db-scheduler is used.

## 5. Large tables and partitioning

| Table | Growth (Phase 2) | Strategy |
|---|---|---|
| `gate.entry_log` | 20k/society/day → ~30M/month at 50 societies | Monthly range partitions (pg_partman); drop after retention |
| `operations.reading` | ~5k/society/day | Monthly partitions; BRIN index on `at` |
| `audit.audit_log` | all events | Monthly partitions; moved to S3 (Parquet) after 13 months |
| `notification.notification` | high | Monthly partitions; 90 days retention |

## 6. Migrations

- **Flyway** per service in `src/main/resources/db/migration`, run as a Kubernetes
  `Job` (`<svc>_owner` role) before the deployment rolls out.
- **Expand then contract**: add nullable column → deploy code that writes both →
  backfill → deploy code that reads new → drop old in a later release.
- Platform migrations (outbox, inbox, RLS helper functions) are versioned `V0_x__`
  and kept in each service under `src/main/resources/db/platform/`, which Flyway runs before `db/migration/`.

## 7. Backups and recovery

RDS continuous backup with 14-day PITR (RPO 15 min), daily snapshots copied to
ap-south-2, quarterly restore drill. Per-society export (JSON + CSV + documents) is
available through an admin API, to meet the Agreement's exit and data-return clauses.
