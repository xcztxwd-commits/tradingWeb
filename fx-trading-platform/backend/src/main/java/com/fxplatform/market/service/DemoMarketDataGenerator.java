package com.fxplatform.market.service;

import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DemoMarketDataGenerator 是行情模块的业务服务。
 */
public class DemoMarketDataGenerator {

  private static final BigDecimal TWO = new BigDecimal("2");
  private static final int DEPTH_LEVELS = 100;

  public QuoteResponse quote(String symbol, long tick, Instant now) {
    Profile profile = profile(symbol);
    double wave = Math.sin((tick + symbol.length()) / 17.0) * profile.spread().doubleValue() * 30.0;
    double drift = Math.cos((tick + symbol.charAt(0)) / 53.0) * profile.spread().doubleValue() * 10.0;
    BigDecimal mid = scale(profile.base().add(BigDecimal.valueOf(wave + drift)), profile.priceScale());
    BigDecimal halfSpread = profile.spread().divide(TWO, profile.priceScale(), RoundingMode.HALF_UP);
    BigDecimal bid = scale(mid.subtract(halfSpread), profile.priceScale());
    BigDecimal ask = scale(mid.add(halfSpread), profile.priceScale());

    return new QuoteResponse("quote", symbol, bid, ask, mid, ask.subtract(bid), "demo-realtime", now.toEpochMilli());
  }

  public MarketDepthResponse depth(String symbol, QuoteResponse quote) {
    Profile profile = profile(symbol);
    List<MarketDepthLevelResponse> bids = new ArrayList<>(DEPTH_LEVELS);
    List<MarketDepthLevelResponse> asks = new ArrayList<>(DEPTH_LEVELS);

    for (int index = 0; index < DEPTH_LEVELS; index += 1) {
      BigDecimal offset = profile.tickSize().multiply(BigDecimal.valueOf(index + 1L));
      bids.add(new MarketDepthLevelResponse(
          scale(quote.bid().subtract(offset), profile.priceScale()),
          amount(profile, index, 1)));
      asks.add(new MarketDepthLevelResponse(
          scale(quote.ask().add(offset), profile.priceScale()),
          amount(profile, index, -1)));
    }

    return new MarketDepthResponse(symbol, quote.timestamp(), bids, asks);
  }

  public RecentTradeResponse trade(String symbol, long tick, QuoteResponse quote) {
    Profile profile = profile(symbol);
    String side = tick % 2 == 0 ? "buy" : "sell";
    return new RecentTradeResponse(
        symbol + "-" + quote.timestamp() + "-" + tick,
        symbol,
        quote.mid(),
        amount(profile, Math.toIntExact(Math.floorMod(tick, DEPTH_LEVELS)), side.equals("buy") ? 1 : -1),
        side,
        quote.timestamp());
  }

  public Instant candleOpenTime(Instant timestamp, String timeframe) {
    long seconds = switch (timeframe) {
      case "1s" -> 1L;
      case "5m" -> 300L;
      case "15m" -> 900L;
      case "1h" -> 3600L;
      default -> 60L;
    };
    long epochSecond = timestamp.getEpochSecond();
    return Instant.ofEpochSecond((epochSecond / seconds) * seconds);
  }

  private BigDecimal amount(Profile profile, int index, int sideSeed) {
    double wave = 0.6 + Math.abs(Math.sin((index + 1 + sideSeed) / 4.0)) * 2.8;
    return profile.amountBase().multiply(BigDecimal.valueOf(wave)).setScale(8, RoundingMode.HALF_UP);
  }

  private BigDecimal scale(BigDecimal value, int scale) {
    return value.setScale(scale, RoundingMode.HALF_UP);
  }

  private Profile profile(String symbol) {
    return switch (symbol) {
      case "BTCUSDT" -> new Profile(new BigDecimal("67240"), new BigDecimal("4.2"), new BigDecimal("0.1"), new BigDecimal("0.24"), 10);
      case "ETHUSDT" -> new Profile(new BigDecimal("3420"), new BigDecimal("0.9"), new BigDecimal("0.1"), new BigDecimal("2.8"), 10);
      case "XAUUSD" -> new Profile(new BigDecimal("2348.4"), new BigDecimal("0.28"), new BigDecimal("0.1"), new BigDecimal("12"), 10);
      case "US100" -> new Profile(new BigDecimal("18924.6"), new BigDecimal("1.2"), new BigDecimal("0.1"), new BigDecimal("4.4"), 10);
      case "GBPUSD" -> new Profile(new BigDecimal("1.27120"), new BigDecimal("0.00005"), new BigDecimal("0.00001"), new BigDecimal("140"), 10);
      case "USDJPY" -> new Profile(new BigDecimal("156.420"), new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("28"), 10);
      case "AUDUSD" -> new Profile(new BigDecimal("0.66420"), new BigDecimal("0.00004"), new BigDecimal("0.00001"), new BigDecimal("180"), 10);
      default -> new Profile(new BigDecimal("1.08320"), new BigDecimal("0.00004"), new BigDecimal("0.00001"), new BigDecimal("160"), 10);
    };
  }

  /**
   * Profile 承载行情模块的数据结构。
   */
  private record Profile(
      BigDecimal base,
      BigDecimal spread,
      BigDecimal tickSize,
      BigDecimal amountBase,
      int priceScale
  ) {
  }
}
