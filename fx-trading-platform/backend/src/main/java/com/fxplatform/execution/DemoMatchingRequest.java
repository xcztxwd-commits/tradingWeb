package com.fxplatform.execution;

import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.TimeInForce;
import java.math.BigDecimal;

public record DemoMatchingRequest(
    String symbol,
    OrderSide side,
    OrderType orderType,
    TimeInForce timeInForce,
    BigDecimal requestedBaseQuantity,
    BigDecimal limitPrice,
    DemoExecutionPolicy policy,
    boolean postOnly,
    LiquidityRole liquidityRole,
    BigDecimal simpleFillPrice
) {}
