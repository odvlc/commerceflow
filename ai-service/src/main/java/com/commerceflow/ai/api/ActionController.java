package com.commerceflow.ai.api;

import com.commerceflow.ai.application.CartActions;
import com.commerceflow.ai.domain.Actor;
import com.commerceflow.ai.domain.AiModels.Confirmation;
import com.commerceflow.ai.domain.AiModels.Execution;
import com.commerceflow.ai.domain.AiModels.Proposal;
import com.commerceflow.ai.persistence.ActionStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai/proposals")
public class ActionController {

    public record Rejection(@Min(0) long version) {

    }

    private final ActionStore store;
    private final CartActions actions;

    public ActionController(ActionStore store, CartActions actions) {
        this.store = store;
        this.actions = actions;
    }

    @GetMapping("/{id}")
    public Proposal get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return store.owned(id, Actor.from(jwt));
    }

    @GetMapping("/{id}/execution")
    public Execution execution(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        store.owned(id, Actor.from(jwt));
        return store.execution(id).orElseThrow(com.commerceflow.ai.persistence.AiStore::missing);
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<Execution> confirm(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody Confirmation input) {
        return ResponseEntity.accepted().body(actions.confirm(id, Actor.from(jwt), input));
    }

    @PostMapping("/{id}/reject")
    public Proposal reject(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody Rejection input) {
        return store.reject(id, Actor.from(jwt), input.version());
    }
}
