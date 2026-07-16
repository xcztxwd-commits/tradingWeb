package com.fxplatform.market.dto;

import com.fxplatform.market.model.MarketSourceMode;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * RecentTradeResponse 承载行情模块的数据结构。
 */
public record RecentTradeResponse(
    String id,
    String symbol,
    BigDecimal price,
    BigDecimal amount,
    String side,
    long timestamp,
    String providerCode,
    String providerSymbol,
    MarketSourceMode sourceMode,
    Instant asOf,
    Instant expiresAt,
    boolean stale
) {

  public RecentTradeResponse(
      String id,
      String symbol,
      BigDecimal price,
      BigDecimal amount,
      String side,
      long timestamp
  ) {
    this(id, symbol, price, amount, side, timestamp, null, null, null, null, null, false);
  }
}
