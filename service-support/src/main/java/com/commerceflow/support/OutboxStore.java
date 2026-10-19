/*
 * Appends outgoing events within the caller transaction; each importing service writes only its
 * own outbox table.
 */
package com.commerceflow.support;

import com.commerceflow.contracts.EventEnvelope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.util.UUID;

public class OutboxStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String type, UUID aggregate, String correlation, Object payload) {
        // Persist the envelope identity now so a later publisher retry sends the same event ID.
        var event = EventEnvelope.create(type, aggregate, correlation, payload);
        jdbc.sql(
                        """
                                INSERT INTO outbox_events(id,aggregate_id,event_type,payload,created_at)
                                VALUES (:id,:aggregate,:type,CAST(:payload AS jsonb),:created)
                                """)
                .param("id", event.eventId())
                .param("aggregate", aggregate)
                .param("type", type)
                .param("payload", json.writeValueAsString(event))
                .param("created", Timestamp.from(event.occurredAt()))
                .update();
        return event.eventId();
    }
}
