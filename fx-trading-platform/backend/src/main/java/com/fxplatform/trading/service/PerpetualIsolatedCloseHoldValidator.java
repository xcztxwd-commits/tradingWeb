package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import java.math.BigDecimal;
import java.util.List;

/** Shared aggregate guard for Task 9 and canonical Task 10 isolated close orders. */
final class PerpetualIsolatedCloseHoldValidator {

  private PerpetualIsolatedCloseHoldValidator() {
  }

  static void validate(
      PositionEntity position,
      PerpetualOrderRiskService.OrderRisk newRisk,
      List<OrderEntity> activeOrders
  ) {
    BigDecimal existingClosing = BigDecimal.ZERO;
    BigDecimal existingHolds = BigDecimal.ZERO;
    for (OrderEntity active : activeOrders) {
      if (!position.getId().equals(active.getParentPositionId())
          || active.getProtectionType() != null) {
        continue;
      }
      existingClosing = existingClosing.add(activeRemainingBase(active));
      existingHolds = existingHolds.add(orZero(active.getHoldAmount()));
    }
    if (existingClosing.add(newRisk.closingBase()).compareTo(abs(position.getLots())) > 0) {
      throw new BusinessException(
          ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION,
          "Aggregate Isolated close orders exceed the locked position slot");
    }
    if (existingHolds.add(newRisk.holdAmount())
        .compareTo(newRisk.isolatedHoldCapacity()) >= 0) {
      throw new BusinessException(
          ErrorCode.MARGIN_REDUCTION_UNSAFE,
          "Aggregate Isolated close holds exceed the position risk buffer");
    }
  }

  private static BigDecimal activeRemainingBase(OrderEntity order) {
    if (order.getRemainingQuantity() != null
        && order.getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
      return order.getRemainingQuantity();
    }
    return orZero(order.getBaseQuantity());
  }

  private static BigDecimal abs(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.abs();
  }
}
