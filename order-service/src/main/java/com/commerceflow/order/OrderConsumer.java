package com.commerceflow.order;

import com.commerceflow.contracts.CheckoutEvents.*;
import com.commerceflow.contracts.Topics;
import com.commerceflow.support.*;
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
public class OrderConsumer {

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final SagaCoordinator saga;

    public OrderConsumer(EventCodec codec, ProcessedEvents processed, SagaCoordinator saga) {
        this.codec = codec;
        this.processed = processed;
        this.saga = saga;
    }

    @KafkaListener(
            topics = {Topics.INVENTORY, Topics.PAYMENT},
            groupId = "order-saga-v1")
    public void handle(ConsumerRecord<String, String> record) {
        var event = codec.decode(record);
        try (var ignored = MDC.putCloseable("correlationId", event.correlationId())) {
            switch (event.eventType()) {
                case "InventoryResult" -> processed.once(
                        event.eventId(),
                        () ->
                                saga.inventory(
                                        event.aggregateId(), codec.payload(event, InventoryResult.class)));
                case "InventoryExpiring" -> processed.once(event.eventId(), () -> saga.expiring(event.aggregateId()));
                case "PaymentResult" -> processed.once(
                        event.eventId(),
                        () -> saga.payment(event.aggregateId(), codec.payload(event, PaymentResult.class)));
                default -> throw new IllegalArgumentException("Unexpected saga event " + event.eventType());
            }
        }
    }

    @Scheduled(fixedDelayString = "${saga.reconcile-delay:5000}")
    public void reconcile() {
        saga.reconcileDeadlines();
    }
}
