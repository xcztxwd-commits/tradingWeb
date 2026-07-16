package com.fxplatform.execution;

import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.trading.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Instant;

/** Single canonical all-or-nothing fill result. */
public record FullFillResult(
    BigDecimal filledPrice,
    Instant filledAt,
    BigDecimal filledQuantity,
    BigDecimal remainingQuantity,
    BigDecimal feeRate,
    BigDecimal fee,
    String feeAsset,
    LiquidityRole liquidityRole,
    BigDecimal slippage,
    MarketSourceMode sourceMode,
    String providerCode,
    String providerSymbol,
    Instant asOf,
    Instant expiresAt
) {

  public ExecutionResult toExecutionResult() {
    return new ExecutionResult(
        filledPrice,
        filledAt,
        filledQuantity,
        remainingQuantity,
        fee,
        feeAsset,
        slippage,
        null,
        null);
  }
}
