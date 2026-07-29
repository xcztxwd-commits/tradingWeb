package com.fxplatform.engagement.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.auth.service.AuthSessionService;
import com.fxplatform.common.security.JwtService;
import com.fxplatform.common.security.TokenRevocationService;
import com.fxplatform.common.websocket.WebSocketJwtChannelInterceptor;
import com.fxplatform.engagement.application.realtime.WakeupPort.Wakeup;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

class EngagementWebSocketAuthorizationTest {

  private final WebSocketJwtChannelInterceptor interceptor = new WebSocketJwtChannelInterceptor(
      mock(JwtService.class),
      mock(UserRepository.class),
      mock(TokenRevocationService.class),
      mock(AuthSessionService.class));
  private final MessageChannel channel = mock(MessageChannel.class);
  private final Principal authenticatedUser = () -> "83ed605f-d6e5-45ef-95d6-281859b75839";

  @Test
  void onlyAuthenticatedUsersMaySubscribeToTheExactEngagementDestinations() {
    assertThatCode(() -> interceptor.preSend(
        subscribe("/topic/engagement/updates", authenticatedUser), channel))
        .doesNotThrowAnyException();
    assertThatCode(() -> interceptor.preSend(
        subscribe("/user/queue/engagement-updates", authenticatedUser), channel))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> interceptor.preSend(
        subscribe("/topic/engagement/updates", null), channel))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> interceptor.preSend(
        subscribe("/user/queue/engagement-updates", null), channel))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void authenticatedUsersCannotBroadenTheEngagementDestinationWhitelist() {
    assertThatThrownBy(() -> interceptor.preSend(
        subscribe("/topic/engagement/updates/other", authenticatedUser), channel))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> interceptor.preSend(
        subscribe("/user/queue/engagement-updates/other", authenticatedUser), channel))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> interceptor.preSend(
        subscribe("/user/another-user/queue/engagement-updates", authenticatedUser), channel))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void simpleBrokerCarriesTheAuthenticatedEngagementTopic() throws Exception {
    String config = Files.readString(Path.of(
        "src/main/java/com/fxplatform/common/websocket/MarketWebSocketConfig.java"));

    assertThat(config).contains("/topic/engagement");
    assertThat(config).contains("/queue");
  }

  @Test
  void allOrdinaryMessageWakeupUsesTheTopicAndOnlyTheMinimalMessageUpdatedPayload() {
    SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    EngagementStompWakeupAdapter adapter = new EngagementStompWakeupAdapter(messagingTemplate);
    UUID aggregateId = UUID.fromString("b3e113bd-a9c7-45fa-b46e-58ab82b9b37f");
    Instant occurredAt = Instant.parse("2026-07-20T03:30:00Z");
    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);

    adapter.publish(new Wakeup(
        "MESSAGE_UPDATED", aggregateId, occurredAt, AudienceType.ALL, Set.of()));

    verify(messagingTemplate).convertAndSend(
        eq("/topic/engagement/updates"), payload.capture());
    verifyNoMoreInteractions(messagingTemplate);
    assertMinimalPayload(payload.getValue(), "MESSAGE_UPDATED", aggregateId, occurredAt);
  }

  @Test
  void selectedWakeupUsesOnlyEachAuthenticatedUserQueue() {
    SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    EngagementStompWakeupAdapter adapter = new EngagementStompWakeupAdapter(messagingTemplate);
    UUID aggregateId = UUID.fromString("765c64d6-fea0-4a50-8d85-97874205c21a");
    UUID firstUser = UUID.fromString("402f4828-2fc1-4ecf-b2c5-38a6eecc0970");
    UUID secondUser = UUID.fromString("c0eb5af5-6fcb-44c2-9783-bac01fbcd852");
    Instant occurredAt = Instant.parse("2026-07-20T03:31:00Z");
    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);

    adapter.publish(new Wakeup(
        "CAMPAIGN_UPDATED",
        aggregateId,
        occurredAt,
        AudienceType.SELECTED,
        Set.of(firstUser, secondUser)));

    verify(messagingTemplate).convertAndSendToUser(
        eq(firstUser.toString()), eq("/queue/engagement-updates"), payload.capture());
    verify(messagingTemplate).convertAndSendToUser(
        eq(secondUser.toString()), eq("/queue/engagement-updates"), payload.capture());
    verifyNoMoreInteractions(messagingTemplate);
    assertThat(payload.getAllValues()).allSatisfy(value ->
        assertMinimalPayload(value, "CAMPAIGN_UPDATED", aggregateId, occurredAt));
  }

  private static void assertMinimalPayload(
      Object payload,
      String updateType,
      UUID aggregateId,
      Instant occurredAt) {
    assertThat(Arrays.stream(payload.getClass().getRecordComponents())
        .map(component -> component.getName()))
        .containsExactly("updateType", "aggregateId", "occurredAt");
    assertThat(payload)
        .hasFieldOrPropertyWithValue("updateType", updateType)
        .hasFieldOrPropertyWithValue("aggregateId", aggregateId)
        .hasFieldOrPropertyWithValue("occurredAt", occurredAt);
  }

  private static Message<byte[]> subscribe(String destination, Principal principal) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setDestination(destination);
    accessor.setUser(principal);
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
