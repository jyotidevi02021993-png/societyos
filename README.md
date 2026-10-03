# SocietyOS

Digital operating system for gated housing societies: security & gate, estate/asset
maintenance, utilities, finance, procurement, compliance, residents and community.

Three client products on one distributed, multi-tenant backend:

| Client | Tech | Users |
|---|---|---|
| Mobile app (resident + staff modes) | Flutter | Residents, guards, technicians, housekeeping, managers, vendors |
| Resident Web Portal | Next.js (TypeScript) | Owners (incl. non-resident), tenants |
| Admin Web Portal | Next.js (TypeScript) | Super Admin, RWA, Estate/Facility Manager, Accounts |

Backend: **Java 21 + Spring Boot 4 microservices on the Spring Cloud Netflix stack**
(Eureka registry, Config Server, Spring Cloud Gateway, OpenFeign, Resilience4j),
**Apache Kafka** (domain events, transactional outbox), **PostgreSQL 16** (database per
service, row-level security), **Redis 7** (OTP, rate limits, token deny-list, caches).
Every service is an **independent Maven project**: no shared library, no root build.

## Services

| Service | Port | Status | What it does |
|---|---|---|---|
| [service-registry](services/service-registry) | 8761 | ✅ built | Eureka server; dashboard at http://localhost:8761 |
| [config-server](services/config-server) | 8888 | ✅ built | Serves [config-repo/](config-repo) (Git in prod) |
| [api-gateway](services/api-gateway) | 8080 | ✅ built | JWT + deny-list + society check, Redis rate limits, circuit breakers, `lb://` routing |
| [identity-service](services/identity-service) | 8081 | ✅ built | OTP + admin login (Argon2id, TOTP), RS256 JWT/JWKS, refresh rotation, roles & permissions |
| society, security, billing, community, ticket, asset, workflow, notification, realtime, media, audit, dashboard | 8082+ | next | See [service catalogue](docs/architecture/01-services.md) |

## Run locally

Prerequisites: Java 21, Docker. No Maven install needed (each service has `mvnw`).

```bash
# 1. Infrastructure: Postgres (a DB + owner/app roles per service), Redis, Kafka + topics, Kafka UI, MinIO, Mailpit
docker compose -f infra/docker/docker-compose.yml up -d
# Optional object storage (requires access to the MinIO container registry)
docker compose -f infra/docker/docker-compose.yml --profile object-storage up -d

# 2. Build everything (or build one service in its own folder: ./mvnw package)
./build-all.sh -DskipTests          # Windows: .\build-all.ps1 -DskipTests

# 3. Start in this order (each in its own terminal)
cd services/service-registry && ./mvnw spring-boot:run
cd services/config-server    && ./mvnw spring-boot:run
cd services/identity-service && SOS_OUTBOX_RELAY=polling SOS_OTP_DEV_CODE=123456 \
     SOS_BOOTSTRAP_ADMIN_EMAIL=admin@societyos.in SOS_BOOTSTRAP_ADMIN_PASSWORD='ChangeMe!2026' ./mvnw spring-boot:run
cd services/api-gateway      && ./mvnw spring-boot:run      # PORT=8000 if 8080 is taken
```

Try it through the gateway:

```bash
curl -X POST localhost:8080/api/identity/v1/auth/otp/request -H 'Content-Type: application/json' \
     -d '{"phone":"+919876543210"}'
curl -X POST localhost:8080/api/identity/v1/auth/otp/verify -H 'Content-Type: application/json' \
     -d '{"phone":"+919876543210","code":"123456","device":{"platform":"ANDROID","name":"Pixel"}}'
curl localhost:8080/api/identity/v1/me -H "Authorization: Bearer <accessToken>"
```

`SOS_OTP_DEV_CODE` makes every OTP `123456` (local only). Kafka UI: http://localhost:8180 ·
Mailpit: http://localhost:8025 · MinIO console: http://localhost:9001 · Swagger UI:
http://localhost:8081/swagger-ui.html.

## Web app

`web/` is an npm-workspaces monorepo: `apps/admin-web` (port 3000), `apps/resident-web`
(port 3001) and shared `packages/` (`ui`, `api-client`, `auth`, `shared`). Both apps use a
backend-for-frontend ([ADR-0008](docs/adr/0008-nextjs-bff-auth.md)): Next.js route handlers log in
against identity-service and keep the refresh token in Redis. The browser only gets an
HTTP-only, SameSite=Strict session cookie, and every API call goes through `/api/proxy/...`.

```bash
cd web
npm install
npm run dev:admin        # http://localhost:3000  (e-mail + password, then TOTP)
npm run dev:resident     # http://localhost:3001  (phone OTP)
npm test                 # Vitest: BFF session/proxy + forms
npm run build            # production build of both apps
```

Env vars (see `apps/*/.env.example`): `API_BASE_URL` (gateway, default `http://localhost:8000`),
`REDIS_URL` (default `redis://localhost:6379`), `SESSION_STORE` (`auto` | `redis` | `memory`),
`SESSION_COOKIE_SECURE`, `SESSION_TTL_SECONDS`, `ADMIN_REQUIRE_MFA`.

## Tests

```bash
cd services/identity-service
./mvnw test                  # unit + architecture (ArchUnit) tests
./mvnw verify -Pintegration  # + Testcontainers: real Postgres (RLS enforced), Kafka, Redis
```

## Architecture documents

| # | Document | What it covers |
|---|---|---|
| 00 | [Overview](docs/architecture/00-overview.md) | Goals, principles, system context, container view |
| 01 | [Service catalogue](docs/architecture/01-services.md) | Every microservice (with mapping to the Solution Design diagram) |
| 02 | [Kafka & events](docs/architecture/02-events-kafka.md) | Topics, envelope, outbox/Debezium, retries, DLQ, event catalogue |
| 03 | [PostgreSQL data](docs/architecture/03-data-postgres.md) | DB-per-service, RLS tenancy, conventions, core schemas |
| 04 | [Redis](docs/architecture/04-redis.md) | Every Redis use, key naming, TTLs |
| 05 | [Security & tenancy](docs/architecture/05-security-tenancy.md) | Auth, JWT, RBAC, multi-site, DPDP, audit |
| 06 | [Key flows](docs/architecture/06-key-flows.md) | Sequence diagrams: gate, complaint→closure, PM, billing, SLA |
| 07 | [Backend service design](docs/architecture/07-backend-spring.md) | Service template, libraries, coding rules |
| 08 | [Mobile app](docs/architecture/08-mobile-flutter.md) | Flutter structure, role modes, offline sync protocol |
| 09 | [Web portals](docs/architecture/09-web-nextjs.md) | Next.js apps, shared UI, API clients |
| 10 | [Infrastructure & DevOps](docs/architecture/10-infra-devops.md) | Local stack, Kubernetes on AWS, CI/CD, observability, DR |
| 11 | [Repo structure & build plan](docs/architecture/11-repo-and-build-plan.md) | Layout, build order, phase plan |

Decisions are recorded as ADRs in [docs/adr](docs/adr/).

## Source inputs

Merged from the documents in the parent folder: *Society Estate Management App
Requirements v1.0*, *Estate Management Flowchart & Forms*, *SocietyOS Brainstorming
Blueprint*, *SocietyOS Solution Design Document* (and its architecture diagram) and the
*Service Agreement Template* (SLA, DPDP, security and exit obligations).
