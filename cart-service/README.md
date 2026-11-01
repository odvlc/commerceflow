# Cart service

## Table of contents

- [Purpose](#purpose)
- [Use](#use)
- [Place in CommerceFlow](#place-in-commerceflow)

## Purpose

The Cart Service stores each customer's active shopping cart in Redis. Cart entries have a configurable TTL, set by
`CART_TTL` (24 hours by default).

## Use

Start Redis and the local application stack with `bash scripts/compose-up.sh` from the repository root. To run through
Maven, ensure Redis and the identity provider are available, then use `./mvnw -pl cart-service -am spring-boot:run`.

The application listens on port `8085` by default. Redis connection settings and the cart TTL are in
`src/main/resources/application.yaml`; see the controller for the cart HTTP routes. The gateway is the normal browser
entry point.

## Place in CommerceFlow

The frontend uses the cart while a customer shops. At checkout, the Order Service validates prices against the Catalog
Service; the cart is not the authority for price or order state. Cart contents live in Redis rather than a service
PostgreSQL database.
