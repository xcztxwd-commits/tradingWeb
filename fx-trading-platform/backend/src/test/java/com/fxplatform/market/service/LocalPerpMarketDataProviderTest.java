package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.adapter.local.LocalPerpMarketDataProvider;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalPerpMarketDataProviderTest {

  private static final Instant NOW = Instant.parse("2026-07-12T00:00:10Z");

  @ParameterizedTest
  @ValueSource(strings = {"0m", "-1m", "999999999999999999999999m", "1M"})
  void invalidOrOverflowingIntervalsRejectTheLocalBundle(String timeframe) {
    LocalPerpMarketDataProvider provider = provider();

    assertThat(provider.fetchPerpetualBundle(
        "BTCUSDT-PERP", "BTCUSDT-PERP",
        new CandleRequest(timeframe, NOW.minusSeconds(60), NOW))).isEmpty();
  }

  @Test
  void reversedAndOversizedRangesRejectTheLocalBundle() {
    LocalPerpMarketDataProvider provider = provider();

    assertThat(provider.fetchPerpetualBundle(
        "BTCUSDT-PERP", "BTCUSDT-PERP",
        new CandleRequest("1m", NOW, NOW.minusSeconds(1)))).isEmpty();
    assertThat(provider.fetchPerpetualBundle(
        "BTCUSDT-PERP", "BTCUSDT-PERP",
        new CandleRequest("1d", NOW.minus(Duration.ofDays(367)), NOW))).isEmpty();
  }

  @Test
  void markUsesSameGenerationSpotIndexAndClampedPremiumForAllFivePerpetuals() {
    LocalPerpMarketDataProvider provider = new LocalPerpMarketDataProvider(
        new DemoMarketDataGenerator(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofSeconds(5),
        new BigDecimal("0.05"),
        new BigDecimal("0.001"));

    List<String> symbols = List.of(
        "BTCUSDT-PERP", "ETHUSDT-PERP", "BNBUSDT-PERP", "SOLUSDT-PERP", "XRPUSDT-PERP");
    for (String symbol : symbols) {
      var bundle = provider.fetchPerpetualBundle(
          symbol, symbol, new CandleRequest("1m", NOW.minusSeconds(300), NOW)).orElseThrow();

      assertThat(bundle.sourceMode()).isEqualTo(MarketSourceMode.LOCAL_SIMULATED);
      assertThat(bundle.mark()).isEqualByComparingTo(
          bundle.index().multiply(new BigDecimal("1.001")).setScale(10, RoundingMode.HALF_UP));
      assertThat(bundle.mark().scale()).isEqualTo(10);
      assertThat(bundle.bid()).isLessThanOrEqualTo(bundle.last());
      assertThat(bundle.last()).isLessThanOrEqualTo(bundle.ask());
      assertThat(bundle.last()).isEqualByComparingTo(bundle.mark());
      assertThat(bundle.changePercent()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(bundle.high24h()).isGreaterThanOrEqualTo(bundle.last());
      assertThat(bundle.low24h()).isLessThanOrEqualTo(bundle.last());
      assertThat(bundle.volume24h()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
      assertThat(bundle.asOf()).isEqualTo(NOW);
      assertThat(bundle.orderBook().asOf()).isEqualTo(NOW);
      assertThat(bundle.recentTrades()).allMatch(trade -> NOW.equals(trade.asOf()));
      assertThat(bundle.candles()).allMatch(candle -> NOW.equals(candle.asOf()));
    }
  }

  private LocalPerpMarketDataProvider provider() {
    return new LocalPerpMarketDataProvider(
        new DemoMarketDataGenerator(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofSeconds(5),
        new BigDecimal("0.0001"),
        new BigDecimal("0.001"));
  }
}
