# Order service

## Table of contents

- [Purpose](#purpose)
- [Use](#use)
- [Place in CommerceFlow](#place-in-commerceflow)

## Purpose

The Order Service accepts order requests, stores order state and coordinates checkout as a durable saga. It records
idempotency data and outgoing events in its own PostgreSQL database.

## Use

Run the local stack with `bash scripts/compose-up.sh`. For a Maven run, start PostgreSQL, Kafka, Keycloak, and the
Catalog Service first, then use `./mvnw -pl order-service -am spring-boot:run`.

The default port is `8082`. Set database and Kafka connection variables in `src/main/resources/application.yaml` or the
process environment. The API is documented at `/docs` while running. Order creation requires an authenticated customer
and an idempotency key; clients should reuse that key when retrying the same request.

## Place in CommerceFlow

An order request causes inventory reservation and simulated payment authorization through Kafka events. The service
advances the order only as results arrive, including compensation for failures and late results. Its local database owns
order and saga state; it does not write to inventory or payment databases.
