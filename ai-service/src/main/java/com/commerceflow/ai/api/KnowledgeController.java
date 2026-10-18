package com.commerceflow.ai.api;

import com.commerceflow.ai.retrieval.KnowledgeModels.ImportDocument;
import com.commerceflow.ai.retrieval.KnowledgeModels.ImportResult;
import com.commerceflow.ai.retrieval.KnowledgeModels.JobView;
import com.commerceflow.ai.retrieval.KnowledgeStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ai/knowledge")
@Validated
public class KnowledgeController {

    private final KnowledgeStore store;

    public KnowledgeController(KnowledgeStore store) {
        this.store = store;
    }

    @PostMapping("/imports")
    public ResponseEntity<ImportResult> ingest(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key,
            @Valid @RequestBody ImportDocument input,
            @RequestAttribute("correlationId") String correlation) {
        return ResponseEntity.accepted()
                .body(store.ingest(UUID.fromString(jwt.getSubject()), key, input, correlation));
    }

    @GetMapping("/jobs/{id}")
    public JobView job(@PathVariable UUID id) {
        return store.job(id);
    }

    @PostMapping("/rebuilds")
    public ResponseEntity<JobView> rebuild(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key) {
        return ResponseEntity.accepted().body(store.rebuild(UUID.fromString(jwt.getSubject()), key));
    }
}
