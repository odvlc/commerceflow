/*
 * Checks wire-envelope identity/version invariants independently of any service database.
 */
package com.commerceflow.contracts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EventEnvelopeTest {

    @Test
    void createsVersionedIdentifiableEvents() {
        // Keep aggregate identity distinct from event identity so the same order can have multiple
        // deduplicated messages.
        var aggregate = UUID.randomUUID();
        var event = EventEnvelope.create("ProductChanged", aggregate, "test-correlation", "payload");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.aggregateId()).isEqualTo(aggregate);
        assertThat(event.occurredAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(event.eventId()).isNotNull();
    }

    @Test
    void refusesUnknownSchemaAndInvalidCorrelation() {
        // Reject unsupported wire contracts at decoding boundaries instead of sending malformed
        // envelopes into business handlers.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new EventEnvelope<>(
                                UUID.randomUUID(),
                                "Test",
                                2,
                                UUID.randomUUID(),
                                "test",
                                Instant.now(),
                                "value"));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> EventEnvelope.create("Test", UUID.randomUUID(), "\n".repeat(129), "value"));
    }
}
