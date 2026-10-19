/*
 * Centralizes versioned topic names so publishers and consumers agree on routing without sharing
 * persistence code.
 */
package com.commerceflow.contracts;

public final class Topics {

    // Topic versions are wire compatibility boundaries, independent of service release numbers.
    public static final String CATALOG = "commerceflow.catalog.v1";
    public static final String ORDER = "commerceflow.order.v1";
    public static final String INVENTORY = "commerceflow.inventory.v1";
    public static final String PAYMENT = "commerceflow.payment.v1";
    public static final String NOTIFICATION = "commerceflow.notification.v1";
    public static final String AI_KNOWLEDGE = "commerceflow.ai-knowledge.v1";
    public static final String DEAD_LETTER = "commerceflow.dead-letter.v1";

    private Topics() {
    }
}
