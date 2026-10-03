# ADR-0005 — Shared schema with society_id and row-level security

- **Status:** Accepted (2026-09-28)

## Context
Hundreds of societies, FM-company users who read several societies at once, and a
strict tenant-isolation obligation (Agreement §9–11).

## Decision
All tenants share tables in each service database. Every tenant row has `society_id`.
PostgreSQL RLS (`ENABLE` + `FORCE`) filters reads by `app.society_ids` (an array) and
checks writes against `app.write_society_id`. The platform sets both with
`set_config(..., true)` at the start of each transaction. The runtime role is not the
table owner. A second layer in Java (tenant-aware base entity, ArchUnit rules) guards
against mistakes.

## Alternatives considered
Schema per tenant (migration fan-out, connection-pool pressure) and database per tenant
(cost, operations). Both stay available for a very large customer through routing.

## Consequences
A missing tenant context returns zero rows, which is the safe default. Every service has
a mandatory two-society RLS test.
