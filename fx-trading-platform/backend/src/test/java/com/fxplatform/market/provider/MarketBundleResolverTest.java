package com.fxplatform.market.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.realtime.MarketTestControlService;
import com.fxplatform.market.service.MarketSourceSelectionTracker;
import com.fxplatform.market.service.ProviderHealthRecorder;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MarketBundleResolverTest {

  private static final Instant NOW = Instant.parse("2026-07-12T00:00:10Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final CandleRequest CANDLES = new CandleRequest(
      "1m", NOW.minus(Duration.ofHours(1)), NOW);
  private static final Set<MarketDataCapability> BUNDLE_CAPABILITIES = Set.of(
      MarketDataCapability.QUOTE,
      MarketDataCapability.CANDLES,
      MarketDataCapability.ORDER_BOOK,
      MarketDataCapability.TRADES);

  @Mock
  private ProviderResolver providerResolver;

  @Mock
  private ProviderHealthRecorder healthRecorder;

  private MarketSourceSelectionTracker tracker;
  private MarketBundleResolver resolver;
  private AtomicInteger selectionEvents;

  @BeforeEach
  void setUp() {
    selectionEvents = new AtomicInteger();
    tracker = new MarketSourceSelectionTracker(event -> selectionEvents.incrementAndGet(), CLOCK);
    resolver = new MarketBundleResolver(
        providerResolver, new MarketBundleValidator(CLOCK), tracker, healthRecorder, CLOCK);
  }

  @Test
  void springWiresTestControlIntoProductionConstructor() {
    MarketTestControlService testControlService = mock(MarketTestControlService.class);

    new ApplicationContextRunner()
        .withBean(ProviderResolver.class, () -> mock(ProviderResolver.class))
        .withBean(MarketBundleValidator.class, () -> mock(MarketBundleValidator.class))
        .withBean(MarketSourceSelectionTracker.class, () -> mock(MarketSourceSelectionTracker.class))
        .withBean(ProviderHealthRecorder.class, () -> mock(ProviderHealthRecorder.class))
        .withBean(MarketTestControlService.class, () -> testControlService)
        .withBean(MarketBundleResolver.class)
        .run(context -> {
          assertThat(context).hasSingleBean(MarketBundleResolver.class);
          assertThat(ReflectionTestUtils.getField(
              context.getBean(MarketBundleResolver.class),
              "testControlService")).isSameAs(testControlService);
        });
  }

  @ParameterizedTest
  @MethodSource("invalidCandleRequests")
  void rejectsInvalidCandleRequestBeforeProviderResolutionAndTelemetry(CandleRequest request) {
    assertThatThrownBy(() -> resolver.resolveSpot("BTCUSDT", request))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("INVALID_CANDLE_REQUEST"));
    assertThatThrownBy(() -> resolver.resolvePerp("BTCUSDT-PERP", request))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("INVALID_CANDLE_REQUEST"));

    verifyNoInteractions(providerResolver, healthRecorder);
    assertThat(selectionEvents).hasValue(0);
  }

  @Test
  void restoredInterruptStopsSpotResolutionWithoutFallbackOrTelemetry() {
    BundleAdapter interrupted = BundleAdapter.interrupting("binance");
    BundleAdapter secondary = BundleAdapter.spot(
        "okx", completeSpot("okx", "BTC-USDT", NOW.minusSeconds(1)));
    BundleAdapter local = BundleAdapter.spot(
        "local-spot", completeSpot("local-spot", "BTCUSDT", NOW.minusSeconds(1)));
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(
            candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", interrupted),
            candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTC-USDT", secondary),
            candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", local)));
    MarketSourceSelectionTracker selectionTracker = mock(MarketSourceSelectionTracker.class);
    MarketBundleResolver interruptResolver = new MarketBundleResolver(
        providerResolver, new MarketBundleValidator(CLOCK), selectionTracker, healthRecorder, CLOCK);

    try {
      assertThatThrownBy(() -> interruptResolver.resolveSpot("BTCUSDT", CANDLES))
          .isInstanceOfSatisfying(BusinessException.class,
              error -> assertThat(error.getCode()).isEqualTo("MARKET_DATA_UNAVAILABLE"));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }

    assertThat(interrupted.spotCalls).isEqualTo(1);
    assertThat(secondary.spotCalls).isZero();
    assertThat(local.spotCalls).isZero();
    verifyNoInteractions(healthRecorder, selectionTracker);
  }

  @Test
  void restoredInterruptStopsPerpetualResolutionWithoutFallbackOrTelemetry() {
    BundleAdapter interrupted = BundleAdapter.interrupting("binance-usdm");
    BundleAdapter secondary = BundleAdapter.perp(
        "okx-swap", completePerp(
            "okx-swap", "BTC-USDT-SWAP", MarketSourceMode.PUBLIC_EXTERNAL, NOW.minusSeconds(1)));
    BundleAdapter local = BundleAdapter.perp(
        "local-perp", completePerp(
            "local-perp", "BTCUSDT-PERP", MarketSourceMode.LOCAL_SIMULATED, NOW.minusSeconds(1)));
    when(providerResolver.resolveCandidates("BTCUSDT-PERP", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(
            candidate("BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTCUSDT", interrupted),
            candidate("BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTC-USDT-SWAP", secondary),
            candidate("BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTCUSDT-PERP", local)));
    MarketSourceSelectionTracker selectionTracker = mock(MarketSourceSelectionTracker.class);
    MarketBundleResolver interruptResolver = new MarketBundleResolver(
        providerResolver, new MarketBundleValidator(CLOCK), selectionTracker, healthRecorder, CLOCK);

    try {
      assertThatThrownBy(() -> interruptResolver.resolvePerp("BTCUSDT-PERP", CANDLES))
          .isInstanceOfSatisfying(BusinessException.class,
              error -> assertThat(error.getCode()).isEqualTo("MARKET_DATA_UNAVAILABLE"));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }

    assertThat(interrupted.perpCalls).isEqualTo(1);
    assertThat(secondary.perpCalls).isZero();
    assertThat(local.perpCalls).isZero();
    verifyNoInteractions(healthRecorder, selectionTracker);
  }

  @Test
  void concurrentDefaultSpotComponentsShareOneAuthoritativeBundleResolution() throws Exception {
    SpotMarketBundle bundle = completeSpot("okx", "BTC-USDT", NOW.minusSeconds(1));
    MarketDataProviderAdapter adapter = mock(MarketDataProviderAdapter.class);
    CountDownLatch componentCallsReady = new CountDownLatch(3);
    CountDownLatch firstResolutionStarted = new CountDownLatch(1);
    CountDownLatch duplicateResolutionStarted = new CountDownLatch(1);
    CountDownLatch releaseResolution = new CountDownLatch(1);
    AtomicInteger resolutionCalls = new AtomicInteger();
    when(adapter.code()).thenReturn("okx");
    when(adapter.fetchSpotBundle(
        org.mockito.ArgumentMatchers.eq("BTCUSDT"),
        org.mockito.ArgumentMatchers.eq("BTC-USDT"),
        org.mockito.ArgumentMatchers.any())).thenAnswer(ignored -> {
          if (resolutionCalls.incrementAndGet() == 1) {
            firstResolutionStarted.countDown();
          } else {
            duplicateResolutionStarted.countDown();
          }
          assertThat(releaseResolution.await(5, TimeUnit.SECONDS)).isTrue();
          return Optional.of(bundle);
        });
    ProviderResolution candidate =
        candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTC-USDT", adapter);
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenAnswer(ignored -> {
          componentCallsReady.countDown();
          assertThat(componentCallsReady.await(5, TimeUnit.SECONDS)).isTrue();
          return List.of(candidate);
        });
    ExecutorService executor = Executors.newFixedThreadPool(3);

    try {
      Future<SpotMarketBundle> quote = executor.submit(() -> resolver.resolveDefaultSpot("BTCUSDT"));
      Future<SpotMarketBundle> depth = executor.submit(() -> resolver.resolveDefaultSpot("BTCUSDT"));
      Future<SpotMarketBundle> trades = executor.submit(() -> resolver.resolveDefaultSpot("BTCUSDT"));
      assertThat(firstResolutionStarted.await(5, TimeUnit.SECONDS)).isTrue();
      boolean duplicateResolution = duplicateResolutionStarted.await(1, TimeUnit.SECONDS);
      releaseResolution.countDown();

      assertThat(quote.get(5, TimeUnit.SECONDS)).isSameAs(bundle);
      assertThat(depth.get(5, TimeUnit.SECONDS)).isSameAs(bundle);
      assertThat(trades.get(5, TimeUnit.SECONDS)).isSameAs(bundle);
      assertThat(duplicateResolution).isFalse();
      assertThat(resolutionCalls).hasValue(1);
    } finally {
      releaseResolution.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void sequentialDefaultSpotComponentsReuseOneFreshAuthoritativeBundle() {
    SpotMarketBundle bundle = completeSpot("okx", "BTC-USDT", NOW.minusSeconds(1));
    BundleAdapter adapter = BundleAdapter.spot("okx", bundle);
    ProviderResolution candidate =
        candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTC-USDT", adapter);
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(candidate));

    assertThat(resolver.resolveDefaultSpot("BTCUSDT")).isSameAs(bundle);
    assertThat(resolver.resolveDefaultSpot("BTCUSDT")).isSameAs(bundle);
    assertThat(resolver.resolveDefaultSpot("BTCUSDT")).isSameAs(bundle);
    assertThat(adapter.spotCalls).isEqualTo(1);
  }

  @Test
  void authorityRevisionDropsFreshDefaultPerpetualFlight() {
    PerpetualMarketBundle bundle = completePerp(
        "okx-swap", "BTC-USDT-SWAP", MarketSourceMode.PUBLIC_EXTERNAL, NOW.minusSeconds(1));
    BundleAdapter adapter = BundleAdapter.perp("okx-swap", bundle);
    ProviderResolution candidate = candidate(
        "BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTC-USDT-SWAP", adapter);
    MarketTestControlService testControlService = mock(MarketTestControlService.class);
    when(providerResolver.resolveCandidates("BTCUSDT-PERP", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(candidate));
    when(testControlService.authorityRevision("BTCUSDT-PERP")).thenReturn(0L, 1L);
    when(testControlService.applyPerpetualOverride(bundle)).thenReturn(bundle);
    MarketBundleResolver authorityResolver = new MarketBundleResolver(
        providerResolver,
        new MarketBundleValidator(CLOCK),
        tracker,
        healthRecorder,
        testControlService,
        CLOCK);

    assertThat(authorityResolver.resolveDefaultPerpetual("BTCUSDT-PERP")).isSameAs(bundle);
    assertThat(authorityResolver.resolveDefaultPerpetual("BTCUSDT-PERP")).isSameAs(bundle);

    assertThat(adapter.perpCalls).isEqualTo(2);
  }

  @Test
  void providerBindingChangeDoesNotReuseEarlierDefaultSpotFlight() throws Exception {
    SpotMarketBundle localBundle =
        completeSpot("local-spot", "BTCUSDT", NOW.minusSeconds(1));
    SpotMarketBundle publicBundle =
        completeSpot("binance", "BTCUSDT", NOW.minusSeconds(1));
    MarketDataProviderAdapter localAdapter = mock(MarketDataProviderAdapter.class);
    MarketDataProviderAdapter publicAdapter = mock(MarketDataProviderAdapter.class);
    CountDownLatch firstResolutionStarted = new CountDownLatch(1);
    CountDownLatch secondResolutionStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstResolution = new CountDownLatch(1);
    when(localAdapter.code()).thenReturn("local-spot");
    when(publicAdapter.code()).thenReturn("binance");
    when(localAdapter.fetchSpotBundle(
        org.mockito.ArgumentMatchers.eq("BTCUSDT"),
        org.mockito.ArgumentMatchers.eq("BTCUSDT"),
        org.mockito.ArgumentMatchers.any())).thenAnswer(ignored -> {
          firstResolutionStarted.countDown();
          assertThat(releaseFirstResolution.await(5, TimeUnit.SECONDS)).isTrue();
          return Optional.of(localBundle);
        });
    when(publicAdapter.fetchSpotBundle(
        org.mockito.ArgumentMatchers.eq("BTCUSDT"),
        org.mockito.ArgumentMatchers.eq("BTCUSDT"),
        org.mockito.ArgumentMatchers.any())).thenAnswer(ignored -> {
          secondResolutionStarted.countDown();
          return Optional.of(publicBundle);
        });
    ProviderResolution local =
        candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", localAdapter);
    ProviderResolution external =
        candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", publicAdapter);
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(local), List.of(external, local));
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<SpotMarketBundle> beforeChange =
          executor.submit(() -> resolver.resolveDefaultSpot("BTCUSDT"));
      assertThat(firstResolutionStarted.await(5, TimeUnit.SECONDS)).isTrue();
      Future<SpotMarketBundle> afterChange =
          executor.submit(() -> resolver.resolveDefaultSpot("BTCUSDT"));
      boolean freshResolution = secondResolutionStarted.await(1, TimeUnit.SECONDS);
      SpotMarketBundle currentBundle = afterChange.get(5, TimeUnit.SECONDS);
      releaseFirstResolution.countDown();

      assertThat(beforeChange.get(5, TimeUnit.SECONDS)).isSameAs(localBundle);
      assertThat(currentBundle).isSameAs(publicBundle);
      assertThat(freshResolution).isTrue();
    } finally {
      releaseFirstResolution.countDown();
      executor.shutdownNow();
    }
  }

  private static Stream<CandleRequest> invalidCandleRequests() {
    return Stream.of(
        null,
        new CandleRequest(null, NOW.minusSeconds(60), NOW),
        new CandleRequest("0m", NOW.minusSeconds(60), NOW),
        new CandleRequest("-1m", NOW.minusSeconds(60), NOW),
        new CandleRequest("999999999999999999999999m", NOW.minusSeconds(60), NOW),
        new CandleRequest("1M", NOW.minusSeconds(60), NOW),
        new CandleRequest("1m", null, NOW),
        new CandleRequest("1m", NOW.minusSeconds(60), null),
        new CandleRequest("1m", NOW, NOW),
        new CandleRequest("1m", NOW, NOW.minusSeconds(1)),
        new CandleRequest("1d", NOW.minus(Duration.ofDays(367)), NOW));
  }

  @Test
  void selectsCompleteBinanceSpotBundleWithoutCallingSecondary() {
    BundleAdapter binance = BundleAdapter.spot("binance", completeSpot("binance", "BTCUSDT", NOW.minusSeconds(1)));
    BundleAdapter okx = BundleAdapter.spot("okx", completeSpot("okx", "BTC-USDT", NOW.minusSeconds(1)));
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", binance),
            candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTC-USDT", okx)));

    SpotMarketBundle result = resolver.resolveSpot("btcusdt", CANDLES);

    assertThat(result.providerCode()).isEqualTo("binance");
    assertThat(result.providerSymbol()).isEqualTo("BTCUSDT");
    assertThat(binance.spotCalls).isEqualTo(1);
    assertThat(okx.spotCalls).isZero();
    verify(providerResolver).resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES);
  }

  @Test
  void preservesBindingCandidatePriorityWithoutHardcodedProviderReordering() {
    BundleAdapter okx = BundleAdapter.spot("okx", completeSpot("okx", "BTC-USDT", NOW.minusSeconds(1)));
    BundleAdapter binance = BundleAdapter.spot("binance", completeSpot("binance", "BTCUSDT", NOW.minusSeconds(1)));
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTC-USDT", okx),
            candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", binance)));

    SpotMarketBundle result = resolver.resolveSpot("BTCUSDT", CANDLES);

    assertThat(result.providerCode()).isEqualTo("okx");
    assertThat(okx.spotCalls).isEqualTo(1);
    assertThat(binance.spotCalls).isZero();
  }

  @Test
  void validatesProviderThenSpotAuthorityAndRecordsOriginalProviderQuote() {
    SpotMarketBundle providerBundle = completeSpot("binance", "BTCUSDT", NOW.minusSeconds(1));
    SpotMarketBundle authorityBundle = withAuthorityPrices(providerBundle);
    BundleAdapter binance = BundleAdapter.spot("binance", providerBundle);
    ProviderResolution candidate =
        candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", binance);
    MarketBundleValidator authorityValidator = mock(MarketBundleValidator.class);
    MarketTestControlService testControlService = mock(MarketTestControlService.class);
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(candidate));
    when(authorityValidator.valid(providerBundle)).thenReturn(true);
    when(testControlService.applySpotOverride(providerBundle)).thenReturn(authorityBundle);
    when(authorityValidator.valid(authorityBundle)).thenReturn(true);
    MarketBundleResolver authorityResolver = new MarketBundleResolver(
        providerResolver,
        authorityValidator,
        tracker,
        healthRecorder,
        testControlService,
        CLOCK);

    SpotMarketBundle result = authorityResolver.resolveSpot("BTCUSDT", CANDLES);

    assertThat(result).isSameAs(authorityBundle);
    InOrder validationOrder = inOrder(authorityValidator, testControlService);
    validationOrder.verify(authorityValidator).valid(same(providerBundle));
    validationOrder.verify(testControlService).applySpotOverride(same(providerBundle));
    validationOrder.verify(authorityValidator).valid(same(authorityBundle));
    verify(healthRecorder).recordQuoteSuccess(
        same(candidate.provider()),
        argThat(quote ->
            quote.bid().compareTo(providerBundle.bid()) == 0
                && quote.ask().compareTo(providerBundle.ask()) == 0
                && providerBundle.providerCode().equals(quote.source())),
        anyLong());
  }

  @Test
  void validatesProviderThenPerpetualAuthorityAndRecordsOriginalProviderQuote() {
    PerpetualMarketBundle providerBundle = completePerp(
        "binance-usdm", "BTCUSDT", MarketSourceMode.PUBLIC_EXTERNAL, NOW.minusSeconds(1));
    PerpetualMarketBundle authorityBundle = withAuthorityPrices(providerBundle);
    BundleAdapter binance = BundleAdapter.perp("binance-usdm", providerBundle);
    ProviderResolution candidate =
        candidate("BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTCUSDT", binance);
    MarketBundleValidator authorityValidator = mock(MarketBundleValidator.class);
    MarketTestControlService testControlService = mock(MarketTestControlService.class);
    when(providerResolver.resolveCandidates("BTCUSDT-PERP", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(candidate));
    when(authorityValidator.valid(providerBundle)).thenReturn(true);
    when(testControlService.applyPerpetualOverride(providerBundle)).thenReturn(authorityBundle);
    when(authorityValidator.valid(authorityBundle)).thenReturn(true);
    MarketBundleResolver authorityResolver = new MarketBundleResolver(
        providerResolver,
        authorityValidator,
        tracker,
        healthRecorder,
        testControlService,
        CLOCK);

    PerpetualMarketBundle result = authorityResolver.resolvePerp("BTCUSDT-PERP", CANDLES);

    assertThat(result).isSameAs(authorityBundle);
    InOrder validationOrder = inOrder(authorityValidator, testControlService);
    validationOrder.verify(authorityValidator).valid(same(providerBundle));
    validationOrder.verify(testControlService).applyPerpetualOverride(same(providerBundle));
    validationOrder.verify(authorityValidator).valid(same(authorityBundle));
    verify(healthRecorder).recordQuoteSuccess(
        same(candidate.provider()),
        argThat(quote ->
            quote.bid().compareTo(providerBundle.bid()) == 0
                && quote.markPrice().compareTo(providerBundle.mark()) == 0
                && providerBundle.providerCode().equals(quote.source())),
        anyLong());
  }

  @Test
  void exceptionFallsBackToEntireOkxSpotBundle() {
    BundleAdapter binance = BundleAdapter.throwing("binance");
    BundleAdapter okx = BundleAdapter.spot("okx", completeSpot("okx", "BTC-USDT", NOW.minusSeconds(1)));
    ProviderResolution binanceCandidate = candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", binance);
    ProviderResolution okxCandidate = candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTC-USDT", okx);
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(binanceCandidate, okxCandidate));

    SpotMarketBundle result = resolver.resolveSpot("BTCUSDT", CANDLES);

    assertThat(result.providerCode()).isEqualTo("okx");
    assertThat(result.orderBook().providerCode()).isEqualTo("okx");
    assertThat(result.recentTrades()).allMatch(trade -> "okx".equals(trade.providerCode()));
    assertThat(result.candles()).allMatch(candle -> "okx".equals(candle.providerCode()));
    verify(healthRecorder).recordFailure(org.mockito.Mockito.same(binanceCandidate.provider()), anyLong());
    verify(healthRecorder).recordQuoteSuccess(
        org.mockito.Mockito.same(okxCandidate.provider()),
        org.mockito.ArgumentMatchers.argThat(quote -> "BTCUSDT".equals(quote.symbol())),
        anyLong());
  }

  @Test
  void incompleteOrStalePrimaryFallsBackWithoutCrossProviderFieldMixing() {
    SpotMarketBundle incomplete = completeSpot("binance", "BTCUSDT", NOW.minusSeconds(1))
        .withOrderBook(null);
    SpotMarketBundle stale = completeSpot("binance", "BTCUSDT", NOW.minusSeconds(10));
    SpotMarketBundle okxBundle = completeSpot("okx", "BTC-USDT", NOW.minusSeconds(1));

    BundleAdapter primary = BundleAdapter.spotSequence("binance", incomplete, stale);
    BundleAdapter secondary = BundleAdapter.spot("okx", okxBundle);
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", primary),
            candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTC-USDT", secondary)));

    SpotMarketBundle first = resolver.resolveSpot("BTCUSDT", CANDLES);
    SpotMarketBundle second = resolver.resolveSpot("BTCUSDT", CANDLES);

    assertThat(first).isSameAs(okxBundle);
    assertThat(second).isSameAs(okxBundle);
    assertThat(first.bid()).isEqualByComparingTo("99");
    assertThat(first.orderBook().bids().getFirst().price()).isEqualByComparingTo("99");
    assertThat(first.recentTrades().getFirst().price()).isEqualByComparingTo("100");
    assertThat(first.candles().getFirst().close()).isEqualByComparingTo("100");
  }

  @Test
  void componentExplicitlyMarkedStaleRejectsTheWholeBundle() {
    SpotMarketBundle primary = completeSpot("binance", "BTCUSDT", NOW.minusSeconds(1));
    MarketDepthResponse staleDepth = new MarketDepthResponse(
        primary.orderBook().symbol(),
        primary.orderBook().timestamp(),
        primary.orderBook().bids(),
        primary.orderBook().asks(),
        primary.orderBook().providerCode(),
        primary.orderBook().providerSymbol(),
        primary.orderBook().sourceMode(),
        primary.orderBook().asOf(),
        primary.orderBook().expiresAt(),
        true);
    BundleAdapter binance = BundleAdapter.spot("binance", primary.withOrderBook(staleDepth));
    BundleAdapter okx = BundleAdapter.spot("okx", completeSpot("okx", "BTC-USDT", NOW.minusSeconds(1)));
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", binance),
            candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTC-USDT", okx)));

    SpotMarketBundle result = resolver.resolveSpot("BTCUSDT", CANDLES);

    assertThat(result.providerCode()).isEqualTo("okx");
  }

  @Test
  void malformedMarketComponentsRejectTheWholePrimaryBundle() {
    SpotMarketBundle valid = completeSpot("binance", "BTCUSDT", NOW.minusSeconds(1));
    MarketDepthResponse invalidDepth = new MarketDepthResponse(
        valid.orderBook().symbol(),
        valid.orderBook().timestamp(),
        List.of(new MarketDepthLevelResponse(BigDecimal.ZERO, BigDecimal.ONE)),
        valid.orderBook().asks(),
        valid.orderBook().providerCode(),
        valid.orderBook().providerSymbol(),
        valid.orderBook().sourceMode(),
        valid.orderBook().asOf(),
        valid.orderBook().expiresAt(),
        false);
    RecentTradeResponse invalidTrade = new RecentTradeResponse(
        "bad", "BTCUSDT", new BigDecimal("100"), BigDecimal.ZERO, "BUY", NOW.toEpochMilli(),
        "binance", "BTCUSDT", MarketSourceMode.PUBLIC_EXTERNAL,
        valid.asOf(), valid.expiresAt(), false);
    CandleResponse invalidStructure = new CandleResponse(
        NOW.minusSeconds(60).toEpochMilli(), new BigDecimal("100"), new BigDecimal("98"),
        new BigDecimal("99"), new BigDecimal("100"), BigDecimal.ONE,
        "binance", "BTCUSDT", MarketSourceMode.PUBLIC_EXTERNAL,
        valid.asOf(), valid.expiresAt(), false);
    CandleResponse invalidVolume = new CandleResponse(
        NOW.minusSeconds(60).toEpochMilli(), new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100"), new BigDecimal("-1"),
        "binance", "BTCUSDT", MarketSourceMode.PUBLIC_EXTERNAL,
        valid.asOf(), valid.expiresAt(), false);
    CandleResponse invalidPrice = new CandleResponse(
        NOW.minusSeconds(60).toEpochMilli(), BigDecimal.ZERO, new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100"), BigDecimal.ONE,
        "binance", "BTCUSDT", MarketSourceMode.PUBLIC_EXTERNAL,
        valid.asOf(), valid.expiresAt(), false);
    SpotMarketBundle malformedDepth = withComponents(
        valid, invalidDepth, valid.recentTrades(), valid.candles());
    SpotMarketBundle malformedTrade = withComponents(
        valid, valid.orderBook(), List.of(invalidTrade), valid.candles());
    SpotMarketBundle malformedStructure = withComponents(
        valid, valid.orderBook(), valid.recentTrades(), List.of(invalidStructure));
    SpotMarketBundle malformedVolume = withComponents(
        valid, valid.orderBook(), valid.recentTrades(), List.of(invalidVolume));
    SpotMarketBundle malformedPrice = withComponents(
        valid, valid.orderBook(), valid.recentTrades(), List.of(invalidPrice));
    SpotMarketBundle fallback = completeSpot("okx", "BTC-USDT", NOW.minusSeconds(1));
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(
            candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", BundleAdapter.spotSequence(
                "binance", malformedDepth, malformedTrade, malformedStructure, malformedVolume, malformedPrice)),
            candidate("BTCUSDT", ProductType.CRYPTO_SPOT, "BTC-USDT", BundleAdapter.spot("okx", fallback))));

    List<SpotMarketBundle> results = java.util.stream.IntStream.range(0, 5)
        .mapToObj(ignored -> resolver.resolveSpot("BTCUSDT", CANDLES))
        .toList();

    assertThat(results).allMatch(result -> result == fallback);
  }

  @Test
  void candidateCannotReturnBundleLabeledAsAnotherProvider() {
    BundleAdapter mislabeledBinance = BundleAdapter.spot(
        "binance", completeSpot("okx", "BTC-USDT", NOW.minusSeconds(1)));
    SpotMarketBundle localBundle = completeSpot("local-spot", "BTCUSDT", NOW.minusSeconds(1));
    BundleAdapter local = BundleAdapter.spot("local-spot", localBundle);
    ProviderResolution binanceCandidate = candidate(
        "BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", mislabeledBinance);
    ProviderResolution localCandidate = candidate(
        "BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", local);
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(binanceCandidate, localCandidate));

    SpotMarketBundle result = resolver.resolveSpot("BTCUSDT", CANDLES);

    assertThat(result).isSameAs(localBundle);
    verify(healthRecorder).recordFailure(org.mockito.Mockito.same(binanceCandidate.provider()), anyLong());
  }

  @Test
  void bothPublicPerpProvidersFailThenLocalBundleIsSelected() {
    BundleAdapter binance = BundleAdapter.throwing("binance-usdm");
    BundleAdapter okx = BundleAdapter.perp("okx-swap", null);
    PerpetualMarketBundle localBundle = completePerp(
        "local-perp", "BTCUSDT-PERP", MarketSourceMode.LOCAL_SIMULATED, NOW.minusSeconds(1));
    BundleAdapter local = BundleAdapter.perp("local-perp", localBundle);
    when(providerResolver.resolveCandidates("BTCUSDT-PERP", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(
            candidate("BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTCUSDT", binance),
            candidate("BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTC-USDT-SWAP", okx),
            candidate("BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTCUSDT-PERP", local)));

    PerpetualMarketBundle result = resolver.resolvePerp("BTCUSDT-PERP", CANDLES);

    assertThat(result).isSameAs(localBundle);
    assertThat(result.sourceMode()).isEqualTo(MarketSourceMode.LOCAL_SIMULATED);
  }

  @Test
  void perpetualKeepsCanonicalAndVenueSymbolsSeparate() {
    PerpetualMarketBundle bundle = completePerp(
        "okx-swap", "BTC-USDT-SWAP", MarketSourceMode.PUBLIC_EXTERNAL, NOW.minusSeconds(1));
    BundleAdapter okx = BundleAdapter.perp("okx-swap", bundle);
    when(providerResolver.resolveCandidates("BTCUSDT-PERP", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(candidate("BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTC-USDT-SWAP", okx)));

    PerpetualMarketBundle result = resolver.resolvePerp("btcusdt-perp", CANDLES);

    assertThat(result.platformSymbol()).isEqualTo("BTCUSDT-PERP");
    assertThat(result.providerSymbol()).isEqualTo("BTC-USDT-SWAP");
    assertThat(result.mark()).isEqualByComparingTo("100.2");
    assertThat(result.index()).isEqualByComparingTo("100.1");
  }

  @Test
  void rejectsWhenNoCandidateCanProduceCompleteFreshBundle() {
    when(providerResolver.resolveCandidates("BTCUSDT", BUNDLE_CAPABILITIES))
        .thenReturn(List.of(candidate(
            "BTCUSDT", ProductType.CRYPTO_SPOT, "BTCUSDT", BundleAdapter.spot("binance", null))));

    assertThatThrownBy(() -> resolver.resolveSpot("BTCUSDT", CANDLES))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("MARKET_DATA_UNAVAILABLE"));
  }

  private ProviderResolution candidate(
      String platformSymbol,
      ProductType productType,
      String providerSymbol,
      MarketDataProviderAdapter adapter
  ) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(platformSymbol);
    symbol.setProductType(productType);
    symbol.setEnabled(true);
    symbol.setQuoteEnabled(true);
    symbol.setChartEnabled(true);
    symbol.setOrderBookEnabled(true);
    DataProviderEntity provider = new DataProviderEntity();
    provider.setId(UUID.randomUUID());
    provider.setCode(adapter.code());
    provider.setEnabled(true);
    SymbolProviderBindingEntity binding = new SymbolProviderBindingEntity();
    binding.setId(UUID.randomUUID());
    binding.setSymbolId(symbol.getId());
    binding.setProviderId(provider.getId());
    binding.setProviderSymbol(providerSymbol);
    binding.setEnabled(true);
    return new ProviderResolution(symbol, provider, binding, adapter);
  }

  private SpotMarketBundle completeSpot(String provider, String providerSymbol, Instant asOf) {
    MarketSourceMode mode = provider.startsWith("local")
        ? MarketSourceMode.LOCAL_SIMULATED
        : MarketSourceMode.PUBLIC_EXTERNAL;
    Instant expiresAt = asOf.plusSeconds(5);
    MarketDepthResponse depth = new MarketDepthResponse(
        "BTCUSDT", asOf.toEpochMilli(),
        List.of(new MarketDepthLevelResponse(new BigDecimal("99"), BigDecimal.ONE)),
        List.of(new MarketDepthLevelResponse(new BigDecimal("101"), BigDecimal.ONE)),
        provider, providerSymbol, mode, asOf, expiresAt, false);
    RecentTradeResponse trade = new RecentTradeResponse(
        "1", "BTCUSDT", new BigDecimal("100"), BigDecimal.ONE, "BUY", asOf.toEpochMilli(),
        provider, providerSymbol, mode, asOf, expiresAt, false);
    CandleResponse candle = new CandleResponse(
        NOW.minusSeconds(3600).toEpochMilli(), new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100"), BigDecimal.ONE,
        provider, providerSymbol, mode, asOf, expiresAt, false);
    return new SpotMarketBundle(
        "BTCUSDT", providerSymbol, provider, mode,
        new BigDecimal("99"), new BigDecimal("101"), new BigDecimal("100"),
        depth, List.of(trade), List.of(candle), asOf, expiresAt);
  }

  private PerpetualMarketBundle completePerp(
      String provider,
      String providerSymbol,
      MarketSourceMode mode,
      Instant asOf
  ) {
    Instant expiresAt = asOf.plusSeconds(5);
    MarketDepthResponse depth = new MarketDepthResponse(
        "BTCUSDT-PERP", asOf.toEpochMilli(),
        List.of(new MarketDepthLevelResponse(new BigDecimal("99"), BigDecimal.ONE)),
        List.of(new MarketDepthLevelResponse(new BigDecimal("101"), BigDecimal.ONE)),
        provider, providerSymbol, mode, asOf, expiresAt, false);
    RecentTradeResponse trade = new RecentTradeResponse(
        "1", "BTCUSDT-PERP", new BigDecimal("100"), BigDecimal.ONE, "BUY", asOf.toEpochMilli(),
        provider, providerSymbol, mode, asOf, expiresAt, false);
    CandleResponse candle = new CandleResponse(
        NOW.minusSeconds(3600).toEpochMilli(), new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100"), BigDecimal.ONE,
        provider, providerSymbol, mode, asOf, expiresAt, false);
    return new PerpetualMarketBundle(
        "BTCUSDT-PERP", providerSymbol, provider, mode,
        new BigDecimal("99"), new BigDecimal("101"), new BigDecimal("100"),
        new BigDecimal("100.2"), new BigDecimal("100.1"), depth,
        List.of(trade), List.of(candle), asOf, expiresAt);
  }

  private SpotMarketBundle withAuthorityPrices(SpotMarketBundle bundle) {
    return new SpotMarketBundle(
        bundle.platformSymbol(),
        bundle.providerSymbol(),
        bundle.providerCode(),
        bundle.sourceMode(),
        new BigDecimal("98"),
        new BigDecimal("102"),
        new BigDecimal("100"),
        bundle.changePercent(),
        bundle.high24h(),
        bundle.low24h(),
        bundle.volume24h(),
        bundle.orderBook(),
        bundle.recentTrades(),
        bundle.candles(),
        bundle.asOf(),
        bundle.expiresAt());
  }

  private PerpetualMarketBundle withAuthorityPrices(PerpetualMarketBundle bundle) {
    return new PerpetualMarketBundle(
        bundle.platformSymbol(),
        bundle.providerSymbol(),
        bundle.providerCode(),
        bundle.sourceMode(),
        new BigDecimal("98"),
        new BigDecimal("102"),
        new BigDecimal("100"),
        new BigDecimal("100"),
        new BigDecimal("100"),
        bundle.changePercent(),
        bundle.high24h(),
        bundle.low24h(),
        bundle.volume24h(),
        bundle.orderBook(),
        bundle.recentTrades(),
        bundle.candles(),
        bundle.asOf(),
        bundle.expiresAt());
  }

  private SpotMarketBundle withComponents(
      SpotMarketBundle bundle,
      MarketDepthResponse depth,
      List<RecentTradeResponse> trades,
      List<CandleResponse> candles
  ) {
    return new SpotMarketBundle(
        bundle.platformSymbol(), bundle.providerSymbol(), bundle.providerCode(), bundle.sourceMode(),
        bundle.bid(), bundle.ask(), bundle.last(), depth, trades, candles, bundle.asOf(), bundle.expiresAt());
  }

  private static final class BundleAdapter implements MarketDataProviderAdapter {
    private final String code;
    private final List<SpotMarketBundle> spots;
    private final PerpetualMarketBundle perp;
    private final boolean throwing;
    private final boolean interrupting;
    private int spotCalls;
    private int perpCalls;

    private BundleAdapter(
        String code,
        List<SpotMarketBundle> spots,
        PerpetualMarketBundle perp,
        boolean throwing,
        boolean interrupting
    ) {
      this.code = code;
      this.spots = spots;
      this.perp = perp;
      this.throwing = throwing;
      this.interrupting = interrupting;
    }

    static BundleAdapter spot(String code, SpotMarketBundle spot) {
      return new BundleAdapter(code, java.util.Collections.singletonList(spot), null, false, false);
    }

    static BundleAdapter spotSequence(String code, SpotMarketBundle... spots) {
      return new BundleAdapter(code, java.util.Arrays.asList(spots), null, false, false);
    }

    static BundleAdapter perp(String code, PerpetualMarketBundle perp) {
      return new BundleAdapter(code, List.of(), perp, false, false);
    }

    static BundleAdapter throwing(String code) {
      return new BundleAdapter(code, List.of(), null, true, false);
    }

    static BundleAdapter interrupting(String code) {
      return new BundleAdapter(code, List.of(), null, false, true);
    }

    @Override
    public String code() {
      return code;
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
      if (interrupting) {
        spotCalls += 1;
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
      if (throwing) {
        throw new IllegalStateException("provider unavailable");
      }
      int index = Math.min(spotCalls, spots.size() - 1);
      spotCalls += 1;
      return index < 0 ? Optional.empty() : Optional.ofNullable(spots.get(index));
    }

    @Override
    public Optional<PerpetualMarketBundle> fetchPerpetualBundle(
        String platformSymbol,
        String providerSymbol,
        CandleRequest candleRequest
    ) {
      perpCalls += 1;
      if (interrupting) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
      if (throwing) {
        throw new IllegalStateException("provider unavailable");
      }
      return Optional.ofNullable(perp);
    }
  }
}
