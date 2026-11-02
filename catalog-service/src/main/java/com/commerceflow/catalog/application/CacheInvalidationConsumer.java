/*
 * Applies source-version cache invalidations so delayed catalog events cannot make an older value
 * authoritative.
 */
package com.commerceflow.catalog.application;

import com.commerceflow.contracts.CheckoutEvents.ProductChanged;
import com.commerceflow.contracts.EventEnvelope;
import com.commerceflow.contracts.Topics;
import com.commerceflow.support.EventCodec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"commerceflow.messaging.enabled", "catalog.cache.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class CacheInvalidationConsumer {

    private final ProductCache cache;
    private final EventCodec codec;

    public CacheInvalidationConsumer(ProductCache cache, EventCodec codec) {
        this.cache = cache;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.CATALOG, groupId = "catalog-cache-v1")
    public void handle(ConsumerRecord<String, String> record) {
        // Decode the wire envelope before applying its source-version invalidation; malformed delivery
        // follows Kafka retry policy.
        EventEnvelope<?> event = codec.decode(record);
        if (!event.eventType().equals("ProductChanged"))
            throw new IllegalArgumentException("Unexpected catalog event");
        ProductChanged product = codec.payload(event, ProductChanged.class);
        if (!cache.invalidate(product.id(), product.version()))
            throw new IllegalStateException("Cache invalidation unavailable");
    }
}
