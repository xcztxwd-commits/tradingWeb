package com.fxplatform.common.websocket;

import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.auth.service.AuthSessionService;
import com.fxplatform.common.security.JwtService;
import com.fxplatform.common.security.JwtTokenClaims;
import com.fxplatform.common.security.TokenRevocationService;
import com.fxplatform.common.security.UserPrincipal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * WebSocketJwtChannelInterceptor 是通用基础设施模块的 WebSocket 消息组件。
 */
@Component
@RequiredArgsConstructor
public class WebSocketJwtChannelInterceptor implements ChannelInterceptor {

  private final JwtService jwtService;
  private final UserRepository userRepository;
  private final TokenRevocationService tokenRevocationService;
  private final AuthSessionService authSessionService;

  /**
   * 发布或处理 preSend WebSocket 消息。
   */
  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
      String token = bearerToken(accessor.getNativeHeader("Authorization"));
      JwtTokenClaims claims = parseAccessToken(token);
      if (claims != null && !tokenRevocationService.isAccessTokenRevoked(claims) && authSessionService.isAccessSessionActive(claims)) {
        userRepository.findById(claims.userId())
            .filter(user -> user.getStatus() == UserStatus.ACTIVE)
            .ifPresent(user -> {
              UserPrincipal principal = UserPrincipal.from(user);
              accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
            });
      }
    }
    return message;
  }

  /**
   * 发布或处理 bearerToken WebSocket 消息。
   */
  private String bearerToken(List<String> authorization) {
    if (authorization == null || authorization.isEmpty()) {
      return null;
    }
    String header = authorization.getFirst();
    return header.startsWith("Bearer ") ? header.substring(7) : null;
  }

  private JwtTokenClaims parseAccessToken(String token) {
    if (token == null) {
      return null;
    }
    try {
      return jwtService.parseAccessToken(token);
    } catch (RuntimeException ex) {
      return null;
    }
  }
}
