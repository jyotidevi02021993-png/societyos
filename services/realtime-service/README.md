# SocietyOS service template

Starting point for every Spring Boot service. Create a service with:

```bash
python templates/new-service.py security-service security 8083 "Gate and security"
```

## What you get

- Independent Maven project (`spring-boot-starter-parent` 4.0.8, Spring Cloud 2025.1.3, `mvnw`).
- `platform/` package (no domain logic), identical in every service:

| Package | Use it for |
|---|---|
| `platform.core` | `TenantContext.activeSocietyId()`, `UuidV7.next()`, `Money` (paise), `ProblemException`, `Hashing.maskPhone` |
| `platform.jpa` | Extend `TenantEntity` (id, society_id, audit columns, version) or `GlobalEntity`; RLS is set per transaction; `DocumentNumberService.next("JC")` |
| `platform.events` | Events are records implementing `DomainEvent`; publish with `DomainEvents.publish(event)` inside `@Transactional`; consume with `@DomainEventListener(topic, group, type)` on `void on(CloudEvent<T> e)` |
| `platform.web` | Throw `ProblemException`; `CursorPage` for lists; `Idempotency-Key` handled automatically |
| `platform.security` | `@PreAuthorize("@perm.has('module:action')")`; `FieldCrypto` for PII columns; Feign calls forward the caller's token |

## Rules

1. Tables: every tenant table has `society_id` and `SELECT sos_enable_tenant_rls('<table>');` in its migration.
   Conventions: `id UUID PK, society_id, created_at, created_by, updated_at, updated_by, version`.
2. Migrations in `src/main/resources/db/migration/V1__…sql` (Flyway). Platform migrations (`V0_x`) are already in `db/platform`.
3. Feature packages: `<feature>/api` (controllers + DTO records), `application` (use cases, `@Transactional`),
   `domain` (entities, events), `infrastructure` (repositories, Kafka listeners, Feign clients).
   Controllers never import `infrastructure`.
4. Never use `KafkaTemplate` outside `platform`. Every consumer group needs a DLQ topic `sos.dlq.<group>`.
5. Money is `long …Paise`. Times are `Instant` (UTC). No PII (phones, photos) in events.
6. Tests: unit tests for domain rules; integration tests extend `IntegrationTestBase` (real Postgres with RLS,
   Kafka, Redis); mint tokens with `TestJwtIssuer.token(user, society, roles...)`; stub permissions with
   `givenPermissions("flat:manage")`.

```bash
./mvnw test                   # unit + ArchUnit
./mvnw verify -Pintegration   # + Testcontainers
```
