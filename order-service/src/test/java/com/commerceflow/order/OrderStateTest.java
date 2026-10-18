package com.commerceflow.order;

import static org.assertj.core.api.Assertions.*;

import com.commerceflow.support.BusinessException;
import org.junit.jupiter.api.Test;

class OrderStateTest {

    @Test
    void terminalOrdersNeverTransition() {
        for (var terminal : new OrderState[]{OrderState.CONFIRMED, OrderState.CANCELLED})
            for (var next : OrderState.values())
                assertThatThrownBy(() -> terminal.requireTransition(next))
                        .isInstanceOf(BusinessException.class);
    }

    @Test
    void inventoryCannotBeConfirmedBeforePaymentAndCommit() {
        assertThatThrownBy(() -> OrderState.PENDING_INVENTORY.requireTransition(OrderState.CONFIRMED))
                .isInstanceOf(BusinessException.class);
        OrderState.PENDING_INVENTORY.requireTransition(OrderState.AWAITING_PAYMENT);
        OrderState.AWAITING_PAYMENT.requireTransition(OrderState.COMMITTING_INVENTORY);
        OrderState.COMMITTING_INVENTORY.requireTransition(OrderState.CONFIRMED);
    }
}
