package com.fxplatform.trading.service;

import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import java.math.BigDecimal;
import java.util.UUID;

/** Immutable execution contract captured before resolving a pending order's market snapshot. */
record PendingOrderExecutionFingerprint(
    UUID id,
    UUID userId,
    UUID accountId,
    String symbol,
    ProductType productType,
    PositionMode positionMode,
    PositionSide positionSide,
    MarginMode marginMode,
    OrderSide side,
    OrderType orderType,
    OrderStatus status,
    BigDecimal lots,
    BigDecimal requestedPrice,
    String clientOrderId,
    BigDecimal quantity,
    QuantityUnit quantityUnit,
    BigDecimal originalQuantity,
    BigDecimal baseQuantity,
    BigDecimal price,
    TimeInForce timeInForce,
    Boolean postOnly,
    BigDecimal activationPrice,
    BigDecimal trailingDelta,
    BigDecimal trailingRate,
    BigDecimal trailingExtreme,
    Boolean reduceOnly,
    OrderOrigin orderOrigin,
    BigDecimal triggerPrice,
    TriggerPriceType triggerPriceType,
    TriggerExecutionType triggerExecutionType,
    ProtectionType protectionType,
    UUID parentOrderId,
    UUID parentPositionId,
    UUID contingencyGroupId,
    UUID holdOwnerOrderId,
    BigDecimal filledQuantity,
    BigDecimal remainingQuantity,
    BigDecimal holdAmount,
    String holdCurrency,
    BigDecimal stopLoss,
    BigDecimal takeProfit,
    String idempotencyKey,
    String requestFingerprint,
    Integer leverage,
    Long version
) {

  static PendingOrderExecutionFingerprint capture(OrderEntity order) {
    return new PendingOrderExecutionFingerprint(
        order.getId(),
        order.getUserId(),
        order.getAccountId(),
        order.getSymbol(),
        order.getProductType(),
        order.getPositionMode(),
        order.getPositionSide(),
        order.getMarginMode(),
        order.getSide(),
        order.getOrderType(),
        order.getStatus(),
        order.getLots(),
        order.getRequestedPrice(),
        order.getClientOrderId(),
        order.getQuantity(),
        order.getQuantityUnit(),
        order.getOriginalQuantity(),
        order.getBaseQuantity(),
        order.getPrice(),
        order.getTimeInForce(),
        order.getPostOnly(),
        order.getActivationPrice(),
        order.getTrailingDelta(),
        order.getTrailingRate(),
        order.getTrailingExtreme(),
        order.getReduceOnly(),
        order.getOrderOrigin(),
        order.getTriggerPrice(),
        order.getTriggerPriceType(),
        order.getTriggerExecutionType(),
        order.getProtectionType(),
        order.getParentOrderId(),
        order.getParentPositionId(),
        order.getContingencyGroupId(),
        order.getHoldOwnerOrderId(),
        order.getFilledQuantity(),
        order.getRemainingQuantity(),
        order.getHoldAmount(),
        order.getHoldCurrency(),
        order.getStopLoss(),
        order.getTakeProfit(),
        order.getIdempotencyKey(),
        order.getRequestFingerprint(),
        order.getLeverage(),
        order.getVersion());
  }

  boolean matches(OrderEntity lockedOrder) {
    return lockedOrder != null && equals(capture(lockedOrder));
  }
}
