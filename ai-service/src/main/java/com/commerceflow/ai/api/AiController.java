/*
 * Maps authenticated conversation/run requests to durable state; opening or replaying an event
 * stream does not dispatch work.
 */
package com.commerceflow.ai.api;

import com.commerceflow.ai.application.Credentials;
import com.commerceflow.ai.domain.Actor;
import com.commerceflow.ai.domain.AiModels.Conversation;
import com.commerceflow.ai.domain.AiModels.CreateConversation;
import com.commerceflow.ai.domain.AiModels.MessageView;
import com.commerceflow.ai.domain.AiModels.Page;
import com.commerceflow.ai.domain.AiModels.RunView;
import com.commerceflow.ai.domain.AiModels.SendMessage;
import com.commerceflow.ai.persistence.AiStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai")
@Validated
public class AiController {

    private final com.commerceflow.ai.retrieval.KnowledgeSearch knowledge;
    private final RunStreams streams;
    private final AiStore store;
    private final Credentials credentials;
    private final com.commerceflow.ai.model.LocalModels models;

    public AiController(
            AiStore store,
            Credentials credentials,
            com.commerceflow.ai.model.LocalModels models,
            RunStreams streams,
            com.commerceflow.ai.retrieval.KnowledgeSearch knowledge) {
        this.knowledge = knowledge;
        this.streams = streams;
        this.store = store;
        this.credentials = credentials;
        this.models = models;
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities(@AuthenticationPrincipal Jwt jwt) {
        var a = Actor.from(jwt);
        return Map.of(
                "enabled",
                true,
                "roles",
                a.roles(),
                "chatModel",
                models.chatName(),
                "modelReady",
                models.ready(),
                "actionsEnabled",
                a.roles().contains("CUSTOMER") || a.roles().contains("ADMIN"),
                "maximumMessageCharacters",
                4000);
    }

    @PostMapping("/conversations")
    public ResponseEntity<Conversation> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key,
            @Valid @RequestBody CreateConversation input) {
        var c = store.create(Actor.from(jwt), key, input.mode());
        return ResponseEntity.created(URI.create("/api/v1/ai/conversations/" + c.id())).body(c);
    }

    @GetMapping("/conversations")
    public Page<Conversation> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return store.conversations(Actor.from(jwt), page, size);
    }

    @GetMapping("/conversations/{id}/messages")
    public Page<MessageView> messages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return store.messages(id, Actor.from(jwt), page, size);
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<RunView> send(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key,
            @Valid @RequestBody SendMessage input) {
        var actor = Actor.from(jwt);
        models.requireReady();
        var run = store.send(id, actor, key, input.content());
        credentials.attach(run.id(), actor);
        return ResponseEntity.accepted().location(URI.create("/api/v1/ai/runs/" + run.id())).body(run);
    }

    @GetMapping("/runs/{id}")
    public RunView run(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return store.run(id, Actor.from(jwt));
    }

    @PostMapping("/runs/{id}/cancel")
    public RunView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var result = store.cancel(id, Actor.from(jwt));
        credentials.remove(id);
        return result;
    }

    @PostMapping("/runs/{id}/resume")
    public RunView resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var a = Actor.from(jwt);
        // A committed proposal resumes through Cart reconciliation, which does not require generation.
        // An existing approval continues with deterministic service calls; only fresh investigation
        // requires available inference.
        if (store.run(id, a).proposals().isEmpty()) models.requireReady();
        var run = store.resume(id, a);
        credentials.attach(id, a);
        return run;
    }

    @GetMapping(value = "/runs/{id}/events", produces = "text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader(value = "Last-Event-ID", defaultValue = "0") @Min(0) long after) {
        // Streaming reads persisted events and must not create another run or implicitly resume one.
        return streams.open(id, Actor.from(jwt), after);
    }

    @GetMapping("/knowledge/search")
    public com.commerceflow.ai.retrieval.KnowledgeModels.SearchResult searchKnowledge(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @Size(min = 1, max = 200) String query,
            @RequestParam(defaultValue = "CUSTOMER") @Pattern(regexp = "CUSTOMER|SUPPORT") String mode,
            @RequestParam(defaultValue = "5") @Min(1) @Max(6) int size) {
        Actor.from(jwt).requireMode(mode);
        return knowledge.search(query, mode, size);
    }
}
