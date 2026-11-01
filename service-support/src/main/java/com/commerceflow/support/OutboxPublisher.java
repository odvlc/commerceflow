/*
 * Publishes committed local events and marks delivery only after broker acknowledgment; the
 * acknowledgment/SQL gap permits replay.
 */
package com.commerceflow.support;

import com.commerceflow.contracts.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class OutboxPublisher {

    private record Pending(
            UUID id,
            UUID aggregateId,
            String type,
            String payload,
            int attempts) {

    }

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry metrics;

    public OutboxPublisher(JdbcClient jdbc, KafkaTemplate<String, String> kafka, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.metrics = metrics;
        metrics.gauge("commerceflow.outbox.pending", this, self -> self.pendingCount());
    }

    public long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
                .query(Long.class)
                .single();
    }

    @Transactional
    public int publishBatch() {
        // Only the earliest unpublished event per aggregate is eligible, even during retry backoff.
        var batch = jdbc.sql("""
                        SELECT e.* FROM outbox_events e
                        WHERE e.published_at IS NULL AND e.next_attempt_at<=now()
                        AND NOT EXISTS (SELECT 1 FROM outbox_events earlier WHERE earlier.aggregate_id=e.aggregate_id
                          AND earlier.published_at IS NULL AND earlier.sequence<e.sequence)
                        ORDER BY e.sequence LIMIT 20 FOR UPDATE OF e SKIP LOCKED
                        """)
                .query((rs, n) ->
                        new Pending(
                                rs.getObject("id", UUID.class),
                                rs.getObject("aggregate_id", UUID.class),
                                rs.getString("event_type"),
                                rs.getString("payload"),
                                rs.getInt("attempts")))
                .list();

        int delivered = 0;
        for (var event : batch) {
            try {
                kafka.send(topic(event.type()), event.aggregateId().toString(), event.payload())
                        // Wait for broker acknowledgment before setting published_at. Producer idempotence cannot make the
                        // SQL and Kafka writes atomic.
                        .get(8, TimeUnit.SECONDS);
                // A crash before this commit sends the event again. Consumers deduplicate replays.
                jdbc.sql("UPDATE outbox_events SET published_at=now(),attempts=attempts+1,last_error=NULL"
                                + " WHERE id=:id")
                        .param("id", event.id())
                        .update();
                metrics.counter("commerceflow.outbox.delivered").increment();
                delivered++;
            } catch (Exception failure) {
                if (failure instanceof InterruptedException)
                    Thread.currentThread().interrupt();
                // Bound exponential retry delay while leaving the durable unpublished event available
                // for later recovery.
                long delay = Math.min(60_000, 250L * (1L << Math.min(8, event.attempts())));
                jdbc.sql("UPDATE outbox_events SET"
                                + " attempts=attempts+1,next_attempt_at=:next,last_error=:error WHERE id=:id")
                        .param("id", event.id())
                        .param("next", Timestamp.from(Instant.now().plusMillis(delay)))
                        .param("error", failure.getClass().getSimpleName())
                        .update();
                metrics.counter("commerceflow.outbox.failures").increment();
            }
        }
        return delivered;
    }

    private static String topic(String type) {
        return switch (type) {
            case "ProductChanged" -> Topics.CATALOG;
            case "KnowledgeChanged" -> Topics.AI_KNOWLEDGE;
            case "ReserveInventory",
                 "CommitInventory",
                 "ReleaseInventory",
                 "AuthorizePayment",
                 "CancelPayment",
                 "OrderStatusChanged" -> Topics.ORDER;
            case "InventoryResult",
                 "InventoryExpiring" -> Topics.INVENTORY;
            case "PaymentResult" -> Topics.PAYMENT;
            case "NotificationCreated" -> Topics.NOTIFICATION;
            default -> throw new IllegalArgumentException("Unknown event type " + type);
        };
    }
}
