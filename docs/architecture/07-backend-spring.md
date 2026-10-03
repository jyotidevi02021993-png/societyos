# 07 — Backend service design (Java 21 + Spring Boot 4)

## 1. Stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 (LTS), virtual threads on (`spring.threads.virtual.enabled=true`) |
| Framework | Spring Boot 4.0.x ([ADR-0006](../adr/0006-spring-boot-4.md)), Spring Web MVC (blocking + virtual threads); Spring Cloud Gateway (WebFlux) for the gateway only |
| Build | **Maven**, one independent project per service (`spring-boot-starter-parent` + Spring Cloud BOM), Maven wrapper in each ([ADR-0009](../adr/0009-independent-services-netflix-stack.md)) |
| Persistence | Spring Data JPA + Hibernate 6, HikariCP, **Flyway** migrations; jOOQ allowed for reporting queries |
| Messaging | Spring for Apache Kafka (`@KafkaListener`, `@RetryableTopic`), Debezium outbox for publishing |
| Cache / Redis | Spring Data Redis (Lettuce), Spring Cache, Redisson for locks |
| Security | Spring Security OAuth2 Resource Server (JWT), Spring Authorization Server in identity-service for issuing tokens |
| Scheduling | **db-scheduler** (Postgres-backed, clustered) |
| Resilience | Resilience4j (circuit breaker, retry, timeout, bulkhead) for every outbound HTTP call |
| HTTP clients | Spring `RestClient` + `@HttpExchange` interfaces generated from OpenAPI |
| API docs | springdoc-openapi; contract-first specs in `contracts/openapi/<svc>.yaml`, server interfaces generated with openapi-generator (`interfaceOnly=true`) |
| Mapping / validation | MapStruct, Jakarta Bean Validation, networknt json-schema-validator for JSONB |
| Observability | Micrometer + OpenTelemetry (OTLP) → Grafana stack; logback JSON encoder (logstash) |
| Testing | JUnit 5, AssertJ, Testcontainers (Postgres, Kafka, Redis), Spring Cloud Contract or Pact for consumer contracts, ArchUnit |
| Code quality | Spotless (google-java-format), Error Prone, SpotBugs + FindSecBugs, JaCoCo (≥ 70% on changed code) |
| Container | Jib or buildpacks → distroless Java 21 image, non-root |

## 2. Package structure per service (hexagonal, package-by-feature)

```
services/ticket-service/
  pom.xml  mvnw
  src/main/java/in/societyos/maintenance/
    MaintenanceApplication.java
    jobcard/                       ← one feature = one package
      api/                         ← REST controllers, request/response DTOs, mappers
        JobCardController.java      (implements generated JobCardsApi)
      application/                 ← use cases, @Transactional boundaries, ports
        AssignJobCardUseCase.java
        CloseJobCardUseCase.java
        port/ JobCardRepository.java, AssetDirectory.java
      domain/                      ← entities, value objects, state machine, domain events (no Spring)
        JobCard.java, JobCardStatus.java, JobCardTransitions.java
        event/ JobCardClosed.java
      infrastructure/              ← JPA adapters, Kafka listeners, HTTP clients
        persistence/ JobCardEntity.java, JpaJobCardRepository.java
        messaging/  PmTaskDueListener.java, ClassificationSuggestedListener.java
    complaint/ …
    breakdown/ …
    incident/ …
    sla/ …
    sync/                          ← mobile delta sync endpoints
    readmodel/                     ← asset_summary, flat_directory projections
  src/main/resources/
    application.yml
    db/migration/V1__init.sql …
  src/test/java/…  (unit, slice, Testcontainers integration, ArchUnit)
```

Rules checked by ArchUnit:
- `domain` depends on nothing outside `domain` and `java.*`.
- Controllers never touch repositories directly; they go through a use case.
- No `KafkaTemplate` outside `platform` (publishing is only through the outbox).
- Every `@Entity` extends `TenantEntity`.
- No package imports another feature's `infrastructure`.

## 3. Platform packages (inside each service)

There is no shared library ([ADR-0009](../adr/0009-independent-services-netflix-stack.md)). Each service carries the same
platform code as the package `in.societyos.<svc>.platform.<part>`; it contains **no domain logic**.

| Module | Provides |
|---|---|
| `platform-core` | `TenantContext`, `UuidV7`, `Money` (paise), `DocumentNumberService`, error model (RFC 7807 `ProblemDetail`), clock abstraction |
| `platform-web` | Global exception handler, request logging, `Idempotency-Key` filter (Redis), pagination/sort conventions, `X-Society-Id` resolution |
| `platform-security` | JWT resource server config, `@perm.has()` bean, permission cache, service-to-service token client |
| `platform-jpa` | `TenantEntity` base (id, society_id, audit columns, version), `TenantTransactionListener` (SET LOCAL for RLS), read-replica routing, Hibernate tenant `@Filter` |
| `platform-events` | `DomainEvents.publish()` → outbox insert, CloudEvents envelope, `@IdempotentConsumer` (inbox), Kafka consumer factory defaults, retry/DLQ config, JSON Schema validation in tests, polling relay fallback |
| `platform-scheduling` | db-scheduler config, per-society iteration helper |
| `platform-observability` | OTel config, MDC (`traceId`, `societyId`, `userIdHash`), Kafka header propagation, standard metrics |
| `platform-test` | Testcontainers base classes (Postgres + Kafka + Redis singleton containers), JWT test builders, event assertion helpers |
| `platform.*.config` | Plain `@Configuration` classes in the service that wire all of the above |

## 4. API conventions

- Base path `/api/{service}/v1` (through the gateway); inside the cluster, `/v1`.
- JSON, camelCase. Time is ISO-8601 UTC. Money is `{ "amountPaise": 425000, "currency": "INR" }`.
- Pagination: cursor based `?limit=50&cursor=…` → `{ items, nextCursor }`.
- Errors: RFC 7807 `application/problem+json` with `code` (e.g. `JOBCARD_LOCKED`), `traceId`.
- Writes accept `Idempotency-Key`; required for payments and sync push.
- Concurrency: `ETag` / `If-Match` on updates, mapped to JPA `@Version`.
- State transitions are **commands**, not field updates:
  `POST /jobcards/{id}/transitions {"action":"COMPLETE", …}`.

## 5. Transactions and events in code

```java
@Service
@RequiredArgsConstructor
class CloseJobCardUseCase {
  private final JobCardRepository jobCards;
  private final DomainEvents events;

  @Transactional
  @PreAuthorize("@perm.has('jobcard:close')")
  public JobCardView close(UUID id, CloseCommand cmd) {
    JobCard jc = jobCards.getForUpdate(id);          // tenant already set by listener
    jc.close(cmd.rootCause(), cmd.workDone(), clock); // domain enforces state machine + lock
    jobCards.save(jc);
    events.publish(JobCardClosed.from(jc));           // outbox row, same transaction
    return JobCardView.of(jc);
  }
}
```

```java
@Component
class PmTaskDueListener {
  // platform annotation: subscribes to the topic, filters on the ce_type header,
  // sets TenantContext from ce_societyid, and records the event id in inbox_event
  @DomainEventListener(topic = "sos.asset.events.v1", group = "maintenance.pm-jobcards",
                       type = "asset.pmtask.due")
  void on(CloudEvent<PmTaskDue> e) { createPmJobCard.handle(e.data()); }
}
```

`@DomainEventListener` is a meta-annotation in `platform-events`. Under the hood it is a
`@KafkaListener` with a header-based `RecordFilterStrategy`, the inbox check, and
`@RetryableTopic` defaults.

## 6. Configuration

- `application.yml` holds defaults; per-environment values come from env vars
  (Kubernetes ConfigMap + External Secrets).
- Per-society business configuration (SLA policies, checklists, approval chains, billing
  plans) is **data** in the owning service, managed from the admin portal, never config files.
- Feature flags: Unleash (self-hosted) via the platform SDK; flags can target a society.

## 7. Testing pyramid

| Level | What | Tooling |
|---|---|---|
| Unit | Domain state machines, calculators (bill, SLA, PM schedule) | JUnit, no Spring |
| Slice | Controllers (`@WebMvcTest`), repositories (`@DataJpaTest` + Testcontainers Postgres with RLS on) | Spring test slices |
| Integration | Use case + DB + outbox + Kafka consumer end to end inside one service | Testcontainers |
| Contract | OpenAPI conformance; event JSON Schema compatibility; consumer-driven contracts for sync calls | openapi-diff, json-schema, Pact |
| E2E | Top 20 flows across services on the full compose stack / staging | Playwright (web), Maestro (mobile), REST-assured |
| Load | Gate approval p95 < 3 s at 2× peak; bill run for 3,000 flats < 2 min | k6 |

A mandatory RLS test runs per service: it creates two societies and asserts that every
repository query under society A returns zero rows of society B.
