/*
 * Attaches caller credentials only in process memory; durable recovery requires fresh
 * authentication after that attachment is lost.
 */
package com.commerceflow.ai.application;

import com.commerceflow.ai.domain.Actor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class Credentials {

    private final Map<UUID, Actor> values = new ConcurrentHashMap<>();

    public void attach(UUID run, Actor actor) {
        if (actor.valid()) values.put(run, actor);
    }

    public Optional<Actor> get(UUID run) {
        var actor = values.get(run);
        if (actor == null || !actor.valid()) {
            // Remove expired credentials on read as well as on the sweeper so a queued worker never
            // reuses an expired attachment.
            values.remove(run);
            return Optional.empty();
        }
        return Optional.of(actor);
    }

    public void remove(UUID run) {
        values.remove(run);
    }

    @Scheduled(fixedDelay = 30000)
    void expire() {
        // Evict unused expired attachments too; do not persist tokens merely to make restart recovery
        // automatic.
        values.entrySet().removeIf(e -> !e.getValue().valid());
    }
}
