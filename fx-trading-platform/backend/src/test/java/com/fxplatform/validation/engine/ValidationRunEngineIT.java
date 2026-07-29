package com.fxplatform.validation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Command;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.HttpResult;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Operation;
import com.fxplatform.validation.service.ValidationMarketClock;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import com.fxplatform.validation.service.ValidationRunEngine;
import com.fxplatform.validation.service.ValidationRunEngine.AccountSettings;
import com.fxplatform.validation.service.ValidationRunEngine.PublicAction;
import com.fxplatform.validation.service.ValidationRunEngine.PublicActionType;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import com.fxplatform.validation.service.ValidationRunEventStore;
import com.fxplatform.validation.service.ValidationRunEventStore.EventWrite;
import com.fxplatform.validation.service.ValidationRunOrchestrator;
import com.fxplatform.validation.service.ValidationRunPacer;
import com.fxplatform.validation.service.ValidationRunRuntimeStore;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.BoundaryOutcome;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.LeaseClaim;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.OperationIntent;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.OperationState;
import com.fxplatform.validation.service.ValidationSystemStepService.Request;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ValidationRunEngineIT {

  static final UUID RUN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000503");
  static final long GENERATION = 23L;
  static final Instant START = Instant.parse("2026-07-23T01:00:00Z");
  static final LeaseClaim CLAIM = new LeaseClaim(
      RUN_ID,
      GENERATION,
      "test-owner",
      UUID.fromString("00000000-0000-0000-0000-000000000599"));

  @Test
  void startIsDurablyIdempotentReturnsBeforeWorkAndLaunchesOnlyOnce() {
    Harness harness = new Harness(request());
    when(harness.runtime.accept(eq(harness.request), any())).thenReturn(true, false);

    var first = harness.orchestrator.start(harness.request);
    var replay = harness.orchestrator.start(harness.request);

    assertThat(first).isEqualTo(replay);
    assertThat(first.runId()).isEqualTo(RUN_ID);
    assertThat(first.requestFingerprint()).isEqualTo("run-fingerprint-v1");
    assertThat(harness.pacer.pending()).isEqualTo(1);
    verify(harness.loopback, never()).execute(any());

    harness.pacer.runNext();
    verify(harness.runtime).claim(RUN_ID);
  }

  @Test
  void everyTickRunsPreThenOrderedPublicActionsThenPostThroughLoopbackOnly() {
    Harness harness = new Harness(request());
    harness.engine.start(harness.request);
    harness.engine.execute(RUN_ID, false);

    ArgumentCaptor<Command> commands = ArgumentCaptor.forClass(Command.class);
    verify(harness.loopback, atLeastOnce()).execute(commands.capture());
    List<Command> all = commands.getAllValues();

    assertThat(all).extracting(Command::operation).containsSubsequence(
        Operation.REGISTER,
        Operation.SEED_ACCOUNT,
        Operation.START_MARKET_PATH,
        Operation.CONFIGURE_ACCOUNT);
    assertThat(all.stream()
        .filter(command -> Set.of(Operation.SYSTEM_STEP, Operation.PUBLIC_ACTION)
            .contains(command.operation()))
        .toList())
        .satisfiesExactly(
            command -> assertSystemPhase(command, "PRE_ACTIONS"),
            command -> assertPublicAction(command, "00000000-0000-0000-0000-000000000011"),
            command -> assertPublicAction(command, "00000000-0000-0000-0000-000000000012"),
            command -> assertSystemPhase(command, "POST_ACTIONS"));

    for (Command command : all) {
      assertThat(harness.trace.indexOf("intent:" + command.idempotencyKey()))
          .isLessThan(harness.trace.indexOf("http:" + command.idempotencyKey()));
    }
    verify(harness.runtime).releaseLease(CLAIM);
  }

  @Test
  void accountConfigurationRunsAgainstThePublishedFirstTickClock() {
    StartRequest base = request();
    CompositeTick firstTick = tickWithSpotAndPerpetual(1L);
    StartRequest nonDefaultLeverage = new StartRequest(
        base.runId(),
        base.generation(),
        base.requestFingerprint(),
        base.seed(),
        base.virtualStart(),
        base.executionPolicy(),
        base.initialBalances(),
        new AccountSettings("HEDGE", "CROSS", 8, "BASE"),
        List.of(firstTick),
        List.of(),
        base.speedMultiplier());
    Harness harness = new Harness(nonDefaultLeverage);
    ValidationMarketClock.Tick[] configuredAt = new ValidationMarketClock.Tick[1];
    ValidationMarketClock.Tick[] preActionsAt = new ValidationMarketClock.Tick[1];
    when(harness.loopback.execute(org.mockito.ArgumentMatchers.argThat(command ->
        command != null
            && command.operation() == Operation.CONFIGURE_ACCOUNT))).thenAnswer(invocation -> {
          configuredAt[0] = harness.clock.current().orElseThrow();
          return new HttpResult(200, "configured-at-first-tick", Map.of());
        });
    when(harness.loopback.execute(org.mockito.ArgumentMatchers.argThat(command ->
        command != null
            && command.operation() == Operation.SYSTEM_STEP
            && command.body() instanceof Request step
            && step.phase().name().equals("PRE_ACTIONS")))).thenAnswer(invocation -> {
              preActionsAt[0] = harness.clock.current().orElseThrow();
              return new HttpResult(200, "pre-actions-at-first-tick", Map.of());
            });

    harness.engine.execute(RUN_ID, false);

    assertThat(configuredAt[0]).isNotNull();
    assertThat(configuredAt[0].sequence()).isEqualTo(firstTick.sequence());
    assertThat(configuredAt[0].virtualTime()).isEqualTo(firstTick.virtualTime());
    assertThat(preActionsAt[0]).isNotNull();
    assertThat(preActionsAt[0].sequence()).isEqualTo(firstTick.sequence());
    assertThat(preActionsAt[0].virtualTime()).isEqualTo(firstTick.virtualTime());
  }

  @Test
  @SuppressWarnings("unchecked")
  void marketTickDurablyCarriesActualInstrumentPricesAsDecimalStrings() {
    StartRequest base = request();
    StartRequest actualPrices = new StartRequest(
        base.runId(),
        base.generation(),
        base.requestFingerprint(),
        base.seed(),
        base.virtualStart(),
        base.executionPolicy(),
        base.initialBalances(),
        base.accountSettings(),
        List.of(tickWithSpotAndPerpetual(1L)),
        base.actions(),
        base.speedMultiplier());
    Harness harness = new Harness(actualPrices);

    harness.engine.execute(RUN_ID, false);

    ArgumentCaptor<List<EventWrite>> eventBatches =
        ArgumentCaptor.forClass(List.class);
    verify(harness.runtime, atLeastOnce()).appendEvents(eq(CLAIM), eventBatches.capture());
    EventWrite marketTick = eventBatches.getAllValues().stream()
        .flatMap(List::stream)
        .filter(event -> "MARKET_TICK".equals(event.type()))
        .findFirst()
        .orElseThrow();

    assertThat(marketTick.payload())
        .containsEntry("tickSequence", 1L)
        .containsEntry("instruments", List.of(
            Map.of(
                "productType", "CRYPTO_SPOT",
                "symbol", "BTCUSDT",
                "bid", "50000",
                "ask", "50002",
                "last", "50001"),
            Map.of(
                "productType", "LINEAR_PERP",
                "symbol", "ETHUSDT",
                "bid", "2999.10",
                "ask", "3001.10",
                "last", "3000.10",
                "mark", "3000.20",
                "index", "3000.30")));
  }

  @Test
  void executeAlwaysReleasesTheLiveLeaseSessionWhenRunLoadingFails() {
    Harness harness = new Harness(request());
    when(harness.runtime.require(RUN_ID)).thenThrow(new IllegalStateException("load failed"));

    assertThatThrownBy(() -> harness.engine.execute(RUN_ID, true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("load failed");

    verify(harness.runtime).releaseLease(CLAIM);
  }

  @Test
  void minimumSpeedPacingRechecksTheDurableLeaseWithinABoundedInterval() throws Exception {
    StartRequest base = request();
    StartRequest slow = new StartRequest(
        base.runId(),
        base.generation(),
        base.requestFingerprint(),
        base.seed(),
        base.virtualStart(),
        base.executionPolicy(),
        base.initialBalances(),
        base.accountSettings(),
        List.of(tick(1L), tick(2L)),
        base.actions(),
        new BigDecimal("0.000001"));
    Harness harness = new Harness(slow);
    CountDownLatch paceFenceCheck = new CountDownLatch(1);
    doAnswer(invocation -> {
      boolean insidePace = Arrays.stream(Thread.currentThread().getStackTrace())
          .anyMatch(frame -> "pace".equals(frame.getMethodName()));
      if (insidePace) {
        paceFenceCheck.countDown();
        throw new BusinessException(
            "VALIDATION_GENERATION_FENCED",
            "test reset fence");
      }
      return null;
    }).when(harness.runtime).heartbeat(CLAIM);

    Thread worker = Thread.ofVirtual().start(() -> harness.engine.execute(RUN_ID, false));
    try {
      assertThat(paceFenceCheck.await(2L, TimeUnit.SECONDS)).isTrue();
      assertThat(worker.join(java.time.Duration.ofSeconds(2L))).isTrue();
      verify(harness.runtime).releaseLease(CLAIM);
    } finally {
      worker.interrupt();
      worker.join(2_000L);
    }
  }

  @Test
  void engineAndScenarioContractsCannotCarryAnArbitraryNetworkTarget() throws Exception {
    Constructor<?>[] publicConstructors = Arrays.stream(ValidationRunEngine.class.getConstructors())
        .filter(constructor -> java.lang.reflect.Modifier.isPublic(constructor.getModifiers()))
        .toArray(Constructor<?>[]::new);
    assertThat(publicConstructors).singleElement().satisfies(constructor ->
        assertThat(constructor.getParameterTypes()).containsExactly(
            ValidationLoopbackHttpClient.class,
            ValidationRunRuntimeStore.class,
            ValidationMarketClock.class,
            ValidationRunEventStore.class));

    assertThat(ValidationLoopbackHttpClient.LOOPBACK_BASE_URI.toString())
        .isEqualTo("http://127.0.0.1:8080");
    assertNoNetworkFields(Command.class);
    assertNoNetworkFields(StartRequest.class);
    assertNoNetworkFields(PublicAction.class);
    assertThat(Operation.values()).containsExactly(
        Operation.REGISTER,
        Operation.SEED_ACCOUNT,
        Operation.CONFIGURE_ACCOUNT,
        Operation.START_MARKET_PATH,
        Operation.SYSTEM_STEP,
        Operation.PUBLIC_ACTION,
        Operation.QUERY_STATE);

    String source = Files.readString(Path.of(
        "src/main/java/com/fxplatform/validation/service/ValidationRunEngine.java"));
    assertThat(source)
        .doesNotContain(
            "com.fxplatform.trading.",
            "com.fxplatform.wallet.",
            "com.fxplatform.ledger.",
            "FundingSettlement",
            "Liquidation",
            "MarketBundleResolver",
            "OrderService");
  }

  private static void assertNoNetworkFields(Class<?> recordType) {
    assertThat(recordType.isRecord()).isTrue();
    assertThat(Arrays.stream(recordType.getRecordComponents())
        .map(RecordComponent::getName)
        .map(String::toLowerCase)
        .toList())
        .noneMatch(name -> Set.of("url", "uri", "path", "method", "headers", "command")
            .contains(name));
  }

  private static void assertSystemPhase(Command command, String expectedPhase) {
    assertThat(command.operation()).isEqualTo(Operation.SYSTEM_STEP);
    Request step = (Request) command.body();
    assertThat(step.phase().name()).isEqualTo(expectedPhase);
  }

  private static void assertPublicAction(Command command, String actionId) {
    assertThat(command.operation()).isEqualTo(Operation.PUBLIC_ACTION);
    PublicAction action = (PublicAction) command.body();
    assertThat(action.actionId()).isEqualTo(UUID.fromString(actionId));
    assertThat(command.idempotencyKey()).isEqualTo(RUN_ID + ":" + action.actionId() + ":0");
  }

  static StartRequest request() {
    CompositeTick tick = tick(1L);
    PublicAction laterId = new PublicAction(
        UUID.fromString("00000000-0000-0000-0000-000000000012"),
        1L,
        9L,
        PublicActionType.PLACE_ORDER,
        "action-12",
        Map.of(
            "symbol", "BTCUSDT",
            "side", "BUY",
            "orderType", "MARKET",
            "quantity", new BigDecimal("0.01")));
    PublicAction earlierId = new PublicAction(
        UUID.fromString("00000000-0000-0000-0000-000000000011"),
        1L,
        9L,
        PublicActionType.CANCEL_ORDER,
        "action-11",
        Map.of("clientOrderId", "logical-order-11"));
    return new StartRequest(
        RUN_ID,
        GENERATION,
        "run-fingerprint-v1",
        "seed-validation-engine",
        START,
        DemoExecutionPolicy.defaults(),
        List.of(tick),
        List.of(laterId, earlierId),
        new BigDecimal("1000000"));
  }

  static CompositeTick tick(long sequence) {
    Instant time = START.plusSeconds(sequence);
    BigDecimal last = new BigDecimal("50000").add(BigDecimal.valueOf(sequence));
    Instant expiresAt = time.plusSeconds(2);
    MarketDepthResponse depth = new MarketDepthResponse(
        "BTCUSDT",
        time.toEpochMilli(),
        List.of(new MarketDepthLevelResponse(last.subtract(BigDecimal.ONE), BigDecimal.TEN)),
        List.of(new MarketDepthLevelResponse(last.add(BigDecimal.ONE), BigDecimal.TEN)),
        "validation",
        "BTCUSDT",
        MarketSourceMode.LOCAL_SIMULATED,
        time,
        expiresAt,
        false);
    SpotMarketBundle spot = new SpotMarketBundle(
        "BTCUSDT",
        "BTCUSDT",
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        last.subtract(BigDecimal.ONE),
        last.add(BigDecimal.ONE),
        last,
        depth,
        List.of(new RecentTradeResponse(
            "trade-" + sequence,
            "BTCUSDT",
            last,
            BigDecimal.ONE,
            "BUY",
            time.toEpochMilli(),
            "validation",
            "BTCUSDT",
            MarketSourceMode.LOCAL_SIMULATED,
            time,
            expiresAt,
            false)),
        List.of(new CandleResponse(
            time.toEpochMilli(),
            last,
            last.add(BigDecimal.ONE),
            last.subtract(BigDecimal.ONE),
            last,
            BigDecimal.TEN,
            "validation",
            "BTCUSDT",
            MarketSourceMode.LOCAL_SIMULATED,
            time,
            expiresAt,
            false)),
        time,
        expiresAt);
    return new CompositeTick(
        RUN_ID,
        GENERATION,
        sequence,
        time,
        "tick-" + sequence,
        List.of(spot),
        List.of());
  }

  static CompositeTick tickWithSpotAndPerpetual(long sequence) {
    CompositeTick spotOnly = tick(sequence);
    Instant time = spotOnly.virtualTime();
    Instant expiresAt = time.plusSeconds(2);
    BigDecimal last = new BigDecimal("3000.10");
    MarketDepthResponse depth = new MarketDepthResponse(
        "ETHUSDT",
        time.toEpochMilli(),
        List.of(new MarketDepthLevelResponse(new BigDecimal("2999.10"), BigDecimal.TEN)),
        List.of(new MarketDepthLevelResponse(new BigDecimal("3001.10"), BigDecimal.TEN)),
        "validation",
        "ETHUSDT",
        MarketSourceMode.LOCAL_SIMULATED,
        time,
        expiresAt,
        false);
    PerpetualMarketBundle perpetual = new PerpetualMarketBundle(
        "ETHUSDT",
        "ETHUSDT",
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        new BigDecimal("2999.10"),
        new BigDecimal("3001.10"),
        last,
        new BigDecimal("3000.20"),
        new BigDecimal("3000.30"),
        depth,
        List.of(new RecentTradeResponse(
            "perpetual-trade-" + sequence,
            "ETHUSDT",
            last,
            BigDecimal.ONE,
            "BUY",
            time.toEpochMilli(),
            "validation",
            "ETHUSDT",
            MarketSourceMode.LOCAL_SIMULATED,
            time,
            expiresAt,
            false)),
        List.of(new CandleResponse(
            time.toEpochMilli(),
            last,
            last.add(BigDecimal.ONE),
            last.subtract(BigDecimal.ONE),
            last,
            BigDecimal.TEN,
            "validation",
            "ETHUSDT",
            MarketSourceMode.LOCAL_SIMULATED,
            time,
            expiresAt,
            false)),
        time,
        expiresAt);
    return new CompositeTick(
        RUN_ID,
        GENERATION,
        sequence,
        time,
        "tick-" + sequence,
        spotOnly.spotBundles(),
        List.of(perpetual));
  }

  static final class Harness {

    final StartRequest request;
    final ValidationLoopbackHttpClient loopback = mock(ValidationLoopbackHttpClient.class);
    final ValidationRunRuntimeStore runtime = mock(ValidationRunRuntimeStore.class);
    final ValidationRunEventStore events = mock(ValidationRunEventStore.class);
    final CapturingPacer pacer = new CapturingPacer();
    final ValidationMarketClock clock = new ValidationMarketClock();
    final ValidationRunEngine engine;
    final ValidationRunOrchestrator orchestrator;
    final List<String> trace = new ArrayList<>();

    Harness(StartRequest request) {
      this.request = request;
      when(runtime.accept(eq(request), any())).thenReturn(true);
      when(runtime.claim(RUN_ID)).thenReturn(CLAIM);
      when(runtime.require(RUN_ID)).thenReturn(request);
      when(runtime.lastCompletedBoundary(CLAIM)).thenReturn(Optional.empty());
      when(runtime.settleBeforeWork(eq(CLAIM), any(), any()))
          .thenReturn(BoundaryOutcome.CONTINUE);
      when(runtime.settleBoundary(eq(CLAIM), anyBoolean(), any(), any(), any()))
          .thenAnswer(invocation -> invocation.<Boolean>getArgument(1)
              ? BoundaryOutcome.CONTINUE
              : BoundaryOutcome.COMPLETED);
      when(runtime.persistIntent(eq(CLAIM), any())).thenAnswer(invocation -> {
        Command command = invocation.getArgument(1);
        trace.add("intent:" + command.idempotencyKey());
        return new OperationIntent(
            UUID.nameUUIDFromBytes(command.idempotencyKey().getBytes(StandardCharsets.UTF_8)),
            command,
            START,
            OperationState.NEW,
            null);
      });
      when(loopback.execute(any(Command.class))).thenAnswer(invocation -> {
        Command command = invocation.getArgument(0);
        trace.add("http:" + command.idempotencyKey());
        Map<String, Object> body = command.operation() == Operation.REGISTER
            ? Map.of(
                "userId", "00000000-0000-0000-0000-000000000501",
                "accountId", "00000000-0000-0000-0000-000000000502")
            : Map.of("operation", command.operation().name());
        return new HttpResult(200, "correlation-" + command.idempotencyKey(), body);
      });
      clock.reset(GENERATION);
      engine = new ValidationRunEngine(loopback, runtime, clock, events);
      orchestrator = new ValidationRunOrchestrator(engine, pacer);
    }
  }

  static final class CapturingPacer implements ValidationRunPacer {

    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void launch(Runnable command) {
      tasks.add(command);
    }

    @Override
    public void awaitNextTick(BigDecimal speedMultiplier) {
      // The orchestrator only owns launch; deterministic pacing lives inside the engine.
    }

    int pending() {
      return tasks.size();
    }

    void runNext() {
      tasks.removeFirst().run();
    }
  }
}
