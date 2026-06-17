package com.fxplatform.common.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WebSocketOriginPolicyTest {

  @Test
  void marketWebSocketEndpointDoesNotAllowWildcardOrigins() throws Exception {
    String config = Files.readString(Path.of("src/main/java/com/fxplatform/common/websocket/MarketWebSocketConfig.java"));

    assertThat(config).doesNotContain("setAllowedOriginPatterns(\"*\")");
    assertThat(config).contains("allowedOrigins");
    assertThat(config).contains("allowedOriginPatterns");
  }
}
