package com.fxplatform.market.dto;

import java.math.BigDecimal;

/**
 * RecentTradeResponse 承载行情模块的数据结构。
 */
public record RecentTradeResponse(
    String id,
    String symbol,
    BigDecimal price,
    BigDecimal amount,
    String side,
    long timestamp
) {
}
