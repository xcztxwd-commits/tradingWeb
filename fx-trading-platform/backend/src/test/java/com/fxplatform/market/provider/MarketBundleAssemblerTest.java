package com.fxplatform.market.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.model.MarketSourceMode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketBundleAssemblerTest {

  private static final Instant NOW = Instant.parse("2026-07-12T00:00:10Z");
  private static final Instant OLD_EVENT = Instant.parse("2020-01-01T00:00:00Z");

  @Test
  void historicalTradeAndCandleEventTimesDoNotMakeFreshSnapshotsStale() {
    var observations = MarketBundleAssembler.ComponentObservations.spot(NOW, NOW, NOW, NOW);

    var bundle = MarketBundleAssembler.spot(
        "BTCUSDT", "BTCUSDT", "binance", MarketSourceMode.PUBLIC_EXTERNAL,
        quote(NOW), depth(NOW), List.of(trade(OLD_EVENT)), List.of(candle(OLD_EVENT)),
        observations, Duration.ofSeconds(5));

    assertThat(bundle.asOf()).isEqualTo(NOW);
    assertThat(bundle.expiresAt()).isEqualTo(NOW.plusSeconds(5));
    assertThat(bundle.changePercent()).isEqualByComparingTo("1.25");
    assertThat(bundle.high24h()).isEqualByComparingTo("105");
    assertThat(bundle.low24h()).isEqualByComparingTo("95");
    assertThat(bundle.volume24h()).isEqualByComparingTo("123.45");
    assertThat(bundle.recentTrades().getFirst().timestamp()).isEqualTo(OLD_EVENT.toEpochMilli());
    assertThat(bundle.candles().getFirst().timestamp()).isEqualTo(OLD_EVENT.toEpochMilli());
    QuoteResponse projection = MarketBundleAssembler.quote(bundle, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(projection.changePercent()).isEqualByComparingTo("1.25");
    assertThat(projection.high24h()).isEqualByComparingTo("105");
    assertThat(projection.low24h()).isEqualByComparingTo("95");
    assertThat(projection.volume24h()).isEqualByComparingTo("123.45");
  }

  @Test
  void perpetualBundleAndQuoteProjectionPreserve24HourSummary() {
    var bundle = MarketBundleAssembler.perpetual(
        "BTCUSDT-PERP", "BTCUSDT", "binance-usdm", MarketSourceMode.PUBLIC_EXTERNAL,
        quote(NOW), new BigDecimal("100.2"), new BigDecimal("100.1"), depth(NOW),
        List.of(trade(NOW)), List.of(candle(NOW)),
        MarketBundleAssembler.ComponentObservations.perpetual(NOW, NOW, NOW, NOW, NOW),
        Duration.ofSeconds(5));

    assertThat(bundle.changePercent()).isEqualByComparingTo("1.25");
    assertThat(bundle.high24h()).isEqualByComparingTo("105");
    assertThat(bundle.low24h()).isEqualByComparingTo("95");
    assertThat(bundle.volume24h()).isEqualByComparingTo("123.45");
    QuoteResponse projection = MarketBundleAssembler.quote(bundle, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(projection.changePercent()).isEqualByComparingTo("1.25");
    assertThat(projection.high24h()).isEqualByComparingTo("105");
    assertThat(projection.low24h()).isEqualByComparingTo("95");
    assertThat(projection.volume24h()).isEqualByComparingTo("123.45");
  }

  @Test
  void venueSnapshotTimestampCannotBeMadeNewerThanTheHttpFetch() {
    assertThat(MarketBundleAssembler.snapshotObservedAt(OLD_EVENT.toEpochMilli(), NOW))
        .isEqualTo(OLD_EVENT);
    assertThat(MarketBundleAssembler.snapshotObservedAt(NOW.plusSeconds(10).toEpochMilli(), NOW))
        .isEqualTo(NOW);
    assertThat(MarketBundleAssembler.snapshotObservedAt(0, NOW)).isEqualTo(NOW);
  }

  @Test
  void oldestComponentObservationDefinesBundleFreshness() {
    Instant staleDepthFetch = NOW.minusSeconds(10);
    var observations = MarketBundleAssembler.ComponentObservations.spot(
        NOW, staleDepthFetch, NOW, NOW);

    var bundle = MarketBundleAssembler.spot(
        "BTCUSDT", "BTCUSDT", "binance", MarketSourceMode.PUBLIC_EXTERNAL,
        quote(NOW), depth(NOW), List.of(trade(NOW)), List.of(candle(NOW)),
        observations, Duration.ofSeconds(5));

    assertThat(bundle.asOf()).isEqualTo(staleDepthFetch);
    assertThat(bundle.expiresAt()).isEqualTo(NOW.minusSeconds(5));
  }

  private QuoteResponse quote(Instant eventTime) {
    return new QuoteResponse(
        "quote", "BTCUSDT", new BigDecimal("99"), new BigDecimal("101"),
        new BigDecimal("100"), null, new BigDecimal("2"), "binance", eventTime.toEpochMilli(),
        new BigDecimal("1.25"), new BigDecimal("105"), new BigDecimal("95"),
        new BigDecimal("123.45"));
  }

  private MarketDepthResponse depth(Instant eventTime) {
    return new MarketDepthResponse(
        "BTCUSDT", eventTime.toEpochMilli(),
        List.of(new MarketDepthLevelResponse(new BigDecimal("99"), BigDecimal.ONE)),
        List.of(new MarketDepthLevelResponse(new BigDecimal("101"), BigDecimal.ONE)));
  }

  private RecentTradeResponse trade(Instant eventTime) {
    return new RecentTradeResponse(
        "1", "BTCUSDT", new BigDecimal("100"), BigDecimal.ONE, "BUY", eventTime.toEpochMilli());
  }

  private CandleResponse candle(Instant eventTime) {
    return new CandleResponse(
        eventTime.toEpochMilli(), new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100"), BigDecimal.ONE);
  }
}
