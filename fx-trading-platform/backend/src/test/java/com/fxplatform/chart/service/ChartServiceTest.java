package com.fxplatform.chart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.chart.entity.CandleEntity;
import com.fxplatform.chart.repository.CandleRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.provider.MarketDataRouter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChartServiceTest {

  @Mock
  private CandleRepository candleRepository;

  @Mock
  private MarketDataRouter marketDataRouter;

  @Test
  void candlesResolveThroughMarketDataRouter() {
    Instant from = Instant.parse("2026-06-12T00:00:00Z");
    Instant to = Instant.parse("2026-06-13T00:00:00Z");
    CandleResponse providerCandle = new CandleResponse(
        1781222400000L,
        new BigDecimal("1.15710"),
        new BigDecimal("1.15740"),
        new BigDecimal("1.15690"),
        new BigDecimal("1.15730"),
        BigDecimal.ZERO);

    when(marketDataRouter.candles("EURUSD", "5m", from, to)).thenReturn(List.of(providerCandle));
    when(candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc("EURUSD", "5m", from, to))
        .thenReturn(List.of());

    ChartService service = new ChartService(candleRepository, marketDataRouter);

    List<CandleResponse> candles = service.candles("eur-usd", "5m", from, to);

    assertThat(candles).containsExactly(providerCandle);
  }

  @Test
  void p0CandlesNeverOverlayDifferentProviderDatabaseCache() {
    Instant from = Instant.parse("2026-07-12T00:00:00Z");
    Instant to = Instant.parse("2026-07-12T01:00:00Z");
    CandleResponse authoritative = providerCandle(from, "65000");
    when(marketDataRouter.candles("BTCUSDT", "1m", from, to)).thenReturn(List.of(authoritative));

    List<CandleResponse> result = new ChartService(candleRepository, marketDataRouter)
        .candles("BTCUSDT", "1m", from, to);

    assertThat(result).containsExactly(authoritative);
    verifyNoInteractions(candleRepository);
  }

  @Test
  void candlesMergeRealtimeDatabaseCandleOverProviderOverlap() {
    Instant from = Instant.parse("2026-06-12T00:00:00Z");
    Instant firstOpen = Instant.parse("2026-06-12T00:00:00Z");
    Instant secondOpen = Instant.parse("2026-06-12T00:05:00Z");
    Instant to = Instant.parse("2026-06-12T00:10:00Z");
    CandleResponse firstProviderCandle = providerCandle(firstOpen, "1.10000");
    CandleResponse overlappingProviderCandle = providerCandle(secondOpen, "1.20000");
    CandleEntity realtimeCandle = candle(secondOpen);
    realtimeCandle.setClose(new BigDecimal("1.99990"));
    realtimeCandle.setSource("binance-ws-kline");

    when(marketDataRouter.candles("EURUSD", "5m", from, to))
        .thenReturn(List.of(firstProviderCandle, overlappingProviderCandle));
    when(candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc("EURUSD", "5m", from, to))
        .thenReturn(List.of(realtimeCandle));

    ChartService service = new ChartService(candleRepository, marketDataRouter);

    List<CandleResponse> candles = service.candles("eur-usd", "5m", from, to);

    assertThat(candles).hasSize(2);
    assertThat(candles.get(0)).isSameAs(firstProviderCandle);
    assertThat(candles.get(1).timestamp()).isEqualTo(secondOpen.toEpochMilli());
    assertThat(candles.get(1).close()).isEqualByComparingTo("1.99990");
  }

  @Test
  void candlesFallbackToDatabaseWhenProviderReturnsNoCandles() {
    Instant from = Instant.parse("2026-06-12T00:00:00Z");
    Instant openTime = Instant.parse("2026-06-12T00:05:00Z");
    Instant to = Instant.parse("2026-06-12T00:10:00Z");

    when(marketDataRouter.candles("EURUSD", "5m", from, to)).thenReturn(List.of());
    when(candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc("EURUSD", "5m", from, to))
        .thenReturn(List.of(candle(openTime)));

    ChartService service = new ChartService(candleRepository, marketDataRouter);

    List<CandleResponse> candles = service.candles("eur-usd", "5m", from, to);

    assertThat(candles).containsExactly(new CandleResponse(
        openTime.toEpochMilli(),
        new BigDecimal("1.15710"),
        new BigDecimal("1.15740"),
        new BigDecimal("1.15690"),
        new BigDecimal("1.15730"),
        new BigDecimal("42.5")));
  }

  @Test
  void candlesFallbackToDatabaseWhenProviderBindingIsUnavailable() {
    Instant from = Instant.parse("2026-06-12T00:00:00Z");
    Instant openTime = Instant.parse("2026-06-12T00:05:00Z");
    Instant to = Instant.parse("2026-06-12T00:10:00Z");

    when(marketDataRouter.candles("EURUSD", "5m", from, to))
        .thenThrow(new BusinessException("MARKET_PROVIDER_BINDING_NOT_FOUND", "No candle provider"));
    when(candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc("EURUSD", "5m", from, to))
        .thenReturn(List.of(candle(openTime)));

    ChartService service = new ChartService(candleRepository, marketDataRouter);

    List<CandleResponse> candles = service.candles("eur-usd", "5m", from, to);

    assertThat(candles).extracting(CandleResponse::timestamp).containsExactly(openTime.toEpochMilli());
  }

  private CandleEntity candle(Instant openTime) {
    CandleEntity candle = new CandleEntity();
    candle.setOpenTime(openTime);
    candle.setOpen(new BigDecimal("1.15710"));
    candle.setHigh(new BigDecimal("1.15740"));
    candle.setLow(new BigDecimal("1.15690"));
    candle.setClose(new BigDecimal("1.15730"));
    candle.setVolume(new BigDecimal("42.5"));
    return candle;
  }

  private CandleResponse providerCandle(Instant openTime, String close) {
    return new CandleResponse(
        openTime.toEpochMilli(),
        new BigDecimal("1.00000"),
        new BigDecimal("2.00000"),
        new BigDecimal("0.50000"),
        new BigDecimal(close),
        BigDecimal.ZERO);
  }
}
