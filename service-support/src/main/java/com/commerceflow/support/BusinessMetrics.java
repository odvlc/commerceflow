/*
 * Defers business outcome counters until transaction commit so rolled-back state is not counted as
 * successful work.
 */
package com.commerceflow.support;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class BusinessMetrics {

    private BusinessMetrics() {
    }

    public static void afterCommit(Runnable record) {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            record.run();
                        }
                    });
            // Record completed work immediately when there is no transaction to await.
        else record.run();
    }
}
