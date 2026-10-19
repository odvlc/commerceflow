/*
 * Invokes the transactional outbox publisher through a separate Spring bean so scheduled calls
 * retain transaction interception.
 */
package com.commerceflow.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

@ConditionalOnProperty(
        name = "commerceflow.outbox.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxSchedule {

    private final OutboxPublisher publisher;

    public OutboxSchedule(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${commerceflow.outbox.poll-delay:1000}")
    public void publish() {
        // Call a separate proxied bean so Spring opens the publisher transaction on each scheduled
        // batch.
        publisher.publishBatch();
    }
}
