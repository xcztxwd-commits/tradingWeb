package com.fxplatform.market.websocket;

import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.dto.MarketSourceChangedResponse;
import com.fxplatform.market.dto.PerpetualReferenceResponse;
import com.fxplatform.market.service.MarketSourceSelectionTracker.MarketSourceChangedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * MarketWsPublisher 是行情模块的 WebSocket 消息组件。
 */
@Component
@RequiredArgsConstructor
public class MarketWsPublisher {

  private final SimpMessagingTemplate messagingTemplate;

  /**
   * 发布或处理 publishQuote WebSocket 消息。
   */
  public void publishQuote(QuoteResponse quote) {
    messagingTemplate.convertAndSend(SymbolNormalizer.quoteTopic(quote.symbol()), quote);
  }

  /**
   * 发布或处理 publishOrderBook WebSocket 消息。
   */
  public void publishOrderBook(MarketDepthResponse depth) {
    messagingTemplate.convertAndSend(SymbolNormalizer.orderBookTopic(depth.symbol()), depth);
  }

  /**
   * 发布或处理 publishRecentTrades WebSocket 消息。
   */
  public void publishRecentTrades(String symbol, List<RecentTradeResponse> trades) {
    messagingTemplate.convertAndSend(SymbolNormalizer.tradesTopic(symbol), trades);
  }

  public void publishPerpetualReference(PerpetualReferenceResponse reference) {
    messagingTemplate.convertAndSend(
        SymbolNormalizer.perpetualReferenceTopic(reference.symbol()),
        reference);
  }

  @EventListener
  public void onMarketSourceChanged(MarketSourceChangedEvent event) {
    MarketSourceChangedResponse response = new MarketSourceChangedResponse(
        "MARKET_SOURCE_CHANGED",
        SymbolNormalizer.normalize(event.platformSymbol()),
        event.previousProviderCode(),
        event.previousSourceMode(),
        event.providerCode(),
        event.sourceMode(),
        event.changedAt(),
        event.asOf(),
        event.expiresAt(),
        event.stale());
    messagingTemplate.convertAndSend(
        SymbolNormalizer.sourceChangesTopic(event.platformSymbol()),
        response);
  }
}
