package com.fxplatform.trading.service;

import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.TriggerPriceType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical identity of one user-submitted order request. */
final class OrderRequestFingerprint {

  private OrderRequestFingerprint() {
  }

  static String calculate(CreateOrderRequest request) {
    StringBuilder material = new StringBuilder(256);
    append(material, request.accountId());
    append(material, SymbolNormalizer.normalize(request.symbol()));
    append(material, request.clientOrderId());
    append(material, effectiveIdempotencyKey(request));
    append(material, request.side());
    append(material, request.orderType());
    append(material, request.quantity());
    append(material, request.price());
    append(material, request.stopLoss());
    append(material, request.takeProfit());
    append(material, request.leverage());
    append(material, request.positionSide());
    append(material, request.quantityUnit());
    append(material, request.marginMode());
    append(material, request.triggerPrice());
    append(material, effectiveTriggerPriceType(request));
    append(material, request.reduceOnly());
    append(material, request.timeInForce());
    append(material, request.postOnly());
    append(material, request.activationPrice());
    append(material, request.trailingDelta());
    append(material, request.trailingRate());
    append(material, request.attachedProtections().size());
    for (CreateOrderRequest.AttachedProtectionRequest protection
        : request.attachedProtections()) {
      append(material, protection.protectionType());
      append(material, protection.triggerPrice());
      append(material, protection.triggerPriceType());
      append(material, protection.triggerExecutionType());
      append(material, protection.price());
      append(material, protection.quantity());
      append(material, protection.quantityUnit());
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
          material.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required for order request identity", exception);
    }
  }

  static boolean matchesLegacy(
      OrderEntity order,
      OrderCommand command,
      CreateOrderRequest request
  ) {
    return order != null
        && Objects.equals(order.getUserId(), command.userId())
        && Objects.equals(order.getAccountId(), request.accountId())
        && Objects.equals(normalize(order.getSymbol()), normalize(request.symbol()))
        && Objects.equals(order.getClientOrderId(), request.clientOrderId())
        && Objects.equals(order.getIdempotencyKey(), command.idempotencyKey())
        && order.getSide() == request.side()
        && order.getOrderType() == request.orderType()
        && sameDecimal(originalQuantity(order), request.quantity())
        && sameDecimal(currentPrice(order), request.price())
        && sameDecimal(order.getStopLoss(), request.stopLoss())
        && sameDecimal(order.getTakeProfit(), request.takeProfit())
        && (request.leverage() == null
            || Objects.equals(order.getLeverage(), request.leverage()))
        && order.getPositionSide() == request.positionSide()
        && order.getQuantityUnit() == request.quantityUnit()
        && order.getMarginMode() == request.marginMode()
        && sameDecimal(order.getTriggerPrice(), request.triggerPrice())
        && (request.triggerPriceType() == null
            || order.getTriggerPriceType() == request.triggerPriceType())
        && Objects.equals(order.getReduceOnly(), request.reduceOnly())
        && order.getTimeInForce() == request.timeInForce()
        && Objects.equals(order.getPostOnly(), request.postOnly())
        && sameDecimal(order.getActivationPrice(), request.activationPrice())
        && sameDecimal(order.getTrailingDelta(), request.trailingDelta())
        && sameDecimal(order.getTrailingRate(), request.trailingRate())
        && request.attachedProtections().isEmpty();
  }

  private static BigDecimal originalQuantity(OrderEntity order) {
    if (order.getOriginalQuantity() != null) {
      return order.getOriginalQuantity();
    }
    if (order.getQuantity() != null) {
      return order.getQuantity();
    }
    return order.getLots();
  }

  private static BigDecimal currentPrice(OrderEntity order) {
    return order.getPrice() != null ? order.getPrice() : order.getRequestedPrice();
  }

  private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
    return left == null ? right == null : right != null && left.compareTo(right) == 0;
  }

  private static String normalize(String symbol) {
    return symbol == null ? null : SymbolNormalizer.normalize(symbol);
  }

  private static String effectiveIdempotencyKey(CreateOrderRequest request) {
    return request.idempotencyKey() == null || request.idempotencyKey().isBlank()
        ? request.clientOrderId()
        : request.idempotencyKey();
  }

  private static TriggerPriceType effectiveTriggerPriceType(CreateOrderRequest request) {
    if (request.triggerPriceType() != null) {
      return request.triggerPriceType();
    }
    if (request.orderType() != OrderType.STOP_MARKET
        && request.orderType() != OrderType.STOP_LIMIT) {
      return null;
    }
    return normalize(request.symbol()).endsWith("-PERP")
        ? TriggerPriceType.MARK_PRICE
        : TriggerPriceType.LAST_PRICE;
  }

  private static void append(StringBuilder material, Object value) {
    String text;
    if (value instanceof BigDecimal decimal) {
      text = normalizeDecimal(decimal);
    } else if (value instanceof Enum<?> enumeration) {
      text = enumeration.name();
    } else {
      text = value == null ? null : value.toString();
    }
    if (text == null) {
      material.append("-1:");
    } else {
      material.append(text.length()).append(':').append(text);
    }
    material.append('|');
  }

  static String normalizeDecimal(BigDecimal decimal) {
    return decimal.stripTrailingZeros().toString();
  }
}
