package com.fxplatform.market.provider;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.service.MarketSourceSelectionTracker;
import com.fxplatform.market.service.ProviderHealthRecorder;
import com.fxplatform.market.service.SymbolProductTypes;
import com.fxplatform.validation.service.ValidationMarketState;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class MarketBundleResolver {

  private static final Set<String> SPOT_PROVIDERS = Set.of("binance", "okx", "local-spot");
  private static final Set<String> PERP_PROVIDERS = Set.of("binance-usdm", "okx-swap", "local-perp");
  private static final Set<MarketDataCapability> BUNDLE_CAPABILITIES = Set.of(
      MarketDataCapability.QUOTE,
      MarketDataCapability.CANDLES,
      MarketDataCapability.ORDER_BOOK,
      MarketDataCapability.TRADES);

  private final ProviderResolver providerResolver;
  private final MarketBundleValidator validator;
  private final MarketSourceSelectionTracker selectionTracker;
  private final ProviderHealthRecorder healthRecorder;
  private final Clock clock;
  private final ValidationMarketState validationMarketState;

  @Autowired
  public MarketBundleResolver(
      ProviderResolver providerResolver,
      MarketBundleValidator validator,
      MarketSourceSelectionTracker selectionTracker,
      ProviderHealthRecorder healthRecorder,
      ObjectProvider<ValidationMarketState> validationMarketState
  ) {
    this(
        providerResolver,
        validator,
        selectionTracker,
        healthRecorder,
        Clock.systemUTC(),
        validationMarketState.getIfAvailable());
  }

  public MarketBundleResolver(
      ProviderResolver providerResolver,
      MarketBundleValidator validator,
      MarketSourceSelectionTracker selectionTracker,
      ProviderHealthRecorder healthRecorder
  ) {
    this(providerResolver, validator, selectionTracker, healthRecorder, Clock.systemUTC(), null);
  }

  public MarketBundleResolver(
      ProviderResolver providerResolver,
      MarketBundleValidator validator,
      MarketSourceSelectionTracker selectionTracker
  ) {
    this(
        providerResolver,
        validator,
        selectionTracker,
        ProviderHealthRecorder.noop(),
        Clock.systemUTC(),
        null);
  }

  public MarketBundleResolver(
      ProviderResolver providerResolver,
      MarketBundleValidator validator,
      MarketSourceSelectionTracker selectionTracker,
      ProviderHealthRecorder healthRecorder,
      Clock clock
  ) {
    this(providerResolver, validator, selectionTracker, healthRecorder, clock, null);
  }

  public MarketBundleResolver(
      ProviderResolver providerResolver,
      MarketBundleValidator validator,
      MarketSourceSelectionTracker selectionTracker,
      ProviderHealthRecorder healthRecorder,
      Clock clock,
      ValidationMarketState validationMarketState
  ) {
    this.providerResolver = providerResolver;
    this.validator = validator;
    this.selectionTracker = selectionTracker;
    this.healthRecorder = healthRecorder;
    this.clock = clock;
    this.validationMarketState = validationMarketState;
  }

  public SpotMarketBundle resolveSpot(String platformSymbol, CandleRequest candleRequest) {
    CandleRequestPolicy.requireValid(candleRequest);
    String symbol = SymbolNormalizer.normalize(platformSymbol);
    if (validationMarketState != null) {
      return validationMarketState.requireSpot(symbol);
    }
    for (ProviderResolution candidate : candidates(symbol, ProductType.CRYPTO_SPOT, SPOT_PROVIDERS)) {
      Instant startedAt = clock.instant();
      try {
        Optional<SpotMarketBundle> bundle = candidate.adapter().fetchSpotBundle(
            symbol, candidate.providerSymbol(), candleRequest);
        throwIfInterrupted();
        if (bundle.isPresent() && matchesCandidate(bundle.get(), candidate) && validator.valid(bundle.get())) {
          recordSuccess(candidate, MarketBundleAssembler.quote(bundle.get(), clock), startedAt);
          selectionTracker.recordSelection(
              symbol,
              bundle.get().providerCode(),
              bundle.get().sourceMode(),
              bundle.get().asOf(),
              bundle.get().expiresAt(),
              false);
          return bundle.get();
        }
      } catch (RuntimeException ignored) {
        // An incomplete or failed candidate causes whole-bundle fallback.
        throwIfInterrupted();
      }
      recordFailure(candidate, startedAt);
    }
    throw new BusinessException(ErrorCode.MARKET_DATA_UNAVAILABLE, "No complete fresh market bundle is available");
  }

  public PerpetualMarketBundle resolvePerp(String platformSymbol, CandleRequest candleRequest) {
    CandleRequestPolicy.requireValid(candleRequest);
    String symbol = SymbolNormalizer.normalize(platformSymbol);
    if (validationMarketState != null) {
      return validationMarketState.requirePerpetual(symbol);
    }
    for (ProviderResolution candidate : candidates(symbol, ProductType.LINEAR_PERP, PERP_PROVIDERS)) {
      Instant startedAt = clock.instant();
      try {
        Optional<PerpetualMarketBundle> bundle = candidate.adapter().fetchPerpetualBundle(
            symbol, candidate.providerSymbol(), candleRequest);
        throwIfInterrupted();
        if (bundle.isPresent() && matchesCandidate(bundle.get(), candidate) && validator.valid(bundle.get())) {
          recordSuccess(candidate, MarketBundleAssembler.quote(bundle.get(), clock), startedAt);
          selectionTracker.recordSelection(
              symbol,
              bundle.get().providerCode(),
              bundle.get().sourceMode(),
              bundle.get().asOf(),
              bundle.get().expiresAt(),
              false);
          return bundle.get();
        }
      } catch (RuntimeException ignored) {
        // An incomplete or failed candidate causes whole-bundle fallback.
        throwIfInterrupted();
      }
      recordFailure(candidate, startedAt);
    }
    throw new BusinessException(ErrorCode.MARKET_DATA_UNAVAILABLE, "No complete fresh market bundle is available");
  }

  private List<ProviderResolution> candidates(
      String symbol,
      ProductType expectedProduct,
      Set<String> allowedProviders
  ) {
    return providerResolver.resolveCandidates(symbol, BUNDLE_CAPABILITIES).stream()
        .filter(candidate -> SymbolProductTypes.readOrLegacy(candidate.symbol()) == expectedProduct)
        .filter(candidate -> allowedProviders.contains(candidate.provider().getCode()))
        .toList();
  }

  private void throwIfInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_UNAVAILABLE,
          "Market data resolution was interrupted");
    }
  }

  private void recordSuccess(
      ProviderResolution candidate,
      com.fxplatform.market.dto.QuoteResponse quote,
      Instant startedAt
  ) {
    try {
      healthRecorder.recordQuoteSuccess(candidate.provider(), quote, latencyMs(startedAt));
    } catch (RuntimeException ignored) {
      // Telemetry failure must not make a complete market bundle unavailable.
    }
  }

  private void recordFailure(ProviderResolution candidate, Instant startedAt) {
    try {
      healthRecorder.recordFailure(candidate.provider(), latencyMs(startedAt));
    } catch (RuntimeException ignored) {
      // Telemetry failure must not suppress fallback or future primary probes.
    }
  }

  private long latencyMs(Instant startedAt) {
    return Math.max(0L, Duration.between(startedAt, clock.instant()).toMillis());
  }

  private boolean matchesCandidate(SpotMarketBundle bundle, ProviderResolution candidate) {
    return candidate.symbol().getSymbol().equals(bundle.platformSymbol())
        && candidate.provider().getCode().equals(bundle.providerCode())
        && candidate.providerSymbol().equals(bundle.providerSymbol())
        && expectedSourceMode(candidate).equals(bundle.sourceMode());
  }

  private boolean matchesCandidate(PerpetualMarketBundle bundle, ProviderResolution candidate) {
    return candidate.symbol().getSymbol().equals(bundle.platformSymbol())
        && candidate.provider().getCode().equals(bundle.providerCode())
        && candidate.providerSymbol().equals(bundle.providerSymbol())
        && expectedSourceMode(candidate).equals(bundle.sourceMode());
  }

  private MarketSourceMode expectedSourceMode(ProviderResolution candidate) {
    return candidate.provider().getCode().startsWith("local-")
        ? MarketSourceMode.LOCAL_SIMULATED
        : MarketSourceMode.PUBLIC_EXTERNAL;
  }
}
