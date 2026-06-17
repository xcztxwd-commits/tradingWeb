package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.realtime.RealtimeMarketEvent;
import com.fxplatform.market.realtime.RealtimeQuoteSink;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MarketTestDataServiceTest {

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private RealtimeQuoteSink realtimeQuoteSink;

  @Test
  void normalizesMarketDepthAndRecentTradesSymbols() {
    MarketTestDataService service = service();

    for (String input : List.of("USDJPY", "usd-jpy", "USD_JPY", "USD/JPY")) {
      MarketDepthResponse depth = service.orderBook(input);
      List<RecentTradeResponse> trades = service.recentTrades(input, 1);

      assertThat(depth.symbol()).isEqualTo("USDJPY");
      assertThat(trades).hasSize(1);
      assertThat(trades.get(0).symbol()).isEqualTo("USDJPY");
      assertThat(trades.get(0).id()).startsWith("USDJPY-");
    }
  }

  @Test
  void normalizesRealtimeQuoteTradeAndCandleSymbolsBeforePublishing() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("usd-jpy");
    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(symbol));
    MarketTestDataService service = service();
    ReflectionTestUtils.setField(service, "enabled", true);

    service.publishRealtimeTestData();

    ArgumentCaptor<RealtimeMarketEvent> events = ArgumentCaptor.forClass(RealtimeMarketEvent.class);
    verify(realtimeQuoteSink, times(7)).acceptDemo(events.capture());
    assertThat(events.getAllValues()).allMatch(event -> "USDJPY".equals(event.symbol()));
    assertThat(events.getAllValues()).anyMatch(RealtimeMarketEvent.Quote.class::isInstance);
    assertThat(events.getAllValues()).anyMatch(RealtimeMarketEvent.OrderBook.class::isInstance);
    assertThat(events.getAllValues()).anyMatch(RealtimeMarketEvent.Trade.class::isInstance);
    assertThat(events.getAllValues().stream()
        .filter(RealtimeMarketEvent.Candle.class::isInstance)
        .map(RealtimeMarketEvent.Candle.class::cast)
        .map(RealtimeMarketEvent.Candle::interval)
        .toList())
        .containsExactlyInAnyOrder("1s", "5m", "15m", "1h");
    verifyNoMoreInteractions(realtimeQuoteSink);
  }

  @Test
  void publishesRealtimeTestDataOnlyForConfiguredSymbolsWhenProvided() {
    SymbolEntity eurusd = new SymbolEntity();
    eurusd.setSymbol("EURUSD");
    SymbolEntity usdjpy = new SymbolEntity();
    usdjpy.setSymbol("USDJPY");
    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(eurusd, usdjpy));
    MarketTestDataService service = service();
    ReflectionTestUtils.setField(service, "enabled", true);
    ReflectionTestUtils.setField(service, "symbols", "EURUSD");

    service.publishRealtimeTestData();

    verify(realtimeQuoteSink, times(7)).acceptDemo(argThat(event -> event != null && "EURUSD".equals(event.symbol())));
    verify(realtimeQuoteSink, never()).acceptDemo(argThat(event -> event != null && "USDJPY".equals(event.symbol())));
  }

  private MarketTestDataService service() {
    return new MarketTestDataService(symbolRepository, realtimeQuoteSink);
  }
}
