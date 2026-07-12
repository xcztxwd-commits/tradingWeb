package com.fxplatform.trading.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.trading.dto.request.CreateOrderRequest.AttachedProtectionRequest;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreateOrderRequestTest {

  @Test
  void normalizesLegacyFieldsToInternalOrderFields() {
    CreateOrderRequest request = new CreateOrderRequest(
        UUID.randomUUID(),
        "EURUSD",
        OrderSide.BUY,
        OrderType.LIMIT,
        new BigDecimal("0.10"),
        new BigDecimal("1.08000"),
        null,
        null,
        "legacy-idem-1",
        null,
        null,
        null);

    assertThat(request.clientOrderId()).isEqualTo("legacy-idem-1");
    assertThat(request.quantity()).isEqualByComparingTo("0.10");
    assertThat(request.price()).isEqualByComparingTo("1.08000");
  }

  @Test
  void keepsNewFieldsWhenBothOldAndNewFieldsArePresent() {
    CreateOrderRequest request = new CreateOrderRequest(
        UUID.randomUUID(),
        "EURUSD",
        OrderSide.BUY,
        OrderType.LIMIT,
        new BigDecimal("0.10"),
        new BigDecimal("1.08000"),
        null,
        null,
        "legacy-idem-1",
        "client-order-1",
        new BigDecimal("0.25"),
        new BigDecimal("1.08100"));

    assertThat(request.clientOrderId()).isEqualTo("client-order-1");
    assertThat(request.quantity()).isEqualByComparingTo("0.25");
    assertThat(request.price()).isEqualByComparingTo("1.08100");
  }

  @Test
  void exposesSpotAndPerpetualOrderInputs() {
    Map<String, String> componentTypes = Arrays.stream(CreateOrderRequest.class.getRecordComponents())
        .collect(java.util.stream.Collectors.toMap(
            RecordComponent::getName,
            component -> component.getType().getName()));

    assertThat(componentTypes)
        .containsEntry("positionSide", "com.fxplatform.trading.enums.PositionSide")
        .containsEntry("quantityUnit", "com.fxplatform.trading.enums.QuantityUnit")
        .containsEntry("marginMode", "com.fxplatform.trading.enums.MarginMode")
        .containsEntry("triggerPrice", BigDecimal.class.getName())
        .containsEntry("triggerPriceType", "com.fxplatform.trading.enums.TriggerPriceType")
        .containsEntry("reduceOnly", Boolean.class.getName())
        .containsEntry("attachedProtections", java.util.List.class.getName());

    RecordComponent attachedProtections = Arrays.stream(CreateOrderRequest.class.getRecordComponents())
        .filter(component -> component.getName().equals("attachedProtections"))
        .findFirst()
        .orElseThrow();
    assertThat(attachedProtections.getGenericType().getTypeName())
        .contains("CreateOrderRequest$AttachedProtectionRequest");
  }

  @Test
  void exposesTypedAttachedProtectionInputs() throws Exception {
    Class<?> type = Class.forName(
        "com.fxplatform.trading.dto.request.CreateOrderRequest$AttachedProtectionRequest");
    Map<String, String> componentTypes = Arrays.stream(type.getRecordComponents())
        .collect(java.util.stream.Collectors.toMap(
            RecordComponent::getName,
            component -> component.getType().getName()));

    assertThat(componentTypes)
        .containsEntry("protectionType", "com.fxplatform.trading.enums.ProtectionType")
        .containsEntry("triggerPrice", BigDecimal.class.getName())
        .containsEntry("triggerPriceType", "com.fxplatform.trading.enums.TriggerPriceType")
        .containsEntry("triggerExecutionType", "com.fxplatform.trading.enums.TriggerExecutionType")
        .containsEntry("price", BigDecimal.class.getName());
  }

  @Test
  void acceptsSpotAndPerpetualOrderValues() {
    AttachedProtectionRequest protection = new AttachedProtectionRequest(
        ProtectionType.STOP_LOSS,
        new BigDecimal("60000"),
        TriggerPriceType.MARK_PRICE,
        TriggerExecutionType.MARKET,
        null);

    CreateOrderRequest request = new CreateOrderRequest(
        UUID.randomUUID(),
        "BTCUSDT-PERP",
        OrderSide.BUY,
        OrderType.STOP_MARKET,
        null,
        null,
        null,
        null,
        "perp-order-1",
        "perp-order-1",
        BigDecimal.ONE,
        null,
        10,
        PositionSide.LONG,
        QuantityUnit.CONTRACTS,
        MarginMode.ISOLATED,
        new BigDecimal("61000"),
        TriggerPriceType.MARK_PRICE,
        true,
        List.of(protection));

    assertThat(request.positionSide()).isEqualTo(PositionSide.LONG);
    assertThat(request.quantityUnit()).isEqualTo(QuantityUnit.CONTRACTS);
    assertThat(request.marginMode()).isEqualTo(MarginMode.ISOLATED);
    assertThat(request.triggerPrice()).isEqualByComparingTo("61000");
    assertThat(request.triggerPriceType()).isEqualTo(TriggerPriceType.MARK_PRICE);
    assertThat(request.reduceOnly()).isTrue();
    assertThat(request.attachedProtections()).containsExactly(protection);
  }

  @Test
  void validatesEachAttachedProtection() {
    AttachedProtectionRequest invalidProtection = new AttachedProtectionRequest(
        null,
        null,
        TriggerPriceType.MARK_PRICE,
        null,
        null);
    CreateOrderRequest request = new CreateOrderRequest(
        UUID.randomUUID(),
        "BTCUSDT-PERP",
        OrderSide.BUY,
        OrderType.MARKET,
        null,
        null,
        null,
        null,
        "perp-order-invalid-protection",
        "perp-order-invalid-protection",
        BigDecimal.ONE,
        null,
        10,
        PositionSide.LONG,
        QuantityUnit.BASE,
        MarginMode.ISOLATED,
        null,
        null,
        false,
        List.of(invalidProtection));

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      Set<ConstraintViolation<CreateOrderRequest>> violations = factory.getValidator()
          .validate(request);

      assertThat(violations)
          .extracting(violation -> violation.getPropertyPath().toString())
          .containsExactlyInAnyOrder(
              "attachedProtections[0].protectionType",
              "attachedProtections[0].triggerPrice",
              "attachedProtections[0].triggerExecutionType");
    }
  }

  @Test
  void acceptsPositiveSpotQuantityBelowGenericOneCentThreshold() {
    CreateOrderRequest request = new CreateOrderRequest(
        UUID.randomUUID(),
        "BTCUSDT",
        OrderSide.SELL,
        OrderType.MARKET,
        null,
        null,
        null,
        null,
        "spot-small-btc",
        "spot-small-btc",
        new BigDecimal("0.0001"),
        null,
        1,
        PositionSide.BOTH,
        QuantityUnit.BASE,
        MarginMode.CASH,
        null,
        null,
        false,
        List.of());

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(request)).isEmpty();
    }
  }
}
