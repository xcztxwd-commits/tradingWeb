package com.fxplatform.common.websocket;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * MarketWebSocketConfig 是通用基础设施模块的 WebSocket 消息组件。
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class MarketWebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final WebSocketJwtChannelInterceptor jwtChannelInterceptor;

  @Value("${app.cors.allowed-origins:}")
  private String allowedOrigins;

  @Value("${app.cors.allowed-origin-patterns:}")
  private String allowedOriginPatterns;

  /**
   * 发布或处理 configureMessageBroker WebSocket 消息。
   */
  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic", "/queue");
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
  }

  /**
   * 发布或处理 registerStompEndpoints WebSocket 消息。
   */
  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    var endpoint = registry.addEndpoint("/ws");
    String[] origins = csvValues(allowedOrigins);
    if (origins.length > 0) {
      endpoint.setAllowedOrigins(origins);
    }
    String[] originPatterns = csvValues(allowedOriginPatterns);
    if (originPatterns.length > 0) {
      endpoint.setAllowedOriginPatterns(originPatterns);
    }
  }

  /**
   * 发布或处理 configureClientInboundChannel WebSocket 消息。
   */
  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(jwtChannelInterceptor);
  }

  private String[] csvValues(String value) {
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .toArray(String[]::new);
  }
}
