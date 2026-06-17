package com.fxplatform.market.realtime;

import com.fxplatform.market.dto.MarketDepthLevelResponse;
import java.math.BigDecimal;
import java.util.List;

public sealed interface RealtimeMarketEvent permits
    RealtimeMarketEvent.Quote,
    RealtimeMarketEvent.TickerStats,
    RealtimeMarketEvent.OrderBook,
    RealtimeMarketEvent.Trade,
    RealtimeMarketEvent.Candle,
    RealtimeMarketEvent.ServerShutdown {

  String symbol();

  long eventTime();

  record Quote(
      String symbol,
      long eventTime,
      long updateId,
      BigDecimal bid,
      BigDecimal ask
  ) implements RealtimeMarketEvent {
  }

  record TickerStats(
      String symbol,
      long eventTime,
      BigDecimal changePercent,
      BigDecimal high24h,
      BigDecimal low24h,
      BigDecimal volume24h
  ) implements RealtimeMarketEvent {
  }

  record OrderBook(
      String symbol,
      long eventTime,
      long updateId,
      List<MarketDepthLevelResponse> bids,
      List<MarketDepthLevelResponse> asks
  ) implements RealtimeMarketEvent {
  }

  record Trade(
      String symbol,
      long eventTime,
      long aggregateTradeId,
      BigDecimal price,
      BigDecimal amount,
      String side
  ) implements RealtimeMarketEvent {
  }

  record Candle(
      String symbol,
      long eventTime,
      String interval,
      long openTime,
      long closeTime,
      long lastTradeId,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      BigDecimal volume,
      boolean closed
  ) implements RealtimeMarketEvent {
  }

  record ServerShutdown(long eventTime) implements RealtimeMarketEvent {

    @Override
    public String symbol() {
      return "";
    }
  }
}
