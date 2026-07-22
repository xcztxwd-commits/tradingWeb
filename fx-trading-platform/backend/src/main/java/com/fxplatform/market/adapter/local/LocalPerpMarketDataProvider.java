package com.fxplatform.market.adapter.local;

import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.provider.MarketBundleAssembler;
import com.fxplatform.market.provider.CandleRequestPolicy;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.MarketDataDurations;
import com.fxplatform.market.provider.MarketDataProviderAdapter;
import com.fxplatform.market.service.DemoMarketDataGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LocalPerpMarketDataProvider implements MarketDataProviderAdapter {

  private final DemoMarketDataGenerator generator;
  private final Clock clock;
  private final Duration freshness;
  private final BigDecimal simulatedPremium;
  private final BigDecimal premiumLimit;

  @Autowired
  public LocalPerpMarketDataProvider(
      @Value("${market.bundle-freshness:3s}") String freshnessValue,
      @Value("${market.local-perp.simulated-premium:0.0001}") BigDecimal simulatedPremium,
      @Value("${market.local-perp.premium-limit:0.001}") BigDecimal premiumLimit
  ) {
    this(
        new DemoMarketDataGenerator(),
        Clock.systemUTC(),
        MarketDataDurations.parsePositive(freshnessValue, Duration.ofSeconds(3)),
        simulatedPremium,
        premiumLimit);
  }

  public LocalPerpMarketDataProvider(
      DemoMarketDataGenerator generator,
      Clock clock,
      Duration freshness,
      BigDecimal simulatedPremium,
      BigDecimal premiumLimit
  ) {
    this.generator = generator;
    this.clock = clock;
    this.freshness = MarketDataDurations.positive(freshness, Duration.ofSeconds(3));
    this.simulatedPremium = simulatedPremium;
    this.premiumLimit = premiumLimit.abs();
  }

  @Override
  public String code() {
    return "local-perp";
  }

  @Override
  public Set<MarketDataCapability> capabilities() {
    return Set.of(MarketDataCapability.QUOTE, MarketDataCapability.CANDLES,
        MarketDataCapability.ORDER_BOOK, MarketDataCapability.TRADES);
  }

  @Override
  public boolean configured() {
    return true;
  }

  @Override
  public Optional<PerpetualMarketBundle> fetchPerpetualBundle(
      String platformSymbol,
      String providerSymbol,
      CandleRequest candleRequest
  ) {
    if (!CandleRequestPolicy.isValid(candleRequest)) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    long tick = now.toEpochMilli() / 1000L;
    String spotSymbol = platformSymbol.substring(0, platformSymbol.length() - "-PERP".length());
    QuoteResponse spot = generator.quote(spotSymbol, tick, now);
    BigDecimal index = spot.mid();
    BigDecimal premium = simulatedPremium.max(premiumLimit.negate()).min(premiumLimit);
    BigDecimal mark = index.multiply(BigDecimal.ONE.add(premium))
        .setScale(10, RoundingMode.HALF_UP);
    BigDecimal halfSpread = spot.ask().subtract(spot.bid()).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
    BigDecimal bid = mark.subtract(halfSpread).max(BigDecimal.ZERO).setScale(10, RoundingMode.HALF_UP);
    BigDecimal ask = mark.add(halfSpread).setScale(10, RoundingMode.HALF_UP);
    QuoteResponse quote = new QuoteResponse(
        "quote", platformSymbol, bid, ask, mark, ask.subtract(bid), "local-perp", now.toEpochMilli(),
        spot.changePercent(),
        spot.high24h().multiply(BigDecimal.ONE.add(premium)).setScale(10, RoundingMode.HALF_UP),
        spot.low24h().multiply(BigDecimal.ONE.add(premium)).setScale(10, RoundingMode.HALF_UP),
        spot.volume24h());
    var depth = generator.depth(platformSymbol, quote);
    var trades = java.util.stream.LongStream.range(0, 20)
        .mapToObj(offset -> generator.trade(platformSymbol, tick + offset, quote))
        .toList();
    var candles = LocalMarketDataSupport.candles(candleRequest, mark);
    if (candles.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(MarketBundleAssembler.perpetual(
        platformSymbol,
        providerSymbol == null || providerSymbol.isBlank() ? platformSymbol : providerSymbol,
        code(),
        MarketSourceMode.LOCAL_SIMULATED,
        quote,
        mark,
        index,
        depth,
        trades,
        candles,
        MarketBundleAssembler.ComponentObservations.perpetual(now, now, now, now, now),
        freshness));
  }
}
