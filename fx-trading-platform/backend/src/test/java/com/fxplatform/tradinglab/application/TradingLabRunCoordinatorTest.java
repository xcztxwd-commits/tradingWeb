package com.fxplatform.tradinglab.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.tradinglab.client.ValidationBackendExchange;
import com.fxplatform.tradinglab.client.ValidationBackendClient;
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
import com.fxplatform.tradinglab.queue.TradingLabRunCoordinator;
import com.fxplatform.tradinglab.queue.TradingLabRunClaim;
import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabFencedReportWriter;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import com.fxplatform.tradinglab.repository.TradingLabWorkerRunSnapshot;
import com.fxplatform.tradinglab.state.TradingLabRunState;
import com.fxplatform.tradinglab.state.TradingLabRunTransitionService;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TradingLabRunCoordinatorTest {

  @Test
  void concreteCoordinatorPreservesTheFrozenQueueSeam() {
    assertThat(TradingLabRunCoordinator.class)
        .isAssignableFrom(DefaultTradingLabRunCoordinator.class);
  }

  @Test
  void startsExactlyOnceMirrorsTerminalEventAndClosesReportBeforeTerminalState() {
    UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000711");
    UUID scenarioId = UUID.fromString("00000000-0000-0000-0000-000000000712");
    UUID reportId = UUID.fromString("00000000-0000-0000-0000-000000000713");
    UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000714");
    String owner = "worker:coordinator-test";
    Instant now = Instant.parse("2026-07-23T16:00:00Z");
    AtomicReference<TradingLabRunState> state =
        new AtomicReference<>(TradingLabRunState.QUEUED);
    AtomicLong version = new AtomicLong(7L);
    AtomicLong validationCursor = new AtomicLong();
    AtomicLong evidenceSequence = new AtomicLong();
    List<String> order = new ArrayList<>();
    Map<String, TradingLabCoordinatorEvidence> intents = new LinkedHashMap<>();

    TradingLabCoordinatorRunLoader loader = mock(TradingLabCoordinatorRunLoader.class);
    TradingLabRunRepository runs = mock(TradingLabRunRepository.class);
    when(runs.advanceFencedProcessedTicks(runId, owner, 1L)).thenReturn(1);
    when(loader.requireLive(any(TradingLabRunClaim.class))).thenAnswer(ignored -> context(
        runId, scenarioId, reportId, actorId, owner, now, state.get(), version.get()));
    when(loader.requireLive(runId, owner)).thenAnswer(ignored -> context(
        runId, scenarioId, reportId, actorId, owner, now, state.get(), version.get()));

    TradingLabRunTransitionService transitions = mock(TradingLabRunTransitionService.class);
    when(transitions.transitionFenced(any(), eq(owner))).thenAnswer(invocation -> {
      var command = (com.fxplatform.tradinglab.state.RunTransitionCommand)
          invocation.getArgument(0);
      assertThat(state.get()).isEqualTo(command.expected());
      state.set(command.target());
      version.incrementAndGet();
      order.add("transition:" + command.target());
      return null;
    });

    TradingLabCoordinatorEvidenceStore evidence = mock(
        TradingLabCoordinatorEvidenceStore.class);
    when(evidence.appendIntent(eq(runId), eq(owner), anyString(), any()))
        .thenAnswer(invocation -> {
          String logicalKey = invocation.getArgument(2);
          TradingLabCoordinatorEvidence existing = intents.get(logicalKey);
          if (existing != null) {
            return existing;
          }
          TradingLabCoordinatorEvidence appended = journalEvidence(
              runId,
              evidenceSequence.getAndIncrement(),
              TradingLabCoordinatorEvidenceStore.HTTP_INTENT,
              Map.of("evidence", invocation.getArgument(3)));
          intents.put(logicalKey, appended);
          return appended;
        });
    when(evidence.findIntent(eq(runId), eq(owner), anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(
            intents.get(invocation.getArgument(2))));
    when(evidence.findCleanupIntentForRecovery(
        eq(runId), eq(owner), anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(
            intents.get(invocation.getArgument(2))));
    when(evidence.appendHttpResult(
        eq(runId), eq(owner), anyString(), any(), any()))
        .thenAnswer(invocation -> {
          ValidationHttpResult result = invocation.getArgument(3);
          long sequence = evidenceSequence.getAndIncrement();
          return journalEvidence(
              runId,
              sequence,
              TradingLabCoordinatorEvidenceStore.HTTP_RESULT,
              Map.of("evidence", result.withSequence(sequence).toSafeMap()));
        });
    when(evidence.appendValidationEvent(eq(runId), eq(owner), any()))
        .thenAnswer(invocation -> {
          ValidationRunEvent event = invocation.getArgument(2);
          validationCursor.set(event.sequence());
          return journalEvidence(
              runId,
              evidenceSequence.getAndIncrement(),
              TradingLabCoordinatorEvidenceStore.VALIDATION_EVENT,
              Map.of("validationSequence", event.sequence(), "evidence", event.toSafeMap()));
        });
    when(evidence.lastValidationSequence(runId, owner))
        .thenAnswer(ignored -> validationCursor.get());
    when(evidence.findHttpResult(runId, owner, "start")).thenReturn(Optional.empty());

    TradingLabEvidenceReportProjector projector = mock(TradingLabEvidenceReportProjector.class);
    TradingLabValidationExchangeRecorder recorder = new TradingLabValidationExchangeRecorder(
        loader, evidence, projector);
    TradingLabRunReportInitializer initializer = mock(TradingLabRunReportInitializer.class);
    TradingLabValidationStartRequestFactory requestFactory =
        mock(TradingLabValidationStartRequestFactory.class);
    ValidationRunStartRequest startRequest = startRequest(runId, now);
    when(requestFactory.create(any(), eq(1L))).thenReturn(startRequest);

    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    doAnswer(ignored -> {
      order.add("report:complete");
      return null;
    }).when(writer).complete(any());

    ValidationBackendClient client = mock(ValidationBackendClient.class);
    ValidationResetReceipt initialReset = resetReceipt(1L, now);
    ValidationResetReceipt finalReset = resetReceipt(2L, now.plusSeconds(10));
    when(client.reset(any())).thenReturn(
        exchange(initialReset, "POST", "/internal/validation/reset", now),
        exchange(finalReset, "POST", "/internal/validation/reset", now.plusSeconds(10)));
    when(client.startRun(startRequest)).thenReturn(exchange(
        new ValidationRunAccepted(runId, startRequest.requestFingerprint(),
            ValidationRunState.ACCEPTED),
        "POST",
        "/internal/validation/runs",
        now.plusSeconds(1)));
    ValidationRunStateSnapshot running = new ValidationRunStateSnapshot(
        runId,
        1L,
        startRequest.requestFingerprint(),
        ValidationRunState.RUNNING,
        false,
        false,
        0L,
        now.plusSeconds(1),
        0L,
        null,
        now.plusSeconds(1),
        false);
    ValidationRunStateSnapshot terminal = new ValidationRunStateSnapshot(
        runId,
        1L,
        startRequest.requestFingerprint(),
        ValidationRunState.COMPLETED,
        false,
        false,
        1L,
        now.plusSeconds(2),
        1L,
        null,
        now.plusSeconds(3),
        true);
    ValidationRunStateObservation observation = observation(terminal);
    when(client.state(runId)).thenReturn(
        exchange(pristineObservation(), "GET", "/internal/validation/state?runId=" + runId,
            now),
        exchange(observation(running), "GET", "/internal/validation/state?runId=" + runId,
            now.plusSeconds(2)),
        exchange(observation, "GET", "/internal/validation/state?runId=" + runId,
            now.plusSeconds(3)));
    ValidationRunEvent terminalEvent = new ValidationRunEvent(
        runId,
        1L,
        "state:completed",
        "event-fingerprint",
        "RUN_STATE_CHANGED",
        now.plusSeconds(2),
        "correlation-terminal",
        Map.of("state", "COMPLETED"));
    when(client.eventsAfter(runId, 0L)).thenReturn(exchange(
        new ValidationEventPage(List.of(terminalEvent), 1L, true, false),
        "GET",
        "/internal/validation/runs/" + runId + "/events?afterSequence=0&limit=1",
        now.plusSeconds(2)));

    DefaultTradingLabRunCoordinator coordinator = new DefaultTradingLabRunCoordinator(
        loader,
        runs,
        transitions,
        evidence,
        projector,
        recorder,
        initializer,
        requestFactory,
        writer,
        client);

    coordinator.coordinate(new TradingLabRunClaim(
        runId,
        TradingLabRunState.QUEUED,
        7L,
        1L,
        owner,
        now.plusSeconds(30)));

    var bootstrap = inOrder(initializer, projector);
    bootstrap.verify(initializer).initialize(runId, owner);
    bootstrap.verify(projector).replayMissing(runId, owner);
    verify(client, times(1)).startRun(startRequest);
    verify(client, times(2)).reset(any(ValidationResetRequest.class));
    verify(runs).advanceFencedProcessedTicks(runId, owner, 1L);
    assertThat(validationCursor.get()).isEqualTo(1L);
    assertThat(state.get()).isEqualTo(TradingLabRunState.COMPLETED);
    assertThat(order).containsSubsequence(
        "transition:RESETTING",
        "transition:RUNNING",
        "transition:CLEANING",
        "report:complete",
        "transition:COMPLETED");
  }

  private static TradingLabCoordinatorRunContext context(
      UUID runId,
      UUID scenarioId,
      UUID reportId,
      UUID actorId,
      String owner,
      Instant now,
      TradingLabRunState state,
      long version
  ) {
    return new TradingLabCoordinatorRunContext(new TradingLabWorkerRunSnapshot(
        runId,
        scenarioId,
        reportId,
        "WRITING",
        state.name(),
        version,
        false,
        false,
        now,
        now,
        0L,
        1L,
        BigDecimal.ONE,
        "{}",
        "{}",
        "a".repeat(64),
        "model-v1",
        "symbols-v1",
        "code-v1",
        actorId), owner);
  }

  private static TradingLabCoordinatorEvidence journalEvidence(
      UUID runId,
      long sequence,
      String type,
      Map<String, Object> payload
  ) {
    return new TradingLabCoordinatorEvidence(
        UUID.nameUUIDFromBytes((runId + ":" + sequence).getBytes()),
        runId,
        sequence,
        type,
        null,
        Instant.parse("2026-07-23T16:00:00Z").plusMillis(sequence),
        null,
        payload);
  }

  private static ValidationRunStartRequest startRequest(UUID runId, Instant now) {
    return new ValidationRunStartRequest(
        runId,
        1L,
        "request-fingerprint",
        "seed-task7",
        now,
        Map.of("mode", "SIMPLE"),
        Map.of("USDT", new BigDecimal("50000.00000000")),
        Map.of("positionMode", "HEDGE"),
        List.of(Map.of("sequence", 1L)),
        List.of(),
        BigDecimal.ONE);
  }

  private static ValidationResetReceipt resetReceipt(long generation, Instant at) {
    return new ValidationResetReceipt(
        ValidationResetReceipt.Status.SUCCEEDED,
        "fx_validation_task7",
        generation,
        generation,
        at,
        at.plusMillis(1),
        List.of(),
        null);
  }

  private static ValidationRunStateObservation observation(
      ValidationRunStateSnapshot run
  ) {
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
        Map.of("virtualTime", run.virtualTime().toString()),
        0L,
        true);
  }

  private static ValidationRunStateObservation pristineObservation() {
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

  private static <T> ValidationBackendExchange<T> exchange(
      T data,
      String method,
      String path,
      Instant at
  ) {
    ValidationHttpResult result = new ValidationHttpResult(
        0L,
        "validation",
        method,
        URI.create("http://127.0.0.1:18087" + path),
        null,
        at,
        200,
        Duration.ofMillis(1),
        Map.of(),
        Map.of("ok", true),
        UUID.randomUUID().toString(),
        null,
        null);
    return new ValidationBackendExchange<>(data, result, mock(SafeTradingLabHttpTrace.class));
  }
}
