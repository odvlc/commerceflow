package com.commerceflow.payment;

import com.commerceflow.contracts.CheckoutEvents.AuthorizePayment;
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
public class PaymentConsumer {

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final PaymentService payments;

    public PaymentConsumer(EventCodec codec, ProcessedEvents processed, PaymentService payments) {
        this.codec = codec;
        this.processed = processed;
        this.payments = payments;
    }

    @KafkaListener(topics = Topics.ORDER, groupId = "payment-v1")
    public void handle(ConsumerRecord<String, String> record) {
        var event = codec.decode(record);
        try (var ignored = MDC.putCloseable("correlationId", event.correlationId())) {
            switch (event.eventType()) {
                case "AuthorizePayment" -> processed.once(
                        event.eventId(),
                        () ->
                                payments.authorize(
                                        event.aggregateId(),
                                        codec.payload(event, AuthorizePayment.class),
                                        event.correlationId()));
                case "CancelPayment" -> processed.once(
                        event.eventId(), () -> payments.cancel(event.aggregateId(), event.correlationId()));
                default -> {
                    /* Inventory commands and order statuses have other consumers. */
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${payment.settle-delay:1000}")
    public void settleDue() {
        payments.settleDue();
    }
}
