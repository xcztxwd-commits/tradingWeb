package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarketRealtimePropertiesTest {

  @Test
  void defaultsKeepBinanceRealtimeOptInAndWithinStreamBudget() {
    MarketRealtimeProperties properties = new MarketRealtimeProperties();

    assertThat(properties.enabled()).isFalse();
    assertThat(properties.provider()).isEqualTo("binance");
    assertThat(properties.symbols()).containsExactly("BTCUSDT", "ETHUSDT");
    assertThat(properties.klineIntervals()).contains("1s", "1m", "5m", "15m", "1h", "4h", "1d");
    assertThat(properties.maxStreamsPerConnection()).isEqualTo(1024);
    assertThat(properties.effectiveStreamsPerSymbol()).isEqualTo(11);
    assertThat(properties.maxActiveSymbols()).isLessThanOrEqualTo(90);
  }

  @Test
  void orderBookSuffixMatchesDepthAndSpeedSettings() {
    MarketRealtimeProperties properties = new MarketRealtimeProperties();

    assertThat(properties.orderBookStreamSuffix()).isEqualTo("depth20@100ms");

    properties.setOrderBookFast(false);

    assertThat(properties.orderBookStreamSuffix()).isEqualTo("depth20");
  }
}
