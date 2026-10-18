# CommerceFlow engineering rules

`CODEX_PROMPT.md` is the authoritative product specification. Read it before changing architecture or scope. Follow its 13 phases in order. Preserve existing files and user work.

## Implementation standards

- Java 25, Maven, Spring Boot 4.1.x where compatible; verify version compatibility in official documentation and record sources in `docs/dependencies.md`. Use the Boot BOM; do not independently upgrade Spring components.
- Use explicit, readable code, records for boundary DTOs, UUID identifiers, `BigDecimal` money, ISO currency codes, and UTC `Instant` timestamps. No Lombok, floating-point money, dummy implementations, or runtime in-memory infrastructure substitutes.
- Keep HTTP, application/domain, and persistence concerns separate. Validate input, bound pagination and quantities, return consistent `ProblemDetail` errors, propagate correlation IDs, and generate OpenAPI contracts.
- Implement real behavior and meaningful tests together. Never expose secrets or real payment/customer data. Development credentials belong only in documented local configuration.

## Architecture constraints

- Independently deployable catalog, cart, order, inventory, payment, notification, and gateway applications. `contracts` contains versioned message DTOs only; never share JPA entities or service database access.
- Each service owns its PostgreSQL database, credentials, and Flyway migrations. No cross-service SQL, direct foreign database writes, or Hibernate schema creation. Use constraints, indexes, and optimistic locking.
- Persist business changes and outgoing events in the same local transaction. Kafka delivery is at least once; wait for broker acknowledgment before marking outbox delivery, deduplicate consumers transactionally, use bounded retries and dead letters. Never imply atomic PostgreSQL/Kafka commits.
- Persist saga steps and enforce legal state transitions. Handle duplicate/late events, crashes, payment uncertainty, reconciliation, compensation, and external-operation idempotency.
- Reserve inventory with a conditional SQL update. No overselling. PostgreSQL remains authoritative for orders, payments, and inventory. Redis caches/carts have explicit TTLs and atomic updates; Elasticsearch is an eventually consistent derived search index.
- Acquire multi-product stock locks in canonical UUID string order, matching PostgreSQL UUID ordering; Java UUID's signed natural ordering differs. Saga attempt counts increase when issuing/retrying a step, not when recording its result.
- Keycloak handles identity. Use PKCE in React, JWT validation and role/ownership checks in services, and authenticated service-to-service calls. Do not build an identity provider.
- Use virtual threads where appropriate, bounded database pools, structured logs, health probes, Micrometer metrics, and environment-driven configuration. Bind development infrastructure to localhost.

## Verification and progress

- Maintain `PLAN.md` with tasks, dependencies, acceptance criteria, actual commands/results, decisions, blockers, and next steps. Check an item only when implemented and appropriately verified. Distinguish source completion from runtime validation.
- At each phase run compilation and relevant tests, fix failures, and record evidence before moving on. Prioritize a working vertical slice if the environment blocks infrastructure. Never hide failures, silently skip required integration tests, or invent results/benchmarks.
- Use JUnit, AssertJ, Mockito where suitable, MockMvc, Spring Boot Test and Testcontainers against real PostgreSQL/Kafka/Redis/Elasticsearch. Do not replace PostgreSQL with H2 for integration tests. Keep integration tests explicit and fail clearly when requested infrastructure is absent.
- Critical verification includes 100 concurrent reservations against stock 10, replay-safe events, outbox survival during broker outage, declined/late payments and compensation, search synchronization, order ownership, Redis outage behavior, and Kubernetes restart durability.
- Test frontend behavior and build/lint; validate Compose/Kustomize and run end-to-end workflows when tools permit. Document exact missing prerequisites when execution is blocked.
- Do not deploy remotely, use paid resources, send messages, or erase persisted data as part of routine work.
