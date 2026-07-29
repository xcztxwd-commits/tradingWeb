package com.fxplatform.engagement.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InternalRoutePolicyTest {

  private final ContentDocumentSanitizer policy =
      new ContentDocumentSanitizer(new ObjectMapper());

  @Test
  void acceptsOnlyKnownRouteKeysWithTheirSmallParameterSchemas() {
    assertThat(policy.sanitizeRouteParams("WALLET", "{}", "CONTENT_CTA_INVALID"))
        .isEqualTo("{}");
    assertThat(policy.sanitizeRouteParams(
        "TRADE_PERPETUAL", "{\"symbol\":\"BTCUSDT\"}", "CONTENT_CTA_INVALID"))
        .isEqualTo("{\"symbol\":\"BTCUSDT\"}");
    assertThat(policy.sanitizeRouteParams(
        "MESSAGE_CENTER", "{\"filter\":\"UNREAD\"}", "CONTENT_CTA_INVALID"))
        .isEqualTo("{\"filter\":\"UNREAD\"}");
  }

  @ParameterizedTest
  @MethodSource("invalidRoutes")
  void rejectsExternalTraversalUnknownAndUnexpectedRouteParameters(
      String routeKey,
      String params
  ) {
    assertThatThrownBy(() -> policy.sanitizeRouteParams(
            routeKey, params, "CONTENT_CTA_INVALID"))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_CTA_INVALID");
  }

  private static Stream<Arguments> invalidRoutes() {
    return Stream.of(
        Arguments.of("https://evil.example", "{}"),
        Arguments.of("javascript:alert(1)", "{}"),
        Arguments.of("../WALLET", "{}"),
        Arguments.of("UNKNOWN", "{}"),
        Arguments.of("WALLET", "{\"redirect\":\"https://evil.example\"}"),
        Arguments.of("TRADE_SPOT", "{\"symbol\":\"BTC/USDT?<script>\"}"),
        Arguments.of("MESSAGE_CENTER", "{\"filter\":\"../../all\"}"),
        Arguments.of("WALLET", "[]"));
  }
}
