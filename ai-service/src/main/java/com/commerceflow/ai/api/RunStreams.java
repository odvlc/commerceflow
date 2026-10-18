package com.commerceflow.ai.api;

import com.commerceflow.ai.domain.Actor;
import com.commerceflow.ai.persistence.AiStore;
import com.commerceflow.support.BusinessException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Persisted event replay only. Opening a stream never creates or resumes a run. */
@Component
public class RunStreams implements AutoCloseable {
  private final AiStore store;
  private final Semaphore total = new Semaphore(32);
  private final ConcurrentMap<UUID, Integer> owners = new ConcurrentHashMap<>();
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

  public RunStreams(AiStore store) {
    this.store = store;
  }

  public SseEmitter open(UUID run, Actor actor, long after) {
    store.run(run, actor);
    if (!actor.valid())
      throw new BusinessException(
          HttpStatus.UNAUTHORIZED, "AI_AUTHENTICATION_REQUIRED", "Fresh authentication required");
    if (!total.tryAcquire()) throw capacity();
    try {
      owners.compute(
          actor.subject(),
          (id, count) -> {
            if (count != null && count >= 2) throw capacity();
            return count == null ? 1 : count + 1;
          });
    } catch (RuntimeException error) {
      total.release();
      throw error;
    }
    long lifetime =
        Math.min(
            120000, Duration.between(Instant.now(), actor.expiresAt().minusSeconds(5)).toMillis());
    var emitter = new SseEmitter(Math.max(1, lifetime));
    var released = new AtomicBoolean();
    Runnable release =
        () -> {
          if (released.compareAndSet(false, true)) {
            owners.computeIfPresent(actor.subject(), (id, count) -> count <= 1 ? null : count - 1);
            total.release();
          }
        };
    emitter.onCompletion(release);
    emitter.onTimeout(
        () -> {
          release.run();
          emitter.complete();
        });
    emitter.onError(e -> release.run());
    workers.submit(
        () -> {
          long cursor = after;
          long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lifetime);
          long heartbeat = System.nanoTime();
          try {
            while (!released.get() && actor.valid() && System.nanoTime() < deadline) {
              var events = store.events(run, actor, cursor);
              for (var event : events) {
                if (!actor.valid()) break;
                emitter.send(
                    SseEmitter.event()
                        .id(Long.toString(event.sequence()))
                        .name(event.type())
                        .data(event.payload()));
                cursor = event.sequence();
              }
              var state = store.run(run, actor).state();
              if (events.size() < 100
                  && Set.of(
                          "SUCCEEDED",
                          "FAILED",
                          "CANCELLED",
                          "AUTHENTICATION_REQUIRED",
                          "WAITING_CONFIRMATION")
                      .contains(state)) break;
              if (System.nanoTime() - heartbeat > TimeUnit.SECONDS.toNanos(15)) {
                emitter.send(SseEmitter.event().comment("heartbeat"));
                heartbeat = System.nanoTime();
              }
              Thread.sleep(500);
            }
            emitter.complete();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emitter.complete();
          } catch (Exception disconnected) {
            emitter.completeWithError(disconnected);
          } finally {
            release.run();
          }
        });
    return emitter;
  }

  private BusinessException capacity() {
    return new BusinessException(
        HttpStatus.TOO_MANY_REQUESTS, "AI_STREAM_LIMIT", "Too many active copilot streams");
  }

  @Override
  public void close() {
    workers.shutdownNow();
  }
}
