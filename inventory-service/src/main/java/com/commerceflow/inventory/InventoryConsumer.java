/*
 * Deduplicates inventory commands transactionally and signals expiry to the saga rather than
 * independently releasing uncertain stock.
 */
package com.commerceflow.inventory;

import com.commerceflow.contracts.CheckoutEvents.ReserveInventory;
import com.commerceflow.contracts.EventEnvelope;
import com.commerceflow.contracts.Topics;
import com.commerceflow.support.EventCodec;
import com.commerceflow.support.ProcessedEvents;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "commerceflow.messaging.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class InventoryConsumer {

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final InventoryService inventoryService;

    public InventoryConsumer(EventCodec codec, ProcessedEvents processed, InventoryService inventoryService) {
        this.codec = codec;
        this.processed = processed;
        this.inventoryService = inventoryService;
    }

    @KafkaListener(topics = Topics.ORDER, groupId = "inventory-v1")
    public void handle(ConsumerRecord<String, String> record) {
        EventEnvelope<?> event = codec.decode(record);
        try (var ignored = MDC.putCloseable("correlationId", event.correlationId())) {
            switch (event.eventType()) {
                // The dedup marker and reservation effect commit together; listener acknowledgment
                // happens after the handler returns.
                case "ReserveInventory" -> processed.once(
                        event.eventId(), () ->
                                inventoryService.reserve(
                                        event.aggregateId(),
                                        codec.payload(event, ReserveInventory.class),
                                        event.correlationId()));
                case "CommitInventory" -> processed.once(
                        event.eventId(), () -> inventoryService.commit(event.aggregateId(), event.correlationId()));
                case "ReleaseInventory" -> processed.once(
                        event.eventId(), () -> inventoryService.release(event.aggregateId(), event.correlationId()));
                default -> {
                    /* Other commands on the order topic belong to other services. */
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${inventory.expiry-check-delay:10000}")
    public void reconcileExpiry() {
        inventoryService.signalExpired();
    }
}
