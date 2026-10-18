package com.commerceflow.catalog.application;

import com.commerceflow.contracts.CheckoutEvents.ProductChanged;
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
    var event = codec.decode(record);
    if (!event.eventType().equals("ProductChanged"))
      throw new IllegalArgumentException("Unexpected catalog event");
    var product = codec.payload(event, ProductChanged.class);
    if (!cache.invalidate(product.id(), product.version()))
      throw new IllegalStateException("Cache invalidation unavailable");
  }
}
