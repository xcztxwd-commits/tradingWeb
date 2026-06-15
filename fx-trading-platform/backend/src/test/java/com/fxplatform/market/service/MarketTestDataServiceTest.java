package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.RealtimeCandleRepository;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.websocket.MarketWsPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MarketTestDataServiceTest {

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private QuoteService quoteService;

  @Mock
  private MarketWsPublisher marketWsPublisher;

  @Mock
  private RealtimeCandleRepository realtimeCandleRepository;

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

    verify(quoteService).cache(argThat(quote -> "USDJPY".equals(quote.symbol())));
    verify(marketWsPublisher).publishQuote(argThat(quote -> "USDJPY".equals(quote.symbol())));
    verify(marketWsPublisher).publishOrderBook(argThat(depth -> "USDJPY".equals(depth.symbol())));
    verify(marketWsPublisher).publishRecentTrades(
        eq("USDJPY"),
        argThat(trades -> !trades.isEmpty() && "USDJPY".equals(trades.get(0).symbol())));
    verify(realtimeCandleRepository).upsert(eq("USDJPY"), eq("1s"), any(Instant.class), any(BigDecimal.class), any(BigDecimal.class));
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

    verify(marketWsPublisher).publishQuote(argThat(quote -> quote != null && "EURUSD".equals(quote.symbol())));
    verify(marketWsPublisher, never()).publishQuote(argThat(quote -> quote != null && "USDJPY".equals(quote.symbol())));
    verify(realtimeCandleRepository).upsert(eq("EURUSD"), eq("1s"), any(Instant.class), any(BigDecimal.class), any(BigDecimal.class));
    verify(realtimeCandleRepository, never()).upsert(eq("USDJPY"), any(), any(Instant.class), any(BigDecimal.class), any(BigDecimal.class));
  }

  private MarketTestDataService service() {
    return new MarketTestDataService(symbolRepository, quoteService, marketWsPublisher, realtimeCandleRepository);
  }
}
