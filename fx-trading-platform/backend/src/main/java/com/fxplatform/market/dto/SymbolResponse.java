package com.fxplatform.market.dto;

import java.math.BigDecimal;

/**
 * SymbolResponse 承载行情模块的数据结构。
 */
public record SymbolResponse(
    String symbol,
    String displayName,
    String assetClass,
    String baseCurrency,
    String quoteCurrency,
    BigDecimal minLot,
    BigDecimal maxLot,
    Integer leverage,
    boolean enabled,
    String provider,
    String providerSymbol,
    boolean tradable,
    BigDecimal lastPrice,
    BigDecimal changePercent,
    BigDecimal high24h,
    BigDecimal low24h,
    BigDecimal volume24h,
    BigDecimal marketCap,
    BigDecimal spread,
    Long quoteTimestamp,
    String quoteSource
) {
  public SymbolResponse(
      String symbol,
      String displayName,
      String assetClass,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal minLot,
      BigDecimal maxLot,
      Integer leverage,
      boolean enabled,
      String provider,
      String providerSymbol,
      boolean tradable
  ) {
    this(
        symbol,
        displayName,
        assetClass,
        baseCurrency,
        quoteCurrency,
        minLot,
        maxLot,
        leverage,
        enabled,
        provider,
        providerSymbol,
        tradable,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
