package com.fxplatform.trading.websocket;

import com.fxplatform.trading.dto.response.TradingSessionEventResponse;
import com.fxplatform.trading.event.TradingAccountMutationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class TradingWsPublisher {

  private final SimpMessagingTemplate messagingTemplate;

  @TransactionalEventListener(
      phase = TransactionPhase.AFTER_COMMIT,
      fallbackExecution = true)
  public void onTradingAccountMutation(TradingAccountMutationEvent mutation) {
    try {
      TradingSessionEventResponse event = mutation.toResponse();
      messagingTemplate.convertAndSendToUser(
          mutation.userId().toString(),
          "/queue/trading-events",
          event);
    } catch (RuntimeException failure) {
      log.warn(
          "Trading WebSocket delivery failed; the committed mutation remains authoritative ({})",
          failure.getClass().getSimpleName());
    }
  }
}
