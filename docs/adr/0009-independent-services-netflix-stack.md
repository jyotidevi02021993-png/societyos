# ADR-0009 — Independent service projects on the Spring Cloud Netflix stack

- **Status:** Accepted (2026-09-28)
- **Amends:** [07](../architecture/07-backend-spring.md) §3 and [11](../architecture/11-repo-and-build-plan.md) §1–2,
  which described a shared `platform/` library and a Gradle multi-project build

## Context
The product owner asked for Java + Spring Boot microservices on the Netflix-style
architecture, with each service a separate project, a gateway and a service registry, and
no shared platform library.

## Decision
- **Every service is its own Maven project** (`services/<name>/pom.xml`, own `mvnw`), with
  `spring-boot-starter-parent` as parent and the Spring Cloud BOM imported. There is no
  root build and no shared jar: a service builds, versions and deploys alone.
- **Platform code lives inside each service** as the package `in.societyos.<svc>.platform`
  (core, jpa, events, web, security, and `test` under `src/test`). It starts as the same code
  in every service and each team may adapt it; it holds no domain logic.
- **Spring Cloud Netflix stack:**

  | Concern | Component |
  |---|---|
  | Service registry | Netflix **Eureka** server (`service-registry`, port 8761) |
  | Central configuration | Spring Cloud **Config Server** (`config-server`, 8888; `config-repo/` locally, Git in prod) |
  | Edge | Spring Cloud **Gateway** (`api-gateway`, 8080) routing `lb://<service>` through Eureka |
  | Client-side load balancing | Spring Cloud LoadBalancer (successor of Netflix Ribbon) |
  | Service-to-service calls | **OpenFeign** clients (few; events are the default) |
  | Circuit breakers | **Resilience4j** (successor of Netflix Hystrix), per gateway route and Feign client |

- In Kubernetes, Eureka can be replaced by Kubernetes Service discovery by setting
  `eureka.client.enabled=false` and `spring.cloud.kubernetes.discovery.enabled=true`; the
  `lb://` routes stay the same.

## Consequences
- Services are fully independent: different release trains are possible, and one failing
  build never blocks another.
- Platform fixes (e.g. in the outbox relay) must be applied in each service. Mitigation: the
  platform packages are small, covered by each service's tests, and a checklist in the PR
  template lists the services to update.
- Eureka and Config Server are two more deployables to run highly available (2 replicas each).
