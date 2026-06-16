package com.fxplatform.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.risk.model.InstrumentKind;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PerpMarginCalculatorTest {

  private final PerpMarginCalculator calculator = new PerpMarginCalculator();

  @Test
  void linearPerpetualCalculatesInitialAndMaintenanceMargin() {
    PerpMarginCalculator.MarginResult result = calculator.calculate(
        InstrumentKind.LINEAR_PERPETUAL,
        new BigDecimal("1"),
        new BigDecimal("1"),
        BigDecimal.ONE,
        new BigDecimal("50000"),
        10,
        new BigDecimal("0.005"));

    assertThat(result.notional()).isEqualByComparingTo("50000.00000000");
    assertThat(result.initialMargin()).isEqualByComparingTo("5000.00000000");
    assertThat(result.maintenanceMargin()).isEqualByComparingTo("250.00000000");
  }

  @Test
  void inversePerpetualCalculatesCoinSettledInitialAndMaintenanceMargin() {
    PerpMarginCalculator.MarginResult result = calculator.calculate(
        InstrumentKind.INVERSE_PERPETUAL,
        new BigDecimal("100"),
        new BigDecimal("100"),
        BigDecimal.ONE,
        new BigDecimal("50000"),
        10,
        new BigDecimal("0.005"));

    assertThat(result.notional()).isEqualByComparingTo("10000.00000000");
    assertThat(result.initialMargin()).isEqualByComparingTo("0.02000000");
    assertThat(result.maintenanceMargin()).isEqualByComparingTo("0.00100000");
  }
}
