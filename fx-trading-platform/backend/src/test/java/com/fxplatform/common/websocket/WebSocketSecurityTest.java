package com.fxplatform.common.websocket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.auth.service.AuthSessionService;
import com.fxplatform.common.security.JwtService;
import com.fxplatform.common.security.JwtTokenClaims;
import com.fxplatform.common.security.TokenRevocationService;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

class WebSocketSecurityTest {

  private final JwtService jwtService = mock(JwtService.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final TokenRevocationService tokenRevocationService = mock(TokenRevocationService.class);
  private final AuthSessionService authSessionService = mock(AuthSessionService.class);
  private final WebSocketJwtChannelInterceptor interceptor = new WebSocketJwtChannelInterceptor(
      jwtService, userRepository, tokenRevocationService, authSessionService);

  @Test
  void authenticatedConnectionsUseTheStableUserIdAsTheUserDestinationName() {
    UUID userId = UUID.randomUUID();
    JwtTokenClaims claims = new JwtTokenClaims(
        userId, UUID.randomUUID(), "jwt-id", "access", Instant.parse("2026-07-14T00:00:00Z"));
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setEmail("trader@example.com");
    user.setStatus(UserStatus.ACTIVE);
    when(jwtService.parseAccessToken("valid-token")).thenReturn(claims);
    when(tokenRevocationService.isAccessTokenRevoked(claims)).thenReturn(false);
    when(authSessionService.isAccessSessionActive(claims)).thenReturn(true);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "Bearer valid-token");
    accessor.setLeaveMutable(true);
    Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    Message<?> authenticated = interceptor.preSend(message, mock(MessageChannel.class));

    org.assertj.core.api.Assertions.assertThat(StompHeaderAccessor.wrap(authenticated).getUser())
        .isNotNull()
        .extracting(Principal::getName)
        .isEqualTo(userId.toString());
  }

  @Test
  void anonymousClientsMaySubscribeToPublicMarketTopics() {
    Message<byte[]> message = subscribe("/topic/market/quotes/BTCUSDT", null);

    assertThatCode(() -> interceptor.preSend(message, mock(MessageChannel.class)))
        .doesNotThrowAnyException();
  }

  @Test
  void anonymousClientsCannotSubscribeToThePrivateTradingQueue() {
    Message<byte[]> message = subscribe("/user/queue/trading-events", null);

    assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void authenticatedClientsMaySubscribeOnlyToTheirResolvedPrivateQueue() {
    Message<byte[]> message = subscribe(
        "/user/queue/trading-events", () -> UUID.randomUUID().toString());

    assertThatCode(() -> interceptor.preSend(message, mock(MessageChannel.class)))
        .doesNotThrowAnyException();
  }

  @Test
  void legacyMutableAccountTopicsAreNeverSubscribable() {
    Message<byte[]> message = subscribe(
        "/topic/trading/accounts/2e81c13a-d59b-4edc-bdc1-bc4aabf81e5f/events",
        () -> "authenticated-user");

    assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void directBrokerQueuesCannotBypassUserDestinationIsolation() {
    Message<byte[]> message = subscribe(
        "/queue/trading-events-session-of-another-user",
        () -> "authenticated-user");

    assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void clientsCannotSpoofMessagesToBrokerDestinations() {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    accessor.setDestination("/topic/market/quotes/BTCUSDT");
    accessor.setUser(() -> UUID.randomUUID().toString());
    accessor.setLeaveMutable(true);
    Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
        .isInstanceOf(AccessDeniedException.class);
  }

  private Message<byte[]> subscribe(String destination, Principal principal) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setDestination(destination);
    accessor.setUser(principal);
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
