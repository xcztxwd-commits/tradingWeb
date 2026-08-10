package com.fxplatform.market.provider;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.realtime.MarketTestControlService;
import com.fxplatform.market.service.MarketSourceSelectionTracker;
import com.fxplatform.market.service.ProviderHealthRecorder;
import com.fxplatform.market.service.SymbolProductTypes;
import com.fxplatform.validation.service.ValidationMarketState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
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
  private final MarketTestControlService testControlService;
  private final Clock clock;
  private final ValidationMarketState validationMarketState;
  private final ConcurrentHashMap<BundleFlightKey, CompletableFuture<SpotMarketBundle>>
      defaultSpotFlights = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<BundleFlightKey, CompletableFuture<PerpetualMarketBundle>>
      defaultPerpetualFlights = new ConcurrentHashMap<>();

  @Autowired
  public MarketBundleResolver(
      ProviderResolver providerResolver,
      MarketBundleValidator validator,
      MarketSourceSelectionTracker selectionTracker,
      ProviderHealthRecorder healthRecorder,
      MarketTestControlService testControlService,
      ObjectProvider<ValidationMarketState> validationMarketState
  ) {
    this(
        providerResolver,
        validator,
        selectionTracker,
        healthRecorder,
        testControlService,
        Clock.systemUTC(),
        validationMarketState.getIfAvailable());
  }

  public MarketBundleResolver(
      ProviderResolver providerResolver,
      MarketBundleValidator validator,
      MarketSourceSelectionTracker selectionTracker,
      ProviderHealthRecorder healthRecorder
  ) {
    this(
        providerResolver,
        validator,
        selectionTracker,
        healthRecorder,
        null,
        Clock.systemUTC(),
        null);
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
        null,
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
    this(providerResolver, validator, selectionTracker, healthRecorder, null, clock, null);
  }

  public MarketBundleResolver(
      ProviderResolver providerResolver,
      MarketBundleValidator validator,
      MarketSourceSelectionTracker selectionTracker,
      ProviderHealthRecorder healthRecorder,
      MarketTestControlService testControlService,
      Clock clock
  ) {
    this(
        providerResolver,
        validator,
        selectionTracker,
        healthRecorder,
        testControlService,
        clock,
        null);
  }

  public MarketBundleResolver(
      ProviderResolver providerResolver,
      MarketBundleValidator validator,
      MarketSourceSelectionTracker selectionTracker,
      ProviderHealthRecorder healthRecorder,
      Clock clock,
      ValidationMarketState validationMarketState
  ) {
    this(
        providerResolver,
        validator,
        selectionTracker,
        healthRecorder,
        null,
        clock,
        validationMarketState);
  }

  public MarketBundleResolver(
      ProviderResolver providerResolver,
      MarketBundleValidator validator,
      MarketSourceSelectionTracker selectionTracker,
      ProviderHealthRecorder healthRecorder,
      MarketTestControlService testControlService,
      Clock clock,
      ValidationMarketState validationMarketState
  ) {
    this.providerResolver = providerResolver;
    this.validator = validator;
    this.selectionTracker = selectionTracker;
    this.healthRecorder = healthRecorder;
    this.testControlService = testControlService;
    this.clock = clock;
    this.validationMarketState = validationMarketState;
  }

  public SpotMarketBundle resolveDefaultSpot(String platformSymbol) {
    String symbol = SymbolNormalizer.normalize(platformSymbol);
    if (validationMarketState != null) {
      return validationMarketState.requireSpot(symbol);
    }
    List<ProviderResolution> candidates =
        candidates(symbol, ProductType.CRYPTO_SPOT, SPOT_PROVIDERS);
    return resolveSingleFlight(
        defaultSpotFlights,
        flightKey(symbol, candidates),
        () -> resolveSpot(symbol, defaultCandleRequest(), candidates),
        SpotMarketBundle::expiresAt);
  }

  public PerpetualMarketBundle resolveDefaultPerpetual(String platformSymbol) {
    String symbol = SymbolNormalizer.normalize(platformSymbol);
    if (validationMarketState != null) {
      return validationMarketState.requirePerpetual(symbol);
    }
    List<ProviderResolution> candidates =
        candidates(symbol, ProductType.LINEAR_PERP, PERP_PROVIDERS);
    return resolveSingleFlight(
        defaultPerpetualFlights,
        flightKey(symbol, candidates),
        () -> resolvePerpetual(symbol, defaultCandleRequest(), candidates),
        PerpetualMarketBundle::expiresAt);
  }

  public SpotMarketBundle resolveSpot(String platformSymbol, CandleRequest candleRequest) {
    CandleRequestPolicy.requireValid(candleRequest);
    String symbol = SymbolNormalizer.normalize(platformSymbol);
    if (validationMarketState != null) {
      return validationMarketState.requireSpot(symbol);
    }
    return resolveSpot(
        symbol,
        candleRequest,
        candidates(symbol, ProductType.CRYPTO_SPOT, SPOT_PROVIDERS));
  }

  private SpotMarketBundle resolveSpot(
      String symbol,
      CandleRequest candleRequest,
      List<ProviderResolution> candidates
  ) {
    for (ProviderResolution candidate : candidates) {
      Instant startedAt = clock.instant();
      try {
        Optional<SpotMarketBundle> bundle = candidate.adapter().fetchSpotBundle(
            symbol, candidate.providerSymbol(), candleRequest);
        throwIfInterrupted();
        if (bundle.isPresent()) {
          SpotMarketBundle providerBundle = bundle.get();
          if (matchesCandidate(providerBundle, candidate) && validator.valid(providerBundle)) {
            SpotMarketBundle authorityBundle = testControlService == null
                ? providerBundle
                : testControlService.applySpotOverride(providerBundle);
            if (validator.valid(authorityBundle)) {
              recordSuccess(candidate, MarketBundleAssembler.quote(providerBundle, clock), startedAt);
              selectionTracker.recordSelection(
                  symbol,
                  providerBundle.providerCode(),
                  providerBundle.sourceMode(),
                  providerBundle.asOf(),
                  providerBundle.expiresAt(),
                  false);
              return authorityBundle;
            }
          }
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
    return resolvePerpetual(
        symbol,
        candleRequest,
        candidates(symbol, ProductType.LINEAR_PERP, PERP_PROVIDERS));
  }

  private PerpetualMarketBundle resolvePerpetual(
      String symbol,
      CandleRequest candleRequest,
      List<ProviderResolution> candidates
  ) {
    for (ProviderResolution candidate : candidates) {
      Instant startedAt = clock.instant();
      try {
        Optional<PerpetualMarketBundle> bundle = candidate.adapter().fetchPerpetualBundle(
            symbol, candidate.providerSymbol(), candleRequest);
        throwIfInterrupted();
        if (bundle.isPresent()) {
          PerpetualMarketBundle providerBundle = bundle.get();
          if (matchesCandidate(providerBundle, candidate) && validator.valid(providerBundle)) {
            PerpetualMarketBundle authorityBundle = testControlService == null
                ? providerBundle
                : testControlService.applyPerpetualOverride(providerBundle);
            if (validator.valid(authorityBundle)) {
              recordSuccess(candidate, MarketBundleAssembler.quote(providerBundle, clock), startedAt);
              selectionTracker.recordSelection(
                  symbol,
                  providerBundle.providerCode(),
                  providerBundle.sourceMode(),
                  providerBundle.asOf(),
                  providerBundle.expiresAt(),
                  false);
              return authorityBundle;
            }
          }
        }
      } catch (RuntimeException ignored) {
        // An incomplete or failed candidate causes whole-bundle fallback.
        throwIfInterrupted();
      }
      recordFailure(candidate, startedAt);
    }
    throw new BusinessException(ErrorCode.MARKET_DATA_UNAVAILABLE, "No complete fresh market bundle is available");
  }

  private BundleFlightKey flightKey(String symbol, List<ProviderResolution> candidates) {
    List<CandidateRevision> revisions = candidates.stream()
        .map(candidate -> new CandidateRevision(
            candidate.binding().getId(),
            candidate.binding().getUpdatedAt(),
            candidate.binding().getProviderId(),
            candidate.binding().getProviderSymbol(),
            candidate.binding().getPriority(),
            candidate.provider().getId(),
            candidate.provider().getCode(),
            candidate.provider().getRestBaseUrl(),
            candidate.provider().getTimeoutMs(),
            candidate.provider().getConfigJson()))
        .toList();
    long authorityRevision = testControlService == null
        ? 0L
        : testControlService.authorityRevision(symbol);
    return new BundleFlightKey(symbol, revisions, authorityRevision);
  }

  private <T> T resolveSingleFlight(
      ConcurrentHashMap<BundleFlightKey, CompletableFuture<T>> flights,
      BundleFlightKey key,
      Supplier<T> resolver,
      Function<T, Instant> expiresAt
  ) {
    flights.keySet().removeIf(existingKey ->
        existingKey.symbol().equals(key.symbol()) && !existingKey.equals(key));
    while (true) {
      CompletableFuture<T> candidate = new CompletableFuture<>();
      CompletableFuture<T> existing = flights.putIfAbsent(key, candidate);
      if (existing != null) {
        T bundle = awaitBundle(existing);
        if (clock.instant().isBefore(expiresAt.apply(bundle))) {
          return bundle;
        }
        flights.remove(key, existing);
        continue;
      }
      try {
        T bundle = resolver.get();
        candidate.complete(bundle);
        return bundle;
      } catch (RuntimeException | Error error) {
        candidate.completeExceptionally(error);
        flights.remove(key, candidate);
        throw error;
      }
    }
  }

  private <T> T awaitBundle(CompletableFuture<T> flight) {
    try {
      return flight.join();
    } catch (CompletionException error) {
      if (error.getCause() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (error.getCause() instanceof Error seriousError) {
        throw seriousError;
      }
      throw error;
    }
  }

  private CandleRequest defaultCandleRequest() {
    Instant to = clock.instant();
    return new CandleRequest("1m", to.minus(Duration.ofHours(1)), to);
  }

  private record BundleFlightKey(
      String symbol,
      List<CandidateRevision> candidates,
      long authorityRevision
  ) {
  }

  private record CandidateRevision(
      UUID bindingId,
      Instant bindingUpdatedAt,
      UUID bindingProviderId,
      String providerSymbol,
      Integer bindingPriority,
      UUID providerId,
      String providerCode,
      String restBaseUrl,
      Integer timeoutMs,
      String providerConfig
  ) {
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
