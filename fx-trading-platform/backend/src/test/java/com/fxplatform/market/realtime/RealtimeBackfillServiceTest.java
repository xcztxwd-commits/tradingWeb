package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.adapter.binance.BinanceSpotMarketDataProvider;
import com.fxplatform.market.repository.RealtimeCandleRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RealtimeBackfillServiceTest {

  private static final Instant NOW = Instant.parse("2026-06-17T00:00:00Z");

  private final MarketRealtimeProperties properties = new MarketRealtimeProperties();

  @Mock
  private BinanceSpotMarketDataProvider binanceProvider;

  @Mock
  private RealtimeCandleRepository candleRepository;

  @Test
  void noExistingCandleStartsAtLookbackWindow() {
    properties.setKlineIntervals(List.of("1m"));
    properties.setBackfillLookback(Duration.ofMinutes(30));
    when(candleRepository.findLastOpenTime("BTCUSDT", "1m")).thenReturn(Optional.empty());
    when(binanceProvider.fetchCandles(eq("BTCUSDT"), eq("BTCUSDT"), eq("1m"), any(), eq(NOW)))
        .thenReturn(List.of(candle(1000L)));
    RealtimeBackfillService service = service();

    service.backfill("btc-usdt");

    verify(binanceProvider).fetchCandles(
        "BTCUSDT",
        "BTCUSDT",
        "1m",
        NOW.minus(Duration.ofMinutes(30)),
        NOW);
    assertThat(service.successCount()).isEqualTo(1L);
  }

  @Test
  void existingCandleStartsAtLastOpenTime() {
    properties.setKlineIntervals(List.of("1m"));
    Instant lastOpenTime = NOW.minus(Duration.ofMinutes(5));
    when(candleRepository.findLastOpenTime("BTCUSDT", "1m")).thenReturn(Optional.of(lastOpenTime));
    when(binanceProvider.fetchCandles(eq("BTCUSDT"), eq("BTCUSDT"), eq("1m"), eq(lastOpenTime), eq(NOW)))
        .thenReturn(List.of(candle(lastOpenTime.toEpochMilli())));

    service().backfill("BTCUSDT");

    verify(binanceProvider).fetchCandles("BTCUSDT", "BTCUSDT", "1m", lastOpenTime, NOW);
  }

  @Test
  void emptyFetchDoesNotWriteOrFail() {
    properties.setKlineIntervals(List.of("1m"));
    when(candleRepository.findLastOpenTime("BTCUSDT", "1m")).thenReturn(Optional.empty());
    when(binanceProvider.fetchCandles(eq("BTCUSDT"), eq("BTCUSDT"), eq("1m"), any(), eq(NOW)))
        .thenReturn(List.of());
    RealtimeBackfillService service = service();

    service.backfill("BTCUSDT");

    verify(candleRepository, never()).upsertCandle(any(), any(), any(), any(), any(), any(), any(), any(), any());
    assertThat(service.successCount()).isZero();
    assertThat(service.failureCount()).isZero();
  }

  @Test
  void multipleIntervalsFetchRestPerInterval() {
    properties.setKlineIntervals(List.of("1m", "5m"));
    when(candleRepository.findLastOpenTime(eq("BTCUSDT"), any())).thenReturn(Optional.empty());
    when(binanceProvider.fetchCandles(eq("BTCUSDT"), eq("BTCUSDT"), any(), any(), eq(NOW)))
        .thenReturn(List.of());

    service().backfill(List.of("BTCUSDT"));

    verify(binanceProvider).fetchCandles(eq("BTCUSDT"), eq("BTCUSDT"), eq("1m"), any(), eq(NOW));
    verify(binanceProvider).fetchCandles(eq("BTCUSDT"), eq("BTCUSDT"), eq("5m"), any(), eq(NOW));
  }

  @Test
  void restFailureIncrementsFailureAndContinuesNextSymbol() {
    properties.setKlineIntervals(List.of("1m"));
    when(candleRepository.findLastOpenTime(any(), eq("1m"))).thenReturn(Optional.empty());
    when(binanceProvider.fetchCandles(eq("BTCUSDT"), eq("BTCUSDT"), eq("1m"), any(), eq(NOW)))
        .thenThrow(new RuntimeException("rest down"));
    when(binanceProvider.fetchCandles(eq("ETHUSDT"), eq("ETHUSDT"), eq("1m"), any(), eq(NOW)))
        .thenReturn(List.of(candle(1000L)));
    RealtimeBackfillService service = service();

    service.backfill(List.of("BTCUSDT", "ETHUSDT"));

    assertThat(service.failureCount()).isEqualTo(1L);
    assertThat(service.successCount()).isEqualTo(1L);
    verify(candleRepository).upsertCandle(
        eq("ETHUSDT"),
        eq("1m"),
        eq(Instant.ofEpochMilli(1000L)),
        any(),
        any(),
        any(),
        any(),
        any(),
        eq("binance-rest-backfill"));
  }

  @Test
  void fetchedCandlesAreWrittenThroughFullOhlcvUpsert() {
    properties.setKlineIntervals(List.of("1m"));
    CandleResponse candle = candle(1000L);
    when(candleRepository.findLastOpenTime("BTCUSDT", "1m")).thenReturn(Optional.empty());
    when(binanceProvider.fetchCandles(eq("BTCUSDT"), eq("BTCUSDT"), eq("1m"), any(), eq(NOW)))
        .thenReturn(List.of(candle));
    RealtimeBackfillService service = service();

    service.backfill("BTCUSDT");

    ArgumentCaptor<BigDecimal> volumeCaptor = ArgumentCaptor.forClass(BigDecimal.class);
    verify(candleRepository).upsertCandle(
        eq("BTCUSDT"),
        eq("1m"),
        eq(Instant.ofEpochMilli(1000L)),
        eq(candle.open()),
        eq(candle.high()),
        eq(candle.low()),
        eq(candle.close()),
        volumeCaptor.capture(),
        eq("binance-rest-backfill"));
    assertThat(volumeCaptor.getValue()).isEqualByComparingTo(candle.volume());
    assertThat(service.successCount()).isEqualTo(1L);
  }

  private RealtimeBackfillService service() {
    return new RealtimeBackfillService(
        properties,
        binanceProvider,
        candleRepository,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private CandleResponse candle(long timestamp) {
    return new CandleResponse(
        timestamp,
        new BigDecimal("1.00"),
        new BigDecimal("2.00"),
        new BigDecimal("0.50"),
        new BigDecimal("1.50"),
        new BigDecimal("12.30"));
  }
}
