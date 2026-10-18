package com.commerceflow.support;

import org.springframework.transaction.support.*;

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
        else record.run();
    }
}
