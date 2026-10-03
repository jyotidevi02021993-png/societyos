# ADR-0006 — Java 21 + Spring Boot 4.0 / Spring Cloud 2025.1

- **Status:** Accepted (2026-09-28)
- **Amends:** [07](../architecture/07-backend-spring.md) §1, which named Spring Boot 3.4

## Context
Spring Boot 3.x open-source support has ended. Starting a new code base on it would mean
a migration within months.

## Decision
Spring Boot **4.0.x** (Spring Framework 7, Spring Security 7), Spring Cloud **2025.1.x**
for the gateway, Java 21, Maven (one project per service). Virtual threads on.

## Consequences
A current support window and baseline. Some third-party starters lag behind Boot 4; we
prefer plain libraries wired by each service's own platform configuration ([ADR-0009](0009-independent-services-netflix-stack.md)).
