/*
 * Consumes catalog changes into the derived search index; source versions make replay and out-of-
 * order delivery safe.
 */
package com.commerceflow.catalog.search;

import com.commerceflow.contracts.CheckoutEvents.ProductChanged;
import com.commerceflow.contracts.Topics;
import com.commerceflow.support.EventCodec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"commerceflow.messaging.enabled", "catalog.search.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class SearchConsumer {

    private final ProductSearch search;
    private final EventCodec codec;

    public SearchConsumer(ProductSearch search, EventCodec codec) {
        this.search = search;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.CATALOG, groupId = "catalog-search-v1")
    public void handle(ConsumerRecord<String, String> record) {
        // Version checks in the index write make repeated or delayed catalog events safe without a
        // shared service database.
        var event = codec.decode(record);
        if (!event.eventType().equals("ProductChanged"))
            throw new IllegalArgumentException("Unexpected catalog event");
        var product = codec.payload(event, ProductChanged.class);
        if (!product.id().equals(event.aggregateId()))
            throw new IllegalArgumentException("Product id differs from aggregate");
        search.index(product);
    }
}
