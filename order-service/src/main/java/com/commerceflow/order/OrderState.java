package com.commerceflow.order;

import com.commerceflow.support.BusinessException;
import java.util.Set;
import org.springframework.http.HttpStatus;

public enum OrderState {
  PENDING_INVENTORY,
  AWAITING_PAYMENT,
  COMMITTING_INVENTORY,
  CANCELLING_PAYMENT,
  CANCELLING_INVENTORY,
  CONFIRMED,
  CANCELLED;

  public void requireTransition(OrderState target) {
    Set<OrderState> allowed =
        switch (this) {
          case PENDING_INVENTORY -> Set.of(AWAITING_PAYMENT, CANCELLING_INVENTORY, CANCELLED);
          case AWAITING_PAYMENT ->
              Set.of(COMMITTING_INVENTORY, CANCELLING_PAYMENT, CANCELLING_INVENTORY);
          case COMMITTING_INVENTORY -> Set.of(CONFIRMED);
          case CANCELLING_PAYMENT -> Set.of(CANCELLING_INVENTORY);
          case CANCELLING_INVENTORY -> Set.of(CANCELLED);
          case CONFIRMED, CANCELLED -> Set.of();
        };
    if (!allowed.contains(target))
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "ILLEGAL_ORDER_TRANSITION",
          "Cannot transition " + this + " to " + target);
  }
}
