package com.fxplatform.market.dto;

import com.fxplatform.market.model.MarketSourceMode;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * QuoteResponse 承载行情模块的数据结构。
 */
public record QuoteResponse(
    String type,
    String symbol,
    BigDecimal bid,
    BigDecimal ask,
    BigDecimal mid,
    BigDecimal markPrice,
    BigDecimal spread,
    String source,
    long timestamp,
    BigDecimal changePercent,
    BigDecimal high24h,
    BigDecimal low24h,
    BigDecimal volume24h,
    MarketSourceMode sourceMode,
    String providerCode,
    String providerSymbol,
    Instant asOf,
    Instant expiresAt,
    boolean stale
) {
  public QuoteResponse(
      String type,
      String symbol,
      BigDecimal bid,
      BigDecimal ask,
      BigDecimal mid,
      BigDecimal markPrice,
      BigDecimal spread,
      String source,
      long timestamp,
      BigDecimal changePercent,
      BigDecimal high24h,
      BigDecimal low24h,
      BigDecimal volume24h
  ) {
    this(type, symbol, bid, ask, mid, markPrice, spread, source, timestamp,
        changePercent, high24h, low24h, volume24h, null, null, null, null, null, false);
  }

  public QuoteResponse(
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
    this(type, symbol, bid, ask, mid, null, spread, source, timestamp,
        changePercent, high24h, low24h, volume24h, null, null, null, null, null, false);
  }

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
    this(type, symbol, bid, ask, mid, null, spread, source, timestamp,
        null, null, null, null, null, null, null, null, null, false);
  }
}
