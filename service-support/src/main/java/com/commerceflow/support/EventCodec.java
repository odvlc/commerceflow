package com.commerceflow.support;

import com.commerceflow.contracts.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.databind.json.JsonMapper;

public class EventCodec {

    private final JsonMapper json;

    public EventCodec(JsonMapper json) {
        this.json = json;
    }

    public EventEnvelope<?> decode(ConsumerRecord<String, String> record) {
        var event = json.readValue(record.value(), EventEnvelope.class);
        if (!event.aggregateId().toString().equals(record.key()))
            throw new IllegalArgumentException("Event key must equal aggregateId");
        return event;
    }

    public <T> T payload(EventEnvelope<?> event, Class<T> type) {
        return json.convertValue(event.payload(), type);
    }
}
