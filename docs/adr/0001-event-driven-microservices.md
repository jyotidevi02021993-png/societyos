# ADR-0001 — Event-driven microservices instead of a modular monolith

- **Status:** Accepted (2026-09-28)
- **Supersedes:** the SDD's "modular monolith at MVP"

## Context
The SDD proposed a modular monolith for the pilot. The product owner asked for a
distributed architecture on Kafka, Redis and PostgreSQL from day one. The hard
requirements are: gate and payments at 99.9% even when other modules fail, a staff app
that works offline, a 60-second morning dashboard, and a path to 50+ societies.

## Decision
One Spring Boot service per bounded context, each with its own PostgreSQL database.
State changes propagate as domain events on Kafka. Synchronous calls between services are
the exception and need an ADR.

## Consequences
- Gate and billing deploy, scale and fail independently.
- Read models (flat directory in gate, dashboard projections) make the 60 s dashboard and
  gate resilience possible.
- Cost: more infrastructure, eventual consistency, harder debugging. Mitigations: one
  platform package copied into every service, one service template, one Helm chart, full local stack in Compose,
  tracing across Kafka.
- Phase 1 ships 14 deployables; Phase 2 services are added only when needed.
