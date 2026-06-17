package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.market.realtime.RealtimeMarketEvent.Candle;
import com.fxplatform.market.realtime.RealtimeMarketEvent.OrderBook;
import com.fxplatform.market.realtime.RealtimeMarketEvent.Quote;
import com.fxplatform.market.realtime.RealtimeMarketEvent.ServerShutdown;
import com.fxplatform.market.realtime.RealtimeMarketEvent.TickerStats;
import com.fxplatform.market.realtime.RealtimeMarketEvent.Trade;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BinanceStreamMessageParserTest {

  private final BinanceStreamMessageParser parser = new BinanceStreamMessageParser(new ObjectMapper());

  @Test
  void parsesBookTickerAsQuote() {
    RealtimeMarketEvent event = parser.parse("""
        {"u":400900217,"s":"BTCUSDT","b":"100.00","B":"1.2","a":"101.00","A":"2.4"}
        """).orElseThrow();

    assertThat(event).isInstanceOf(Quote.class);
    Quote quote = (Quote) event;
    assertThat(quote.symbol()).isEqualTo("BTCUSDT");
    assertThat(quote.updateId()).isEqualTo(400900217L);
    assertThat(quote.bid()).isEqualByComparingTo(new BigDecimal("100.00"));
    assertThat(quote.ask()).isEqualByComparingTo(new BigDecimal("101.00"));
  }

  @Test
  void parsesTwentyFourHourTickerStats() {
    RealtimeMarketEvent event = parser.parse("""
        {"e":"24hrTicker","E":123456789,"s":"BTCUSDT","P":"5.25","h":"110.00","l":"90.00","v":"1234.5"}
        """).orElseThrow();

    assertThat(event).isInstanceOf(TickerStats.class);
    TickerStats stats = (TickerStats) event;
    assertThat(stats.symbol()).isEqualTo("BTCUSDT");
    assertThat(stats.eventTime()).isEqualTo(123456789L);
    assertThat(stats.changePercent()).isEqualByComparingTo(new BigDecimal("5.25"));
    assertThat(stats.high24h()).isEqualByComparingTo(new BigDecimal("110.00"));
    assertThat(stats.low24h()).isEqualByComparingTo(new BigDecimal("90.00"));
    assertThat(stats.volume24h()).isEqualByComparingTo(new BigDecimal("1234.5"));
  }

  @Test
  void parsesAggTradeAsTrade() {
    RealtimeMarketEvent event = parser.parse("""
        {"e":"aggTrade","E":123456789,"s":"BTCUSDT","a":42,"p":"100.50","q":"0.25","m":false}
        """).orElseThrow();

    assertThat(event).isInstanceOf(Trade.class);
    Trade trade = (Trade) event;
    assertThat(trade.symbol()).isEqualTo("BTCUSDT");
    assertThat(trade.aggregateTradeId()).isEqualTo(42L);
    assertThat(trade.price()).isEqualByComparingTo(new BigDecimal("100.50"));
    assertThat(trade.amount()).isEqualByComparingTo(new BigDecimal("0.25"));
    assertThat(trade.side()).isEqualTo("BUY");
  }

  @Test
  void parsesKlineAsCandle() {
    RealtimeMarketEvent event = parser.parse("""
        {"e":"kline","E":2000,"s":"BTCUSDT","k":{"t":1000,"T":59999,"i":"1m","L":10,"o":"1.00","h":"2.00","l":"0.50","c":"1.50","v":"12.30","x":false}}
        """).orElseThrow();

    assertThat(event).isInstanceOf(Candle.class);
    Candle candle = (Candle) event;
    assertThat(candle.symbol()).isEqualTo("BTCUSDT");
    assertThat(candle.eventTime()).isEqualTo(2000L);
    assertThat(candle.interval()).isEqualTo("1m");
    assertThat(candle.openTime()).isEqualTo(1000L);
    assertThat(candle.closeTime()).isEqualTo(59999L);
    assertThat(candle.lastTradeId()).isEqualTo(10L);
    assertThat(candle.open()).isEqualByComparingTo(new BigDecimal("1.00"));
    assertThat(candle.high()).isEqualByComparingTo(new BigDecimal("2.00"));
    assertThat(candle.low()).isEqualByComparingTo(new BigDecimal("0.50"));
    assertThat(candle.close()).isEqualByComparingTo(new BigDecimal("1.50"));
    assertThat(candle.volume()).isEqualByComparingTo(new BigDecimal("12.30"));
    assertThat(candle.closed()).isFalse();
  }

  @Test
  void parsesCombinedPartialDepthUsingStreamSymbol() {
    RealtimeMarketEvent event = parser.parse("""
        {"stream":"btcusdt@depth20@100ms","data":{"lastUpdateId":160,"bids":[["100.00","1.2"]],"asks":[["101.00","2.4"]]}}
        """).orElseThrow();

    assertThat(event).isInstanceOf(OrderBook.class);
    OrderBook orderBook = (OrderBook) event;
    assertThat(orderBook.symbol()).isEqualTo("BTCUSDT");
    assertThat(orderBook.updateId()).isEqualTo(160L);
    assertThat(orderBook.bids()).hasSize(1);
    assertThat(orderBook.bids().getFirst().price()).isEqualByComparingTo(new BigDecimal("100.00"));
    assertThat(orderBook.bids().getFirst().amount()).isEqualByComparingTo(new BigDecimal("1.2"));
    assertThat(orderBook.asks()).hasSize(1);
    assertThat(orderBook.asks().getFirst().price()).isEqualByComparingTo(new BigDecimal("101.00"));
    assertThat(orderBook.asks().getFirst().amount()).isEqualByComparingTo(new BigDecimal("2.4"));
  }

  @Test
  void parsesServerShutdown() {
    RealtimeMarketEvent event = parser.parse("""
        {"e":"serverShutdown","E":123456789}
        """).orElseThrow();

    assertThat(event).isInstanceOf(ServerShutdown.class);
    assertThat(event.symbol()).isEmpty();
    assertThat(event.eventTime()).isEqualTo(123456789L);
  }

  @Test
  void ignoresBadJsonAndUnsupportedPayloads() {
    assertThat(parser.parse("{bad-json")).isEmpty();
    assertThat(parser.parse("""
        {"e":"unknown","s":"BTCUSDT"}
        """)).isEmpty();
    assertThat(parser.parse("""
        {"e":"unknown","s":"BTCUSDT","u":1,"b":"1.00","a":"2.00"}
        """)).isEmpty();
  }
}
