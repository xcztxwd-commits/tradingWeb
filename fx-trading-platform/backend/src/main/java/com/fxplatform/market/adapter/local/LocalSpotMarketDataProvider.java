package com.fxplatform.market.adapter.local;

import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleAssembler;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.MarketDataDurations;
import com.fxplatform.market.provider.MarketDataProviderAdapter;
import com.fxplatform.market.service.DemoMarketDataGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LocalSpotMarketDataProvider implements MarketDataProviderAdapter {

  private final DemoMarketDataGenerator generator;
  private final Clock clock;
  private final Duration freshness;

  @Autowired
  public LocalSpotMarketDataProvider(
      @Value("${market.bundle-freshness:3s}") String freshnessValue
  ) {
    this(
        new DemoMarketDataGenerator(),
        Clock.systemUTC(),
        MarketDataDurations.parsePositive(freshnessValue, Duration.ofSeconds(3)));
  }

  public LocalSpotMarketDataProvider(DemoMarketDataGenerator generator, Clock clock, Duration freshness) {
    this.generator = generator;
    this.clock = clock;
    this.freshness = MarketDataDurations.positive(freshness, Duration.ofSeconds(3));
  }

  @Override
  public String code() {
    return "local-spot";
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
  public Optional<SpotMarketBundle> fetchSpotBundle(
      String platformSymbol,
      String providerSymbol,
      CandleRequest candleRequest
  ) {
    Instant now = clock.instant();
    long tick = now.toEpochMilli() / 1000L;
    QuoteResponse quote = generator.quote(platformSymbol, tick, now);
    var depth = generator.depth(platformSymbol, quote);
    var trades = java.util.stream.LongStream.range(0, 20)
        .mapToObj(index -> generator.trade(platformSymbol, tick + index, quote))
        .toList();
    var candles = LocalMarketDataSupport.candles(candleRequest, quote.mid(), now);
    return Optional.of(MarketBundleAssembler.spot(
        platformSymbol,
        providerSymbol == null || providerSymbol.isBlank() ? platformSymbol : providerSymbol,
        code(),
        MarketSourceMode.LOCAL_SIMULATED,
        quote,
        depth,
        trades,
        candles,
        MarketBundleAssembler.ComponentObservations.spot(now, now, now, now),
        freshness));
  }
}
