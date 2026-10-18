package com.commerceflow.contracts;

import java.util.UUID;

public final class KnowledgeEvents {

    private KnowledgeEvents() {
    }

    public record KnowledgeChanged(UUID documentId, long version) {

        public KnowledgeChanged {
            if (documentId == null || version < 1)
                throw new IllegalArgumentException("Invalid knowledge version");
        }
    }
}
