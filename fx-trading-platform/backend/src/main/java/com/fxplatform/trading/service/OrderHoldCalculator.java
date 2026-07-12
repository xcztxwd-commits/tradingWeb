package com.fxplatform.trading.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillPricingProjection;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Calculates deterministic P0 Spot wallet holds from one canonical market bundle. */
@Service
@RequiredArgsConstructor
public class OrderHoldCalculator {

  private static final int WALLET_SCALE = 8;

  private final FullFillCoordinator fullFillCoordinator;

  public OrderHold limit(
      OrderSide side,
      BigDecimal baseQuantity,
      BigDecimal limitPrice,
      ExecutableMarketSnapshot snapshot
  ) {
    requirePositive(baseQuantity, "Base quantity must be positive");
    requirePositive(limitPrice, "Limit price must be positive");
    if (side == OrderSide.SELL) {
      return new OrderHold(up(baseQuantity), baseAsset(snapshot));
    }
    FullFillPricingProjection policy = fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT,
        side,
        FullFillExecutionPath.IMMEDIATE_LIMIT,
        limitPrice,
        snapshot);
    BigDecimal amount = baseQuantity.multiply(limitPrice)
        .multiply(BigDecimal.ONE.add(policy.worstFeeRate()));
    return new OrderHold(up(amount), "USDT");
  }

  public OrderHold stopMarket(
      OrderSide side,
      BigDecimal baseQuantity,
      BigDecimal triggerPrice,
      ExecutableMarketSnapshot snapshot
  ) {
    requirePositive(baseQuantity, "Base quantity must be positive");
    requirePositive(triggerPrice, "Stop trigger price must be positive");
    if (side == OrderSide.SELL) {
      return new OrderHold(up(baseQuantity), baseAsset(snapshot));
    }
    FullFillPricingProjection policy = fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT,
        side,
        FullFillExecutionPath.TRIGGERED_STOP_MARKET,
        null,
        snapshot);
    BigDecimal worstReference = triggerPrice.max(snapshot.ask());
    BigDecimal amount = baseQuantity.multiply(worstReference)
        .multiply(BigDecimal.ONE.add(policy.slippageRate()))
        .multiply(BigDecimal.ONE.add(policy.worstFeeRate()));
    return new OrderHold(up(amount), "USDT");
  }

  public OrderHold oco(
      OrderSide side,
      BigDecimal baseQuantity,
      BigDecimal limitPrice,
      BigDecimal stopTriggerPrice,
      ExecutableMarketSnapshot snapshot
  ) {
    OrderHold limitHold = limit(side, baseQuantity, limitPrice, snapshot);
    OrderHold stopHold = stopMarket(side, baseQuantity, stopTriggerPrice, snapshot);
    return limitHold.amount().compareTo(stopHold.amount()) >= 0 ? limitHold : stopHold;
  }

  private static BigDecimal up(BigDecimal value) {
    return value.setScale(WALLET_SCALE, RoundingMode.CEILING);
  }

  private static String baseAsset(ExecutableMarketSnapshot snapshot) {
    if (snapshot == null
        || snapshot.platformSymbol() == null
        || !snapshot.platformSymbol().endsWith("USDT")
        || snapshot.platformSymbol().length() <= 4) {
      throw new BusinessException("MARKET_BUNDLE_INCOMPLETE", "Spot symbol does not expose base asset");
    }
    return snapshot.platformSymbol().substring(0, snapshot.platformSymbol().length() - 4);
  }

  private static void requirePositive(BigDecimal value, String message) {
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("BAD_QUANTITY", message);
    }
  }

  public record OrderHold(BigDecimal amount, String currency) {
  }
}
