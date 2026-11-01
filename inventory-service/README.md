# Inventory service

## Table of contents

- [Purpose](#purpose)
- [Use](#use)
- [Place in CommerceFlow](#place-in-commerceflow)

## Purpose

The Inventory Service owns stock quantities and reservations. It handles reservation commands, reports results to the
checkout workflow, and supports reservation commitment or release.

## Use

Start the local stack with `bash scripts/compose-up.sh`. To start it through Maven, provide its PostgreSQL database,
Kafka, and Keycloak, then run `./mvnw -pl inventory-service -am spring-boot:run`.

The default port is `8083`. Database and Kafka settings are supplied through `src/main/resources/application.yaml` and
environment variables. The HTTP API documentation is at `/docs` when the service is running.

## Place in CommerceFlow

During checkout, inventory reserves requested quantities using a conditional database update, so concurrent orders
cannot reserve more than available stock. Inventory changes and outgoing events share a local transaction. The service
owns its schema and migrations under `src/main/resources/db/migration/inventory`.
