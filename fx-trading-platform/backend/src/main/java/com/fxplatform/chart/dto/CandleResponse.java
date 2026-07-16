package com.fxplatform.chart.dto;

import com.fxplatform.market.model.MarketSourceMode;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * CandleResponse 承载图表 K 线模块的数据结构。
 */
public record CandleResponse(
    long timestamp,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    BigDecimal volume,
    String providerCode,
    String providerSymbol,
    MarketSourceMode sourceMode,
    Instant asOf,
    Instant expiresAt,
    boolean stale
) {

  public CandleResponse(
      long timestamp,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      BigDecimal volume
  ) {
    this(timestamp, open, high, low, close, volume, null, null, null, null, null, false);
  }
}
