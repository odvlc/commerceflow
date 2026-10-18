package com.commerceflow.ai.api;

import com.commerceflow.ai.retrieval.*;
import com.commerceflow.ai.retrieval.KnowledgeModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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
