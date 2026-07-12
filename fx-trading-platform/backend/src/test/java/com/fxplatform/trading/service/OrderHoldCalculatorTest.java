package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderHoldCalculatorTest {

  private final OrderHoldCalculator calculator = new OrderHoldCalculator(
      new FullFillCoordinator(request -> null));

  @Test
  void calculatesLimitAndStopBuyHoldsWithUpwardWalletRounding() {
    assertThat(calculator.limit(OrderSide.BUY, decimal("0.1"), decimal("50000"), snapshot()).amount())
        .isEqualByComparingTo("5002.50000000");
    assertThat(calculator.stopMarket(OrderSide.BUY, decimal("0.1"), decimal("51000"), snapshot()).amount())
        .isEqualByComparingTo("5103.06025500");
  }

  @Test
  void usesOneBaseHoldForSellAndMaximumNotSumForBuyOco() {
    assertThat(calculator.limit(OrderSide.SELL, decimal("0.1"), decimal("55000"), snapshot()).amount())
        .isEqualByComparingTo("0.10000000");
    assertThat(calculator.stopMarket(OrderSide.SELL, decimal("0.1"), decimal("49000"), snapshot()).amount())
        .isEqualByComparingTo("0.10000000");

    OrderHoldCalculator.OrderHold hold = calculator.oco(
        OrderSide.BUY,
        decimal("0.1"),
        decimal("49000"),
        decimal("51000"),
        snapshot());

    assertThat(hold.amount()).isEqualByComparingTo("5103.06025500");
    assertThat(hold.currency()).isEqualTo("USDT");
  }

  private static ExecutableMarketSnapshot snapshot() {
    Instant now = Instant.now();
    return new ExecutableMarketSnapshot(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        "binance",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal("49990"),
        decimal("50010"),
        decimal("50000"),
        null,
        null,
        now.minusSeconds(1),
        now.plusSeconds(30));
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
