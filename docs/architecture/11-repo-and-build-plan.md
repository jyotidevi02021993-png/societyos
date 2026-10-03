# 11 — Repository structure and build plan

## 1. Repository layout

One Git repository, but **every service is an independent Maven project** with no shared
library and no root build ([ADR-0009](../adr/0009-independent-services-netflix-stack.md)).

```
societyos/
  README.md
  build-all.sh / build-all.ps1     ← convenience loop over services/*; CI builds each alone
  config-repo/                     ← Spring Cloud Config files (application.yml, <service>.yml)
  services/
    service-registry/              ← Eureka server            (8761)
    config-server/                 ← Spring Cloud Config      (8888)
    api-gateway/                   ← Spring Cloud Gateway     (8080)
    identity-service/              ← built
    society-service/  security-service/  billing-service/  community-service/
    ticket-service/   asset-service/     workflow-service/ notification-service/
    realtime-service/ media-service/     audit-service/    dashboard-service/
    utility-service/  vendor-service/    inventory-service/ compliance-service/   (Phase 2)
    marketplace-service/                                                          (Phase 3)
  ai-service/                      ← Python 3.12 FastAPI
  apps/
    mobile/                        ← Flutter
    admin-web/  resident-web/      ← Next.js
  contracts/
    openapi/<service>.yaml
    events/<context>/<event-type>/1.json
  infra/
    docker/docker-compose.yml      ← Postgres (DB + roles per service), Redis, Kafka, Kafka UI, MinIO, Mailpit, Debezium (profile cdc)
    docker/postgres/init/          ← creates <svc>_db, <svc>_owner, <svc>_app
    docker/kafka/create-topics.sh  ← all topics and DLQs (auto-create is off)
    helm/  terraform/  k8s/
  docs/architecture/  docs/adr/
```

Inside one service (identity-service shown):

```
services/identity-service/
  pom.xml  mvnw  mvnw.cmd  .mvn/
  src/main/java/in/societyos/identity/
    IdentityApplication.java
    auth/ user/ role/ key/           ← features: api / application / domain / infrastructure
    platform/                        ← this service's copy of the platform code (no domain logic)
      core/      TenantContext, Tenant, UuidV7, Money, Hashing, ProblemException
      jpa/       TenantEntity, GlobalEntity, TenantAwareJpaTransactionManager (RLS), DocumentNumberService
      events/    DomainEvent, DomainEvents (outbox), @DomainEventListener (inbox, retry, DLQ), polling relay
      web/       RFC 7807 errors, CorrelationFilter, IdempotencyFilter, CursorPage
      security/  JWT resource server, TenantResolverFilter, @perm.has(), deny-list validator
  src/main/resources/
    application.yml
    db/platform/V0_1__tenancy.sql, V0_2__outbox_inbox.sql
    db/migration/V1__….sql
  src/test/java/in/societyos/identity/
    platform/test/                   ← IntegrationTestBase (Testcontainers), TestTenants, SosArchitectureRules
```

Java base package: `in.societyos.<service>`; platform code: `in.societyos.<service>.platform.<part>`.

## 2. Platform API (the platform package every service carries)

| Type | Package | Purpose |
|---|---|---|
| `TenantEntity` (abstract `@MappedSuperclass`) | `platform.jpa` | `id UUID`, `societyId`, `createdAt/By`, `updatedAt/By`, `@Version version` |
| `TenantContext` | `platform.core` | `current()` → `{userId, activeSocietyId, readableSocietyIds, roles}`; `runAs(societyId, Runnable)` |
| `UuidV7.next()` | `platform.core` | ID generator |
| `Money` (record, `long paise`) | `platform.core` | Arithmetic, formatting |
| `DocumentNumberService.next(kind)` | `platform.jpa` | `JC-2026-000123` style numbers |
| `DomainEvent` (interface: `type()`, `aggregateType()`, `aggregateId()`) | `platform.events` | Implemented by event records |
| `DomainEvents.publish(DomainEvent)` | `platform.events` | Writes the outbox row in the current transaction |
| `CloudEvent<T>` (record) | `platform.events` | Envelope received by listeners |
| `@DomainEventListener(topic, group, type)` | `platform.events` | Kafka listener + type filter + tenant + inbox idempotency + retry/DLQ |
| `@perm.has('module:action')` bean `PermissionEvaluator` | `platform.security` | Method security |
| `ProblemException(code, status, message)` | `platform.web` | RFC 7807 errors |
| `@Idempotent` on controller methods | `platform.web` | Redis-backed `Idempotency-Key` |
| `PerSocietyTask` helper | `platform.scheduling` | db-scheduler recurring task that runs per society |
| `IntegrationTestBase` | `platform.test` | Testcontainers Postgres + Kafka + Redis |

## 3. Build order

| Step | Deliverable | Depends on |
|---|---|---|
| 0 | Repo, Maven projects, CI skeleton, local Docker stack; **service-registry, config-server** | — ✅ |
| 1 | Platform package (tenancy/RLS, outbox, inbox, security, errors) + tests | 0 ✅ |
| 2 | identity-service + api-gateway (OTP login, JWT, roles) | 1 ✅ |
| 3 | society-service (master data, members, Excel import) | 2 |
| 4 | gate-service + realtime + notification (first end-to-end: visitor approval) | 3 |
| 5 | asset + maintenance + workflow + media (complaint → job card → closure; PM) | 3 |
| 6 | billing (bill run, Razorpay, receipts, ledger) + community (notices, polls, bookings) | 3 |
| 7 | dashboard + audit + ai (helpdesk, classification) | 4–6 |
| 8 | Mobile modes and web portals in parallel with 3–7, against generated clients + mocks | contracts |
| 9 | Phase 2: operations, procurement, document; OpenSearch; ClickHouse | Phase 1 live |
| 10 | Phase 3: marketplace, vendor portal, predictive maintenance | Phase 2 gate |

## 4. Phase plan and gates

| Phase | Months | Scope | Exit gate before the next phase's go-to-market spend |
|---|---|---|---|
| 1 — Pilot | 1–4 | Steps 0–8: gate, billing, complaints/job cards, assets + QR, PM, community, dashboard, AI helpdesk | 5 pilot societies live; ≥ 60% resident activation; gate p95 < 3 s; collection via app ≥ 40%; zero P1 security incidents |
| 2 — Operations depth | 5–8 | Utilities, checklists and daily sign-off, vendors/AMC/inventory/PO, compliance, NL search, health summary | 25 societies; PM compliance ≥ 85% in pilots; MTTR trending down; churn 0 |
| 3 — Commerce | 9–12 | Marketplace, vendor portal, predictive maintenance, anomaly detection | 50 societies; unit economics positive per society |
