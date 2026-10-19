/*
 * Defines AI API/checkpoint boundary records without sharing persistence entities with commerce
 * services.
 */
package com.commerceflow.ai.domain;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AiModels {

    private AiModels() {
    }

    public record CreateConversation(@NotNull @Pattern(regexp = "CUSTOMER|SUPPORT") String mode) {

    }

    public record SendMessage(@NotBlank @Size(max = 4000) String content) {

    }

    // Approval binds to the exact preview hash and version; a proposal UUID alone does not authorize
    // execution.
    public record Confirmation(
            @Min(0) long version, @NotNull @Pattern(regexp = "[a-f0-9]{64}") String argumentsHash) {

    }

    public record Page<T>(List<T> items, int page, int size, long totalElements) {

    }

    public record Conversation(
            UUID id, UUID ownerId, String mode, long version, Instant createdAt, Instant updatedAt) {

    }

    public record Citation(
            String sourceId, long version, String title, String heading, String chunkId) {

    }

    public record MessageView(
            UUID id,
            UUID runId,
            String role,
            String content,
            JsonNode citations,
            long sequence,
            Instant createdAt) {

    }

    public record RunView(
            UUID id,
            UUID conversationId,
            String state,
            String modelName,
            long fence,
            String errorCode,
            int rounds,
            long activeMillis,
            Instant createdAt,
            Instant updatedAt,
            List<StepView> steps,
            List<Proposal> proposals) {

    }

    public record StepView(
            UUID id,
            long sequence,
            String kind,
            String name,
            String state,
            JsonNode input,
            JsonNode result,
            Long durationMillis) {

    }

    public record RunEvent(long sequence, String type, JsonNode payload) {

    }

    public record Proposal(
            UUID id,
            UUID runId,
            String operation,
            UUID productId,
            int quantity,
            String argumentsHash,
            String cartRevision,
            String state,
            long version,
            Instant expiresAt,
            JsonNode preview) {

    }

    public record Execution(
            UUID id,
            UUID proposalId,
            String requestKey,
            String state,
            int attempts,
            JsonNode result,
            String errorCode,
            Instant recoveryUntil) {

    }

    public record Claimed(
            UUID id,
            UUID conversationId,
            UUID ownerId,
            String mode,
            long fence,
            String modelName,
            int rounds,
            long activeMillis) {

    }
}
