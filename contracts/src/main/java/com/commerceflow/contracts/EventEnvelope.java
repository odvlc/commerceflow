/*
 * Carries wire identity, schema version and correlation; event identity is independent of the
 * business aggregate identity.
 */
package com.commerceflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire contract only. Delivery is at least once; eventId is the consumer deduplication key.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        String correlationId,
        Instant occurredAt,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
        if (eventType == null || eventType.isBlank())
            throw new IllegalArgumentException("eventType required");
        // Fail unknown schemas explicitly rather than interpreting a newer payload using incompatible
        // assumptions.
        if (schemaVersion != 1)
            throw new IllegalArgumentException("Unsupported schema version");
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128)
            throw new IllegalArgumentException("Invalid correlationId");
    }

    public static <T> EventEnvelope<T> create(String type, UUID aggregateId, String correlationId, T payload) {
        // Create a new delivery identity; retries of this envelope must retain that identity
        // for deduplication.
        return new EventEnvelope<>(UUID.randomUUID(), type, 1, aggregateId, correlationId, Instant.now(), payload);
    }
}
