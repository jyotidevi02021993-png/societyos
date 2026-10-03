# billing-service (port 8084, `billing_db`)

Charge heads and tariffs, monthly bill runs (preview → publish), bills with line items, late fees,
credit/debit notes, payments (online gateway + offline), receipts, a double-entry ledger with
per-flat outstanding and CSV export, expenses, vendor payments and budgets with approval.

## Features

| Package | What |
|---|---|
| `roster` | Local copies from `sos.society.events.v1` (group `billing.roster`, DLQ `sos.dlq.billing.roster`): flats (area, type, status), memberships (who may see/pay a flat's bills), `billingDueDay`/`lateFeeGraceDays`. `GET/PUT /v1/billing/settings` (GST registration, rate, RWA threshold, late-fee rule, reminder days), `GET /v1/billing/flats`. `BillingAccess` = own-flat checks. |
| `tariff` | `/v1/charge-heads`: FIXED (per flat), PER_SQFT (paise per sq ft), FLAT_TYPE (rate per type, fallback rate). `TariffCalculator` is pure. |
| `charge` | `/v1/charges`: one-off charges for the next bill; `community.booking.confirmed/cancelled` (group `billing.booking-charges`, DLQ `sos.dlq.billing.booking-charges`) adds/drops booking charges. |
| `bill` | `/v1/bill-runs` (POST = preview, `/{id}/publish`, DELETE = discard preview), `/v1/bills`, `/v1/bills/{id}/adjustments` (CN-/DN-), `/v1/dues`, `/v1/me/dues`, `/v1/flats/{id}/dues`. Daily dues pass: reminders, overdue notices, one-time late fee. |
| `payment` | `POST /v1/bills/{id}/pay` (gateway order), `POST /v1/webhooks/{provider}` (public, HMAC, dedup by `webhook_event`), `POST /v1/payments` (CASH/CHEQUE/UPI/BANK_TRANSFER), `/v1/payments`, `/v1/receipts`. `PaymentGateway` is the plug-in point for Razorpay; `stub` is built in. Reconciliation every 15 min. |
| `ledger` | Append-only, balanced per transaction (DB triggers). `/v1/ledger/accounts` (trial balance), `/v1/ledger/entries`, `/v1/ledger/export.csv`, `/v1/flats/{id}/statement`. |
| `expense` | `/v1/expenses`, `/v1/vendor-payments`, `/v1/budgets` (+ `/approve`, `/reject`; maker-checker). |
| `jobs` | db-scheduler: `billing-daily-dues` (06:00 IST), `billing-payment-reconcile` (15 min). |

## Money and rounding

All money is `long` paise; no floating point. Fixed and per-sq-ft charges are exact integer
products. Only percentages (GST, percentage late fees) produce fractions: each is computed exactly
and rounded **once, half-up to the nearest paisa, per line** (GST per bill line; late fee on the
bill's overdue balance). Bill and run totals are sums of rounded lines and are never re-rounded.
GST (RWA rule): when the society is GST-registered and a flat's GST-applicable recurring charges
for the month exceed the threshold (Rs 7,500 default), GST applies to the whole of them; at or
below, exempt. Due date = `billingDueDay` of the billed month, but at least 7 days after the bill
date. Late fee: once, after `lateFeeGraceDays` past the due date.

---

*Template notes (shared by every service) follow.*

## What you get

- Independent Maven project (`spring-boot-starter-parent` 4.0.8, Spring Cloud 2025.1.3, `mvnw`).
- `platform/` package (no domain logic), identical in every service:

| Package | Use it for |
|---|---|
| `platform.core` | `TenantContext.activeSocietyId()`, `UuidV7.next()`, `Money` (paise), `ProblemException`, `Hashing.maskPhone` |
| `platform.jpa` | Extend `TenantEntity` (id, society_id, audit columns, version) or `GlobalEntity`; RLS is set per transaction; `DocumentNumberService.next("JC")` |
| `platform.events` | Events are records implementing `DomainEvent`; publish with `DomainEvents.publish(event)` inside `@Transactional`; consume with `@DomainEventListener(topic, group, type)` on `void on(CloudEvent<T> e)` |
| `platform.web` | Throw `ProblemException`; `CursorPage` for lists; `Idempotency-Key` handled automatically |
| `platform.security` | `@PreAuthorize("@perm.has('module:action')")`; `FieldCrypto` for PII columns; Feign calls forward the caller's token |

## Rules

1. Tables: every tenant table has `society_id` and `SELECT sos_enable_tenant_rls('<table>');` in its migration.
   Conventions: `id UUID PK, society_id, created_at, created_by, updated_at, updated_by, version`.
2. Migrations in `src/main/resources/db/migration/V1__…sql` (Flyway). Platform migrations (`V0_x`) are already in `db/platform`.
3. Feature packages: `<feature>/api` (controllers + DTO records), `application` (use cases, `@Transactional`),
   `domain` (entities, events), `infrastructure` (repositories, Kafka listeners, Feign clients).
   Controllers never import `infrastructure`.
4. Never use `KafkaTemplate` outside `platform`. Every consumer group needs a DLQ topic `sos.dlq.<group>`.
5. Money is `long …Paise`. Times are `Instant` (UTC). No PII (phones, photos) in events.
6. Tests: unit tests for domain rules; integration tests extend `IntegrationTestBase` (real Postgres with RLS,
   Kafka, Redis); mint tokens with `TestJwtIssuer.token(user, society, roles...)`; stub permissions with
   `givenPermissions("flat:manage")`.

```bash
./mvnw test                   # unit + ArchUnit
./mvnw verify -Pintegration   # + Testcontainers
```
