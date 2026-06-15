package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DemoMarketDataGeneratorTest {

  private final DemoMarketDataGenerator generator = new DemoMarketDataGenerator();

  @Test
  void createsChangingSecondLevelQuotesForFrontendTesting() {
    QuoteResponse first = generator.quote("BTCUSDT", 10, Instant.parse("2026-06-06T00:00:10Z"));
    QuoteResponse next = generator.quote("BTCUSDT", 11, Instant.parse("2026-06-06T00:00:11Z"));

    assertThat(first.symbol()).isEqualTo("BTCUSDT");
    assertThat(first.timestamp()).isEqualTo(1780704010000L);
    assertThat(first.mid()).isNotEqualByComparingTo(next.mid());
    assertThat(first.ask()).isGreaterThan(first.bid());
    assertThat(first.source()).isEqualTo("demo-realtime");
  }

  @Test
  void createsSortedOrderBookLevelsAroundLatestQuote() {
    QuoteResponse quote = generator.quote("EURUSD", 5, Instant.parse("2026-06-06T00:00:05Z"));

    MarketDepthResponse depth = generator.depth("EURUSD", quote);

    assertThat(depth.symbol()).isEqualTo("EURUSD");
    assertThat(depth.bids()).hasSize(100);
    assertThat(depth.asks()).hasSize(100);
    assertThat(depth.bids().size() + depth.asks().size()).isEqualTo(200);
    assertThat(depth.bids().get(0).price()).isGreaterThan(depth.bids().get(1).price());
    assertThat(depth.asks().get(0).price()).isLessThan(depth.asks().get(1).price());
    assertThat(depth.bids().get(0).price()).isLessThan(quote.mid());
    assertThat(depth.asks().get(0).price()).isGreaterThan(quote.mid());
  }

  @Test
  void createsLatestTradeRowsFromRealtimeQuotes() {
    QuoteResponse quote = generator.quote("USDJPY", 8, Instant.parse("2026-06-06T00:00:08Z"));

    RecentTradeResponse trade = generator.trade("USDJPY", 8, quote);

    assertThat(trade.id()).isEqualTo("USDJPY-1780704008000-8");
    assertThat(trade.symbol()).isEqualTo("USDJPY");
    assertThat(trade.price()).isEqualByComparingTo(quote.mid());
    assertThat(trade.amount()).isGreaterThan(BigDecimal.ZERO);
    assertThat(trade.side()).isIn("buy", "sell");
    assertThat(trade.timestamp()).isEqualTo(1780704008000L);
  }

  @Test
  void floorsRealtimeCandlesToSupportedTimeframes() {
    Instant time = Instant.parse("2026-06-06T12:17:34Z");

    assertThat(generator.candleOpenTime(time, "1s")).isEqualTo(Instant.parse("2026-06-06T12:17:34Z"));
    assertThat(generator.candleOpenTime(time, "5m")).isEqualTo(Instant.parse("2026-06-06T12:15:00Z"));
    assertThat(generator.candleOpenTime(time, "15m")).isEqualTo(Instant.parse("2026-06-06T12:15:00Z"));
    assertThat(generator.candleOpenTime(time, "1h")).isEqualTo(Instant.parse("2026-06-06T12:00:00Z"));
  }
}
