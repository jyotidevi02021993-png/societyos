# ADR-0004 — db-scheduler on Postgres for timers and recurring jobs

- **Status:** Accepted (2026-09-28)
- **Supersedes:** the SDD's Redis/BullMQ queues

## Context
SLA timers, PM task generation, bill runs, retention purges and expiry reminders must
survive restarts, run exactly once across replicas, and be auditable.

## Decision
[db-scheduler](https://github.com/kagkarlsson/db-scheduler) in each service that needs
it, storing tasks in that service's database (`scheduled_tasks`). Recurring tasks iterate
societies and open one transaction per society (`PerSocietyTask`). The source of truth for
a timer is a domain table (such as `sla_timer`); db-scheduler only wakes the service up.

## Consequences
No extra infrastructure; durable; clustered through row locks. Throughput is lower than a
dedicated queue, which is fine for our volumes (thousands of timers, not millions).
