package com.fxplatform.market.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketBundleImmutabilityTest {

  private static final Instant NOW = Instant.parse("2026-07-12T00:00:10Z");

  @Test
  void depthDefensivelyCopiesBothSidesAndExposesUnmodifiableLists() {
    List<MarketDepthLevelResponse> bids = new ArrayList<>(List.of(level("99")));
    List<MarketDepthLevelResponse> asks = new ArrayList<>(List.of(level("101")));
    MarketDepthResponse depth = new MarketDepthResponse("BTCUSDT", NOW.toEpochMilli(), bids, asks);

    bids.add(level("98"));
    asks.add(level("102"));

    assertThat(depth.bids()).hasSize(1);
    assertThat(depth.asks()).hasSize(1);
    assertThatThrownBy(() -> depth.bids().add(level("97")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> depth.asks().add(level("103")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void spotBundleDefensivelyCopiesComponentsAndExposesUnmodifiableLists() {
    List<RecentTradeResponse> trades = new ArrayList<>(List.of(trade("1")));
    List<CandleResponse> candles = new ArrayList<>(List.of(candle(1L)));
    SpotMarketBundle bundle = new SpotMarketBundle(
        "BTCUSDT", "BTCUSDT", "binance", MarketSourceMode.PUBLIC_EXTERNAL,
        decimal("99"), decimal("101"), decimal("100"), null, trades, candles, NOW, NOW.plusSeconds(5));

    trades.add(trade("2"));
    candles.add(candle(2L));

    assertThat(bundle.recentTrades()).hasSize(1);
    assertThat(bundle.candles()).hasSize(1);
    assertThatThrownBy(() -> bundle.recentTrades().add(trade("3")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> bundle.candles().add(candle(3L)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void perpetualBundleDefensivelyCopiesComponentsAndExposesUnmodifiableLists() {
    List<RecentTradeResponse> trades = new ArrayList<>(List.of(trade("1")));
    List<CandleResponse> candles = new ArrayList<>(List.of(candle(1L)));
    PerpetualMarketBundle bundle = new PerpetualMarketBundle(
        "BTCUSDT-PERP", "BTCUSDT", "binance-usdm", MarketSourceMode.PUBLIC_EXTERNAL,
        decimal("99"), decimal("101"), decimal("100"), decimal("100.2"), decimal("100.1"),
        null, trades, candles, NOW, NOW.plusSeconds(5));

    trades.add(trade("2"));
    candles.add(candle(2L));

    assertThat(bundle.recentTrades()).hasSize(1);
    assertThat(bundle.candles()).hasSize(1);
    assertThatThrownBy(() -> bundle.recentTrades().add(trade("3")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> bundle.candles().add(candle(3L)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void nullComponentsRemainConstructibleForWholeBundleValidation() {
    MarketDepthResponse depth = new MarketDepthResponse("BTCUSDT", NOW.toEpochMilli(), null, null);
    SpotMarketBundle spot = new SpotMarketBundle(
        "BTCUSDT", "BTCUSDT", "binance", MarketSourceMode.PUBLIC_EXTERNAL,
        decimal("99"), decimal("101"), decimal("100"), null, null, null, NOW, NOW.plusSeconds(5));
    PerpetualMarketBundle perp = new PerpetualMarketBundle(
        "BTCUSDT-PERP", "BTCUSDT", "binance-usdm", MarketSourceMode.PUBLIC_EXTERNAL,
        decimal("99"), decimal("101"), decimal("100"), decimal("100.2"), decimal("100.1"),
        null, null, null, NOW, NOW.plusSeconds(5));

    assertThat(depth.bids()).isNull();
    assertThat(depth.asks()).isNull();
    assertThat(spot.recentTrades()).isNull();
    assertThat(spot.candles()).isNull();
    assertThat(perp.recentTrades()).isNull();
    assertThat(perp.candles()).isNull();
  }

  private MarketDepthLevelResponse level(String price) {
    return new MarketDepthLevelResponse(decimal(price), BigDecimal.ONE);
  }

  private RecentTradeResponse trade(String id) {
    return new RecentTradeResponse(id, "BTCUSDT", decimal("100"), BigDecimal.ONE, "BUY", NOW.toEpochMilli());
  }

  private CandleResponse candle(long timestamp) {
    return new CandleResponse(
        timestamp, decimal("100"), decimal("101"), decimal("99"), decimal("100"), BigDecimal.ONE);
  }

  private BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
