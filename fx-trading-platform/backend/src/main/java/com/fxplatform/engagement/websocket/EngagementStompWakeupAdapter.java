package com.fxplatform.engagement.websocket;

import com.fxplatform.engagement.application.realtime.WakeupPort;
import com.fxplatform.engagement.application.realtime.WakeupPort.Wakeup;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EngagementStompWakeupAdapter implements WakeupPort {

  private static final String ALL_UPDATES_TOPIC = "/topic/engagement/updates";
  private static final String USER_UPDATES_QUEUE = "/queue/engagement-updates";

  private final SimpMessagingTemplate messagingTemplate;

  @Override
  public void publish(Wakeup wakeup) {
    WakeupPayload payload = new WakeupPayload(
        wakeup.updateType(), wakeup.aggregateId(), wakeup.occurredAt());
    switch (wakeup.audienceType()) {
      case ALL -> messagingTemplate.convertAndSend(ALL_UPDATES_TOPIC, payload);
      case SELECTED -> wakeup.targetUserIds().forEach(userId ->
          messagingTemplate.convertAndSendToUser(
              userId.toString(), USER_UPDATES_QUEUE, payload));
    }
  }

  public record WakeupPayload(
      String updateType,
      UUID aggregateId,
      Instant occurredAt
  ) {
  }
}
