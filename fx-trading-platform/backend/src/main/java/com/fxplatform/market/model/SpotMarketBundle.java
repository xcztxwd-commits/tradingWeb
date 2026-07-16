package com.fxplatform.market.model;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SpotMarketBundle(
    String platformSymbol,
    String providerSymbol,
    String providerCode,
    MarketSourceMode sourceMode,
    BigDecimal bid,
    BigDecimal ask,
    BigDecimal last,
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

  public SpotMarketBundle {
    recentTrades = recentTrades == null ? null : List.copyOf(recentTrades);
    candles = candles == null ? null : List.copyOf(candles);
  }

  public SpotMarketBundle(
      String platformSymbol,
      String providerSymbol,
      String providerCode,
      MarketSourceMode sourceMode,
      BigDecimal bid,
      BigDecimal ask,
      BigDecimal last,
      MarketDepthResponse orderBook,
      List<RecentTradeResponse> recentTrades,
      List<CandleResponse> candles,
      Instant asOf,
      Instant expiresAt
  ) {
    this(platformSymbol, providerSymbol, providerCode, sourceMode, bid, ask, last,
        null, null, null, null, orderBook, recentTrades, candles, asOf, expiresAt);
  }

  public SpotMarketBundle withOrderBook(MarketDepthResponse replacement) {
    return new SpotMarketBundle(
        platformSymbol,
        providerSymbol,
        providerCode,
        sourceMode,
        bid,
        ask,
        last,
        changePercent,
        high24h,
        low24h,
        volume24h,
        replacement,
        recentTrades,
        candles,
        asOf,
        expiresAt);
  }
}
