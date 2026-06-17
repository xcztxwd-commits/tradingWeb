package com.fxplatform.market.realtime;

import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.service.DemoMarketDataGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DemoRealtimeEventFactory {

  private static final long DEFAULT_INTERVAL_MILLIS = 60_000L;

  private final DemoMarketDataGenerator generator;
  private final Map<CandleKey, CandleState> candles = new ConcurrentHashMap<>();

  public DemoRealtimeEventFactory(DemoMarketDataGenerator generator) {
    this.generator = generator;
  }

  public List<RealtimeMarketEvent> events(
      QuoteResponse quote,
      MarketDepthResponse depth,
      RecentTradeResponse trade,
      List<String> intervals,
      long sequence) {
    List<String> normalizedIntervals = intervals == null ? List.of() : intervals.stream()
        .filter(interval -> interval != null && !interval.isBlank())
        .distinct()
        .toList();
    List<RealtimeMarketEvent> events = new ArrayList<>(3 + normalizedIntervals.size());
    events.add(new RealtimeMarketEvent.Quote(quote.symbol(), quote.timestamp(), sequence, quote.bid(), quote.ask()));
    events.add(new RealtimeMarketEvent.OrderBook(
        depth.symbol(),
        depth.timestamp(),
        sequence,
        depth.bids(),
        depth.asks()));
    events.add(new RealtimeMarketEvent.Trade(
        trade.symbol(),
        trade.timestamp(),
        sequence,
        trade.price(),
        trade.amount(),
        trade.side()));
    for (String interval : normalizedIntervals) {
      events.add(candleEvent(quote, trade, interval, sequence));
    }
    return events;
  }

  private RealtimeMarketEvent.Candle candleEvent(
      QuoteResponse quote,
      RecentTradeResponse trade,
      String interval,
      long sequence) {
    long openTime = generator.candleOpenTime(Instant.ofEpochMilli(quote.timestamp()), interval).toEpochMilli();
    BigDecimal price = quote.mid();
    CandleKey key = new CandleKey(quote.symbol(), interval);
    CandleState state = candles.compute(key, (ignored, current) -> {
      if (current == null || current.openTime() != openTime) {
        return CandleState.open(openTime, price, trade.amount());
      }
      return current.add(price, trade.amount());
    });
    return state.toEvent(
        quote.symbol(),
        quote.timestamp(),
        interval,
        openTime + intervalMillis(interval) - 1L,
        sequence);
  }

  private long intervalMillis(String interval) {
    if (interval == null || interval.length() < 2) {
      return DEFAULT_INTERVAL_MILLIS;
    }
    try {
      long value = Long.parseLong(interval.substring(0, interval.length() - 1));
      return switch (interval.charAt(interval.length() - 1)) {
        case 's' -> value * 1_000L;
        case 'm' -> value * 60_000L;
        case 'h' -> value * 3_600_000L;
        case 'd' -> value * 86_400_000L;
        default -> DEFAULT_INTERVAL_MILLIS;
      };
    } catch (NumberFormatException ex) {
      return DEFAULT_INTERVAL_MILLIS;
    }
  }

  private record CandleKey(String symbol, String interval) {
  }

  private record CandleState(
      long openTime,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      BigDecimal volume
  ) {

    static CandleState open(long openTime, BigDecimal price, BigDecimal volume) {
      return new CandleState(openTime, price, price, price, price, volume);
    }

    CandleState add(BigDecimal price, BigDecimal amount) {
      return new CandleState(
          openTime,
          open,
          high.max(price),
          low.min(price),
          price,
          volume.add(amount));
    }

    RealtimeMarketEvent.Candle toEvent(
        String symbol,
        long eventTime,
        String interval,
        long closeTime,
        long sequence) {
      return new RealtimeMarketEvent.Candle(
          symbol,
          eventTime,
          interval,
          openTime,
          closeTime,
          sequence,
          open,
          high,
          low,
          close,
          volume,
          false);
    }
  }
}
