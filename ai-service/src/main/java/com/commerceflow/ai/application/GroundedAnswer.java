package com.commerceflow.ai.application;

import com.commerceflow.ai.retrieval.KnowledgeModels.Evidence;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Evidence authority comes only from this run's server-collected set. */
public record GroundedAnswer(String content, List<Evidence> citations) {
  public static GroundedAnswer validate(JsonNode input, Map<String, Evidence> permitted) {
    if (input == null
        || !input.isObject()
        || input.properties().stream()
            .anyMatch(p -> !Set.of("answer", "citationIds").contains(p.getKey())))
      throw new IllegalArgumentException("Invalid answer fields");
    var answer = input.path("answer");
    var references = input.path("citationIds");
    if (!answer.isTextual()
        || answer.asText().isBlank()
        || answer.asText().length() > 4000
        || !references.isArray()
        || references.isEmpty()
        || references.size() > 8) throw new IllegalArgumentException("Invalid grounded answer");
    var ids = new LinkedHashSet<String>();
    for (var id : references) {
      if (!id.isTextual() || !permitted.containsKey(id.asText()) || !ids.add(id.asText()))
        throw new IllegalArgumentException("Invalid citation reference");
    }
    return new GroundedAnswer(answer.asText(), ids.stream().map(permitted::get).toList());
  }
}
