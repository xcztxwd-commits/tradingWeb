package com.fxplatform.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PerpetualAlgorithmRegressionProtectionTest {

  private final TradingAlgorithmEngine engine = new TradingAlgorithmEngine();

  @Test
  void linearPerpetualRequiredMarginAndUnrealizedPnlUseQuoteNotionalFormula() {
    BigDecimal requiredMargin = engine.requiredMargin(
        InstrumentKind.LINEAR_PERPETUAL,
        new BigDecimal("2"),
        new BigDecimal("25000"),
        BigDecimal.ONE,
        new BigDecimal("10"));
    BigDecimal unrealizedPnl = engine.unrealizedPnl(
        InstrumentKind.LINEAR_PERPETUAL,
        OrderSide.BUY,
        new BigDecimal("2"),
        new BigDecimal("25000"),
        new BigDecimal("26000"),
        BigDecimal.ONE);

    assertThat(requiredMargin).isEqualByComparingTo("5000.00000000");
    assertThat(unrealizedPnl).isEqualByComparingTo("2000.00000000");
  }

  @Test
  void inversePerpetualRequiredMarginAndUnrealizedPnlUseContractSizeAndReciprocalFormula() {
    BigDecimal requiredMargin = engine.requiredMargin(
        InstrumentKind.INVERSE_PERPETUAL,
        new BigDecimal("100"),
        new BigDecimal("50000"),
        new BigDecimal("100"),
        new BigDecimal("10"));
    BigDecimal unrealizedPnl = engine.unrealizedPnl(
        InstrumentKind.INVERSE_PERPETUAL,
        OrderSide.BUY,
        new BigDecimal("100"),
        new BigDecimal("50000"),
        new BigDecimal("55000"),
        new BigDecimal("100"));

    assertThat(requiredMargin).isEqualByComparingTo("0.02000000");
    assertThat(unrealizedPnl).isEqualByComparingTo("0.01818182");
  }
}
