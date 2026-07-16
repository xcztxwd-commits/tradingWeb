package com.fxplatform.execution;

import com.fxplatform.trading.enums.LiquidityRole;
import java.math.BigDecimal;

/** Side-effect-free pricing and policy projection for one immutable market bundle. */
public record FullFillPricingProjection(
    BigDecimal filledPrice,
    BigDecimal slippage,
    BigDecimal slippageRate,
    BigDecimal feeRate,
    BigDecimal worstFeeRate,
    LiquidityRole liquidityRole
) {
}
