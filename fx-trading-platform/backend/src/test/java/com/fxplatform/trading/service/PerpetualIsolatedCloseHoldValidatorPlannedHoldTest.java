package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PerpetualIsolatedCloseHoldValidatorPlannedHoldTest {

  @Test
  void plannedInitialHoldReplacesRiskHoldForCapacityCheck() {
    PositionEntity position = position("1");
    PerpetualOrderRiskService.OrderRisk risk = risk("0.25", "20", "10");

    assertThatCode(() -> PerpetualIsolatedCloseHoldValidator.validate(
        position, risk, new BigDecimal("2"), List.of()))
        .doesNotThrowAnyException();
  }

  @Test
  void plannedInitialHoldIsAddedToExistingActiveHolds() {
    PositionEntity position = position("1");
    PerpetualOrderRiskService.OrderRisk risk = risk("0.25", "1", "10");
    OrderEntity active = activeClose(position.getId(), "0.25", "8");

    assertThatThrownBy(() -> PerpetualIsolatedCloseHoldValidator.validate(
        position, risk, new BigDecimal("2"), List.of(active)))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                .isEqualTo(ErrorCode.MARGIN_REDUCTION_UNSAFE));
  }

  @Test
  void legacyOverloadUsesRiskHoldAmount() {
    PositionEntity position = position("1");
    PerpetualOrderRiskService.OrderRisk risk = risk("0.25", "2", "10");
    OrderEntity active = activeClose(position.getId(), "0.25", "8");

    assertThatThrownBy(() -> PerpetualIsolatedCloseHoldValidator.validate(
        position, risk, List.of(active)))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                .isEqualTo(ErrorCode.MARGIN_REDUCTION_UNSAFE));
  }

  private static PositionEntity position(String lots) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.fromString("00000000-0000-0000-0000-000000000101"));
    position.setLots(new BigDecimal(lots));
    return position;
  }

  private static OrderEntity activeClose(UUID positionId, String quantity, String hold) {
    OrderEntity order = new OrderEntity();
    order.setParentPositionId(positionId);
    order.setRemainingQuantity(new BigDecimal(quantity));
    order.setHoldAmount(new BigDecimal(hold));
    return order;
  }

  private static PerpetualOrderRiskService.OrderRisk risk(
      String closingBase,
      String holdAmount,
      String isolatedHoldCapacity
  ) {
    return new PerpetualOrderRiskService.OrderRisk(
        PositionMode.ONE_WAY,
        PositionSide.BOTH,
        MarginMode.ISOLATED,
        10,
        new BigDecimal(closingBase),
        BigDecimal.ZERO,
        new BigDecimal("100"),
        new BigDecimal("100"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        new BigDecimal(holdAmount),
        new BigDecimal(isolatedHoldCapacity),
        "USDT");
  }
}
