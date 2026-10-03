# 02 — Kafka and domain events

## 1. Why Kafka (and what it is *not* used for)

| Use Kafka for | Do **not** use Kafka for |
|---|---|
| Domain events between services (`maintenance.jobcard.closed`) | Request/response calls; use REST |
| Feeding read models (gate flat directory, dashboard) | Delayed jobs and timers; use db-scheduler on Postgres |
| Audit stream, analytics sink (ClickHouse), search indexing (OpenSearch) | WebSocket fan-out between realtime pods; use Redis pub/sub |
| Commands to notification-service | Anything that needs < 50 ms end-to-end |
| Replay and backfill (new consumer reads from the beginning) | Storing the system of record; Postgres is that |

## 2. Topic design

**One events topic per bounded context**, plus a few command topics. Fewer topics keep
operations simple; consumers filter on the `type` header.

| Topic | Key | Partitions (prod) | Retention | Cleanup |
|---|---|---|---|---|
| `sos.<context>.events.v1` | aggregate id (UUID) | 12 | 14 days | delete |
| `sos.gate.events.v1` | aggregate id | 24 (highest volume) | 7 days | delete |
| `sos.operations.events.v1` | asset id | 24 (readings) | 7 days | delete |
| `sos.notification.commands.v1` | recipient user id | 12 | 3 days | delete |
| `sos.society.snapshots.v1` | entity id | 6 | ∞ | **compact** (latest flat/resident state, for bootstrapping read models) |
| `sos.dlq.<consumer-group>` | original key | 3 | 30 days | delete |

- **Keying by aggregate id** guarantees order per aggregate (all events of one job card in
  sequence). There is no global or per-society ordering guarantee, and none is needed.
- Cluster settings: `replication.factor=3`, `min.insync.replicas=2`,
  `auto.create.topics.enable=false` (topics are created by Terraform / the init script).
- Producer (Debezium): `acks=all`, `enable.idempotence=true`.
- Naming: topic `sos.<context>.<kind>.v<major>`; event type `<context>.<entity>.<past-tense-verb>`.

## 3. Event envelope (CloudEvents 1.0, JSON)

```json
{
  "specversion": "1.0",
  "id": "0192c3a0-7b1e-7cc2-9d1f-5b6e2a9f1c11",
  "source": "maintenance-service",
  "type": "maintenance.jobcard.closed",
  "dataschema": "https://schemas.societyos.in/maintenance/jobcard.closed/1.json",
  "time": "2026-09-28T06:31:04.221Z",
  "subject": "jobcard/0192c39f-...",
  "societyid": "0191aa10-...",
  "actorid": "0191ab22-...",
  "actortype": "USER",
  "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
  "data": {
    "jobCardId": "0192c39f-...",
    "number": "JC-2026-000123",
    "assetId": "0191f0...",
    "complaintId": "0192c1...",
    "labourCostPaise": 150000,
    "spares": [{ "spareId": "...", "qty": 2, "costPaise": 42000 }],
    "rootCause": "Worn impeller",
    "closedAt": "2026-09-28T06:31:04Z"
  }
}
```

- The Kafka message key is the aggregate id. Headers duplicate `ce_type`, `ce_id`,
  `ce_societyid` and `traceparent` so consumers can filter and trace without parsing the body.
- **Schemas**: JSON Schema per event type in `contracts/events/<context>/<type>/<major>.json`.
  CI checks backwards compatibility (only additive, optional fields within a major).
  A breaking change means a new major, published in parallel to `…v2` until consumers move.
- **Events carry enough data to act on** (event-carried state transfer), so consumers
  rarely need to call back. Personal data in events is minimised: IDs and flat numbers,
  never phone numbers or photos (photos are referenced by media id).

## 4. Publishing: transactional outbox + Debezium

```mermaid
sequenceDiagram
  participant S as Service (Spring @Transactional)
  participant DB as Postgres (service DB)
  participant DZ as Debezium (Kafka Connect)
  participant K as Kafka
  S->>DB: UPDATE job_card ...; INSERT INTO outbox_event(...)
  Note over S,DB: one ACID transaction
  DB-->>DZ: WAL (logical replication, pgoutput)
  DZ->>DZ: Outbox Event Router SMT
  DZ->>K: produce to sos.maintenance.events.v1 (key = aggregate_id)
```

`outbox_event` table (identical in every service DB, created by the platform migration `V0_2__outbox_inbox.sql` in each service's Flyway
migration):

```sql
CREATE TABLE outbox_event (
  id             UUID PRIMARY KEY,          -- = CloudEvents id
  aggregate_type TEXT NOT NULL,             -- routes to topic, e.g. 'maintenance'
  aggregate_id   UUID NOT NULL,             -- Kafka key
  type           TEXT NOT NULL,             -- 'maintenance.jobcard.closed'
  society_id     UUID NOT NULL,
  payload        JSONB NOT NULL,            -- full CloudEvent
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Debezium reads inserts from the WAL; a nightly job deletes rows older than 3 days.
```

- Business code calls `DomainEvents.publish(event)` from the service's platform package, which
  inserts the outbox row in the current transaction. Direct `KafkaTemplate` use in
  domain code is banned (ArchUnit rule).
- Debezium connector per service DB, `table.include.list=public.outbox_event`,
  `transforms=outbox`, `route.by.field=aggregate_type`,
  `route.topic.replacement=sos.${routedByValue}.events.v1`.
- Fallback if Debezium is not available in an environment: the platform package includes a
  polling relay (`SELECT … FOR UPDATE SKIP LOCKED`, every 200 ms) with the same contract.
  Turned on with `sos.outbox.relay=polling`.

**Latency:** commit → Kafka is typically 100–500 ms with Debezium, which fits the 3 s gate budget.

## 5. Consuming: idempotent inbox, retries, DLQ

Every consumer is a Spring Kafka `@KafkaListener` wrapped by the platform's
`@IdempotentConsumer`:

```sql
CREATE TABLE inbox_event (
  consumer    TEXT NOT NULL,   -- listener id
  event_id    UUID NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);
```

1. Listener receives a record, reads `ce_id` and `ce_societyid`.
2. Opens a DB transaction, sets the tenant (`SET LOCAL app.society_ids = …`, see [05](05-security-tenancy.md)).
3. `INSERT INTO inbox_event … ON CONFLICT DO NOTHING`; if 0 rows, it's a duplicate → skip.
4. Runs the handler and commits. **Offset is committed after the DB commit**
   (`AckMode.RECORD`, manual ack), so there is at-least-once delivery with exactly-once effect.

Failure handling (Spring Kafka `@RetryableTopic` + `DefaultErrorHandler`):

| Attempt | Where | Delay |
|---|---|---|
| 1–3 | in-process | 1 s, 2 s, 4 s (exponential) |
| 4 | `…-retry-1m` topic | 1 min |
| 5 | `…-retry-10m` topic | 10 min |
| — | `sos.dlq.<group>` | alert to on-call; replay with the `sos-dlq-replay` admin tool |

Non-retryable exceptions (validation, `UnknownEventTypeException`) go straight to the DLQ.
Unknown event *types* on a topic are **ignored**, not failed, so producers can add events
without breaking consumers.

## 6. Consumer groups

Group id = `<service>.<purpose>`, e.g. `gate.flat-directory`, `dashboard.projections`,
`audit.all`, `billing.charges`. Separate groups for separate purposes, so a slow
projection rebuild never delays a billing consumer.

## 7. Sagas (multi-service business actions)

Mostly **choreography**. **Orchestration** is used only where a human approval or a
timer is involved, and then workflow-service is the orchestrator.

Example: complaint to closure (choreography)

```
maintenance.complaint.created ─▶ ai (classify) ─▶ ai.classification.suggested ─▶ maintenance (apply, route)
                              └▶ workflow (start SLA timer)
maintenance.jobcard.closed    ─▶ asset (history, cost, next PM)
                              ─▶ procurement (stock reconcile)
                              ─▶ billing (recoverable cost)
                              ─▶ workflow (stop SLA timer)
                              ─▶ dashboard, audit, notification
```

Example: purchase order approval (orchestration by workflow-service)

```
procurement.po.submitted ─▶ workflow (instance by amount threshold)
workflow.approval.requested ─▶ notification (approver)
workflow.approved ─▶ procurement (PO → APPROVED, send to vendor)
workflow.rejected ─▶ procurement (PO → REJECTED)   ← compensation
```

## 8. Event catalogue (Phase 1)

| Event type | Producer | Main consumers |
|---|---|---|
| `identity.user.registered` | identity | notification, audit |
| `identity.role.assigned` / `revoked` | identity | gate, maintenance (assignee lists), audit |
| `society.membership.created` / `ended` | society | identity, gate, billing, notification |
| `society.flat.created` / `updated` | society | gate, billing, maintenance, dashboard |
| `society.facility.created` / `updated` | society | community |
| `society.vehicle.registered` / `domesticstaff.registered` | society | gate |
| `gate.entry.requested` | gate | realtime, notification, audit |
| `gate.entry.approved` / `denied` | gate | realtime (guard), audit, dashboard |
| `gate.entry.checked_in` / `checked_out` | gate | dashboard, audit |
| `gate.sos.raised` | gate | realtime, notification (all managers), maintenance (incident) |
| `billing.bill.generated` | billing | notification, dashboard |
| `billing.payment.succeeded` | billing | notification, dashboard, community |
| `billing.dues.overdue` | billing | notification, dashboard |
| `asset.created` / `updated` / `status.changed` | asset | maintenance, dashboard, search |
| `asset.pmtask.due` / `overdue` | asset | maintenance, dashboard, notification |
| `asset.warranty.expiring` / `amc.expiring` | asset | notification, dashboard |
| `maintenance.complaint.created` | maintenance | ai, workflow, notification, dashboard |
| `maintenance.jobcard.assigned` | maintenance | notification (technician push), dashboard |
| `maintenance.jobcard.closed` | maintenance | asset, billing, procurement, workflow, dashboard |
| `maintenance.incident.raised` | maintenance | notification, realtime, workflow, dashboard |
| `workflow.sla.breached` / `escalated` | workflow | maintenance, notification, dashboard |
| `community.notice.published` | community | notification, ai (index) |
| `community.booking.confirmed` | community | billing, notification |
| `media.processed` | media | owning services (attach), document |
| `ai.classification.suggested` | ai | maintenance |

Phase 2 adds operations, procurement and document events (see [01](01-services.md)).

## 9. Local and production Kafka

- **Local:** a single-broker Apache Kafka 3.8 in KRaft mode, Kafka Connect with Debezium 2.7,
  Kafka UI, all in Docker Compose.
- **Production:** Amazon MSK (3 brokers across 3 AZs, `kafka.m7g.large` to start), MSK
  Connect for Debezium, IAM auth, TLS in transit. Alternative: Strimzi on EKS.
