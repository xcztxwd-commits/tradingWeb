package com.fxplatform.trading.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Shared locked/read OCO group integrity checks used before any group mutation. */
final class OcoGroupValidator {

  private OcoGroupValidator() {
  }

  static List<OrderEntity> requireValid(List<OrderEntity> rawLegs) {
    if (rawLegs == null
        || rawLegs.size() != 2
        || rawLegs.stream().anyMatch(Objects::isNull)
        || rawLegs.stream().anyMatch(leg -> leg.getId() == null)) {
      throw incomplete("OCO group must contain exactly two legs");
    }
    List<OrderEntity> legs = rawLegs.stream()
        .sorted(Comparator.comparing(OrderEntity::getId))
        .toList();
    OrderEntity first = legs.getFirst();
    UUID groupId = first.getContingencyGroupId();
    UUID ownerId = first.getHoldOwnerOrderId();
    BigDecimal baseQuantity = canonicalBase(first);
    String holdCurrency = first.getHoldCurrency();
    String expectedHoldCurrency = expectedHoldCurrency(first);
    boolean structural = groupId != null
        && ownerId != null
        && first.getUserId() != null
        && first.getAccountId() != null
        && first.getSymbol() != null
        && !first.getSymbol().isBlank()
        && first.getSide() != null
        && baseQuantity != null
        && baseQuantity.signum() > 0
        && holdCurrency != null
        && !holdCurrency.isBlank()
        && expectedHoldCurrency != null
        && expectedHoldCurrency.equalsIgnoreCase(holdCurrency)
        && legs.stream().map(OrderEntity::getId).distinct().count() == 2
        && legs.stream().filter(leg -> leg.getOrderType() == OrderType.LIMIT).count() == 1
        && legs.stream().filter(leg -> leg.getOrderType() == OrderType.STOP_MARKET).count() == 1
        && legs.stream().anyMatch(leg -> ownerId.equals(leg.getId()))
        && legs.stream().allMatch(leg -> groupId.equals(leg.getContingencyGroupId())
            && ownerId.equals(leg.getHoldOwnerOrderId())
            && first.getUserId().equals(leg.getUserId())
            && first.getAccountId().equals(leg.getAccountId())
            && sameSymbol(first.getSymbol(), leg.getSymbol())
            && first.getSide() == leg.getSide()
            && leg.getProductType() == ProductType.CRYPTO_SPOT
            && leg.getMarginMode() == MarginMode.CASH
            && leg.getPositionSide() == PositionSide.BOTH
            && leg.getQuantityUnit() == QuantityUnit.BASE
            && Boolean.FALSE.equals(leg.getReduceOnly())
            && leg.getOrderOrigin() == OrderOrigin.OCO
            && leg.getTimeInForce() == TimeInForce.GTC
            && validLegShape(leg)
            && sameQuantity(baseQuantity, canonicalBase(leg))
            && leg.getHoldAmount() != null
            && leg.getHoldAmount().signum() >= 0
            && leg.getHoldCurrency() != null
            && holdCurrency.equalsIgnoreCase(leg.getHoldCurrency()))
        && legs.stream()
            .filter(leg -> !ownerId.equals(leg.getId()))
            .allMatch(leg -> leg.getHoldAmount().signum() == 0);
    if (!structural || !validStateAndHoldShape(legs, ownerId)) {
      throw incomplete("OCO group legs do not share one owner/account/symbol/side/quantity contract");
    }
    return legs;
  }

  private static boolean validLegShape(OrderEntity leg) {
    return switch (leg.getOrderType()) {
      case LIMIT -> positive(leg.getPrice())
          && positive(leg.getRequestedPrice())
          && leg.getPrice().compareTo(leg.getRequestedPrice()) == 0
          && leg.getTriggerPrice() == null
          && leg.getTriggerPriceType() == null
          && leg.getTriggerExecutionType() == null;
      case STOP_MARKET -> leg.getPrice() == null
          && leg.getRequestedPrice() == null
          && positive(leg.getTriggerPrice())
          && leg.getTriggerPriceType() == TriggerPriceType.LAST_PRICE
          && leg.getTriggerExecutionType() == TriggerExecutionType.MARKET;
      default -> false;
    };
  }

  private static boolean positive(BigDecimal value) {
    return value != null && value.signum() > 0;
  }

  private static boolean validStateAndHoldShape(List<OrderEntity> legs, UUID ownerId) {
    OrderEntity owner = legs.stream().filter(leg -> ownerId.equals(leg.getId()))
        .findFirst().orElse(null);
    if (owner == null) {
      return false;
    }
    boolean allPending = legs.stream().allMatch(leg -> leg.getStatus() == OrderStatus.PENDING);
    if (allPending) {
      return owner.getHoldAmount().signum() > 0;
    }
    boolean allTerminal = legs.stream().allMatch(OcoGroupValidator::terminal);
    return allTerminal
        && legs.stream().allMatch(leg -> leg.getHoldAmount().signum() == 0)
        && legs.stream().filter(leg -> leg.getStatus() == OrderStatus.FILLED).count() <= 1;
  }

  private static boolean terminal(OrderEntity order) {
    return order.getStatus() == OrderStatus.FILLED
        || order.getStatus() == OrderStatus.CANCELED
        || order.getStatus() == OrderStatus.CANCELLED
        || order.getStatus() == OrderStatus.REJECTED
        || order.getStatus() == OrderStatus.EXPIRED;
  }

  private static BigDecimal canonicalBase(OrderEntity order) {
    return order.getBaseQuantity();
  }

  private static boolean sameQuantity(BigDecimal expected, BigDecimal actual) {
    return actual != null && expected.compareTo(actual) == 0;
  }

  private static boolean sameSymbol(String first, String second) {
    return Objects.equals(SymbolNormalizer.normalize(first), SymbolNormalizer.normalize(second));
  }

  private static String expectedHoldCurrency(OrderEntity order) {
    if (order.getSymbol() == null || order.getSymbol().isBlank() || order.getSide() == null) {
      return null;
    }
    String symbol = SymbolNormalizer.normalize(order.getSymbol());
    if (!symbol.endsWith("USDT") || symbol.length() <= 4) {
      return null;
    }
    return order.getSide() == com.fxplatform.trading.enums.OrderSide.BUY
        ? "USDT"
        : symbol.substring(0, symbol.length() - 4);
  }

  private static BusinessException incomplete(String message) {
    return new BusinessException(ErrorCode.OCO_GROUP_INCOMPLETE, message);
  }
}
