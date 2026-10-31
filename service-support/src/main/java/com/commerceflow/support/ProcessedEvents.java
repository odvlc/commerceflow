/*
 * Commits the consumer deduplication marker and business effect in one local transaction so failed
 * handlers remain retryable.
 */
package com.commerceflow.support;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public class ProcessedEvents {

    private final JdbcClient jdbc;
    private final MeterRegistry metrics;

    public ProcessedEvents(JdbcClient jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Transactional
    public boolean once(UUID eventId, Runnable businessEffect) {
        var sample = Timer.start(metrics);
        try {
            int inserted = jdbc.sql(
                            "INSERT INTO processed_events(event_id) VALUES (:id) ON CONFLICT DO NOTHING")
                    .param("id", eventId)
                    .update();
            // A duplicate exits before invoking any business effect; its prior successful transaction
            // already owns the outcome.
            if (inserted == 0) {
                metrics.counter("commerceflow.kafka.duplicates").increment();
                return false;
            }
            // A handler failure rolls back this marker too, allowing Kafka redelivery to retry the effect.
            businessEffect.run();
            return true;
        } finally {
            sample.stop(metrics.timer("commerceflow.kafka.processing"));
        }
    }
}
