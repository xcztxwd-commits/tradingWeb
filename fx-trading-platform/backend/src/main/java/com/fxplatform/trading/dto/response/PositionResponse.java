package com.fxplatform.trading.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * PositionResponse 承载交易模块的数据结构。
 */
public record PositionResponse(
    UUID id,
    String symbol,
    String side,
    String instrumentType,
    String marginMode,
    Integer leverage,
    String positionUnit,
    BigDecimal lots,
    BigDecimal openPrice,
    BigDecimal markPrice,
    BigDecimal currentPrice,
    BigDecimal liquidationPrice,
    BigDecimal breakEvenPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit,
    BigDecimal floatingPnl,
    BigDecimal floatingPnlRatio,
    BigDecimal realizedPnl,
    BigDecimal marginHeld,
    BigDecimal maintenanceMargin,
    BigDecimal maintenanceMarginRate,
    Integer adlLevel,
    String status,
    Instant openedAt,
    Instant closedAt
) {
}
