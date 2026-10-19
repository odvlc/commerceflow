/*
 * Checks the transition matrix independently so illegal saga moves fail before state is persisted.
 */
package com.commerceflow.order;

import com.commerceflow.support.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateTest {

    @Test
    void terminalOrdersNeverTransition() {
        // Check every possible next state, including the same terminal state; idempotent handlers
        // must return before requesting a transition.
        for (var terminal : new OrderState[]{OrderState.CONFIRMED, OrderState.CANCELLED})
            for (var next : OrderState.values())
                assertThatThrownBy(() -> terminal.requireTransition(next))
                        .isInstanceOf(BusinessException.class);
    }

    @Test
    void inventoryCannotBeConfirmedBeforePaymentAndCommit() {
        // There is no shortcut from reservation/payment waiting to a fulfilled order; commit
        // acknowledgment is required.
        assertThatThrownBy(() -> OrderState.PENDING_INVENTORY.requireTransition(OrderState.CONFIRMED))
                .isInstanceOf(BusinessException.class);
        OrderState.PENDING_INVENTORY.requireTransition(OrderState.AWAITING_PAYMENT);
        OrderState.AWAITING_PAYMENT.requireTransition(OrderState.COMMITTING_INVENTORY);
        OrderState.COMMITTING_INVENTORY.requireTransition(OrderState.CONFIRMED);
    }
}
