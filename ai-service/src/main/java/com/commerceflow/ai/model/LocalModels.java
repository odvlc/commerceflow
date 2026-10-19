/*
 * Uses the explicitly prepared local chat/embedding model digests and bounds shared inference
 * concurrency.
 */
package com.commerceflow.ai.model;

import com.commerceflow.support.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaApi.ChatRequest;
import org.springframework.ai.ollama.api.OllamaApi.ChatResponse;
import org.springframework.ai.ollama.api.OllamaApi.EmbeddingsRequest;
import org.springframework.ai.ollama.api.OllamaApi.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Component
public class LocalModels {

    private final OllamaApi api;
    private final RestClient metadata;
    private final JsonMapper json;
    private final MeterRegistry metrics;
    private final String chat, embedding, chatDigest, embeddingDigest;
    private final int dimensions;
    private volatile Instant checked = Instant.EPOCH;
    private volatile boolean available;
    // Embedding and chat share one local runtime; serialize both to bound inference memory pressure.
    private final Semaphore slots = new Semaphore(1, true);

    public LocalModels(
            @Value("${ai.model-url:http://127.0.0.1:11434}") String url,
            @Value("${ai.chat-model:llama3.1:8b}") String chat,
            @Value("${ai.embedding-model:nomic-embed-text:latest}") String embedding,
            @Value("${ai.chat-digest:46e0c10c039e019119339687c3c1757cc81b9da49709a3b3924863ba87ca666e}")
            String chatDigest,
            @Value(
                    "${ai.embedding-digest:0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f}")
            String embeddingDigest,
            @Value("${ai.embedding-dimensions:768}") int dimensions,
            JsonMapper json,
            MeterRegistry metrics) {
        this.chat = chat;
        this.embedding = embedding;
        this.chatDigest = chatDigest;
        this.embeddingDigest = embeddingDigest;
        this.dimensions = dimensions;
        this.json = json;
        this.metrics = metrics;
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(45));
        api =
                OllamaApi.builder()
                        .baseUrl(url)
                        .restClientBuilder(RestClient.builder().requestFactory(factory))
                        .webClientBuilder(
                                org.springframework.web.reactive.function.client.WebClient.builder()
                                        .clientConnector(
                                                new org.springframework.http.client.reactive.JdkClientHttpConnector(
                                                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build())))
                        .build();
        var shortFactory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        shortFactory.setReadTimeout(Duration.ofSeconds(3));
        metadata = RestClient.builder().baseUrl(url).requestFactory(shortFactory).build();
        metrics.gauge("commerceflow.ai.model.active", slots, s -> 1 - s.availablePermits());
    }

    public String chatName() {
        return chat;
    }

    public String chatDigest() {
        return chatDigest;
    }

    public String embeddingName() {
        return embedding;
    }

    public String embeddingDigest() {
        return embeddingDigest;
    }

    public int dimensions() {
        return dimensions;
    }

    public synchronized boolean ready() {
        if (checked.plusSeconds(5).isAfter(Instant.now())) return available;
        try {
            var models =
                    json.readTree(metadata.get().uri("/api/tags").retrieve().body(String.class))
                            .path("models");
            boolean chatFound = false, embeddingFound = false;
            for (var m : models) {
                if (chat.equals(m.path("name").asText()) && chatDigest.equals(m.path("digest").asText()))
                    chatFound = true;
                if (embedding.equals(m.path("name").asText())
                        && embeddingDigest.equals(m.path("digest").asText())) embeddingFound = true;
            }
            // Both pinned digests are required; a tag with different weights is not silently accepted
            // as the same model.
            available = chatFound && embeddingFound;
        } catch (RuntimeException e) {
            available = false;
        }
        checked = Instant.now();
        return available;
    }

    public void requireReady() {
        if (!ready())
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_MODEL_UNAVAILABLE",
                    "Required local model digests are unavailable; run model preparation");
    }

    public float[] embed(String text) {
        requireReady();
        acquire();
        var sample = Timer.start(metrics);
        try {
            var result =
                    api.embed(new EmbeddingsRequest(embedding, List.of(text), "5m", Map.of(), false, null));
            if (result.embeddings() == null
                    || result.embeddings().size() != 1
                    || result.embeddings().getFirst().length != dimensions)
                throw new IllegalStateException(
                        "Embedding dimension requires a new configured index generation");
            float[] vector = result.embeddings().getFirst();
            for (float value : vector)
                if (!Float.isFinite(value)) throw new IllegalStateException("Invalid embedding vector");
            if (result.promptEvalCount() != null)
                metrics
                        .counter("commerceflow.ai.tokens", "operation", "embedding")
                        .increment(result.promptEvalCount());
            return vector;
        } catch (RuntimeException error) {
            metrics.counter("commerceflow.ai.model.failures", "operation", "embedding").increment();
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_EMBEDDING_UNAVAILABLE",
                    "Local embedding inference unavailable");
        } finally {
            slots.release();
            sample.stop(metrics.timer("commerceflow.ai.model", "operation", "embedding"));
        }
    }

    public ChatResponse chat(List<Message> messages, List<ChatRequest.Tool> tools, Object format) {
        int characters =
                messages.stream().mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum();
        if (characters > 12000)
            throw new BusinessException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "AI_CONTEXT_LIMIT",
                    "Conversation context exceeds the bounded model window");
        requireReady();
        acquire();
        var sample = Timer.start(metrics);
        try {
            long dispatched = System.nanoTime();
            var accumulator =
                    new ChatAccumulator(
                            kind ->
                                    metrics
                                            .timer(
                                                    kind.equals("text")
                                                            ? "commerceflow.ai.model.first.token"
                                                            : "commerceflow.ai.model.first.tool.request")
                                            .record(Duration.ofNanos(System.nanoTime() - dispatched)));
            api.streamingChat(
                            new ChatRequest(
                                    chat,
                                    List.copyOf(messages),
                                    true,
                                    format,
                                    "5m",
                                    tools,
                                    Map.of("temperature", 0, "num_ctx", 4096, "num_predict", 768),
                                    null))
                    .doOnNext(accumulator::accept)
                    .then()
                    .block(Duration.ofSeconds(45));
            var response = accumulator.finish();
            if (response.message() == null) throw new IllegalStateException("Missing model message");
            if (response.promptEvalCount() != null)
                metrics
                        .counter("commerceflow.ai.tokens", "operation", "prompt")
                        .increment(response.promptEvalCount());
            if (response.evalCount() != null)
                metrics
                        .counter("commerceflow.ai.tokens", "operation", "generation")
                        .increment(response.evalCount());
            return response;
        } catch (RuntimeException error) {
            metrics.counter("commerceflow.ai.model.failures", "operation", "chat").increment();
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, "AI_MODEL_UNAVAILABLE", "Local generation unavailable");
        } finally {
            slots.release();
            sample.stop(metrics.timer("commerceflow.ai.model", "operation", "chat"));
        }
    }

    private void acquire() {
        try {
            // Use a bounded wait for shared inference capacity so requests fail explicitly instead of
            // building an unbounded queue.
            if (!slots.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new BusinessException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "AI_CAPACITY_EXCEEDED",
                        "Local inference is busy; retry later");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, "AI_INTERRUPTED", "Inference interrupted");
        }
    }

    public static Message message(Message.Role role, String content) {
        return new Message(role, content, List.of(), List.of(), null, null);
    }
}
