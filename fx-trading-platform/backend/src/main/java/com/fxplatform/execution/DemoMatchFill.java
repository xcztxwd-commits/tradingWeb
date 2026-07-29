package com.fxplatform.execution;

import com.fxplatform.trading.enums.LiquidityRole;
import java.math.BigDecimal;

public record DemoMatchFill(
    BigDecimal quantity,
    BigDecimal price,
    LiquidityRole liquidityRole,
    BigDecimal feeRate
) {}
