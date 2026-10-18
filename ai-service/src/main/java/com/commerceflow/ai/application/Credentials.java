package com.commerceflow.ai.application;

import com.commerceflow.ai.domain.Actor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Credentials {
  private final Map<UUID, Actor> values = new ConcurrentHashMap<>();

  public void attach(UUID run, Actor actor) {
    if (actor.valid()) values.put(run, actor);
  }

  public Optional<Actor> get(UUID run) {
    var actor = values.get(run);
    if (actor == null || !actor.valid()) {
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
    values.entrySet().removeIf(e -> !e.getValue().valid());
  }
}
