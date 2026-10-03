# ADR-0002 — Kafka as the event backbone

- **Status:** Accepted (2026-09-28)

## Context
Many consumers per event (audit, dashboard, notification, search, analytics), a need to
replay history into new read models, and per-aggregate ordering.

## Decision
Apache Kafka (KRaft). One events topic per bounded context (`sos.<context>.events.v1`),
keyed by aggregate id, CloudEvents JSON envelope, JSON Schema per event type. Amazon MSK
in production; Strimzi on EKS is the documented cheaper alternative (saves about
350 USD a month at pilot scale, costs operational effort).

## Alternatives considered
- **RabbitMQ:** simpler, but no replay and weaker fan-out to many independent consumer groups.
- **Redis Streams:** fine at small scale, but Redis is disposable in our design ([04](../architecture/04-redis.md)).
- **SNS/SQS:** vendor lock-in; no ordered replay.

## Consequences
Consumers must be idempotent (inbox table). Kafka is not used for delayed jobs
([ADR-0004](0004-scheduling-db-scheduler.md)) or WebSocket fan-out (Redis pub/sub).
