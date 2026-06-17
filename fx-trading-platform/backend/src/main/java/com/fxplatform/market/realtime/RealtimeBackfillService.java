package com.fxplatform.market.realtime;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.adapter.binance.BinanceSpotMarketDataProvider;
import com.fxplatform.market.repository.RealtimeCandleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RealtimeBackfillService {

  private static final String SOURCE = "binance-rest-backfill";

  private final MarketRealtimeProperties properties;
  private final BinanceSpotMarketDataProvider binanceProvider;
  private final RealtimeCandleRepository realtimeCandleRepository;
  private final Clock clock;
  private final AtomicLong successCount = new AtomicLong();
  private final AtomicLong failureCount = new AtomicLong();

  @Autowired
  public RealtimeBackfillService(
      MarketRealtimeProperties properties,
      BinanceSpotMarketDataProvider binanceProvider,
      RealtimeCandleRepository realtimeCandleRepository
  ) {
    this(properties, binanceProvider, realtimeCandleRepository, Clock.systemUTC());
  }

  RealtimeBackfillService(
      MarketRealtimeProperties properties,
      BinanceSpotMarketDataProvider binanceProvider,
      RealtimeCandleRepository realtimeCandleRepository,
      Clock clock
  ) {
    this.properties = properties;
    this.binanceProvider = binanceProvider;
    this.realtimeCandleRepository = realtimeCandleRepository;
    this.clock = clock;
  }

  public void backfill(Collection<String> symbols) {
    if (symbols == null || symbols.isEmpty()) {
      return;
    }
    for (String symbol : normalizeSymbols(symbols)) {
      for (String interval : klineIntervals()) {
        backfillInterval(symbol, interval);
      }
    }
  }

  public void backfill(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      return;
    }
    backfill(List.of(symbol));
  }

  public long successCount() {
    return successCount.get();
  }

  public long failureCount() {
    return failureCount.get();
  }

  private void backfillInterval(String symbol, String interval) {
    Instant to = clock.instant();
    try {
      Instant from = realtimeCandleRepository.findLastOpenTime(symbol, interval)
          .orElse(to.minus(properties.backfillLookback()));
      List<CandleResponse> candles = binanceProvider.fetchCandles(symbol, symbol, interval, from, to);
      for (CandleResponse candle : candles) {
        realtimeCandleRepository.upsertCandle(
            symbol,
            interval,
            Instant.ofEpochMilli(candle.timestamp()),
            candle.open(),
            candle.high(),
            candle.low(),
            candle.close(),
            candle.volume(),
            SOURCE);
        successCount.incrementAndGet();
      }
    } catch (RuntimeException ex) {
      failureCount.incrementAndGet();
    }
  }

  private List<String> klineIntervals() {
    List<String> intervals = properties.klineIntervals();
    return intervals == null ? List.of() : intervals.stream()
        .filter(interval -> interval != null && !interval.isBlank())
        .distinct()
        .toList();
  }

  private Collection<String> normalizeSymbols(Collection<String> symbols) {
    LinkedHashSet<String> normalizedSymbols = new LinkedHashSet<>();
    for (String symbol : symbols) {
      if (symbol != null && !symbol.isBlank()) {
        normalizedSymbols.add(SymbolNormalizer.normalize(symbol));
      }
    }
    return normalizedSymbols;
  }
}
