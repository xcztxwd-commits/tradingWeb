package com.fxplatform.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PnLCalculatorTest {

  private final PnLCalculator calculator = new PnLCalculator();

  @Test
  void buyPositionProfitAndLossUseForexContractSize() {
    assertThat(calculator.floatingPnl(
        OrderSide.BUY,
        new BigDecimal("0.10"),
        new BigDecimal("1.10020"),
        new BigDecimal("1.10120")))
        .isEqualByComparingTo("10.00");

    assertThat(calculator.floatingPnl(
        OrderSide.BUY,
        new BigDecimal("0.10"),
        new BigDecimal("1.10020"),
        new BigDecimal("1.09920")))
        .isEqualByComparingTo("-10.00");
  }

  @Test
  void sellPositionProfitAndLossUseForexContractSize() {
    assertThat(calculator.floatingPnl(
        OrderSide.SELL,
        new BigDecimal("0.10"),
        new BigDecimal("1.10020"),
        new BigDecimal("1.09920")))
        .isEqualByComparingTo("10.00");

    assertThat(calculator.floatingPnl(
        OrderSide.SELL,
        new BigDecimal("0.10"),
        new BigDecimal("1.10020"),
        new BigDecimal("1.10120")))
        .isEqualByComparingTo("-10.00");
  }
}
