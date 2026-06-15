package com.fxplatform.trading.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import java.math.BigDecimal;
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
}
