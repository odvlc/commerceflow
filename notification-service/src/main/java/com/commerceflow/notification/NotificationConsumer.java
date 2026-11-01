/*
 * Deduplicates order status events before storing customer-visible notifications in the
 * notification database.
 */
package com.commerceflow.notification;

import com.commerceflow.contracts.CheckoutEvents.OrderStatus;
import com.commerceflow.contracts.Topics;
import com.commerceflow.support.EventCodec;
import com.commerceflow.support.ProcessedEvents;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "commerceflow.messaging.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NotificationConsumer {

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final NotificationService notifications;

    public NotificationConsumer(EventCodec codec, ProcessedEvents processed, NotificationService notifications) {
        this.codec = codec;
        this.processed = processed;
        this.notifications = notifications;
    }

    @KafkaListener(topics = Topics.ORDER, groupId = "notifications-v1")
    public void handle(ConsumerRecord<String, String> record) {
        var event = codec.decode(record);
        if (event.eventType().equals("OrderStatusChanged"))
            // Use the incoming event identity for transport replay, in addition to notification-level
            // status uniqueness.
            processed.once(event.eventId(), () -> notifications.record(
                    event.eventId(),
                    event.aggregateId(),
                    codec.payload(event, OrderStatus.class),
                    event.correlationId()));
    }
}
