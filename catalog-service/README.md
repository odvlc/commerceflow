# Catalog service

## Table of contents

- [Purpose](#purpose)
- [Use](#use)
- [Place in CommerceFlow](#place-in-commerceflow)

## Purpose

The Catalog Service owns product and category data. It exposes product APIs, supports search, caches catalog reads in
Redis, and publishes catalog changes for downstream projections.

## Use

Run the complete local stack with `bash scripts/compose-up.sh` from the repository root. For an individual Maven run,
start the required PostgreSQL, Kafka, Redis, Elasticsearch, and identity infrastructure first, then use
`./mvnw -pl catalog-service -am spring-boot:run`.

Configuration is in `src/main/resources/application.yaml`. Defaults use port `8081`; environment variables such as
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `REDIS_HOST`, and `ELASTICSEARCH_URL` configure
connections. OpenAPI UI is available at `/docs` when running.

## Place in CommerceFlow

Catalog is the authoritative source for product details and prices. Elasticsearch is a derived search index populated
through Kafka; checkout asks the catalog for trusted current prices. The module owns its PostgreSQL database and
migrations under `src/main/resources/db/migration/catalog`.
