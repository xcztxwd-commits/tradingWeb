package com.fxplatform.market.provider;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.SpotMarketBundle;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class MarketBundleAssembler {

  private MarketBundleAssembler() {
  }

  public static SpotMarketBundle spot(
      String platformSymbol,
      String providerSymbol,
      String providerCode,
      MarketSourceMode sourceMode,
      QuoteResponse quote,
      MarketDepthResponse depth,
      List<RecentTradeResponse> trades,
      List<CandleResponse> candles,
      ComponentObservations observations,
      Duration freshness
  ) {
    Instant asOf = observations.oldestRequired(false);
    Instant expiresAt = asOf.plus(freshness);
    return new SpotMarketBundle(
        platformSymbol,
        providerSymbol,
        providerCode,
        sourceMode,
        quote.bid(),
        quote.ask(),
        quote.mid(),
        quote.changePercent(),
        quote.high24h(),
        quote.low24h(),
        quote.volume24h(),
        decorate(depth, platformSymbol, providerSymbol, providerCode, sourceMode, asOf, expiresAt),
        decorateTrades(trades, platformSymbol, providerSymbol, providerCode, sourceMode, asOf, expiresAt),
        decorateCandles(candles, providerSymbol, providerCode, sourceMode, asOf, expiresAt),
        asOf,
        expiresAt);
  }

  public static PerpetualMarketBundle perpetual(
      String platformSymbol,
      String providerSymbol,
      String providerCode,
      MarketSourceMode sourceMode,
      QuoteResponse quote,
      BigDecimal mark,
      BigDecimal index,
      MarketDepthResponse depth,
      List<RecentTradeResponse> trades,
      List<CandleResponse> candles,
      ComponentObservations observations,
      Duration freshness
  ) {
    Instant asOf = observations.oldestRequired(true);
    Instant expiresAt = asOf.plus(freshness);
    return new PerpetualMarketBundle(
        platformSymbol,
        providerSymbol,
        providerCode,
        sourceMode,
        quote.bid(),
        quote.ask(),
        quote.mid(),
        mark,
        index,
        quote.changePercent(),
        quote.high24h(),
        quote.low24h(),
        quote.volume24h(),
        decorate(depth, platformSymbol, providerSymbol, providerCode, sourceMode, asOf, expiresAt),
        decorateTrades(trades, platformSymbol, providerSymbol, providerCode, sourceMode, asOf, expiresAt),
        decorateCandles(candles, providerSymbol, providerCode, sourceMode, asOf, expiresAt),
        asOf,
        expiresAt);
  }

  public static QuoteResponse quote(SpotMarketBundle bundle, Clock clock) {
    return quote(
        bundle.platformSymbol(), bundle.providerSymbol(), bundle.providerCode(), bundle.sourceMode(),
        bundle.bid(), bundle.ask(), bundle.last(), null,
        bundle.changePercent(), bundle.high24h(), bundle.low24h(), bundle.volume24h(),
        bundle.asOf(), bundle.expiresAt(), clock);
  }

  public static QuoteResponse quote(PerpetualMarketBundle bundle, Clock clock) {
    return quote(
        bundle.platformSymbol(), bundle.providerSymbol(), bundle.providerCode(), bundle.sourceMode(),
        bundle.bid(), bundle.ask(), bundle.last(), bundle.mark(),
        bundle.changePercent(), bundle.high24h(), bundle.low24h(), bundle.volume24h(),
        bundle.asOf(), bundle.expiresAt(), clock);
  }

  public static Instant snapshotObservedAt(long venueTimestampMillis, Instant fetchedAt) {
    if (venueTimestampMillis <= 0) {
      return java.util.Objects.requireNonNull(fetchedAt, "fetchedAt");
    }
    try {
      return snapshotObservedAt(Instant.ofEpochMilli(venueTimestampMillis), fetchedAt);
    } catch (java.time.DateTimeException ignored) {
      return java.util.Objects.requireNonNull(fetchedAt, "fetchedAt");
    }
  }

  public static Instant snapshotObservedAt(Instant venueObservedAt, Instant fetchedAt) {
    java.util.Objects.requireNonNull(fetchedAt, "fetchedAt");
    return venueObservedAt == null ? fetchedAt : earlier(venueObservedAt, fetchedAt);
  }

  private static QuoteResponse quote(
      String platformSymbol,
      String providerSymbol,
      String providerCode,
      MarketSourceMode sourceMode,
      BigDecimal bid,
      BigDecimal ask,
      BigDecimal last,
      BigDecimal mark,
      BigDecimal changePercent,
      BigDecimal high24h,
      BigDecimal low24h,
      BigDecimal volume24h,
      Instant asOf,
      Instant expiresAt,
      Clock clock
  ) {
    return new QuoteResponse(
        "quote",
        platformSymbol,
        bid,
        ask,
        last,
        mark,
        ask.subtract(bid),
        providerCode,
        asOf.toEpochMilli(),
        changePercent,
        high24h,
        low24h,
        volume24h,
        sourceMode,
        providerCode,
        providerSymbol,
        asOf,
        expiresAt,
        !clock.instant().isBefore(expiresAt));
  }

  private static Instant earlier(Instant left, Instant right) {
    if (right == null) {
      return left;
    }
    return right.isBefore(left) ? right : left;
  }

  private static MarketDepthResponse decorate(
      MarketDepthResponse depth,
      String symbol,
      String providerSymbol,
      String providerCode,
      MarketSourceMode sourceMode,
      Instant asOf,
      Instant expiresAt
  ) {
    return new MarketDepthResponse(
        symbol,
        depth.timestamp(),
        depth.bids(),
        depth.asks(),
        providerCode,
        providerSymbol,
        sourceMode,
        asOf,
        expiresAt,
        false);
  }

  private static List<RecentTradeResponse> decorateTrades(
      List<RecentTradeResponse> trades,
      String symbol,
      String providerSymbol,
      String providerCode,
      MarketSourceMode sourceMode,
      Instant asOf,
      Instant expiresAt
  ) {
    return trades.stream().map(trade -> new RecentTradeResponse(
        trade.id(),
        symbol,
        trade.price(),
        trade.amount(),
        trade.side(),
        trade.timestamp(),
        providerCode,
        providerSymbol,
        sourceMode,
        asOf,
        expiresAt,
        false)).toList();
  }

  private static List<CandleResponse> decorateCandles(
      List<CandleResponse> candles,
      String providerSymbol,
      String providerCode,
      MarketSourceMode sourceMode,
      Instant asOf,
      Instant expiresAt
  ) {
    return candles.stream().map(candle -> new CandleResponse(
        candle.timestamp(),
        candle.open(),
        candle.high(),
        candle.low(),
        candle.close(),
        candle.volume(),
        providerCode,
        providerSymbol,
        sourceMode,
        asOf,
        expiresAt,
        false)).toList();
  }

  public record ComponentObservations(
      Instant quoteObservedAt,
      Instant depthObservedAt,
      Instant tradesFetchedAt,
      Instant candlesFetchedAt,
      Instant referenceObservedAt
  ) {

    public static ComponentObservations spot(
        Instant quoteObservedAt,
        Instant depthObservedAt,
        Instant tradesFetchedAt,
        Instant candlesFetchedAt
    ) {
      return new ComponentObservations(
          quoteObservedAt, depthObservedAt, tradesFetchedAt, candlesFetchedAt, null);
    }

    public static ComponentObservations perpetual(
        Instant quoteObservedAt,
        Instant depthObservedAt,
        Instant tradesFetchedAt,
        Instant candlesFetchedAt,
        Instant referenceObservedAt
    ) {
      return new ComponentObservations(
          quoteObservedAt, depthObservedAt, tradesFetchedAt, candlesFetchedAt, referenceObservedAt);
    }

    private Instant oldestRequired(boolean perpetual) {
      java.util.Objects.requireNonNull(quoteObservedAt, "quoteObservedAt");
      java.util.Objects.requireNonNull(depthObservedAt, "depthObservedAt");
      java.util.Objects.requireNonNull(tradesFetchedAt, "tradesFetchedAt");
      java.util.Objects.requireNonNull(candlesFetchedAt, "candlesFetchedAt");
      if (perpetual) {
        java.util.Objects.requireNonNull(referenceObservedAt, "referenceObservedAt");
      }
      Instant oldest = earlier(quoteObservedAt, depthObservedAt);
      oldest = earlier(oldest, tradesFetchedAt);
      oldest = earlier(oldest, candlesFetchedAt);
      return perpetual ? earlier(oldest, referenceObservedAt) : oldest;
    }
  }
}
