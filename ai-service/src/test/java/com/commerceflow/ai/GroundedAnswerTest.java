/*
 * Checks that generated answers cannot invent citation IDs outside the evidence collected for
 * their run.
 */
package com.commerceflow.ai;

import com.commerceflow.ai.application.GroundedAnswer;
import com.commerceflow.ai.retrieval.KnowledgeModels.Evidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroundedAnswerTest {

    final JsonMapper json = JsonMapper.builder().build();
    final Map<String, Evidence> evidence =
            Map.of(
                    "owned-source",
                    new Evidence("owned-source", "returns", 1, "Demo returns", "Window", "chunk", "30 days"));

    @Test
    void citationsCannotBeForgedReusedAcrossRunsOrDuplicated() {
        // Supply plausible-looking but unauthorized IDs directly; provenance must be enforced
        // without trusting the model prompt.
        for (var ids :
                List.of(
                        List.of("another-run-source"),
                        List.of("secret-internal"),
                        List.of("owned-source", "owned-source"),
                        List.of()))
            assertThatThrownBy(() -> GroundedAnswer.validate(
                    json.valueToTree(Map.of("answer", "30 days", "citationIds", ids)), evidence))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void answerCannotSmuggleAnActionOrAuthorityField() {
        // A final answer is data only; accepting extra action fields would bypass the explicit
        // proposal/confirmation boundary.
        assertThatThrownBy(() -> GroundedAnswer.validate(
                json.valueToTree(Map.of(
                        "answer", "30 days",
                        "citationIds", List.of("owned-source"),
                        "confirm", true)),
                evidence))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptedReferencesResolveOnlyToServerEvidence() {
        var result = GroundedAnswer.validate(
                json.valueToTree(Map.of("answer", "30 days", "citationIds", List.of("owned-source"))),
                evidence);
        assertThat(result.citations()).containsExactly(evidence.get("owned-source"));
    }
}
