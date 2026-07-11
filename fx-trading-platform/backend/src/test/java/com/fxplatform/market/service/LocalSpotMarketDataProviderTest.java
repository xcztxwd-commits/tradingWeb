package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.adapter.local.LocalSpotMarketDataProvider;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalSpotMarketDataProviderTest {

  private static final Instant NOW = Instant.parse("2026-07-12T00:00:10Z");

  @Test
  void allFiveSpotProfilesProduceCompleteSameGenerationBundles() {
    LocalSpotMarketDataProvider provider = new LocalSpotMarketDataProvider(
        new DemoMarketDataGenerator(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5));

    List<String> symbols = List.of("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT");
    List<BigDecimal> prices = symbols.stream()
        .map(symbol -> provider.fetchSpotBundle(
            symbol, symbol, new CandleRequest("1m", NOW.minusSeconds(300), NOW)).orElseThrow())
        .peek(bundle -> {
          assertThat(bundle.sourceMode()).isEqualTo(MarketSourceMode.LOCAL_SIMULATED);
          assertThat(bundle.asOf()).isEqualTo(NOW);
          assertThat(bundle.expiresAt()).isEqualTo(NOW.plusSeconds(5));
          assertThat(bundle.changePercent()).isEqualByComparingTo(BigDecimal.ZERO);
          assertThat(bundle.high24h()).isGreaterThanOrEqualTo(bundle.last());
          assertThat(bundle.low24h()).isLessThanOrEqualTo(bundle.last());
          assertThat(bundle.volume24h()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
          assertThat(bundle.orderBook().asOf()).isEqualTo(bundle.asOf());
          assertThat(bundle.recentTrades()).allMatch(trade -> bundle.asOf().equals(trade.asOf()));
          assertThat(bundle.candles()).allMatch(candle -> bundle.asOf().equals(candle.asOf()));
        })
        .map(bundle -> bundle.last())
        .toList();

    assertThat(prices.get(0)).isGreaterThan(new BigDecimal("10000"));
    assertThat(prices.get(1)).isGreaterThan(new BigDecimal("1000"));
    assertThat(prices.get(2)).isGreaterThan(new BigDecimal("100"));
    assertThat(prices.get(3)).isGreaterThan(new BigDecimal("10"));
    assertThat(prices.get(4)).isLessThan(new BigDecimal("10"));
    assertThat(prices).doesNotContain(new BigDecimal("1.08320"));
  }
}
