# Payment service

## Table of contents

- [Purpose](#purpose)
- [Use](#use)
- [Place in CommerceFlow](#place-in-commerceflow)

## Purpose

The Payment Service simulates payment authorization outcomes and records payment attempts. It supports approval,
decline, timeout, and delayed-result scenarios used by checkout and recovery flows.

## Use

Run the local stack with `bash scripts/compose-up.sh`. For a standalone Maven run, provide PostgreSQL, Kafka, and
Keycloak, then use `./mvnw -pl payment-service -am spring-boot:run`.

The default port is `8084`; configuration lives in `src/main/resources/application.yaml`. The service uses synthetic
payment behavior and does not connect to a real payment provider. Its API documentation is available at `/docs` when
running.

## Place in CommerceFlow

The Order Service requests authorization through Kafka as part of its saga. Payment results are persisted locally and
published for the order workflow. Payment records and migrations belong to this service's PostgreSQL database.
