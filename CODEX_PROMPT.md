# CODEX MASTER PROMPT — COMMERCEFLOW

## ROLE AND MISSION

Act as a Principal Java Backend Engineer, Distributed Systems Architect, DevOps Engineer, Security Engineer, and Senior
Frontend Engineer.

Build a complete, working, production-style **Event-Driven E-Commerce Platform** named **CommerceFlow** in the current
Git repository.

**Do not deliver only scaffolding, pseudocode, or architectural diagrams. Implement, integrate, run, test, and document
the working application.**

The system must run locally on a MacBook Pro with at least 32 GB RAM, using Docker Compose for development and
Kubernetes (kind) for orchestration.

## 1. Engineering standards and working rules

- Use Java 25 LTS, Spring Boot 4.1.x where compatible, Maven, and currently supported stable libraries.
- Verify dependency and Docker image compatibility using official documentation before pinning versions.
- Use the Spring Boot BOM to manage compatible Spring dependencies.
- Prefer clean, readable Java with clear package separation.
- Use Java records for DTOs, `BigDecimal` for money, UUIDs for identifiers, UTC timestamps, and Flyway for schema
  migrations.
- Favor explicit code over excessive abstraction or Lombok.
- Follow REST, SOLID, transactional integrity, and secure-coding practices.
- Use real services for runtime infrastructure, not in-memory substitutes or mocks.
- Do not use external paid cloud resources, real payment providers, or real customer information.
- Inspect the existing repository before modifying files. Preserve existing user work.
- Create and maintain `PLAN.md`, including completed tasks, next steps, test results, decisions, and blockers.
- Implement in phases. Compile, execute tests, and fix issues after each phase.
- Continue autonomously without asking confirmation for routine technical decisions.
- Never claim functionality is working without executing an appropriate verification.
- Do not conceal build failures, skip failing tests, or invent benchmark numbers.
- If the environment imposes limits, prioritize a complete working vertical slice over many incomplete services.

## 2. Technology stack

| Category                 | Technology                                |
|--------------------------|-------------------------------------------|
| Language                 | Java 25 LTS                               |
| Backend                  | Spring Boot 4.x, Spring MVC               |
| Persistence              | Spring Data JPA, Hibernate, PostgreSQL    |
| Migrations               | Flyway                                    |
| Messaging                | Apache Kafka                              |
| Distributed transactions | Saga orchestration, transactional outbox  |
| Caching                  | Redis                                     |
| Search                   | Elasticsearch, Spring Data Elasticsearch  |
| Security                 | Spring Security, OAuth2/OIDC, Keycloak    |
| Concurrency              | Java virtual threads                      |
| Testing                  | JUnit, Mockito, AssertJ, Testcontainers   |
| Containers               | Docker, Docker Compose                    |
| Orchestration            | Kubernetes with kind, Kustomize           |
| Monitoring               | Actuator, Micrometer, Prometheus, Grafana |
| Frontend                 | React, TypeScript, Vite                   |
| CI                       | GitHub Actions                            |
| Load testing             | k6                                        |

## 3. Microservices architecture

Implement these Maven modules as independently deployable applications:

1. `catalog-service`: Product catalog, categories, prices, product search, PostgreSQL-to-Elasticsearch synchronization.
2. `cart-service`: User shopping carts persisted in Redis with TTL.
3. `order-service`: Orders, idempotency, state transitions, and saga coordination.
4. `inventory-service`: Stock, atomic reservations, reservation commitment and release.
5. `payment-service`: Simulated payment authorization, decline, timeout, and refund.
6. `notification-service`: Asynchronous notification events stored locally.
7. `api-gateway`: Secure entry point and API routing. Use a compatible supported Spring gateway implementation.
8. `contracts`: Versioned Kafka message DTOs without shared JPA entities.
9. `frontend`: React and TypeScript application.

Use one local PostgreSQL server with independently owned service databases and credentials. Cross-service SQL joins and
direct writes into another service's database are prohibited.

Use Kafka as the asynchronous event bus, Redis for cart/cache workloads, and Elasticsearch for derived full-text product
search.

## 4. Core business workflow

Implement a complete order checkout workflow:

1. Customer authenticates through Keycloak.
2. Customer browses products and searches using Elasticsearch.
3. Customer adds items to a Redis-backed cart.
4. Customer checks out through the Order Service.
5. Order Service validates current trusted prices and creates a pending order.
6. Within the same PostgreSQL transaction, persist an outbox event.
7. Outbox publisher sends the event to Kafka.
8. Inventory Service atomically reserves stock and emits the result.
9. Order Service requests simulated payment authorization.
10. Payment Service records the result and publishes an event.
11. If payment succeeds, commit the inventory reservation and confirm the order.
12. If payment is declined, release the reservation and cancel the order.
13. Publish order status events and simulated notifications.
14. React displays the updated order status.

Persist saga state durably and handle crashes, timeouts, duplicate messages, and late events.

## 5. PostgreSQL data model

Use separate Flyway migrations for each service.

**Order Service**

- `orders`: id, customer_id, idempotency_key, request_hash, status, total_amount, currency, version, created_at,
  updated_at.
- `order_items`: id, order_id, product_id, quantity, unit_price.
- `order_saga_steps`: id, order_id, step, status, attempts, last_error, updated_at.
- `outbox_events`: id, aggregate_id, event_type, payload, created_at, published_at.
- `processed_events`: event_id, processed_at.

**Inventory Service**

- `products` or a local product-reference projection.
- `inventory`: product_id, available_quantity, reserved_quantity, version.
- `reservations`: id, order_id, product_id, quantity, status, expires_at.
- `outbox_events` and `processed_events`.

**Payment Service**

- `payments`: id, order_id, amount, currency, status, timestamps.
- `payment_attempts`: id, payment_id, attempt_number, result, failure_reason, timestamps.
- `outbox_events` and `processed_events`.

**Catalog Service**

- `products`: id, sku, name, description, category, price, currency, active, version, timestamps.
- `categories`.
- `outbox_events`.

Enforce foreign keys where applicable, uniqueness constraints, quantity checks, relevant indexes, and optimistic locking
where appropriate.

Prevent inventory overselling with a conditional SQL update that succeeds only when enough stock exists. Persist
reservations and outgoing events in the same local transaction.

Implement idempotency for every externally retried business operation.

## 6. REST endpoints

Implement at least:

**Catalog**

- `GET /api/v1/products`
- `GET /api/v1/products/{id}`
- `GET /api/v1/products/search`
- `POST /api/v1/admin/products`
- `PUT /api/v1/admin/products/{id}`

**Cart**

- `GET /api/v1/cart`
- `POST /api/v1/cart/items`
- `PATCH /api/v1/cart/items/{productId}`
- `DELETE /api/v1/cart/items/{productId}`

**Order**

- `POST /api/v1/orders`
- `GET /api/v1/orders/{id}`
- `GET /api/v1/orders`
- `POST /api/v1/orders/{id}/cancel`

**Administration**

- `GET /api/v1/admin/orders`
- `GET /api/v1/admin/inventory/{productId}`
- `PUT /api/v1/admin/inventory/{productId}`
- `GET /api/v1/admin/payments/{orderId}`

Use typed request/response DTOs, `ProblemDetail`, input validation, pagination, consistent API error codes, correlation
IDs, and generated OpenAPI documentation.

Order creation must support an idempotency header and prevent different request contents from reusing an idempotency
key.

## 7. Kafka

Define versioned topics for order, inventory, payment, catalog, and notification events, plus a dead-letter topic.

Use an event envelope containing:

- eventId
- eventType
- schemaVersion
- aggregateId
- correlationId
- occurredAt
- payload

Key order-related Kafka messages by order ID.

Implement durable transactional outbox publishing, broker acknowledgments, idempotent consumers, retries with bounded
backoff, poison-message handling, dead-letter queues, and recovery documentation.

Do not assume Kafka and PostgreSQL participate in one atomic transaction.

The order saga must support:

- Successful checkout.
- Insufficient inventory.
- Declined payment.
- Payment timeout.
- Delayed payment authorization.
- Duplicate Kafka events.
- Kafka unavailability and restart.
- Inventory release and reconciliation.
- Explicit validation of legal state transitions.

## 8. Redis

Implement:

- Cache-aside product catalog caching.
- Explicit TTL and invalidation when products change.
- User-scoped shopping carts.
- Atomic cart operations or suitable concurrency protection.
- Optional rate limiting for expensive or sensitive endpoints.
- Observable cache hits, misses, and failures.
- Graceful degraded behavior where safe.

Redis must never be the sole authority for orders, payments, or inventory.

## 9. Elasticsearch

Build real Elasticsearch-powered product search.

Features:

- Full-text product-name and description search.
- Category and price filtering.
- Pagination and sorting.
- Relevance ranking.
- Explicit mappings for text, keyword, numeric, and date fields.
- Versioned indices and stable read aliases.
- Asynchronous indexing from Kafka product events.
- Product updates and deactivation synchronization.
- Deduplication and protection against stale updates.
- Full index rebuild from PostgreSQL.
- Documented eventual consistency.
- Clear failure/degraded behavior when Elasticsearch is unavailable.

Verify that the Elasticsearch server, Java client, and Spring Data versions are mutually compatible.

## 10. Spring Security

Use Keycloak as the local identity provider.

Implement OAuth2/OIDC login with Authorization Code + PKCE for React and JWT validation in backend services.

Roles:

- CUSTOMER
- ADMIN
- SUPPORT

Enforce customer-order ownership, authorization of administrative operations, and appropriate service-to-service
security.

Do not implement custom password hashing, JWT signing, or a custom identity provider.

Keep secrets outside the repository. Configure safe local development credentials only in documented development
settings.

## 11. React frontend

Build a responsive frontend with:

- Product catalog.
- Product search and filtering.
- Product details.
- Shopping cart.
- Login/logout.
- Checkout.
- Order history.
- Asynchronous order-status updates.
- Admin product management.
- Admin stock management.
- Admin order monitoring.

Use TypeScript, typed API clients, reusable components, accessible forms, loading states, validation, and error
handling.

Prevent accidental duplicate checkout submissions.

## 12. Docker Compose

Create a complete `infra/compose.yaml`.

Services must include:

- PostgreSQL
- Kafka
- Redis
- Elasticsearch
- Keycloak
- Catalog Service
- Cart Service
- Order Service
- Inventory Service
- Payment Service
- Notification Service
- API Gateway
- Prometheus
- Grafana
- React frontend

Use multi-stage Dockerfiles, persistent volumes, healthchecks, configuration through environment variables, and reliable
service discovery.

Provide lightweight Compose profiles for day-to-day development.

Configure Kafka internal/external listeners correctly for both host applications and containerized services.

Bind development ports to localhost when appropriate.

## 13. Kubernetes

Create a complete local Kubernetes deployment using kind and Kustomize.

Include:

- Namespace.
- ConfigMaps.
- Secret templates.
- Application Deployments.
- ClusterIP Services.
- Ingress.
- Readiness, liveness, and startup probes.
- CPU/memory requests and limits.
- PersistentVolumeClaims.
- Stateful infrastructure resources.
- HorizontalPodAutoscalers where appropriate.
- Rolling deployment strategy.
- NetworkPolicies where supported.
- Local container-image loading.
- Prometheus and Grafana deployment.
- Startup, teardown, diagnostics, and recovery scripts.

Ensure service addresses use Kubernetes DNS, not localhost.

Make the local configuration appropriate for a MacBook with 32 GB RAM.

Clearly document that a single-node kind environment is not a production high-availability deployment.

## 14. Virtual threads and performance

Use Java virtual threads for suitable blocking I/O code paths.

Provide a configuration option for platform-thread versus virtual-thread benchmarking.

Measure:

- Request throughput.
- p95/p99 latency.
- JVM memory.
- Thread counts.
- Database connection-pool usage.
- Kafka processing duration.
- Search query latency.
- Redis cache performance.

Do not invent performance numbers. Produce reproducible k6 tests and record actual results.

## 15. Observability

Implement Spring Boot Actuator, Micrometer, Prometheus, Grafana and structured JSON logs.

Collect:

- HTTP request count and latency.
- HTTP error rates.
- JVM heap and GC.
- HikariCP usage.
- Kafka consumer lag.
- Pending outbox events.
- Saga completion time.
- Order success and failure.
- Inventory reservation failures.
- Payment authorization outcomes.
- Redis cache hits/misses.
- Elasticsearch query latency.

Provide two provisioned Grafana dashboards:

1. Service Health.
2. Order Processing.

Add example alert rules for error rate, latency, outbox backlog, and Kafka lag.

Use correlation IDs for cross-service troubleshooting.

## 16. Automated tests

Use JUnit, Mockito, AssertJ, Spring Boot Test, MockMvc, Testcontainers, and appropriate React testing tools.

Implement unit, integration, HTTP contract, security, concurrency, Kafka, Redis, Elasticsearch, and end-to-end tests.

Critical tests:

1. Stock = 10, concurrent reservation requests = 100. Exactly 10 succeed and inventory never becomes negative.
2. Replaying one Kafka event multiple times causes its business effect only once.
3. Kafka unavailability does not lose a transactionally persisted outbox event.
4. A declined payment releases inventory and cancels the order.
5. A delayed payment result is reconciled without double charging or incorrect stock release.
6. Elasticsearch reflects product creation, update, and deactivation.
7. Unauthorized customers cannot access each other's orders.
8. Redis outages do not corrupt authoritative business data.
9. Kubernetes service restarts preserve durable data.

All test commands must be documented and executed where the environment permits.

## 17. CI and documentation

Create GitHub Actions for:

- Java build and tests.
- Frontend lint, tests and build.
- Integration tests.
- Docker builds, when feasible.

Write:

- README.md
- PLAN.md
- Architecture diagram using Mermaid.
- Data model documentation.
- Kafka event contracts.
- API contracts.
- Security model.
- Observability guide.
- Load-test results.
- Failure-recovery playbook.
- Architecture decision records.

Provide a five-minute local demonstration script showing a successful purchase, declined payment, compensating inventory
release, Elasticsearch product search, Redis caching, and Grafana monitoring.

## 18. Implementation phases

Implement sequentially:

1. Maven repository and local infrastructure.
2. Catalog Service and PostgreSQL.
3. Order and Inventory Services.
4. Kafka integration and transactional outbox.
5. Payment Service and saga orchestration.
6. Redis cache and Cart Service.
7. Elasticsearch indexing and search.
8. Keycloak and security.
9. Notification Service and React frontend.
10. Observability and load testing.
11. Complete Docker Compose stack.
12. Complete Kubernetes deployment.
13. Failure testing, CI, documentation, and final polish.

**At every phase:** compile, test, fix issues, update PLAN.md, and proceed.

## 19. Final acceptance criteria

The completed repository must demonstrate:

- A reproducible Docker Compose startup.
- A working authenticated React interface.
- Working product search backed by Elasticsearch.
- Redis-backed cart and catalog caching.
- Kafka-powered checkout.
- PostgreSQL transactional data integrity.
- Durable saga state and compensation.
- Reliable outbox and replay-safe consumers.
- Successful and declined simulated payment flows.
- Concurrency-safe inventory management.
- Grafana dashboards populated with meaningful metrics.
- Runnable automated tests.
- Local Kubernetes deployment and restart/recovery demonstration.
- Working security and authorization.
- Complete architecture, setup, API, and troubleshooting documentation.

Do not mark requirements complete unless they have been implemented and verified.

## 20. Begin implementation now

Inspect the current workspace and available tools. Create PLAN.md and a phased task checklist. Verify dependency
compatibility, then implement the project from Phase 1 through Phase 13.

Work on the actual repository files. Do not respond with just a plan.

Keep the implementation cohesive and testable. If runtime or tool limits prevent completing everything, finish the most
valuable working vertical slice, record every incomplete requirement and precise blocker, and report verified results
without exaggeration.
