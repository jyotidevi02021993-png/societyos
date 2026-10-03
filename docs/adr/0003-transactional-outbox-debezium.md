# ADR-0003 — Transactional outbox with Debezium

- **Status:** Accepted (2026-09-28)

## Context
Writing to the database and then calling `KafkaTemplate.send()` can lose events (a crash
between the two) or publish events for transactions that rolled back.

## Decision
Business code inserts an `outbox_event` row in the same transaction
(`DomainEvents.publish`). Debezium's Outbox Event Router reads the WAL and produces to
`sos.<aggregate_type>.events.v1`. The platform ships a polling relay
(`sos.outbox.relay=polling`, `FOR UPDATE SKIP LOCKED`) with the same contract, for
environments without Kafka Connect (local dev, tests).

## Consequences
At-least-once delivery with no lost or phantom events. `wal_level=logical` is required.
Outbox rows are deleted after 3 days by a scheduled job.
