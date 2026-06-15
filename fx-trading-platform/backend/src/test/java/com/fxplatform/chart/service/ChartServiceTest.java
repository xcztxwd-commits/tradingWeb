package com.fxplatform.chart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.chart.entity.CandleEntity;
import com.fxplatform.chart.repository.CandleRepository;
import com.fxplatform.market.adapter.MarketDataProvider;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChartServiceTest {

  @Mock
  private CandleRepository candleRepository;

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private MarketDataProvider marketDataProvider;

  @Test
  void candlesUseProviderRowsBeforeLocalHistoryForForexSymbols() {
    Instant from = Instant.parse("2026-06-12T00:00:00Z");
    Instant to = Instant.parse("2026-06-13T00:00:00Z");
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProviderSymbol("C:EURUSD");
    symbol.setAssetClass("FOREX");
    CandleResponse providerCandle = new CandleResponse(
        1781222400000L,
        new BigDecimal("1.15710"),
        new BigDecimal("1.15740"),
        new BigDecimal("1.15690"),
        new BigDecimal("1.15730"),
        BigDecimal.ZERO);

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol));
    when(marketDataProvider.isConfigured()).thenReturn(true);
    when(marketDataProvider.fetchCandles("EURUSD", "C:EURUSD", "5m", from, to)).thenReturn(List.of(providerCandle));

    ChartService service = new ChartService(candleRepository, symbolRepository, marketDataProvider);

    List<CandleResponse> candles = service.candles("eur-usd", "5m", from, to);

    assertThat(candles).containsExactly(providerCandle);
    verify(marketDataProvider).fetchCandles("EURUSD", "C:EURUSD", "5m", from, to);
  }

  @Test
  void candlesDoNotFallBackToLocalHistoryWhenForexProviderHasNoRows() {
    Instant from = Instant.parse("2026-06-12T00:00:00Z");
    Instant to = Instant.parse("2026-06-13T00:00:00Z");
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProviderSymbol("C:EURUSD");
    symbol.setAssetClass("FOREX");

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol));
    when(marketDataProvider.isConfigured()).thenReturn(true);
    when(marketDataProvider.fetchCandles("EURUSD", "C:EURUSD", "5m", from, to)).thenReturn(List.of());

    ChartService service = new ChartService(candleRepository, symbolRepository, marketDataProvider);

    List<CandleResponse> candles = service.candles("EURUSD", "5m", from, to);

    assertThat(candles).isEmpty();
    verifyNoInteractions(candleRepository);
  }

  @Test
  void candlesDoNotReplaceExternalForexProviderMissWithDemoRows() {
    Instant from = Instant.parse("2026-06-14T12:30:00Z");
    Instant to = Instant.parse("2026-06-14T16:10:00Z");
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProviderSymbol("C:EURUSD");
    symbol.setAssetClass("FOREX");

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol));
    when(marketDataProvider.isConfigured()).thenReturn(true);
    when(marketDataProvider.fetchCandles("EURUSD", "C:EURUSD", "1m", from, to)).thenReturn(List.of());

    ChartService service = new ChartService(candleRepository, symbolRepository, marketDataProvider);

    List<CandleResponse> candles = service.candles("EURUSD", "1m", from, to);

    assertThat(candles).isEmpty();
    verifyNoInteractions(candleRepository);
  }
}
