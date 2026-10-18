package com.commerceflow.ai.indexing;

import com.commerceflow.ai.retrieval.*;
import com.commerceflow.ai.retrieval.KnowledgeModels.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ai.indexing-enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexer {
  private final DataSource data;
  private final KnowledgeStore store;
  private final KnowledgeIndex index;
  private final MeterRegistry metrics;

  public KnowledgeIndexer(
      DataSource data, KnowledgeStore store, KnowledgeIndex index, MeterRegistry metrics) {
    this.data = data;
    this.store = store;
    this.index = index;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelayString = "${ai.index-delay:1000}")
  public void poll() {
    // A session lock serializes rebuilds and live updates across external I/O without a long SQL
    // transaction. Keep this connection until unlock; process loss releases the lock for recovery.
    try (var connection = data.getConnection()) {
      boolean acquired;
      try (var statement = connection.createStatement();
          var result =
              statement.executeQuery(
                  "SELECT"
                      + " pg_try_advisory_lock(hashtextextended('commerceflow-ai-knowledge-index',0))")) {
        result.next();
        acquired = result.getBoolean(1);
      }
      if (!acquired) return;
      try {
        var rebuild = store.pendingRebuild();
        if (rebuild.isPresent()) {
          rebuild(rebuild.get());
          return;
        }
        for (var job : store.pending())
          try {
            var doc = store.required(job.documentId());
            if (doc.version() == job.version()) {
              index.ensure();
              index.write(
                  KnowledgeIndex.WRITE,
                  doc,
                  store.chunks(doc.id(), doc.version()),
                  store.maxOrdinal(doc.id()));
              if (index.semanticEnabled() && doc.active())
                store.embeddingRecorded(
                    doc.id(), doc.version(), index.embeddingModel(), index.embeddingDimensions());
            }
            // Checkpoint after Elasticsearch acknowledges; a crash in between replays the write.
            store.completed(job.id());
            metrics.counter("commerceflow.ai.knowledge.indexed").increment();
          } catch (RuntimeException error) {
            store.retry(job, error);
            metrics.counter("commerceflow.ai.knowledge.failures").increment();
          }
      } finally {
        try (var statement = connection.createStatement()) {
          statement.execute(
              "SELECT pg_advisory_unlock(hashtextextended('commerceflow-ai-knowledge-index',0))");
        }
      }
    } catch (java.sql.SQLException error) {
      metrics.counter("commerceflow.ai.knowledge.failures").increment();
    }
  }

  private void rebuild(JobView job) {
    long count = 0;
    try {
      store.rebuildState(job.id(), "RUNNING", 0, null);
      index.create(job.indexName());
      UUID after = null;
      while (true) {
        var documents = store.page(after, 100);
        for (var doc : documents) {
          index.write(
              job.indexName(),
              doc,
              store.chunks(doc.id(), doc.version()),
              store.maxOrdinal(doc.id()));
          if (index.semanticEnabled() && doc.active())
            store.embeddingRecorded(
                doc.id(), doc.version(), index.embeddingModel(), index.embeddingDimensions());
          count++;
          after = doc.id();
        }
        if (documents.size() < 100) break;
      }
      index.swap(job.indexName());
      store.rebuildState(job.id(), "SUCCEEDED", count, null);
    } catch (RuntimeException error) {
      store.rebuildState(job.id(), "FAILED", count, error.getClass().getSimpleName());
      metrics.counter("commerceflow.ai.knowledge.failures").increment();
    }
  }
}
