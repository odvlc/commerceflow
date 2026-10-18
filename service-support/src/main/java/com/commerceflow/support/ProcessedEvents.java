package com.commerceflow.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public class ProcessedEvents {

    private final JdbcClient jdbc;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public ProcessedEvents(JdbcClient jdbc, io.micrometer.core.instrument.MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Transactional
    public boolean once(UUID eventId, Runnable businessEffect) {
        var sample = io.micrometer.core.instrument.Timer.start(metrics);
        try {
            int inserted =
                    jdbc.sql("INSERT INTO processed_events(event_id) VALUES (:id) ON CONFLICT DO NOTHING")
                            .param("id", eventId)
                            .update();
            if (inserted == 0) {
                metrics.counter("commerceflow.kafka.duplicates").increment();
                return false;
            }
            businessEffect.run();
            return true;
        } finally {
            sample.stop(metrics.timer("commerceflow.kafka.processing"));
        }
    }
}
