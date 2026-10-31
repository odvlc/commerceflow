/*
 * Combines lexical/vector rankings and rechecks source permission/version in PostgreSQL before
 * returning evidence.
 */
package com.commerceflow.ai.retrieval;

import com.commerceflow.ai.retrieval.KnowledgeModels.Evidence;
import com.commerceflow.ai.retrieval.KnowledgeModels.SearchResult;
import com.commerceflow.support.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
public class KnowledgeSearch {

    private final KnowledgeIndex index;
    private final KnowledgeStore store;
    private final MeterRegistry metrics;

    public KnowledgeSearch(KnowledgeIndex index, KnowledgeStore store, MeterRegistry metrics) {
        this.index = index;
        this.store = store;
        this.metrics = metrics;
    }

    public SearchResult search(String text, String mode, int size) {
        if (text == null
                || text.length() > 200
                || text.matches(
                "(?s).*[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}.*")
                || text.matches("(?is).*(bearer\\s+|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}).*"))
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "AI_KNOWLEDGE_QUERY_INVALID",
                    "Use a general policy query without identifiers or credentials");
        var sample = Timer.start(metrics);
        try {
            var query = Map.of("multi_match",
                    Map.of("query", text, "fields",
                            List.of("title^3", "heading^2", "content")));
            var lexical = index.query(query, mode, 20);
            var hits = lexical;
            boolean semantic = false, degraded = false;
            // Only embedding failure degrades to lexical hits; an unavailable lexical/search backend
            // remains an explicit retrieval failure.
            if (index.semanticEnabled())
                try {
                    var vectors = index.vector(text, mode, 20);
                    hits = fuse(lexical, vectors);
                    semantic = true;
                } catch (RuntimeException e) {
                    degraded = true;
                    metrics.counter("commerceflow.ai.retrieval.embedding_fallback").increment();
                }
            List<Evidence> result = new ArrayList<>();
            for (var hit : hits) {
                var value = hit.path("_source");
                String source = value.path("sourceId").asText();
                long version = value.path("sourceVersion").asLong();
                // Elasticsearch can lag revocation/version changes; PostgreSQL makes the final access check.
                if (!store.permitted(source, version, mode))
                    continue;
                String chunk = value.path("chunkId").asText();
                result.add(new Evidence(
                        "k:" + chunk + ":" + version,
                        source,
                        version,
                        value.path("title").asText(),
                        value.path("heading").asText(),
                        chunk,
                        value.path("content").asText()));
                if (result.size() >= size)
                    break;
            }
            return new SearchResult(List.copyOf(result), semantic, degraded);
        } catch (RuntimeException error) {
            metrics.counter("commerceflow.ai.retrieval.failures").increment();
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_RETRIEVAL_UNAVAILABLE",
                    "Knowledge retrieval is temporarily unavailable");
        } finally {
            sample.stop(metrics.timer("commerceflow.ai.retrieval"));
        }
    }

    private List<JsonNode> fuse(List<JsonNode> first, List<JsonNode> second) {
        // Reciprocal rank fusion combines rankings because BM25 and vector scores use different scales.
        // The constant 61 is the usual offset 60 plus a one-based rank.
        var values = new HashMap<String, JsonNode>();
        var scores = new HashMap<String, Double>();
        for (var list : List.of(first, second))
            for (int rank = 0; rank < list.size(); rank++) {
                var hit = list.get(rank);
                String id = hit.path("_id").asText();
                values.put(id, hit);
                scores.merge(id, 1.0 / (61 + rank), Double::sum);
            }
        return scores.keySet().stream()
                .sorted(Comparator.<String>comparingDouble(scores::get)
                        .reversed()
                        .thenComparing(Function.identity()))
                .map(values::get)
                .toList();
    }
}
