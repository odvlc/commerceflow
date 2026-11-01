# Notification service

## Table of contents

- [Purpose](#purpose)
- [Use](#use)
- [Place in CommerceFlow](#place-in-commerceflow)

## Purpose

The Notification Service consumes order status events, stores notification records in its PostgreSQL database, and
exposes them through its HTTP API.

## Use

Start PostgreSQL, Kafka, and Keycloak, or run the complete local stack with `bash scripts/compose-up.sh`. To run the
service with Maven, first install the module and its local dependencies, then invoke Spring Boot from the service POM:

```bash
./mvnw -pl notification-service -am install -DskipTests
./mvnw -f notification-service/pom.xml spring-boot:run
```

`-am` selects the required modules too. A directly invoked Maven goal applies to every selected module, including the
root aggregator, so use it for the install step only. The second command targets the notification application itself.

The service listens on port `8086` by default. Its `KAFKA_BOOTSTRAP_SERVERS` environment setting points to Kafka; it
defaults to `localhost:9092` in `src/main/resources/application.yaml`. In Compose, the broker address is `kafka:29092`.
The OpenAPI page is at `/docs` while running.

## Place in CommerceFlow

Order status changes arrive asynchronously over Kafka. The service records these events as notification data for the
application to retrieve. This module does not send email, SMS, or push messages to external providers.
