package com.fxplatform.validation.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import com.fxplatform.validation.service.ValidationMarketState.FundingRatePoint;
import com.fxplatform.validation.service.ValidationRunEngine.ExpectedHttpError;
import com.fxplatform.validation.service.ValidationRunEngine.PublicAction;
import com.fxplatform.validation.service.ValidationRunEngine.PublicActionType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidationCanonicalJsonTest {

  @Test
  void postgresJsonbNumericScaleDoesNotBreakExactSemanticReplay() throws Exception {
    Class<?> type = Class.forName(
        "com.fxplatform.validation.persistence.ValidationCanonicalJson");
    Constructor<?> constructor = type.getDeclaredConstructor(ObjectMapper.class);
    constructor.setAccessible(true);
    Object canonicalJson = constructor.newInstance(new ObjectMapper());
    Method equivalent = type.getDeclaredMethod("equivalent", Object.class, Object.class);
    equivalent.setAccessible(true);

    assertThat((boolean) equivalent.invoke(
        canonicalJson,
        Map.of("speedMultiplier", new BigDecimal("1.000000")),
        Map.of("speedMultiplier", BigDecimal.ONE)))
        .isTrue();
    assertThat((boolean) equivalent.invoke(
        canonicalJson,
        Map.of("speedMultiplier", BigDecimal.ONE),
        Map.of("speedMultiplier", new BigDecimal("1.000001"))))
        .isFalse();
  }

  @Test
  void serializedNanosecondInstantsRetainExactNumericPrecision() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    Class<?> type = Class.forName(
        "com.fxplatform.validation.persistence.ValidationCanonicalJson");
    Constructor<?> constructor = type.getDeclaredConstructor(ObjectMapper.class);
    constructor.setAccessible(true);
    Object canonicalJson = constructor.newInstance(objectMapper);
    Method equivalent = type.getDeclaredMethod("equivalent", Object.class, Object.class);
    equivalent.setAccessible(true);
    Instant instant = Instant.parse("2026-07-23T06:00:01.123456789Z");
    String stored = objectMapper.writeValueAsString(Map.of("asOf", instant));

    assertThat((boolean) equivalent.invoke(
        canonicalJson,
        stored,
        Map.of("asOf", instant)))
        .isTrue();
    assertThat((boolean) equivalent.invoke(
        canonicalJson,
        stored,
        Map.of("asOf", instant.plusNanos(1))))
        .isFalse();
  }

  @Test
  void newOptionalFundingAndExpectedErrorFieldsPreserveTheLegacyDurableJsonShape()
      throws Exception {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    ValidationCanonicalJson canonicalJson = new ValidationCanonicalJson(objectMapper);
    CompositeTick legacyTick = new CompositeTick(
        UUID.fromString("00000000-0000-0000-0000-000000000597"),
        7L,
        1L,
        Instant.parse("2030-01-01T00:00:01Z"),
        "legacy-tick",
        List.of(),
        List.of());
    PublicAction legacyAction = new PublicAction(
        UUID.fromString("00000000-0000-0000-0000-000000000598"),
        1L,
        1L,
        PublicActionType.PLACE_ORDER,
        "legacy-action",
        Map.of(
            "symbol", "BTCUSDT",
            "side", "BUY",
            "orderType", "MARKET",
            "quantity", "0.01"));

    ObjectNode oldTickJson = objectMapper.valueToTree(legacyTick);
    oldTickJson.remove("fundingRates");
    ObjectNode oldActionJson = objectMapper.valueToTree(legacyAction);
    oldActionJson.remove("expectedError");

    assertThat(canonicalJson.equivalent(oldTickJson.toString(), legacyTick)).isTrue();
    assertThat(canonicalJson.equivalent(oldActionJson.toString(), legacyAction)).isTrue();

    CompositeTick fundedTick = new CompositeTick(
        legacyTick.runId(),
        legacyTick.generation(),
        legacyTick.sequence(),
        legacyTick.virtualTime(),
        legacyTick.fingerprint(),
        List.of(),
        List.of(),
        List.of(new FundingRatePoint(
            "BTCUSDT-PERP",
            new BigDecimal("0.0001000000"))));
    PublicAction expectedErrorAction = new PublicAction(
        legacyAction.actionId(),
        legacyAction.tickSequence(),
        legacyAction.sequence(),
        legacyAction.type(),
        legacyAction.requestFingerprint(),
        legacyAction.payload(),
        new ExpectedHttpError(400, "ORDER_REJECTED"));

    assertThat(objectMapper.valueToTree(fundedTick).has("fundingRates")).isTrue();
    assertThat(objectMapper.valueToTree(expectedErrorAction).has("expectedError")).isTrue();
    assertThat(canonicalJson.equivalent(oldTickJson.toString(), fundedTick)).isFalse();
    assertThat(canonicalJson.equivalent(oldActionJson.toString(), expectedErrorAction)).isFalse();
  }
}
