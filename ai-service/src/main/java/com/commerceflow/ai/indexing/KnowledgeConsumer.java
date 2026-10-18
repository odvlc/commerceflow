package com.commerceflow.ai.indexing;

import com.commerceflow.ai.retrieval.KnowledgeStore;
import com.commerceflow.contracts.KnowledgeEvents.KnowledgeChanged;
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
public class KnowledgeConsumer {

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final KnowledgeStore store;

    public KnowledgeConsumer(EventCodec codec, ProcessedEvents processed, KnowledgeStore store) {
        this.codec = codec;
        this.processed = processed;
        this.store = store;
    }

    @KafkaListener(topics = Topics.AI_KNOWLEDGE, groupId = "commerceflow-ai-knowledge-v1")
    public void receive(ConsumerRecord<String, String> record) {
        var event = codec.decode(record);
        if (!event.eventType().equals("KnowledgeChanged"))
            throw new IllegalArgumentException("Unexpected knowledge event");
        var payload = codec.payload(event, KnowledgeChanged.class);
        if (!payload.documentId().equals(event.aggregateId()))
            throw new IllegalArgumentException("Document mismatch");
        processed.once(event.eventId(), () -> store.delivered(payload.documentId(), payload.version()));
    }
}
