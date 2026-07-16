package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerPriceType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderCommandFactoryTest {

  @Test
  void rejectsReservedSystemNamespaceInEitherUserControlledKey() {
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(
        UUID.randomUUID(), "trader@example.com", "TRADER");
    CreateOrderRequest reservedClient = requestWithKeys(
        accountId, "normal-idempotency", "__SYSTEM__:forged-client");
    CreateOrderRequest reservedIdempotency = requestWithKeys(
        accountId, "__system__:forged-idempotency", "normal-client");

    assertThatThrownBy(() -> new OrderCommandFactory().from(principal, reservedClient))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.DUPLICATE_CLIENT_ORDER_ID));
    assertThatThrownBy(() -> new OrderCommandFactory().from(principal, reservedIdempotency))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.DUPLICATE_CLIENT_ORDER_ID));
  }

  @Test
  void normalizesNewOrderFieldsWhileKeepingCompatibleAliases() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId,
        "btc-usdt",
        OrderSide.BUY,
        OrderType.LIMIT,
        null,
        null,
        new BigDecimal("59000"),
        new BigDecimal("62000"),
        " ",
        "client-123",
        new BigDecimal("0.02"),
        new BigDecimal("60736.3"));

    OrderCommand command = new OrderCommandFactory().from(principal, request);

    assertThat(command.userId()).isEqualTo(userId);
    assertThat(command.accountId()).isEqualTo(accountId);
    assertThat(command.symbol()).isEqualTo("BTCUSDT");
    assertThat(command.quantity()).isEqualByComparingTo("0.02");
    assertThat(command.price()).isEqualByComparingTo("60736.3");
    assertThat(command.clientOrderId()).isEqualTo("client-123");
    assertThat(command.idempotencyKey()).isEqualTo("client-123");
    assertThat(command.toRequest().lots()).isEqualByComparingTo("0.02");
    assertThat(command.toRequest().requestedPrice()).isEqualByComparingTo("60736.3");
  }

  @Test
  void keepsPublicQuantityIdentityWhileCarryingCanonicalBaseQuantity() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId,
        "btc-usdt",
        OrderSide.BUY,
        OrderType.MARKET,
        null,
        null,
        null,
        null,
        "spot-quote-1",
        "spot-quote-1",
        new BigDecimal("100.00"),
        null,
        1,
        PositionSide.BOTH,
        QuantityUnit.QUOTE,
        MarginMode.CASH,
        null,
        TriggerPriceType.LAST_PRICE,
        false,
        List.of());

    OrderCommand command = new OrderCommandFactory().from(
        principal,
        request,
        new BigDecimal("0.0019"));

    assertThat(command.quantity()).isEqualByComparingTo("0.0019");
    assertThat(command.baseQuantity()).isEqualByComparingTo("0.0019");
    assertThat(command.originalQuantity()).isEqualByComparingTo("100.00");
    assertThat(command.quantityUnit()).isEqualTo(QuantityUnit.QUOTE);
    assertThat(command.marginMode()).isEqualTo(MarginMode.CASH);
    assertThat(command.positionSide()).isEqualTo(PositionSide.BOTH);
    assertThat(command.reduceOnly()).isFalse();
    assertThat(command.triggerPriceType()).isEqualTo(TriggerPriceType.LAST_PRICE);
    assertThat(command.toRequest().quantity()).isEqualByComparingTo("100.00");
    assertThat(command.toRequest().quantityUnit()).isEqualTo(QuantityUnit.QUOTE);
  }

  private static CreateOrderRequest requestWithKeys(
      UUID accountId,
      String idempotencyKey,
      String clientOrderId
  ) {
    return new CreateOrderRequest(
        accountId,
        "BTCUSDT",
        OrderSide.BUY,
        OrderType.MARKET,
        null,
        null,
        null,
        null,
        idempotencyKey,
        clientOrderId,
        new BigDecimal("0.01"),
        null,
        1,
        PositionSide.BOTH,
        QuantityUnit.BASE,
        MarginMode.CASH,
        null,
        TriggerPriceType.LAST_PRICE,
        false,
        List.of());
  }
}
