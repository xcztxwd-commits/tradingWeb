package com.fxplatform.execution;

import com.fxplatform.trading.enums.LiquidityRole;

/** Explicit execution path; liquidity role is never inferred from order state. */
public enum FullFillExecutionPath {
  MARKET(LiquidityRole.TAKER, true),
  IMMEDIATE_LIMIT(LiquidityRole.TAKER, false),
  RESTING_LIMIT(LiquidityRole.MAKER, false),
  TRIGGERED_STOP_MARKET(LiquidityRole.TAKER, true);

  private final LiquidityRole liquidityRole;
  private final boolean marketPricing;

  FullFillExecutionPath(LiquidityRole liquidityRole, boolean marketPricing) {
    this.liquidityRole = liquidityRole;
    this.marketPricing = marketPricing;
  }

  public LiquidityRole liquidityRole() {
    return liquidityRole;
  }

  public boolean marketPricing() {
    return marketPricing;
  }
}
