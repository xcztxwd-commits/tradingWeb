package com.fxplatform.market.model;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PerpetualMarketBundle(
    String platformSymbol,
    String providerSymbol,
    String providerCode,
    MarketSourceMode sourceMode,
    BigDecimal bid,
    BigDecimal ask,
    BigDecimal last,
    BigDecimal mark,
    BigDecimal index,
    BigDecimal changePercent,
    BigDecimal high24h,
    BigDecimal low24h,
    BigDecimal volume24h,
    MarketDepthResponse orderBook,
    List<RecentTradeResponse> recentTrades,
    List<CandleResponse> candles,
    Instant asOf,
    Instant expiresAt
) {

  public PerpetualMarketBundle {
    recentTrades = recentTrades == null ? null : List.copyOf(recentTrades);
    candles = candles == null ? null : List.copyOf(candles);
  }

  public PerpetualMarketBundle(
      String platformSymbol,
      String providerSymbol,
      String providerCode,
      MarketSourceMode sourceMode,
      BigDecimal bid,
      BigDecimal ask,
      BigDecimal last,
      BigDecimal mark,
      BigDecimal index,
      MarketDepthResponse orderBook,
      List<RecentTradeResponse> recentTrades,
      List<CandleResponse> candles,
      Instant asOf,
      Instant expiresAt
  ) {
    this(platformSymbol, providerSymbol, providerCode, sourceMode, bid, ask, last, mark, index,
        null, null, null, null, orderBook, recentTrades, candles, asOf, expiresAt);
  }
}
