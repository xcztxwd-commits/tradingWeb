package com.fxplatform.market.realtime;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RealtimeDeduplicationState {

  private final Map<String, Long> quoteUpdateIds = new HashMap<>();
  private final Map<String, Long> orderBookUpdateIds = new HashMap<>();
  private final Map<String, Long> aggregateTradeIds = new HashMap<>();
  private final Map<String, Long> tickerEventTimes = new HashMap<>();
  private final Map<CandleKey, CandleCursor> candleCursors = new HashMap<>();
  private long serverShutdownEventTime = Long.MIN_VALUE;

  public synchronized boolean shouldProcess(RealtimeMarketEvent event) {
    return switch (event) {
      case RealtimeMarketEvent.Quote quote -> newerId(quoteUpdateIds, quote.symbol(), quote.updateId());
      case RealtimeMarketEvent.OrderBook orderBook -> newerId(orderBookUpdateIds, orderBook.symbol(), orderBook.updateId());
      case RealtimeMarketEvent.Trade trade -> newerId(aggregateTradeIds, trade.symbol(), trade.aggregateTradeId());
      case RealtimeMarketEvent.TickerStats tickerStats -> newerId(tickerEventTimes, tickerStats.symbol(), tickerStats.eventTime());
      case RealtimeMarketEvent.Candle candle -> shouldProcessCandle(candle);
      case RealtimeMarketEvent.ServerShutdown serverShutdown -> shouldProcessServerShutdown(serverShutdown);
    };
  }

  private boolean newerId(Map<String, Long> state, String key, long nextId) {
    long previous = state.getOrDefault(key, Long.MIN_VALUE);
    if (nextId <= previous) {
      return false;
    }
    state.put(key, nextId);
    return true;
  }

  private boolean shouldProcessCandle(RealtimeMarketEvent.Candle candle) {
    CandleKey key = new CandleKey(candle.symbol(), candle.interval(), candle.openTime());
    CandleCursor previous = candleCursors.get(key);
    CandleCursor next = new CandleCursor(candle.eventTime(), candle.lastTradeId());
    if (previous != null && previous.compareTo(next) >= 0) {
      return false;
    }
    candleCursors.put(key, next);
    return true;
  }

  private boolean shouldProcessServerShutdown(RealtimeMarketEvent.ServerShutdown serverShutdown) {
    if (serverShutdown.eventTime() <= serverShutdownEventTime) {
      return false;
    }
    serverShutdownEventTime = serverShutdown.eventTime();
    return true;
  }

  private record CandleKey(String symbol, String interval, long openTime) {
  }

  private record CandleCursor(long eventTime, long lastTradeId) implements Comparable<CandleCursor> {

    @Override
    public int compareTo(CandleCursor other) {
      int eventTimeComparison = Long.compare(eventTime, other.eventTime);
      if (eventTimeComparison != 0) {
        return eventTimeComparison;
      }
      return Long.compare(lastTradeId, other.lastTradeId);
    }
  }
}
