package com.fxplatform.market.realtime;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.dto.QuoteResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
    return state.quote(clock.millis());
  }

  public Optional<QuoteResponse> overrideQuote(String symbol) {
    String normalizedSymbol = normalizeSymbol(symbol);
    OverrideState state = overrides.get(normalizedSymbol);
    if (state == null) {
      return Optional.empty();
    }
    if (!state.expiresAt().isAfter(clock.instant())) {
      overrides.remove(normalizedSymbol, state);
      return Optional.empty();
    }
    return Optional.of(state.quote(clock.millis()));
  }

  public void endOverride(String requestedSymbol) {
    String symbol = normalizeSymbol(requestedSymbol);
    if (overrides.remove(symbol) != null) {
      backfillService.backfill(symbol);
    }
  }

  private String normalizeSymbol(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      throw new BusinessException("MARKET_TEST_CONTROL_INVALID_SYMBOL", "Symbol is required");
    }
    return SymbolNormalizer.normalize(symbol);
  }

  private void validateQuote(BigDecimal bid, BigDecimal ask) {
    if (bid == null || ask == null || bid.signum() <= 0 || ask.signum() <= 0 || ask.compareTo(bid) <= 0) {
      throw new BusinessException("MARKET_TEST_CONTROL_INVALID_QUOTE", "Bid and ask must be positive and ask must exceed bid");
    }
  }

  private Duration effectiveTtl(Duration requestedTtl) {
    Duration maxTtl = properties.maxTtl() == null || properties.maxTtl().isZero() || properties.maxTtl().isNegative()
        ? DEFAULT_MAX_TTL
        : properties.maxTtl();
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
