# Service support

## Table of contents

- [Purpose](#purpose)
- [Use](#use)
- [Place in CommerceFlow](#place-in-commerceflow)

## Purpose

This library provides infrastructure used inside backend applications: Kafka publishing and consumer setup, outbox
support, processed-event deduplication, request correlation, shared web configuration, API errors, security helpers, and
metrics.

## Use

Backend modules include `service-support` as a Maven dependency. Configuration reads
`commerceflow.messaging.bootstrap-servers` (default `localhost:9092`) and `commerceflow.messaging.enabled` (default
`true`). The producer waits for broker acknowledgments; consumers commit records after handling and use bounded retries
with dead-letter routing. `OutboxPublisher` uses the importing service's transaction-aware JDBC access; each service
still owns its outbox table and transaction boundary.

Build it with `./mvnw -pl service-support -am package` from the repository root. For broker addresses in local Compose,
see [`infra/compose.yaml`](../infra/compose.yaml).

## Place in CommerceFlow

The module shares transport and web mechanics. Business rules, schema, credentials, and database access remain with each
owning application. It is a library, not a separately deployed service.
