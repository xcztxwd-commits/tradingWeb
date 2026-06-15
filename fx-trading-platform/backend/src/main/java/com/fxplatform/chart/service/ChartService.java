package com.fxplatform.chart.service;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.chart.entity.CandleEntity;
import com.fxplatform.chart.repository.CandleRepository;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.adapter.MarketDataProvider;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * ChartService 是图表 K 线模块的业务服务。
 */
@Service
@RequiredArgsConstructor
public class ChartService {

  private final CandleRepository candleRepository;
  private final SymbolRepository symbolRepository;
  private final MarketDataProvider marketDataProvider;

  public List<CandleResponse> candles(String symbol, String timeframe, Instant from, Instant to) {
    String normalizedSymbol = SymbolNormalizer.normalize(symbol);
    Optional<SymbolEntity> symbolEntity = symbolRepository.findBySymbol(normalizedSymbol);
    String providerSymbol = symbolEntity.map(SymbolEntity::getProviderSymbol).orElse("C:" + normalizedSymbol);
    boolean providerBackedForex = marketDataProvider.isConfigured() && isForex(symbolEntity, normalizedSymbol);
    if (providerBackedForex) {
      List<CandleResponse> providerCandles = marketDataProvider.fetchCandles(
          normalizedSymbol,
          providerSymbol,
          timeframe,
          from,
          to);
      if (!providerCandles.isEmpty()) {
        return providerCandles;
      }
      return List.of();
    }

    List<CandleEntity> candles = candleRepository
        .findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc(normalizedSymbol, timeframe, from, to);
    if (!candles.isEmpty()) {
      return candles.stream().map(this::toResponse).toList();
    }
    return demoCandles(normalizedSymbol, from);
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

  private List<CandleResponse> demoCandles(String symbol, Instant from) {
    BigDecimal base = symbol.endsWith("JPY") ? new BigDecimal("156.420") : new BigDecimal("1.08320");
    return java.util.stream.IntStream.range(0, 60)
        .mapToObj(index -> {
          BigDecimal drift = new BigDecimal(index).multiply(symbol.endsWith("JPY") ? new BigDecimal("0.003") : new BigDecimal("0.00003"));
          BigDecimal open = base.add(drift);
          return new CandleResponse(
              from.plusSeconds(index * 60L).toEpochMilli(),
              open,
              open.add(symbol.endsWith("JPY") ? new BigDecimal("0.040") : new BigDecimal("0.00040")),
              open.subtract(symbol.endsWith("JPY") ? new BigDecimal("0.035") : new BigDecimal("0.00035")),
              open.add(symbol.endsWith("JPY") ? new BigDecimal("0.008") : new BigDecimal("0.00008")),
              BigDecimal.ZERO);
        })
        .toList();
  }

  private boolean isForex(Optional<SymbolEntity> symbolEntity, String normalizedSymbol) {
    return symbolEntity
        .map(SymbolEntity::getAssetClass)
        .map(value -> "FOREX".equals(value.toUpperCase(Locale.ROOT)))
        .orElse(normalizedSymbol.length() == 6);
  }
}
