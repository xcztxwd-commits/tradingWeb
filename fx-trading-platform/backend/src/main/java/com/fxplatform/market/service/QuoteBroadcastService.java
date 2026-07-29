package com.fxplatform.market.service;

import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.websocket.MarketWsPublisher;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Broadcasts normalized platform quotes over WebSocket when a realtime provider is enabled.
 */
@Service
@RequiredArgsConstructor
public class QuoteBroadcastService {

  private final SymbolRepository symbolRepository;
  private final QuoteService quoteService;
  private final MarketWsPublisher marketWsPublisher;

  @Value("${market.quote-broadcast-enabled:false}")
  private boolean enabled;

  @Value("${market.quote-broadcast-symbols:}")
  private String symbols;

  public void broadcastLatestQuotes() {
    if (!enabled) {
      return;
    }
    Set<String> allowedSymbols = MarketSymbolFilter.normalizeSymbols(symbols);
    for (SymbolEntity symbol : symbolRepository.findQuoteBroadcastSymbols()) {
      if (!MarketSymbolFilter.allows(allowedSymbols, symbol.getSymbol())) {
        continue;
      }
      marketWsPublisher.publishQuote(quoteService.latestQuote(symbol.getSymbol()));
    }
  }
}
