package com.fxplatform.tradinglab.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.client.ThrowableInfo;
import com.fxplatform.tradinglab.client.ValidationBackendClient;
import com.fxplatform.tradinglab.client.ValidationBackendExchange;
import com.fxplatform.tradinglab.client.ValidationControlReceipt;
import com.fxplatform.tradinglab.client.ValidationEventPage;
import com.fxplatform.tradinglab.client.ValidationHttpResult;
import com.fxplatform.tradinglab.client.ValidationResetReceipt;
import com.fxplatform.tradinglab.client.ValidationResetRequest;
import com.fxplatform.tradinglab.client.ValidationRunAccepted;
import com.fxplatform.tradinglab.client.ValidationRunEvent;
import com.fxplatform.tradinglab.client.ValidationRunStartRequest;
import com.fxplatform.tradinglab.client.ValidationRunState;
import com.fxplatform.tradinglab.client.ValidationRunStateObservation;
import com.fxplatform.tradinglab.client.ValidationRunStateSnapshot;
import com.fxplatform.tradinglab.evidence.TradingLabCoordinatorEvidence;
import com.fxplatform.tradinglab.evidence.TradingLabCoordinatorEvidenceStore;
import com.fxplatform.tradinglab.queue.TradingLabRunClaim;
import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabFencedReportWriter;
import com.fxplatform.tradinglab.report.TradingLabReportSection;
import com.fxplatform.tradinglab.report.TradingLabReportWriteFence;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import com.fxplatform.tradinglab.repository.TradingLabWorkerRunSnapshot;
import com.fxplatform.tradinglab.state.RunTransitionCommand;
import com.fxplatform.tradinglab.state.TradingLabRunState;
import com.fxplatform.tradinglab.state.TradingLabRunTransitionService;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TradingLabCoordinatorRecoveryTest {

  private static final Instant NOW = Instant.parse("2026-07-23T17:00:00Z");

  @Test
  void resettingRecoveryObservesAcceptedIdentityWithoutResettingOrRestarting() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.RUNNING, false, false, 0L, false);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "state-1"),
        exchange(fixture.observation(remote), "state-2"));
    when(fixture.client.eventsAfter(fixture.runId, 0L)).thenReturn(exchange(
        new ValidationEventPage(List.of(), 0L, false, false), "events-1"));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verify(fixture.client, never()).startRun(any(ValidationRunStartRequest.class));
    verify(fixture.client).eventsAfter(fixture.runId, 0L);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
  }

  @Test
  void forwardsPendingPauseBeforeReplayingMissingEvidence() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", true, false);
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.RUNNING, false, false, 0L, false);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "state-before-priority-pause"));
    when(fixture.client.pause(fixture.runId)).thenReturn(exchange(
        new ValidationControlReceipt(
            fixture.runId, "PAUSE_REQUESTED", ValidationRunState.RUNNING),
        "priority-pause"));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.client).pause(fixture.runId);
    verify(fixture.projector, never()).replayMissing(fixture.runId, fixture.owner);
    verify(fixture.client, never()).eventsAfter(any(), anyLong());
  }

  @Test
  void durablePauseAcknowledgementPreventsDuplicateControlPost() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", true, false);
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.RUNNING, true, false, 0L, false);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "state-pause-ack"));
    when(fixture.client.eventsAfter(fixture.runId, 0L)).thenReturn(exchange(
        new ValidationEventPage(List.of(), 0L, false, false), "events-pause-ack"));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.client, never()).pause(fixture.runId);
    verify(fixture.projector, never()).replayMissing(fixture.runId, fixture.owner);
    verify(fixture.client, never()).eventsAfter(any(), anyLong());
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
  }

  @Test
  void durableCancelAcknowledgementPreventsDuplicateControlPostOrEventDrain() {
    Fixture fixture = new Fixture(
        TradingLabRunState.CANCELLING, "WRITING", false, true);
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.CANCELLING, false, true, 0L, false);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "state-cancel-ack"));
    when(fixture.client.eventsAfter(fixture.runId, 0L)).thenReturn(exchange(
        new ValidationEventPage(List.of(), 0L, false, false), "events-cancel-ack"));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.client, never()).cancel(fixture.runId);
    verify(fixture.projector, never()).replayMissing(fixture.runId, fixture.owner);
    verify(fixture.client, never()).eventsAfter(any(), anyLong());
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CANCELLING);
  }

  @Test
  void yieldsEventMirroringSoTheNextTurnCanForwardAConcurrentPause() {
    Fixture fixture = new Fixture(
        TradingLabRunState.RUNNING,
        "WRITING",
        false,
        false,
        40L);
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.RUNNING, false, false, 2L, false);
    ValidationRunEvent first = new ValidationRunEvent(
        fixture.runId,
        1L,
        "tick:1",
        "tick-1-fingerprint",
        "TICK_COMPLETED",
        NOW.plusSeconds(1),
        "tick-1",
        Map.of("sequence", 1L));
    AtomicLong durableCursor = new AtomicLong();
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "state-before-concurrent-pause"),
        exchange(fixture.observation(remote), "state-before-forwarding-concurrent-pause"));
    when(fixture.evidence.lastValidationSequence(fixture.runId, fixture.owner))
        .thenAnswer(ignored -> durableCursor.get());
    when(fixture.evidence.appendValidationEvent(
        eq(fixture.runId), eq(fixture.owner), eq(first)))
        .thenAnswer(ignored -> {
          durableCursor.set(first.sequence());
          fixture.pauseRequested.set(true);
          fixture.version.incrementAndGet();
          return evidence(fixture.runId, first.sequence(), first.toSafeMap());
        });
    when(fixture.client.eventsAfter(fixture.runId, 0L)).thenReturn(exchange(
        new ValidationEventPage(List.of(first), 2L, false, true),
        "events-before-concurrent-pause"));
    when(fixture.client.eventsAfter(fixture.runId, 1L)).thenReturn(exchange(
        new ValidationEventPage(List.of(), 2L, false, false),
        "events-after-starved-pause"));
    when(fixture.client.pause(fixture.runId)).thenReturn(exchange(
        new ValidationControlReceipt(
            fixture.runId, "PAUSE_REQUESTED", ValidationRunState.RUNNING),
        "pause-on-next-turn"));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.evidence).appendValidationEvent(
        fixture.runId, fixture.owner, first);
    verify(fixture.projector).project(
        eq(fixture.runId), eq(fixture.owner), any(TradingLabCoordinatorEvidence.class));
    verify(fixture.client, never()).eventsAfter(fixture.runId, 1L);
    verify(fixture.client, never()).pause(fixture.runId);

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.client).pause(fixture.runId);
    InOrder controlOrder = org.mockito.Mockito.inOrder(fixture.client);
    controlOrder.verify(fixture.client).state(fixture.runId);
    controlOrder.verify(fixture.client).eventsAfter(fixture.runId, 0L);
    controlOrder.verify(fixture.client).state(fixture.runId);
    controlOrder.verify(fixture.client).pause(fixture.runId);
    verify(fixture.client, never()).eventsAfter(fixture.runId, 1L);
  }

  @Test
  void yieldsAfterMirroringCheckpointSoResumedProgressIsReobserved() {
    Fixture fixture = new Fixture(
        TradingLabRunState.RUNNING,
        "WRITING",
        false,
        false,
        40L);
    ValidationRunStateSnapshot beforeCheckpoint = fixture.remote(
        ValidationRunState.RUNNING, false, false, 2L, false);
    ValidationRunStateSnapshot afterCheckpoint = fixture.remote(
        ValidationRunState.RUNNING, false, false, 3L, false);
    ValidationRunEvent firstCheckpoint = new ValidationRunEvent(
        fixture.runId,
        1L,
        "tick:1:checkpoint",
        "tick-1-checkpoint-fingerprint",
        "CHECKPOINT",
        NOW.plusSeconds(1),
        "tick-1-checkpoint",
        Map.of("tickSequence", 1L, "state", Map.of()));
    ValidationRunEvent secondCheckpoint = new ValidationRunEvent(
        fixture.runId,
        2L,
        "tick:2:checkpoint",
        "tick-2-checkpoint-fingerprint",
        "CHECKPOINT",
        NOW.plusSeconds(2),
        "tick-2-checkpoint",
        Map.of("tickSequence", 2L, "state", Map.of()));
    AtomicLong durableCursor = new AtomicLong();
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(beforeCheckpoint), "state-before-checkpoint-yield"),
        exchange(fixture.observation(afterCheckpoint), "state-after-checkpoint-yield"));
    when(fixture.evidence.lastValidationSequence(fixture.runId, fixture.owner))
        .thenAnswer(ignored -> durableCursor.get());
    when(fixture.evidence.appendValidationEvent(
        eq(fixture.runId), eq(fixture.owner), any(ValidationRunEvent.class)))
        .thenAnswer(invocation -> {
          ValidationRunEvent event = invocation.getArgument(2);
          durableCursor.set(event.sequence());
          return evidence(fixture.runId, event.sequence(), event.toSafeMap());
        });
    when(fixture.client.eventsAfter(fixture.runId, 0L)).thenReturn(exchange(
        new ValidationEventPage(List.of(firstCheckpoint), 2L, false, true),
        "checkpoint-before-yield"));
    when(fixture.client.eventsAfter(fixture.runId, 1L)).thenReturn(exchange(
        new ValidationEventPage(List.of(secondCheckpoint), 3L, false, true),
        "checkpoint-after-yield"));
    when(fixture.client.eventsAfter(fixture.runId, 2L)).thenReturn(exchange(
        new ValidationEventPage(List.of(), 3L, false, false),
        "events-after-starved-progress"));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.evidence).appendValidationEvent(
        fixture.runId, fixture.owner, firstCheckpoint);
    verify(fixture.client, never()).eventsAfter(fixture.runId, 1L);

    fixture.coordinator.coordinate(fixture.claim());

    InOrder progressOrder = org.mockito.Mockito.inOrder(fixture.client, fixture.runs);
    progressOrder.verify(fixture.client).state(fixture.runId);
    progressOrder.verify(fixture.runs).advanceFencedProcessedTicks(
        fixture.runId, fixture.owner, 2L);
    progressOrder.verify(fixture.client).eventsAfter(fixture.runId, 0L);
    progressOrder.verify(fixture.client).state(fixture.runId);
    progressOrder.verify(fixture.runs).advanceFencedProcessedTicks(
        fixture.runId, fixture.owner, 3L);
    progressOrder.verify(fixture.client).eventsAfter(fixture.runId, 1L);
  }

  @Test
  void projectsTheCompletedTickSequenceWithoutAnOffByOneIncrement() {
    Fixture fixture = new Fixture(
        TradingLabRunState.RUNNING,
        "WRITING",
        false,
        false,
        12L);
    ValidationRunStateSnapshot remote = new ValidationRunStateSnapshot(
        fixture.runId,
        1L,
        fixture.request.requestFingerprint(),
        ValidationRunState.RUNNING,
        false,
        false,
        7L,
        NOW.plusSeconds(7),
        0L,
        null,
        NOW.plusSeconds(7),
        false);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "state-progress"));
    when(fixture.client.eventsAfter(fixture.runId, 0L)).thenReturn(exchange(
        new ValidationEventPage(List.of(), 0L, false, false), "events-progress"));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.runs).advanceFencedProcessedTicks(
        fixture.runId,
        fixture.owner,
        7L);
  }

  @Test
  void projectsTerminalProgressBeforeCleaningWhenTheDrainedPageHasNoEvents() {
    Fixture fixture = new Fixture(
        TradingLabRunState.RUNNING,
        "WRITING",
        false,
        false,
        5L);
    ValidationRunStateSnapshot running = new ValidationRunStateSnapshot(
        fixture.runId,
        1L,
        fixture.request.requestFingerprint(),
        ValidationRunState.RUNNING,
        false,
        false,
        0L,
        NOW,
        0L,
        null,
        NOW,
        false);
    ValidationRunStateSnapshot terminal = new ValidationRunStateSnapshot(
        fixture.runId,
        1L,
        fixture.request.requestFingerprint(),
        ValidationRunState.COMPLETED,
        false,
        false,
        5L,
        NOW.plusSeconds(5),
        0L,
        null,
        NOW.plusSeconds(5),
        true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(running), "state-running-before-empty-terminal-page"),
        exchange(fixture.observation(terminal), "state-terminal-after-empty-terminal-page"));
    when(fixture.client.eventsAfter(fixture.runId, 0L)).thenReturn(exchange(
        new ValidationEventPage(List.of(), 0L, true, false),
        "events-empty-terminal-page"));
    when(fixture.client.reset(any(ValidationResetRequest.class)))
        .thenReturn(exchange(resetReceipt(2L), "final-reset-after-empty-terminal-page"));

    fixture.coordinator.coordinate(fixture.claim());

    InOrder terminalOrder = org.mockito.Mockito.inOrder(
        fixture.runs, fixture.transitions);
    terminalOrder.verify(fixture.runs).advanceFencedProcessedTicks(
        fixture.runId,
        fixture.owner,
        5L);
    terminalOrder.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.RUNNING
                && command.target() == TradingLabRunState.CLEANING),
        eq(fixture.owner));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.COMPLETED);
  }

  @Test
  void rejectsAValidationProgressSequenceBeyondTheFrozenTickTotal() {
    Fixture fixture = new Fixture(
        TradingLabRunState.RUNNING,
        "WRITING",
        false,
        false,
        12L);
    ValidationRunStateSnapshot remote = progressSnapshot(fixture, 13L);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "state-invalid-progress"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_PROGRESS_INVALID"));

    verify(fixture.runs, never()).advanceFencedProcessedTicks(any(), anyString(), anyLong());
    verify(fixture.client, never()).eventsAfter(any(), anyLong());
  }

  @Test
  void failsClosedWhenAStillStaleSnapshotCannotAdvanceThroughTheFence() {
    Fixture fixture = new Fixture(
        TradingLabRunState.RUNNING,
        "WRITING",
        false,
        false,
        12L);
    ValidationRunStateSnapshot remote = progressSnapshot(fixture, 7L);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "state-lost-progress-fence"));
    when(fixture.runs.advanceFencedProcessedTicks(
        fixture.runId,
        fixture.owner,
        7L)).thenReturn(0);

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_PROGRESS_FENCE_LOST"));

    verify(fixture.client, never()).eventsAfter(any(), anyLong());
  }

  @Test
  void closedReportInCleaningTransitionsTerminalWithoutRepeatingCleanup() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "COMPLETED", false, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(fixture.runId)));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.COMPLETED);
    verifyNoInteractions(fixture.client, fixture.writer, fixture.projector);
  }

  @Test
  void quarantinedOpenReportResetsBeforeFailingCleaningRun() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.COMPLETED, false, false, 10L, true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "quarantine-cleaning-state"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        exchange(resetReceipt(2L), "quarantine-cleaning-reset"));

    fixture.coordinator.coordinate(fixture.claim());

    InOrder recovery = org.mockito.Mockito.inOrder(
        fixture.evidence, fixture.client, fixture.writer, fixture.transitions);
    recovery.verify(fixture.evidence).findCleanupIntentForRecovery(
        fixture.runId, fixture.owner, "cleanup:COMPLETED");
    recovery.verify(fixture.client).state(fixture.runId);
    recovery.verify(fixture.client).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 1L));
    recovery.verify(fixture.writer).fail(
        any(TradingLabReportWriteFence.class),
        eq("TRADING_LAB_REPORT_UNSAFE_TRACE"),
        eq("Trading Lab report contains unsafe trace evidence"));
    recovery.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CLEANING
                && command.target() == TradingLabRunState.FAILED
                && "task10:cleaning-failed-unsafe-report".equals(
                    command.idempotencyKey())
                && "TRADING_LAB_REPORT_UNSAFE_TRACE".equals(command.reason())),
        eq(fixture.owner));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verify(fixture.writer, never()).appendSingleton(any(), any(), any());
    verify(fixture.writer, never()).flush(any());
    verify(fixture.writer, never()).complete(any());
    verify(fixture.writer, never()).cancel(any(), anyString());
    verifyNoInteractions(fixture.projector, fixture.exchanges);
  }

  @Test
  void quarantinedCancelledReportResetsWithoutTryingToRefinalizeTheReport() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "CANCELLED", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:CANCELLED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.CANCELLED)));
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(2L), "quarantine-cancelled-state"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        exchange(resetReceipt(2L), "quarantine-cancelled-reset-replay"));

    fixture.coordinator.coordinate(fixture.claim());

    verifyNoInteractions(fixture.writer, fixture.projector, fixture.exchanges);
    verify(fixture.evidence).findCleanupIntentForRecovery(
        fixture.runId, fixture.owner, "cleanup:CANCELLED");
    verify(fixture.client).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 1L));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
  }

  @Test
  void quarantineFinalizeFailureKeepsRunCleaningAndLeaseOwned() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    BusinessException fenceLost = new BusinessException(
        "TRADING_LAB_REPORT_FENCE_LOST",
        "Trading Lab report write fence is no longer valid");
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.COMPLETED, false, false, 10L, true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "quarantine-cleaning-before-fence-loss"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        exchange(resetReceipt(2L), "quarantine-cleaning-reset-before-fence-loss"));
    org.mockito.Mockito.doThrow(fenceLost).when(fixture.writer).fail(
        any(TradingLabReportWriteFence.class),
        eq("TRADING_LAB_REPORT_UNSAFE_TRACE"),
        eq("Trading Lab report contains unsafe trace evidence"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isSameAs(fenceLost);

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);
    verify(fixture.client).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 1L));
    verifyNoInteractions(fixture.transitions, fixture.projector, fixture.exchanges);
  }

  @Test
  void quarantinedActiveStatesResetBeforeFailingReportAndRun() {
    for (TradingLabRunState initial : List.of(
        TradingLabRunState.RESETTING,
        TradingLabRunState.RUNNING,
        TradingLabRunState.PAUSED,
        TradingLabRunState.CANCELLING)) {
      Fixture fixture = new Fixture(initial, "WRITING", false, false);
      fixture.reportQuarantined.set(true);
      ValidationRunStateSnapshot remote = fixture.remote(
          ValidationRunState.COMPLETED, false, false, 10L, true);
      when(fixture.client.state(fixture.runId)).thenReturn(
          exchange(fixture.observation(remote), "quarantine-terminal-state"));
      when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
          exchange(resetReceipt(2L), "quarantine-final-reset"));

      fixture.coordinator.coordinate(fixture.claim());

      UUID expectedOperationId = UUID.nameUUIDFromBytes(
          (fixture.runId + ":trading-lab:final-reset")
              .getBytes(StandardCharsets.UTF_8));
      InOrder recovery = org.mockito.Mockito.inOrder(
          fixture.client, fixture.transitions, fixture.writer);
      recovery.verify(fixture.client).state(fixture.runId);
      recovery.verify(fixture.client).reset(argThat(request ->
          request.runId().equals(fixture.runId)
              && request.operationId().equals(expectedOperationId)
              && request.mode() == ValidationResetRequest.Mode.FINAL
              && request.expectedGeneration() == 1L));
      recovery.verify(fixture.transitions).transitionFenced(
          argThat(command ->
              command.expected() == initial
                  && command.target() == TradingLabRunState.CLEANING
                  && "TRADING_LAB_REPORT_UNSAFE_TRACE".equals(command.reason())),
          eq(fixture.owner));
      recovery.verify(fixture.writer).fail(
          any(TradingLabReportWriteFence.class),
          eq("TRADING_LAB_REPORT_UNSAFE_TRACE"),
          eq("Trading Lab report contains unsafe trace evidence"));
      recovery.verify(fixture.transitions).transitionFenced(
          argThat(command ->
              command.expected() == TradingLabRunState.CLEANING
                  && command.target() == TradingLabRunState.FAILED
                  && "TRADING_LAB_REPORT_UNSAFE_TRACE".equals(command.reason())),
          eq(fixture.owner));
      assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
      verify(fixture.writer, never()).appendSingleton(any(), any(), any());
      verify(fixture.writer, never()).flush(any());
      verifyNoInteractions(
          fixture.projector, fixture.exchanges, fixture.initializer);
    }
  }

  @Test
  void quarantinedResettingFalseCleanupIntentNeverTouchesValidation() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", TradingLabRunState.FAILED.name());
    cleanup.put("generation", 0L);
    cleanup.put("validationSequence", 0L);
    cleanup.put("failureCode", "TRADING_LAB_VALIDATION_FAILED");
    cleanup.put("finalResetRequired", false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:FAILED"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            2L,
            Map.of("evidence", cleanup))));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verifyNoInteractions(fixture.client, fixture.projector, fixture.exchanges);
    verify(fixture.writer).fail(
        any(TradingLabReportWriteFence.class),
        eq("TRADING_LAB_REPORT_UNSAFE_TRACE"),
        eq("Trading Lab report contains unsafe trace evidence"));
    InOrder transitions = org.mockito.Mockito.inOrder(fixture.transitions);
    transitions.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.RESETTING
                && command.target() == TradingLabRunState.CLEANING
                && command.idempotencyKey().contains("no-validation-reset")),
        eq(fixture.owner));
    transitions.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CLEANING
                && command.target() == TradingLabRunState.FAILED),
        eq(fixture.owner));
  }

  @Test
  void quarantinedPristineResettingRunFinishesWithoutAValidationReset() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.pristineObservation(), "quarantine-pristine-state"));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verify(fixture.client).state(fixture.runId);
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verify(fixture.client, never()).cancel(fixture.runId);
    verify(fixture.writer).fail(
        any(TradingLabReportWriteFence.class),
        eq("TRADING_LAB_REPORT_UNSAFE_TRACE"),
        eq("Trading Lab report contains unsafe trace evidence"));
    InOrder transitions = org.mockito.Mockito.inOrder(fixture.transitions);
    transitions.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.RESETTING
                && command.target() == TradingLabRunState.CLEANING
                && command.idempotencyKey().contains("no-validation-reset")),
        eq(fixture.owner));
    transitions.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CLEANING
                && command.target() == TradingLabRunState.FAILED),
        eq(fixture.owner));
  }

  @Test
  void quarantinedPristineCleaningRunFinishesAfterThePriorTransitionCrash() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.pristineObservation(), "quarantine-pristine-cleaning-state"));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verify(fixture.client).state(fixture.runId);
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CLEANING
                && command.target() == TradingLabRunState.FAILED
                && "TRADING_LAB_REPORT_UNSAFE_TRACE".equals(command.reason())),
        eq(fixture.owner));
    verify(fixture.transitions, times(1)).transitionFenced(any(), eq(fixture.owner));
  }

  @Test
  void quarantinedFalseCleanupIntentCannotEscapeRunningOrPaused() {
    for (TradingLabRunState initial : List.of(
        TradingLabRunState.RUNNING,
        TradingLabRunState.PAUSED)) {
      Fixture fixture = new Fixture(initial, "WRITING", false, false);
      fixture.reportQuarantined.set(true);
      when(fixture.evidence.findIntent(
          fixture.runId, fixture.owner, "cleanup:CANCELLED"))
          .thenReturn(Optional.of(cleanupIntent(
              fixture.runId,
              TradingLabRunState.CANCELLED,
              0L,
              0L,
              null,
              false)));

      assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
          .isInstanceOfSatisfying(BusinessException.class, exception ->
              assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

      assertThat(fixture.state.get()).isEqualTo(initial);
      verifyNoInteractions(fixture.client, fixture.writer, fixture.transitions);
    }
  }

  @Test
  void quarantinedActiveCleanupIntentGenerationMismatchNeverResetsOrMutates() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", TradingLabRunState.COMPLETED.name());
    cleanup.put("generation", 999L);
    cleanup.put("validationSequence", 10L);
    cleanup.put("failureCode", null);
    cleanup.put("finalResetRequired", true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            2L,
            Map.of("evidence", cleanup))));
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(2L), "quarantine-active-wrong-generation"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_STATE_UNCERTAIN"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verifyNoInteractions(
        fixture.writer,
        fixture.projector,
        fixture.exchanges,
        fixture.transitions);
  }

  @Test
  void quarantinedBoundCleanupTargetMismatchNeverResetsOrMutates() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:CANCELLED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.CANCELLED)));
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.COMPLETED, false, false, 10L, true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "quarantine-target-mismatch"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_STATE_UNCERTAIN"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verifyNoInteractions(fixture.writer, fixture.projector, fixture.exchanges, fixture.transitions);
  }

  @Test
  void quarantinedBoundCleanupHighWatermarkMismatchNeverResetsOrMutates() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.COMPLETED, false, false, 11L, true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "quarantine-high-watermark-mismatch"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_STATE_UNCERTAIN"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verifyNoInteractions(fixture.writer, fixture.projector, fixture.exchanges, fixture.transitions);
  }

  @Test
  void quarantinedBoundCleanupFailureCodeMismatchNeverResetsOrMutates() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:FAILED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId,
            TradingLabRunState.FAILED,
            1L,
            10L,
            "VALIDATION_EXECUTION_FAILED",
            true)));
    ValidationRunStateSnapshot remote = fixture.remote(
        1L,
        ValidationRunState.FAILED,
        false,
        false,
        10L,
        "VALIDATION_OTHER_FAILED",
        true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "quarantine-failure-code-mismatch"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_STATE_UNCERTAIN"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verifyNoInteractions(fixture.writer, fixture.projector, fixture.exchanges, fixture.transitions);
  }

  @Test
  void quarantinedActiveMultipleCleanupIntentsFailBeforeValidationOrMutation() {
    Fixture fixture = new Fixture(TradingLabRunState.CANCELLING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:CANCELLED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.CANCELLED)));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CANCELLING);
    verifyNoInteractions(
        fixture.client,
        fixture.writer,
        fixture.projector,
        fixture.exchanges,
        fixture.transitions);
  }

  @Test
  void quarantinedQueuedReportFailsWithoutTouchingValidation() {
    for (String reportStatus : List.of("WRITING", "FAILED")) {
      Fixture fixture = new Fixture(TradingLabRunState.QUEUED, reportStatus, false, false);
      fixture.reportQuarantined.set(true);

      fixture.coordinator.coordinate(fixture.claim());

      if ("WRITING".equals(reportStatus)) {
        verify(fixture.writer).fail(
            any(TradingLabReportWriteFence.class),
            eq("TRADING_LAB_REPORT_UNSAFE_TRACE"),
            eq("Trading Lab report contains unsafe trace evidence"));
      } else {
        verifyNoInteractions(fixture.writer);
      }
      verify(fixture.transitions).transitionFenced(
          argThat(command ->
              command.expected() == TradingLabRunState.QUEUED
                  && command.target() == TradingLabRunState.FAILED
                  && "TRADING_LAB_REPORT_UNSAFE_TRACE".equals(command.reason())),
          eq(fixture.owner));
      assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
      verifyNoInteractions(
          fixture.client,
          fixture.projector,
          fixture.exchanges,
          fixture.initializer);
    }
  }

  @Test
  void quarantinedQueuedResetRequiredIntentFailsBeforeReportOrRunMutation() {
    Fixture fixture = new Fixture(TradingLabRunState.QUEUED, "WRITING", false, true);
    fixture.reportQuarantined.set(true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.QUEUED);
    verifyNoInteractions(
        fixture.client,
        fixture.writer,
        fixture.projector,
        fixture.exchanges,
        fixture.transitions,
        fixture.initializer);
  }

  @Test
  void quarantinedResettingAcceptedRunCancelsRawBeforeAnyReportBackedRecovery() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.RUNNING, false, false, 4L, false);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "quarantine-resetting-running-state"));
    when(fixture.client.cancel(fixture.runId)).thenReturn(exchange(
        new ValidationControlReceipt(
            fixture.runId, "CANCEL_REQUESTED", ValidationRunState.CANCELLING),
        "quarantine-resetting-cancel"));

    fixture.coordinator.coordinate(fixture.claim());

    InOrder recovery = org.mockito.Mockito.inOrder(fixture.client, fixture.transitions);
    recovery.verify(fixture.client).state(fixture.runId);
    recovery.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.RESETTING
                && command.target() == TradingLabRunState.CANCELLING
                && "TRADING_LAB_REPORT_UNSAFE_TRACE".equals(command.reason())),
        eq(fixture.owner));
    recovery.verify(fixture.client).cancel(fixture.runId);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CANCELLING);
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verifyNoInteractions(
        fixture.projector, fixture.exchanges, fixture.initializer, fixture.writer);
  }

  @Test
  void quarantinedResettingAfterFirstInitialResetUsesTheCurrentGeneration() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(1L), "quarantine-after-first-initial-reset"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        exchange(resetReceipt(2L), "quarantine-final-reset-after-first-initial"));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.client).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 1L));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verifyNoInteractions(fixture.projector, fixture.exchanges, fixture.initializer);
  }

  @Test
  void quarantinedResettingAfterLaterInitialResetProbesReplayBeforeCurrentGeneration() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    UUID expectedOperationId = UUID.nameUUIDFromBytes(
        (fixture.runId + ":trading-lab:final-reset")
            .getBytes(StandardCharsets.UTF_8));
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(7L), "quarantine-after-later-initial-reset"));
    when(fixture.client.reset(argThat(request ->
        request != null && request.expectedGeneration() == 6L)))
        .thenReturn(failedExchange(
            "VALIDATION_RESET_STALE_GENERATION", 409, "REMOTE", false));
    when(fixture.client.reset(argThat(request ->
        request != null && request.expectedGeneration() == 7L)))
        .thenReturn(exchange(resetReceipt(8L), "quarantine-current-generation-final-reset"));

    fixture.coordinator.coordinate(fixture.claim());

    InOrder resetOrder = org.mockito.Mockito.inOrder(fixture.client);
    resetOrder.verify(fixture.client).state(fixture.runId);
    resetOrder.verify(fixture.client).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.operationId().equals(expectedOperationId)
            && request.expectedGeneration() == 6L));
    resetOrder.verify(fixture.client).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.operationId().equals(expectedOperationId)
            && request.expectedGeneration() == 7L));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verifyNoInteractions(fixture.projector, fixture.exchanges, fixture.initializer);
  }

  @Test
  void quarantinedFinalRunMismatchProvesNoAcceptedRunWithoutRetryingForever() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(1L), "quarantine-final-run-absent"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        failedExchange(
            "VALIDATION_RESET_FINAL_RUN_MISMATCH", 409, "REMOTE", false));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verify(fixture.client, times(1)).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 1L));
    verify(fixture.writer).fail(
        any(TradingLabReportWriteFence.class),
        eq("TRADING_LAB_REPORT_UNSAFE_TRACE"),
        eq("Trading Lab report contains unsafe trace evidence"));
  }

  @Test
  void quarantinedLaterGenerationFinalRunMismatchStopsAfterTheBoundedProbe() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(7L), "quarantine-later-final-run-absent"));
    when(fixture.client.reset(argThat(request ->
        request != null && request.expectedGeneration() == 6L)))
        .thenReturn(failedExchange(
            "VALIDATION_RESET_STALE_GENERATION", 409, "REMOTE", false));
    when(fixture.client.reset(argThat(request ->
        request != null && request.expectedGeneration() == 7L)))
        .thenReturn(failedExchange(
            "VALIDATION_RESET_FINAL_RUN_MISMATCH", 409, "REMOTE", false));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    InOrder resetOrder = org.mockito.Mockito.inOrder(fixture.client);
    resetOrder.verify(fixture.client).state(fixture.runId);
    resetOrder.verify(fixture.client).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 6L));
    resetOrder.verify(fixture.client).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 7L));
    verify(fixture.client, times(2)).reset(any(ValidationResetRequest.class));
  }

  @Test
  void quarantinedTerminalRunAtMaximumGenerationNeverPostsAnOverflowingReset() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    ValidationRunStateSnapshot remote = fixture.remote(
        Long.MAX_VALUE,
        ValidationRunState.COMPLETED,
        false,
        false,
        10L,
        true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "quarantine-terminal-max-generation"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_STATE_UNCERTAIN"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verifyNoInteractions(fixture.writer, fixture.transitions);
  }

  @Test
  void quarantinedMaximumReadyGenerationNeverFallsBackPastTheLongRange() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(Long.MAX_VALUE), "quarantine-ready-max-generation"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        failedExchange(
            "VALIDATION_RESET_STALE_GENERATION", 409, "REMOTE", false));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_STATE_UNCERTAIN"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RESETTING);
    verify(fixture.client, times(1)).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == Long.MAX_VALUE - 1L));
    verify(fixture.client, never()).reset(argThat(request ->
        request.expectedGeneration() == Long.MAX_VALUE));
    verifyNoInteractions(fixture.writer, fixture.transitions);
  }

  @Test
  void quarantinedMaximumReadyGenerationAcceptsTheExactReplayReceipt() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(
            fixture.absentAtGeneration(Long.MAX_VALUE),
            "quarantine-ready-max-generation-replayed"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        exchange(resetReceipt(Long.MAX_VALUE), "quarantine-max-generation-receipt"));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verify(fixture.client, times(1)).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == Long.MAX_VALUE - 1L));
    verify(fixture.client, never()).reset(argThat(request ->
        request.expectedGeneration() == Long.MAX_VALUE));
  }

  @Test
  void quarantinedLaterInitialResetFallbackCoversCancellingAndCleaning() {
    for (TradingLabRunState initial : List.of(
        TradingLabRunState.CANCELLING,
        TradingLabRunState.CLEANING)) {
      Fixture fixture = new Fixture(initial, "WRITING", false, false);
      fixture.reportQuarantined.set(true);
      UUID expectedOperationId = UUID.nameUUIDFromBytes(
          (fixture.runId + ":trading-lab:final-reset")
              .getBytes(StandardCharsets.UTF_8));
      when(fixture.client.state(fixture.runId)).thenReturn(
          exchange(
              fixture.absentAtGeneration(7L),
              "quarantine-later-initial-reset-" + initial.name().toLowerCase()));
      when(fixture.client.reset(argThat(request ->
          request != null && request.expectedGeneration() == 6L)))
          .thenReturn(failedExchange(
              "VALIDATION_RESET_STALE_GENERATION", 409, "REMOTE", false));
      when(fixture.client.reset(argThat(request ->
          request != null && request.expectedGeneration() == 7L)))
          .thenReturn(exchange(
              resetReceipt(8L),
              "quarantine-current-generation-" + initial.name().toLowerCase()));

      fixture.coordinator.coordinate(fixture.claim());

      InOrder resetOrder = org.mockito.Mockito.inOrder(fixture.client);
      resetOrder.verify(fixture.client).state(fixture.runId);
      resetOrder.verify(fixture.client).reset(argThat(request ->
          request.mode() == ValidationResetRequest.Mode.FINAL
              && request.operationId().equals(expectedOperationId)
              && request.expectedGeneration() == 6L));
      resetOrder.verify(fixture.client).reset(argThat(request ->
          request.mode() == ValidationResetRequest.Mode.FINAL
              && request.operationId().equals(expectedOperationId)
              && request.expectedGeneration() == 7L));
      assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
      verify(fixture.evidence).findCleanupIntentForRecovery(
          fixture.runId, fixture.owner, "cleanup:COMPLETED");
      verifyNoInteractions(fixture.projector, fixture.exchanges, fixture.initializer);
    }
  }

  @Test
  void quarantinedResettingReplaysCompletedFinalResetWithoutAdvancingAgain() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(8L), "quarantine-after-final-reset-crash"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        exchange(resetReceipt(8L), "quarantine-final-reset-replay"));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.client).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 7L));
    verify(fixture.client, never()).reset(argThat(request ->
        request.expectedGeneration() == 8L));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
  }

  @Test
  void quarantinedNonTerminalRunCancelsRawAndNeverResets() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.RUNNING, false, false, 4L, false);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "quarantine-running-state"));
    when(fixture.client.cancel(fixture.runId)).thenReturn(exchange(
        new ValidationControlReceipt(
            fixture.runId, "CANCEL_REQUESTED", ValidationRunState.CANCELLING),
        "quarantine-cancel"));

    fixture.coordinator.coordinate(fixture.claim());

    InOrder recovery = org.mockito.Mockito.inOrder(fixture.client, fixture.transitions);
    recovery.verify(fixture.client).state(fixture.runId);
    recovery.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.RUNNING
                && command.target() == TradingLabRunState.CANCELLING
                && "TRADING_LAB_REPORT_UNSAFE_TRACE".equals(command.reason())),
        eq(fixture.owner));
    recovery.verify(fixture.client).cancel(fixture.runId);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CANCELLING);
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verifyNoInteractions(
        fixture.projector, fixture.exchanges, fixture.initializer, fixture.writer);
  }

  @Test
  void quarantinedResetTimeoutResettingAndAbsentReplayTheExactTuple() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.COMPLETED, false, false, 10L, true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "quarantine-terminal-before-timeout"),
        exchange(fixture.resettingAtGeneration(1L), "quarantine-resetting"),
        exchange(fixture.absentAtGeneration(2L), "quarantine-reset-completed"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        failedExchange("VALIDATION_HTTP_TIMEOUT", 0, "TRANSPORT", true),
        exchange(resetReceipt(2L), "quarantine-reset-exact-replay"));

    fixture.coordinator.coordinate(fixture.claim());
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
    fixture.coordinator.coordinate(fixture.claim());
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
    fixture.coordinator.coordinate(fixture.claim());

    UUID expectedOperationId = UUID.nameUUIDFromBytes(
        (fixture.runId + ":trading-lab:final-reset")
            .getBytes(StandardCharsets.UTF_8));
    verify(fixture.client, times(2)).reset(argThat(request ->
        request.runId().equals(fixture.runId)
            && request.operationId().equals(expectedOperationId)
            && request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 1L));
    verify(fixture.writer, times(1)).fail(
        any(TradingLabReportWriteFence.class),
        eq("TRADING_LAB_REPORT_UNSAFE_TRACE"),
        eq("Trading Lab report contains unsafe trace evidence"));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verifyNoInteractions(fixture.projector, fixture.exchanges, fixture.initializer);
  }

  @Test
  void quarantinedResetSuccessReplaysBeforeRetryingReportClose() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.COMPLETED, false, false, 10L, true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "quarantine-terminal-before-close"),
        exchange(fixture.absentAtGeneration(2L), "quarantine-absent-after-close-failure"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        exchange(resetReceipt(2L), "quarantine-reset-success"),
        exchange(resetReceipt(2L), "quarantine-reset-success-replay"));
    BusinessException fenceLost = new BusinessException(
        "TRADING_LAB_REPORT_FENCE_LOST",
        "Trading Lab report write fence is no longer valid");
    org.mockito.Mockito.doThrow(fenceLost).doNothing().when(fixture.writer).fail(
        any(TradingLabReportWriteFence.class),
        eq("TRADING_LAB_REPORT_UNSAFE_TRACE"),
        eq("Trading Lab report contains unsafe trace evidence"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isSameAs(fenceLost);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);

    fixture.coordinator.coordinate(fixture.claim());

    UUID expectedOperationId = UUID.nameUUIDFromBytes(
        (fixture.runId + ":trading-lab:final-reset")
            .getBytes(StandardCharsets.UTF_8));
    verify(fixture.client, times(2)).reset(argThat(request ->
        request.operationId().equals(expectedOperationId)
            && request.expectedGeneration() == 1L));
    verify(fixture.writer, times(2)).fail(
        any(TradingLabReportWriteFence.class),
        eq("TRADING_LAB_REPORT_UNSAFE_TRACE"),
        eq("Trading Lab report contains unsafe trace evidence"));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verify(fixture.evidence, times(2)).findCleanupIntentForRecovery(
        fixture.runId, fixture.owner, "cleanup:COMPLETED");
    verifyNoInteractions(fixture.projector, fixture.exchanges, fixture.initializer);
  }

  @Test
  void quarantinedPermanentResetFailureNeverReleasesTheRun() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", false, false);
    fixture.reportQuarantined.set(true);
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.COMPLETED, false, false, 10L, true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "quarantine-terminal-before-rejection"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        failedExchange(
            "VALIDATION_RESET_STALE_GENERATION", 409, "REMOTE", false));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("VALIDATION_RESET_STALE_GENERATION"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
    verifyNoInteractions(
        fixture.projector,
        fixture.exchanges,
        fixture.initializer,
        fixture.writer,
        fixture.transitions);
  }

  @Test
  void cleanupPersistsTheLatestDurableActualStateBeforeFinalReset() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));
    Map<String, Object> state = Map.of(
        "orders", List.of(Map.of("id", "order-1")),
        "positions", List.of());
    when(fixture.projector.latestDurableState(fixture.runId, fixture.owner))
        .thenReturn(Optional.of(
            new TradingLabEvidenceReportProjector.DurableValidationState(
                27L,
                9L,
                "CHECKPOINT",
                NOW.plusSeconds(9),
                "correlation-9",
                state)));
    when(fixture.client.reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL)))
        .thenReturn(exchange(resetReceipt(2L), "final-reset"));

    fixture.coordinator.coordinate(fixture.claim());

    InOrder terminal = org.mockito.Mockito.inOrder(fixture.writer, fixture.client);
    terminal.verify(fixture.writer).appendSingleton(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.ACTUAL_STATE),
        argThat(value -> value instanceof Map<?, ?> actual
            && "COMPLETED".equals(actual.get("terminalState"))
            && Boolean.TRUE.equals(actual.get("stateAvailable"))
            && state.equals(actual.get("state"))
            && actual.get("stateSource") instanceof Map<?, ?> source
            && Long.valueOf(27L).equals(source.get("journalSequence"))
            && Long.valueOf(9L).equals(source.get("validationSequence"))
            && "CHECKPOINT".equals(source.get("eventType"))));
    terminal.verify(fixture.writer).flush(any(TradingLabReportWriteFence.class));
    terminal.verify(fixture.client).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 1L));
    terminal.verify(fixture.writer).appendSingleton(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.CLEANUP),
        any());
    terminal.verify(fixture.writer).flush(any(TradingLabReportWriteFence.class));
    terminal.verify(fixture.writer).complete(any(TradingLabReportWriteFence.class));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.COMPLETED);
  }

  @Test
  void noCheckpointCancellationDoesNotInventActualTradingState() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:CANCELLED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.CANCELLED)));
    when(fixture.projector.latestDurableState(fixture.runId, fixture.owner))
        .thenReturn(Optional.empty());
    when(fixture.client.reset(any(ValidationResetRequest.class)))
        .thenReturn(exchange(resetReceipt(2L), "cancel-final-reset"));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.writer).appendSingleton(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.ACTUAL_STATE),
        argThat(value -> value instanceof Map<?, ?> actual
            && "CANCELLED".equals(actual.get("terminalState"))
            && Boolean.FALSE.equals(actual.get("stateAvailable"))
            && actual.containsKey("state")
            && actual.get("state") == null
            && actual.containsKey("stateSource")
            && actual.get("stateSource") == null));
    verify(fixture.writer).cancel(
        any(TradingLabReportWriteFence.class),
        eq("Validation run cancelled"));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CANCELLED);
  }

  @Test
  void permanentFinalResetFailureRecordsDirtyCleanupAndRetainsQueueOwnership() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));
    when(fixture.projector.latestDurableState(fixture.runId, fixture.owner))
        .thenReturn(Optional.empty());
    when(fixture.client.reset(any(ValidationResetRequest.class)))
        .thenReturn(exchange(
            failedResetReceipt("DATABASE_RESET_FAILED"),
            "durable-final-reset-failure"));

    fixture.coordinator.coordinate(fixture.claim());

    InOrder terminal = org.mockito.Mockito.inOrder(
        fixture.writer, fixture.client);
    terminal.verify(fixture.writer).appendSingleton(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.ACTUAL_STATE),
        any());
    terminal.verify(fixture.writer).flush(any(TradingLabReportWriteFence.class));
    terminal.verify(fixture.client).reset(any(ValidationResetRequest.class));
    terminal.verify(fixture.writer).appendSingleton(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.CLEANUP),
        argThat(value -> value instanceof Map<?, ?> cleanup
            && "FAILED".equals(cleanup.get("status"))
            && Boolean.TRUE.equals(cleanup.get("cleanupFailed"))
            && Boolean.FALSE.equals(cleanup.get("environmentClean"))
            && "DATABASE_RESET_FAILED".equals(cleanup.get("failureCode"))
            && !cleanup.containsKey("message")
            && !cleanup.containsKey("exception")));
    terminal.verify(fixture.writer).flush(any(TradingLabReportWriteFence.class));
    verify(fixture.writer, never()).fail(any(), any(), any());
    verifyNoInteractions(fixture.transitions);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);
  }

  @Test
  void finalResetReceiptForTheWrongGenerationRecordsDirtyCleanupAndRetainsOwnership() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));
    when(fixture.projector.latestDurableState(fixture.runId, fixture.owner))
        .thenReturn(Optional.empty());
    when(fixture.client.reset(any(ValidationResetRequest.class)))
        .thenReturn(exchange(resetReceipt(3L), "wrong-generation-final-reset"));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.writer).appendSingleton(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.CLEANUP),
        argThat(value -> value instanceof Map<?, ?> cleanup
            && "FAILED".equals(cleanup.get("status"))
            && Boolean.TRUE.equals(cleanup.get("cleanupFailed"))
            && Boolean.FALSE.equals(cleanup.get("environmentClean"))));
    verify(fixture.writer, never()).complete(any(TradingLabReportWriteFence.class));
    verifyNoInteractions(fixture.transitions);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);
  }

  @Test
  void finalResetTransportFailureRemainsCleaningForDurableReceiptReplay() {
    assertFinalResetFailureRemainsRecoverable(failedExchange(
        "VALIDATION_HTTP_TIMEOUT",
        0,
        "TRANSPORT",
        true));
  }

  @Test
  void finalResetInProgressRejectionRemainsCleaningForDurableReceiptReplay() {
    assertFinalResetFailureRemainsRecoverable(failedExchange(
        "VALIDATION_RESET_OPERATION_IN_PROGRESS",
        400,
        "REMOTE",
        false));
  }

  @Test
  void finalResetPendingDurableResettingSkipsDuplicatePostUntilReadyExactReplay() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    ValidationResetRequest durableReset = new ValidationResetRequest(
        fixture.runId,
        UUID.nameUUIDFromBytes(
            (fixture.runId + ":trading-lab:final-reset")
                .getBytes(StandardCharsets.UTF_8)),
        ValidationResetRequest.Mode.FINAL,
        1L);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));
    when(fixture.exchanges.previousResult(
        fixture.runId, fixture.owner, "final-reset:COMPLETED"))
        .thenReturn(Optional.of(failedExchange(
            "VALIDATION_HTTP_TIMEOUT",
            0,
            "TRANSPORT",
            true).result()));
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.resettingAtGeneration(1L), "state-final-resetting-1"),
        exchange(fixture.absentAtGeneration(2L), "state-final-ready-2"));
    when(fixture.projector.latestDurableState(fixture.runId, fixture.owner))
        .thenReturn(Optional.empty());
    when(fixture.client.reset(durableReset))
        .thenReturn(exchange(resetReceipt(2L), "final-reset-exact-replay"));

    assertThatCode(() -> fixture.coordinator.coordinate(fixture.claim()))
        .doesNotThrowAnyException();

    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verify(fixture.writer, never()).complete(any(TradingLabReportWriteFence.class));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.client, times(1)).reset(durableReset);
    verify(fixture.writer).complete(any(TradingLabReportWriteFence.class));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.COMPLETED);
  }

  @Test
  void pendingFinalResetWithDifferentDurableGenerationFailsClosed() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));
    when(fixture.exchanges.previousResult(
        fixture.runId, fixture.owner, "final-reset:COMPLETED"))
        .thenReturn(Optional.of(failedExchange(
            "VALIDATION_HTTP_TIMEOUT",
            0,
            "TRANSPORT",
            true).result()));
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.resettingAtGeneration(2L), "state-final-wrong-generation"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_STATE_UNCERTAIN"));

    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verify(fixture.writer, never()).complete(any(TradingLabReportWriteFence.class));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);
  }

  @Test
  void failedReportRecoveredFromCleaningRequiresExactFinalResetProof() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "FAILED", false, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:FAILED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.FAILED)));
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(2L), "closed-failed-report-state"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        exchange(resetReceipt(2L), "closed-failed-report-reset-replay"));

    fixture.coordinator.coordinate(fixture.claim());

    UUID expectedOperationId = UUID.nameUUIDFromBytes(
        (fixture.runId + ":trading-lab:final-reset")
            .getBytes(StandardCharsets.UTF_8));
    verify(fixture.client).reset(argThat(request ->
        request.runId().equals(fixture.runId)
            && request.operationId().equals(expectedOperationId)
            && request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 1L));
    verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CLEANING
                && command.target() == TradingLabRunState.FAILED
                && "task10:cleaning-failed-reset-proven".equals(
                    command.idempotencyKey())
                && "VALIDATION_CLEANUP_FINISHED".equals(command.reason())),
        eq(fixture.owner));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verify(fixture.evidence).findIntent(
        fixture.runId, fixture.owner, "cleanup:FAILED");
    verifyNoInteractions(fixture.writer, fixture.projector, fixture.exchanges);
  }

  @Test
  void failedReportWithNoValidationCleanupIntentDoesNotInventAReset() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "FAILED", false, false);
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", TradingLabRunState.FAILED.name());
    cleanup.put("generation", 0L);
    cleanup.put("validationSequence", 0L);
    cleanup.put("failureCode", "TRADING_LAB_VALIDATION_FAILED");
    cleanup.put("finalResetRequired", false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:FAILED"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            2L,
            Map.of("evidence", cleanup))));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verifyNoInteractions(fixture.client, fixture.writer, fixture.projector, fixture.exchanges);
    verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CLEANING
                && command.target() == TradingLabRunState.FAILED
                && "VALIDATION_CLEANUP_FAILED".equals(command.reason())),
        eq(fixture.owner));
  }

  @Test
  void failedReportCleanupIntentGenerationMismatchNeverResetsOrReleasesTheRun() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "FAILED", false, false);
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", TradingLabRunState.FAILED.name());
    cleanup.put("generation", 999L);
    cleanup.put("validationSequence", 10L);
    cleanup.put("failureCode", "VALIDATION_EXECUTION_FAILED");
    cleanup.put("finalResetRequired", true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:FAILED"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            2L,
            Map.of("evidence", cleanup))));
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(2L), "closed-failed-report-wrong-intent"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_STATE_UNCERTAIN"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verifyNoInteractions(
        fixture.writer,
        fixture.projector,
        fixture.exchanges,
        fixture.transitions);
  }

  @Test
  void cleanupIntentTargetMismatchFailsClosedWithoutMutation() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", TradingLabRunState.CANCELLED.name());
    cleanup.put("generation", 1L);
    cleanup.put("validationSequence", 10L);
    cleanup.put("failureCode", null);
    cleanup.put("finalResetRequired", true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            2L,
            Map.of("evidence", cleanup))));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

    verifyNoInteractions(
        fixture.client,
        fixture.writer,
        fixture.projector,
        fixture.exchanges,
        fixture.transitions);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);
  }

  @Test
  void cleanupIntentCannotSkipResetForACompletedTarget() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "FAILED", false, false);
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", TradingLabRunState.COMPLETED.name());
    cleanup.put("generation", 0L);
    cleanup.put("validationSequence", 0L);
    cleanup.put("failureCode", null);
    cleanup.put("finalResetRequired", false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            2L,
            Map.of("evidence", cleanup))));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

    verifyNoInteractions(
        fixture.client,
        fixture.writer,
        fixture.projector,
        fixture.exchanges,
        fixture.transitions);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);
  }

  @Test
  void cleanupIntentCannotRequireAnUnrepresentableNextGeneration() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "FAILED", false, false);
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", TradingLabRunState.FAILED.name());
    cleanup.put("generation", Long.MAX_VALUE);
    cleanup.put("validationSequence", 10L);
    cleanup.put("failureCode", "VALIDATION_EXECUTION_FAILED");
    cleanup.put("finalResetRequired", true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:FAILED"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            2L,
            Map.of("evidence", cleanup))));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

    verifyNoInteractions(
        fixture.client,
        fixture.writer,
        fixture.projector,
        fixture.exchanges,
        fixture.transitions);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);
  }

  @Test
  void multipleCleanupIntentsFailClosedWithoutMutation() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:CANCELLED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.CANCELLED)));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

    verifyNoInteractions(
        fixture.client,
        fixture.writer,
        fixture.projector,
        fixture.exchanges,
        fixture.transitions);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);
  }

  @Test
  void multipleCleanupIntentsCannotMutateCancellingOrResettingState() {
    for (TradingLabRunState initial : List.of(
        TradingLabRunState.QUEUED,
        TradingLabRunState.RUNNING,
        TradingLabRunState.PAUSED,
        TradingLabRunState.CANCELLING,
        TradingLabRunState.RESETTING)) {
      Fixture fixture = new Fixture(initial, "WRITING", false, false);
      when(fixture.evidence.findIntent(
          fixture.runId, fixture.owner, "cleanup:COMPLETED"))
          .thenReturn(Optional.of(cleanupIntent(
              fixture.runId, TradingLabRunState.COMPLETED)));
      when(fixture.evidence.findIntent(
          fixture.runId, fixture.owner, "cleanup:CANCELLED"))
          .thenReturn(Optional.of(cleanupIntent(
              fixture.runId, TradingLabRunState.CANCELLED)));

      assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
          .isInstanceOfSatisfying(BusinessException.class, exception ->
              assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

      assertThat(fixture.state.get()).isEqualTo(initial);
      verifyNoInteractions(fixture.client, fixture.transitions);
    }
  }

  @Test
  void resettingCancelledBeforeInitialResetResumesWithoutValidationHttp() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:CANCELLED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId,
            TradingLabRunState.CANCELLED,
            0L,
            0L,
            null,
            false)));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CANCELLED);
    verifyNoInteractions(fixture.client);
    InOrder transitions = org.mockito.Mockito.inOrder(fixture.transitions);
    transitions.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.RESETTING
                && command.target() == TradingLabRunState.CANCELLING
                && "task7:resetting-cancelling-unstarted".equals(
                    command.idempotencyKey())),
        eq(fixture.owner));
    transitions.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CANCELLING
                && command.target() == TradingLabRunState.CLEANING
                && "task7:cancelling-cleaning:unstarted".equals(
                    command.idempotencyKey())),
        eq(fixture.owner));
    transitions.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CLEANING
                && command.target() == TradingLabRunState.CANCELLED),
        eq(fixture.owner));
  }

  @Test
  void resettingCancelledAfterInitialResetResumesItsBoundFinalReset() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:CANCELLED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId,
            TradingLabRunState.CANCELLED,
            1L,
            0L,
            null,
            true)));
    when(fixture.projector.latestDurableState(fixture.runId, fixture.owner))
        .thenReturn(Optional.empty());
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        exchange(resetReceipt(2L), "resetting-cancelled-final-reset"));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CANCELLED);
    verify(fixture.client, times(1)).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 1L));
    verify(fixture.client, never()).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.INITIAL));
    verify(fixture.client, never()).startRun(any(ValidationRunStartRequest.class));
    InOrder transitions = org.mockito.Mockito.inOrder(fixture.transitions);
    transitions.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.RESETTING
                && command.target() == TradingLabRunState.CANCELLING
                && "task7:resetting-cancelling-after-reset".equals(
                    command.idempotencyKey())),
        eq(fixture.owner));
    transitions.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CANCELLING
                && command.target() == TradingLabRunState.CLEANING
                && "task7:cancelling-cleaning:reset-only".equals(
                    command.idempotencyKey())),
        eq(fixture.owner));
    transitions.verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CLEANING
                && command.target() == TradingLabRunState.CANCELLED),
        eq(fixture.owner));
  }

  @Test
  void resettingCancelledIntentsRequireTheStickyCancellationFlag() {
    for (boolean finalResetRequired : List.of(false, true)) {
      Fixture fixture = new Fixture(
          TradingLabRunState.RESETTING, "WRITING", false, false);
      when(fixture.evidence.findIntent(
          fixture.runId, fixture.owner, "cleanup:CANCELLED"))
          .thenReturn(Optional.of(cleanupIntent(
              fixture.runId,
              TradingLabRunState.CANCELLED,
              finalResetRequired ? 1L : 0L,
              0L,
              null,
              finalResetRequired)));

      assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
          .isInstanceOfSatisfying(BusinessException.class, exception ->
              assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

      assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RESETTING);
      verifyNoInteractions(
          fixture.client, fixture.writer, fixture.transitions, fixture.initializer);
    }
  }

  @Test
  void resettingResetOnlyIntentRejectsAValidationEventCursor() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:CANCELLED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId,
            TradingLabRunState.CANCELLED,
            1L,
            1L,
            null,
            true)));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RESETTING);
    verifyNoInteractions(
        fixture.client, fixture.writer, fixture.transitions, fixture.initializer);
  }

  @Test
  void runningCleanupIntentResumesBeforeAnyValidationStateRequest() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", false, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));
    when(fixture.projector.latestDurableState(fixture.runId, fixture.owner))
        .thenReturn(Optional.empty());
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        exchange(resetReceipt(2L), "running-planned-final-reset"));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.COMPLETED);
    verify(fixture.client, never()).state(fixture.runId);
    verify(fixture.client, times(1)).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 1L));
    verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.RUNNING
                && command.target() == TradingLabRunState.CLEANING
                && command.idempotencyKey().contains("planned:completed")),
        eq(fixture.owner));
  }

  @Test
  void enterCleaningPostflightRejectsAConcurrentSecondTerminalIntent() {
    Fixture fixture = new Fixture(TradingLabRunState.RUNNING, "WRITING", false, false);
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.COMPLETED, false, false, 0L, true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "terminal-before-intent-race"));
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:CANCELLED"))
        .thenReturn(
            Optional.empty(),
            Optional.empty(),
            Optional.of(cleanupIntent(
                fixture.runId, TradingLabRunState.CANCELLED)));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
    verify(fixture.evidence).appendIntent(
        eq(fixture.runId),
        eq(fixture.owner),
        eq("cleanup:COMPLETED"),
        any());
    verify(fixture.projector, never()).project(
        eq(fixture.runId),
        eq(fixture.owner),
        any(TradingLabCoordinatorEvidence.class));
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verifyNoInteractions(fixture.writer, fixture.transitions);
  }

  @Test
  void resetRequiredCleanupIntentCannotMutateAResettingRun() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:FAILED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.FAILED)));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RESETTING);
    verifyNoInteractions(fixture.client, fixture.transitions);
  }

  @Test
  void quarantineDiscoveredAfterFinalResetCannotReleaseACancelledRun() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:CANCELLED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.CANCELLED)));
    when(fixture.projector.latestDurableState(fixture.runId, fixture.owner))
        .thenReturn(Optional.empty());
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        exchange(resetReceipt(2L), "cleanup-reset-before-cancel-quarantine"));
    org.mockito.Mockito.doAnswer(ignored -> {
      fixture.reportStatus.set("CANCELLED");
      fixture.reportQuarantined.set(true);
      return null;
    }).when(fixture.writer).cancel(
        any(TradingLabReportWriteFence.class),
        eq("Validation run cancelled"));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verify(fixture.client, times(1)).reset(argThat(request ->
        request.mode() == ValidationResetRequest.Mode.FINAL
            && request.expectedGeneration() == 1L));
    verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CLEANING
                && command.target() == TradingLabRunState.FAILED
                && "TRADING_LAB_REPORT_UNSAFE_TRACE".equals(command.reason())),
        eq(fixture.owner));
  }

  @Test
  void quarantineDiscoveredWhileClosingUnstartedCancellationFailsWithoutReset() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", TradingLabRunState.CANCELLED.name());
    cleanup.put("generation", 0L);
    cleanup.put("validationSequence", 0L);
    cleanup.put("failureCode", null);
    cleanup.put("finalResetRequired", false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:CANCELLED"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            2L,
            Map.of("evidence", cleanup))));
    org.mockito.Mockito.doAnswer(ignored -> {
      fixture.reportStatus.set("CANCELLED");
      fixture.reportQuarantined.set(true);
      return null;
    }).when(fixture.writer).cancel(
        any(TradingLabReportWriteFence.class),
        eq("Cancelled before validation start"));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verifyNoInteractions(fixture.client);
    verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CLEANING
                && command.target() == TradingLabRunState.FAILED
                && "TRADING_LAB_REPORT_UNSAFE_TRACE".equals(command.reason())),
        eq(fixture.owner));
  }

  @Test
  void quarantineDiscoveredWhileClosingRejectedInitialResetUsesUnsafeAudit() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", TradingLabRunState.FAILED.name());
    cleanup.put("generation", 0L);
    cleanup.put("validationSequence", 0L);
    cleanup.put("failureCode", "TRADING_LAB_VALIDATION_FAILED");
    cleanup.put("finalResetRequired", false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:FAILED"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            2L,
            Map.of("evidence", cleanup))));
    org.mockito.Mockito.doAnswer(ignored -> {
      fixture.reportStatus.set("FAILED");
      fixture.reportQuarantined.set(true);
      return null;
    }).when(fixture.writer).fail(
        any(TradingLabReportWriteFence.class),
        eq("TRADING_LAB_VALIDATION_FAILED"),
        eq("Validation run failed before validation start"));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verifyNoInteractions(fixture.client);
    verify(fixture.transitions).transitionFenced(
        argThat(command ->
            command.expected() == TradingLabRunState.CLEANING
                && command.target() == TradingLabRunState.FAILED
                && "task10:cleaning-failed-unsafe-report".equals(
                    command.idempotencyKey())
                && "TRADING_LAB_REPORT_UNSAFE_TRACE".equals(command.reason())),
        eq(fixture.owner));
  }

  @Test
  void closedQuarantineWithNoResetIntentRecoversWithoutTouchingValidation() {
    for (TradingLabRunState target : List.of(
        TradingLabRunState.CANCELLED,
        TradingLabRunState.FAILED)) {
      Fixture fixture = new Fixture(
          TradingLabRunState.CLEANING, target.name(), false, false);
      fixture.reportQuarantined.set(true);
      Map<String, Object> cleanup = new LinkedHashMap<>();
      cleanup.put("target", target.name());
      cleanup.put("generation", 0L);
      cleanup.put("validationSequence", 0L);
      cleanup.put(
          "failureCode",
          target == TradingLabRunState.FAILED
              ? "TRADING_LAB_VALIDATION_FAILED"
              : null);
      cleanup.put("finalResetRequired", false);
      when(fixture.evidence.findIntent(
          fixture.runId, fixture.owner, "cleanup:" + target.name()))
          .thenReturn(Optional.of(evidence(
              fixture.runId,
              2L,
              Map.of("evidence", cleanup))));

      fixture.coordinator.coordinate(fixture.claim());

      assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
      verifyNoInteractions(fixture.client, fixture.writer, fixture.projector, fixture.exchanges);
      verify(fixture.transitions).transitionFenced(
          argThat(command ->
              command.expected() == TradingLabRunState.CLEANING
                  && command.target() == TradingLabRunState.FAILED
                  && "TRADING_LAB_REPORT_UNSAFE_TRACE".equals(command.reason())),
          eq(fixture.owner));
    }
  }

  @Test
  void closedQuarantineCleanupIntentGenerationMismatchNeverResetsOrReleasesTheRun() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "CANCELLED", false, false);
    fixture.reportQuarantined.set(true);
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", TradingLabRunState.CANCELLED.name());
    cleanup.put("generation", 999L);
    cleanup.put("validationSequence", 10L);
    cleanup.put("failureCode", null);
    cleanup.put("finalResetRequired", true);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:CANCELLED"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            2L,
            Map.of("evidence", cleanup))));
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(2L), "quarantine-wrong-cleanup-generation"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_STATE_UNCERTAIN"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verifyNoInteractions(
        fixture.writer,
        fixture.projector,
        fixture.exchanges,
        fixture.transitions);
  }

  @Test
  void failedReportWithDurableFailedResetStateNeverReleasesTheRun() {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "FAILED", false, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:FAILED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.FAILED)));
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.failedAtGeneration(2L), "closed-failed-report-dirty-state"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_STATE_UNCERTAIN"));

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verifyNoInteractions(
        fixture.writer,
        fixture.projector,
        fixture.exchanges,
        fixture.transitions);
  }

  @Test
  void terminalHighWatermarkBehindDurableCursorFailsClosed() {
    Fixture fixture = new Fixture(
        TradingLabRunState.RUNNING,
        "WRITING",
        false,
        false,
        10L);
    when(fixture.evidence.lastValidationSequence(fixture.runId, fixture.owner))
        .thenReturn(11L);
    ValidationRunStateSnapshot remote = fixture.remote(
        ValidationRunState.COMPLETED, false, false, 10L, true);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.observation(remote), "state-regressed"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_HIGH_WATERMARK_REGRESSED"));

    verify(fixture.client, never()).eventsAfter(any(), anyLong());
    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
  }

  @Test
  void cancellingBeforeAnyInitialResetSkipsValidationAndClosesReport() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, true);
    when(fixture.client.state(fixture.runId)).thenReturn(exchange(
        fixture.absentBeforeReset(), "state-clean-absence"));
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "initial-reset"))
        .thenReturn(Optional.empty());
    when(fixture.evidence.appendIntent(
        eq(fixture.runId), eq(fixture.owner), eq("cleanup:CANCELLED"), any()))
        .thenAnswer(invocation -> evidence(
            fixture.runId,
            1L,
            Map.of("evidence", invocation.getArgument(3))));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verify(fixture.client, never()).startRun(any(ValidationRunStartRequest.class));
    InOrder terminal = org.mockito.Mockito.inOrder(fixture.writer);
    terminal.verify(fixture.writer).appendSingleton(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.ACTUAL_STATE),
        argThat(value -> value instanceof Map<?, ?> actual
            && "CANCELLED".equals(actual.get("terminalState"))
            && Long.valueOf(0L).equals(actual.get("validationGeneration"))
            && Long.valueOf(0L).equals(actual.get("lastValidationSequence"))
            && actual.containsKey("failureCode")
            && actual.get("failureCode") == null
            && Boolean.FALSE.equals(actual.get("stateAvailable"))
            && actual.containsKey("stateSource")
            && actual.get("stateSource") == null
            && actual.containsKey("state")
            && actual.get("state") == null));
    terminal.verify(fixture.writer).flush(any(TradingLabReportWriteFence.class));
    terminal.verify(fixture.writer).appendSingleton(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.CLEANUP),
        any());
    terminal.verify(fixture.writer).flush(any(TradingLabReportWriteFence.class));
    terminal.verify(fixture.writer).cancel(
        any(TradingLabReportWriteFence.class),
        eq("Cancelled before validation start"));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CANCELLED);
  }

  @Test
  void uncertainAcceptedStartDuringResettingCancelNeverTriggersCleanupReset() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, true);
    when(fixture.client.state(fixture.runId)).thenReturn(exchange(
        fixture.absentBeforeReset(), "state-start-uncertain"));
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "initial-reset"))
        .thenReturn(Optional.of(evidence(fixture.runId, 1L, Map.of())));
    when(fixture.evidence.findIntent(fixture.runId, fixture.owner, "start"))
        .thenReturn(Optional.of(evidence(fixture.runId, 2L, Map.of())));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_STATE_UNCERTAIN"));

    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verify(fixture.client, never()).cancel(fixture.runId);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RESETTING);
  }

  @Test
  void initialResetUsesTheCurrentAuthoritativeNonZeroGeneration() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    ValidationRunStartRequest request = fixture.request(7L);
    ValidationRunStateSnapshot running = fixture.remote(
        7L, ValidationRunState.RUNNING, false, false, 0L, false);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(6L), "state-generation-6"),
        exchange(fixture.observation(running), "state-running-7"));
    when(fixture.client.reset(argThat(requested ->
        requested.mode() == ValidationResetRequest.Mode.INITIAL
            && requested.expectedGeneration() == 6L)))
        .thenReturn(exchange(resetReceipt(7L), "reset-generation-6"));
    when(fixture.client.startRun(request)).thenReturn(exchange(
        new ValidationRunAccepted(
            fixture.runId,
            request.requestFingerprint(),
            ValidationRunState.ACCEPTED),
        "start-generation-7"));
    when(fixture.client.eventsAfter(fixture.runId, 0L)).thenReturn(exchange(
        new ValidationEventPage(List.of(), 0L, false, false), "events-generation-7"));

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.client).reset(argThat(requested ->
        requested.runId().equals(fixture.runId)
            && requested.mode() == ValidationResetRequest.Mode.INITIAL
            && requested.expectedGeneration() == 6L));
    verify(fixture.client).startRun(request);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
  }

  @Test
  void initialResetRecoveryReplaysTheExactDurableIntentInsteadOfAdvancingItsFence() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    UUID operationId = UUID.nameUUIDFromBytes(
        (fixture.runId + ":trading-lab:initial-reset")
            .getBytes(StandardCharsets.UTF_8));
    ValidationResetRequest durableReset = new ValidationResetRequest(
        fixture.runId,
        operationId,
        ValidationResetRequest.Mode.INITIAL,
        6L);
    ValidationRunStartRequest request = fixture.request(7L);
    ValidationRunStateSnapshot running = fixture.remote(
        7L, ValidationRunState.RUNNING, false, false, 0L, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "initial-reset"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            1L,
            Map.of("evidence", Map.of(
                "runId", fixture.runId.toString(),
                "operationId", operationId.toString(),
                "mode", "INITIAL",
                "expectedGeneration", 6L)))));
    when(fixture.exchanges.previousResult(
        fixture.runId, fixture.owner, "initial-reset"))
        .thenReturn(Optional.of(failedExchange(
            "VALIDATION_HTTP_TIMEOUT",
            0,
            "TRANSPORT",
            true).result()));
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.resettingAtGeneration(6L), "state-resetting-6"),
        exchange(fixture.absentAtGeneration(7L), "state-after-uncertain-reset"),
        exchange(fixture.observation(running), "state-running-after-replay"));
    when(fixture.client.reset(durableReset))
        .thenReturn(exchange(resetReceipt(7L), "reset-exact-replay"));
    when(fixture.client.startRun(request)).thenReturn(exchange(
        new ValidationRunAccepted(
            fixture.runId,
            request.requestFingerprint(),
            ValidationRunState.ACCEPTED),
        "start-after-replay"));
    when(fixture.client.eventsAfter(fixture.runId, 0L)).thenReturn(exchange(
        new ValidationEventPage(List.of(), 0L, false, false), "events-after-replay"));

    assertThatCode(() -> fixture.coordinator.coordinate(fixture.claim()))
        .doesNotThrowAnyException();

    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verify(fixture.client, never()).startRun(any(ValidationRunStartRequest.class));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RESETTING);

    fixture.coordinator.coordinate(fixture.claim());

    verify(fixture.client, times(1)).reset(durableReset);
    verify(fixture.client).startRun(request);
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RUNNING);
  }

  @Test
  void initialResetTimeoutYieldsWithoutEscapingTheScheduledTurn() {
    assertInitialResetPendingYields(failedExchange(
        "VALIDATION_HTTP_TIMEOUT",
        0,
        "TRANSPORT",
        true));
  }

  @Test
  void initialResetExactInProgressYieldsWithoutEscapingTheScheduledTurn() {
    assertInitialResetPendingYields(failedExchange(
        "VALIDATION_RESET_OPERATION_IN_PROGRESS",
        400,
        "REMOTE",
        false));
  }

  @Test
  void pendingInitialResetWithDifferentDurableGenerationFailsClosed() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    UUID operationId = UUID.nameUUIDFromBytes(
        (fixture.runId + ":trading-lab:initial-reset")
            .getBytes(StandardCharsets.UTF_8));
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "initial-reset"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            1L,
            Map.of("evidence", Map.of(
                "runId", fixture.runId.toString(),
                "operationId", operationId.toString(),
                "mode", "INITIAL",
                "expectedGeneration", 6L)))));
    when(fixture.exchanges.previousResult(
        fixture.runId, fixture.owner, "initial-reset"))
        .thenReturn(Optional.of(failedExchange(
            "VALIDATION_HTTP_TIMEOUT",
            0,
            "TRANSPORT",
            true).result()));
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.resettingAtGeneration(7L), "state-resetting-wrong-generation"));

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_STATE_UNCERTAIN"));

    verify(fixture.client, never()).reset(any(ValidationResetRequest.class));
    verify(fixture.client, never()).startRun(any(ValidationRunStartRequest.class));
    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RESETTING);
  }

  @Test
  void deterministicNonRetryableInitialResetRejectionFailsOnceWithGenericReport() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(51L), "state-before-rejected-reset"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(
        failedExchange(
            "VALIDATION_RESET_INITIAL_RUN_PRESENT",
            400,
            "REMOTE",
            false));

    fixture.coordinator.coordinate(fixture.claim());
    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verify(fixture.client, times(1)).reset(any(ValidationResetRequest.class));
    verify(fixture.client, never()).startRun(any(ValidationRunStartRequest.class));
    verify(fixture.writer).appendSingleton(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.CLEANUP),
        argThat(value -> value instanceof Map<?, ?> cleanup
            && "SKIPPED".equals(cleanup.get("status"))
            && "VALIDATION_INITIAL_RESET_REJECTED".equals(cleanup.get("reason"))
            && Boolean.FALSE.equals(cleanup.get("environmentClean"))
            && !cleanup.containsKey("remoteCode")));
    verify(fixture.writer).fail(
        any(TradingLabReportWriteFence.class),
        eq("TRADING_LAB_VALIDATION_FAILED"),
        eq("Validation run failed before validation start"));
  }

  @Test
  void rejectedInitialResetCleanupIntentResumesWithoutRepostingReset() {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", TradingLabRunState.FAILED.name());
    cleanup.put("generation", 0L);
    cleanup.put("validationSequence", 0L);
    cleanup.put("failureCode", "TRADING_LAB_VALIDATION_FAILED");
    cleanup.put("finalResetRequired", false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:FAILED"))
        .thenReturn(Optional.of(evidence(
            fixture.runId,
            2L,
            Map.of("evidence", cleanup))));

    fixture.coordinator.coordinate(fixture.claim());

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.FAILED);
    verifyNoInteractions(fixture.client);
    verify(fixture.writer).fail(
        any(TradingLabReportWriteFence.class),
        eq("TRADING_LAB_VALIDATION_FAILED"),
        eq("Validation run failed before validation start"));
  }

  @Test
  void retryableTransportAndUncertainInitialResetFailuresRemainRecoverable() {
    assertInitialResetFailureRemainsRecoverable(failedExchange(
        "VALIDATION_HTTP_STATUS_503",
        503,
        "REMOTE",
        true));
    assertInitialResetFailureRemainsRecoverable(failedExchange(
        "VALIDATION_HTTP_TRANSPORT_FAILURE",
        0,
        "TRANSPORT",
        true));
    assertInitialResetFailureRemainsRecoverable(failedExchange(
        "VALIDATION_RESET_OPERATION_IN_PROGRESS",
        409,
        "REMOTE",
        false));
    assertInitialResetFailureRemainsRecoverable(failedExchange(
        "RESET_ALREADY_IN_PROGRESS",
        400,
        "REMOTE",
        false));
    assertInitialResetFailureRemainsRecoverable(failedExchange(
        "MUTATION_BUSY",
        409,
        "REMOTE",
        false));
    assertInitialResetFailureRemainsRecoverable(failedExchange(
        "VALIDATION_RESET_STALE_GENERATION",
        409,
        "REMOTE",
        false));
  }

  private static TradingLabCoordinatorEvidence cleanupIntent(UUID runId) {
    return cleanupIntent(runId, TradingLabRunState.COMPLETED);
  }

  private static TradingLabCoordinatorEvidence cleanupIntent(
      UUID runId,
      TradingLabRunState target
  ) {
    return cleanupIntent(runId, target, 1L, 10L, null, true);
  }

  private static TradingLabCoordinatorEvidence cleanupIntent(
      UUID runId,
      TradingLabRunState target,
      long generation,
      long validationSequence,
      String failureCode,
      boolean finalResetRequired
  ) {
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", target.name());
    cleanup.put("generation", generation);
    cleanup.put("validationSequence", validationSequence);
    cleanup.put("failureCode", failureCode);
    cleanup.put("finalResetRequired", finalResetRequired);
    return evidence(runId, 1L, Map.of("evidence", cleanup));
  }

  private static TradingLabCoordinatorEvidence evidence(
      UUID runId,
      long sequence,
      Map<String, Object> payload
  ) {
    return new TradingLabCoordinatorEvidence(
        UUID.nameUUIDFromBytes((runId + ":" + sequence).getBytes()),
        runId,
        sequence,
        TradingLabCoordinatorEvidenceStore.HTTP_INTENT,
        null,
        NOW.plusMillis(sequence),
        null,
        payload);
  }

  private static <T> ValidationBackendExchange<T> exchange(T data, String traceId) {
    ValidationHttpResult result = new ValidationHttpResult(
        0L,
        "validation",
        "GET",
        URI.create("http://127.0.0.1:18087/internal/validation/state"
            + "?runId=00000000-0000-0000-0000-000000000001"),
        null,
        NOW,
        200,
        Duration.ofMillis(1),
        Map.of(),
        Map.of("ok", true),
        traceId,
        null,
        null);
    return new ValidationBackendExchange<>(data, result, mock(SafeTradingLabHttpTrace.class));
  }

  private static ValidationResetReceipt resetReceipt(long generation) {
    return new ValidationResetReceipt(
        ValidationResetReceipt.Status.SUCCEEDED,
        "fx_validation_task7",
        generation,
        generation,
        NOW,
        NOW.plusSeconds(1),
        List.of(),
        null);
  }

  private static ValidationResetReceipt failedResetReceipt(String code) {
    return new ValidationResetReceipt(
        ValidationResetReceipt.Status.FAILED,
        "fx_validation_task7",
        null,
        null,
        NOW,
        NOW.plusSeconds(1),
        List.of(),
        code);
  }

  private static ValidationBackendExchange<ValidationResetReceipt> failedExchange(
      String code
  ) {
    return failedExchange(code, 503, "REMOTE", true);
  }

  private static ValidationBackendExchange<ValidationResetReceipt> failedExchange(
      String code,
      int status,
      String type,
      boolean retryable
  ) {
    ValidationHttpResult result = new ValidationHttpResult(
        0L,
        "validation",
        "POST",
        URI.create("http://127.0.0.1:18087/internal/validation/reset"),
        null,
        NOW,
        status,
        Duration.ofMillis(1),
        Map.of(),
        Map.of("code", code),
        "reset-failure",
        null,
        new ThrowableInfo(type, code, "uncontrolled remote detail", retryable));
    return new ValidationBackendExchange<>(
        null, result, mock(SafeTradingLabHttpTrace.class));
  }

  private static void assertInitialResetFailureRemainsRecoverable(
      ValidationBackendExchange<ValidationResetReceipt> failure
  ) {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(51L), "state-before-retryable-reset"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(failure);

    assertThatThrownBy(() -> fixture.coordinator.coordinate(fixture.claim()))
        .isInstanceOf(BusinessException.class);

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RESETTING);
    verify(fixture.client, times(1)).reset(any(ValidationResetRequest.class));
    verify(fixture.client, never()).startRun(any(ValidationRunStartRequest.class));
    verify(fixture.writer, never()).fail(any(), any(), any());
  }

  private static void assertInitialResetPendingYields(
      ValidationBackendExchange<ValidationResetReceipt> failure
  ) {
    Fixture fixture = new Fixture(TradingLabRunState.RESETTING, "WRITING", false, false);
    when(fixture.client.state(fixture.runId)).thenReturn(
        exchange(fixture.absentAtGeneration(51L), "state-before-pending-reset"));
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(failure);

    assertThatCode(() -> fixture.coordinator.coordinate(fixture.claim()))
        .doesNotThrowAnyException();

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.RESETTING);
    verify(fixture.client, times(1)).reset(any(ValidationResetRequest.class));
    verify(fixture.client, never()).startRun(any(ValidationRunStartRequest.class));
    verify(fixture.writer, never()).fail(any(), any(), any());
    verifyNoInteractions(fixture.transitions);
  }

  private static void assertFinalResetFailureRemainsRecoverable(
      ValidationBackendExchange<ValidationResetReceipt> failure
  ) {
    Fixture fixture = new Fixture(TradingLabRunState.CLEANING, "WRITING", false, false);
    when(fixture.evidence.findIntent(
        fixture.runId, fixture.owner, "cleanup:COMPLETED"))
        .thenReturn(Optional.of(cleanupIntent(
            fixture.runId, TradingLabRunState.COMPLETED)));
    when(fixture.projector.latestDurableState(fixture.runId, fixture.owner))
        .thenReturn(Optional.empty());
    when(fixture.client.reset(any(ValidationResetRequest.class))).thenReturn(failure);

    assertThatCode(() -> fixture.coordinator.coordinate(fixture.claim()))
        .doesNotThrowAnyException();

    assertThat(fixture.state.get()).isEqualTo(TradingLabRunState.CLEANING);
    verify(fixture.client, times(1)).reset(any(ValidationResetRequest.class));
    verify(fixture.writer, never()).appendSingleton(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.CLEANUP),
        any());
    verify(fixture.writer, never()).fail(any(), any(), any());
    verifyNoInteractions(fixture.transitions);
  }

  private static final class Fixture {

    private final UUID runId = UUID.randomUUID();
    private final UUID scenarioId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final String owner = "worker:task7-recovery";
    private final AtomicReference<TradingLabRunState> state;
    private final AtomicReference<String> reportStatus;
    private final AtomicBoolean reportQuarantined = new AtomicBoolean(false);
    private final AtomicLong version = new AtomicLong(20L);
    private final AtomicBoolean pauseRequested;
    private final boolean cancelRequested;
    private final long totalTicks;
    private final Map<String, TradingLabCoordinatorEvidence> cleanupIntents =
        new LinkedHashMap<>();
    private final TradingLabCoordinatorRunLoader loader =
        mock(TradingLabCoordinatorRunLoader.class);
    private final TradingLabRunRepository runs =
        mock(TradingLabRunRepository.class);
    private final TradingLabRunTransitionService transitions =
        mock(TradingLabRunTransitionService.class);
    private final TradingLabCoordinatorEvidenceStore evidence =
        mock(TradingLabCoordinatorEvidenceStore.class);
    private final TradingLabEvidenceReportProjector projector =
        mock(TradingLabEvidenceReportProjector.class);
    private final TradingLabValidationExchangeRecorder exchanges =
        mock(TradingLabValidationExchangeRecorder.class);
    private final TradingLabRunReportInitializer initializer =
        mock(TradingLabRunReportInitializer.class);
    private final TradingLabValidationStartRequestFactory requests =
        mock(TradingLabValidationStartRequestFactory.class);
    private final TradingLabFencedReportWriter writer =
        mock(TradingLabFencedReportWriter.class);
    private final ValidationBackendClient client = mock(ValidationBackendClient.class);
    private final ValidationRunStartRequest request;
    private final DefaultTradingLabRunCoordinator coordinator;

    private Fixture(
        TradingLabRunState initialState,
        String initialReportStatus,
        boolean pauseRequested,
        boolean cancelRequested
    ) {
      this(initialState, initialReportStatus, pauseRequested, cancelRequested, 1L);
    }

    private Fixture(
        TradingLabRunState initialState,
        String initialReportStatus,
        boolean pauseRequested,
        boolean cancelRequested,
        long totalTicks
    ) {
      state = new AtomicReference<>(initialState);
      reportStatus = new AtomicReference<>(initialReportStatus);
      this.pauseRequested = new AtomicBoolean(pauseRequested);
      this.cancelRequested = cancelRequested;
      this.totalTicks = totalTicks;
      request = request(1L);

      when(loader.requireLive(any(TradingLabRunClaim.class)))
          .thenAnswer(ignored -> context());
      when(loader.requireLive(runId, owner)).thenAnswer(ignored -> context());
      when(runs.advanceFencedProcessedTicks(eq(runId), eq(owner), anyLong()))
          .thenReturn(1);
      when(transitions.transitionFenced(any(), eq(owner))).thenAnswer(invocation -> {
        RunTransitionCommand command = invocation.getArgument(0);
        assertThat(state.get()).isEqualTo(command.expected());
        state.set(command.target());
        version.incrementAndGet();
        return null;
      });
      when(requests.create(any(), anyLong())).thenAnswer(invocation ->
          request(invocation.getArgument(1)));
      when(evidence.lastValidationSequence(runId, owner)).thenReturn(0L);
      when(evidence.findIntent(eq(runId), eq(owner), anyString()))
          .thenAnswer(invocation -> Optional.ofNullable(
              cleanupIntents.get(invocation.getArgument(2))));
      when(evidence.appendIntent(
          eq(runId), eq(owner), anyString(), any()))
          .thenAnswer(invocation -> {
            String logicalKey = invocation.getArgument(2);
            Map<String, Object> request = new LinkedHashMap<>(
                invocation.getArgument(3));
            TradingLabCoordinatorEvidence appended = evidence(
                runId,
                cleanupIntents.size() + 1L,
                Map.of("evidence", request));
            TradingLabCoordinatorEvidence existing =
                cleanupIntents.putIfAbsent(logicalKey, appended);
            return existing == null ? appended : existing;
          });
      when(exchanges.previousResult(runId, owner, "start"))
          .thenReturn(Optional.empty());
      when(exchanges.<Object>query(eq(runId), eq(owner), any()))
          .thenAnswer(invocation -> invoke(invocation.getArgument(2)));
      when(exchanges.<Object>mutation(
          eq(runId), eq(owner), anyString(), any(), any()))
          .thenAnswer(invocation -> invoke(invocation.getArgument(4)));
      when(exchanges.<Object>mutationAttempt(
          eq(runId), eq(owner), anyString(), any(), any()))
          .thenAnswer(invocation -> invoke(invocation.getArgument(4)));
      when(evidence.findCleanupIntentForRecovery(
          eq(runId), eq(owner), anyString()))
          .thenAnswer(invocation -> evidence.findIntent(
              runId, owner, invocation.getArgument(2)));

      coordinator = new DefaultTradingLabRunCoordinator(
          loader,
          runs,
          transitions,
          evidence,
          projector,
          exchanges,
          initializer,
          requests,
          writer,
          client);
    }

    private TradingLabRunClaim claim() {
      return new TradingLabRunClaim(
          runId, state.get(), version.get(), 1L, owner, NOW.plusSeconds(30));
    }

    private TradingLabCoordinatorRunContext context() {
      return new TradingLabCoordinatorRunContext(new TradingLabWorkerRunSnapshot(
          runId,
          scenarioId,
          reportId,
          reportStatus.get(),
          reportQuarantined.get(),
          state.get().name(),
          version.get(),
          pauseRequested.get(),
          cancelRequested,
          NOW,
          NOW,
          0L,
          totalTicks,
          BigDecimal.ONE,
          "{}",
          "{}",
          "{}",
          "b".repeat(64),
          "model-v1",
          "symbols-v1",
          "code-v1",
          actorId), owner);
    }

    private ValidationRunStateSnapshot remote(
        ValidationRunState remoteState,
        boolean remotePauseRequested,
        boolean remoteCancelRequested,
        long highWatermark,
        boolean terminal
    ) {
      return remote(
          1L,
          remoteState,
          remotePauseRequested,
          remoteCancelRequested,
          highWatermark,
          terminal);
    }

    private ValidationRunStateSnapshot remote(
        long generation,
        ValidationRunState remoteState,
        boolean remotePauseRequested,
        boolean remoteCancelRequested,
        long highWatermark,
        boolean terminal
    ) {
      return remote(
          generation,
          remoteState,
          remotePauseRequested,
          remoteCancelRequested,
          highWatermark,
          null,
          terminal);
    }

    private ValidationRunStateSnapshot remote(
        long generation,
        ValidationRunState remoteState,
        boolean remotePauseRequested,
        boolean remoteCancelRequested,
        long highWatermark,
        String failureCode,
        boolean terminal
    ) {
      return new ValidationRunStateSnapshot(
          runId,
          generation,
          request.requestFingerprint(),
          remoteState,
          remotePauseRequested,
          remoteCancelRequested,
          Math.max(-1L, highWatermark),
          NOW,
          highWatermark,
          failureCode,
          NOW,
          terminal);
    }

    private ValidationRunStateObservation observation(ValidationRunStateSnapshot run) {
      return new ValidationRunStateObservation(
          "READY",
          run.generation(),
          "READY",
          run.generation(),
          run.generation(),
          run.generation(),
          run.generation(),
          true,
          run,
          Map.of("virtualTime", NOW.toString()),
          0L,
          true);
    }

    private ValidationRunStateObservation absentAtGeneration(long generation) {
      return new ValidationRunStateObservation(
          "READY",
          generation,
          "READY",
          generation,
          generation,
          generation,
          generation,
          true,
          null,
          null,
          null,
          false);
    }

    private ValidationRunStateObservation resettingAtGeneration(long generation) {
      return new ValidationRunStateObservation(
          "RESETTING",
          generation,
          "RESETTING",
          generation,
          null,
          generation,
          null,
          false,
          null,
          null,
          null,
          false);
    }

    private ValidationRunStateObservation failedAtGeneration(long generation) {
      return new ValidationRunStateObservation(
          "FAILED",
          generation,
          "FAILED",
          generation,
          null,
          generation,
          null,
          false,
          null,
          null,
          null,
          false);
    }

    private ValidationRunStateObservation absentBeforeReset() {
      return new ValidationRunStateObservation(
          "READY",
          0L,
          "READY",
          0L,
          null,
          null,
          null,
          false,
          null,
          null,
          null,
          false);
    }

    private ValidationRunStateObservation pristineObservation() {
      return new ValidationRunStateObservation(
          "UNINITIALIZED",
          0L,
          "UNINITIALIZED",
          0L,
          null,
          0L,
          null,
          false,
          null,
          null,
          null,
          false);
    }

    private ValidationRunStartRequest request(long generation) {
      return new ValidationRunStartRequest(
          runId,
          generation,
          "task7-request-fingerprint",
          "seed-task7",
          NOW,
          Map.of("mode", "SIMPLE"),
          Map.of("USDT", new BigDecimal("50000.00000000")),
          Map.of("positionMode", "HEDGE"),
          List.of(Map.of("sequence", 1L)),
          List.of(),
          BigDecimal.ONE);
    }

    private static Object invoke(Supplier<?> supplier) {
      return supplier.get();
    }
  }

  private static ValidationRunStateSnapshot progressSnapshot(
      Fixture fixture,
      long completedTickSequence
  ) {
    return new ValidationRunStateSnapshot(
        fixture.runId,
        1L,
        fixture.request.requestFingerprint(),
        ValidationRunState.RUNNING,
        false,
        false,
        completedTickSequence,
        NOW.plusSeconds(completedTickSequence),
        0L,
        null,
        NOW.plusSeconds(completedTickSequence),
        false);
  }
}
