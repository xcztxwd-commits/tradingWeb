package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class OcoGroupValidatorTest {

  @Test
  void rejectsNullIdBlankSymbolMissingCanonicalBasePeerHoldAndCurrencyMismatch() {
    List<Consumer<List<OrderEntity>>> corruptions = List.of(
        legs -> legs.getFirst().setId(null),
        legs -> legs.forEach(leg -> leg.setSymbol(" ")),
        legs -> legs.getLast().setBaseQuantity(null),
        legs -> legs.getLast().setHoldAmount(new BigDecimal("0.01000000")),
        legs -> legs.getLast().setHoldCurrency("ETH"),
        legs -> legs.forEach(leg -> leg.setHoldCurrency("USDT")),
        legs -> legs.getLast().setProductType(ProductType.LINEAR_PERP),
        legs -> legs.getLast().setMarginMode(MarginMode.CROSS),
        legs -> legs.getLast().setPositionSide(PositionSide.LONG),
        legs -> legs.getLast().setQuantityUnit(QuantityUnit.QUOTE),
        legs -> legs.getLast().setReduceOnly(true),
        legs -> legs.getLast().setOrderOrigin(OrderOrigin.USER),
        legs -> legs.getLast().setTimeInForce(null),
        legs -> legs.getFirst().setHoldAmount(BigDecimal.ZERO),
        legs -> legs.getLast().setStatus(OrderStatus.CANCELED));

    for (Consumer<List<OrderEntity>> corruption : corruptions) {
      List<OrderEntity> legs = validGroup();
      corruption.accept(legs);
      assertThatThrownBy(() -> OcoGroupValidator.requireValid(legs))
          .isInstanceOfSatisfying(BusinessException.class,
              exception -> assertThat(exception.getCode()).isEqualTo("OCO_GROUP_INCOMPLETE"));
    }
  }

  @Test
  void acceptsOnlyZeroHoldForAConsistentTerminalGroup() {
    List<OrderEntity> legs = validGroup();
    legs.getFirst().setStatus(OrderStatus.FILLED);
    legs.getLast().setStatus(OrderStatus.CANCELED);
    legs.forEach(leg -> leg.setHoldAmount(BigDecimal.ZERO));

    assertThat(OcoGroupValidator.requireValid(legs)).containsExactlyInAnyOrderElementsOf(legs);
  }

  @Test
  void rejectsCorruptedLimitLegShape() {
    assertLegCorruptionsRejected(OrderType.LIMIT, List.of(
        leg -> leg.setPrice(null),
        leg -> leg.setPrice(BigDecimal.ZERO),
        leg -> leg.setRequestedPrice(null),
        leg -> leg.setRequestedPrice(BigDecimal.ZERO),
        leg -> leg.setRequestedPrice(new BigDecimal("49001")),
        leg -> leg.setTriggerPrice(BigDecimal.ONE),
        leg -> leg.setTriggerPriceType(TriggerPriceType.LAST_PRICE),
        leg -> leg.setTriggerExecutionType(TriggerExecutionType.MARKET)));
  }

  @Test
  void rejectsCorruptedStopMarketLegShape() {
    assertLegCorruptionsRejected(OrderType.STOP_MARKET, List.of(
        leg -> leg.setPrice(BigDecimal.ONE),
        leg -> leg.setRequestedPrice(BigDecimal.ONE),
        leg -> leg.setTriggerPrice(null),
        leg -> leg.setTriggerPrice(BigDecimal.ZERO),
        leg -> leg.setTriggerPriceType(null),
        leg -> leg.setTriggerPriceType(TriggerPriceType.MARK_PRICE),
        leg -> leg.setTriggerExecutionType(null),
        leg -> leg.setTriggerExecutionType(TriggerExecutionType.LIMIT)));
  }

  @Test
  void rejectsInvalidLegTypeCombination() {
    List<OrderEntity> duplicateLimit = validGroup();
    legOfType(duplicateLimit, OrderType.STOP_MARKET).setOrderType(OrderType.LIMIT);
    List<OrderEntity> unsupportedType = validGroup();
    legOfType(unsupportedType, OrderType.LIMIT).setOrderType(OrderType.MARKET);

    assertAll(
        () -> assertIncomplete(duplicateLimit),
        () -> assertIncomplete(unsupportedType));
  }

  private static List<OrderEntity> validGroup() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    OrderEntity owner = leg(userId, accountId, groupId, ownerId, ownerId, OrderType.LIMIT);
    owner.setHoldAmount(new BigDecimal("0.10000000"));
    OrderEntity peer = leg(
        userId, accountId, groupId, ownerId, UUID.randomUUID(), OrderType.STOP_MARKET);
    return List.of(owner, peer);
  }

  private static OrderEntity leg(
      UUID userId,
      UUID accountId,
      UUID groupId,
      UUID ownerId,
      UUID id,
      OrderType type
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(id);
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setContingencyGroupId(groupId);
    order.setHoldOwnerOrderId(ownerId);
    order.setSymbol("BTCUSDT");
    order.setSide(OrderSide.SELL);
    order.setOrderType(type);
    if (type == OrderType.LIMIT) {
      order.setPrice(new BigDecimal("49000"));
      order.setRequestedPrice(new BigDecimal("49000.0"));
    } else {
      order.setTriggerPrice(new BigDecimal("51000"));
      order.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
      order.setTriggerExecutionType(TriggerExecutionType.MARKET);
    }
    order.setStatus(OrderStatus.PENDING);
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setMarginMode(MarginMode.CASH);
    order.setPositionSide(PositionSide.BOTH);
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setReduceOnly(false);
    order.setOrderOrigin(OrderOrigin.OCO);
    order.setTimeInForce(TimeInForce.GTC);
    order.setBaseQuantity(new BigDecimal("0.1000"));
    order.setQuantity(new BigDecimal("0.1000"));
    order.setLots(new BigDecimal("0.1000"));
    order.setHoldAmount(BigDecimal.ZERO);
    order.setHoldCurrency("BTC");
    return order;
  }

  private static void assertLegCorruptionsRejected(
      OrderType targetType,
      List<Consumer<OrderEntity>> corruptions
  ) {
    List<Executable> assertions = corruptions.stream()
        .<Executable>map(corruption -> () -> {
          List<OrderEntity> legs = validGroup();
          corruption.accept(legOfType(legs, targetType));
          assertIncomplete(legs);
        })
        .toList();
    assertAll(assertions);
  }

  private static OrderEntity legOfType(List<OrderEntity> legs, OrderType type) {
    return legs.stream()
        .filter(leg -> leg.getOrderType() == type)
        .findFirst()
        .orElseThrow();
  }

  private static void assertIncomplete(List<OrderEntity> legs) {
    assertThatThrownBy(() -> OcoGroupValidator.requireValid(legs))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("OCO_GROUP_INCOMPLETE"));
  }
}
