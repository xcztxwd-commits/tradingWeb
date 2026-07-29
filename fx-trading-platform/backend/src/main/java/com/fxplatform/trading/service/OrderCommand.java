package com.fxplatform.trading.service;

import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerPriceType;
import java.math.BigDecimal;
import java.util.List;
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
    Integer leverage,
    BigDecimal originalQuantity,
    BigDecimal baseQuantity,
    QuantityUnit quantityUnit,
    MarginMode marginMode,
    PositionSide positionSide,
    Boolean reduceOnly,
    BigDecimal triggerPrice,
    TriggerPriceType triggerPriceType,
    List<CreateOrderRequest.AttachedProtectionRequest> attachedProtections,
    TimeInForce timeInForce,
    Boolean postOnly,
    BigDecimal activationPrice,
    BigDecimal trailingDelta,
    BigDecimal trailingRate
) {

  public OrderCommand(
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
      Integer leverage,
      BigDecimal originalQuantity,
      BigDecimal baseQuantity,
      QuantityUnit quantityUnit,
      MarginMode marginMode,
      PositionSide positionSide,
      Boolean reduceOnly,
      BigDecimal triggerPrice,
      TriggerPriceType triggerPriceType,
      List<CreateOrderRequest.AttachedProtectionRequest> attachedProtections
  ) {
    this(
        userId,
        accountId,
        symbol,
        side,
        orderType,
        quantity,
        price,
        stopLoss,
        takeProfit,
        clientOrderId,
        idempotencyKey,
        leverage,
        originalQuantity,
        baseQuantity,
        quantityUnit,
        marginMode,
        positionSide,
        reduceOnly,
        triggerPrice,
        triggerPriceType,
        attachedProtections,
        TimeInForce.GTC,
        false,
        null,
        null,
        null);
  }

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
        originalQuantity,
        price,
        leverage,
        positionSide,
        quantityUnit,
        marginMode,
        triggerPrice,
        triggerPriceType,
        reduceOnly,
        attachedProtections,
        timeInForce,
        postOnly,
        activationPrice,
        trailingDelta,
        trailingRate);
  }
}
