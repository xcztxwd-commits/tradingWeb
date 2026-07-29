package com.fxplatform.tradinglab.evidence;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.application.TradingLabCoordinatorRunLoader;
import com.fxplatform.tradinglab.client.ValidationHttpResult;
import com.fxplatform.tradinglab.client.ValidationRunEvent;
import com.fxplatform.tradinglab.entity.TradingLabRunEventEntity;
import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabFencedReportWriter;
import com.fxplatform.tradinglab.report.TradingLabJournalEvidenceGuard;
import com.fxplatform.tradinglab.report.TradingLabReportWriteFence;
import com.fxplatform.tradinglab.repository.TradingLabRunEventRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single fenced durable journal for coordinator intents, HTTP results, and validation events.
 * Its sequence becomes Task 8's SSE id and the source sequence used by report array appends.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class TradingLabCoordinatorEvidenceStore {

  public record ReportProjectionCursors(
      long lifecycle,
      long apiTrace,
      long marketTicks,
      long checkpoints,
      long errors
  ) {

    public ReportProjectionCursors {
      if (lifecycle < -1L
          || apiTrace < -1L
          || marketTicks < -1L
          || checkpoints < -1L
          || errors < -1L) {
        throw new IllegalArgumentException("Invalid Trading Lab report projection cursor");
      }
    }
  }

  public static final String HTTP_INTENT = "VALIDATION_HTTP_INTENT";
  public static final String HTTP_RESULT = "VALIDATION_HTTP_RESULT";
  public static final String VALIDATION_EVENT = "VALIDATION_EVENT";
  public static final String VALIDATION_HIGH_WATERMARK = "VALIDATION_HIGH_WATERMARK";
  private static final int MAX_JOURNAL_PAGE = 200;
  private static final int MAX_KEY_LENGTH = 200;
  private static final Set<String> RECOVERY_CLEANUP_KEYS = Set.of(
      "cleanup:COMPLETED",
      "cleanup:CANCELLED",
      "cleanup:FAILED");
  private static final Set<String> INTENT_ENVELOPE_KEYS = Set.of(
      "sourceKind",
      "sourceKey",
      "sourceFingerprint",
      "evidence");

  private final TradingLabCoordinatorRunLoader runLoader;
  private final TradingLabRunEventRepository events;
  private final TradingLabEvidenceCanonicalizer canonicalizer;
  private final TradingLabFencedReportWriter reportWriter;
  private final TransactionOperations transactions;
  private final Clock clock;

  @Autowired
  public TradingLabCoordinatorEvidenceStore(
      TradingLabCoordinatorRunLoader runLoader,
      TradingLabRunEventRepository events,
      TradingLabEvidenceCanonicalizer canonicalizer,
      TradingLabFencedReportWriter reportWriter,
      PlatformTransactionManager transactionManager
  ) {
    this(
        runLoader,
        events,
        canonicalizer,
        reportWriter,
        journalTransactions(transactionManager),
        Clock.systemUTC());
  }

  TradingLabCoordinatorEvidenceStore(
      TradingLabCoordinatorRunLoader runLoader,
      TradingLabRunEventRepository events,
      TradingLabEvidenceCanonicalizer canonicalizer,
      TradingLabFencedReportWriter reportWriter,
      Clock clock
  ) {
    this(
        runLoader,
        events,
        canonicalizer,
        reportWriter,
        TransactionOperations.withoutTransaction(),
        clock);
  }

  TradingLabCoordinatorEvidenceStore(
      TradingLabCoordinatorRunLoader runLoader,
      TradingLabRunEventRepository events,
      TradingLabEvidenceCanonicalizer canonicalizer,
      TradingLabFencedReportWriter reportWriter,
      TransactionOperations transactions,
      Clock clock
  ) {
    this.runLoader = Objects.requireNonNull(runLoader, "runLoader");
    this.events = Objects.requireNonNull(events, "events");
    this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
    this.reportWriter = Objects.requireNonNull(reportWriter, "reportWriter");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public TradingLabCoordinatorEvidence appendIntent(
      UUID runId,
      String claimOwner,
      String logicalKey,
      Map<String, Object> request
  ) {
    String key = requireLogicalKey(logicalKey);
    var source = canonicalizer.canonicalize(request);
    return append(
        runId,
        claimOwner,
        HTTP_INTENT,
        "intent:" + key,
        null,
        null,
        ignored -> envelope(
            "HTTP_INTENT", key, checksum(source.json()), source.value()));
  }

  public TradingLabCoordinatorEvidence appendHttpResult(
      UUID runId,
      String claimOwner,
      String logicalKey,
      ValidationHttpResult result
  ) {
    return appendHttpResult(runId, claimOwner, logicalKey, result, null);
  }

  public TradingLabCoordinatorEvidence appendHttpResult(
      UUID runId,
      String claimOwner,
      String logicalKey,
      ValidationHttpResult result,
      SafeTradingLabHttpTrace trace
  ) {
    Objects.requireNonNull(result, "result");
    String key = requireLogicalKey(logicalKey);
    String traceId = requireAttemptTraceId(result.traceId());
    String attemptKey = "attempt:" + checksum(key + "\0" + traceId);
    return append(
        runId,
        claimOwner,
        HTTP_RESULT,
        attemptKey,
        result.virtualTime(),
        result.correlationId(),
        trace,
        sequence -> {
          var sequenced = result.withSequence(sequence);
          var source = canonicalizer.canonicalize(sequenced.toSafeMap());
          return envelope(
              "HTTP_RESULT", key, checksum(source.json()), source.value());
        });
  }

  public TradingLabCoordinatorEvidence appendValidationEvent(
      UUID runId,
      String claimOwner,
      ValidationRunEvent event
  ) {
    Objects.requireNonNull(event, "event");
    if (!runId.equals(event.runId()) || event.sequence() < 1L) {
      throw conflict("Validation event identity does not match the Trading Lab run");
    }
    String logicalKey = "validation:" + event.sequence();
    var source = canonicalizer.canonicalize(event.toSafeMap());
    Map<String, Object> payload = envelope(
        "VALIDATION_EVENT",
        logicalKey,
        checksum(source.json()),
        source.value());
    payload.put("validationSequence", event.sequence());
    payload.put("durableKey", event.durableKey());
    payload.put("validationFingerprint", event.fingerprint());
    return append(
        runId,
        claimOwner,
        VALIDATION_EVENT,
        logicalKey,
        event.virtualTime(),
        event.correlationId(),
        ignored -> payload);
  }

  /**
   * Durably remembers the largest remote event high watermark observed for one validation
   * generation. This closes the cross-turn gap where the durable event cursor can lag by a page.
   */
  public Optional<TradingLabCoordinatorEvidence> observeValidationHighWatermark(
      UUID runId,
      String claimOwner,
      long generation,
      long highWatermark
  ) {
    if (generation <= 0L || highWatermark < 0L) {
      throw new IllegalArgumentException("Validation high watermark identity is invalid");
    }
    TradingLabReportWriteFence fence = runLoader.requireLive(
        runId, claimOwner).reportFence();
    return reportWriter.withJournalEvidenceGuard(fence, null, guard ->
        inTransaction(() -> {
          runLoader.lockLive(runId, claimOwner);
          Long highest = events.findHighestValidationHighWatermark(runId, generation);
          if (highest != null && highWatermark < highest) {
            throw new BusinessException(
                "TRADING_LAB_VALIDATION_HIGH_WATERMARK_REGRESSED",
                "Validation event high watermark regressed across coordinator turns");
          }
          if (highest != null && highWatermark == highest) {
            return Optional.empty();
          }
          Map<String, Object> source = new LinkedHashMap<>();
          source.put("validationGeneration", generation);
          source.put("highWatermark", highWatermark);
          var canonical = canonicalizer.canonicalize(source);
          Map<String, Object> payload = envelope(
              VALIDATION_HIGH_WATERMARK,
              "watermark:" + generation + ":" + highWatermark,
              checksum(canonical.json()),
              canonical.value());
          payload.put("validationGeneration", generation);
          payload.put("highWatermark", highWatermark);
          return Optional.of(appendLocked(
              runId,
              claimOwner,
              VALIDATION_HIGH_WATERMARK,
              "watermark:" + generation + ":" + highWatermark,
              null,
              null,
              ignored -> payload,
              guard));
        }));
  }

  public List<TradingLabCoordinatorEvidence> journalAfter(
      UUID runId,
      String claimOwner,
      long afterSequence,
      int limit
  ) {
    if (afterSequence < -1L || limit < 1 || limit > MAX_JOURNAL_PAGE) {
      throw new IllegalArgumentException("Invalid Trading Lab journal page");
    }
    TradingLabReportWriteFence fence = runLoader.requireLive(
        runId, claimOwner).reportFence();
    return reportWriter.withJournalEvidenceGuard(fence, null, guard ->
        inTransaction(() -> {
          List<TradingLabCoordinatorEvidence> journal = events.findJournalAfter(
                  runId, afterSequence, limit)
              .stream()
              .map(entity -> fromEntityAndGuard(entity, guard))
              .toList();
          long expected = Math.addExact(afterSequence, 1L);
          for (TradingLabCoordinatorEvidence evidence : journal) {
            if (evidence.sequence() != expected) {
              throw corruption();
            }
            expected = Math.addExact(expected, 1L);
          }
          return journal;
        }));
  }

  public List<TradingLabCoordinatorEvidence> reportProjectionCandidates(
      UUID runId,
      String claimOwner,
      ReportProjectionCursors cursors,
      int limit
  ) {
    Objects.requireNonNull(cursors, "cursors");
    if (limit < 1 || limit > MAX_JOURNAL_PAGE) {
      throw new IllegalArgumentException("Invalid Trading Lab report projection page");
    }
    TradingLabReportWriteFence fence = runLoader.requireLive(
        runId, claimOwner).reportFence();
    return reportWriter.withJournalEvidenceGuard(fence, null, guard ->
        inTransaction(() -> {
          List<TradingLabCoordinatorEvidence> candidates =
              events.findReportProjectionCandidates(
                      runId,
                      cursors.lifecycle(),
                      cursors.apiTrace(),
                      cursors.marketTicks(),
                      cursors.checkpoints(),
                      cursors.errors(),
                      limit)
                  .stream()
                  .map(entity -> fromEntityAndGuard(entity, guard))
                  .toList();
          long previous = -1L;
          for (TradingLabCoordinatorEvidence evidence : candidates) {
            if (evidence.sequence() <= previous) {
              throw corruption();
            }
            previous = evidence.sequence();
          }
          return candidates;
        }));
  }

  @Transactional(readOnly = true)
  public long lastValidationSequence(UUID runId, String claimOwner) {
    runLoader.requireLive(runId, claimOwner);
    Long highest = events.findHighestValidationSequence(runId);
    return highest == null ? 0L : highest;
  }

  @Transactional(readOnly = true)
  public long lastEvidenceSequence(UUID runId, String claimOwner) {
    runLoader.requireLive(runId, claimOwner);
    Long highest = events.findHighestEvidenceSequence(runId);
    return highest == null ? -1L : highest;
  }

  public Optional<TradingLabCoordinatorEvidence> findIntent(
      UUID runId,
      String claimOwner,
      String logicalKey
  ) {
    TradingLabReportWriteFence fence = runLoader.requireLive(
        runId, claimOwner).reportFence();
    String key = "intent:" + requireLogicalKey(logicalKey);
    return reportWriter.withJournalEvidenceGuard(fence, null, guard ->
        inTransaction(() ->
            events.findEvidenceById(deterministicId(runId, HTTP_INTENT, key))
                .map(entity -> fromEntityAndGuard(entity, guard))));
  }

  /**
   * Reads only the deterministic cleanup intent needed to recover a run after its report closed.
   *
   * <p>The ordinary journal read path intentionally requires a writable report so every projected
   * value is rechecked by the report guard. A terminal report cannot satisfy that fence. This
   * narrow path therefore accepts only one of the three internal cleanup keys and independently
   * verifies the complete persisted intent envelope and source fingerprint before returning it.
   */
  public Optional<TradingLabCoordinatorEvidence> findCleanupIntentForRecovery(
      UUID runId,
      String claimOwner,
      String logicalKey
  ) {
    Objects.requireNonNull(runId, "runId");
    String sourceKey = requireLogicalKey(logicalKey);
    if (!RECOVERY_CLEANUP_KEYS.contains(sourceKey)) {
      throw new IllegalArgumentException("Trading Lab recovery cleanup key is invalid");
    }
    String deterministicKey = "intent:" + sourceKey;
    UUID eventId = deterministicId(runId, HTTP_INTENT, deterministicKey);
    return inTransaction(() -> {
      runLoader.lockLive(runId, claimOwner);
      return events.findEvidenceById(eventId)
          .map(entity -> requireRecoveryCleanupIntent(
              entity, runId, eventId, sourceKey));
    });
  }

  public Optional<TradingLabCoordinatorEvidence> findHttpResult(
      UUID runId,
      String claimOwner,
      String logicalKey
  ) {
    TradingLabReportWriteFence fence = runLoader.requireLive(
        runId, claimOwner).reportFence();
    String key = requireLogicalKey(logicalKey);
    return reportWriter.withJournalEvidenceGuard(fence, null, guard ->
        inTransaction(() ->
            events.findPreferredHttpResult(runId, key)
                .map(entity -> fromEntityAndGuard(entity, guard))));
  }

  public Optional<TradingLabCoordinatorEvidence> latestStateBearingValidationEvent(
      UUID runId,
      String claimOwner
  ) {
    TradingLabReportWriteFence fence = runLoader.requireLive(
        runId, claimOwner).reportFence();
    return reportWriter.withJournalEvidenceGuard(fence, null, guard ->
        inTransaction(() -> {
          runLoader.lockLive(runId, claimOwner);
          return events.findLatestStateBearingValidationEvent(runId)
              .map(entity -> fromEntityAndGuard(entity, guard));
        }));
  }

  private TradingLabCoordinatorEvidence append(
      UUID runId,
      String claimOwner,
      String eventType,
      String logicalKey,
      Instant virtualTime,
      String correlationId,
      LongFunction<Map<String, Object>> payloadFactory
  ) {
    return append(
        runId,
        claimOwner,
        eventType,
        logicalKey,
        virtualTime,
        correlationId,
        null,
        payloadFactory);
  }

  private TradingLabCoordinatorEvidence append(
      UUID runId,
      String claimOwner,
      String eventType,
      String logicalKey,
      Instant virtualTime,
      String correlationId,
      SafeTradingLabHttpTrace trace,
      LongFunction<Map<String, Object>> payloadFactory
  ) {
    Objects.requireNonNull(runId, "runId");
    requireLogicalKey(logicalKey);
    TradingLabReportWriteFence fence = runLoader.requireLive(
        runId, claimOwner).reportFence();
    return reportWriter.withJournalEvidenceGuard(fence, trace, guard ->
        inTransaction(() -> {
          runLoader.lockLive(runId, claimOwner);
          return appendLocked(
              runId,
              claimOwner,
              eventType,
              logicalKey,
              virtualTime,
              correlationId,
              payloadFactory,
              guard);
        }));
  }

  private <T> T inTransaction(Supplier<T> work) {
    return Objects.requireNonNull(
        transactions.execute(ignored -> work.get()),
        "Trading Lab evidence transaction result");
  }

  private static TransactionTemplate journalTransactions(
      PlatformTransactionManager transactionManager
  ) {
    TransactionTemplate transactions = new TransactionTemplate(
        Objects.requireNonNull(transactionManager, "transactionManager"));
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transactions;
  }

  private TradingLabCoordinatorEvidence appendLocked(
      UUID runId,
      String claimOwner,
      String eventType,
      String logicalKey,
      Instant virtualTime,
      String correlationId,
      LongFunction<Map<String, Object>> payloadFactory,
      TradingLabJournalEvidenceGuard guard
  ) {
    UUID eventId = deterministicId(runId, eventType, logicalKey);
    TradingLabRunEventEntity existing = events.findEvidenceById(eventId).orElse(null);
    if (existing != null) {
      Map<String, Object> expected = payloadFactory.apply(existing.getSequence());
      return requireExact(
          existing, runId, eventType, virtualTime, correlationId, expected, guard);
    }

    if (VALIDATION_EVENT.equals(eventType)) {
      long remoteSequence = requiredLong(
          payloadFactory.apply(0L).get("validationSequence"),
          "validationSequence");
      Long highestRemote = events.findHighestValidationSequence(runId);
      long expectedRemote = highestRemote == null ? 1L : Math.addExact(highestRemote, 1L);
      if (remoteSequence != expectedRemote) {
        throw conflict("Validation events must be mirrored contiguously");
      }
    }

    Long highest = events.findHighestEvidenceSequence(runId);
    long sequence = highest == null ? 0L : Math.addExact(highest, 1L);
    Map<String, Object> requestedPayload = payloadFactory.apply(sequence);
    var canonical = canonicalizer.canonicalize(requestedPayload);

    TradingLabRunEventEntity entity = new TradingLabRunEventEntity();
    entity.setId(eventId);
    entity.setRunId(runId);
    entity.setSequence(sequence);
    entity.setEventType(eventType);
    entity.setVirtualTime(normalize(virtualTime));
    entity.setRealTime(normalize(clock.instant()));
    entity.setCorrelationId(correlationId);
    entity.setPayloadJson(canonical.json());
    TradingLabCoordinatorEvidence candidate = fromEntity(entity);
    guard.requireSafe(candidate.toLifecycleValue());
    try {
      if (events.insert(entity) != 1) {
        throw new IllegalStateException("Trading Lab evidence was not inserted");
      }
    } catch (DuplicateKeyException duplicate) {
      throw conflict("Trading Lab evidence sequence or logical key conflicted");
    }
    return candidate;
  }

  private TradingLabCoordinatorEvidence requireExact(
      TradingLabRunEventEntity existing,
      UUID runId,
      String eventType,
      Instant virtualTime,
      String correlationId,
      Map<String, Object> payload,
      TradingLabJournalEvidenceGuard guard
  ) {
    var expected = canonicalizer.canonicalize(payload);
    var persisted = canonicalizer.parse(existing.getPayloadJson());
    if (!runId.equals(existing.getRunId())
        || !eventType.equals(existing.getEventType())
        || !Objects.equals(normalize(virtualTime), normalize(existing.getVirtualTime()))
        || !Objects.equals(correlationId, existing.getCorrelationId())
        || !expected.json().equals(persisted.json())) {
      throw conflict("Trading Lab evidence logical key already has another meaning");
    }
    TradingLabCoordinatorEvidence evidence = fromEntity(existing);
    guard.requireSafe(evidence.toLifecycleValue());
    return evidence;
  }

  private TradingLabCoordinatorEvidence fromEntityAndGuard(
      TradingLabRunEventEntity entity,
      TradingLabJournalEvidenceGuard guard
  ) {
    TradingLabCoordinatorEvidence evidence = fromEntity(entity);
    guard.requireSafe(evidence.toLifecycleValue());
    return evidence;
  }

  private TradingLabCoordinatorEvidence requireRecoveryCleanupIntent(
      TradingLabRunEventEntity entity,
      UUID runId,
      UUID eventId,
      String sourceKey
  ) {
    try {
      if (entity.getSequence() == null
          || entity.getSequence() < 0L
          || entity.getRealTime() == null) {
        throw corruption();
      }
      var persisted = canonicalizer.parsePersistedExact(entity.getPayloadJson());
      Map<String, Object> payload = persisted.value();
      if (!eventId.equals(entity.getId())
          || !runId.equals(entity.getRunId())
          || !HTTP_INTENT.equals(entity.getEventType())
          || entity.getVirtualTime() != null
          || entity.getCorrelationId() != null
          || !payload.keySet().equals(INTENT_ENVELOPE_KEYS)
          || !"HTTP_INTENT".equals(payload.get("sourceKind"))
          || !sourceKey.equals(payload.get("sourceKey"))
          || !(payload.get("sourceFingerprint") instanceof String sourceFingerprint)
          || !(payload.get("evidence") instanceof Map<?, ?> rawSource)) {
        throw corruption();
      }
      Map<String, Object> typedSource = new LinkedHashMap<>();
      rawSource.forEach((key, value) -> {
        if (!(key instanceof String text)) {
          throw corruption();
        }
        typedSource.put(text, value);
      });
      var canonicalSource = canonicalizer.canonicalizePersistedExact(typedSource);
      Map<String, Object> expectedEnvelope = envelope(
          "HTTP_INTENT",
          sourceKey,
          checksum(canonicalSource.json()),
          canonicalSource.value());
      var canonicalEnvelope = canonicalizer.canonicalizePersistedExact(expectedEnvelope);
      if (!sourceFingerprint.equals(checksum(canonicalSource.json()))
          || !canonicalEnvelope.json().equals(persisted.json())) {
        throw corruption();
      }
      return new TradingLabCoordinatorEvidence(
          entity.getId(),
          entity.getRunId(),
          entity.getSequence(),
          entity.getEventType(),
          normalize(entity.getVirtualTime()),
          normalize(entity.getRealTime()),
          entity.getCorrelationId(),
          persisted.value());
    } catch (BusinessException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw corruption();
    }
  }

  private TradingLabCoordinatorEvidence fromEntity(TradingLabRunEventEntity entity) {
    if (entity.getId() == null
        || entity.getRunId() == null
        || entity.getSequence() == null
        || entity.getEventType() == null
        || entity.getRealTime() == null
        || entity.getPayloadJson() == null) {
      throw corruption();
    }
    return new TradingLabCoordinatorEvidence(
        entity.getId(),
        entity.getRunId(),
        entity.getSequence(),
        entity.getEventType(),
        normalize(entity.getVirtualTime()),
        normalize(entity.getRealTime()),
        entity.getCorrelationId(),
        canonicalizer.parse(entity.getPayloadJson()).value());
  }

  private static Map<String, Object> envelope(
      String sourceKind,
      String sourceKey,
      String sourceFingerprint,
      Map<String, Object> evidence
  ) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("sourceKind", sourceKind);
    value.put("sourceKey", sourceKey);
    value.put("sourceFingerprint", sourceFingerprint);
    value.put("evidence", evidence);
    return value;
  }

  private static String requireLogicalKey(String value) {
    if (value == null || value.isBlank() || value.length() > MAX_KEY_LENGTH) {
      throw new IllegalArgumentException("Trading Lab evidence logical key is invalid");
    }
    return value;
  }

  private static String requireAttemptTraceId(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Validation HTTP attempt traceId is required");
    }
    return value;
  }

  private static long requiredLong(Object value, String field) {
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException(field + " is required");
    }
    return number.longValue();
  }

  private static UUID deterministicId(UUID runId, String eventType, String logicalKey) {
    byte[] digest = digest((runId + "|" + eventType + "|" + logicalKey)
        .getBytes(StandardCharsets.UTF_8));
    long most = ByteBuffer.wrap(digest, 0, 8).getLong();
    long least = ByteBuffer.wrap(digest, 8, 8).getLong();
    most = (most & 0xffffffffffff0fffL) | 0x0000000000005000L;
    least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
    return new UUID(most, least);
  }

  private static String checksum(String value) {
    return HexFormat.of().formatHex(digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] digest(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static Instant normalize(Instant value) {
    return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
  }

  private static BusinessException conflict(String message) {
    return new BusinessException("TRADING_LAB_EVIDENCE_CONFLICT", message);
  }

  private static BusinessException corruption() {
    return new BusinessException(
        "TRADING_LAB_EVIDENCE_CORRUPT",
        "Trading Lab evidence journal is not contiguous and canonical");
  }
}
