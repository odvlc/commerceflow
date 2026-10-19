# ADR 0001: service-owned state and shared infrastructure helpers

<!--
Maintenance: Records service data ownership and event-driven coordination decisions; changes should
explain the architectural tradeoff.
-->

Accepted: 2026-10-17.

Each application owns a PostgreSQL database, credentials, Flyway history, repositories and business
transactions. Catalog uses JPA; conditional inventory updates and saga persistence use explicit JDBC
within Spring local transactions. The JDBC statements make the locking and stock arithmetic visible
during review. Cross-service SQL is prohibited.

The `contracts` library contains versioned Kafka wire DTOs only. A separate `service-support`
library contains correlation/error/security configuration and outbox/deduplication helpers; it has
no JPA entities or foreign-service database connections. Every helper operates on the importing
application's own datasource.

Tests launch real native PostgreSQL and a real KRaft broker when Docker is absent. These
dependencies are restricted to tests and cannot replace runtime infrastructure. Container-specific
behavior, Keycloak login and kind durability still need their own validation.
