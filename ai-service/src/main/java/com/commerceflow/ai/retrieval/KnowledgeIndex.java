package com.commerceflow.ai.retrieval;

import com.commerceflow.ai.retrieval.KnowledgeModels.Chunk;
import com.commerceflow.ai.retrieval.KnowledgeModels.Document;
import com.commerceflow.support.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class KnowledgeIndex {

    public static final String READ = "cf-ai-knowledge-read", WRITE = "cf-ai-knowledge-write";
    private final RestClient http;
    private final JsonMapper json;
    private final com.commerceflow.ai.model.LocalModels models;
    private final boolean embeddings;

    public KnowledgeIndex(String url, JsonMapper json) {
        this(url, json, null, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeIndex(
            @Value("${ai.search-url:http://127.0.0.1:9200}") String url,
            JsonMapper json,
            com.commerceflow.ai.model.LocalModels models,
            @Value("${ai.embeddings-enabled:false}") boolean embeddings) {
        this.json = json;
        this.models = models;
        this.embeddings = embeddings;
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    public void ensure() {
        try {
            http.head().uri("/_alias/" + READ).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) throw e;
            String index = "cf-ai-knowledge-v1-" + UUID.randomUUID();
            create(index);
            swap(index);
        }
        if (embeddings) verifyEmbeddingMapping();
    }

    public void create(String index) {
        var props = new LinkedHashMap<String, Object>();
        for (String field : List.of("sourceId", "audience", "chunkId"))
            props.put(field, Map.of("type", "keyword"));
        for (String field : List.of("title", "heading", "content"))
            props.put(field, Map.of("type", "text"));
        props.put("active", Map.of("type", "boolean"));
        props.put("sourceVersion", Map.of("type", "long"));
        props.put("effectiveAt", Map.of("type", "date"));
        props.put(
                "embedding",
                Map.of(
                        "type",
                        "dense_vector",
                        "dims",
                        models == null ? 768 : models.dimensions(),
                        "index",
                        true,
                        "similarity",
                        "cosine"));
        var body =
                Map.of(
                        "settings",
                        Map.of("number_of_shards", 1, "number_of_replicas", 0),
                        "mappings",
                        Map.of(
                                "dynamic",
                                "strict",
                                "properties",
                                props,
                                "_meta",
                                Map.of("embeddingDigest", models == null ? "lexical" : models.embeddingDigest())));
        try {
            http.put()
                    .uri("/" + index)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 400
                    || !e.getResponseBodyAsString().contains("resource_already_exists_exception")) throw e;
        }
    }

    public void write(String target, Document doc, List<Chunk> chunks, int maximumOrdinal) {
        var byOrdinal = new HashMap<Integer, Chunk>();
        chunks.forEach(c -> byOrdinal.put(c.ordinal(), c));
        for (int ordinal = 0; ordinal <= maximumOrdinal; ordinal++) {
            Chunk chunk = byOrdinal.get(ordinal);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sourceId", doc.sourceId());
            body.put("sourceVersion", doc.version());
            body.put("audience", doc.audience());
            body.put("title", doc.title());
            body.put("effectiveAt", doc.effectiveAt().toString());
            body.put("active", doc.active() && chunk != null);
            body.put("chunkId", chunk == null ? "tombstone" : chunk.id().toString());
            body.put("heading", chunk == null ? "" : chunk.heading());
            body.put("content", chunk == null ? "" : chunk.content());
            if (embeddings && doc.active() && chunk != null)
                body.put("embedding", models.embed("search_document: " + chunk.content()));
            put(target, doc.id() + "-" + ordinal, doc.version(), body);
        }
        http.post().uri("/" + target + "/_refresh").retrieve().toBodilessEntity();
    }

    protected void put(String index, String id, long version, Map<String, Object> body) {
        try {
            http.put()
                    .uri("/" + index + "/_doc/" + id + "?version=" + version + "&version_type=external_gte")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 409) throw e;
        }
    }

    public void swap(String index) {
        var actions = new ArrayList<Map<String, Object>>();
        for (String alias : List.of(READ, WRITE))
            actions.add(Map.of("remove", Map.of("index", "*", "alias", alias, "must_exist", false)));
        actions.add(Map.of("add", Map.of("index", index, "alias", READ)));
        actions.add(Map.of("add", Map.of("index", index, "alias", WRITE, "is_write_index", true)));
        http.post()
                .uri("/_aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("actions", actions))
                .retrieve()
                .toBodilessEntity();
    }

    public List<JsonNode> query(Object query, String mode, int size) {
        var filters = filters(mode);
        var body =
                Map.of(
                        "size",
                        size,
                        "_source",
                        Map.of("excludes", List.of("embedding")),
                        "query",
                        Map.of("bool", Map.of("must", List.of(query), "filter", filters)));
        return hits(body);
    }

    private List<Object> filters(String mode) {
        return List.of(
                Map.of("term", Map.of("active", true)),
                Map.of(
                        "terms",
                        Map.of(
                                "audience",
                                "SUPPORT".equals(mode) ? List.of("PUBLIC", "SUPPORT") : List.of("PUBLIC"))),
                Map.of("range", Map.of("effectiveAt", Map.of("lte", "now"))));
    }

    public List<JsonNode> hits(Map<String, ?> body) {
        String response =
                http.post()
                        .uri("/" + READ + "/_search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
        var result = new ArrayList<JsonNode>();
        for (var hit : json.readTree(response).path("hits").path("hits")) result.add(hit);
        return result;
    }

    public boolean semanticEnabled() {
        return embeddings;
    }

    public List<JsonNode> vector(String query, String mode, int size) {
        if (!embeddings) return List.of();
        verifyEmbeddingMapping();
        float[] vector = models.embed("search_query: " + query);
        var filter = new ArrayList<>(filters(mode));
        filter.add(Map.of("exists", Map.of("field", "embedding")));
        var score =
                Map.of(
                        "script_score",
                        Map.of(
                                "query",
                                Map.of("bool", Map.of("filter", filter)),
                                "script",
                                Map.of(
                                        "source",
                                        "cosineSimilarity(params.queryVector, 'embedding') + 1.0",
                                        "params",
                                        Map.of("queryVector", vector))));
        return hits(
                Map.of("size", size, "_source", Map.of("excludes", List.of("embedding")), "query", score));
    }

    public String embeddingModel() {
        return models == null ? null : models.embeddingName();
    }

    public int embeddingDimensions() {
        return models == null ? 768 : models.dimensions();
    }

    private void verifyEmbeddingMapping() {
        var mappings =
                json.readTree(http.get().uri("/" + READ + "/_mapping").retrieve().body(String.class));
        for (var entry : mappings.properties()) {
            var mapping = entry.getValue().path("mappings");
            if (!models.embeddingDigest().equals(mapping.path("_meta").path("embeddingDigest").asText())
                    || mapping.path("properties").path("embedding").path("dims").asInt()
                    != models.dimensions())
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "AI_KNOWLEDGE_REBUILD_REQUIRED",
                        "Embedding configuration changed; rebuild into a new index generation");
        }
    }
}
