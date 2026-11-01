# Contracts

## Table of contents

- [Purpose](#purpose)
- [Use](#use)
- [Place in CommerceFlow](#place-in-commerceflow)

## Purpose

This Maven module holds versioned message payloads shared by CommerceFlow services. It contains event envelopes,
checkout and knowledge event DTOs, and Kafka topic names. It has no persistence code or service database access.

## Use

Services depend on this module to encode and consume compatible Kafka messages. Treat topic and schema changes as wire
compatibility changes. Add new payload versions when a change would break existing consumers.

From the repository root, compile it with `./mvnw -pl contracts -am package`.

## Place in CommerceFlow

Contracts let independently deployed services exchange events through Kafka without sharing JPA entities. The root [
`README.md`](../README.md) describes how those services participate in checkout.
