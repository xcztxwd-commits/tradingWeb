package com.fxplatform.common.websocket;

import lombok.RequiredArgsConstructor;
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
    registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
  }

  /**
   * 发布或处理 configureClientInboundChannel WebSocket 消息。
   */
  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(jwtChannelInterceptor);
  }
}
