package com.fxplatform.market.dto;

import java.math.BigDecimal;

/**
 * QuoteResponse 承载行情模块的数据结构。
 */
public record QuoteResponse(
    String type,
    String symbol,
    BigDecimal bid,
    BigDecimal ask,
    BigDecimal mid,
    BigDecimal spread,
    String source,
    long timestamp,
    BigDecimal changePercent,
    BigDecimal high24h,
    BigDecimal low24h,
    BigDecimal volume24h
) {
  public QuoteResponse(
      String type,
      String symbol,
      BigDecimal bid,
      BigDecimal ask,
      BigDecimal mid,
      BigDecimal spread,
      String source,
      long timestamp
  ) {
    this(type, symbol, bid, ask, mid, spread, source, timestamp, null, null, null, null);
  }
}
