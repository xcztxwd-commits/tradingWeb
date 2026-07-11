package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.adapter.local.LocalPerpMarketDataProvider;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalPerpMarketDataProviderTest {

  private static final Instant NOW = Instant.parse("2026-07-12T00:00:10Z");

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
          bundle.index().multiply(new BigDecimal("1.001")));
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
}
