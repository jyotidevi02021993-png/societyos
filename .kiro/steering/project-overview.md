# SocietyOS — Project Steering Document

> This document is the authoritative orientation guide for any AI coding agent or developer
> working on this codebase. Read it fully before writing, modifying, or reviewing any code.

---

## 1. What SocietyOS Is

SocietyOS is a **digital operating system for gated housing societies** (apartment complexes,
residential estates) in India. It covers security & gate management, estate/asset maintenance,
utilities, finance, procurement, compliance, resident community features, and AI-assisted support.

**Three client products** share one distributed multi-tenant backend:

| Client | Technology | Audience |
|---|---|---|
| Mobile app | Flutter 3.x (Android + iOS) | Residents, guards, technicians, housekeeping, managers, vendors |
| Resident Web Portal | Next.js 15 (TypeScript) | Owners (incl. non-resident), tenants |
| Admin Web Portal | Next.js 15 (TypeScript) | Super Admin, RWA committee, Estate/Facility Manager, Accounts |

The system is hosted **India-only** (AWS ap-south-1 primary, ap-south-2 DR) for DPDP Act 2023
compliance. Target scale: 50 societies / ~100k residents / 20k gate events per society per day.

---

## 2. Repository Layout

```
societyos/
  README.md
  build-all.sh / build-all.ps1     ← loops over services/*; CI builds each service alone
  .env                             ← local dev secrets (never commit real secrets)
  config-repo/                     ← Spring Cloud Config files (application.yml shared)
  services/
    service-registry/              ← Eureka server              (port 8761)
    config-server/                 ← Spring Cloud Config Server (port 8888)
    api-gateway/                   ← Spring Cloud Gateway       (port 8080)
    identity-service/              ← built, port 8081
    society-service/               ← port 8082
    security-service/              ← gate & security, port 8083
    billing-service/               ← port 8084
    asset-service/                 ← port 8085
    ticket-service/                ← complaints/jobcards, port 8086
    community-service/             ← port 8087
    workflow-service/              ← approvals/SLA, port 8088
    notification-service/          ← port 8089
    realtime-service/              ← WebSocket/STOMP, port 8090
    media-service/                 ← port 8091
    audit-service/                 ← port 8092
    dashboard-service/             ← MIS/CQRS, port 8093
    utility-service/               ← readings/checklists, port 8094 (Phase 2)
    vendor-service/                ← vendors/POs, port 8095 (Phase 2)
    inventory-service/             ← spares/stock, port (Phase 2)
    compliance-service/            ← certificates, port 8096 (Phase 2)
    marketplace-service/           ← port 8098 (Phase 3 reserved)
  ai-service/                      ← Python 3.12 FastAPI, port 8097
  web/                             ← npm-workspaces monorepo
    apps/admin-web/                ← Next.js admin portal, port 3000
    apps/resident-web/             ← Next.js resident portal, port 3001
    packages/ui/                   ← Tailwind + shadcn/ui design system
    packages/api-client/           ← generated TypeScript API clients + React Query hooks
    packages/auth/                 ← BFF session helpers shared by both Next.js apps
    packages/shared/               ← SessionProvider and cross-app utilities
    packages/config/               ← eslint, tsconfig, tailwind preset
  contracts/
    events/CATALOGUE.md            ← single source of truth for all event payloads
    openapi/<service>.yaml         ← contract-first OpenAPI specs (not yet present for all)
  infra/
    docker/docker-compose.yml      ← full local stack
    docker/postgres/init/          ← creates one DB + owner/app roles per service
    docker/kafka/create-topics.sh  ← all topics and DLQs
    helm/                          ← one generic Helm chart for every Spring service
    terraform/                     ← network, eks, rds, msk, elasticache, s3, cloudfront, iam
    k8s/                           ← Argo CD Application manifests per env
  docs/architecture/               ← 12 architecture documents (00–11)
  docs/adr/                        ← 9 Architecture Decision Records
```

---

## 3. Architecture

### 3.1 Style

**Event-driven microservices** (ADR-0001). One Spring Boot service per bounded context, each
owning its PostgreSQL database. Services must not read or write another service's tables.

Core rules (non-negotiable):

1. **Own your data.** Cross-service data only through events or REST APIs.
2. **Every change is an event.** State changes go through the transactional outbox, never
   with a direct `kafkaTemplate.send()` inside business code (ArchUnit enforces this).
3. **Consumers are idempotent.** Every consumer records processed event IDs in an `inbox_event`
   table. Redelivery must be harmless.
4. **Tenant everywhere.** `society_id` in every row, every event, every log line, every cache key.
5. **Money is `BIGINT` paise.** Never floats. Financial and compliance records are append-only
   after approval; corrections are reversal entries.
6. **Time is UTC in storage**, society timezone (`Asia/Kolkata` default) for display.
7. **IDs are UUIDv7** (time-ordered), generated in the service. Human-facing numbers
   (e.g. `JC-2026-000123`) come from `document_sequence`.
8. **APIs are contract-first.** OpenAPI per service in `contracts/`, server interfaces generated
   with `openapi-generator` (`interfaceOnly=true`).
9. **No shared domain logic.** Each service carries its own platform package — no shared library.

### 3.2 Service Catalogue

| # | Service | Port | DB | Kafka Topic Published | Phase |
|---|---|---|---|---|---|
| 0 | api-gateway | 8080 | — (Redis) | — | 1 |
| 1 | identity-service | 8081 | identity_db | sos.identity.events.v1 | 1 |
| 2 | society-service | 8082 | society_db | sos.society.events.v1 | 1 |
| 3 | security-service | 8083 | gate_db | sos.security.events.v1 | 1 |
| 4 | billing-service | 8084 | billing_db | sos.billing.events.v1 | 1 |
| 5 | asset-service | 8085 | asset_db | sos.asset.events.v1 | 1 |
| 6 | ticket-service | 8086 | maintenance_db | sos.ticket.events.v1 | 1 |
| 7 | community-service | 8087 | community_db | sos.community.events.v1 | 1 |
| 8 | workflow-service | 8088 | workflow_db | sos.workflow.events.v1 | 1 |
| 9 | notification-service | 8089 | notification_db | sos.notification.events.v1 | 1 |
| 10 | realtime-service | 8090 | — (Redis) | — | 1 |
| 11 | media-service | 8091 | media_db | sos.media.events.v1 | 1 |
| 12 | audit-service | 8092 | audit_db | — | 1 |
| 13 | dashboard-service | 8093 | dashboard_db | — | 1 |
| 14 | utility-service | 8094 | operations_db | sos.utility.events.v1 | 2 |
| 15 | vendor-service | 8095 | procurement_db | sos.vendor.events.v1 | 2 |
| 16 | inventory-service | — | inventory_db | sos.inventory.events.v1 | 2 |
| 17 | compliance-service | 8096 | document_db | sos.compliance.events.v1 | 2 |
| 17 | ai-service (Python) | 8097 | ai_db (pgvector) | sos.ai.events.v1 | 1/2 |
| 18 | marketplace-service | 8098 | marketplace_db | sos.marketplace.events.v1 | 3 |

Also two infrastructure services: `service-registry` (Eureka, 8761) and `config-server` (8888).

---

## 4. Technology Stack

### 4.1 Backend Services (Java)

| Concern | Technology |
|---|---|
| Language | Java 21 (LTS), virtual threads enabled (`spring.threads.virtual.enabled=true`) |
| Framework | Spring Boot **4.0.x** (Spring Framework 7, Spring Security 7) — ADR-0006 |
| Spring Cloud | **2025.1.x** — Gateway, Config, OpenFeign, LoadBalancer, Resilience4j |
| Service registry | Netflix **Eureka** (`service-registry`, port 8761) |
| Edge routing | Spring Cloud **Gateway** (WebFlux/reactive) with `lb://` routes via Eureka |
| Circuit breakers | **Resilience4j** on every gateway route and Feign client |
| Build | **Maven**, one independent project per service (`spring-boot-starter-parent` + Spring Cloud BOM + own `mvnw`) — ADR-0009 |
| Persistence | Spring Data JPA + Hibernate 6, HikariCP, **Flyway** migrations |
| Messaging | Spring for Apache Kafka (`@KafkaListener`, `@RetryableTopic`), Debezium for outbox publishing |
| Cache / Redis | Spring Data Redis (Lettuce), Spring Cache (`@Cacheable`), Redisson for distributed locks |
| Security | Spring Security OAuth2 Resource Server (JWT RS256) |
| Scheduling | **db-scheduler** (Postgres-backed, clustered; no in-memory or Redis timers) — ADR-0004 |
| HTTP clients | Spring `RestClient` + `@HttpExchange` (OpenFeign where generated) |
| API docs | springdoc-openapi 3.1.x; contract-first specs |
| Mapping | MapStruct; Jakarta Bean Validation |
| Observability | Micrometer + OpenTelemetry (OTLP) → Grafana (Tempo/Loki/Prometheus); logstash JSON encoder |
| Testing | JUnit 5, AssertJ, Testcontainers, ArchUnit, Spring Cloud Contract/Pact, JaCoCo ≥ 70% |
| Code quality | Spotless (google-java-format), Error Prone, SpotBugs + FindSecBugs, OWASP Dependency-Check |

### 4.2 AI Service (Python)

| Concern | Technology |
|---|---|
| Language | Python 3.12 |
| Framework | FastAPI |
| DB driver | psycopg (psycopg3) |
| Settings | pydantic-settings (reads env vars and `.env`) |
| Vector DB | PostgreSQL 16 + pgvector (1536-dim or 384-dim embeddings depending on provider) |
| LLM | Anthropic Claude (`claude-sonnet-5` default); configurable provider (`anthropic` or `fake`) |
| Embeddings | `sentence-transformers/all-MiniLM-L6-v2` (or hashing for dev) |
| Migrations | Flyway-compatible SQL files in `migrations/` (run by the service itself at startup) |
| Auth | Same RS256 JWT; JWKS fetched from identity-service |

### 4.3 Frontend (Next.js)

| Concern | Technology |
|---|---|
| Framework | Next.js 15 (App Router), React 19, TypeScript strict |
| Styling / UI | Tailwind CSS v4 + shadcn/ui (Radix), shared `packages/ui` |
| Data fetching | TanStack Query on client; server components use session token |
| API client | Generated from `contracts/openapi/*.yaml` with openapi-typescript + openapi-fetch |
| Forms | react-hook-form + zod |
| Tables | TanStack Table (virtualised, server-side paging) |
| Realtime | @stomp/stompjs for dashboard alerts |
| i18n | next-intl (en, hi) |
| Auth | BFF pattern — Next.js route handlers keep refresh token server-side in Redis (ADR-0008) |
| Tests | Vitest + Testing Library, Playwright E2E |
| Package manager | npm workspaces (note: package.json declares `pnpm workspaces` in docs but actual root is npm workspaces) |

### 4.4 Mobile (Flutter)

| Concern | Technology |
|---|---|
| Framework | Flutter 3.x (Dart 3), min Android 8 (API 26), iOS 15 |
| State | Riverpod 2 (code-gen `@riverpod`) |
| Navigation | go_router with role-guarded route trees |
| HTTP | dio + interceptors; clients generated from OpenAPI (openapi-generator dart-dio) |
| Local DB | Drift (SQLite) + SQLCipher encryption |
| Secure storage | flutter_secure_storage (Keystore / Keychain) |
| Realtime | stomp_dart_client over WebSocket |
| Push | firebase_messaging (FCM + APNs) |
| QR/camera | mobile_scanner, camera, flutter_image_compress |
| Payments | Razorpay Flutter SDK (hosted checkout) |
| Offline sync | Local Drift outbox + batch push/pull APIs (`/v1/sync/push`, `/v1/sync/changes`) |
| Background | workmanager (Android), background_fetch (iOS) |

---

## 5. Data Layer

### 5.1 PostgreSQL 16

- **One database per service**: `identity_db`, `society_db`, `gate_db`, `billing_db`,
  `asset_db`, `maintenance_db`, `community_db`, `workflow_db`, `notification_db`, `media_db`,
  `audit_db`, `dashboard_db`, `ai_db`, etc.
- Each service has two roles: `<svc>_owner` (runs Flyway migrations) and `<svc>_app` (DML
  only at runtime; RLS applies to this role, not to the owner).
- `wal_level=logical` is required so Debezium can read the outbox WAL.
- Reports and MIS read from a **read replica**; Spring routes `@Transactional(readOnly=true)`.
- Production: RDS Multi-AZ + read replica, PITR 14 days, daily snapshots to ap-south-2.

**Standard table columns** (every table):
```sql
id           UUID PRIMARY KEY  -- UUIDv7 generated in Java/Python
society_id   UUID NOT NULL     -- tenant; first column of most indexes
created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
created_by   UUID
updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
updated_by   UUID
version      BIGINT NOT NULL DEFAULT 0  -- JPA @Version optimistic locking
```

**Migration files**: every service has `src/main/resources/db/platform/` (run first: `V0_1__tenancy.sql`,
`V0_2__outbox_inbox.sql`) and `src/main/resources/db/migration/` (service-specific, `V1__…`).
Python ai-service has `migrations/` at project root, applied by the service at startup.

### 5.2 Multi-Tenancy with Row-Level Security (ADR-0005)

All tenants share tables within each service DB. PostgreSQL RLS enforces tenant isolation:

```sql
-- From V0_1__tenancy.sql — this function is called in every service's own migrations:
SELECT sos_enable_tenant_rls('job_card');
-- Which generates:
ALTER TABLE job_card ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_card FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON job_card
  USING      (society_id = ANY (app_society_ids()))
  WITH CHECK (society_id = app_write_society_id());
```

- `app.society_ids` is an **array** — FM companies can read multiple societies.
- `app.write_society_id` is a single UUID — writes always target exactly one society.
- If the setting is missing, `current_setting(…, true)` returns NULL → query returns zero
  rows (safe failure mode).
- `TenantAwareJpaTransactionManager` sets both values with `SET LOCAL` at the start of
  every transaction; scope ends with the transaction so HikariCP connections never leak tenant.
- A mandatory two-society RLS test runs per service in CI.

### 5.3 Redis 7

Disposable state only — losing Redis must never lose business data.

| Use | Key pattern | TTL |
|---|---|---|
| OTP challenges | `otp:{phoneHash}` | 5 min |
| Rate limiting | `rl:{route}:{userId\|ip}` | window |
| Revoked access tokens | `jwt:deny:{jti}` | ≤ 15 min |
| Permission cache | `perm:{userId}:{societyId}` | 10 min |
| Read-through cache | `cache:{svc}:{entity}:{id}` | 5–60 min |
| Idempotency keys | `idem:{svc}:{userId}:{Idempotency-Key}` | 24 h |
| WebSocket fan-out | channels `rt:user:{userId}`, `rt:society:{id}:{topic}` | — |
| Gate pending approvals | `gate:pending:{entryId}` | 10 min |
| Distributed locks | `lock:{name}` (Redisson) | lease 5 min |
| Dashboard counters | `dash:{societyId}:{metric}` | 1 day |
| Web portal sessions | `sess:{id}` | 12 h sliding |

Rules: every key has a TTL; no raw PII in values; serialisation is JSON (never Java
serialisation); services degrade gracefully when Redis is down.

---

## 6. Event-Driven Patterns

### 6.1 Kafka

- **One events topic per bounded context**: `sos.<context>.events.v1`
- Key = aggregate id (UUID); consumers filter on `ce_type` header.
- Partitions (prod): 12 for most, 24 for gate and operations (highest volume).
- Retention: 14 days for most, 7 days for gate/operations.
- `replication.factor=3`, `min.insync.replicas=2`, `auto.create.topics.enable=false`.
- Topics created by `infra/docker/kafka/create-topics.sh` and Terraform in production.
- Command topic: `sos.notification.commands.v1` (recipient user id as key, 3 day retention).
- DLQ topic: `sos.dlq.<consumer-group>` (30 day retention).
- Compacted snapshot topic: `sos.society.snapshots.v1` (bootstrapping read models).

### 6.2 Event Envelope (CloudEvents 1.0 JSON)

```json
{
  "specversion": "1.0",
  "id": "0192c3a0-7b1e-7cc2-9d1f-5b6e2a9f1c11",
  "source": "ticket-service",
  "type": "ticket.jobcard.closed",
  "dataschema": "https://schemas.societyos.in/ticket/ticket.jobcard.closed/1.json",
  "time": "2026-09-28T06:31:04.221Z",
  "subject": "jobcard/0192c39f-...",
  "societyid": "0191aa10-...",
  "actorid": "0191ab22-...",
  "actortype": "USER",
  "traceparent": "00-...",
  "data": { ... }
}
```

Kafka message key = aggregate id. Headers duplicate `ce_type`, `ce_id`, `ce_societyid`, `traceparent`.

### 6.3 Transactional Outbox + Debezium (ADR-0003)

Business code calls `DomainEvents.publish(event)` from the platform package. This inserts
an `outbox_event` row in the **same DB transaction** as the business change. Debezium CDC
reads the WAL and produces to `sos.<aggregate_type>.events.v1`.

```sql
-- outbox_event table (identical in every service):
CREATE TABLE outbox_event (
  id             UUID PRIMARY KEY,    -- CloudEvents id
  aggregate_type TEXT NOT NULL,       -- routes to topic, e.g. 'ticket'
  aggregate_id   UUID NOT NULL,       -- Kafka message key
  type           TEXT NOT NULL,       -- 'ticket.jobcard.closed'
  society_id     UUID NOT NULL,
  payload        JSONB NOT NULL,      -- full CloudEvent envelope
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Fallback for local dev** (without Debezium): set `SOS_OUTBOX_RELAY=polling`. The platform
package includes an `OutboxPollingRelay` that uses `SELECT … FOR UPDATE SKIP LOCKED` every 200 ms.

**Direct `KafkaTemplate` use in domain code is banned** by ArchUnit rule.

### 6.4 Idempotent Consumer (inbox)

```sql
-- inbox_event table (identical in every service):
CREATE TABLE inbox_event (
  consumer    TEXT NOT NULL,
  event_id    UUID NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);
```

The `@DomainEventListener` meta-annotation (on top of `@KafkaListener`) sets the tenant
from `ce_societyid`, inserts into `inbox_event ON CONFLICT DO NOTHING`, and commits the
offset after the DB commit (`AckMode.RECORD`). At-least-once delivery, exactly-once effect.

### 6.5 Retry / DLQ

| Attempt | Location | Delay |
|---|---|---|
| 1–3 | in-process | 1 s, 2 s, 4 s |
| 4 | `…-retry-1m` topic | 1 min |
| 5 | `…-retry-10m` topic | 10 min |
| Final | `sos.dlq.<group>` | alert on-call |

Non-retryable exceptions go straight to DLQ. Unknown event *types* are **ignored** (not failed).

### 6.6 Event Catalogue

Full payload contracts in `contracts/events/CATALOGUE.md`. Key events:

| Event | Producer | Main Consumers |
|---|---|---|
| `identity.user.registered` | identity | notification, audit |
| `society.membership.created/ended` | society | identity, gate, billing, notification |
| `society.flat.created/updated` | society | gate, billing, maintenance, dashboard |
| `security.entry.requested` | security | realtime, notification, audit |
| `security.entry.approved/denied` | security | realtime (guard), audit, dashboard |
| `security.sos.raised` | security | realtime, notification, maintenance |
| `billing.bill.generated` | billing | notification, dashboard |
| `billing.payment.succeeded` | billing | notification, dashboard, community |
| `asset.pmtask.due/overdue` | asset | maintenance, notification, dashboard |
| `ticket.complaint.created` | ticket | ai, workflow, notification, dashboard |
| `ticket.jobcard.closed` | ticket | asset, billing, vendor, workflow, dashboard |
| `workflow.sla.breached/escalated` | workflow | ticket, notification, dashboard |
| `community.notice.published` | community | notification, ai (index) |
| `ai.classification.suggested` | ai | ticket |

---

## 7. Security and Tenancy

### 7.1 Authentication

| Actor | Method |
|---|---|
| Mobile (resident, guard, technician) | Phone OTP (SMS; WhatsApp fallback) + device binding |
| Admin web | Email + Argon2id password + **TOTP MFA** |
| Gate edge agent | Client certificate + device credentials |
| Service to service | OAuth2 client credentials from identity-service |
| Super Admin support | MFA + reason + time-boxed grant |

Refresh-token reuse detection: if a rotated token is presented again, the entire device session
is revoked.

### 7.2 JWT (RS256)

Issued by identity-service. JWKS at `/.well-known/jwks.json`. Keys rotate every 90 days.

Custom claims:
- `sid` — active society (writes go here)
- `sids` — array of readable societies (multi-site FM users)
- `roles` — role codes
- `pv` — permission version (cache key)
- `did` — device UUID
- `amr` — authentication methods

Permissions (`module:action` strings) are **not** in the token. Services resolve them from
`pv` + Redis cache (`perm:{userId}:{societyId}`, 10 min TTL), with identity-service as source.

### 7.3 Authorisation Layers

1. **Gateway**: token valid, not revoked (Redis deny-list on `jti`), `X-Society-Id ∈ sids`.
2. **Service**: Spring Security resource server re-validates JWT (zero-trust). Method security:
   `@PreAuthorize("@perm.has('jobcard:approve')")`.
3. **Database**: PostgreSQL RLS on `society_id`.
4. **Object level**: ownership checks in service code (a resident only sees own flat's bills).
5. **Field level**: response DTOs per role (guards see masked phone numbers).

### 7.4 DPDP Act 2023 Compliance

- Consent records per purpose and version; withdrawal in Account settings.
- Events carry IDs not PII; guards see masked data; AI receives redacted text.
- Per-society retention settings with nightly purge jobs.
- `/me/data-export` API; erasure request triggers a `privacy.erasure.requested` event.
- India-only hosting (ap-south-1 primary, ap-south-2 DR).
- Application-level field encryption: AES-256-GCM + KMS (envelope) for phone numbers,
  ID-proof numbers, TOTP secrets. Deterministic HMAC column (`phone_hash`) for lookups.

---

## 8. Service Communication

### 8.1 Synchronous REST (Exception, Not Default)

Only three synchronous cross-service calls are permitted:
1. `api-gateway` → identity-service (JWKS for JWT validation, cached)
2. `ai-service` → billing API (bill explanation, using the user's own token)
3. `media-service` → owning service (permission check before signed URL)

All other cross-service data flows through Kafka. A new synchronous dependency requires an ADR.

All Feign / RestClient calls have timeout + Resilience4j circuit breaker.
`spring.cloud.circuitbreaker.resilience4j.disable-thread-pool=true` is set globally (config-repo)
because `TenantContext` is a ThreadLocal.

### 8.2 Gateway Routing

Pattern: `/api/{service}/v1/**` → strips prefix → routes to `lb://{service}` via Eureka.
Internal endpoints (`/v1/internal/**`) are blocked at the gateway (returns 404).

WebSocket: `/ws/**` → `lb:ws://realtime-service`.

### 8.3 Realtime (WebSocket)

- `realtime-service` exposes STOMP over SockJS at `/ws`.
- Per-pod subscriptions; cross-pod fan-out uses **Redis pub/sub** (channels `rt:user:{userId}`
  and `rt:society:{id}:{topic}`).
- Consumes gate, maintenance, dashboard and workflow Kafka events, then fans out.

---

## 9. Platform Package (per-service)

There is **no shared library** (ADR-0009). Each service carries the same platform code at
`in.societyos.<svc>.platform.<part>` (and in Python: `app/platform/`). It contains no domain logic.

| Module | Provides |
|---|---|
| `platform.core` | `TenantContext`, `UuidV7`, `Money` (paise), `DocumentNumberService`, `ProblemException` (RFC 7807) |
| `platform.jpa` | `TenantEntity` base class, `TenantAwareJpaTransactionManager` (RLS SET LOCAL), `GlobalEntity` |
| `platform.events` | `DomainEvents.publish()` (outbox insert), `@DomainEventListener`, `CloudEvent<T>`, `OutboxPollingRelay` |
| `platform.security` | JWT resource server config, `PermissionEvaluator` (`@perm.has()`), `TenantResolverFilter`, deny-list |
| `platform.web` | RFC 7807 exception handler, `CorrelationFilter`, `IdempotencyFilter`, `CursorPage` |
| `platform.scheduling` | db-scheduler config, `PerSocietyTask` helper |
| `platform.test` | `IntegrationTestBase` (Testcontainers Postgres + Kafka + Redis) |

**ArchUnit rules enforced per service:**
- `domain` package depends only on itself and `java.*`
- Controllers never touch repositories; they go through a use case
- No `KafkaTemplate` outside `platform.events`
- Every `@Entity` extends `TenantEntity`
- No feature's `infrastructure` imports another feature's `infrastructure`

---

## 10. Package Structure per Java Service (Hexagonal, Package-by-Feature)

```
services/ticket-service/
  pom.xml  mvnw  mvnw.cmd
  src/main/java/in/societyos/ticket/
    TicketApplication.java
    complaint/                         ← one feature = one package
      api/        ComplaintController.java (implements generated ComplaintsApi)
      application/ ComplaintService.java, ComplaintJobCardHook.java
      domain/     Complaint.java, ComplaintEvents.java, ComplaintStatus.java
      infrastructure/ ComplaintRepository.java
    jobcard/
      api/        JobCardController.java
      application/ JobCardService.java
      domain/     JobCard.java (state machine), JobCardStatus.java, JobCardEvents.java
      infrastructure/ JobCardRepository.java, JobCardIntakeListener.java
    breakdown/  incident/  category/
    directory/             ← read model projections (asset summary, flat directory)
    notification/          ← notification.requested event publishing
    escalation/            ← SLA escalation from workflow events
    platform/              ← local copy of all platform packages
  src/main/resources/
    application.yml
    db/platform/V0_1__tenancy.sql  V0_2__outbox_inbox.sql
    db/migration/V1__....sql
  src/test/java/…
    platform/test/ IntegrationTestBase, TestTenants, SosArchitectureRules
```

Base Java package: `in.societyos.<service-name-without-hyphen>` (e.g. `in.societyos.ticket`,
`in.societyos.identity`).

---

## 11. API Conventions

- Base path inside service: `/v1`; through the gateway: `/api/{service}/v1`
- JSON, camelCase fields. Time: ISO-8601 UTC. Money: `{ "amountPaise": 425000, "currency": "INR" }`.
- Pagination: cursor-based `?limit=50&cursor=…` → `{ items, nextCursor }`.
- Errors: RFC 7807 `application/problem+json` with `code`, `traceId`.
- Writes accept `Idempotency-Key` header; required for payments and offline sync push.
- Concurrency: `ETag` / `If-Match` on updates, backed by JPA `@Version`.
- State transitions are **commands**, not field updates:
  `POST /jobcards/{id}/transitions {"action":"COMPLETE", ...}`.
- Internal-only endpoints go under `/v1/internal/**` (blocked by gateway).

---

## 12. Build and Run

### 12.1 Prerequisites

- Java 21, Docker (no global Maven needed — each service has `mvnw`)
- Node.js ≥ 20.9 (for web)
- Python 3.12 + pip (for ai-service)

### 12.2 Start Infrastructure

```bash
# Full local stack (Postgres, Redis, Kafka + topics, Kafka UI, MinIO mock, Mailpit, OTEL)
docker compose -f infra/docker/docker-compose.yml up -d

# With Debezium CDC connector (optional; use SOS_OUTBOX_RELAY=polling without it)
docker compose -f infra/docker/docker-compose.yml --profile cdc up -d

# With real MinIO object storage (optional)
docker compose -f infra/docker/docker-compose.yml --profile object-storage up -d
```

Local URLs: Kafka UI http://localhost:8180 · Mailpit http://localhost:8025 ·
MinIO console http://localhost:9001 · Grafana http://localhost:3000 (OTEL LGTM container).

### 12.3 Build All Services

```bash
# Linux/Mac
./build-all.sh -DskipTests

# Windows
.\build-all.ps1 -DskipTests

# Single service
cd services/identity-service && ./mvnw package -DskipTests
```

### 12.4 Start Services (Start Order Matters)

```bash
# 1. Eureka (service registry)
cd services/service-registry && ./mvnw spring-boot:run

# 2. Config server
cd services/config-server && ./mvnw spring-boot:run

# 3. Identity (set bootstrap admin and dev OTP code)
cd services/identity-service
SOS_OUTBOX_RELAY=polling SOS_OTP_DEV_CODE=123456 \
  SOS_BOOTSTRAP_ADMIN_EMAIL=admin@societyos.in \
  SOS_BOOTSTRAP_ADMIN_PASSWORD='ChangeMe!2026' \
  ./mvnw spring-boot:run

# 4. API Gateway (use PORT=8000 if 8080 is taken)
cd services/api-gateway && ./mvnw spring-boot:run
```

Without Debezium running: always set `SOS_OUTBOX_RELAY=polling`.

### 12.5 Test

```bash
# Unit + ArchUnit tests (one service)
cd services/identity-service && ./mvnw test

# Integration tests (Testcontainers: real Postgres with RLS, Kafka, Redis)
cd services/identity-service && ./mvnw verify -Pintegration

# Web tests
cd web && npm test

# Web build
cd web && npm run build
```

### 12.6 Web Apps

```bash
cd web
npm install
npm run dev:admin        # http://localhost:3000  (email + password + TOTP MFA)
npm run dev:resident     # http://localhost:3001  (phone OTP)
npm test                 # Vitest: BFF session/proxy + forms
npm run build            # production build of both apps
```

Key env vars (see `apps/*/.env.example`): `API_BASE_URL` (default `http://localhost:8000`),
`REDIS_URL` (default `redis://localhost:6379`), `SESSION_STORE` (`auto|redis|memory`),
`ADMIN_REQUIRE_MFA` (default `true`).

### 12.7 Quick Smoke Test via Gateway

```bash
# Request OTP (SOS_OTP_DEV_CODE=123456 makes every OTP this fixed code)
curl -X POST localhost:8080/api/identity/v1/auth/otp/request \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210"}'

# Verify OTP and get tokens
curl -X POST localhost:8080/api/identity/v1/auth/otp/verify \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","code":"123456","device":{"platform":"ANDROID","name":"Pixel"}}'

# Verify token
curl localhost:8080/api/identity/v1/me -H "Authorization: Bearer <accessToken>"
```

---

## 13. Environment Configuration

Services read configuration from two places in order:
1. **Spring Cloud Config Server** (`config-repo/application.yml` — shared; `config-repo/<service>.yml` — per service)
2. **Environment variables** (override any config-server value)

Key environment variables per service:

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/<svc>_db` | Runtime connection (app role) |
| `DB_USER` | `<svc>_app` | Runtime role |
| `DB_PASSWORD` | `<svc>_app` | Runtime password |
| `DB_OWNER_USER` | `<svc>_owner` | Flyway migration role |
| `DB_OWNER_PASSWORD` | `<svc>_owner` | Flyway migration password |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka bootstrap |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `CONFIG_SERVER_URL` | `http://localhost:8888` | Spring Cloud Config |
| `EUREKA_URL` | `http://localhost:8761/eureka` | Eureka registry |
| `SOS_OUTBOX_RELAY` | `debezium` | `debezium` or `polling` |
| `SOS_ISSUER` | `https://auth.societyos.in` | JWT issuer |
| `SOS_JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` | JWKS endpoint |
| `PORT` | varies by service | HTTP server port |
| `SOS_OTP_DEV_CODE` | (empty = real SMS) | Fixed OTP for local dev only |
| `SOS_BOOTSTRAP_ADMIN_EMAIL` | (empty) | Identity service bootstrap |
| `SOS_BOOTSTRAP_ADMIN_PASSWORD` | (empty) | Identity service bootstrap |

Production secrets: AWS Secrets Manager, mounted via External Secrets Operator. Never in Git.

---

## 14. Key Flows

### 14.1 Visitor Entry (target p95 < 3 s)

1. Guard submits visitor entry → gate-service inserts `entry_log(REQUESTED)` + outbox event
2. Debezium → Kafka `security.entry.requested`
3. Parallel: realtime-service pushes to resident's socket AND notification-service sends FCM push
4. Resident approves via app → gate-service updates to `APPROVED` + outbox event
5. Kafka `security.entry.approved` → realtime → guard console + open barrier

**Pre-approved** (QR/OTP): gate-service validates `gate_pass` locally → `checked_in`. No
resident round-trip. Edge agent caches valid passes so this works offline.

### 14.2 Complaint to Closure (Saga — Choreography)

1. Resident submits complaint → maintenance creates complaint, publishes `ticket.complaint.created`
2. Kafka → ai-service classifies → `ai.classification.suggested` → maintenance applies (if confidence ≥ 0.8)
3. Maintenance creates job card, publishes `ticket.jobcard.assigned`
4. Kafka → workflow starts SLA timer; notification pushes to technician
5. Technician works (offline possible) → eventually submits `COMPLETE`
6. Supervisor verifies; resident confirms or rejects (→ REOPENED)
7. `CLOSE` published as `ticket.jobcard.closed` →  
   - asset: history + cost + next PM date  
   - vendor: stock reconciliation + SLA score  
   - billing: recoverable cost → bill line  
   - workflow: stop SLA timer  
   - dashboard + audit + notification

### 14.3 Preventive Maintenance

Asset-service scheduler (00:30 society-local, db-scheduler) generates `pm_task(DUE)` →
`asset.pmtask.due` → maintenance creates PM job card with checklist → technician executes →
failed items auto-raise breakdown tickets → `ticket.jobcard.closed` resets next PM date.

### 14.4 Monthly Billing

Accounts triggers bill run → billing-service locks and calculates per flat (plan + bookings +
recoverables + arrears + late fees + GST) → `billing.bill.generated` per bill →
notification pushes bill to resident → resident pays via Razorpay hosted checkout →
webhook `payment.captured` → billing records receipt → `billing.payment.succeeded` →
notification sends PDF receipt.

### 14.5 Mobile Offline Sync (Technician in Basement)

Local Drift DB + outbox → `POST /v1/sync/push` (batch, one idempotency key per change) →
server answers per change: `APPLIED`, `DUPLICATE`, `CONFLICT_MERGED`, or `REJECTED` →
`GET /sync/changes?cursor=` for pull. Triggered by foreground, reconnect, FCM data message,
and every 15 min in background.

---

## 15. Architecture Decision Records

| ADR | Decision |
|---|---|
| ADR-0001 | Event-driven microservices (not modular monolith) |
| ADR-0002 | Kafka as the event backbone |
| ADR-0003 | Transactional outbox with Debezium (not direct `KafkaTemplate`) |
| ADR-0004 | db-scheduler for timers (not Redis/BullMQ) |
| ADR-0005 | Shared schema with `society_id` + PostgreSQL RLS for tenancy |
| ADR-0006 | Java 21 + Spring Boot 4.0 / Spring Cloud 2025.1 |
| ADR-0007 | Flutter single app with role-based mode switching (not separate apps) |
| ADR-0008 | Next.js BFF auth (refresh token never reaches browser) |
| ADR-0009 | Independent Maven services on Spring Cloud Netflix stack (no shared library) |

---

## 16. Phase Plan

| Phase | Timeline | Scope |
|---|---|---|
| Phase 1 — Pilot | Months 1–4 | service-registry, config-server, api-gateway, identity, society, security (gate), billing, asset, ticket, community, workflow, notification, realtime, media, audit, dashboard, ai (helpdesk/triage); mobile + web |
| Phase 2 — Operations | Months 5–8 | utility (checklists, readings), vendor/AMC/PO, inventory, compliance, OpenSearch, ClickHouse analytics |
| Phase 3 — Commerce | Months 9–12 | marketplace, vendor portal, predictive maintenance, anomaly detection |

Phase 1 exit gate: 5 pilot societies live; ≥ 60% resident activation; gate p95 < 3 s;
app collection ≥ 40%; zero P1 security incidents.

---

## 17. Coding Conventions

### Java Services

- Java package naming: `in.societyos.<service>` (e.g. `in.societyos.ticket`)
- Platform code: `in.societyos.<service>.platform.<part>`
- All entities extend `TenantEntity` (enforced by ArchUnit)
- Never extend `GlobalEntity` unless the entity truly spans societies (e.g. `app_user`)
- Domain objects have **no Spring annotations** — they live in `domain/` and depend only on `java.*`
- State machines live in the domain entity (e.g. `JobCard.assign()`, `JobCard.close()`)
- Use `ProblemException.badRequest(code, message)` / `.conflict()` / `.unprocessable()` for all API errors
- Publish domain events with `DomainEvents.publish(event)` in the same `@Transactional` method
- Use `@DomainEventListener(topic, group, type)` for consuming events (not raw `@KafkaListener`)
- DB table naming: snake_case, no plurals (`job_card` not `job_cards`)
- FK naming: `<table>_id`
- Index naming: `ix_<table>_<cols>`, unique: `ux_<table>_<cols>`
- Enumerations as `TEXT CHECK` constraints (not Postgres ENUM type)
- Money stored as `BIGINT` paise; never `DECIMAL` or `FLOAT`
- Use `JSONB` for configurable structures (checklists, specs, workflows) validated by JSON Schema

### Python ai-service

- Settings via `app/config.py` (pydantic-settings, reads env vars)
- Platform code in `app/platform/` (events, tenant, db, ids, migrations)
- `events.publish(conn, event)` for outbox publishing (same pattern as Java)
- Migrations in `migrations/` as SQL files with the same `V0_1__tenancy.sql`,
  `V0_2__outbox_inbox.sql`, then `V1__ai.sql`
- `app.society_ids` and `app.write_society_id` RLS settings, same as Java

### TypeScript / Next.js

- Server components call API with session token; client components use TanStack Query
- BFF session via `createBffFromEnv()` from `@societyos/auth`
- Generated API clients from `packages/api-client` — do not write manual fetch calls
- Use `server-only` import in any file that must not ship to the client
- Forms use react-hook-form + zod schemas (generated from OpenAPI where possible)

---

## 18. Observability

- **Traces**: OpenTelemetry via Micrometer Tracing, `traceparent` in HTTP headers AND Kafka
  message headers. Tempo as trace backend.
- **Metrics**: Micrometer → Prometheus. RED metrics per endpoint, Kafka consumer lag,
  outbox backlog, HikariCP pool.
- **Logs**: JSON (logstash encoder) → Loki. Every line has `traceId`, `societyId`, `userIdHash`.
  No raw PII in logs.
- **MDC**: `traceId`, `societyId`, `userIdHash` set by `CorrelationFilter`.
- **SLOs**: Gate approval p95 < 3 s; gate + payments 99.9% monthly; other APIs 99.5% monthly;
  event propagation p95 < 2 s.

---

## 19. Production Infrastructure Summary

- **AWS ap-south-1** (primary); **ap-south-2** (DR)
- EKS (Graviton nodes + Karpenter spot); GitOps with Argo CD
- RDS PostgreSQL 16 Multi-AZ + read replica
- Amazon MSK (3 brokers, RF=3) + MSK Connect (Debezium)
- ElastiCache Redis (cluster mode, 1 shard × 2 replicas)
- S3 (media) + CloudFront + WAF
- Helm chart per service (`infra/helm/sos-service`); values in `infra/helm/values/<service>.yaml`
- CI/CD: GitHub Actions per service path; path-filtered builds; Jib image + Trivy scan
- Migrations run as Kubernetes `Job` before rollout (`<svc>_owner` role)
- Secrets: AWS Secrets Manager + External Secrets Operator (never in Git or env files in prod)
- mTLS between pods with Linkerd (Phase 2)
