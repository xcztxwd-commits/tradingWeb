package com.fxplatform.trading.websocket;

import com.fxplatform.trading.dto.response.TradingSessionEventResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TradingWsPublisher {

  private final SimpMessagingTemplate messagingTemplate;

  public void publishAccountEvent(UUID accountId, TradingSessionEventResponse event) {
    if (accountId == null) {
      return;
    }
    messagingTemplate.convertAndSend("/topic/trading/accounts/" + accountId + "/events", event);
  }
}
