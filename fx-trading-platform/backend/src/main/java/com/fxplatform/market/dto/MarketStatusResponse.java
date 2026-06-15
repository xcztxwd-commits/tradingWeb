package com.fxplatform.market.dto;

/**
 * MarketStatusResponse 承载行情模块的数据结构。
 */
public record MarketStatusResponse(
    boolean massiveConfigured,
    boolean redisCacheEnabled,
    long quoteStaleMs,
    String status,
    boolean demoQuotesEnabled,
    String sourceMode,
    String providerStatus,
    String failureCode,
    String failureReason
) {
}
