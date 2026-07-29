package com.fxplatform.validation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Command;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.HttpResult;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.LookupResult;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.LookupStatus;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Operation;
import com.fxplatform.validation.service.ValidationRunEngine.PublicAction;
import com.fxplatform.validation.service.ValidationRunEngine.PublicActionType;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import com.fxplatform.validation.service.ValidationRunEventStore;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.Boundary;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.BoundaryOutcome;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.Completion;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.OperationIntent;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.OperationState;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.State;
import com.fxplatform.common.exception.BusinessException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class ValidationRunRecoveryIT {

  private static final UUID RUN_ID = ValidationRunEngineIT.RUN_ID;
  private static final long GENERATION = ValidationRunEngineIT.GENERATION;
  private static final Instant START = ValidationRunEngineIT.START;

  @Test
  void restartReentersTheOriginalTickSlotBeforeReconcilingItsUnfinishedSystemStep() {
    StartRequest request = withTwoTicks(List.of());
    ValidationRunEngineIT.Harness harness = new ValidationRunEngineIT.Harness(request);
    Boundary boundary = boundary(1L);
    AtomicBoolean replayedAtTickTwo = new AtomicBoolean();

    when(harness.runtime.lastCompletedBoundary(ValidationRunEngineIT.CLAIM))
        .thenReturn(java.util.Optional.of(boundary));
    when(harness.runtime.persistIntent(eq(ValidationRunEngineIT.CLAIM), any()))
        .thenAnswer(invocation -> {
          Command command = invocation.getArgument(1);
          if (command.operation() == Operation.SYSTEM_STEP
              && command.tickSequence() == 2L
              && command.idempotencyKey().endsWith("PRE_ACTIONS")) {
            return unfinished(command);
          }
          if (command.tickSequence() == 0L) {
            return completed(command);
          }
          return fresh(command);
        });
    when(harness.loopback.rehydrate(argThat(command ->
        command.operation() == Operation.REGISTER)))
        .thenReturn(registration());
    when(harness.loopback.execute(argThat(command ->
        command.operation() == Operation.SYSTEM_STEP
            && command.tickSequence() == 2L
            && command.idempotencyKey().endsWith("PRE_ACTIONS"))))
        .thenAnswer(invocation -> {
          assertThat(harness.clock.current().orElseThrow().sequence()).isEqualTo(2L);
          assertThat(harness.clock.current().orElseThrow().virtualTime())
              .isEqualTo(START.plusSeconds(2));
          replayedAtTickTwo.set(true);
          return new HttpResult(200, "step-replayed", Map.of("receipt", "stored-or-new"));
        });

    harness.engine.execute(RUN_ID, true);

    assertThat(replayedAtTickTwo).isTrue();
    verify(harness.runtime).restore(ValidationRunEngineIT.CLAIM, boundary);
    verify(harness.loopback, never()).rehydrate(argThat(command ->
        command.operation() == Operation.START_MARKET_PATH));
    verify(harness.loopback).execute(argThat(command ->
        command.operation() == Operation.SYSTEM_STEP
            && command.tickSequence() == 2L
            && command.idempotencyKey().endsWith("PRE_ACTIONS")));
  }

  @Test
  void firstTickUncertainIntentCompletionCanRecoverWithoutACompletedBoundary() {
    ValidationRunEngineIT.Harness harness = new ValidationRunEngineIT.Harness(
        oneTick(List.of()));
    String preActionsKey = RUN_ID + ":1:PRE_ACTIONS";
    UUID preActionsId = UUID.nameUUIDFromBytes(
        preActionsKey.getBytes(StandardCharsets.UTF_8));
    AtomicBoolean injectedUncertainty = new AtomicBoolean();

    when(harness.runtime.persistIntent(eq(ValidationRunEngineIT.CLAIM), any()))
        .thenAnswer(invocation -> {
          Command command = invocation.getArgument(1);
          if (injectedUncertainty.get() && command.tickSequence() == 0L) {
            return completed(command);
          }
          if (injectedUncertainty.get() && preActionsKey.equals(command.idempotencyKey())) {
            return unfinished(command);
          }
          return fresh(command);
        });
    when(harness.loopback.rehydrate(any())).thenAnswer(invocation -> {
      Command command = invocation.getArgument(0);
      return command.operation() == Operation.REGISTER
          ? registration()
          : new HttpResult(
              200,
              "rehydrated-" + command.idempotencyKey(),
              Map.of("operation", command.operation().name()));
    });
    org.mockito.Mockito.doAnswer(invocation -> {
      UUID operationId = invocation.getArgument(1);
      if (preActionsId.equals(operationId)
          && injectedUncertainty.compareAndSet(false, true)) {
        throw new IllegalStateException("first Tick completion outcome is uncertain");
      }
      return null;
    }).when(harness.runtime).completeIntent(any(), any(), any(), any(), any());

    harness.engine.execute(RUN_ID, false);

    assertThat(injectedUncertainty).isTrue();
    assertThat(harness.clock.current().orElseThrow().sequence()).isEqualTo(1L);
    verify(harness.runtime, never()).completeBoundary(any(), any(), any());
    verify(harness.runtime, never()).transition(
        eq(ValidationRunEngineIT.CLAIM),
        eq(State.FAILED),
        any(),
        any());

    harness.engine.execute(RUN_ID, true);

    assertThat(harness.clock.current().orElseThrow().sequence()).isEqualTo(1L);
    verify(harness.runtime).completeIntent(
        eq(ValidationRunEngineIT.CLAIM),
        eq(preActionsId),
        eq(Completion.RECOVERED),
        any(),
        any());
    verify(harness.runtime).completeBoundary(
        eq(ValidationRunEngineIT.CLAIM),
        argThat(boundary -> boundary.tickSequence() == 1L),
        any());
    verify(harness.runtime, never()).restore(any(), any());
    verify(harness.loopback).rehydrate(argThat(command ->
        command.operation() == Operation.START_MARKET_PATH));
    verify(harness.runtime, never()).transition(
        eq(ValidationRunEngineIT.CLAIM),
        eq(State.FAILED),
        any(),
        any());
  }

  @Test
  void completedPublicActionReusesItsFrozenResultWithoutAnotherHttpMutation() {
    PublicAction action = new PublicAction(
        UUID.fromString("00000000-0000-0000-0000-000000000077"),
        2L,
        1L,
        PublicActionType.CANCEL_ORDER,
        "cancel-existing",
        Map.of("clientOrderId", "logical-order-77"));
    StartRequest request = withTwoTicks(List.of(action));
    ValidationRunEngineIT.Harness harness = new ValidationRunEngineIT.Harness(request);
    when(harness.runtime.lastCompletedBoundary(ValidationRunEngineIT.CLAIM))
        .thenReturn(java.util.Optional.of(boundary(1L)));
    when(harness.runtime.persistIntent(eq(ValidationRunEngineIT.CLAIM), any()))
        .thenAnswer(invocation -> {
          Command command = invocation.getArgument(1);
          if (command.tickSequence() == 0L || command.operation() == Operation.PUBLIC_ACTION) {
            return completed(command);
          }
          return fresh(command);
        });
    when(harness.loopback.rehydrate(argThat(command ->
        command.operation() == Operation.REGISTER)))
        .thenReturn(registration());

    harness.engine.execute(RUN_ID, true);

    verify(harness.loopback, never()).execute(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION));
    verify(harness.loopback, never()).lookup(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION));
  }

  @Test
  void unknownOutcomeStopsAtRecoveryBlockedWithoutExecutingLaterSlots() {
    PublicAction action = new PublicAction(
        UUID.fromString("00000000-0000-0000-0000-000000000078"),
        1L,
        1L,
        PublicActionType.CANCEL_ORDER,
        "cancel-uncertain",
        Map.of("clientOrderId", "logical-order-78"));
    StartRequest request = oneTick(List.of(action));
    ValidationRunEngineIT.Harness harness = new ValidationRunEngineIT.Harness(request);
    when(harness.runtime.persistIntent(eq(ValidationRunEngineIT.CLAIM), any()))
        .thenAnswer(invocation -> {
          Command command = invocation.getArgument(1);
          return command.operation() == Operation.PUBLIC_ACTION
              ? unfinished(command)
              : fresh(command);
        });
    when(harness.loopback.lookup(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION)))
        .thenReturn(new LookupResult(LookupStatus.UNKNOWN, null));

    harness.engine.execute(RUN_ID, true);

    verify(harness.loopback, never()).execute(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION));
    verify(harness.runtime).transition(
        eq(ValidationRunEngineIT.CLAIM),
        eq(State.RECOVERY_BLOCKED),
        eq("UNCERTAIN_OPERATION_OUTCOME"),
        any());
    verify(harness.runtime, never()).completeBoundary(any(), any(), any());
  }

  @Test
  void initialTransportUncertaintyStaysUnfinishedAndUsesLookupInsteadOfFreezingFailure() {
    PublicAction action = new PublicAction(
        UUID.fromString("00000000-0000-0000-0000-000000000079"),
        1L,
        1L,
        PublicActionType.CANCEL_ORDER,
        "cancel-response-lost",
        Map.of("clientOrderId", "logical-order-79"));
    ValidationRunEngineIT.Harness harness = new ValidationRunEngineIT.Harness(
        oneTick(List.of(action)));
    when(harness.loopback.execute(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION)))
        .thenThrow(new BusinessException(
            "VALIDATION_LOOPBACK_UNAVAILABLE",
            "Validation loopback request failed"));
    when(harness.loopback.lookup(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION)))
        .thenReturn(new LookupResult(LookupStatus.UNKNOWN, null));

    harness.engine.execute(RUN_ID, false);

    verify(harness.loopback).lookup(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION));
    verify(harness.runtime, never()).failIntent(any(), any(), any(), any(), any(), any());
    verify(harness.runtime).transition(
        eq(ValidationRunEngineIT.CLAIM),
        eq(State.RECOVERY_BLOCKED),
        eq("UNCERTAIN_OPERATION_OUTCOME"),
        any());
  }

  @Test
  void publicActionHttp5xxLooksUpAndRecoversFoundOutcomeWithoutFailingIntent() {
    PublicAction action = new PublicAction(
        UUID.fromString("00000000-0000-0000-0000-000000000080"),
        1L,
        1L,
        PublicActionType.CANCEL_ORDER,
        "cancel-committed-before-500",
        Map.of("clientOrderId", "logical-order-80"));
    ValidationRunEngineIT.Harness harness = new ValidationRunEngineIT.Harness(
        oneTick(List.of(action)));
    HttpResult recovered = new HttpResult(
        200,
        "cancel-found-after-500",
        Map.of("status", "CANCELLED"));
    when(harness.loopback.execute(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION)))
        .thenReturn(new HttpResult(
            500,
            "cancel-response-500",
            Map.of("error", "response failed after commit")));
    when(harness.loopback.lookup(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION)))
        .thenReturn(new LookupResult(LookupStatus.FOUND, recovered));

    harness.engine.execute(RUN_ID, false);

    InOrder recovery = inOrder(harness.loopback, harness.runtime);
    recovery.verify(harness.loopback).execute(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION));
    recovery.verify(harness.loopback).lookup(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION));
    recovery.verify(harness.runtime).completeIntent(
        eq(ValidationRunEngineIT.CLAIM),
        any(UUID.class),
        eq(Completion.RECOVERED),
        eq(recovered),
        any());
    verify(harness.runtime, never()).failIntent(any(), any(), any(), any(), any(), any());
  }

  @Test
  void idempotentPublicActionHttp5xxLooksUpBeforeSafeReplayWithoutFailingIntent() {
    PublicAction action = new PublicAction(
        UUID.fromString("00000000-0000-0000-0000-000000000081"),
        1L,
        1L,
        PublicActionType.CANCEL_ALL,
        "cancel-all-receipt-backed",
        Map.of());
    ValidationRunEngineIT.Harness harness = new ValidationRunEngineIT.Harness(
        oneTick(List.of(action)));
    HttpResult replayed = new HttpResult(
        200,
        "cancel-all-replayed",
        Map.of("requestState", "COMPLETED"));
    when(harness.loopback.execute(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION)))
        .thenReturn(
            new HttpResult(
                500,
                "cancel-all-response-500",
                Map.of("error", "response failed before receipt was observed")),
            replayed);
    when(harness.loopback.lookup(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION)))
        .thenReturn(new LookupResult(LookupStatus.DEFINITELY_ABSENT, null));

    harness.engine.execute(RUN_ID, false);

    verify(harness.loopback).lookup(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION));
    verify(harness.loopback, times(2)).execute(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION));
    verify(harness.runtime).completeIntent(
        eq(ValidationRunEngineIT.CLAIM),
        any(UUID.class),
        eq(Completion.RECOVERED),
        eq(replayed),
        any());
    verify(harness.runtime, never()).failIntent(any(), any(), any(), any(), any(), any());
  }

  @Test
  void failedResultPersistenceUncertaintyLeavesIntentRecoverableWithItsRealEvidence() {
    PublicAction action = new PublicAction(
        UUID.fromString("00000000-0000-0000-0000-000000000082"),
        1L,
        1L,
        PublicActionType.CANCEL_ORDER,
        "cancel-known-rejection",
        Map.of("clientOrderId", "logical-order-82"));
    ValidationRunEngineIT.Harness harness = new ValidationRunEngineIT.Harness(
        oneTick(List.of(action)));
    HttpResult rejection = new HttpResult(
        422,
        "cancel-known-rejection",
        Map.of("code", "ORDER_NOT_FOUND", "message", "sanitized"));
    AtomicBoolean persistenceFailed = new AtomicBoolean();
    when(harness.runtime.persistIntent(eq(ValidationRunEngineIT.CLAIM), any()))
        .thenAnswer(invocation -> {
          Command command = invocation.getArgument(1);
          return command.operation() == Operation.PUBLIC_ACTION
              && persistenceFailed.get()
                  ? unfinished(command)
                  : fresh(command);
        });
    when(harness.loopback.execute(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION))).thenReturn(rejection);
    when(harness.loopback.lookup(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION)))
        .thenReturn(new LookupResult(LookupStatus.FOUND, rejection));
    org.mockito.Mockito.doAnswer(invocation -> {
      if (persistenceFailed.compareAndSet(false, true)) {
        throw new IllegalStateException("failed operation commit outcome is uncertain");
      }
      return null;
    }).when(harness.runtime).failIntent(
        any(), any(), any(), any(), any(), any());

    harness.engine.execute(RUN_ID, false);

    assertThat(persistenceFailed).isTrue();
    verify(harness.runtime, never()).transition(
        eq(ValidationRunEngineIT.CLAIM),
        eq(State.FAILED),
        any(),
        any());

    org.mockito.Mockito.clearInvocations(harness.runtime, harness.loopback);
    harness.engine.execute(RUN_ID, true);

    verify(harness.loopback).lookup(argThat(command ->
        command.operation() == Operation.PUBLIC_ACTION));
    verify(harness.runtime).failIntent(
        eq(ValidationRunEngineIT.CLAIM),
        any(UUID.class),
        eq("VALIDATION_LOOPBACK_FAILED"),
        eq("VALIDATION_LOOPBACK_FAILED"),
        eq(rejection),
        any());
    verify(harness.runtime).transition(
        eq(ValidationRunEngineIT.CLAIM),
        eq(State.FAILED),
        eq("VALIDATION_LOOPBACK_FAILED"),
        any());
  }

  @Test
  void pauseAndCancelAreSettledAtomicallyAtTheFirstCompletedTickBoundary() {
    StartRequest request = withTwoTicks(List.of());
    ValidationRunEngineIT.Harness paused = new ValidationRunEngineIT.Harness(request);
    when(paused.runtime.settleBoundary(
        eq(ValidationRunEngineIT.CLAIM), eq(true), any(), any(), any()))
        .thenReturn(BoundaryOutcome.PAUSED);
    paused.engine.execute(RUN_ID, false);
    assertThat(systemStepCount(paused)).isEqualTo(2L);

    ValidationRunEngineIT.Harness cancelled = new ValidationRunEngineIT.Harness(request);
    when(cancelled.runtime.settleBoundary(
        eq(ValidationRunEngineIT.CLAIM), eq(true), any(), any(), any()))
        .thenReturn(BoundaryOutcome.CANCELLED);
    cancelled.engine.execute(RUN_ID, false);
    assertThat(systemStepCount(cancelled)).isEqualTo(2L);
  }

  @Test
  void eventJournalFreezesIdempotentKeysAndBoundedStrictAfterSequencePages() throws Exception {
    Method eventsAfter = ValidationRunEventStore.class.getMethod(
        "eventsAfter", UUID.class, long.class, int.class);
    assertThat(eventsAfter.getReturnType())
        .isEqualTo(ValidationRunEventStore.EventPage.class);
    assertThat(Arrays.stream(ValidationRunEventStore.EventPage.class.getRecordComponents())
        .map(component -> component.getName())
        .toList())
        .containsExactly("events", "highWatermark", "terminal", "hasMore");

    String store = Files.readString(Path.of(
        "src/main/java/com/fxplatform/validation/service/ValidationRunEventStore.java"));
    String migration = Files.readString(Path.of(
        "src/main/resources/db/migration/V66__validation_runtime.sql"));
    assertThat(store)
        .contains("MAX_PAGE_SIZE")
        .contains("afterSequence")
        .contains("durableKey")
        .contains("fingerprint")
        .contains("VALIDATION_EVENT_CONFLICT");
    assertThat(migration)
        .contains("validation_runtime.run_events")
        .contains("UNIQUE (run_id, sequence)")
        .contains("UNIQUE (run_id, durable_event_key)");
  }

  private static long systemStepCount(ValidationRunEngineIT.Harness harness) {
    ArgumentCaptor<Command> commands = ArgumentCaptor.forClass(Command.class);
    verify(harness.loopback, atLeastOnce()).execute(commands.capture());
    return commands.getAllValues().stream()
        .filter(command -> command.operation() == Operation.SYSTEM_STEP)
        .count();
  }

  private static StartRequest oneTick(List<PublicAction> actions) {
    StartRequest base = ValidationRunEngineIT.request();
    return new StartRequest(
        base.runId(),
        base.generation(),
        base.requestFingerprint(),
        base.seed(),
        base.virtualStart(),
        base.executionPolicy(),
        base.initialBalances(),
        base.accountSettings(),
        List.of(ValidationRunEngineIT.tick(1L)),
        actions,
        new BigDecimal("1000000"));
  }

  private static StartRequest withTwoTicks(List<PublicAction> actions) {
    StartRequest base = ValidationRunEngineIT.request();
    return new StartRequest(
        base.runId(),
        base.generation(),
        base.requestFingerprint(),
        base.seed(),
        base.virtualStart(),
        base.executionPolicy(),
        base.initialBalances(),
        base.accountSettings(),
        List.of(ValidationRunEngineIT.tick(1L), ValidationRunEngineIT.tick(2L)),
        actions,
        new BigDecimal("1000000"));
  }

  private static Boundary boundary(long sequence) {
    return new Boundary(
        RUN_ID,
        GENERATION,
        sequence,
        START.plusSeconds(sequence),
        "tick-" + sequence,
        "policy-v1");
  }

  private static OperationIntent fresh(Command command) {
    return intent(command, OperationState.NEW, null);
  }

  private static OperationIntent unfinished(Command command) {
    return intent(command, OperationState.UNFINISHED, null);
  }

  private static OperationIntent completed(Command command) {
    HttpResult result = command.operation() == Operation.REGISTER
        ? registration()
        : new HttpResult(200, "stored-" + command.idempotencyKey(), Map.of("stored", true));
    return intent(command, OperationState.COMPLETED, result);
  }

  private static OperationIntent intent(
      Command command,
      OperationState state,
      HttpResult result
  ) {
    return new OperationIntent(
        UUID.nameUUIDFromBytes(command.idempotencyKey().getBytes(StandardCharsets.UTF_8)),
        command,
        START,
        state,
        result);
  }

  private static HttpResult registration() {
    return new HttpResult(
        200,
        "registration",
        Map.of(
            "userId", "00000000-0000-0000-0000-000000000501",
            "accountId", "00000000-0000-0000-0000-000000000502"));
  }
}
