package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.realtime.RealtimeMarketEvent.TickerStats;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RealtimeMarketSnapshotCacheTest {

  private final RealtimeMarketSnapshotCache cache = new RealtimeMarketSnapshotCache();

  @Test
  void tickerStatsAreEmptyUntilUpdated() {
    assertThat(cache.tickerStats("btcusdt")).isEmpty();

    TickerStats stats = new TickerStats(
        "BTCUSDT",
        1000L,
        new BigDecimal("1.25"),
        new BigDecimal("110.00"),
        new BigDecimal("90.00"),
        new BigDecimal("1000.00"));
    cache.putTickerStats(stats);

    assertThat(cache.tickerStats("btcusdt")).contains(stats);
  }

  @Test
  void recentTradesReturnNewestFirstAndCapAtOneHundred() {
    for (int index = 0; index < 101; index++) {
      cache.addTrade(trade(index));
    }

    List<RecentTradeResponse> trades = cache.recentTrades("BTCUSDT", 200);

    assertThat(trades).hasSize(100);
    assertThat(trades.getFirst().id()).isEqualTo("100");
    assertThat(trades.getLast().id()).isEqualTo("1");
    assertThat(cache.recentTrades("BTCUSDT", 2)).extracting(RecentTradeResponse::id)
        .containsExactly("100", "99");
  }

  @Test
  void orderBookStoresLastDepthSnapshot() {
    MarketDepthResponse first = depth("BTCUSDT", "100.00");
    MarketDepthResponse second = depth("BTCUSDT", "101.00");

    cache.putOrderBook(first);
    cache.putOrderBook(second);

    assertThat(cache.orderBook("btcusdt")).contains(second);
  }

  private RecentTradeResponse trade(int index) {
    return new RecentTradeResponse(
        String.valueOf(index),
        "BTCUSDT",
        new BigDecimal("100.00").add(BigDecimal.valueOf(index)),
        new BigDecimal("0.10"),
        "BUY",
        1000L + index);
  }

  private MarketDepthResponse depth(String symbol, String bid) {
    return new MarketDepthResponse(
        symbol,
        1000L,
        List.of(new MarketDepthLevelResponse(new BigDecimal(bid), new BigDecimal("1.0"))),
        List.of(new MarketDepthLevelResponse(new BigDecimal("102.00"), new BigDecimal("2.0"))));
  }
}
