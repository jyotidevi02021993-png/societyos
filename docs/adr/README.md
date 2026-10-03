# Architecture decision records

Format: context → decision → consequences. Status is one of Proposed, Accepted, Superseded.
Add a new ADR (next number) for any decision that changes a rule in `docs/architecture`.

| # | Title | Status |
|---|---|---|
| [0001](0001-event-driven-microservices.md) | Event-driven microservices instead of a modular monolith | Accepted |
| [0002](0002-kafka-as-event-backbone.md) | Kafka as the event backbone | Accepted |
| [0003](0003-transactional-outbox-debezium.md) | Transactional outbox with Debezium (polling relay fallback) | Accepted |
| [0004](0004-scheduling-db-scheduler.md) | db-scheduler on Postgres for timers and recurring jobs | Accepted |
| [0005](0005-postgres-rls-tenancy.md) | Shared schema with `society_id` + PostgreSQL row-level security | Accepted |
| [0006](0006-spring-boot-4.md) | Java 21 + Spring Boot 4.0 / Spring Cloud 2025.1 | Accepted |
| [0007](0007-flutter-single-app-role-modes.md) | One Flutter app with role modes | Accepted |
| [0008](0008-nextjs-bff-auth.md) | Next.js web portals with a backend-for-frontend session | Accepted |
| [0009](0009-independent-services-netflix-stack.md) | Independent service projects on the Spring Cloud Netflix stack | Accepted |
