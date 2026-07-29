package com.fxplatform.validation.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.SpotMarketBundle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("validation")
public class ValidationMarketState {

  private final AtomicReference<CompositeTick> current = new AtomicReference<>();
  private volatile long generation = -1L;

  public synchronized void reset(long generation) {
    if (generation < 0L) {
      throw new IllegalArgumentException("generation must be non-negative");
    }
    this.generation = generation;
    current.set(null);
  }

  public synchronized CompositeTick publish(CompositeTick requested) {
    Objects.requireNonNull(requested, "requested");
    requireGeneration(requested.generation());
    validateComplete(requested);
    CompositeTick existing = current.get();
    if (existing == null) {
      if (requested.sequence() != 1L) {
        throw failure("VALIDATION_TICK_SEQUENCE_GAP", "The first market Tick must have sequence 1");
      }
      current.set(requested);
      return requested;
    }
    requireSameRun(existing, requested);
    if (requested.sequence() == existing.sequence()) {
      if (existing.fingerprint().equals(requested.fingerprint()) && existing.equals(requested)) {
        return existing;
      }
      throw failure("VALIDATION_TICK_CONFLICT", "Market Tick replay conflicts with stored content");
    }
    if (requested.sequence() != existing.sequence() + 1L) {
      throw failure("VALIDATION_TICK_SEQUENCE_GAP", "Market Tick sequence must be consecutive");
    }
    current.set(requested);
    return requested;
  }

  public synchronized CompositeTick restore(CompositeTick restored) {
    Objects.requireNonNull(restored, "restored");
    requireGeneration(restored.generation());
    validateComplete(restored);
    current.set(restored);
    return restored;
  }

  public Optional<CompositeTick> current() {
    return Optional.ofNullable(current.get());
  }

  public SpotMarketBundle requireSpot(String platformSymbol) {
    String symbol = SymbolNormalizer.normalize(platformSymbol);
    CompositeTick snapshot = requireCurrent();
    return snapshot.spotBundles().stream()
        .filter(bundle -> symbol.equals(bundle.platformSymbol()))
        .findFirst()
        .orElseThrow(() -> missing(symbol, "spot"));
  }

  public PerpetualMarketBundle requirePerpetual(String platformSymbol) {
    String symbol = SymbolNormalizer.normalize(platformSymbol);
    CompositeTick snapshot = requireCurrent();
    return snapshot.perpetualBundles().stream()
        .filter(bundle -> symbol.equals(bundle.platformSymbol()))
        .findFirst()
        .orElseThrow(() -> missing(symbol, "perpetual"));
  }

  public long generation() {
    return generation;
  }

  private CompositeTick requireCurrent() {
    CompositeTick snapshot = current.get();
    if (snapshot == null) {
      throw failure("VALIDATION_MARKET_STATE_MISSING", "Validation market state is not available");
    }
    return snapshot;
  }

  private void requireGeneration(long requestedGeneration) {
    if (requestedGeneration != generation) {
      throw failure("VALIDATION_GENERATION_FENCED", "Validation generation is no longer active");
    }
  }

  private static void requireSameRun(CompositeTick existing, CompositeTick requested) {
    if (!existing.runId().equals(requested.runId())
        || existing.generation() != requested.generation()) {
      throw failure("VALIDATION_TICK_CONFLICT", "Market Tick belongs to another run");
    }
  }

  static void validateComplete(CompositeTick tick) {
    if (tick.spotBundles().isEmpty() && tick.perpetualBundles().isEmpty()) {
      throw failure("VALIDATION_MARKET_TICK_INCOMPLETE", "Market Tick contains no symbols");
    }
    tick.spotBundles().forEach(bundle -> validateSpot(tick.virtualTime(), bundle));
    tick.perpetualBundles().forEach(bundle -> validatePerpetual(tick.virtualTime(), bundle));
    Set<String> perpetualSymbols = tick.perpetualBundles().stream()
        .map(PerpetualMarketBundle::platformSymbol)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    for (FundingRatePoint rate : tick.fundingRates()) {
      if (!perpetualSymbols.contains(rate.platformSymbol())) {
        throw failure(
            "VALIDATION_FUNDING_SYMBOL_MISSING",
            "Validation funding rate must reference a perpetual symbol in the same Tick");
      }
    }
  }

  private static void validateSpot(Instant virtualTime, SpotMarketBundle bundle) {
    if (bundle == null
        || blank(bundle.platformSymbol())
        || !"validation".equals(bundle.providerCode())
        || bundle.sourceMode() != MarketSourceMode.LOCAL_SIMULATED
        || !positive(bundle.bid())
        || !positive(bundle.ask())
        || !positive(bundle.last())
        || bundle.bid().compareTo(bundle.ask()) > 0
        || bundle.orderBook() == null
        || bundle.recentTrades() == null
        || bundle.recentTrades().isEmpty()
        || bundle.candles() == null
        || bundle.candles().isEmpty()
        || !virtualTime.equals(bundle.asOf())
        || bundle.expiresAt() == null
        || !bundle.expiresAt().isAfter(bundle.asOf())) {
      throw failure("VALIDATION_MARKET_TICK_INCOMPLETE", "Spot market bundle is incomplete");
    }
  }

  private static void validatePerpetual(Instant virtualTime, PerpetualMarketBundle bundle) {
    if (bundle == null
        || blank(bundle.platformSymbol())
        || !"validation".equals(bundle.providerCode())
        || bundle.sourceMode() != MarketSourceMode.LOCAL_SIMULATED
        || !positive(bundle.bid())
        || !positive(bundle.ask())
        || !positive(bundle.last())
        || !positive(bundle.mark())
        || !positive(bundle.index())
        || bundle.bid().compareTo(bundle.ask()) > 0
        || bundle.orderBook() == null
        || bundle.recentTrades() == null
        || bundle.recentTrades().isEmpty()
        || bundle.candles() == null
        || bundle.candles().isEmpty()
        || !virtualTime.equals(bundle.asOf())
        || bundle.expiresAt() == null
        || !bundle.expiresAt().isAfter(bundle.asOf())) {
      throw failure("VALIDATION_MARKET_TICK_INCOMPLETE", "Perpetual market bundle is incomplete");
    }
  }

  private static boolean positive(BigDecimal value) {
    return value != null && value.signum() > 0;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static BusinessException missing(String symbol, String product) {
    return failure(
        "VALIDATION_MARKET_STATE_MISSING",
        "Validation " + product + " market state is missing for " + symbol);
  }

  private static BusinessException failure(String code, String message) {
    return new BusinessException(code, message);
  }

  public record CompositeTick(
      UUID runId,
      long generation,
      long sequence,
      Instant virtualTime,
      String fingerprint,
      List<SpotMarketBundle> spotBundles,
      List<PerpetualMarketBundle> perpetualBundles,
      @JsonInclude(JsonInclude.Include.NON_EMPTY)
      List<FundingRatePoint> fundingRates
  ) {

    public CompositeTick {
      Objects.requireNonNull(runId, "runId");
      virtualTime = ValidationInstantPrecision.require(virtualTime, "virtualTime");
      Objects.requireNonNull(fingerprint, "fingerprint");
      if (generation < 0L || sequence < 1L || fingerprint.isBlank()
          || fingerprint.length() > 128) {
        throw new IllegalArgumentException("generation, sequence and fingerprint are required");
      }
      spotBundles = sortedSpots(spotBundles);
      perpetualBundles = sortedPerpetuals(perpetualBundles);
      fundingRates = sortedFundingRates(fundingRates);
    }

    public CompositeTick(
        UUID runId,
        long generation,
        long sequence,
        Instant virtualTime,
        String fingerprint,
        List<SpotMarketBundle> spotBundles,
        List<PerpetualMarketBundle> perpetualBundles
    ) {
      this(
          runId,
          generation,
          sequence,
          virtualTime,
          fingerprint,
          spotBundles,
          perpetualBundles,
          List.of());
    }

    private static List<SpotMarketBundle> sortedSpots(List<SpotMarketBundle> bundles) {
      List<SpotMarketBundle> sorted = List.copyOf(bundles == null ? List.of() : bundles).stream()
          .sorted(java.util.Comparator.comparing(SpotMarketBundle::platformSymbol))
          .toList();
      requireUnique(sorted.stream().map(SpotMarketBundle::platformSymbol).toList());
      return sorted;
    }

    private static List<PerpetualMarketBundle> sortedPerpetuals(
        List<PerpetualMarketBundle> bundles
    ) {
      List<PerpetualMarketBundle> sorted = List.copyOf(bundles == null ? List.of() : bundles).stream()
          .sorted(java.util.Comparator.comparing(PerpetualMarketBundle::platformSymbol))
          .toList();
      requireUnique(sorted.stream().map(PerpetualMarketBundle::platformSymbol).toList());
      return sorted;
    }

    private static List<FundingRatePoint> sortedFundingRates(
        List<FundingRatePoint> requested
    ) {
      List<FundingRatePoint> sorted =
          List.copyOf(requested == null ? List.of() : requested).stream()
              .sorted(Comparator.comparing(FundingRatePoint::platformSymbol))
              .toList();
      requireUnique(sorted.stream().map(FundingRatePoint::platformSymbol).toList());
      return sorted;
    }

    private static void requireUnique(List<String> symbols) {
      Set<String> unique = new HashSet<>(symbols);
      if (unique.size() != symbols.size()) {
        throw new IllegalArgumentException("Composite Tick contains duplicate symbols");
      }
    }
  }

  public record FundingRatePoint(String platformSymbol, BigDecimal fundingRate) {

    public FundingRatePoint {
      if (platformSymbol == null || fundingRate == null) {
        throw new IllegalArgumentException("Funding rate symbol and value are required");
      }
      platformSymbol = SymbolNormalizer.normalize(
          platformSymbol.strip().toUpperCase(Locale.ROOT));
      try {
        fundingRate = fundingRate.setScale(10, RoundingMode.UNNECESSARY);
        if (fundingRate.precision() > 18) {
          throw new ArithmeticException("precision");
        }
      } catch (ArithmeticException invalid) {
        throw new IllegalArgumentException(
            "Funding rate must be an exact NUMERIC(18,10) value",
            invalid);
      }
    }
  }
}
