package com.fxplatform.tradinglab.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.application.TradingLabCoordinatorRunContext;
import com.fxplatform.tradinglab.application.TradingLabCoordinatorRunLoader;
import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import com.fxplatform.tradinglab.application.TradingLabEvidenceReportProjector;
import com.fxplatform.tradinglab.application.TradingLabValidationExchangeRecorder;
import com.fxplatform.tradinglab.client.ThrowableInfo;
import com.fxplatform.tradinglab.client.ValidationBackendExchange;
import com.fxplatform.tradinglab.client.ValidationHttpResult;
import com.fxplatform.tradinglab.client.ValidationRunEvent;
import com.fxplatform.tradinglab.entity.TradingLabRunEventEntity;
import com.fxplatform.tradinglab.report.TradingLabFixedValidationSecretProvider;
import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabFencedReportWriter;
import com.fxplatform.tradinglab.report.TradingLabJournalEvidenceGuard;
import com.fxplatform.tradinglab.report.TradingLabReportProperties;
import com.fxplatform.tradinglab.report.TradingLabReportWriteFence;
import com.fxplatform.tradinglab.repository.TradingLabRunEventRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradingLabCoordinatorEvidenceStoreTest {

  private static final UUID RUN_ID = UUID.fromString(
      "00000000-0000-0000-0000-000000000701");
  private static final String OWNER = "worker:task7";
  private static final Instant NOW = Instant.parse("2026-07-23T15:00:00Z");
  private static final String FIXED_SECRET = "task7-fixed-secret-value";

  private final Map<UUID, TradingLabRunEventEntity> stored = new LinkedHashMap<>();
  private final ObjectMapper json = new ObjectMapper();
  private TradingLabCoordinatorRunLoader loader;
  private TradingLabRunEventRepository repository;
  private TradingLabFencedReportWriter reportWriter;
  private TradingLabJournalEvidenceGuard journalGuard;
  private TradingLabReportWriteFence fence;
  private TradingLabCoordinatorEvidenceStore store;

  @BeforeEach
  void setUp() throws Exception {
    loader = mock(TradingLabCoordinatorRunLoader.class);
    TradingLabCoordinatorRunContext context = mock(TradingLabCoordinatorRunContext.class);
    fence = new TradingLabReportWriteFence(
        RUN_ID,
        UUID.fromString("00000000-0000-0000-0000-000000000702"),
        OWNER);
    when(context.reportFence()).thenReturn(fence);
    when(loader.lockLive(RUN_ID, OWNER)).thenReturn(context);
    when(loader.requireLive(RUN_ID, OWNER)).thenReturn(context);

    repository = mock(TradingLabRunEventRepository.class);
    reportWriter = mock(TradingLabFencedReportWriter.class);
    journalGuard = mock(TradingLabJournalEvidenceGuard.class);
    when(reportWriter.withJournalEvidenceGuard(
        eq(fence), nullable(SafeTradingLabHttpTrace.class), any()))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          Function<TradingLabJournalEvidenceGuard, Object> transaction =
              invocation.getArgument(2);
          return transaction.apply(journalGuard);
        });
    when(repository.findEvidenceById(any())).thenAnswer(invocation ->
        Optional.ofNullable(stored.get(invocation.getArgument(0))));
    when(repository.findPreferredHttpResult(eq(RUN_ID), any()))
        .thenAnswer(invocation -> preferredHttpResult(invocation.getArgument(1)));
    when(repository.findHighestEvidenceSequence(RUN_ID)).thenAnswer(ignored -> stored.values()
        .stream()
        .map(TradingLabRunEventEntity::getSequence)
        .max(Long::compareTo)
        .orElse(null));
    when(repository.findHighestValidationSequence(RUN_ID)).thenAnswer(ignored -> {
      Long highest = null;
      for (TradingLabRunEventEntity entity : stored.values()) {
        if (!TradingLabCoordinatorEvidenceStore.VALIDATION_EVENT.equals(
            entity.getEventType())) {
          continue;
        }
        Object value = json.readValue(entity.getPayloadJson(), Map.class)
            .get("validationSequence");
        long sequence = ((Number) value).longValue();
        highest = highest == null ? sequence : Math.max(highest, sequence);
      }
      return highest;
    });
    when(repository.findHighestValidationHighWatermark(eq(RUN_ID), anyLong()))
        .thenAnswer(invocation -> {
          long generation = invocation.getArgument(1);
          Long highest = null;
          for (TradingLabRunEventEntity entity : stored.values()) {
            if (!TradingLabCoordinatorEvidenceStore.VALIDATION_HIGH_WATERMARK.equals(
                entity.getEventType())) {
              continue;
            }
            Map<?, ?> payload = json.readValue(entity.getPayloadJson(), Map.class);
            if (((Number) payload.get("validationGeneration")).longValue() != generation) {
              continue;
            }
            long watermark = ((Number) payload.get("highWatermark")).longValue();
            highest = highest == null ? watermark : Math.max(highest, watermark);
          }
          return highest;
        });
    when(repository.insert(any(TradingLabRunEventEntity.class))).thenAnswer(invocation -> {
      TradingLabRunEventEntity entity = invocation.getArgument(0);
      stored.put(entity.getId(), entity);
      return 1;
    });
    when(repository.findJournalAfter(eq(RUN_ID), anyLong(), anyInt()))
        .thenAnswer(invocation -> {
          long after = invocation.getArgument(1);
          int limit = invocation.getArgument(2);
          return stored.values().stream()
              .filter(entity -> entity.getSequence() > after)
              .sorted(Comparator.comparingLong(TradingLabRunEventEntity::getSequence))
              .limit(limit)
              .toList();
        });
    when(repository.findLatestStateBearingValidationEvent(RUN_ID))
        .thenAnswer(ignored -> stored.values().stream()
            .filter(entity -> TradingLabCoordinatorEvidenceStore.VALIDATION_EVENT.equals(
                entity.getEventType()))
            .filter(entity -> {
              Object evidence = payload(entity).get("evidence");
              if (!(evidence instanceof Map<?, ?> event)) {
                return false;
              }
              return List.of("STATE_SNAPSHOT", "CHECKPOINT").contains(event.get("type"));
            })
            .max(Comparator.comparingLong(TradingLabRunEventEntity::getSequence)));

    TradingLabReportProperties properties = new TradingLabReportProperties();
    TradingLabFixedValidationSecretProvider fixed =
        mock(TradingLabFixedValidationSecretProvider.class);
    when(fixed.secrets()).thenReturn(List.of(FIXED_SECRET));
    TradingLabEvidenceCanonicalizer canonicalizer = new TradingLabEvidenceCanonicalizer(
        new TradingLabCredentialSanitizer(), fixed, properties);
    store = new TradingLabCoordinatorEvidenceStore(
        loader,
        repository,
        canonicalizer,
        reportWriter,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void allocatesOneGlobalSequenceAndReplaysExactValidationEvents() {
    ValidationRunEvent first = event(1L, "fingerprint-1");
    TradingLabCoordinatorEvidence inserted = store.appendValidationEvent(
        RUN_ID, OWNER, first);
    TradingLabCoordinatorEvidence replay = store.appendValidationEvent(
        RUN_ID, OWNER, first);
    TradingLabCoordinatorEvidence second = store.appendValidationEvent(
        RUN_ID, OWNER, event(2L, "fingerprint-2"));
    TradingLabCoordinatorEvidence intent = store.appendIntent(
        RUN_ID,
        OWNER,
        "pause:7",
        Map.of("echo", FIXED_SECRET));

    assertThat(inserted.sequence()).isZero();
    assertThat(replay).isEqualTo(inserted);
    assertThat(second.sequence()).isEqualTo(1L);
    assertThat(intent.sequence()).isEqualTo(2L);
    assertThat(((Map<?, ?>) intent.payload().get("evidence")).get("echo"))
        .isEqualTo("[REDACTED]");
    assertThat(store.lastValidationSequence(RUN_ID, OWNER)).isEqualTo(2L);
    assertThat(store.journalAfter(RUN_ID, OWNER, -1L, 10))
        .extracting(TradingLabCoordinatorEvidence::sequence)
        .containsExactly(0L, 1L, 2L);
  }

  @Test
  void terminalRecoveryReadsOnlyAnExactCleanupIntentWithoutAReportGuard() {
    TradingLabCoordinatorEvidence intent = store.appendIntent(
        RUN_ID,
        OWNER,
        "cleanup:FAILED",
        Map.of(
            "target", "FAILED",
            "generation", 7L,
            "finalResetRequired", true));
    clearInvocations(loader, repository, reportWriter, journalGuard);

    assertThat(store.findCleanupIntentForRecovery(
        RUN_ID, OWNER, "cleanup:FAILED")).contains(intent);

    verify(loader).lockLive(RUN_ID, OWNER);
    verify(repository).findEvidenceById(intent.id());
    verifyNoInteractions(reportWriter, journalGuard);
  }

  @Test
  void terminalRecoveryRejectsACorruptCleanupIntentFingerprintWithoutAReportGuard()
      throws Exception {
    TradingLabCoordinatorEvidence intent = store.appendIntent(
        RUN_ID,
        OWNER,
        "cleanup:CANCELLED",
        Map.of(
            "target", "CANCELLED",
            "generation", 0L,
            "finalResetRequired", false));
    TradingLabRunEventEntity entity = stored.get(intent.id());
    Map<String, Object> payload = new LinkedHashMap<>(
        json.readValue(entity.getPayloadJson(), Map.class));
    payload.put("sourceFingerprint", "0".repeat(64));
    entity.setPayloadJson(json.writeValueAsString(payload));
    clearInvocations(reportWriter, journalGuard);

    assertThatThrownBy(() -> store.findCleanupIntentForRecovery(
        RUN_ID, OWNER, "cleanup:CANCELLED"))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

    verifyNoInteractions(reportWriter, journalGuard);
  }

  @Test
  void terminalRecoveryRejectsExtraSensitiveCleanupIntentEnvelopeFieldsWithoutAReportGuard()
      throws Exception {
    TradingLabCoordinatorEvidence intent = store.appendIntent(
        RUN_ID,
        OWNER,
        "cleanup:COMPLETED",
        Map.of(
            "target", "COMPLETED",
            "generation", 7L,
            "finalResetRequired", true));
    TradingLabRunEventEntity entity = stored.get(intent.id());
    Map<String, Object> payload = new LinkedHashMap<>(
        json.readValue(entity.getPayloadJson(), Map.class));
    payload.put("accessToken", "injected");
    entity.setPayloadJson(json.writeValueAsString(payload));
    clearInvocations(reportWriter, journalGuard);

    assertThatThrownBy(() -> store.findCleanupIntentForRecovery(
        RUN_ID, OWNER, "cleanup:COMPLETED"))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

    verifyNoInteractions(reportWriter, journalGuard);
  }

  @Test
  void terminalRecoveryRejectsNonCleanupKeysBeforeReadingTheJournal() {
    clearInvocations(loader, repository, reportWriter, journalGuard);

    assertThatThrownBy(() -> store.findCleanupIntentForRecovery(
        RUN_ID, OWNER, "start"))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage("Trading Lab recovery cleanup key is invalid");

    verifyNoInteractions(loader, repository, reportWriter, journalGuard);
  }

  @Test
  void terminalRecoveryRejectsAConflictingPersistedIntentIdentity() {
    TradingLabCoordinatorEvidence intent = store.appendIntent(
        RUN_ID,
        OWNER,
        "cleanup:FAILED",
        Map.of(
            "target", "FAILED",
            "generation", 7L,
            "finalResetRequired", true));
    stored.get(intent.id()).setEventType(TradingLabCoordinatorEvidenceStore.HTTP_RESULT);
    clearInvocations(reportWriter, journalGuard);

    assertThatThrownBy(() -> store.findCleanupIntentForRecovery(
        RUN_ID, OWNER, "cleanup:FAILED"))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));

    verifyNoInteractions(reportWriter, journalGuard);
  }

  @Test
  void rejectsGapsAndSameRemoteSequenceWithDifferentMeaning() {
    store.appendValidationEvent(RUN_ID, OWNER, event(1L, "fingerprint-1"));

    assertThatThrownBy(() -> store.appendValidationEvent(
        RUN_ID, OWNER, event(3L, "fingerprint-3")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("contiguously");
    assertThatThrownBy(() -> store.appendValidationEvent(
        RUN_ID, OWNER, event(1L, "different")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("another meaning");
    assertThat(stored).hasSize(1);
  }

  @Test
  void durableHttpResultUsesTheSameGlobalSequenceAndCarriesItsAssignedId() {
    store.appendIntent(RUN_ID, OWNER, "start", Map.of("runId", RUN_ID.toString()));
    ValidationHttpResult result = new ValidationHttpResult(
        0L,
        "validation",
        "POST",
        URI.create("http://127.0.0.1:18087/internal/validation/runs"),
        null,
        NOW,
        200,
        Duration.ofMillis(7),
        Map.of("body", Map.of("runId", RUN_ID.toString())),
        Map.of("body", Map.of("success", true)),
        "trace-task7",
        "correlation-task7",
        null);

    TradingLabCoordinatorEvidence evidence = store.appendHttpResult(
        RUN_ID, OWNER, "start", result);
    Map<?, ?> safeResult = (Map<?, ?>) evidence.payload().get("evidence");

    assertThat(evidence.sequence()).isEqualTo(1L);
    assertThat(((Number) safeResult.get("sequence")).longValue()).isEqualTo(1L);
    assertThat(store.findHttpResult(RUN_ID, OWNER, "start")).contains(evidence);
  }

  @Test
  void handsTraceRegistryOffAndScansSequencedEvidenceBeforeJournalInsert() {
    SafeTradingLabHttpTrace trace = mock(SafeTradingLabHttpTrace.class);
    ValidationHttpResult result = httpResult(
        "trace-registry-handoff",
        NOW,
        200,
        Duration.ofMillis(3),
        null);

    TradingLabCoordinatorEvidence evidence = store.appendHttpResult(
        RUN_ID, OWNER, "query:registry", result, trace);

    var ordered = inOrder(reportWriter, journalGuard, repository);
    ordered.verify(reportWriter).withJournalEvidenceGuard(
        eq(fence), eq(trace), any());
    ordered.verify(journalGuard).requireSafe(any(Map.class));
    ordered.verify(repository).insert(any(TradingLabRunEventEntity.class));
    assertThat(((Number) ((Map<?, ?>) evidence.payload().get("evidence"))
        .get("sequence")).longValue()).isZero();
  }

  @Test
  void separatesStableIntentFromAttemptsAndPrefersTheLatestSuccessfulResult() {
    TradingLabCoordinatorEvidence intent = store.appendIntent(
        RUN_ID, OWNER, "start", Map.of("runId", RUN_ID.toString()));
    ValidationHttpResult firstFailure = httpResult(
        "trace-attempt-1",
        NOW,
        503,
        Duration.ofMillis(9),
        new ThrowableInfo("REMOTE", "UNAVAILABLE", "try again", true));
    TradingLabCoordinatorEvidence failed = store.appendHttpResult(
        RUN_ID, OWNER, "start", firstFailure);
    TradingLabCoordinatorEvidence exactReplay = store.appendHttpResult(
        RUN_ID, OWNER, "start", firstFailure);
    assertThatThrownBy(() -> store.appendHttpResult(
        RUN_ID,
        OWNER,
        "start",
        httpResult(
            "trace-attempt-1",
            NOW,
            503,
            Duration.ofMillis(10),
            new ThrowableInfo("REMOTE", "UNAVAILABLE", "try again", true))))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("another meaning");
    ValidationHttpResult success = httpResult(
        "trace-attempt-2",
        NOW.plusSeconds(1),
        200,
        Duration.ofMillis(3),
        null);
    TradingLabCoordinatorEvidence accepted = store.appendHttpResult(
        RUN_ID, OWNER, "start", success);
    TradingLabCoordinatorEvidence laterFailure = store.appendHttpResult(
        RUN_ID,
        OWNER,
        "start",
        httpResult(
            "trace-attempt-3",
            NOW.plusSeconds(2),
            503,
            Duration.ofMillis(17),
            new ThrowableInfo("TRANSPORT", "DISCONNECTED", "connection lost", true)));

    TradingLabValidationExchangeRecorder recorder =
        new TradingLabValidationExchangeRecorder(
            loader, store, mock(TradingLabEvidenceReportProjector.class));

    assertThat(intent.sequence()).isZero();
    assertThat(failed.sequence()).isEqualTo(1L);
    assertThat(exactReplay).isEqualTo(failed);
    assertThat(accepted.sequence()).isEqualTo(2L);
    assertThat(laterFailure.sequence()).isEqualTo(3L);
    assertThat(stored).hasSize(4);
    assertThat(store.journalAfter(RUN_ID, OWNER, -1L, 10))
        .extracting(TradingLabCoordinatorEvidence::sequence)
        .containsExactly(0L, 1L, 2L, 3L);
    assertThat(recorder.previousResult(RUN_ID, OWNER, "start"))
        .contains(success.withSequence(2L));
  }

  @Test
  void recorderRetriesOneStableMutationIntentWithDistinctDurableAttempts() {
    TradingLabValidationExchangeRecorder recorder =
        new TradingLabValidationExchangeRecorder(
            loader, store, mock(TradingLabEvidenceReportProjector.class));
    SafeTradingLabHttpTrace trace = mock(SafeTradingLabHttpTrace.class);
    Map<String, Object> request = Map.of("runId", RUN_ID.toString());
    ValidationHttpResult failure = httpResult(
        "trace-recorder-1",
        NOW,
        503,
        Duration.ofMillis(7),
        new ThrowableInfo("REMOTE", "UNAVAILABLE", "try again", true));
    ValidationHttpResult success = httpResult(
        "trace-recorder-2",
        NOW.plusSeconds(1),
        200,
        Duration.ofMillis(2),
        null);

    ValidationBackendExchange<String> failed = recorder.mutation(
        RUN_ID,
        OWNER,
        "start",
        request,
        () -> new ValidationBackendExchange<>(null, failure, trace));
    ValidationBackendExchange<String> accepted = recorder.mutation(
        RUN_ID,
        OWNER,
        "start",
        request,
        () -> new ValidationBackendExchange<>("accepted", success, trace));

    assertThat(failed.result().sequence()).isEqualTo(1L);
    assertThat(accepted.result().sequence()).isEqualTo(2L);
    assertThat(stored).hasSize(3);
    assertThat(recorder.previousResult(RUN_ID, OWNER, "start"))
        .contains(success.withSequence(2L));
  }

  @Test
  void returnsTheLatestAttemptWhenNoAttemptSucceeded() {
    store.appendHttpResult(
        RUN_ID,
        OWNER,
        "initial-reset",
        httpResult(
            "trace-reset-1",
            NOW,
            503,
            Duration.ofMillis(4),
            new ThrowableInfo("REMOTE", "BUSY", "busy", true)));
    ValidationHttpResult latest = httpResult(
        "trace-reset-2",
        NOW.plusSeconds(1),
        0,
        Duration.ofMillis(6),
        new ThrowableInfo("TRANSPORT", "TIMEOUT", "timed out", true));
    store.appendHttpResult(RUN_ID, OWNER, "initial-reset", latest);

    TradingLabValidationExchangeRecorder recorder =
        new TradingLabValidationExchangeRecorder(
            loader, store, mock(TradingLabEvidenceReportProjector.class));

    assertThat(recorder.previousResult(RUN_ID, OWNER, "initial-reset"))
        .contains(latest.withSequence(1L));
  }

  @Test
  void persistsHighWatermarkAcrossTurnsAndRejectsARegressionBehindThePageCursor() {
    TradingLabCoordinatorEvidence first = store.observeValidationHighWatermark(
        RUN_ID, OWNER, 7L, 300L).orElseThrow();

    assertThat(store.observeValidationHighWatermark(RUN_ID, OWNER, 7L, 300L))
        .isEmpty();
    assertThatThrownBy(() -> store.observeValidationHighWatermark(
        RUN_ID, OWNER, 7L, 250L))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_VALIDATION_HIGH_WATERMARK_REGRESSED"));

    TradingLabCoordinatorEvidence advanced = store.observeValidationHighWatermark(
        RUN_ID, OWNER, 7L, 320L).orElseThrow();
    TradingLabCoordinatorEvidence nextGeneration = store.observeValidationHighWatermark(
        RUN_ID, OWNER, 8L, 0L).orElseThrow();

    assertThat(first.sequence()).isZero();
    assertThat(advanced.sequence()).isEqualTo(1L);
    assertThat(nextGeneration.sequence()).isEqualTo(2L);
    assertThat(stored.values())
        .extracting(TradingLabRunEventEntity::getEventType)
        .containsOnly(TradingLabCoordinatorEvidenceStore.VALIDATION_HIGH_WATERMARK);
  }

  @Test
  void latestStateBearingValidationEventIsLeaseAndJournalGuardBound() {
    store.appendValidationEvent(
        RUN_ID,
        OWNER,
        validationEvent(1L, "MARKET_TICK", Map.of("tickSequence", 1L)));
    store.appendValidationEvent(
        RUN_ID,
        OWNER,
        validationEvent(
            2L,
            "STATE_SNAPSHOT",
            Map.of("tickSequence", 1L, "state", Map.of("orders", List.of()))));
    TradingLabCoordinatorEvidence checkpoint = store.appendValidationEvent(
        RUN_ID,
        OWNER,
        validationEvent(
            3L,
            "CHECKPOINT",
            Map.of("tickSequence", 1L, "state", Map.of("positions", List.of()))));
    clearInvocations(loader, journalGuard);

    assertThat(store.latestStateBearingValidationEvent(RUN_ID, OWNER))
        .contains(checkpoint);
    verify(loader).lockLive(RUN_ID, OWNER);
    verify(journalGuard).requireSafe(checkpoint.toLifecycleValue());
  }

  private Optional<TradingLabRunEventEntity> preferredHttpResult(String sourceKey) {
    return stored.values().stream()
        .filter(entity -> TradingLabCoordinatorEvidenceStore.HTTP_RESULT.equals(
            entity.getEventType()))
        .filter(entity -> sourceKey.equals(payload(entity).get("sourceKey")))
        .sorted(Comparator
            .comparing((TradingLabRunEventEntity entity) -> !successful(entity))
            .thenComparing(
                TradingLabRunEventEntity::getSequence,
                Comparator.reverseOrder()))
        .findFirst();
  }

  private boolean successful(TradingLabRunEventEntity entity) {
    Map<?, ?> evidence = (Map<?, ?>) payload(entity).get("evidence");
    int status = ((Number) evidence.get("status")).intValue();
    return status >= 200 && status < 300 && evidence.get("exception") == null;
  }

  private Map<?, ?> payload(TradingLabRunEventEntity entity) {
    try {
      return json.readValue(entity.getPayloadJson(), Map.class);
    } catch (Exception invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static ValidationHttpResult httpResult(
      String traceId,
      Instant realTime,
      int status,
      Duration duration,
      ThrowableInfo failure
  ) {
    return new ValidationHttpResult(
        0L,
        "validation",
        "POST",
        URI.create("http://127.0.0.1:18087/internal/validation/runs"),
        null,
        realTime,
        status,
        duration,
        Map.of("body", Map.of("runId", RUN_ID.toString())),
        Map.of("body", Map.of("success", status >= 200 && status < 300)),
        traceId,
        "correlation-task7",
        failure);
  }

  private static ValidationRunEvent event(long sequence, String fingerprint) {
    return new ValidationRunEvent(
        RUN_ID,
        sequence,
        "durable:" + sequence,
        fingerprint,
        "RUN_STATE_CHANGED",
        NOW.plusSeconds(sequence),
        "correlation-" + sequence,
        Map.of("state", sequence == 1L ? "RUNNING" : "COMPLETED"));
  }

  private static ValidationRunEvent validationEvent(
      long sequence,
      String type,
      Map<String, Object> payload
  ) {
    return new ValidationRunEvent(
        RUN_ID,
        sequence,
        "durable:" + sequence,
        "fingerprint-" + sequence,
        type,
        NOW.plusSeconds(sequence),
        "correlation-" + sequence,
        payload);
  }
}
