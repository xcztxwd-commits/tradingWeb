package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PerpetualRiskService;
import com.fxplatform.risk.service.PerpetualRiskService.CrossLiquidationLeg;
import com.fxplatform.risk.service.PerpetualRiskService.PoolPosition;
import com.fxplatform.risk.service.PerpetualRiskService.PositionRisk;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerpetualRiskServiceTest {

  private static final BigDecimal MMR = new BigDecimal("0.005");
  private final PerpetualRiskService service = new PerpetualRiskService(new PerpMarginCalculator());

  @Test
  void longRiskUsesAuthorityMarkForUplRoiMaintenanceAndCloseFee() {
    PositionRisk risk = risk(
        OrderSide.BUY, "0.2", "50000", "50500", "1000", "0");

    assertThat(risk.entryNotional()).isEqualByComparingTo("10000.00000000");
    assertThat(risk.markNotional()).isEqualByComparingTo("10100.00000000");
    assertThat(risk.initialMargin()).isEqualByComparingTo("1000.00000000");
    assertThat(risk.maintenanceMargin()).isEqualByComparingTo("50.50000000");
    assertThat(risk.unrealizedPnl()).isEqualByComparingTo("100.00000000");
    assertThat(risk.roiRatio()).isEqualByComparingTo("0.10000000");
    assertThat(risk.estimatedCloseTakerFee()).isEqualByComparingTo("5.05000000");
    assertThat(risk.isolatedEquity()).isEqualByComparingTo("1100.00000000");
    assertThat(risk.liquidationThreshold()).isEqualByComparingTo("55.55000000");
    assertThat(risk.estimatedLiquidationPrice()).isEqualByComparingTo("45248.86877828");
    assertThat(risk.liquidatable()).isFalse();
  }

  @Test
  void shortRiskUsesInversePriceDirectionWithoutChangingCanonicalQuantity() {
    PositionRisk risk = risk(
        OrderSide.SELL, "0.2", "50000", "49500", "1000", "0");

    assertThat(risk.markNotional()).isEqualByComparingTo("9900.00000000");
    assertThat(risk.maintenanceMargin()).isEqualByComparingTo("49.50000000");
    assertThat(risk.unrealizedPnl()).isEqualByComparingTo("100.00000000");
    assertThat(risk.roiRatio()).isEqualByComparingTo("0.10000000");
    assertThat(risk.estimatedCloseTakerFee()).isEqualByComparingTo("4.95000000");
    assertThat(risk.estimatedLiquidationPrice()).isEqualByComparingTo("54699.15464943");
  }

  @Test
  void riskRejectsMissingPositionDirection() {
    assertThatThrownBy(() -> risk(
        null, "0.2", "50000", "50500", "1000", "0"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void isolatedFundingIsPartOfTheSlotPoolAndUnsafeBoundaryIsInclusive() {
    PositionRisk unsafe = risk(
        OrderSide.BUY, "0.2", "50000", "45000", "1049", "0");
    PositionRisk safe = risk(
        OrderSide.BUY, "0.2", "50000", "45000", "1049", "1");

    assertThat(unsafe.isolatedEquity()).isEqualByComparingTo("49.00000000");
    assertThat(unsafe.liquidationThreshold()).isEqualByComparingTo("49.50000000");
    assertThat(unsafe.liquidatable()).isTrue();
    assertThat(safe.isolatedEquity()).isEqualByComparingTo("50.00000000");
    assertThat(safe.liquidatable()).isFalse();
  }

  @Test
  void crossPoolExcludesIsolatedPrincipalAndUplAndSubtractsEveryActiveHold() {
    PositionRisk crossLong = risk(
        OrderSide.BUY, "0.2", "50000", "50500", "1000", "0");
    PositionRisk crossShort = risk(
        OrderSide.SELL, "1", "3000", "3100", "300", "0");
    PositionRisk isolated = risk(
        OrderSide.BUY, "1", "1000", "2000", "2000", "0");

    var cross = service.crossRisk(
        new BigDecimal("50000"),
        List.of(
            new PoolPosition(MarginMode.CROSS, crossLong),
            new PoolPosition(MarginMode.CROSS, crossShort),
            new PoolPosition(MarginMode.ISOLATED, isolated)),
        new BigDecimal("500"));

    assertThat(cross.crossEquity()).isEqualByComparingTo("48000.00000000");
    assertThat(cross.crossMaintenance()).isEqualByComparingTo("66.00000000");
    assertThat(cross.estimatedCloseTakerFees()).isEqualByComparingTo("6.60000000");
    assertThat(cross.crossAvailable()).isEqualByComparingTo("46200.00000000");
    assertThat(cross.liquidatable()).isFalse();
  }

  @Test
  void crossLongLiquidationInverseIncludesFixedPoolRiskAndTargetCloseThreshold() {
    BigDecimal price = service.estimatedCrossLiquidationPrice(
        new BigDecimal("9000"),
        new BigDecimal("11"),
        List.of(crossLeg(OrderSide.BUY, "1", "50000", "0.005")));

    assertThat(price).isEqualByComparingTo("41237.80794369");
  }

  @Test
  void crossShortLiquidationInverseIncludesFixedPoolRiskAndTargetCloseThreshold() {
    BigDecimal price = service.estimatedCrossLiquidationPrice(
        new BigDecimal("9000"),
        new BigDecimal("11"),
        List.of(crossLeg(OrderSide.SELL, "1", "50000", "0.005")));

    assertThat(price).isEqualByComparingTo("58666.33515664");
  }

  @Test
  void deltaNeutralHedgeStillHasOnePositiveGrossMaintenanceBoundary() {
    BigDecimal price = service.estimatedCrossLiquidationPrice(
        new BigDecimal("10000"),
        BigDecimal.ZERO,
        List.of(
            crossLeg(OrderSide.BUY, "1", "50000", "0.005"),
            crossLeg(OrderSide.SELL, "1", "50000", "0.005")));

    assertThat(price).isEqualByComparingTo("909090.90909091");
  }

  @Test
  void negativeCrossLiquidationRootUsesTheExistingZeroDisplayFloor() {
    BigDecimal price = service.estimatedCrossLiquidationPrice(
        new BigDecimal("100000"),
        BigDecimal.ZERO,
        List.of(crossLeg(OrderSide.BUY, "1", "50000", "0.005")));

    assertThat(price).isEqualByComparingTo("0.00000000");
  }

  @Test
  void crossLiquidationReturnsNullWhenTheEquationDenominatorIsZero() {
    BigDecimal price = service.estimatedCrossLiquidationPrice(
        new BigDecimal("10000"),
        BigDecimal.ZERO,
        List.of(crossLeg(OrderSide.BUY, "1", "50000", "0.9995")));

    assertThat(price).isNull();
  }

  @Test
  void crossLiquidationRejectsNegativeCanonicalQuantity() {
    assertThatThrownBy(() -> service.estimatedCrossLiquidationPrice(
        new BigDecimal("10000"),
        BigDecimal.ZERO,
        List.of(crossLeg(OrderSide.BUY, "-1", "50000", "0.005"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private PositionRisk risk(
      OrderSide side,
      String quantity,
      String entry,
      String mark,
      String marginHeld,
      String fundingPnl
  ) {
    return service.positionRisk(
        side,
        new BigDecimal(quantity),
        new BigDecimal(entry),
        new BigDecimal(mark),
        10,
        new BigDecimal(marginHeld),
        new BigDecimal(fundingPnl),
        MMR);
  }

  private CrossLiquidationLeg crossLeg(
      OrderSide side,
      String quantity,
      String entryPrice,
      String maintenanceMarginRate
  ) {
    return new CrossLiquidationLeg(
        side,
        new BigDecimal(quantity),
        new BigDecimal(entryPrice),
        new BigDecimal(maintenanceMarginRate));
  }
}
