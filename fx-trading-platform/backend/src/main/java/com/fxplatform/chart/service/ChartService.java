package com.fxplatform.chart.service;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.chart.entity.CandleEntity;
import com.fxplatform.chart.repository.CandleRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.provider.MarketDataRouter;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves K-line data through configured market-data bindings.
 */
@Service
@RequiredArgsConstructor
public class ChartService {

  private final CandleRepository candleRepository;
  private final MarketDataRouter marketDataRouter;

  public List<CandleResponse> candles(String symbol, String timeframe, Instant from, Instant to) {
    String normalizedSymbol = SymbolNormalizer.normalize(symbol);
    try {
      List<CandleResponse> providerCandles = marketDataRouter.candles(normalizedSymbol, timeframe, from, to);
      List<CandleEntity> dbCandles = databaseCandleEntities(normalizedSymbol, timeframe, from, to);
      if (!providerCandles.isEmpty()) {
        return mergeRealtimeDatabaseCandles(providerCandles, dbCandles);
      }
      return dbCandles.stream()
          .map(this::toResponse)
          .toList();
    } catch (BusinessException ex) {
      if (!allowsDatabaseFallback(ex.getCode())) {
        throw ex;
      }
      List<CandleResponse> dbCandles = databaseCandles(normalizedSymbol, timeframe, from, to);
      if (!dbCandles.isEmpty()) {
        return dbCandles;
      }
      throw ex;
    }
  }

  private boolean allowsDatabaseFallback(String code) {
    return "MARKET_PROVIDER_BINDING_NOT_FOUND".equals(code)
        || "MARKET_PROVIDER_UNAVAILABLE".equals(code);
  }

  private List<CandleResponse> databaseCandles(String symbol, String timeframe, Instant from, Instant to) {
    return databaseCandleEntities(symbol, timeframe, from, to)
        .stream()
        .map(this::toResponse)
        .toList();
  }

  private List<CandleEntity> databaseCandleEntities(String symbol, String timeframe, Instant from, Instant to) {
    return candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc(symbol, timeframe, from, to);
  }

  private List<CandleResponse> mergeRealtimeDatabaseCandles(
      List<CandleResponse> providerCandles,
      List<CandleEntity> dbCandles) {
    Map<Long, CandleResponse> mergedByTimestamp = new LinkedHashMap<>();
    for (CandleResponse candle : providerCandles) {
      mergedByTimestamp.put(candle.timestamp(), candle);
    }
    for (CandleEntity candle : dbCandles) {
      if (isRealtimeDatabaseCandle(candle)) {
        mergedByTimestamp.put(candle.getOpenTime().toEpochMilli(), toResponse(candle));
      }
    }
    return mergedByTimestamp.values()
        .stream()
        .sorted(Comparator.comparingLong(CandleResponse::timestamp))
        .toList();
  }

  private boolean isRealtimeDatabaseCandle(CandleEntity candle) {
    return "binance-ws-kline".equals(candle.getSource())
        || "binance-rest-backfill".equals(candle.getSource())
        || "demo-realtime".equals(candle.getSource());
  }

  private CandleResponse toResponse(CandleEntity candle) {
    return new CandleResponse(
        candle.getOpenTime().toEpochMilli(),
        candle.getOpen(),
        candle.getHigh(),
        candle.getLow(),
        candle.getClose(),
        candle.getVolume());
  }
}
