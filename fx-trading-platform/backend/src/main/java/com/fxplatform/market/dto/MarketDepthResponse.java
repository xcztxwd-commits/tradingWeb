package com.fxplatform.market.dto;

import com.fxplatform.market.model.MarketSourceMode;
import java.time.Instant;
import java.util.List;

/**
 * MarketDepthResponse 承载行情模块的数据结构。
 */
public record MarketDepthResponse(
    String symbol,
    long timestamp,
    List<MarketDepthLevelResponse> bids,
    List<MarketDepthLevelResponse> asks,
    String providerCode,
    String providerSymbol,
    MarketSourceMode sourceMode,
    Instant asOf,
    Instant expiresAt,
    boolean stale
) {

  public MarketDepthResponse {
    bids = bids == null ? null : List.copyOf(bids);
    asks = asks == null ? null : List.copyOf(asks);
  }

  public MarketDepthResponse(
      String symbol,
      long timestamp,
      List<MarketDepthLevelResponse> bids,
      List<MarketDepthLevelResponse> asks
  ) {
    this(symbol, timestamp, bids, asks, null, null, null, null, null, false);
  }
}
