# Workflow tests

## Table of contents

- [Purpose](#purpose)
- [Use](#use)
- [Place in CommerceFlow](#place-in-commerceflow)

## Purpose

This Maven module contains cross-service integration tests. Its fixtures start application contexts and exercise
boundaries with PostgreSQL, Kafka, Redis, Elasticsearch, and signed test tokens. It is a test harness, not a deployable
application.

## Use

Run the module's tests from the repository root with `./mvnw -pl workflow-tests -am verify`. The tests use real
infrastructure processes or configured test infrastructure; see the root [`README.md`](../README.md) and [
`docs/verification.md`](../docs/verification.md) for prerequisites and recorded verification scope. AI model evaluation
is opt-in through the `ai-evaluation` profile and requires its model setup.

## Place in CommerceFlow

Service unit and integration tests live with their owning modules. `workflow-tests` checks interactions across those
services, including checkout, identity behavior, and AI evaluation paths, without changing production runtime
architecture.
