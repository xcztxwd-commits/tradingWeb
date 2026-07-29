package com.fxplatform.tradinglab.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.tradinglab.application.TradingLabCoordinatorRunContext;
import com.fxplatform.tradinglab.application.TradingLabCoordinatorRunLoader;
import com.fxplatform.tradinglab.application.TradingLabEvidenceReportProjector;
import com.fxplatform.tradinglab.application.TradingLabValidationExchangeRecorder;
import com.fxplatform.tradinglab.client.ValidationBackendExchange;
import com.fxplatform.tradinglab.client.ValidationHttpResult;
import com.fxplatform.tradinglab.client.ValidationRunEvent;
import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabFencedReportWriter;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceInput;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizer;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizerTestFactory;
import com.fxplatform.tradinglab.report.TradingLabReportSection;
import com.fxplatform.tradinglab.report.TradingLabReportWriteFence;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TradingLabEventMirrorRecoveryTest {

  private static final String OWNER = "worker-a";

  @Test
  void recoveryUsesBothSectionCursorsAndRepairsAHttpTraceMissingBehindLifecycle() {
    UUID runId = UUID.randomUUID();
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, UUID.randomUUID(), OWNER);
    TradingLabCoordinatorRunLoader runLoader = liveLoader(runId, fence);
    TradingLabCoordinatorEvidenceStore evidenceStore =
        mock(TradingLabCoordinatorEvidenceStore.class);
    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    TradingLabHttpTraceSanitizer sanitizer = mock(TradingLabHttpTraceSanitizer.class);
    SafeTradingLabHttpTrace rebuiltTrace = mock(SafeTradingLabHttpTrace.class);
    TradingLabCoordinatorEvidence missingTrace = httpEvidence(runId, 2L);
    TradingLabCoordinatorEvidence alreadyMirrored = lifecycleEvidence(runId, 3L);

    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE))).thenReturn(3L);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE))).thenReturn(1L);
    whenDedicatedCursors(writer, 3L);
    when(evidenceStore.reportProjectionCandidates(
        eq(runId), eq(OWNER), any(), eq(200)))
        .thenReturn(List.of(missingTrace, alreadyMirrored));
    when(sanitizer.sanitize(any(TradingLabHttpTraceInput.class)))
        .thenReturn(rebuiltTrace);

    projector(runLoader, evidenceStore, writer, sanitizer)
        .replayMissing(runId, OWNER);

    InOrder recoveryOrder = org.mockito.Mockito.inOrder(writer);
    recoveryOrder.verify(writer).flush(any(TradingLabReportWriteFence.class));
    recoveryOrder.verify(writer).highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE));
    recoveryOrder.verify(writer).highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE));
    verify(writer, never()).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE),
        anyLong(),
        any());
    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE),
        eq(2L),
        same(rebuiltTrace));
    verify(writer, times(2)).flush(any(TradingLabReportWriteFence.class));
  }

  @Test
  void recoveryAlsoRepairsLifecycleMissingBehindAnAlreadyDurableHttpTrace() {
    UUID runId = UUID.randomUUID();
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, UUID.randomUUID(), OWNER);
    TradingLabCoordinatorRunLoader runLoader = liveLoader(runId, fence);
    TradingLabCoordinatorEvidenceStore evidenceStore =
        mock(TradingLabCoordinatorEvidenceStore.class);
    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    TradingLabHttpTraceSanitizer sanitizer = mock(TradingLabHttpTraceSanitizer.class);
    TradingLabCoordinatorEvidence missingLifecycle = lifecycleEvidence(runId, 2L);
    TradingLabCoordinatorEvidence traceAlreadyDurable = httpEvidence(runId, 3L);

    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE))).thenReturn(1L);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE))).thenReturn(3L);
    whenDedicatedCursors(writer, 3L);
    when(evidenceStore.reportProjectionCandidates(
        eq(runId), eq(OWNER), any(), eq(200)))
        .thenReturn(List.of(missingLifecycle, traceAlreadyDurable));

    projector(runLoader, evidenceStore, writer, sanitizer)
        .replayMissing(runId, OWNER);

    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE),
        eq(2L),
        eq(missingLifecycle.toLifecycleValue()));
    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE),
        eq(3L),
        eq(traceAlreadyDurable.toLifecycleValue()));
    verify(writer, never()).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE),
        anyLong(),
        any());
    verifyNoInteractions(sanitizer);
  }

  @Test
  void liveProjectionUsesTheCanonicalDurableEnvelopeAndInheritsOriginalSecrets() {
    UUID runId = UUID.randomUUID();
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, UUID.randomUUID(), OWNER);
    TradingLabCoordinatorRunLoader runLoader = liveLoader(runId, fence);
    TradingLabCoordinatorEvidenceStore evidenceStore =
        mock(TradingLabCoordinatorEvidenceStore.class);
    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    TradingLabHttpTraceSanitizer sanitizer = mock(TradingLabHttpTraceSanitizer.class);
    SafeTradingLabHttpTrace originalTrace = mock(SafeTradingLabHttpTrace.class);
    SafeTradingLabHttpTrace canonicalTrace = mock(SafeTradingLabHttpTrace.class);
    TradingLabCoordinatorEvidence evidence = httpEvidence(runId, 7L);
    when(sanitizer.sanitizeWithInheritedSecrets(
        any(TradingLabHttpTraceInput.class), same(originalTrace)))
        .thenReturn(canonicalTrace);

    projector(runLoader, evidenceStore, writer, sanitizer)
        .project(runId, OWNER, evidence, originalTrace);

    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE),
        eq(7L),
        eq(evidence.toLifecycleValue()));
    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE),
        eq(7L),
        same(canonicalTrace));
    verify(writer).flush(any(TradingLabReportWriteFence.class));
    verify(sanitizer).sanitizeWithInheritedSecrets(
        any(TradingLabHttpTraceInput.class), same(originalTrace));
  }

  @Test
  void liveProjectionRoutesValidationEventsIntoTheirDedicatedSections() {
    UUID runId = UUID.randomUUID();
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, UUID.randomUUID(), OWNER);
    TradingLabCoordinatorRunLoader runLoader = liveLoader(runId, fence);
    TradingLabCoordinatorEvidenceStore evidenceStore =
        mock(TradingLabCoordinatorEvidenceStore.class);
    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    TradingLabHttpTraceSanitizer sanitizer = mock(TradingLabHttpTraceSanitizer.class);
    ValidationRunEvent marketTick = validationEvent(
        runId, 1L, "MARKET_TICK", Map.of("tickSequence", 1L));
    ValidationRunEvent stateSnapshot = validationEvent(
        runId, 2L, "STATE_SNAPSHOT", Map.of(
            "tickSequence", 1L,
            "state", Map.of("orders", List.of())));
    ValidationRunEvent checkpoint = validationEvent(
        runId, 3L, "CHECKPOINT", Map.of(
            "tickSequence", 1L,
            "state", Map.of("positions", List.of())));
    ValidationRunEvent failedState = validationEvent(
        runId, 4L, "RUN_STATE_CHANGED", Map.of(
            "state", "FAILED",
            "reason", "VALIDATION_RUN_FAILED"));
    ValidationRunEvent failedApi = validationEvent(
        runId, 5L, "API_TRACE", Map.of(
            "operation", "PUBLIC_ACTION",
            "outcome", "FAILED",
            "status", 500));
    ValidationRunEvent expectedError = validationEvent(
        runId, 6L, "API_TRACE", Map.of(
            "operation", "PUBLIC_ACTION",
            "outcome", "EXPECTED_ERROR",
            "status", 400));
    ValidationRunEvent executionFailed = validationEvent(
        runId, 7L, "RUN_EXECUTION_FAILED", Map.of(
            "failurePoint", Map.of(
                "operation", "PUBLIC_ACTION",
                "actionId", "00000000-0000-0000-0000-000000000091",
                "tickSequence", 1L,
                "actionSequence", 1L,
                "status", 409,
                "code", "INSUFFICIENT_BALANCE"),
            "unexecuted", List.of()));

    TradingLabEvidenceReportProjector projector =
        projector(runLoader, evidenceStore, writer, sanitizer);
    projector.project(runId, OWNER, validationEvidence(runId, 10L, marketTick));
    projector.project(runId, OWNER, validationEvidence(runId, 11L, stateSnapshot));
    projector.project(runId, OWNER, validationEvidence(runId, 12L, checkpoint));
    projector.project(runId, OWNER, validationEvidence(runId, 13L, failedState));
    projector.project(runId, OWNER, validationEvidence(runId, 14L, failedApi));
    projector.project(runId, OWNER, validationEvidence(runId, 15L, expectedError));
    projector.project(runId, OWNER, validationEvidence(runId, 16L, executionFailed));

    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.MARKET_TICKS),
        eq(10L),
        eq(marketTick.toSafeMap()));
    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.CHECKPOINTS),
        eq(11L),
        eq(stateSnapshot.toSafeMap()));
    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.CHECKPOINTS),
        eq(12L),
        eq(checkpoint.toSafeMap()));
    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.ERRORS),
        eq(13L),
        eq(failedState.toSafeMap()));
    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.ERRORS),
        eq(14L),
        eq(failedApi.toSafeMap()));
    verify(writer, never()).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.ERRORS),
        eq(15L),
        any());
    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.ERRORS),
        eq(16L),
        eq(executionFailed.toSafeMap()));
    verifyNoInteractions(sanitizer);
  }

  @Test
  void validationApiTraceIsResealedAndNeverAppendedAsARawMap() {
    UUID runId = UUID.randomUUID();
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, UUID.randomUUID(), OWNER);
    TradingLabCoordinatorRunLoader runLoader = liveLoader(runId, fence);
    TradingLabCoordinatorEvidenceStore evidenceStore =
        mock(TradingLabCoordinatorEvidenceStore.class);
    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    TradingLabHttpTraceSanitizer sanitizer =
        TradingLabHttpTraceSanitizerTestFactory.create(List.of(), 1_048_576);
    Map<String, Object> trace = validationHopTrace();
    ValidationRunEvent apiTrace = validationEvent(
        runId,
        7L,
        "API_TRACE",
        Map.ofEntries(
            Map.entry("operation", "PUBLIC_ACTION"),
            Map.entry("outcome", "SUCCEEDED"),
            Map.entry("traceScope", "HTTP_HOP"),
            Map.entry("hopIndex", 0),
            Map.entry("method", "POST"),
            Map.entry("path", "/api/trading/orders"),
            Map.entry("status", 200),
            Map.entry("durationMillis", 5L),
            Map.entry("trace", trace)));

    projector(runLoader, evidenceStore, writer, sanitizer)
        .project(runId, OWNER, validationEvidence(runId, 17L, apiTrace));

    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE),
        eq(17L),
        argThat(value ->
            value instanceof SafeTradingLabHttpTrace safe
                && safe.toSafeMap().equals(trace)));
    verify(writer, never()).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE),
        eq(17L),
        eq(apiTrace.toSafeMap()));
  }

  @Test
  void recoveryUsesTheApiTraceCursorForValidationHopEvidence() {
    UUID runId = UUID.randomUUID();
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, UUID.randomUUID(), OWNER);
    TradingLabCoordinatorRunLoader runLoader = liveLoader(runId, fence);
    TradingLabCoordinatorEvidenceStore evidenceStore =
        mock(TradingLabCoordinatorEvidenceStore.class);
    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    TradingLabHttpTraceSanitizer sanitizer = mock(TradingLabHttpTraceSanitizer.class);
    SafeTradingLabHttpTrace sealed = mock(SafeTradingLabHttpTrace.class);
    Map<String, Object> trace = validationHopTrace();
    when(sealed.toSafeMap()).thenReturn(trace);
    when(sanitizer.sanitize(any(TradingLabHttpTraceInput.class))).thenReturn(sealed);
    ValidationRunEvent apiTrace = validationEvent(
        runId,
        7L,
        "API_TRACE",
        Map.ofEntries(
            Map.entry("operation", "PUBLIC_ACTION"),
            Map.entry("outcome", "SUCCEEDED"),
            Map.entry("traceScope", "HTTP_HOP"),
            Map.entry("hopIndex", 0),
            Map.entry("method", "POST"),
            Map.entry("path", "/api/trading/orders"),
            Map.entry("status", 200),
            Map.entry("durationMillis", 5L),
            Map.entry("trace", trace)));
    TradingLabCoordinatorEvidence evidence = validationEvidence(runId, 17L, apiTrace);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE))).thenReturn(20L);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE))).thenReturn(16L);
    whenDedicatedCursors(writer, 20L);
    when(evidenceStore.reportProjectionCandidates(
        eq(runId),
        eq(OWNER),
        argThat(cursors ->
            cursors.lifecycle() == 20L && cursors.apiTrace() == 16L),
        eq(200))).thenReturn(List.of(evidence));

    projector(runLoader, evidenceStore, writer, sanitizer)
        .replayMissing(runId, OWNER);

    verify(writer, never()).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE),
        eq(17L),
        any());
    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE),
        eq(17L),
        same(sealed));
  }

  @Test
  void recoveryRepairsADedicatedSectionMissingBehindLifecycle() {
    UUID runId = UUID.randomUUID();
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, UUID.randomUUID(), OWNER);
    TradingLabCoordinatorRunLoader runLoader = liveLoader(runId, fence);
    TradingLabCoordinatorEvidenceStore evidenceStore =
        mock(TradingLabCoordinatorEvidenceStore.class);
    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    TradingLabHttpTraceSanitizer sanitizer = mock(TradingLabHttpTraceSanitizer.class);
    ValidationRunEvent marketTick = validationEvent(
        runId, 2L, "MARKET_TICK", Map.of("tickSequence", 2L));
    TradingLabCoordinatorEvidence missingMarketTick =
        validationEvidence(runId, 2L, marketTick);
    TradingLabCoordinatorEvidence alreadyMirrored = lifecycleEvidence(runId, 3L);

    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE))).thenReturn(3L);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE))).thenReturn(3L);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.MARKET_TICKS))).thenReturn(1L);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.CHECKPOINTS))).thenReturn(3L);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.ERRORS))).thenReturn(3L);
    when(evidenceStore.reportProjectionCandidates(
        eq(runId), eq(OWNER), any(), eq(200)))
        .thenReturn(List.of(missingMarketTick, alreadyMirrored));

    projector(runLoader, evidenceStore, writer, sanitizer)
        .replayMissing(runId, OWNER);

    verify(writer, never()).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE),
        anyLong(),
        any());
    verify(writer).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.MARKET_TICKS),
        eq(2L),
        eq(marketTick.toSafeMap()));
    verifyNoInteractions(sanitizer);
  }

  @Test
  void successfulRecoveryDoesNotRescanTheWholeJournalForAnEmptyErrorsSection() {
    UUID runId = UUID.randomUUID();
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, UUID.randomUUID(), OWNER);
    TradingLabCoordinatorRunLoader runLoader = liveLoader(runId, fence);
    TradingLabCoordinatorEvidenceStore evidenceStore =
        mock(TradingLabCoordinatorEvidenceStore.class);
    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    TradingLabHttpTraceSanitizer sanitizer = mock(TradingLabHttpTraceSanitizer.class);

    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE))).thenReturn(500L);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE))).thenReturn(500L);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.MARKET_TICKS))).thenReturn(500L);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.CHECKPOINTS))).thenReturn(500L);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.ERRORS))).thenReturn(-1L);

    projector(runLoader, evidenceStore, writer, sanitizer)
        .replayMissing(runId, OWNER);

    verify(evidenceStore).reportProjectionCandidates(
        runId,
        OWNER,
        new TradingLabCoordinatorEvidenceStore.ReportProjectionCursors(
            500L, 500L, 500L, 500L, -1L),
        200);
    verify(evidenceStore, never()).journalAfter(runId, OWNER, -1L, 200);
    verifyNoInteractions(sanitizer);
  }

  @Test
  void recoveryAdvancesItsFilteredCursorAfterEachDurablePage() {
    UUID runId = UUID.randomUUID();
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, UUID.randomUUID(), OWNER);
    TradingLabCoordinatorRunLoader runLoader = liveLoader(runId, fence);
    TradingLabCoordinatorEvidenceStore evidenceStore =
        mock(TradingLabCoordinatorEvidenceStore.class);
    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    TradingLabHttpTraceSanitizer sanitizer = mock(TradingLabHttpTraceSanitizer.class);
    List<TradingLabCoordinatorEvidence> firstPage = LongStream.range(0L, 200L)
        .mapToObj(sequence -> plainEvidence(runId, sequence))
        .toList();
    TradingLabCoordinatorEvidence finalEvidence = plainEvidence(runId, 200L);

    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE))).thenReturn(-1L);
    when(writer.highestDurableSourceSequence(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.API_TRACE))).thenReturn(500L);
    whenDedicatedCursors(writer, 500L);
    when(evidenceStore.reportProjectionCandidates(
        eq(runId), eq(OWNER), any(), eq(200)))
        .thenAnswer(invocation -> {
          TradingLabCoordinatorEvidenceStore.ReportProjectionCursors cursors =
              invocation.getArgument(2);
          if (cursors.lifecycle() == -1L) {
            return firstPage;
          }
          if (cursors.lifecycle() == 199L) {
            return List.of(finalEvidence);
          }
          throw new AssertionError("Unexpected recovery cursor " + cursors.lifecycle());
        });

    projector(runLoader, evidenceStore, writer, sanitizer)
        .replayMissing(runId, OWNER);

    verify(evidenceStore).reportProjectionCandidates(
        runId,
        OWNER,
        new TradingLabCoordinatorEvidenceStore.ReportProjectionCursors(
            -1L, 500L, 500L, 500L, 500L),
        200);
    verify(evidenceStore).reportProjectionCandidates(
        runId,
        OWNER,
        new TradingLabCoordinatorEvidenceStore.ReportProjectionCursors(
            199L, 500L, 500L, 500L, 500L),
        200);
    verify(writer, times(201)).appendEvent(
        any(TradingLabReportWriteFence.class),
        eq(TradingLabReportSection.LIFECYCLE),
        anyLong(),
        any());
    verify(writer, times(3)).flush(any(TradingLabReportWriteFence.class));
    verifyNoInteractions(sanitizer);
  }

  @Test
  void latestDurableStateComesOnlyFromTheGuardedStateBearingJournalEvent() {
    UUID runId = UUID.randomUUID();
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, UUID.randomUUID(), OWNER);
    TradingLabCoordinatorRunLoader runLoader = liveLoader(runId, fence);
    TradingLabCoordinatorEvidenceStore evidenceStore =
        mock(TradingLabCoordinatorEvidenceStore.class);
    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    TradingLabHttpTraceSanitizer sanitizer = mock(TradingLabHttpTraceSanitizer.class);
    Map<String, Object> state = Map.of(
        "orders", List.of(Map.of("id", "order-1")),
        "positions", List.of());
    ValidationRunEvent checkpoint = validationEvent(
        runId,
        9L,
        "CHECKPOINT",
        Map.of(
            "tickSequence", 4L,
            "state", state));
    TradingLabCoordinatorEvidence durable =
        validationEvidence(runId, 27L, checkpoint);
    when(evidenceStore.latestStateBearingValidationEvent(runId, OWNER))
        .thenReturn(Optional.of(durable));

    assertThat(projector(runLoader, evidenceStore, writer, sanitizer)
        .latestDurableState(runId, OWNER))
        .contains(new TradingLabEvidenceReportProjector.DurableValidationState(
            27L,
            9L,
            "CHECKPOINT",
            checkpoint.virtualTime(),
            checkpoint.correlationId(),
            state));
    verifyNoInteractions(writer, sanitizer);
  }

  @Test
  void exchangeRecorderCarriesTheOriginalSealedTraceIntoLiveProjection() {
    UUID runId = UUID.randomUUID();
    TradingLabCoordinatorRunLoader runLoader = mock(TradingLabCoordinatorRunLoader.class);
    TradingLabCoordinatorEvidenceStore evidenceStore =
        mock(TradingLabCoordinatorEvidenceStore.class);
    TradingLabEvidenceReportProjector projector =
        mock(TradingLabEvidenceReportProjector.class);
    SafeTradingLabHttpTrace originalTrace = mock(SafeTradingLabHttpTrace.class);
    ValidationHttpResult unsequenced = httpResult(0L);
    TradingLabCoordinatorEvidence sequenced = httpEvidence(runId, 9L);
    ValidationBackendExchange<String> exchange =
        new ValidationBackendExchange<>("ok", unsequenced, originalTrace);

    when(evidenceStore.appendHttpResult(
        runId, OWNER, "query:trace-1", unsequenced, originalTrace))
        .thenReturn(sequenced);

    ValidationBackendExchange<String> recorded =
        new TradingLabValidationExchangeRecorder(runLoader, evidenceStore, projector)
            .query(runId, OWNER, () -> exchange);

    assertThat(recorded.result().sequence()).isEqualTo(9L);
    assertThat(recorded.reportTrace()).isSameAs(originalTrace);
    verify(projector).project(runId, OWNER, sequenced, originalTrace);
  }

  private static TradingLabEvidenceReportProjector projector(
      TradingLabCoordinatorRunLoader runLoader,
      TradingLabCoordinatorEvidenceStore evidenceStore,
      TradingLabFencedReportWriter writer,
      TradingLabHttpTraceSanitizer sanitizer
  ) {
    return new TradingLabEvidenceReportProjector(
        runLoader, evidenceStore, writer, sanitizer);
  }

  private static TradingLabCoordinatorRunLoader liveLoader(
      UUID runId,
      TradingLabReportWriteFence fence
  ) {
    TradingLabCoordinatorRunLoader loader = mock(TradingLabCoordinatorRunLoader.class);
    TradingLabCoordinatorRunContext context = mock(TradingLabCoordinatorRunContext.class);
    when(context.reportFence()).thenReturn(fence);
    when(loader.requireLive(runId, OWNER)).thenReturn(context);
    return loader;
  }

  private static void whenDedicatedCursors(
      TradingLabFencedReportWriter writer,
      long cursor
  ) {
    for (TradingLabReportSection section : List.of(
        TradingLabReportSection.MARKET_TICKS,
        TradingLabReportSection.CHECKPOINTS,
        TradingLabReportSection.ERRORS)) {
      when(writer.highestDurableSourceSequence(
          any(TradingLabReportWriteFence.class), eq(section))).thenReturn(cursor);
    }
  }

  private static TradingLabCoordinatorEvidence httpEvidence(UUID runId, long sequence) {
    return new TradingLabCoordinatorEvidence(
        UUID.randomUUID(),
        runId,
        sequence,
        TradingLabCoordinatorEvidenceStore.HTTP_RESULT,
        Instant.parse("2026-07-23T00:00:00Z"),
        Instant.parse("2026-07-23T00:00:01Z"),
        "correlation-1",
        Map.of("evidence", httpResult(sequence).toSafeMap()));
  }

  private static TradingLabCoordinatorEvidence plainEvidence(UUID runId, long sequence) {
    return new TradingLabCoordinatorEvidence(
        UUID.randomUUID(),
        runId,
        sequence,
        TradingLabCoordinatorEvidenceStore.HTTP_INTENT,
        null,
        Instant.parse("2026-07-23T00:00:01Z").plusSeconds(sequence),
        null,
        Map.of("evidence", Map.of("step", sequence)));
  }

  private static TradingLabCoordinatorEvidence lifecycleEvidence(
      UUID runId,
      long sequence
  ) {
    return validationEvidence(
        runId,
        sequence,
        validationEvent(
            runId,
            sequence,
            "RUN_STATE_CHANGED",
            Map.of("state", "RUNNING", "reason", "TEST")));
  }

  private static TradingLabCoordinatorEvidence validationEvidence(
      UUID runId,
      long sourceSequence,
      ValidationRunEvent event
  ) {
    return new TradingLabCoordinatorEvidence(
        UUID.randomUUID(),
        runId,
        sourceSequence,
        TradingLabCoordinatorEvidenceStore.VALIDATION_EVENT,
        event.virtualTime(),
        Instant.parse("2026-07-23T00:00:01Z"),
        event.correlationId(),
        Map.of(
            "validationSequence", event.sequence(),
            "evidence", event.toSafeMap()));
  }

  private static ValidationRunEvent validationEvent(
      UUID runId,
      long sequence,
      String type,
      Map<String, Object> payload
  ) {
    return new ValidationRunEvent(
        runId,
        sequence,
        "validation:" + sequence,
        "fingerprint-" + sequence,
        type,
        Instant.parse("2026-07-23T00:00:00Z").plusSeconds(sequence),
        "correlation-" + sequence,
        payload);
  }

  private static ValidationHttpResult httpResult(long sequence) {
    return new ValidationHttpResult(
        sequence,
        "validation",
        "GET",
        URI.create(
            "http://127.0.0.1:18087/internal/validation/state"
                + "?runId=00000000-0000-0000-0000-000000000001"),
        Instant.parse("2026-07-23T00:00:00Z"),
        Instant.parse("2026-07-23T00:00:01Z"),
        200,
        Duration.ofMillis(4),
        Map.of("kind", "state"),
        Map.of("state", "RUNNING"),
        "trace-1",
        "correlation-1",
        null);
  }

  private static Map<String, Object> validationHopTrace() {
    Map<String, Object> request = new java.util.LinkedHashMap<>();
    request.put("sequence", 1L);
    request.put("environment", "validation");
    request.put("method", "POST");
    request.put("url", "http://127.0.0.1:8080/api/trading/orders");
    request.put("virtualTime", null);
    request.put("realTime", "2026-07-23T00:00:00Z");
    request.put("sanitizedRequest", Map.of("symbol", "BTCUSDT"));
    Map<String, Object> response = new java.util.LinkedHashMap<>();
    response.put("status", 200);
    response.put("duration", 5L);
    response.put("traceId", null);
    response.put("correlationId", "correlation-7");
    response.put("recordedException", null);
    response.put("sanitizedResponse", Map.of("code", "OK"));
    Map<String, Object> trace = new java.util.LinkedHashMap<>();
    trace.put("url", "http://127.0.0.1:8080/api/trading/orders");
    trace.put("queryParameters", Map.of());
    trace.put("requestHeaders", Map.of());
    trace.put("requestContentType", "application/json");
    trace.put("requestBody", request);
    trace.put("responseHeaders", Map.of());
    trace.put("responseContentType", "application/json");
    trace.put("responseBody", response);
    trace.put("exception", null);
    trace.put("authentication", null);
    return java.util.Collections.unmodifiableMap(trace);
  }
}
