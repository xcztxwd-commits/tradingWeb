package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationRunEngine.PublicActionType;
import com.fxplatform.validation.service.ValidationRunEngine.PublicAction;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationPublicActionPayloadPolicyTest {

  @Test
  void placeOrderAcceptsOnlyScenarioOwnedOrderFields() {
    Map<String, Object> validPlaceOrder = Map.ofEntries(
        Map.entry("symbol", "BTCUSDT-PERP"),
        Map.entry("side", "BUY"),
        Map.entry("orderType", "LIMIT"),
        Map.entry("quantity", new BigDecimal("0.10000000")),
        Map.entry("price", new BigDecimal("50000.00000000")),
        Map.entry("positionSide", "LONG"),
        Map.entry("quantityUnit", "BASE"),
        Map.entry("marginMode", "CROSS"),
        Map.entry("reduceOnly", false),
        Map.entry("timeInForce", "GTC"),
        Map.entry("postOnly", false),
        Map.entry("attachedProtections", List.of(Map.of(
            "protectionType", "STOP_LOSS",
            "triggerPrice", new BigDecimal("49000.00000000"),
            "triggerExecutionType", "MARKET"))));
    assertThatCode(() -> ValidationPublicActionPayloadPolicy.validate(
        PublicActionType.PLACE_ORDER,
        validPlaceOrder))
        .doesNotThrowAnyException();

    for (String serverOwned : List.of("accountId", "idempotencyKey", "clientOrderId")) {
      assertInvalid(
          PublicActionType.PLACE_ORDER,
          Map.of(
              "symbol", "BTCUSDT-PERP",
              "side", "BUY",
              "orderType", "MARKET",
              "quantity", BigDecimal.ONE,
              serverOwned, "scenario-controlled"));
    }
  }

  @Test
  void scaledZeroBigDecimalRemainsAnExactDecimalPayload() {
    assertValid(
        PublicActionType.PLACE_ORDER,
        Map.of(
            "symbol", "BTCUSDT",
            "side", "BUY",
            "orderType", "MARKET",
            "quantity", new BigDecimal("0.00000000")));
  }

  @Test
  void everyActionUsesAStrictTopLevelAllowlistAndRequiredFields() {
    assertInvalid(
        PublicActionType.PLACE_ORDER,
        Map.of("symbol", "BTCUSDT-PERP", "side", "BUY", "orderType", "MARKET"));
    assertInvalid(
        PublicActionType.PLACE_ORDER,
        Map.of(
            "symbol", "BTCUSDT-PERP",
            "side", "BUY",
            "orderType", "MARKET",
            "quantity", BigDecimal.ONE,
            "unexpected", true));

    assertValid(PublicActionType.CANCEL_ORDER, Map.of("clientOrderId", "scenario-order-1"));
    assertInvalid(PublicActionType.CANCEL_ORDER, Map.of("orderId", "00000000-0000-0000-0000-000000000001"));
    assertInvalid(
        PublicActionType.CANCEL_ORDER,
        Map.of("clientOrderId", "scenario-order-1", "symbol", "BTCUSDT-PERP"));

    assertValid(PublicActionType.CANCEL_ALL, Map.of());
    assertInvalid(PublicActionType.CANCEL_ALL, Map.of("accountId", "scenario-account"));

    assertValid(PublicActionType.SET_POSITION_MODE, Map.of("positionMode", "HEDGE"));
    assertInvalid(PublicActionType.SET_POSITION_MODE, Map.of());
    assertInvalid(
        PublicActionType.SET_POSITION_MODE,
        Map.of("positionMode", "HEDGE", "symbol", "BTCUSDT-PERP"));

    assertValid(
        PublicActionType.SET_MARGIN_MODE,
        Map.of("symbol", "BTCUSDT-PERP", "marginMode", "ISOLATED"));
    assertInvalid(PublicActionType.SET_MARGIN_MODE, Map.of("symbol", "BTCUSDT-PERP"));

    assertValid(
        PublicActionType.SET_LEVERAGE,
        Map.of("symbol", "BTCUSDT-PERP", "leverage", 20));
    assertInvalid(PublicActionType.SET_LEVERAGE, Map.of("leverage", 20));
  }

  @Test
  void networkControlAndSensitiveKeysAreRejectedRecursively() {
    for (String forbidden : List.of(
        "url",
        "uri",
        "path",
        "method",
        "header",
        "headers",
        "command",
        "password",
        "secret",
        "token",
        "credential",
        "apiKey",
        "cookie",
        "authorization",
        "access_token",
        "callbackUrl")) {
      assertInvalid(
          PublicActionType.PLACE_ORDER,
          Map.of(
              "symbol", "BTCUSDT-PERP",
              "side", "BUY",
              "orderType", "MARKET",
              "quantity", BigDecimal.ONE,
              "attachedProtections", List.of(Map.of(
                  "protectionType", "STOP_LOSS",
                  "triggerPrice", new BigDecimal("49000"),
                  forbidden, "must-never-be-persisted"))));
    }
  }

  @Test
  void attachedProtectionsUseAnExactBoundedSchema() {
    Map<String, Object> base = Map.of(
        "symbol", "BTCUSDT-PERP",
        "side", "BUY",
        "orderType", "MARKET",
        "quantity", BigDecimal.ONE);

    assertInvalid(PublicActionType.PLACE_ORDER, withProtections(base, List.of(Map.of(
        "protectionType", "STOP_LOSS",
        "triggerPrice", new BigDecimal("49000"),
        "triggerExecutionType", "MARKET",
        "harmlessButUnknown", true))));
    assertInvalid(PublicActionType.PLACE_ORDER, withProtections(base, Map.of(
        "protectionType", "STOP_LOSS")));
    assertInvalid(PublicActionType.PLACE_ORDER, withProtections(base, List.of(Map.of(
        "protectionType", "STOP_LOSS",
        "triggerPrice", new BigDecimal("49000")))));
    assertInvalid(PublicActionType.PLACE_ORDER, withProtections(base, List.of(Map.of(
        "protectionType", "STOP_LOSS",
        "triggerPrice", "not-a-number",
        "triggerExecutionType", "MARKET"))));
  }

  @Test
  void validatedPayloadIsDeeplyFrozen() {
    java.util.LinkedHashMap<String, Object> protection = new java.util.LinkedHashMap<>();
    protection.put("protectionType", "STOP_LOSS");
    protection.put("triggerPrice", new BigDecimal("49000"));
    protection.put("triggerExecutionType", "MARKET");
    java.util.ArrayList<Object> protections = new java.util.ArrayList<>(List.of(protection));
    java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("symbol", "BTCUSDT-PERP");
    payload.put("side", "BUY");
    payload.put("orderType", "MARKET");
    payload.put("quantity", BigDecimal.ONE);
    payload.put("attachedProtections", protections);

    PublicAction action = new PublicAction(
        java.util.UUID.fromString("00000000-0000-0000-0000-000000000584"),
        1L,
        1L,
        PublicActionType.PLACE_ORDER,
        "deep-freeze",
        payload);
    protection.put("triggerPrice", BigDecimal.ZERO);
    protections.clear();

    assertThatThrownBy(() -> action.payload().put("symbol", "ETHUSDT-PERP"))
        .isInstanceOf(UnsupportedOperationException.class);
    List<?> frozenProtections = (List<?>) action.payload().get("attachedProtections");
    assertThatThrownBy(() -> ((List<Object>) frozenProtections).clear())
        .isInstanceOf(UnsupportedOperationException.class);
    org.assertj.core.api.Assertions.assertThat(frozenProtections).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(
        ((Map<?, ?>) frozenProtections.getFirst()).get("triggerPrice"))
        .isEqualTo(new BigDecimal("49000"));
  }

  @Test
  void requiredTextCannotBeNullOrBlank() {
    assertInvalid(PublicActionType.CANCEL_ORDER, Map.of());
    assertInvalid(PublicActionType.CANCEL_ORDER, Map.of("clientOrderId", "  "));
    assertInvalid(PublicActionType.SET_POSITION_MODE, Map.of("positionMode", ""));
    assertInvalid(
        PublicActionType.SET_MARGIN_MODE,
        Map.of("symbol", "BTCUSDT-PERP", "marginMode", " "));
  }

  @Test
  void loopbackValidatesBeforeSessionRecoveryAndCancelsByLogicalClientOrderId() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/com/fxplatform/validation/service/JdkValidationLoopbackHttpClient.java"));
    String publicAction = source.substring(
        source.indexOf("private HttpResult publicAction"),
        source.indexOf("private RawResponse updateSymbolSettings"));
    String lookupAction = source.substring(
        source.indexOf("private LookupResult lookupPublicAction"),
        source.indexOf("private LookupResult lookupCreatedOrder"));

    org.assertj.core.api.Assertions.assertThat(publicAction.indexOf("validatedPublicAction(command)"))
        .isGreaterThanOrEqualTo(0)
        .isLessThan(publicAction.indexOf("requireSession("));
    org.assertj.core.api.Assertions.assertThat(lookupAction.indexOf("validatedPublicAction(command)"))
        .isGreaterThanOrEqualTo(0)
        .isLessThan(lookupAction.indexOf("requireSession("));
    org.assertj.core.api.Assertions.assertThat(source)
        .contains("orderByClientOrderIdPath(")
        .contains("requiredText(payload, \"clientOrderId\")")
        .contains("data.path(\"id\")")
        .doesNotContain("requiredUuid(payload, \"orderId\")");
  }

  private static void assertValid(PublicActionType type, Map<String, Object> payload) {
    assertThatCode(() -> ValidationPublicActionPayloadPolicy.validate(type, payload))
        .doesNotThrowAnyException();
  }

  private static void assertInvalid(PublicActionType type, Map<String, Object> payload) {
    assertThatThrownBy(() -> ValidationPublicActionPayloadPolicy.validate(type, payload))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            org.assertj.core.api.Assertions.assertThat(exception.getCode())
                .isEqualTo("VALIDATION_ACTION_INVALID"));
  }

  private static Map<String, Object> withProtections(
      Map<String, Object> base,
      Object protections
  ) {
    java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>(base);
    payload.put("attachedProtections", protections);
    return payload;
  }
}
