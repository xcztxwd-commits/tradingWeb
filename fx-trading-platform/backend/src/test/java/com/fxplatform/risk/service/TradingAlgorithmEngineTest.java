package com.fxplatform.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TradingAlgorithmEngineTest {

  private final TradingAlgorithmEngine engine = new TradingAlgorithmEngine();

  @Test
  void spotBuyAndSellMatchReceivedAssetFeeExample() {
    TradingAlgorithmEngine.SpotBuyResult buy = engine.spotBuyWithQuoteBudget(
        new BigDecimal("10000"),
        new BigDecimal("50000"),
        new BigDecimal("0.001"));

    assertThat(buy.grossBase()).isEqualByComparingTo("0.2");
    assertThat(buy.feeBase()).isEqualByComparingTo("0.0002");
    assertThat(buy.netBase()).isEqualByComparingTo("0.1998");
    assertThat(buy.averageCost()).isEqualByComparingTo("50050.05005005");

    TradingAlgorithmEngine.SpotSellResult sell = engine.spotSell(
        buy.netBase(),
        new BigDecimal("55000"),
        new BigDecimal("0.001"),
        new BigDecimal("10000"));

    assertThat(sell.grossQuote()).isEqualByComparingTo("10989");
    assertThat(sell.feeQuote()).isEqualByComparingTo("10.989");
    assertThat(sell.netQuote()).isEqualByComparingTo("10978.011");
    assertThat(sell.netPnl()).isEqualByComparingTo("978.011");
  }

  @Test
  void forexLongUsesAskToOpenBidToCloseAndPerSideCommission() {
    BigDecimal margin = engine.requiredMargin(
        InstrumentKind.FOREX,
        new BigDecimal("100000"),
        new BigDecimal("1.10002"),
        BigDecimal.ONE,
        new BigDecimal("50"));
    BigDecimal grossPnl = engine.unrealizedPnl(
        InstrumentKind.FOREX,
        OrderSide.BUY,
        new BigDecimal("100000"),
        new BigDecimal("1.10002"),
        new BigDecimal("1.10100"),
        BigDecimal.ONE);
    BigDecimal netPnl = engine.netPnl(grossPnl, new BigDecimal("3.50"), new BigDecimal("3.50"), BigDecimal.ZERO);

    assertThat(margin).isEqualByComparingTo("2200.04");
    assertThat(grossPnl).isEqualByComparingTo("98.00000000");
    assertThat(netPnl).isEqualByComparingTo("91.00000000");
  }

  @Test
  void forexShortUsesBidToOpenAskToClose() {
    BigDecimal grossPnl = engine.unrealizedPnl(
        InstrumentKind.FOREX,
        OrderSide.SELL,
        new BigDecimal("100000"),
        new BigDecimal("1.10000"),
        new BigDecimal("1.09890"),
        BigDecimal.ONE);
    BigDecimal netPnl = engine.netPnl(grossPnl, new BigDecimal("3.50"), new BigDecimal("3.50"), BigDecimal.ZERO);

    assertThat(grossPnl).isEqualByComparingTo("110.00000000");
    assertThat(netPnl).isEqualByComparingTo("103.00000000");
  }

  @Test
  void linearPerpetualLongMatchesDocumentedNetPnlAndRoi() {
    BigDecimal initialMargin = engine.requiredMargin(
        InstrumentKind.LINEAR_PERPETUAL,
        new BigDecimal("1"),
        new BigDecimal("50000"),
        BigDecimal.ONE,
        new BigDecimal("10"));
    BigDecimal grossPnl = engine.unrealizedPnl(
        InstrumentKind.LINEAR_PERPETUAL,
        OrderSide.BUY,
        new BigDecimal("1"),
        new BigDecimal("50000"),
        new BigDecimal("55000"),
        BigDecimal.ONE);
    BigDecimal openFee = engine.linearFee(new BigDecimal("1"), new BigDecimal("50000"), new BigDecimal("0.0004"), BigDecimal.ONE);
    BigDecimal closeFee = engine.linearFee(new BigDecimal("1"), new BigDecimal("55000"), new BigDecimal("0.0004"), BigDecimal.ONE);
    BigDecimal netPnl = engine.netPnl(grossPnl, openFee, closeFee, new BigDecimal("-5"));

    assertThat(initialMargin).isEqualByComparingTo("5000.00000000");
    assertThat(grossPnl).isEqualByComparingTo("5000.00000000");
    assertThat(openFee).isEqualByComparingTo("20.00000000");
    assertThat(closeFee).isEqualByComparingTo("22.00000000");
    assertThat(netPnl).isEqualByComparingTo("4953.00000000");
    assertThat(engine.roi(netPnl, initialMargin)).isEqualByComparingTo("0.99060000");
  }

  @Test
  void inversePerpetualLongUsesReciprocalFormula() {
    BigDecimal usdNotional = engine.inverseUsdNotional(new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ONE);
    BigDecimal grossPnl = engine.inversePnl(
        OrderSide.BUY,
        usdNotional,
        new BigDecimal("50000"),
        new BigDecimal("55000"));
    BigDecimal openFee = engine.inverseFee(usdNotional, new BigDecimal("50000"), new BigDecimal("0.0005"));
    BigDecimal closeFee = engine.inverseFee(usdNotional, new BigDecimal("55000"), new BigDecimal("0.0005"));

    assertThat(usdNotional).isEqualByComparingTo("10000");
    assertThat(grossPnl).isEqualByComparingTo("0.01818182");
    assertThat(openFee).isEqualByComparingTo("0.00010000");
    assertThat(closeFee).isEqualByComparingTo("0.00009091");
    assertThat(engine.netPnl(grossPnl, openFee, closeFee, BigDecimal.ZERO)).isEqualByComparingTo("0.01799091");
  }

  @Test
  void linearPerpetualLiquidationPriceUsesMarginToZeroEquity() {
    assertThat(engine.liquidationPrice(
        InstrumentKind.LINEAR_PERPETUAL,
        OrderSide.BUY,
        new BigDecimal("1"),
        new BigDecimal("50000"),
        new BigDecimal("5000"),
        BigDecimal.ONE))
        .isEqualByComparingTo("45000.00000000");

    assertThat(engine.liquidationPrice(
        InstrumentKind.LINEAR_PERPETUAL,
        OrderSide.SELL,
        new BigDecimal("1"),
        new BigDecimal("50000"),
        new BigDecimal("5000"),
        BigDecimal.ONE))
        .isEqualByComparingTo("55000.00000000");
  }

  @Test
  void inversePerpetualLiquidationPriceUsesCoinSettledMargin() {
    assertThat(engine.liquidationPrice(
        InstrumentKind.INVERSE_PERPETUAL,
        OrderSide.BUY,
        new BigDecimal("100"),
        new BigDecimal("50000"),
        new BigDecimal("0.02000000"),
        new BigDecimal("100")))
        .isEqualByComparingTo("45454.54545455");

    assertThat(engine.liquidationPrice(
        InstrumentKind.INVERSE_PERPETUAL,
        OrderSide.SELL,
        new BigDecimal("100"),
        new BigDecimal("50000"),
        new BigDecimal("0.02000000"),
        new BigDecimal("100")))
        .isEqualByComparingTo("55555.55555556");
  }
}
