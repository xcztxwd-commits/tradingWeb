package com.fxplatform.market.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.websocket.MarketWsPublisher;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class QuoteBroadcastServiceTest {

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private QuoteService quoteService;

  @Mock
  private MarketWsPublisher marketWsPublisher;

  @Test
  void skipsBroadcastWhenRealtimeBroadcastIsDisabled() {
    QuoteBroadcastService service = new QuoteBroadcastService(symbolRepository, quoteService, marketWsPublisher);

    service.broadcastLatestQuotes();

    verifyNoInteractions(symbolRepository, quoteService, marketWsPublisher);
  }

  @Test
  void publishesLatestQuoteForEnabledSymbolsWhenRealtimeBroadcastIsEnabled() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    QuoteResponse quote = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.08318"),
        new BigDecimal("1.08322"),
        new BigDecimal("1.08320"),
        new BigDecimal("0.00004"),
        "test",
        1780660000000L);

    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(symbol));
    when(quoteService.latestQuote("EURUSD")).thenReturn(quote);

    QuoteBroadcastService service = new QuoteBroadcastService(symbolRepository, quoteService, marketWsPublisher);
    ReflectionTestUtils.setField(service, "enabled", true);

    service.broadcastLatestQuotes();

    verify(marketWsPublisher).publishQuote(quote);
  }

  @Test
  void publishesLatestQuoteOnlyForConfiguredSymbolsWhenProvided() {
    SymbolEntity eurusd = new SymbolEntity();
    eurusd.setSymbol("EURUSD");
    SymbolEntity usdjpy = new SymbolEntity();
    usdjpy.setSymbol("USDJPY");
    QuoteResponse quote = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.08318"),
        new BigDecimal("1.08322"),
        new BigDecimal("1.08320"),
        new BigDecimal("0.00004"),
        "test",
        1780660000000L);

    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(eurusd, usdjpy));
    when(quoteService.latestQuote("EURUSD")).thenReturn(quote);

    QuoteBroadcastService service = new QuoteBroadcastService(symbolRepository, quoteService, marketWsPublisher);
    ReflectionTestUtils.setField(service, "enabled", true);
    ReflectionTestUtils.setField(service, "symbols", "EURUSD");

    service.broadcastLatestQuotes();

    verify(quoteService).latestQuote("EURUSD");
    verify(quoteService, never()).latestQuote("USDJPY");
    verify(marketWsPublisher).publishQuote(quote);
  }
}
