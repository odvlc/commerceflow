# AI service

## Table of contents

- [Purpose](#purpose)
- [Use](#use)
- [Place in CommerceFlow](#place-in-commerceflow)

## Purpose

The AI Service powers the optional local copilot. It handles chat runs, retrieves cited knowledge, exposes controlled
commerce tools, and records run and action state. It can use a local Ollama model and Elasticsearch for retrieval.

## Use

The default local Compose profile keeps AI off. Use the AI instructions in [`README.md`](../README.md) and [
`docs/run-and-demo.md`](../docs/run-and-demo.md) to start its model and supporting infrastructure. For Maven
development, configure PostgreSQL, Kafka, Elasticsearch, Ollama, and service URLs, then run
`./mvnw -pl ai-service -am spring-boot:run`.

The default port is `8087`. Model, embedding, search, catalog, order, and cart addresses are configured in
`src/main/resources/application.yaml` using `OLLAMA_BASE_URL`, `ELASTICSEARCH_URL`, `CATALOG_URL`, `ORDER_URL`, and
`CART_URL` among other settings. The API documentation is served at `/docs` when running.

## Place in CommerceFlow

The AI component is an optional application alongside the commerce services. It imports versioned knowledge through its
own durable ingestion path, uses existing service APIs for commerce actions, and requires explicit confirmation for
consequential cart changes. It owns a separate database and does not replace service authorization or business rules.
