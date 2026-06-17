package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BinanceStreamNameTest {

  @Test
  void formatsBinanceSpotStreamNames() {
    assertThat(BinanceStreamName.bookTicker("BTCUSDT")).isEqualTo("btcusdt@bookTicker");
    assertThat(BinanceStreamName.ticker("BTCUSDT")).isEqualTo("btcusdt@ticker");
    assertThat(BinanceStreamName.aggTrade("BTCUSDT")).isEqualTo("btcusdt@aggTrade");
    assertThat(BinanceStreamName.depth("BTCUSDT", 20, true)).isEqualTo("btcusdt@depth20@100ms");
    assertThat(BinanceStreamName.kline("BTCUSDT", "1m")).isEqualTo("btcusdt@kline_1m");
  }

  @Test
  void extractsSymbolFromStreamName() {
    assertThat(BinanceStreamName.symbolFromStream("btcusdt@depth20@100ms")).isEqualTo("BTCUSDT");
  }
}
