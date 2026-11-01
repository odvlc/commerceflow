/*
 * Keeps import, indexing and citation DTOs explicit; evidence carries source versions so stale
 * projections can be rejected.
 */
package com.commerceflow.ai.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class KnowledgeModels {

    private KnowledgeModels() {
    }

    public record ImportDocument(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,99}") String sourceId,
            @Min(1) @Max(9007199254740991L) long version,
            @NotBlank @Size(max = 200) String title,
            @Pattern(regexp = "PUBLIC|SUPPORT") @NotNull String audience,
            @NotBlank @Size(max = 100000) String content,
            boolean active) {

    }

    public record ImportResult(
            UUID documentId,
            long version,
            UUID jobId,
            String state) {

    }

    public record Document(
            UUID id,
            String sourceId,
            long version,
            String title,
            String audience,
            String content,
            boolean active,
            Instant effectiveAt) {

    }

    public record Chunk(
            UUID id,
            UUID documentId,
            long version,
            int ordinal,
            String heading,
            String content) {

    }

    public record IndexJob(
            UUID id,
            UUID documentId,
            long version,
            String generation,
            int attempts) {

    }

    public record JobView(
            UUID id,
            String state,
            String indexName,
            long documents,
            String errorCode) {

    }

    // Carry source/version identity with quoted content so callers can trace evidence back to a
    // permitted current source.
    public record Evidence(
            String id,
            String sourceId,
            long version,
            String title,
            String heading,
            String chunkId,
            String content) {

    }

    public record SearchResult(
            List<Evidence> items,
            boolean semantic,
            boolean degraded) {

    }
}
