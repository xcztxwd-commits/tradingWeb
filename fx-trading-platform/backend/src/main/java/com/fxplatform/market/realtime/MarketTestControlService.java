package com.fxplatform.market.realtime;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.SpotMarketBundle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MarketTestControlService {

  private static final String SOURCE = "test-control";
  private static final Duration DEFAULT_MAX_TTL = Duration.ofMinutes(5);

  private final MarketTestControlProperties properties;
  private final RealtimeBackfillService backfillService;
  private final Clock clock;
  private final ConcurrentHashMap<String, OverrideState> overrides = new ConcurrentHashMap<>();
  private final AtomicLong revision = new AtomicLong();

  @Autowired
  public MarketTestControlService(
      MarketTestControlProperties properties,
      RealtimeBackfillService backfillService
  ) {
    this(properties, backfillService, Clock.systemUTC());
  }

  MarketTestControlService(
      MarketTestControlProperties properties,
      RealtimeBackfillService backfillService,
      Clock clock
  ) {
    this.properties = properties;
    this.backfillService = backfillService;
    this.clock = clock;
  }

  public QuoteResponse startOverride(MarketTestControlRequest request) {
    if (!properties.enabled()) {
      throw new BusinessException("MARKET_TEST_CONTROL_DISABLED", "Market test control is disabled");
    }
    String symbol = normalizeSymbol(request.symbol());
    validateQuote(request.bid(), request.ask());
    Duration ttl = effectiveTtl(request.ttl());
    OverrideState state = new OverrideState(
        symbol,
        request.bid(),
        request.ask(),
        clock.instant().plus(ttl));
    overrides.put(symbol, state);
    revision.incrementAndGet();
    return state.quote(clock.millis());
  }

  public long authorityRevision(String requestedSymbol) {
    activeOverride(requestedSymbol);
    return revision.get();
  }

  public Optional<QuoteResponse> overrideQuote(String symbol) {
    return activeOverride(symbol).map(state -> state.quote(clock.millis()));
  }

  public SpotMarketBundle applySpotOverride(SpotMarketBundle providerBundle) {
    return overrideQuote(providerBundle.platformSymbol())
        .map(quote -> new SpotMarketBundle(
            providerBundle.platformSymbol(),
            providerBundle.providerSymbol(),
            providerBundle.providerCode(),
            providerBundle.sourceMode(),
            quote.bid(),
            quote.ask(),
            quote.mid(),
            providerBundle.changePercent(),
            providerBundle.high24h(),
            providerBundle.low24h(),
            providerBundle.volume24h(),
            providerBundle.orderBook(),
            providerBundle.recentTrades(),
            providerBundle.candles(),
            providerBundle.asOf(),
            providerBundle.expiresAt()))
        .orElse(providerBundle);
  }

  public PerpetualMarketBundle applyPerpetualOverride(PerpetualMarketBundle providerBundle) {
    return overrideQuote(providerBundle.platformSymbol())
        .map(quote -> new PerpetualMarketBundle(
            providerBundle.platformSymbol(),
            providerBundle.providerSymbol(),
            providerBundle.providerCode(),
            providerBundle.sourceMode(),
            quote.bid(),
            quote.ask(),
            quote.mid(),
            quote.mid(),
            quote.mid(),
            providerBundle.changePercent(),
            providerBundle.high24h(),
            providerBundle.low24h(),
            providerBundle.volume24h(),
            providerBundle.orderBook(),
            providerBundle.recentTrades(),
            providerBundle.candles(),
            providerBundle.asOf(),
            providerBundle.expiresAt()))
        .orElse(providerBundle);
  }

  public void endOverride(String requestedSymbol) {
    String symbol = normalizeSymbol(requestedSymbol);
    if (overrides.remove(symbol) != null) {
      revision.incrementAndGet();
      backfillService.backfill(symbol);
    }
  }

  private String normalizeSymbol(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      throw new BusinessException("MARKET_TEST_CONTROL_INVALID_SYMBOL", "Symbol is required");
    }
    return SymbolNormalizer.normalize(symbol);
  }

  private Optional<OverrideState> activeOverride(String requestedSymbol) {
    String symbol = normalizeSymbol(requestedSymbol);
    OverrideState state = overrides.get(symbol);
    if (state == null) {
      return Optional.empty();
    }
    if (!state.expiresAt().isAfter(clock.instant())) {
      if (overrides.remove(symbol, state)) {
        revision.incrementAndGet();
      }
      return Optional.empty();
    }
    return Optional.of(state);
  }

  private void validateQuote(BigDecimal bid, BigDecimal ask) {
    if (bid == null || ask == null || bid.signum() <= 0 || ask.signum() <= 0 || ask.compareTo(bid) <= 0) {
      throw new BusinessException("MARKET_TEST_CONTROL_INVALID_QUOTE", "Bid and ask must be positive and ask must exceed bid");
    }
  }

  private Duration effectiveTtl(Duration requestedTtl) {
    Duration configuredMaxTtl =
        properties.maxTtl() == null || properties.maxTtl().isZero() || properties.maxTtl().isNegative()
        ? DEFAULT_MAX_TTL
        : properties.maxTtl();
    Duration maxTtl = configuredMaxTtl.compareTo(DEFAULT_MAX_TTL) > 0
        ? DEFAULT_MAX_TTL
        : configuredMaxTtl;
    Duration ttl = requestedTtl == null ? maxTtl : requestedTtl;
    if (ttl.isZero() || ttl.isNegative()) {
      throw new BusinessException("MARKET_TEST_CONTROL_INVALID_TTL", "TTL must be positive");
    }
    return ttl.compareTo(maxTtl) > 0 ? maxTtl : ttl;
  }

  private record OverrideState(
      String symbol,
      BigDecimal bid,
      BigDecimal ask,
      Instant expiresAt
  ) {

    QuoteResponse quote(long timestamp) {
      BigDecimal mid = bid.add(ask).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
      BigDecimal spread = ask.subtract(bid);
      return new QuoteResponse("quote", symbol, bid, ask, mid, spread, SOURCE, timestamp);
    }
  }
}
