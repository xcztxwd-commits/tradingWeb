package com.fxplatform.trading.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * OrderResponse 承载交易模块的数据结构。
 */
public record OrderResponse(
    UUID id,
    UUID accountId,
    String symbol,
    String side,
    String orderType,
    Integer leverage,
    String status,
    BigDecimal lots,
    BigDecimal quantity,
    BigDecimal price,
    BigDecimal executionPrice,
    BigDecimal filledQuantity,
    BigDecimal remainingQuantity,
    BigDecimal avgFillPrice,
    BigDecimal fee,
    BigDecimal slippage,
    BigDecimal holdAmount,
    String holdCurrency,
    String rejectCode,
    String rejectMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant filledAt,
    Instant canceledAt
) {
}
