package com.fxplatform.execution;

import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.TimeInForce;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A deterministic, side-effect-free matcher for the configured demo execution policy. */
public class DemoMatchingEngine {

  public DemoMatchingResult match(DemoMatchingRequest request) {
    validate(request);
    if (request.policy().matchingMode() == DemoMatchingMode.SIMPLE) {
      if (request.postOnly()) {
        return result(
            request, List.of(), request.requestedBaseQuantity(), OrderStatus.REJECTED);
      }
      if (!isWithinLimit(request, request.simpleFillPrice())) {
        return result(
            request,
            List.of(),
            request.requestedBaseQuantity(),
            statusFor(request.timeInForce(), request.requestedBaseQuantity(), List.of()));
      }
      return simpleFill(request);
    }

    List<DemoMatchFill> fills = depthFills(request);
    if (request.postOnly() && !fills.isEmpty()) {
      return result(request, List.of(), request.requestedBaseQuantity(), OrderStatus.REJECTED);
    }
    BigDecimal remaining = remaining(request, fills);
    if (request.timeInForce() == TimeInForce.FOK
        && remaining.compareTo(BigDecimal.ZERO) > 0) {
      return result(request, List.of(), request.requestedBaseQuantity(), OrderStatus.CANCELLED);
    }
    return result(request, fills, remaining, statusFor(request.timeInForce(), remaining, fills));
  }

  private DemoMatchingResult simpleFill(DemoMatchingRequest request) {
    DemoMatchFill fill = new DemoMatchFill(
        request.requestedBaseQuantity(),
        request.simpleFillPrice(),
        request.liquidityRole(),
        feeRate(request));
    return result(request, List.of(fill), BigDecimal.ZERO, OrderStatus.FILLED);
  }

  private List<DemoMatchFill> depthFills(DemoMatchingRequest request) {
    List<DemoBookLevel> levels = new ArrayList<>(
        request.side() == OrderSide.BUY ? request.policy().asks() : request.policy().bids());
    levels.sort(levelComparator(request.side()));

    List<DemoMatchFill> fills = new ArrayList<>();
    BigDecimal remaining = request.requestedBaseQuantity();
    BigDecimal capRemaining = request.policy().maxFillQuantityPerTick();
    for (DemoBookLevel level : levels) {
      if (remaining.compareTo(BigDecimal.ZERO) == 0 || isCapExhausted(capRemaining)) {
        break;
      }
      if (!isWithinLimit(request, level.price())) {
        break;
      }
      BigDecimal quantity = level.quantity().min(remaining);
      if (capRemaining != null) {
        quantity = quantity.min(capRemaining);
      }
      fills.add(new DemoMatchFill(quantity, level.price(), request.liquidityRole(), feeRate(request)));
      remaining = remaining.subtract(quantity);
      if (capRemaining != null) {
        capRemaining = capRemaining.subtract(quantity);
      }
    }
    return List.copyOf(fills);
  }

  private static Comparator<DemoBookLevel> levelComparator(OrderSide side) {
    Comparator<DemoBookLevel> byPrice = Comparator.comparing(DemoBookLevel::price);
    return side == OrderSide.BUY ? byPrice : byPrice.reversed();
  }

  private static boolean isCapExhausted(BigDecimal capRemaining) {
    return capRemaining != null && capRemaining.compareTo(BigDecimal.ZERO) == 0;
  }

  private static boolean isWithinLimit(DemoMatchingRequest request, BigDecimal price) {
    if (request.orderType() == OrderType.MARKET) {
      return true;
    }
    return request.side() == OrderSide.BUY
        ? price.compareTo(request.limitPrice()) <= 0
        : price.compareTo(request.limitPrice()) >= 0;
  }

  private static BigDecimal remaining(DemoMatchingRequest request, List<DemoMatchFill> fills) {
    BigDecimal remaining = request.requestedBaseQuantity();
    for (DemoMatchFill fill : fills) {
      remaining = remaining.subtract(fill.quantity());
    }
    return remaining;
  }

  private static DemoMatchingResult result(
      DemoMatchingRequest request,
      List<DemoMatchFill> fills,
      BigDecimal remaining,
      OrderStatus status
  ) {
    return new DemoMatchingResult(
        fills, request.requestedBaseQuantity().subtract(remaining), remaining, status);
  }

  private static OrderStatus statusFor(
      TimeInForce timeInForce, BigDecimal remaining, List<DemoMatchFill> fills
  ) {
    if (remaining.compareTo(BigDecimal.ZERO) == 0) {
      return OrderStatus.FILLED;
    }
    if (timeInForce == TimeInForce.IOC || timeInForce == TimeInForce.FOK) {
      return OrderStatus.CANCELLED;
    }
    return fills.isEmpty() ? OrderStatus.PENDING : OrderStatus.PARTIALLY_FILLED;
  }

  private static BigDecimal feeRate(DemoMatchingRequest request) {
    return request.liquidityRole() == LiquidityRole.MAKER
        ? request.policy().makerFeeRate()
        : request.policy().takerFeeRate();
  }

  private static void validate(DemoMatchingRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request is required");
    }
    if (request.symbol() == null || request.symbol().isBlank()) {
      throw new IllegalArgumentException("symbol is required");
    }
    if (request.side() == null || request.orderType() == null || request.timeInForce() == null
        || request.policy() == null || request.liquidityRole() == null) {
      throw new IllegalArgumentException("matching request fields are required");
    }
    if (request.requestedBaseQuantity() == null
        || request.requestedBaseQuantity().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("requestedBaseQuantity must be positive");
    }
    if (request.orderType() == OrderType.LIMIT
        && (request.limitPrice() == null || request.limitPrice().compareTo(BigDecimal.ZERO) <= 0)) {
      throw new IllegalArgumentException("limitPrice must be positive for limit orders");
    }
    if (request.orderType() != OrderType.MARKET && request.orderType() != OrderType.LIMIT) {
      throw new IllegalArgumentException("only MARKET and LIMIT orders can be matched");
    }
    if (request.policy().matchingMode() == DemoMatchingMode.SIMPLE
        && (request.simpleFillPrice() == null
        || request.simpleFillPrice().compareTo(BigDecimal.ZERO) <= 0)) {
      throw new IllegalArgumentException("simpleFillPrice must be positive in SIMPLE mode");
    }
  }
}
