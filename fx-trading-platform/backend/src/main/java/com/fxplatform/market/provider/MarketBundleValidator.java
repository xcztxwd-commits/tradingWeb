package com.fxplatform.market.provider;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.SpotMarketBundle;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MarketBundleValidator {

  private final Clock clock;

  public MarketBundleValidator() {
    this(Clock.systemUTC());
  }

  public MarketBundleValidator(Clock clock) {
    this.clock = clock;
  }

  public boolean valid(SpotMarketBundle bundle) {
    return bundle != null
        && identityPresent(bundle.platformSymbol(), bundle.providerSymbol(), bundle.providerCode(), bundle.sourceMode())
        && validPrices(bundle.bid(), bundle.ask(), bundle.last())
        && validFreshness(bundle.asOf(), bundle.expiresAt())
        && validDepth(bundle.orderBook(), bundle)
        && validTrades(bundle.recentTrades(), bundle)
        && validCandles(bundle.candles(), bundle);
  }

  public boolean valid(PerpetualMarketBundle bundle) {
    return bundle != null
        && identityPresent(bundle.platformSymbol(), bundle.providerSymbol(), bundle.providerCode(), bundle.sourceMode())
        && validPrices(bundle.bid(), bundle.ask(), bundle.last())
        && positive(bundle.mark())
        && positive(bundle.index())
        && validFreshness(bundle.asOf(), bundle.expiresAt())
        && validDepth(bundle.orderBook(), bundle.platformSymbol(), bundle.providerSymbol(), bundle.providerCode(),
            bundle.sourceMode(), bundle.asOf(), bundle.expiresAt())
        && validTrades(bundle.recentTrades(), bundle.platformSymbol(), bundle.providerSymbol(), bundle.providerCode(),
            bundle.sourceMode(), bundle.asOf(), bundle.expiresAt())
        && validCandles(bundle.candles(), bundle.providerSymbol(), bundle.providerCode(), bundle.sourceMode(),
            bundle.asOf(), bundle.expiresAt());
  }

  private boolean validDepth(MarketDepthResponse depth, SpotMarketBundle bundle) {
    return validDepth(depth, bundle.platformSymbol(), bundle.providerSymbol(), bundle.providerCode(), bundle.sourceMode(),
        bundle.asOf(), bundle.expiresAt());
  }

  private boolean validDepth(
      MarketDepthResponse depth,
      String symbol,
      String providerSymbol,
      String providerCode,
      Object sourceMode,
      Instant asOf,
      Instant expiresAt
  ) {
    return depth != null
        && symbol.equals(depth.symbol())
        && providerCode.equals(depth.providerCode())
        && providerSymbol.equals(depth.providerSymbol())
        && sourceMode.equals(depth.sourceMode())
        && asOf.equals(depth.asOf())
        && expiresAt.equals(depth.expiresAt())
        && !depth.stale()
        && depth.bids() != null && !depth.bids().isEmpty()
        && depth.asks() != null && !depth.asks().isEmpty()
        && depth.bids().stream().allMatch(level -> positive(level.price()) && positive(level.amount()))
        && depth.asks().stream().allMatch(level -> positive(level.price()) && positive(level.amount()));
  }

  private boolean validTrades(List<RecentTradeResponse> trades, SpotMarketBundle bundle) {
    return validTrades(trades, bundle.platformSymbol(), bundle.providerSymbol(), bundle.providerCode(), bundle.sourceMode(),
        bundle.asOf(), bundle.expiresAt());
  }

  private boolean validTrades(
      List<RecentTradeResponse> trades,
      String symbol,
      String providerSymbol,
      String providerCode,
      Object sourceMode,
      Instant asOf,
      Instant expiresAt
  ) {
    return trades != null && !trades.isEmpty() && trades.stream().allMatch(trade ->
        symbol.equals(trade.symbol())
            && providerCode.equals(trade.providerCode())
            && providerSymbol.equals(trade.providerSymbol())
            && sourceMode.equals(trade.sourceMode())
            && asOf.equals(trade.asOf())
            && expiresAt.equals(trade.expiresAt())
            && !trade.stale()
            && positive(trade.price())
            && positive(trade.amount()));
  }

  private boolean validCandles(List<CandleResponse> candles, SpotMarketBundle bundle) {
    return validCandles(candles, bundle.providerSymbol(), bundle.providerCode(), bundle.sourceMode(),
        bundle.asOf(), bundle.expiresAt());
  }

  private boolean validCandles(
      List<CandleResponse> candles,
      String providerSymbol,
      String providerCode,
      Object sourceMode,
      Instant asOf,
      Instant expiresAt
  ) {
    return candles != null && !candles.isEmpty() && candles.stream().allMatch(candle ->
        providerCode.equals(candle.providerCode())
            && providerSymbol.equals(candle.providerSymbol())
            && sourceMode.equals(candle.sourceMode())
            && asOf.equals(candle.asOf())
            && expiresAt.equals(candle.expiresAt())
            && !candle.stale()
            && validCandle(candle));
  }

  private boolean identityPresent(String platformSymbol, String providerSymbol, String providerCode, Object sourceMode) {
    return platformSymbol != null && !platformSymbol.isBlank()
        && providerSymbol != null && !providerSymbol.isBlank()
        && providerCode != null && !providerCode.isBlank()
        && sourceMode != null;
  }

  private boolean validPrices(BigDecimal bid, BigDecimal ask, BigDecimal last) {
    return positive(bid) && positive(ask) && positive(last) && bid.compareTo(ask) <= 0;
  }

  private boolean positive(BigDecimal value) {
    return value != null && value.signum() > 0;
  }

  private boolean validCandle(CandleResponse candle) {
    if (candle.timestamp() <= 0
        || !positive(candle.open())
        || !positive(candle.high())
        || !positive(candle.low())
        || !positive(candle.close())
        || candle.volume() == null
        || candle.volume().signum() < 0) {
      return false;
    }
    BigDecimal bodyHigh = candle.open().max(candle.close());
    BigDecimal bodyLow = candle.open().min(candle.close());
    return candle.high().compareTo(bodyHigh) >= 0
        && candle.high().compareTo(candle.low()) >= 0
        && candle.low().compareTo(bodyLow) <= 0;
  }

  private boolean validFreshness(Instant asOf, Instant expiresAt) {
    Instant now = clock.instant();
    return asOf != null && expiresAt != null
        && !expiresAt.isBefore(asOf)
        && now.isBefore(expiresAt);
  }
}
