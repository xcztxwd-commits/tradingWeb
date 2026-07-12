package com.fxplatform.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.risk.model.InstrumentKind;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class PerpMarginCalculatorTest {

  private final PerpMarginCalculator calculator = new PerpMarginCalculator();

  @Test
  void canonicalLinearMarginUsesEntryForInitialAndMarkForMaintenance() {
    PerpMarginCalculator.LinearMarginResult result = calculator.calculateLinear(
        new BigDecimal("0.2"),
        new BigDecimal("50000"),
        new BigDecimal("50500"),
        10,
        new BigDecimal("0.005"));

    assertThat(result.entryNotional()).isEqualByComparingTo("10000.00000000");
    assertThat(result.markNotional()).isEqualByComparingTo("10100.00000000");
    assertThat(result.initialMargin()).isEqualByComparingTo("1000.00000000");
    assertThat(result.maintenanceMargin()).isEqualByComparingTo("50.50000000");
  }

  @ParameterizedTest
  @MethodSource("invalidLinearRiskInputs")
  void canonicalLinearMarginRejectsIncompleteOrNonPositiveRiskInputs(
      String quantity,
      String entry,
      String mark,
      int leverage,
      String maintenanceMarginRate
  ) {
    assertThatThrownBy(() -> calculator.calculateLinear(
        decimal(quantity),
        decimal(entry),
        decimal(mark),
        leverage,
        decimal(maintenanceMarginRate)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Stream<Arguments> invalidLinearRiskInputs() {
    return Stream.of(
        Arguments.of(null, "50000", "50500", 10, "0.005"),
        Arguments.of("0", "50000", "50500", 10, "0.005"),
        Arguments.of("-0.2", "50000", "50500", 10, "0.005"),
        Arguments.of("0.2", null, "50500", 10, "0.005"),
        Arguments.of("0.2", "0", "50500", 10, "0.005"),
        Arguments.of("0.2", "50000", null, 10, "0.005"),
        Arguments.of("0.2", "50000", "0", 10, "0.005"),
        Arguments.of("0.2", "50000", "50500", 0, "0.005"),
        Arguments.of("0.2", "50000", "50500", 10, null),
        Arguments.of("0.2", "50000", "50500", 10, "0"));
  }

  private static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }

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
