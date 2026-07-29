package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.request.UpdateOrderRequest;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerPriceType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdvancedOrderContractTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void exposes_platform_advanced_order_contract() {
    assertThat(OrderType.values())
        .contains(OrderType.STOP_LIMIT, OrderType.TRAILING_STOP_MARKET);
    assertThat(TimeInForce.values())
        .contains(TimeInForce.GTC, TimeInForce.IOC, TimeInForce.FOK);
  }

  @Test
  void trailing_order_requires_exactly_one_callback_contract() {
    CreateOrderRequest invalid = Requests.trailing(null, null);
    Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(invalid);
    assertThat(violations)
        .extracting(ConstraintViolation::getMessage)
        .contains("trailingDelta or trailingRate is required, but not both");
  }

  @Test
  void normalizes_defaults_and_validates_stop_limit_and_post_only_contracts() {
    CreateOrderRequest defaults = Requests.order(
        OrderType.LIMIT, null, null, null, null, null, null);
    CreateOrderRequest invalidStopLimit = Requests.order(
        OrderType.STOP_LIMIT, TimeInForce.GTC, false, null, null, null, null);
    CreateOrderRequest invalidPostOnly = Requests.order(
        OrderType.LIMIT, TimeInForce.IOC, true, null, null, null, null);

    assertThat(defaults.timeInForce()).isEqualTo(TimeInForce.GTC);
    assertThat(defaults.postOnly()).isFalse();
    assertThat(validator.validate(invalidStopLimit))
        .extracting(ConstraintViolation::getMessage)
        .contains("STOP_LIMIT requires triggerPrice and price");
    assertThat(validator.validate(invalidPostOnly))
        .extracting(ConstraintViolation::getMessage)
        .contains("postOnly requires LIMIT and GTC");
  }

  @Test
  void allows_simple_advanced_and_valid_trailing_execution_but_rejects_misplaced_fields() {
    List<CreateOrderRequest> supported = List.of(
        Requests.order(
            OrderType.STOP_LIMIT, TimeInForce.GTC, false,
            BigDecimal.ONE, null, null, null),
        Requests.order(
            OrderType.LIMIT, TimeInForce.IOC, false,
            null, null, null, null),
        Requests.order(
            OrderType.LIMIT, TimeInForce.FOK, false,
            null, null, null, null),
        Requests.order(
            OrderType.LIMIT, TimeInForce.GTC, true,
            null, null, null, null),
        Requests.trailing(BigDecimal.ONE, null));
    supported.forEach(request ->
        assertThatCode(() -> OrderService.requireAdvancedOrderExecutionSupported(request))
            .doesNotThrowAnyException());

    List<CreateOrderRequest> misplacedTrailingFields = List.of(
        Requests.order(
            OrderType.LIMIT, TimeInForce.GTC, false,
            null, BigDecimal.ONE, null, null),
        Requests.order(
            OrderType.LIMIT, TimeInForce.GTC, false,
            null, null, BigDecimal.ONE, null),
        Requests.order(
            OrderType.LIMIT, TimeInForce.GTC, false,
            null, null, null, BigDecimal.ONE));
    misplacedTrailingFields.forEach(request ->
        assertThatThrownBy(() -> OrderService.requireAdvancedOrderExecutionSupported(request))
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("INVALID_ORDER_TYPE")));

    CreateOrderRequest malformedTrailing = Requests.order(
        OrderType.TRAILING_STOP_MARKET,
        TimeInForce.GTC,
        false,
        new BigDecimal("60000"),
        BigDecimal.ONE,
        null,
        new BigDecimal("59000"));
    assertThatThrownBy(() -> OrderService.requireAdvancedOrderExecutionSupported(malformedTrailing))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("INVALID_PERPETUAL_ORDER_FIELDS"));
  }

  @Test
  void update_order_exposes_trigger_price_without_breaking_the_four_field_constructor() {
    UpdateOrderRequest stopLimitUpdate = new UpdateOrderRequest(
        BigDecimal.ONE,
        new BigDecimal("100"),
        new BigDecimal("90"),
        null,
        null);
    UpdateOrderRequest legacyUpdate = new UpdateOrderRequest(
        BigDecimal.ONE,
        new BigDecimal("100"),
        null,
        null);

    assertThat(stopLimitUpdate.triggerPrice()).isEqualByComparingTo("90");
    assertThat(legacyUpdate.triggerPrice()).isNull();
  }

  @Test
  void propagates_advanced_fields_and_fingerprints_execution_semantics() {
    CreateOrderRequest request = Requests.order(
        OrderType.TRAILING_STOP_MARKET,
        TimeInForce.FOK,
        false,
        new BigDecimal("60000"),
        new BigDecimal("100"),
        null,
        new BigDecimal("59000"));
    UserPrincipal principal = new UserPrincipal(
        UUID.randomUUID(), "trader@example.com", "TRADER");

    OrderCommand command = new OrderCommandFactory().from(principal, request);
    OrderEntity entity = new OrderEntityFactory().createReceived(command);
    entity.setTrailingExtreme(new BigDecimal("60500"));
    OrderResponse response = new OrderResponseMapper().toResponse(entity);

    assertThat(command.timeInForce()).isEqualTo(TimeInForce.FOK);
    assertThat(command.postOnly()).isFalse();
    assertThat(command.activationPrice()).isEqualByComparingTo("59000");
    assertThat(command.trailingDelta()).isEqualByComparingTo("100");
    assertThat(command.trailingRate()).isNull();
    assertThat(entity.getTimeInForce()).isEqualTo(TimeInForce.FOK);
    assertThat(entity.getPostOnly()).isFalse();
    assertThat(entity.getActivationPrice()).isEqualByComparingTo("59000");
    assertThat(entity.getTrailingDelta()).isEqualByComparingTo("100");
    assertThat(entity.getTrailingRate()).isNull();
    assertThat(response.timeInForce()).isEqualTo(TimeInForce.FOK);
    assertThat(response.postOnly()).isFalse();
    assertThat(response.activationPrice()).isEqualByComparingTo("59000");
    assertThat(response.trailingDelta()).isEqualByComparingTo("100");
    assertThat(response.trailingRate()).isNull();
    assertThat(response.trailingExtreme()).isEqualByComparingTo("60500");

    String baseline = OrderRequestFingerprint.calculate(Requests.order(
        OrderType.LIMIT, TimeInForce.GTC, false, null, null, null, null));
    assertThat(List.of(
        OrderRequestFingerprint.calculate(Requests.order(
            OrderType.LIMIT, TimeInForce.IOC, false, null, null, null, null)),
        OrderRequestFingerprint.calculate(Requests.order(
            OrderType.LIMIT, TimeInForce.GTC, true, null, null, null, null)),
        OrderRequestFingerprint.calculate(Requests.order(
            OrderType.LIMIT, TimeInForce.GTC, false, null, null, null,
            new BigDecimal("59000"))),
        OrderRequestFingerprint.calculate(Requests.order(
            OrderType.LIMIT, TimeInForce.GTC, false, null, new BigDecimal("100"), null, null)),
        OrderRequestFingerprint.calculate(Requests.order(
            OrderType.LIMIT, TimeInForce.GTC, false, null, null,
            new BigDecimal("0.01"), null))))
        .doesNotContain(baseline)
        .doesNotHaveDuplicates();
  }

  @Test
  void legacy_replay_accepts_canonical_defaults_but_rejects_explicit_differences() {
    UserPrincipal principal = new UserPrincipal(
        UUID.fromString("00000000-0000-0000-0000-000000000002"),
        "trader@example.com",
        "TRADER");
    CreateOrderRequest omitted = Requests.legacyReplay(null, null);
    OrderCommand omittedCommand = new OrderCommandFactory().from(principal, omitted);
    OrderEntity canonical = new OrderEntityFactory().createReceived(omittedCommand);
    canonical.setLeverage(10);
    canonical.setTriggerPriceType(TriggerPriceType.LAST_PRICE);

    assertThat(OrderRequestFingerprint.matchesLegacy(canonical, omittedCommand, omitted))
        .isTrue();

    CreateOrderRequest explicitLeverage = Requests.legacyReplay(5, null);
    OrderCommand explicitLeverageCommand =
        new OrderCommandFactory().from(principal, explicitLeverage);
    OrderEntity differentLeverage =
        new OrderEntityFactory().createReceived(explicitLeverageCommand);
    differentLeverage.setLeverage(10);
    differentLeverage.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
    assertThat(OrderRequestFingerprint.matchesLegacy(
        differentLeverage, explicitLeverageCommand, explicitLeverage))
        .isFalse();

    CreateOrderRequest explicitTrigger =
        Requests.legacyReplay(null, TriggerPriceType.MARK_PRICE);
    OrderCommand explicitTriggerCommand =
        new OrderCommandFactory().from(principal, explicitTrigger);
    OrderEntity differentTrigger =
        new OrderEntityFactory().createReceived(explicitTriggerCommand);
    differentTrigger.setLeverage(10);
    differentTrigger.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
    assertThat(OrderRequestFingerprint.matchesLegacy(
        differentTrigger, explicitTriggerCommand, explicitTrigger))
        .isFalse();
  }

  @Test
  void decimal_fingerprint_normalization_is_scale_insensitive_and_bounded() {
    CreateOrderRequest oneDecimalPlace = Requests.order(
        OrderType.LIMIT, TimeInForce.GTC, false,
        null, null, null, new BigDecimal("1.0"));
    CreateOrderRequest twoDecimalPlaces = Requests.order(
        OrderType.LIMIT, TimeInForce.GTC, false,
        null, null, null, new BigDecimal("1.00"));

    assertThat(OrderRequestFingerprint.calculate(oneDecimalPlace))
        .isEqualTo(OrderRequestFingerprint.calculate(twoDecimalPlaces));
    assertThat(OrderRequestFingerprint.normalizeDecimal(
        new BigDecimal("1E-1000000")))
        .isEqualTo("1E-1000000")
        .hasSizeLessThan(32);
  }

  @Test
  void stop_order_fingerprint_treats_omitted_and_explicit_default_authority_as_equivalent() {
    CreateOrderRequest spotDefault = Requests.stopLimit("BTCUSDT", null);
    CreateOrderRequest spotExplicit =
        Requests.stopLimit("BTCUSDT", TriggerPriceType.LAST_PRICE);
    CreateOrderRequest perpetualDefault = Requests.stopLimit("BTCUSDT-PERP", null);
    CreateOrderRequest perpetualExplicit =
        Requests.stopLimit("BTCUSDT-PERP", TriggerPriceType.MARK_PRICE);

    assertThat(OrderRequestFingerprint.calculate(spotDefault))
        .isEqualTo(OrderRequestFingerprint.calculate(spotExplicit));
    assertThat(OrderRequestFingerprint.calculate(perpetualDefault))
        .isEqualTo(OrderRequestFingerprint.calculate(perpetualExplicit));
    assertThat(OrderRequestFingerprint.calculate(spotDefault))
        .isNotEqualTo(OrderRequestFingerprint.calculate(
            Requests.stopLimit("BTCUSDT", TriggerPriceType.MARK_PRICE)));
  }

  private static final class Requests {

    private static CreateOrderRequest trailing(
        BigDecimal trailingDelta,
        BigDecimal trailingRate
    ) {
      return new CreateOrderRequest(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          "BTCUSDT-PERP",
          OrderSide.SELL,
          OrderType.TRAILING_STOP_MARKET,
          null,
          null,
          null,
          null,
          "advanced-trailing-contract",
          "advanced-trailing-contract",
          BigDecimal.ONE,
          null,
          10,
          PositionSide.LONG,
          QuantityUnit.CONTRACTS,
          MarginMode.CROSS,
          null,
          null,
          true,
          List.of(),
          TimeInForce.GTC,
          false,
          new BigDecimal("59000"),
          trailingDelta,
          trailingRate);
    }

    private static CreateOrderRequest legacyReplay(
        Integer leverage,
        TriggerPriceType triggerPriceType
    ) {
      return new CreateOrderRequest(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          "BTCUSDT",
          OrderSide.BUY,
          OrderType.STOP_MARKET,
          null,
          null,
          null,
          null,
          "legacy-replay",
          "legacy-replay",
          BigDecimal.ONE,
          null,
          leverage,
          PositionSide.BOTH,
          QuantityUnit.BASE,
          MarginMode.CASH,
          new BigDecimal("60000"),
          triggerPriceType,
          false,
          List.of(),
          TimeInForce.GTC,
          false,
          null,
          null,
          null);
    }

    private static CreateOrderRequest stopLimit(
        String symbol,
        TriggerPriceType triggerPriceType
    ) {
      return new CreateOrderRequest(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          symbol,
          OrderSide.BUY,
          OrderType.STOP_LIMIT,
          null,
          null,
          null,
          null,
          "stop-limit-default-authority",
          "stop-limit-default-authority",
          BigDecimal.ONE,
          new BigDecimal("100"),
          1,
          PositionSide.BOTH,
          QuantityUnit.BASE,
          symbol.endsWith("-PERP") ? MarginMode.CROSS : MarginMode.CASH,
          new BigDecimal("110"),
          triggerPriceType,
          false,
          List.of(),
          TimeInForce.GTC,
          false,
          null,
          null,
          null);
    }

    private static CreateOrderRequest order(
        OrderType orderType,
        TimeInForce timeInForce,
        Boolean postOnly,
        BigDecimal triggerPrice,
        BigDecimal trailingDelta,
        BigDecimal trailingRate,
        BigDecimal activationPrice
    ) {
      return new CreateOrderRequest(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          "BTCUSDT",
          OrderSide.BUY,
          orderType,
          null,
          null,
          null,
          null,
          "advanced-contract",
          "advanced-contract",
          BigDecimal.ONE,
          null,
          1,
          PositionSide.BOTH,
          QuantityUnit.BASE,
          MarginMode.CASH,
          triggerPrice,
          TriggerPriceType.LAST_PRICE,
          false,
          List.of(),
          timeInForce,
          postOnly,
          activationPrice,
          trailingDelta,
          trailingRate);
    }
  }
}
