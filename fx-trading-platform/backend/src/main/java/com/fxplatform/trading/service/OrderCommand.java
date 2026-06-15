package com.fxplatform.trading.service;

import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * OrderCommand 承载交易模块的数据结构。
 */
public record OrderCommand(
    UUID userId,
    UUID accountId,
    String symbol,
    OrderSide side,
    OrderType orderType,
    BigDecimal quantity,
    BigDecimal price,
    BigDecimal stopLoss,
    BigDecimal takeProfit,
    String clientOrderId,
    String idempotencyKey,
    Integer leverage
) {

  public CreateOrderRequest toRequest() {
    return new CreateOrderRequest(
        accountId,
        symbol,
        side,
        orderType,
        quantity,
        price,
        stopLoss,
        takeProfit,
        idempotencyKey,
        clientOrderId,
        quantity,
        price,
        leverage);
  }
}
