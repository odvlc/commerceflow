package com.commerceflow.contracts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EventEnvelopeTest {

    @Test
    void createsVersionedIdentifiableEvents() {
        var aggregate = UUID.randomUUID();
        var event = EventEnvelope.create("ProductChanged", aggregate, "test-correlation", "payload");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.aggregateId()).isEqualTo(aggregate);
        assertThat(event.occurredAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(event.eventId()).isNotNull();
    }

    @Test
    void refusesUnknownSchemaAndInvalidCorrelation() {
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
