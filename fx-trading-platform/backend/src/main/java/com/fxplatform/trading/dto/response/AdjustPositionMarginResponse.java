package com.fxplatform.trading.dto.response;

import com.fxplatform.trading.dto.request.AdjustPositionMarginRequest.Action;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionSide;
import java.math.BigDecimal;
import java.util.UUID;

public record AdjustPositionMarginResponse(
    UUID accountId,
    UUID positionId,
    String symbol,
    PositionSide positionSide,
    MarginMode marginMode,
    Action action,
    BigDecimal amount,
    BigDecimal initialMargin,
    BigDecimal positionMargin,
    BigDecimal isolatedEquity,
    BigDecimal maintenanceMargin,
    BigDecimal estimatedCloseFee,
    BigDecimal estimatedLiquidationPrice,
    BigDecimal accountUsedMargin,
    BigDecimal accountFreeMargin,
    Long version
) {
}
