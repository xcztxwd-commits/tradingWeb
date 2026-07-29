package com.fxplatform.validation.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.provider.MarketBundleValidator;
import com.fxplatform.market.provider.ProviderResolver;
import com.fxplatform.market.service.MarketSourceSelectionTracker;
import com.fxplatform.market.service.ProviderHealthRecorder;
import com.fxplatform.validation.service.ValidationDemoExecutionPolicyProvider;
import com.fxplatform.validation.service.ValidationMarketClock;
import com.fxplatform.validation.service.ValidationMarketPathService;
import com.fxplatform.validation.service.ValidationMarketPathService.PathRequest;
import com.fxplatform.validation.service.ValidationMarketState;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import com.fxplatform.validation.service.ValidationResetGate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ValidationMarketClockTest {

  private static final UUID RUN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000501");
  private static final long GENERATION = 41L;
  private static final Instant START = Instant.parse("2026-07-23T00:00:00Z");

  @Test
  void clockAdvancesByExactlyOneContiguousVirtualSecondAndFencesOldGenerations() {
    ValidationMarketClock clock = new ValidationMarketClock();
    clock.reset(GENERATION);

    var zero = clock.start(RUN_ID, GENERATION, START);
    var one = clock.advance(RUN_ID, GENERATION);
    var two = clock.advance(RUN_ID, GENERATION);

    assertThat(zero.sequence()).isZero();
    assertThat(zero.virtualTime()).isEqualTo(START);
    assertThat(one.sequence()).isEqualTo(1L);
    assertThat(one.virtualTime()).isEqualTo(START.plusSeconds(1));
    assertThat(two.sequence()).isEqualTo(2L);
    assertThat(two.virtualTime()).isEqualTo(START.plusSeconds(2));
    assertThat(clock.current()).containsSame(two);

    clock.reset(GENERATION + 1);
    assertThat(clock.current()).isEmpty();
    assertBusinessCode(
        () -> clock.advance(RUN_ID, GENERATION),
        "VALIDATION_GENERATION_FENCED");
  }

  @Test
  void oneCompositeTickPublishesEverySymbolAtomicallyAndOnlyExactReplayIsAllowed() {
    ValidationMarketState state = new ValidationMarketState();
    state.reset(GENERATION);
    CompositeTick tick = tick(1L, "tick-1", "50000", "3000");

    CompositeTick stored = state.publish(tick);
    CompositeTick replay = state.publish(tick(1L, "tick-1", "50000", "3000"));

    assertThat(replay).isSameAs(stored);
    assertThat(state.current()).containsSame(stored);
    assertThat(stored.spotBundles())
        .extracting(SpotMarketBundle::platformSymbol)
        .containsExactly("BTCUSDT", "ETHUSDT");
    assertThat(stored.perpetualBundles())
        .extracting(PerpetualMarketBundle::platformSymbol)
        .containsExactly("BTCUSDT");

    assertBusinessCode(
        () -> state.publish(tick(1L, "tick-1-conflict", "50001", "3001")),
        "VALIDATION_TICK_CONFLICT");
    assertBusinessCode(
        () -> state.publish(tick(3L, "tick-3", "50003", "3003")),
        "VALIDATION_TICK_SEQUENCE_GAP");
  }

  @Test
  void concurrentReadersNeverObserveAMixedMultiSymbolTick() throws Exception {
    ValidationMarketState state = new ValidationMarketState();
    state.reset(GENERATION);
    state.publish(tick(1L, "tick-1", "50001", "50001"));

    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch readersDone = new CountDownLatch(3);
    AtomicBoolean writerDone = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      for (int reader = 0; reader < 3; reader++) {
        pool.execute(() -> {
          try {
            start.await();
            while (!writerDone.get()) {
              CompositeTick snapshot = state.current().orElseThrow();
              BigDecimal expected = new BigDecimal("50000").add(BigDecimal.valueOf(snapshot.sequence()));
              assertThat(snapshot.spotBundles())
                  .hasSize(2)
                  .allSatisfy(bundle -> assertThat(bundle.last()).isEqualByComparingTo(expected));
            }
          } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
          } finally {
            readersDone.countDown();
          }
        });
      }
      pool.execute(() -> {
        try {
          start.await();
          for (long sequence = 2; sequence <= 100; sequence++) {
            String price = BigDecimal.valueOf(50000L + sequence).toPlainString();
            state.publish(tick(sequence, "tick-" + sequence, price, price));
          }
        } catch (Throwable throwable) {
          failure.compareAndSet(null, throwable);
        } finally {
          writerDone.set(true);
        }
      });

      start.countDown();
      assertThat(readersDone.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(failure.get()).isNull();
      assertThat(state.current().orElseThrow().sequence()).isEqualTo(100L);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void validationAuthorityFailsClosedWithoutConsultingNormalProviders() {
    ValidationMarketState state = new ValidationMarketState();
    state.reset(GENERATION);
    ProviderResolver providers = mock(ProviderResolver.class);
    Clock fixedClock = Clock.fixed(START, ZoneOffset.UTC);
    MarketBundleResolver resolver = new MarketBundleResolver(
        providers,
        new MarketBundleValidator(fixedClock),
        mock(MarketSourceSelectionTracker.class),
        mock(ProviderHealthRecorder.class),
        fixedClock,
        state);
    CandleRequest candles = new CandleRequest("1m", START.minusSeconds(60), START);

    assertBusinessCode(
        () -> resolver.resolveSpot("ETHUSDT", candles),
        "VALIDATION_MARKET_STATE_MISSING");
    state.publish(tick(1L, "tick-1", "50000", "3000"));
    assertBusinessCode(
        () -> resolver.resolvePerp("ETHUSDT", candles),
        "VALIDATION_MARKET_STATE_MISSING");
    verifyNoInteractions(providers);
  }

  @Test
  void frozenPolicyPreservesObjectIdentityAndRejectsAnyMidRunChange() {
    ValidationDemoExecutionPolicyProvider provider =
        new ValidationDemoExecutionPolicyProvider();
    provider.reset(GENERATION);
    DemoExecutionPolicy first = policy("0.0005");

    assertBusinessCode(provider::current, "VALIDATION_POLICY_NOT_FROZEN");
    assertThat(provider.freeze(RUN_ID, GENERATION, "policy-v1", first)).isSameAs(first);
    assertThat(provider.freeze(RUN_ID, GENERATION, "policy-v1", policy("0.0005")))
        .isSameAs(first);
    assertThat(provider.current()).isSameAs(first);
    assertBusinessCode(
        () -> provider.freeze(RUN_ID, GENERATION, "policy-v2", policy("0.0007")),
        "VALIDATION_POLICY_FROZEN_CONFLICT");

    provider.reset(GENERATION + 1);
    DemoExecutionPolicy restored = policy("0.0009");
    assertThat(provider.restore(RUN_ID, GENERATION + 1, "policy-restored", restored))
        .isSameAs(restored);
    assertThat(provider.current()).isSameAs(restored);
    assertThat(provider.restore(
        RUN_ID, GENERATION + 1, "policy-restored", policy("0.0009")))
        .isSameAs(restored);
  }

  @Test
  void marketPathRejectsEmptyAndIncompleteTicksBeforeFreezingClockOrPolicy() {
    ValidationResetGate gate = new ValidationResetGate();
    gate.hydrateReady(GENERATION);
    ValidationMarketClock clock = new ValidationMarketClock();
    clock.reset(GENERATION);
    ValidationDemoExecutionPolicyProvider provider =
        new ValidationDemoExecutionPolicyProvider();
    provider.reset(GENERATION);
    ValidationMarketState state = new ValidationMarketState();
    state.reset(GENERATION);
    ValidationMarketPathService paths =
        new ValidationMarketPathService(gate, clock, provider, state);

    assertBusinessCode(
        () -> paths.start(pathRequest(List.of())),
        "VALIDATION_MARKET_PATH_EMPTY");
    assertThat(clock.current()).isEmpty();
    assertThat(state.current()).isEmpty();
    assertBusinessCode(provider::current, "VALIDATION_POLICY_NOT_FROZEN");

    CompositeTick incomplete = new CompositeTick(
        RUN_ID,
        GENERATION,
        1L,
        START.plusSeconds(1),
        "incomplete-tick",
        List.of(incompleteSpot("BTCUSDT", "50000", START.plusSeconds(1))),
        List.of());
    assertBusinessCode(
        () -> paths.start(pathRequest(List.of(incomplete))),
        "VALIDATION_MARKET_TICK_INCOMPLETE");
    assertThat(clock.current()).isEmpty();
    assertThat(state.current()).isEmpty();
    assertBusinessCode(provider::current, "VALIDATION_POLICY_NOT_FROZEN");

    CompositeTick firstTick = tick(1L, "tick-1", "50000", "3000");
    var receipt = paths.start(pathRequest(List.of(firstTick)));
    assertThat(receipt.tickCount()).isEqualTo(1);
    assertThat(clock.current().orElseThrow().sequence()).isZero();
    assertThat(provider.current()).isEqualTo(DemoExecutionPolicy.defaults());
    assertThat(state.current()).containsSame(firstTick);
    assertThat(paths.start(pathRequest(List.of(firstTick)))).isEqualTo(receipt);
    assertThat(clock.current().orElseThrow().sequence()).isZero();
    assertThat(state.current()).containsSame(firstTick);
  }

  @Test
  void marketPathRejectsABundleTimestampOutsideItsOwningVirtualTick() {
    ValidationResetGate gate = new ValidationResetGate();
    gate.hydrateReady(GENERATION);
    ValidationMarketClock clock = new ValidationMarketClock();
    clock.reset(GENERATION);
    ValidationDemoExecutionPolicyProvider provider =
        new ValidationDemoExecutionPolicyProvider();
    provider.reset(GENERATION);
    ValidationMarketState state = new ValidationMarketState();
    state.reset(GENERATION);
    ValidationMarketPathService paths =
        new ValidationMarketPathService(gate, clock, provider, state);
    Instant tickTime = START.plusSeconds(1);
    CompositeTick mismatched = new CompositeTick(
        RUN_ID,
        GENERATION,
        1L,
        tickTime,
        "mismatched-bundle-time",
        List.of(spot("BTCUSDT", "50000", tickTime.plusSeconds(1))),
        List.of());

    assertBusinessCode(
        () -> paths.start(pathRequest(List.of(mismatched))),
        "VALIDATION_MARKET_TICK_INCOMPLETE");
    assertThat(clock.current()).isEmpty();
    assertThat(state.current()).isEmpty();
    assertBusinessCode(provider::current, "VALIDATION_POLICY_NOT_FROZEN");
  }

  private static PathRequest pathRequest(List<CompositeTick> ticks) {
    return new PathRequest(
        RUN_ID,
        GENERATION,
        "path-v1",
        START,
        DemoExecutionPolicy.defaults(),
        ticks);
  }

  private static CompositeTick tick(
      long sequence,
      String fingerprint,
      String btcPrice,
      String ethPrice
  ) {
    Instant time = START.plusSeconds(sequence);
    return new CompositeTick(
        RUN_ID,
        GENERATION,
        sequence,
        time,
        fingerprint,
        List.of(spot("ETHUSDT", ethPrice, time), spot("BTCUSDT", btcPrice, time)),
        List.of(perpetual("BTCUSDT", btcPrice, time)));
  }

  private static SpotMarketBundle spot(String symbol, String price, Instant asOf) {
    BigDecimal last = new BigDecimal(price);
    String providerSymbol = symbol;
    Instant expiresAt = asOf.plusSeconds(2);
    return new SpotMarketBundle(
        symbol,
        providerSymbol,
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        last.subtract(BigDecimal.ONE),
        last.add(BigDecimal.ONE),
        last,
        depth(symbol, providerSymbol, last, asOf, expiresAt),
        List.of(trade(symbol, providerSymbol, last, asOf, expiresAt)),
        List.of(candle(providerSymbol, last, asOf, expiresAt)),
        asOf,
        expiresAt);
  }

  private static SpotMarketBundle incompleteSpot(String symbol, String price, Instant asOf) {
    BigDecimal last = new BigDecimal(price);
    String providerSymbol = symbol;
    Instant expiresAt = asOf.plusSeconds(2);
    return new SpotMarketBundle(
        symbol,
        providerSymbol,
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        last.subtract(BigDecimal.ONE),
        last.add(BigDecimal.ONE),
        last,
        depth(symbol, providerSymbol, last, asOf, expiresAt),
        List.of(),
        List.of(candle(providerSymbol, last, asOf, expiresAt)),
        asOf,
        expiresAt);
  }

  private static PerpetualMarketBundle perpetual(String symbol, String price, Instant asOf) {
    BigDecimal last = new BigDecimal(price);
    String providerSymbol = symbol;
    Instant expiresAt = asOf.plusSeconds(2);
    return new PerpetualMarketBundle(
        symbol,
        providerSymbol,
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        last.subtract(BigDecimal.ONE),
        last.add(BigDecimal.ONE),
        last,
        last,
        last,
        depth(symbol, providerSymbol, last, asOf, expiresAt),
        List.of(trade(symbol, providerSymbol, last, asOf, expiresAt)),
        List.of(candle(providerSymbol, last, asOf, expiresAt)),
        asOf,
        expiresAt);
  }

  private static MarketDepthResponse depth(
      String symbol,
      String providerSymbol,
      BigDecimal last,
      Instant asOf,
      Instant expiresAt
  ) {
    return new MarketDepthResponse(
        symbol,
        asOf.toEpochMilli(),
        List.of(new MarketDepthLevelResponse(last.subtract(BigDecimal.ONE), BigDecimal.TEN)),
        List.of(new MarketDepthLevelResponse(last.add(BigDecimal.ONE), BigDecimal.TEN)),
        "validation",
        providerSymbol,
        MarketSourceMode.LOCAL_SIMULATED,
        asOf,
        expiresAt,
        false);
  }

  private static RecentTradeResponse trade(
      String symbol,
      String providerSymbol,
      BigDecimal last,
      Instant asOf,
      Instant expiresAt
  ) {
    return new RecentTradeResponse(
        "trade-" + asOf.getEpochSecond(),
        symbol,
        last,
        BigDecimal.ONE,
        "BUY",
        asOf.toEpochMilli(),
        "validation",
        providerSymbol,
        MarketSourceMode.LOCAL_SIMULATED,
        asOf,
        expiresAt,
        false);
  }

  private static CandleResponse candle(
      String providerSymbol,
      BigDecimal last,
      Instant asOf,
      Instant expiresAt
  ) {
    return new CandleResponse(
        asOf.toEpochMilli(),
        last,
        last.add(BigDecimal.ONE),
        last.subtract(BigDecimal.ONE),
        last,
        BigDecimal.TEN,
        "validation",
        providerSymbol,
        MarketSourceMode.LOCAL_SIMULATED,
        asOf,
        expiresAt,
        false);
  }

  private static DemoExecutionPolicy policy(String takerFee) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.0002"),
        new BigDecimal(takerFee),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(),
        List.of(),
        new BigDecimal("2"));
  }

  private static void assertBusinessCode(Runnable action, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }
}
