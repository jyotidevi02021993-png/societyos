# SocietyOS — Project Steering Investigation Report

**Date**: 2026-09-28  
**Task**: Read all docs and code; produce a comprehensive Kiro steering document.  
**Output**: `E:\socity app\societyos\.kiro\steering\project-overview.md` ✅  

---

## Summary Answer

SocietyOS is a full-featured digital operating system for gated housing societies in India,
built as **19 microservices** (18 Spring Boot Java + 1 Python FastAPI) with Kafka event-driven
architecture, PostgreSQL 16 + Redis 7, Flutter mobile app, and two Next.js web portals. The
codebase is well-architected with 9 ADRs, 12 architecture docs, a complete event contract
catalogue, and actual running service code. The steering document was successfully written to
`.kiro/steering/project-overview.md`.

---

## Evidence

### Documentation Files Read

| File | Key Finding |
|---|---|
| `README.md` | Master overview: service table, run instructions, architecture doc index |
| `docs/architecture/00-overview.md` | Goals, architectural style, system context, guiding rules |
| `docs/architecture/01-services.md` | All 19 services with ports, DBs, topics, ownership; 3 sync-call rules |
| `docs/architecture/02-events-kafka.md` | Topic design, CloudEvents envelope, outbox pattern, DLQ, sagas |
| `docs/architecture/03-data-postgres.md` | DB-per-service, RLS tenancy, schema conventions, core schemas |
| `docs/architecture/04-redis.md` | All 12 Redis uses with key patterns and TTLs |
| `docs/architecture/05-security-tenancy.md` | Auth flows, JWT claims, RBAC, DPDP compliance, encryption |
| `docs/architecture/06-key-flows.md` | 7 sequence diagrams: visitor entry, complaint→closure, PM, billing, PO |
| `docs/architecture/07-backend-spring.md` | Full tech stack, hexagonal package structure, platform package API |
| `docs/architecture/08-mobile-flutter.md` | Flutter stack, role modes, offline sync protocol |
| `docs/architecture/09-web-nextjs.md` | Next.js stack, BFF auth, app structure, key screens |
| `docs/architecture/10-infra-devops.md` | AWS topology, Helm, CI/CD, observability, SLOs, DR |
| `docs/architecture/11-repo-and-build-plan.md` | Repo layout, platform package API, build order, phase plan |
| `docs/adr/0001-event-driven-microservices.md` | Why event-driven over modular monolith |
| `docs/adr/0003-transactional-outbox-debezium.md` | Why outbox over direct KafkaTemplate |
| `docs/adr/0005-postgres-rls-tenancy.md` | Why RLS over schema-per-tenant |
| `docs/adr/0006-spring-boot-4.md` | Spring Boot 4.0 / Spring Cloud 2025.1 choice |
| `docs/adr/0008-nextjs-bff-auth.md` | Why BFF for web portal auth |
| `docs/adr/0009-independent-services-netflix-stack.md` | No shared library; Spring Cloud Netflix stack |
| `contracts/events/CATALOGUE.md` | All event types, producers, consumers, payload shapes |
| `config-repo/application.yml` | Shared Spring Cloud Config |

### Source Files Read

| File | Key Finding |
|---|---|
| `services/identity-service/src/main/java/…/IdentityApplication.java` | Entry point, `@SpringBootApplication`, `@ConfigurationPropertiesScan` |
| `services/identity-service/src/main/java/…/auth/api/AuthController.java` | OTP request/verify, password login, TOTP, token refresh, society switch, logout |
| `services/identity-service/src/main/java/…/platform/events/DomainEvents.java` | Outbox insert pattern with CloudEvents 1.0 envelope; requires active transaction |
| `services/identity-service/src/main/java/…/platform/jpa/TenantEntity.java` | Base entity: UUIDv7 on persist, `society_id` from `TenantContext`, optimistic lock `@Version` |
| `services/identity-service/src/main/java/…/platform/security/SosClaims.java` | Custom JWT claim names: `sid`, `sids`, `roles`, `pv`, `did`, `typ` |
| `services/identity-service/src/main/resources/application.yml` | All service config keys with defaults; OTP settings; service-client secrets |
| `services/identity-service/src/main/resources/db/platform/V0_1__tenancy.sql` | `sos_enable_tenant_rls()` function, `sos_prevent_locked_change()` trigger, `document_sequence` table |
| `services/identity-service/src/main/resources/db/migration/V1__identity.sql` | `app_user`, `permission`, `role` tables |
| `services/identity-service/src/main/resources/db/migration/V2__permission_catalogue.sql` | 40+ permission codes; role template bundles |
| `services/ticket-service/src/main/java/…/jobcard/domain/JobCard.java` | Full state machine: OPEN→ASSIGNED→IN_PROGRESS⇄WAITING→COMPLETED→VERIFIED→CLOSED→REOPENED; locking; approval integration |
| `services/api-gateway/src/main/resources/application.yml` | All 19 route definitions, rate limiting, circuit breakers, CORS, public paths |
| `ai-service/app/platform/events.py` | Python outbox pattern: same CloudEvents envelope as Java |
| `ai-service/app/config.py` | All settings with env var names: DB, Kafka, JWT, LLM, embeddings, RAG |
| `ai-service/migrations/V1__ai.sql` | pgvector schema: `kb_document`, `kb_chunk` (vector(384)), `conversation`, `triage_suggestion`, `estate_signal`, `estate_health_summary`, `llm_usage`, `llm_call_log` |
| `web/package.json` | npm workspaces; Node.js ≥ 20.9 requirement; dev/build/test scripts |
| `web/apps/admin-web/package.json` | Next.js 15.5, React 19, TanStack Query, react-hook-form, zod, shadcn/ui |
| `web/apps/admin-web/.env.example` | `API_BASE_URL`, `SESSION_STORE`, `REDIS_URL`, `ADMIN_REQUIRE_MFA` |
| `web/apps/admin-web/app/(portal)/layout.tsx` | Server-side session check; redirect on no-session or MFA-required |
| `web/apps/admin-web/lib/bff.ts` | `createBffFromEnv("admin")` from `@societyos/auth` |
| `web/packages/auth/package.json` | ioredis, server-only, Next.js 15 peer dep |
| `infra/docker/docker-compose.yml` | postgres:16 (wal_level=logical), redis:7, kafka:3.8.1 (KRaft), Debezium connect:2.7 (profile cdc), kafka-ui, minio (profile object-storage), mailpit, s3mock, otel-lgtm; service registry + config server containers |
| `build-all.ps1` | Loops over `services/*`, runs `mvnw -q -B package` in each |

---

## Key Architectural Findings

### 1. Services Are Fully Independent

Each Spring Boot service has its own `pom.xml` with `spring-boot-starter-parent 4.0.8` and
Spring Cloud `2025.1.3`. No root build. No shared Maven artifact. Platform code is **copied**
into each service at `in.societyos.<svc>.platform`. This was confirmed by reading the
`identity-service` and `ticket-service` platform packages — identical `DomainEvents.java`,
`TenantEntity.java`, etc.

Practical implication: a platform fix (e.g. a bug in the outbox relay) must be applied in
all affected services separately.

### 2. Spring Boot 4.0 (Not 3.x)

Confirmed from `pom.xml` — `spring-boot-starter-parent version 4.0.8`. ADR-0006 explains the
choice: Spring Boot 3.x support has ended. This is important because some third-party starters
lag behind Boot 4.

### 3. PostgreSQL RLS is the Primary Tenant Guard

`V0_1__tenancy.sql` defines `sos_enable_tenant_rls(tbl)` as a helper function. Every service
migration calls it on every tenant table. The `TenantAwareJpaTransactionManager` runs
`set_config('app.society_ids', ?, true)` at the start of every transaction. This was confirmed
in both Java (`TenantAwareJpaTransactionManager.java`) and Python (`app/platform/tenant.py`).

### 4. Event Catalogue is the Contract Authority

`contracts/events/CATALOGUE.md` lists every event type with exact payload fields. This is the
single source of truth. Producers cannot add required fields or rename existing ones within v1.
Breaking changes require a v2 topic published in parallel.

### 5. ai-service is Python FastAPI, Not Spring Boot

Confirmed from `ai-service/app/config.py` (pydantic-settings), `ai-service/app/platform/events.py`
(psycopg3 outbox), and `ai-service/migrations/V1__ai.sql` (pgvector). The service follows all
the same tenancy and outbox patterns as the Java services.

### 6. JobCard State Machine Is Fully Implemented

`JobCard.java` contains the complete state machine with locking, approval integration, SLA
breach tracking, and escalation. `lockedAt` is set on `CLOSE`; a DB trigger prevents any
further updates. This is the reference for understanding how domain state machines work in this codebase.

### 7. Web Portals Use True BFF Pattern

`admin-web/lib/bff.ts` calls `createBffFromEnv("admin")` from `@societyos/auth`. The
`layout.tsx` in the portal group calls `getServerSession(bff)` — entirely server-side.
The browser never receives the refresh token; it only holds a session cookie that references a
Redis session record.

### 8. Debezium Is Optional in Local Dev

`infra/docker/docker-compose.yml` puts the `connect` (Debezium) container under the `cdc`
profile. All service containers default to `SOS_OUTBOX_RELAY: polling`. The `OutboxPollingRelay`
in the platform package polls `outbox_event FOR UPDATE SKIP LOCKED` every 200 ms. Production
uses real Debezium (Amazon MSK Connect).

---

## Conclusions

1. The architecture is fully designed and mostly implemented for Phase 1. The identity-service
   and ticket-service are substantially complete with real business logic, domain state machines,
   and platform infrastructure.

2. The docs and code are consistent — the ADRs, architecture docs, contract catalogue, and
   source code all agree with each other.

3. Phase 2 services (utility, vendor, inventory, compliance) have folder structures and pom.xml
   but the domain source files appear to be mostly scaffolding.

4. The ai-service has the database schema and platform infrastructure complete but
   the `helpdesk/`, `triage/`, `knowledge/`, `llm/`, `estate/` modules appear to be stubs
   (empty `__init__.py` files).

5. The web packages (`api-client`, `auth`, `shared`) are present and functional; the admin-web
   and resident-web app shells are built but specific feature pages (beyond the auth flow and
   portal shell) are not yet fully implemented.

---

## Recommendations

1. **Complete ai-service module implementations**: `app/helpdesk/__init__.py`,
   `app/triage/__init__.py`, `app/knowledge/__init__.py`, `app/llm/__init__.py` are all empty.
   The schemas and platform infrastructure are ready; the business logic needs to be written.

2. **No shared library discipline**: When applying a platform fix, use a checklist — update all
   services that carry the affected platform module. The PR template should list affected services.

3. **When adding a new service event**: Update `contracts/events/CATALOGUE.md` first; this is
   the contract. The consumer should not be written until the event type is in the catalogue.

4. **SOS_OUTBOX_RELAY=polling in local dev**: Always set this unless the Debezium `cdc` profile
   is explicitly running. Services will not publish any events otherwise.

5. **Two-society RLS test**: Every service integration test (`./mvnw verify -Pintegration`) must
   include the two-society RLS test from `IntegrationTestBase`. Do not skip it.
