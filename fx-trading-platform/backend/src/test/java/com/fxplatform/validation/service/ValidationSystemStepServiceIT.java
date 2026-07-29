package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.service.CancelAllOrderService;
import com.fxplatform.trading.service.FundingSettlementProcessor;
import com.fxplatform.trading.service.LiquidationService;
import com.fxplatform.trading.service.LiquidationSettlementService;
import com.fxplatform.trading.service.PendingOrderExecutionProcessor;
import com.fxplatform.trading.service.PendingOrderExecutionService;
import com.fxplatform.trading.service.ProtectiveOrderExecutionService;
import com.fxplatform.trading.service.SystemCloseOrderService;
import com.fxplatform.trading.service.TrailingStopService;
import com.fxplatform.trading.service.TradingTransactionExecutor;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import com.fxplatform.validation.service.ValidationSystemStepService.Phase;
import com.fxplatform.validation.service.ValidationSystemStepService.Receipt;
import com.fxplatform.validation.service.ValidationSystemStepService.Request;
import com.fxplatform.validation.service.ValidationSystemStepService.SubStepResult;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ValidationSystemStepServiceIT {

  private static final UUID RUN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000502");
  private static final long GENERATION = 17L;

  @Test
  void preActionsPublishesThenUpdatesTrailingThenMatchesRestingThenTriggersProtection() {
    RecordingOperations operations = new RecordingOperations();
    ValidationSystemStepService service = service(operations, new MemoryReceiptStore());

    Receipt receipt = service.execute(request(Phase.PRE_ACTIONS, "pre-v1"));

    assertThat(operations.calls).containsExactly(
        "publish-tick",
        "update-trailing-extrema",
        "match-resting-orders",
        "trigger-protection-orders");
    assertThat(receipt.subSteps())
        .extracting(SubStepResult::name)
        .containsExactlyElementsOf(operations.calls);
    assertThat(receipt.phase()).isEqualTo(Phase.PRE_ACTIONS);
  }

  @Test
  void postActionsSettlesFundingThenLiquidatesThenCapturesCheckpoint() {
    RecordingOperations operations = new RecordingOperations();
    ValidationSystemStepService service = service(operations, new MemoryReceiptStore());
    service.execute(request(Phase.PRE_ACTIONS, "pre-v1"));
    operations.calls.clear();

    Receipt receipt = service.execute(request(Phase.POST_ACTIONS, "post-v1"));

    assertThat(operations.calls).containsExactly(
        "settle-persisted-funding",
        "scan-liquidations",
        "capture-checkpoint");
    assertThat(receipt.subSteps())
        .extracting(SubStepResult::name)
        .containsExactlyElementsOf(operations.calls);
  }

  @Test
  void fullIsExactlyPreActionsFollowedByPostActionsForManualNoActionTicks() {
    RecordingOperations operations = new RecordingOperations();
    service(operations, new MemoryReceiptStore()).execute(request(Phase.FULL, "full-v1"));

    assertThat(operations.calls).containsExactly(
        "publish-tick",
        "update-trailing-extrema",
        "match-resting-orders",
        "trigger-protection-orders",
        "settle-persisted-funding",
        "scan-liquidations",
        "capture-checkpoint");
  }

  @Test
  void exactPreReplayReturnsItsReceiptWithoutRepeatingBusinessSubSteps() {
    RecordingOperations operations = new RecordingOperations();
    MemoryReceiptStore receipts = new MemoryReceiptStore();
    ValidationSystemStepService service = service(operations, receipts);

    Receipt first = service.execute(request(Phase.PRE_ACTIONS, "pre-v1"));
    Receipt replay = service.execute(request(Phase.PRE_ACTIONS, "pre-v1"));

    assertThat(replay).isSameAs(first);
    assertThat(operations.calls).containsExactly(
        "publish-tick",
        "update-trailing-extrema",
        "match-resting-orders",
        "trigger-protection-orders");
    assertThat(receipts.size()).isEqualTo(1);
  }

  @Test
  void exactFullReplayReturnsItsReceiptWithoutRepeatingAnySubStep() {
    RecordingOperations operations = new RecordingOperations();
    MemoryReceiptStore receipts = new MemoryReceiptStore();
    ValidationSystemStepService service = service(operations, receipts);

    Receipt first = service.execute(request(Phase.FULL, "full-v1"));
    Receipt replay = service.execute(request(Phase.FULL, "full-v1"));

    assertThat(replay).isSameAs(first);
    assertThat(operations.calls).containsExactly(
        "publish-tick",
        "update-trailing-extrema",
        "match-resting-orders",
        "trigger-protection-orders",
        "settle-persisted-funding",
        "scan-liquidations",
        "capture-checkpoint");
    assertThat(receipts.size()).isEqualTo(1);
  }

  @Test
  void historicalPreReplayReturnsItsReceiptWithoutRepublishingOrRepeatingBusinessSubSteps() {
    RecordingOperations operations = new RecordingOperations();
    MemoryReceiptStore receipts = new MemoryReceiptStore();
    ValidationSystemStepService service = service(operations, receipts);

    Receipt historical = service.execute(request(7L, Phase.PRE_ACTIONS, "pre-v7"));
    service.execute(request(8L, Phase.PRE_ACTIONS, "pre-v8"));
    List<String> callsBeforeReplay = List.copyOf(operations.calls);

    Receipt replay = service.execute(request(7L, Phase.PRE_ACTIONS, "pre-v7"));

    assertThat(replay).isSameAs(historical);
    assertThat(operations.calls).containsExactlyElementsOf(callsBeforeReplay);
    assertThat(receipts.size()).isEqualTo(2);
  }

  @Test
  void historicalFullReplayReturnsItsReceiptWithoutRepublishingOrRepeatingAnySubStep() {
    RecordingOperations operations = new RecordingOperations();
    MemoryReceiptStore receipts = new MemoryReceiptStore();
    ValidationSystemStepService service = service(operations, receipts);

    Receipt historical = service.execute(request(7L, Phase.FULL, "full-v7"));
    service.execute(request(8L, Phase.FULL, "full-v8"));
    List<String> callsBeforeReplay = List.copyOf(operations.calls);

    Receipt replay = service.execute(request(7L, Phase.FULL, "full-v7"));

    assertThat(replay).isSameAs(historical);
    assertThat(operations.calls).containsExactlyElementsOf(callsBeforeReplay);
    assertThat(receipts.size()).isEqualTo(2);
  }

  @Test
  void productionRehydrateRestoresOnlyTheMissingTickAndIgnoresHistoricalReplay() {
    ValidationMarketState marketState = new ValidationMarketState();
    ValidationMarketClock marketClock = new ValidationMarketClock();
    marketState.reset(GENERATION);
    marketClock.reset(GENERATION);
    marketClock.start(RUN_ID, GENERATION, Instant.parse("2026-07-23T00:00:00Z"));
    marketClock.advance(RUN_ID, GENERATION);
    DefaultValidationSystemStepOperations operations = productionOperations(
        marketState,
        marketClock);
    Request first = completeRequest(1L, Phase.PRE_ACTIONS, "pre-v1");

    operations.rehydratePublishedTick(first);
    assertThat(marketState.current()).contains(first.tick());

    marketClock.advance(RUN_ID, GENERATION);
    Request second = completeRequest(2L, Phase.PRE_ACTIONS, "pre-v2");
    operations.rehydratePublishedTick(second);
    operations.rehydratePublishedTick(first);

    assertThat(marketState.current()).contains(second.tick());
  }

  @Test
  void productionRehydrateFailsClosedOnSameSequenceWithDifferentTickContent() {
    ValidationMarketState marketState = new ValidationMarketState();
    ValidationMarketClock marketClock = new ValidationMarketClock();
    marketState.reset(GENERATION);
    marketClock.reset(GENERATION);
    marketClock.start(RUN_ID, GENERATION, Instant.parse("2026-07-23T00:00:00Z"));
    marketClock.advance(RUN_ID, GENERATION);
    DefaultValidationSystemStepOperations operations = productionOperations(
        marketState,
        marketClock);
    Request stored = completeRequest(1L, Phase.PRE_ACTIONS, "pre-v1");
    operations.rehydratePublishedTick(stored);
    Request conflicting = new Request(
        new CompositeTick(
            RUN_ID,
            GENERATION,
            1L,
            stored.tick().virtualTime(),
            "conflicting-tick-v1",
            stored.tick().spotBundles(),
            stored.tick().perpetualBundles()),
        Phase.PRE_ACTIONS,
        "conflicting-pre-v1");

    assertBusinessCode(
        () -> operations.rehydratePublishedTick(conflicting),
        "VALIDATION_MARKET_REHYDRATION_CONFLICT");
    assertThat(marketState.current()).contains(stored.tick());
  }

  @Test
  void sameRunGenerationTickAndPhaseWithAnotherFingerprintFailsClosed() {
    RecordingOperations operations = new RecordingOperations();
    ValidationSystemStepService service = service(operations, new MemoryReceiptStore());
    service.execute(request(Phase.PRE_ACTIONS, "pre-v1"));

    assertBusinessCode(
        () -> service.execute(request(Phase.PRE_ACTIONS, "pre-conflict")),
        "VALIDATION_SYSTEM_STEP_CONFLICT");
    assertThat(operations.calls).hasSize(4);
  }

  @Test
  void postActionsRequiresPreActionsAndRunsNoSubStepWhenThePhaseOrderIsInvalid() {
    RecordingOperations operations = new RecordingOperations();
    ValidationSystemStepService service = service(operations, new MemoryReceiptStore());

    assertBusinessCode(
        () -> service.execute(request(Phase.POST_ACTIONS, "post-v1")),
        "VALIDATION_SYSTEM_STEP_PHASE_CONFLICT");
    assertThat(operations.calls).isEmpty();
  }

  @Test
  void fullAndSplitReceiptsCannotBeCombinedForTheSameTick() {
    RecordingOperations splitOperations = new RecordingOperations();
    ValidationSystemStepService split =
        service(splitOperations, new MemoryReceiptStore());
    split.execute(request(Phase.PRE_ACTIONS, "pre-v1"));

    assertBusinessCode(
        () -> split.execute(request(Phase.FULL, "full-v1")),
        "VALIDATION_SYSTEM_STEP_PHASE_CONFLICT");
    assertThat(splitOperations.calls).hasSize(4);

    RecordingOperations fullOperations = new RecordingOperations();
    ValidationSystemStepService full =
        service(fullOperations, new MemoryReceiptStore());
    full.execute(request(Phase.FULL, "full-v1"));

    assertBusinessCode(
        () -> full.execute(request(Phase.POST_ACTIONS, "post-v1")),
        "VALIDATION_SYSTEM_STEP_PHASE_CONFLICT");
    assertThat(fullOperations.calls).hasSize(7);
  }

  @Test
  void locksTheRunBeforeAnySubStepAndKeepsTickVirtualTimeOnTheReceipt() {
    MemoryReceiptStore receipts = new MemoryReceiptStore();
    RecordingOperations operations = new RecordingOperations(receipts::isLocked);
    ValidationSystemStepService service = service(operations, receipts);
    Request request = request(Phase.PRE_ACTIONS, "pre-v1");

    Receipt receipt = service.execute(request);

    assertThat(receipts.lockCalls).isEqualTo(1);
    assertThat(receipt.virtualTime()).isEqualTo(request.tick().virtualTime());
  }

  @Test
  void executeDeclaresOneRollbackCapableSpringTransaction() throws Exception {
    Transactional transactional = ValidationSystemStepService.class
        .getMethod("execute", Request.class)
        .getAnnotation(Transactional.class);

    assertThat(transactional).isNotNull();
    assertThat(transactional.rollbackFor()).contains(Exception.class);
  }

  @Test
  void productionAdapterUsesOnlyFailClosedJoinedExecutionEntrypoints() {
    PendingOrderExecutionService pending = mock(PendingOrderExecutionService.class);
    ProtectiveOrderExecutionService protective =
        mock(ProtectiveOrderExecutionService.class);
    LiquidationService liquidation = mock(LiquidationService.class);
    TrailingStopService trailing = mock(TrailingStopService.class);
    when(pending.executeRestingOrdersStrict()).thenReturn(2);
    when(pending.executeConditionalOrdersStrict()).thenReturn(3);
    when(protective.executeProtectiveOrdersStrict()).thenReturn(4);
    when(liquidation.scanAllAccountsStrict()).thenReturn(5);
    when(trailing.triggerReadyStrict(any(ExecutableMarketSnapshot.class))).thenReturn(6);
    DefaultValidationSystemStepOperations operations =
        new DefaultValidationSystemStepOperations(
            mock(ValidationMarketState.class),
            mock(ValidationMarketClock.class),
            mock(ValidationFundingRateSeeder.class),
            trailing,
            pending,
            protective,
            mock(FundingSettlementProcessor.class),
            liquidation,
            mock(JdbcTemplate.class));
    Instant virtualTime = Instant.parse("2026-07-23T00:00:07Z");
    PerpetualMarketBundle perpetual = new PerpetualMarketBundle(
        "BTCUSDT",
        "BTCUSDT",
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        null,
        null,
        null,
        virtualTime,
        virtualTime.plusSeconds(60));
    Request request = new Request(
        new CompositeTick(
            RUN_ID,
            GENERATION,
            7L,
            virtualTime,
            "tick-v7",
            List.of(),
            List.of(perpetual)),
        Phase.PRE_ACTIONS,
        "pre-v1");

    operations.matchRestingOrders(request);
    operations.triggerProtectionOrders(request);
    operations.scanLiquidations(request);

    verify(pending).executeRestingOrdersStrict();
    verify(pending).executeConditionalOrdersStrict();
    verify(trailing).triggerReadyStrict(any(ExecutableMarketSnapshot.class));
    verify(protective).executeProtectiveOrdersStrict();
    verify(liquidation).scanAllAccountsStrict();
    verify(pending, never()).executePendingOrders();
    verify(pending, never()).executeRestingOrders();
    verify(pending, never()).executeConditionalOrders();
    verify(trailing, never()).triggerReady(any(ExecutableMarketSnapshot.class));
    verify(protective, never()).executeProtectiveOrders();
    verify(liquidation, never()).scanAllAccounts();
  }

  @Test
  void strictDownstreamEntrypointsRequireTheOwningTransaction() throws Exception {
    assertMandatory(PendingOrderExecutionService.class, "executeRestingOrdersStrict");
    assertMandatory(PendingOrderExecutionService.class, "executeConditionalOrdersStrict");
    assertMandatory(
        PendingOrderExecutionProcessor.class,
        "processStrict",
        OrderEntity.class,
        ExecutableMarketSnapshot.class);
    assertMandatory(
        TrailingStopService.class,
        "triggerReadyStrict",
        ExecutableMarketSnapshot.class);
    assertMandatory(ProtectiveOrderExecutionService.class, "executeProtectiveOrdersStrict");
    assertMandatory(
        SystemCloseOrderService.class,
        "executeProtectionStrict",
        UUID.class,
        ExecutableMarketSnapshot.class);
    assertMandatory(
        SystemCloseOrderService.class,
        "closeWholeStrict",
        UUID.class,
        UUID.class,
        OrderOrigin.class,
        String.class,
        String.class);
    assertMandatory(
        CancelAllOrderService.class,
        "cancelRiskIncreasingSlotStrict",
        UUID.class,
        UUID.class);
    assertMandatory(
        CancelAllOrderService.class,
        "cancelActiveSlotStrict",
        UUID.class,
        UUID.class);
    assertMandatory(
        CancelAllOrderService.class,
        "cancelActivePerpetualStrict",
        UUID.class);
    assertMandatory(LiquidationService.class, "scanAllAccountsStrict");
    assertMandatory(
        LiquidationSettlementService.class,
        "settleIfReadyStrict",
        UUID.class);
    assertMandatory(TradingTransactionExecutor.class, "executeJoined", Supplier.class);
  }

  @Test
  void publicActionsCannotBeSmuggledIntoTheSystemStepPort() {
    Set<String> portMethods = Arrays.stream(ValidationSystemStepOperations.class.getDeclaredMethods())
        .map(Method::getName)
        .collect(Collectors.toSet());

    assertThat(portMethods).containsExactlyInAnyOrder(
        "publishTick",
        "rehydratePublishedTick",
        "updateTrailingExtrema",
        "matchRestingOrders",
        "triggerProtectionOrders",
        "settlePersistedFunding",
        "scanLiquidations",
        "captureCheckpoint");
    assertThat(portMethods).noneMatch(name -> name.toLowerCase().contains("user"));
  }

  private static ValidationSystemStepService service(
      ValidationSystemStepOperations operations,
      ValidationSystemStepReceiptStore receipts
  ) {
    return new ValidationSystemStepService(operations, receipts);
  }

  private static DefaultValidationSystemStepOperations productionOperations(
      ValidationMarketState marketState,
      ValidationMarketClock marketClock
  ) {
    return new DefaultValidationSystemStepOperations(
        marketState,
        marketClock,
        mock(ValidationFundingRateSeeder.class),
        mock(TrailingStopService.class),
        mock(PendingOrderExecutionService.class),
        mock(ProtectiveOrderExecutionService.class),
        mock(FundingSettlementProcessor.class),
        mock(LiquidationService.class),
        mock(JdbcTemplate.class));
  }

  private static Request request(Phase phase, String fingerprint) {
    return request(7L, phase, fingerprint);
  }

  private static Request request(long tickSequence, Phase phase, String fingerprint) {
    CompositeTick tick = new CompositeTick(
        RUN_ID,
        GENERATION,
        tickSequence,
        Instant.parse("2026-07-23T00:00:00Z").plusSeconds(tickSequence),
        "tick-v" + tickSequence,
        List.of(),
        List.of());
    return new Request(tick, phase, fingerprint);
  }

  private static Request completeRequest(
      long tickSequence,
      Phase phase,
      String fingerprint
  ) {
    Instant virtualTime = Instant.parse("2026-07-23T00:00:00Z").plusSeconds(tickSequence);
    Instant expiresAt = virtualTime.plusSeconds(2L);
    BigDecimal last = BigDecimal.valueOf(100L + tickSequence);
    String symbol = "BTCUSDT";
    MarketDepthResponse depth = new MarketDepthResponse(
        symbol,
        virtualTime.toEpochMilli(),
        List.of(new MarketDepthLevelResponse(last.subtract(BigDecimal.ONE), BigDecimal.TEN)),
        List.of(new MarketDepthLevelResponse(last.add(BigDecimal.ONE), BigDecimal.TEN)),
        "validation",
        symbol,
        MarketSourceMode.LOCAL_SIMULATED,
        virtualTime,
        expiresAt,
        false);
    RecentTradeResponse trade = new RecentTradeResponse(
        "trade-" + tickSequence,
        symbol,
        last,
        BigDecimal.ONE,
        "BUY",
        virtualTime.toEpochMilli(),
        "validation",
        symbol,
        MarketSourceMode.LOCAL_SIMULATED,
        virtualTime,
        expiresAt,
        false);
    CandleResponse candle = new CandleResponse(
        virtualTime.toEpochMilli(),
        last,
        last.add(BigDecimal.ONE),
        last.subtract(BigDecimal.ONE),
        last,
        BigDecimal.TEN,
        "validation",
        symbol,
        MarketSourceMode.LOCAL_SIMULATED,
        virtualTime,
        expiresAt,
        false);
    PerpetualMarketBundle perpetual = new PerpetualMarketBundle(
        symbol,
        symbol,
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        last.subtract(BigDecimal.ONE),
        last.add(BigDecimal.ONE),
        last,
        last,
        last,
        depth,
        List.of(trade),
        List.of(candle),
        virtualTime,
        expiresAt);
    return new Request(
        new CompositeTick(
            RUN_ID,
            GENERATION,
            tickSequence,
            virtualTime,
            "tick-v" + tickSequence,
            List.of(),
            List.of(perpetual)),
        phase,
        fingerprint);
  }

  private static void assertBusinessCode(Runnable action, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }

  private static void assertMandatory(
      Class<?> type,
      String methodName,
      Class<?>... parameterTypes
  ) throws Exception {
    Transactional transactional = type
        .getMethod(methodName, parameterTypes)
        .getAnnotation(Transactional.class);
    assertThat(transactional)
        .as("%s.%s must be transaction-fenced", type.getSimpleName(), methodName)
        .isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
  }

  private static final class RecordingOperations implements ValidationSystemStepOperations {

    private final List<String> calls = new ArrayList<>();
    private final BooleanSupplier executionAllowed;

    private RecordingOperations() {
      this(() -> true);
    }

    private RecordingOperations(BooleanSupplier executionAllowed) {
      this.executionAllowed = executionAllowed;
    }

    @Override
    public SubStepResult publishTick(Request request) {
      return record("publish-tick");
    }

    @Override
    public SubStepResult updateTrailingExtrema(Request request) {
      return record("update-trailing-extrema");
    }

    @Override
    public SubStepResult matchRestingOrders(Request request) {
      return record("match-resting-orders");
    }

    @Override
    public SubStepResult triggerProtectionOrders(Request request) {
      return record("trigger-protection-orders");
    }

    @Override
    public SubStepResult settlePersistedFunding(Request request) {
      return record("settle-persisted-funding");
    }

    @Override
    public SubStepResult scanLiquidations(Request request) {
      return record("scan-liquidations");
    }

    @Override
    public SubStepResult captureCheckpoint(Request request) {
      return record("capture-checkpoint");
    }

    private SubStepResult record(String name) {
      if (!executionAllowed.getAsBoolean()) {
        throw new AssertionError("System substep ran before the durable run lock");
      }
      calls.add(name);
      return new SubStepResult(name, "correlation-" + name, Map.of());
    }
  }

  private static final class MemoryReceiptStore implements ValidationSystemStepReceiptStore {

    private final Map<Key, Receipt> receipts = new HashMap<>();
    private boolean locked;
    private int lockCalls;

    @Override
    public List<Receipt> lockTick(
        UUID runId,
        long generation,
        long tickSequence
    ) {
      locked = true;
      lockCalls++;
      return receipts.entrySet().stream()
          .filter(entry -> entry.getKey().runId().equals(runId)
              && entry.getKey().generation() == generation
              && entry.getKey().tickSequence() == tickSequence)
          .map(Map.Entry::getValue)
          .toList();
    }

    @Override
    public Receipt save(Receipt receipt) {
      Key key = new Key(
          receipt.runId(), receipt.generation(), receipt.tickSequence(), receipt.phase());
      return receipts.computeIfAbsent(key, ignored -> receipt);
    }

    int size() {
      return receipts.size();
    }

    boolean isLocked() {
      return locked;
    }
  }

  private record Key(UUID runId, long generation, long tickSequence, Phase phase) {
  }
}
