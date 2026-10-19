/*
 * Validates model-proposed calls against fixed schemas, role allowlists and timeouts before
 * dispatching to commerce APIs.
 */
package com.commerceflow.ai.tools;

import com.commerceflow.ai.domain.Actor;
import com.commerceflow.ai.retrieval.KnowledgeModels.Evidence;
import com.commerceflow.ai.retrieval.KnowledgeSearch;
import com.commerceflow.support.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.ollama.api.OllamaApi.ChatRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ToolRegistry implements AutoCloseable {

    public record ToolResult(JsonNode data, List<Evidence> evidence) {

    }

    private final com.commerceflow.ai.application.CartActions actions;
    private final CommerceClient commerce;
    private final KnowledgeSearch knowledge;
    private final JsonMapper json;
    private final MeterRegistry metrics;
    private final Map<String, JsonNode> schemas = new LinkedHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ToolRegistry(
            CommerceClient commerce,
            KnowledgeSearch knowledge,
            JsonMapper json,
            MeterRegistry metrics,
            com.commerceflow.ai.application.CartActions actions)
            throws java.io.IOException {
        this.actions = actions;
        this.commerce = commerce;
        this.knowledge = knowledge;
        this.json = json;
        this.metrics = metrics;
        try (var stream = new ClassPathResource("prompts/tools-v1.json").getInputStream()) {
            for (var entry : json.readTree(stream)) schemas.put(entry.path("name").asText(), entry);
        }
    }

    private Set<String> allowed(String mode) {
        return "SUPPORT".equals(mode)
                ? Set.of("searchProducts", "getProduct", "searchKnowledge", "getSupportOrderExplanation")
                : Set.of(
                "searchProducts",
                "getProduct",
                "searchKnowledge",
                "getMyOrderExplanation",
                "getMyCart",
                "proposeCartChange");
    }

    public List<ChatRequest.Tool> definitions(String mode) {
        return schemas.entrySet().stream()
                .filter(e -> allowed(mode).contains(e.getKey()))
                .map(
                        e ->
                                new ChatRequest.Tool(
                                        new ChatRequest.Tool.Function(
                                                e.getKey(),
                                                e.getValue().path("description").asText(),
                                                json.convertValue(e.getValue().path("parameters"), Map.class))))
                .toList();
    }

    public ToolResult invoke(UUID run, Actor actor, String mode, String name, JsonNode arguments) {
        return invoke(run, -1, actor, mode, name, arguments);
    }

    public ToolResult invoke(
            UUID run, long fence, Actor actor, String mode, String name, JsonNode arguments) {
        actor.requireMode(mode);
        if (!actor.valid())
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED, "AI_AUTHENTICATION_REQUIRED", "Fresh authentication required");
        // Recheck the role-specific allowlist at execution time; a model-advertised or invented tool
        // name grants no authority.
        if (!allowed(mode).contains(name))
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "AI_TOOL_NOT_ALLOWED",
                    "Tool is not available in this conversation mode");
        var canonical = validatedArguments(name, arguments);
        var sample = io.micrometer.core.instrument.Timer.start(metrics);
        var future =
                executor.submit(() -> dispatch(run, fence, actor, mode, name, canonical, "ai-" + run));
        try {
            var result = future.get(5, TimeUnit.SECONDS);
            if (json.writeValueAsString(result).length() > 8000)
                throw new BusinessException(
                        HttpStatus.BAD_GATEWAY, "AI_TOOL_RESULT_LIMIT", "Tool result exceeds context limits");
            return result;
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new BusinessException(
                    HttpStatus.GATEWAY_TIMEOUT, "AI_TOOL_TIMEOUT", "Tool execution exceeded its deadline");
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, "AI_INTERRUPTED", "Tool execution interrupted");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("Tool execution failed");
        } finally {
            sample.stop(metrics.timer("commerceflow.ai.tool", "name", name));
        }
    }

    JsonNode validatedArguments(String name, JsonNode arguments) {
        if (!schemas.containsKey(name) || arguments == null || !arguments.isObject()) throw invalid();
        var canonical = json.createObjectNode();
        for (var field : arguments.properties()) {
            var value = field.getValue();
            // Llama 3.1 may encode an absent optional string as JSON null or the literal string "null".
            // This compatibility rule applies only to this read filter, never identifiers, quantities or
            // actions.
            if (name.equals("searchProducts")
                    && field.getKey().equals("maxPrice")
                    && (value.isNull() || value.isTextual() && value.asText().equals("null"))) continue;
            canonical.set(field.getKey(), value);
        }
        validate(name, canonical);
        return canonical;
    }

    private void validate(String name, JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) throw invalid();
        var schema = schemas.get(name).path("parameters");
        var fields = schema.path("properties");
        for (var field : arguments.properties()) if (!fields.has(field.getKey())) throw invalid();
        for (var required : schema.path("required"))
            if (!arguments.hasNonNull(required.asText())) throw invalid();
        for (var property : fields.properties()) {
            var value = arguments.path(property.getKey());
            if (value.isMissingNode()) continue;
            var spec = property.getValue();
            if (spec.has("anyOf")) spec = spec.path("anyOf").get(0);
            if (spec.path("type").asText().equals("string")) {
                if (!value.isTextual() || value.asText().isBlank()) throw invalid();
                String text = value.asText();
                if (text.length() > spec.path("maxLength").asInt(200)) throw invalid();
                if (spec.has("pattern") && !text.matches(spec.path("pattern").asText())) throw invalid();
                if (spec.path("format").asText().equals("uuid"))
                    try {
                        UUID.fromString(text);
                    } catch (IllegalArgumentException e) {
                        throw invalid();
                    }
            } else if (spec.path("type").asText().equals("integer")) {
                // Reject out-of-range integers before conversion; a huge JSON integer must not wrap
                // into a permitted cart quantity.
                if (!value.isIntegralNumber()
                        || !value.canConvertToInt()
                        || value.asLong() < spec.path("minimum").asLong()
                        || value.asLong() > spec.path("maximum").asLong()) throw invalid();
            }
            if (spec.has("enum")) {
                boolean match = false;
                for (var item : spec.path("enum")) if (item.asText().equals(value.asText())) match = true;
                if (!match) throw invalid();
            }
        }
        if (arguments.has("maxPrice")) {
            var amount = new BigDecimal(arguments.path("maxPrice").asText());
            if (amount.compareTo(new BigDecimal("999999999.99")) > 0) throw invalid();
        }
    }

    public String correction(String name) {
        return name.equals("searchProducts")
                ? "Use a nonempty product or category query. maxPrice is an OPTIONAL decimal string such as"
                + " 150.00: OMIT it when no numeric price limit was requested. Never supply null, USD"
                + " or an empty query."
                : "Match the registered parameter schema exactly. Omit optional absent fields; required"
                + " UUIDs and quantities must come from the current user request or verified"
                + " evidence. Do not guess identifiers.";
    }

    private BusinessException invalid() {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                "AI_TOOL_ARGUMENTS_INVALID",
                "Tool arguments do not match the registered schema");
    }

    private ToolResult dispatch(
            UUID run,
            long fence,
            Actor actor,
            String mode,
            String name,
            JsonNode args,
            String correlation) {
        return switch (name) {
            case "proposeCartChange" -> {
                if (fence < 0)
                    throw new BusinessException(
                            HttpStatus.FORBIDDEN,
                            "AI_TOOL_CONTEXT_REQUIRED",
                            "A leased run is required to prepare a proposal");
                var proposal =
                        actions.prepare(
                                run,
                                fence,
                                actor,
                                args.path("operation").asText(),
                                UUID.fromString(args.path("productId").asText()),
                                args.path("quantity").asInt());
                yield new ToolResult(json.valueToTree(proposal), List.of());
            }
            case "searchKnowledge" -> {
                var result = knowledge.search(args.path("query").asText(), mode, 6);
                var evidence =
                        result.items().stream()
                                .map(
                                        e ->
                                                new Evidence(
                                                        e.id(),
                                                        e.sourceId(),
                                                        e.version(),
                                                        e.title(),
                                                        e.heading(),
                                                        e.chunkId(),
                                                        clip(e.content(), 700)))
                                .toList();
                yield new ToolResult(
                        json.valueToTree(
                                Map.of(
                                        "semantic",
                                        result.semantic(),
                                        "degraded",
                                        result.degraded(),
                                        "sourceCount",
                                        evidence.size())),
                        evidence);
            }
            case "searchProducts" -> {
                var parameters = new LinkedHashMap<String, Object>();
                parameters.put("q", args.path("query").asText());
                parameters.put("currency", "USD");
                parameters.put("size", 4);
                if (args.has("maxPrice")) parameters.put("maxPrice", args.path("maxPrice").asText());
                var page =
                        commerce.get("catalog", "/api/v1/products/search", parameters, actor, correlation);
                String matchedQuery = args.path("query").asText();
                boolean relaxed = false;
                if (page.path("items").isEmpty()) {
                    var words = matchedQuery.split("[^\\p{L}\\p{Nd}-]+");
                    var candidates = new LinkedHashSet<String>();
                    candidates.add(matchedQuery);
                    for (String word : words) {
                        if (word.length() >= 2
                                && !Set.of(
                                        "actual",
                                        "product",
                                        "products",
                                        "priced",
                                        "compare",
                                        "under",
                                        "below",
                                        "usd")
                                .contains(word.toLowerCase(Locale.ROOT))) {
                            candidates.add(word);
                            break;
                        }
                    }
                    int probes = 0;
                    for (String candidate : candidates) {
                        if (candidate.length() > 80) continue;
                        for (String category :
                                new LinkedHashSet<>(
                                        List.of(
                                                candidate,
                                                candidate.substring(0, 1).toUpperCase(Locale.ROOT)
                                                        + candidate.substring(1).toLowerCase(Locale.ROOT)))) {
                            if (++probes > 4) break;
                            var categoryParameters = new LinkedHashMap<>(parameters);
                            categoryParameters.remove("q");
                            categoryParameters.put("category", category);
                            var alternative =
                                    commerce.get(
                                            "catalog", "/api/v1/products/search", categoryParameters, actor, correlation);
                            if (!alternative.path("items").isEmpty()) {
                                page = alternative;
                                matchedQuery = "category:" + category;
                                relaxed = true;
                                break;
                            }
                        }
                        if (relaxed) break;
                    }
                    if (!relaxed) {
                        int attempts = 0;
                        for (String word : words) {
                            if (word.length() < 2
                                    || Set.of(
                                            "actual",
                                            "product",
                                            "products",
                                            "priced",
                                            "compare",
                                            "under",
                                            "below",
                                            "usd")
                                    .contains(word.toLowerCase(Locale.ROOT))
                                    || word.equals(matchedQuery)) continue;
                            if (++attempts > 3) break;
                            parameters.put("q", word);
                            var alternative =
                                    commerce.get(
                                            "catalog", "/api/v1/products/search", parameters, actor, correlation);
                            if (!alternative.path("items").isEmpty()) {
                                page = alternative;
                                matchedQuery = word;
                                relaxed = true;
                                break;
                            }
                        }
                    }
                }
                var items = new ArrayList<Object>();
                var evidence = new ArrayList<Evidence>();
                for (var item : page.path("items")) {
                    if (Thread.currentThread().isInterrupted())
                        throw new IllegalStateException("Interrupted");
                    var product =
                            commerce.get(
                                    "catalog",
                                    "/api/v1/products/" + UUID.fromString(item.path("id").asText()),
                                    Map.of(),
                                    actor,
                                    correlation);
                    // Search is a derived projection; revalidate current currency/price against Catalog
                    // before returning candidates.
                    if (!product.path("currency").asText().equals("USD")) continue;
                    if (args.has("maxPrice")
                            && new BigDecimal(product.path("price").asText())
                            .compareTo(new BigDecimal(args.path("maxPrice").asText()))
                            > 0) continue;
                    if (matchedQuery.startsWith("category:")
                            && !product.path("category").asText().equals(matchedQuery.substring(9))) continue;
                    var selected = new LinkedHashMap<String, Object>();
                    selected.put("matchScope", relaxed ? "PARTIAL" : "ORIGINAL_QUERY");
                    selected.putAll(product(product));
                    items.add(selected);
                    evidence.add(
                            liveEvidence(
                                    "product",
                                    product.path("id").asText(),
                                    product.path("version").asLong(),
                                    product.path("name").asText(),
                                    selected));
                }
                yield new ToolResult(
                        json.valueToTree(
                                Map.of(
                                        "items",
                                        items,
                                        "totalElements",
                                        page.path("totalElements").asLong(),
                                        "matchedQuery",
                                        matchedQuery,
                                        "relaxedQuery",
                                        relaxed,
                                        "observedAt",
                                        Instant.now().toString())),
                        evidence);
            }
            case "getProduct" -> {
                UUID id = UUID.fromString(args.path("productId").asText());
                var product =
                        commerce.get("catalog", "/api/v1/products/" + id, Map.of(), actor, correlation);
                var selected = product(product);
                yield new ToolResult(
                        json.valueToTree(selected),
                        List.of(
                                liveEvidence(
                                        "product",
                                        id.toString(),
                                        product.path("version").asLong(),
                                        product.path("name").asText(),
                                        selected)));
            }
            case "getMyOrderExplanation", "getSupportOrderExplanation" -> {
                UUID id = UUID.fromString(args.path("orderId").asText());
                String prefix =
                        name.equals("getSupportOrderExplanation") ? "/api/v1/admin/orders/" : "/api/v1/orders/";
                var response =
                        commerce.get("order", prefix + id + "/explanation", Map.of(), actor, correlation);
                // The copilot explains the current public state/cause. Keep the full event timeline in
                // Order's API; ISO timestamps are observations, not model-computed elapsed durations.
                var selected = json.createObjectNode();
                for (String field :
                        List.of("id", "status", "reason", "reasonKnown", "version", "observedAt")) {
                    selected.set(field, response.path(field));
                }
                yield new ToolResult(
                        selected,
                        List.of(
                                liveEvidence(
                                        "order",
                                        id.toString(),
                                        response.path("version").asLong(),
                                        "Current order state and recorded public cause",
                                        selected)));
            }
            case "getMyCart" -> {
                var cart = commerce.get("cart", "/api/v1/cart", Map.of(), actor, correlation);
                List<JsonNode> items = new ArrayList<>();
                for (var item : cart.path("items")) {
                    if (items.size() == 20) break;
                    items.add(item);
                }
                var result =
                        Map.of(
                                "items",
                                items,
                                "totalItems",
                                cart.path("items").size(),
                                "version",
                                cart.path("version").asLong(),
                                "revision",
                                cart.path("revision").asText(),
                                "observedAt",
                                Instant.now().toString());
                yield new ToolResult(
                        json.valueToTree(result),
                        List.of(
                                liveEvidence(
                                        "cart", "current", cart.path("version").asLong(), "Current cart", result)));
            }
            default -> throw new IllegalStateException("Tool is not dispatched");
        };
    }

    private Map<String, Object> product(JsonNode p) {
        return Map.of(
                "id",
                p.path("id").asText(),
                "name",
                p.path("name").asText(),
                "description",
                clip(p.path("description").asText(), 240),
                "price",
                p.path("price").asText(),
                "currency",
                p.path("currency").asText(),
                "category",
                p.path("category").asText(),
                "version",
                p.path("version").asLong(),
                "observedAt",
                Instant.now().toString());
    }

    private Evidence liveEvidence(
            String kind, String id, long version, String title, Object content) {
        String reference = kind + ":" + id + ":" + version + ":" + UUID.randomUUID();
        return new Evidence(
                reference,
                kind + "/" + id,
                version,
                title,
                "Observed live API response",
                id,
                clip(json.writeValueAsString(content), 1800));
    }

    private String clip(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
