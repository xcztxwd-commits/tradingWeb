package com.fxplatform.market.dto;

import java.math.BigDecimal;

/**
 * MarketDepthLevelResponse 承载行情模块的数据结构。
 */
public record MarketDepthLevelResponse(
    BigDecimal price,
    BigDecimal amount
) {
}
