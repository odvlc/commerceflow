/*
 * Publishes only knowledge document identity/version; the AI service retains ownership of its
 * document contents.
 */
package com.commerceflow.contracts;

import java.util.UUID;

public final class KnowledgeEvents {

    private KnowledgeEvents() {
    }

    // Consumers use this version to schedule/replay projection work; document content stays in the AI-
    // owned store.
    public record KnowledgeChanged(
            UUID documentId,
            long version) {

        public KnowledgeChanged {
            if (documentId == null || version < 1)
                throw new IllegalArgumentException("Invalid knowledge version");
        }
    }
}
