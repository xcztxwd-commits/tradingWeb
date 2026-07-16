package com.fxplatform.market.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fxplatform.market.dto.MarketSourceChangedResponse;
import com.fxplatform.market.dto.PerpetualReferenceResponse;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.service.MarketSourceSelectionTracker.MarketSourceChangedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class MarketWsPublisherTest {

  @Test
  void publishesPerpetualReferenceWithOneCompleteSourceBundle() {
    SimpMessagingTemplate messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
    MarketWsPublisher publisher = new MarketWsPublisher(messagingTemplate);
    Instant asOf = Instant.parse("2026-07-13T00:00:00Z");
    Instant expiresAt = Instant.parse("2026-07-13T00:00:05Z");
    PerpetualReferenceResponse reference = new PerpetualReferenceResponse(
        "btc_usdt-perp",
        "BTCUSDT",
        "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("49999"),
        new BigDecimal("50001"),
        new BigDecimal("50000"),
        new BigDecimal("50000.5"),
        new BigDecimal("49998.5"),
        asOf,
        expiresAt,
        false);

    publisher.publishPerpetualReference(reference);

    verify(messagingTemplate).convertAndSend(
        "/topic/market/perp-reference/BTCUSDT-PERP", reference);
  }

  @Test
  void bridgesSourceChangesAsAnExplicitMetadataCompleteEvent() {
    SimpMessagingTemplate messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
    MarketWsPublisher publisher = new MarketWsPublisher(messagingTemplate);
    Instant changedAt = Instant.parse("2026-07-13T00:00:01Z");
    Instant asOf = Instant.parse("2026-07-13T00:00:00Z");
    Instant expiresAt = Instant.parse("2026-07-13T00:00:05Z");
    MarketSourceChangedEvent event = new MarketSourceChangedEvent(
        "btc_usdt-perp",
        "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL,
        "local-perp",
        MarketSourceMode.LOCAL_SIMULATED,
        changedAt,
        asOf,
        expiresAt,
        false);

    publisher.onMarketSourceChanged(event);

    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(messagingTemplate).convertAndSend(
        org.mockito.ArgumentMatchers.eq("/topic/market/source-changes/BTCUSDT-PERP"),
        payload.capture());
    assertThat(payload.getValue()).isEqualTo(new MarketSourceChangedResponse(
        "MARKET_SOURCE_CHANGED",
        "BTCUSDT-PERP",
        "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL,
        "local-perp",
        MarketSourceMode.LOCAL_SIMULATED,
        changedAt,
        asOf,
        expiresAt,
        false));
  }
}
