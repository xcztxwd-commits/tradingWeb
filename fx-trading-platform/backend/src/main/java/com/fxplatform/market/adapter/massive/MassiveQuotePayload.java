package com.fxplatform.market.adapter.massive;

import java.math.BigDecimal;

/**
 * MassiveQuotePayload 承载行情模块的数据结构。
 */
public record MassiveQuotePayload(
    String providerSymbol,
    BigDecimal bid,
    BigDecimal ask,
    long timestamp
) {
}
