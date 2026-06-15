package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderCommandFactoryTest {

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
}
