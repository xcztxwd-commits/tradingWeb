package com.fxplatform.tradinglab.application;

import com.fxplatform.tradinglab.client.ValidationHttpResult;
import com.fxplatform.tradinglab.client.ValidationRunEvent;
import com.fxplatform.tradinglab.evidence.TradingLabCoordinatorEvidence;
import com.fxplatform.tradinglab.evidence.TradingLabCoordinatorEvidenceStore;
import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabFencedReportWriter;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceInput;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizer;
import com.fxplatform.tradinglab.report.TradingLabReportSection;
import com.fxplatform.tradinglab.report.TradingLabReportWriteFence;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Replays the durable coordinator journal into the fenced report in one global order. */
@Service
public class TradingLabEvidenceReportProjector {

  public record DurableValidationState(
      long journalSequence,
      long validationSequence,
      String eventType,
      Instant virtualTime,
      String correlationId,
      Map<String, Object> state
  ) {

    public DurableValidationState {
      if (journalSequence < 0L || validationSequence < 1L) {
        throw new IllegalArgumentException("Durable validation state sequence is invalid");
      }
      if (!Set.of("STATE_SNAPSHOT", "CHECKPOINT").contains(eventType)) {
        throw new IllegalArgumentException("Durable validation state type is invalid");
      }
      state = Collections.unmodifiableMap(new LinkedHashMap<>(
          Objects.requireNonNull(state, "state")));
    }
  }

  private static final int REPLAY_PAGE_SIZE = 200;
  private static final List<TradingLabReportSection> RECOVERY_SECTIONS = List.of(
      TradingLabReportSection.LIFECYCLE,
      TradingLabReportSection.API_TRACE,
      TradingLabReportSection.MARKET_TICKS,
      TradingLabReportSection.CHECKPOINTS,
      TradingLabReportSection.ERRORS);
  private static final Set<String> VALIDATION_EVENT_KEYS = Set.of(
      "runId",
      "sequence",
      "durableKey",
      "fingerprint",
      "type",
      "virtualTime",
      "correlationId",
      "payload");
  private static final Set<String> SAFE_TRACE_KEYS = Set.of(
      "url",
      "queryParameters",
      "requestHeaders",
      "requestContentType",
      "requestBody",
      "responseHeaders",
      "responseContentType",
      "responseBody",
      "exception",
      "authentication");

  private final TradingLabCoordinatorRunLoader runLoader;
  private final TradingLabCoordinatorEvidenceStore evidenceStore;
  private final TradingLabFencedReportWriter writer;
  private final TradingLabHttpTraceSanitizer traceSanitizer;

  public TradingLabEvidenceReportProjector(
      TradingLabCoordinatorRunLoader runLoader,
      TradingLabCoordinatorEvidenceStore evidenceStore,
      TradingLabFencedReportWriter writer,
      TradingLabHttpTraceSanitizer traceSanitizer
  ) {
    this.runLoader = Objects.requireNonNull(runLoader, "runLoader");
    this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore");
    this.writer = Objects.requireNonNull(writer, "writer");
    this.traceSanitizer = Objects.requireNonNull(traceSanitizer, "traceSanitizer");
  }

  public void project(
      UUID runId,
      String claimOwner,
      TradingLabCoordinatorEvidence evidence
  ) {
    projectOne(runId, claimOwner, evidence, null);
  }

  /**
   * Projects a live HTTP result with the trace sealed by the client that observed the exchange.
   *
   * <p>The original trace carries the dynamic-secret registry captured from the raw exchange. A
   * replay after that process-local value has been lost deliberately rebuilds a trace only from
   * the durable safe HTTP result.
   */
  public void project(
      UUID runId,
      String claimOwner,
      TradingLabCoordinatorEvidence evidence,
      SafeTradingLabHttpTrace originalTrace
  ) {
    Objects.requireNonNull(evidence, "evidence");
    Objects.requireNonNull(originalTrace, "originalTrace");
    if (!TradingLabCoordinatorEvidenceStore.HTTP_RESULT.equals(evidence.eventType())) {
      throw new IllegalArgumentException(
          "An original HTTP trace requires HTTP result evidence");
    }
    projectOne(runId, claimOwner, evidence, originalTrace);
  }

  private void projectOne(
      UUID runId,
      String claimOwner,
      TradingLabCoordinatorEvidence evidence,
      SafeTradingLabHttpTrace originalTrace
  ) {
    Objects.requireNonNull(evidence, "evidence");
    if (!runId.equals(evidence.runId())) {
      throw new IllegalArgumentException("Evidence belongs to another Trading Lab run");
    }
    Set<TradingLabReportSection> dedicated =
        TradingLabCoordinatorEvidenceStore.VALIDATION_EVENT.equals(evidence.eventType())
            ? dedicatedSections(validationEvent(evidence))
            : Set.of();
    appendOne(runId, claimOwner, evidence, true, true, dedicated, originalTrace);
    flushFresh(runId, claimOwner);
  }

  public void replayMissing(UUID runId, String claimOwner) {
    // A prior live projection may have appended the original sealed trace and crashed before its
    // final flush. Persist that exact buffered value first; rebuilding the same source sequence
    // from durable safe evidence would intentionally have a different canonical representation.
    flushFresh(runId, claimOwner);
    Map<TradingLabReportSection, Long> cursors =
        new EnumMap<>(TradingLabReportSection.class);
    for (TradingLabReportSection section : RECOVERY_SECTIONS) {
      cursors.put(section, durableCursor(runId, claimOwner, section));
    }
    while (true) {
      List<TradingLabCoordinatorEvidence> page =
          evidenceStore.reportProjectionCandidates(
              runId,
              claimOwner,
              projectionCursors(cursors),
              REPLAY_PAGE_SIZE);
      if (page.isEmpty()) {
        return;
      }
      Map<TradingLabReportSection, Long> nextCursors =
          new EnumMap<>(cursors);
      for (TradingLabCoordinatorEvidence evidence : page) {
        boolean missingLifecycle =
            evidence.sequence() > cursors.get(TradingLabReportSection.LIFECYCLE);
        boolean missingTrace =
            TradingLabCoordinatorEvidenceStore.HTTP_RESULT.equals(evidence.eventType())
                && evidence.sequence() > cursors.get(TradingLabReportSection.API_TRACE);
        Set<TradingLabReportSection> missingDedicated =
            missingDedicatedSections(evidence, cursors);
        appendOne(
            runId,
            claimOwner,
            evidence,
            missingLifecycle,
            missingTrace,
            missingDedicated,
            null);
        if (missingLifecycle) {
          nextCursors.put(TradingLabReportSection.LIFECYCLE, evidence.sequence());
        }
        if (missingTrace) {
          nextCursors.put(TradingLabReportSection.API_TRACE, evidence.sequence());
        }
        for (TradingLabReportSection section : missingDedicated) {
          nextCursors.put(section, evidence.sequence());
        }
      }
      flushFresh(runId, claimOwner);
      cursors = nextCursors;
      if (page.size() < REPLAY_PAGE_SIZE) {
        return;
      }
    }
  }

  public Optional<DurableValidationState> latestDurableState(
      UUID runId,
      String claimOwner
  ) {
    return evidenceStore.latestStateBearingValidationEvent(runId, claimOwner)
        .map(evidence -> {
          ValidationRunEvent event = validationEvent(evidence);
          if (!Set.of("STATE_SNAPSHOT", "CHECKPOINT").contains(event.type())) {
            throw new IllegalArgumentException(
                "Latest durable validation state has a conflicting type");
          }
          return new DurableValidationState(
              evidence.sequence(),
              event.sequence(),
              event.type(),
              event.virtualTime(),
              event.correlationId(),
              stringMap(event.payload().get("state")));
        });
  }

  private void appendOne(
      UUID runId,
      String claimOwner,
      TradingLabCoordinatorEvidence evidence,
      boolean appendLifecycle,
      boolean appendTrace,
      Set<TradingLabReportSection> dedicatedSections,
      SafeTradingLabHttpTrace originalTrace
  ) {
    if (appendLifecycle) {
      TradingLabReportWriteFence lifecycleFence = freshFence(runId, claimOwner);
      writer.appendEvent(
          lifecycleFence,
          TradingLabReportSection.LIFECYCLE,
          evidence.sequence(),
          evidence.toLifecycleValue());
    }

    if (appendTrace
        && TradingLabCoordinatorEvidenceStore.HTTP_RESULT.equals(evidence.eventType())) {
      ValidationHttpResult result = httpResult(evidence);
      SafeTradingLabHttpTrace trace = originalTrace == null
          ? result.rebuildReportTrace(traceSanitizer)
          : result.rebuildReportTrace(traceSanitizer, originalTrace);
      TradingLabReportWriteFence traceFence = freshFence(runId, claimOwner);
      writer.appendEvent(
          traceFence,
          TradingLabReportSection.API_TRACE,
          evidence.sequence(),
          trace);
    }

    if (!dedicatedSections.isEmpty()) {
      ValidationRunEvent event = validationEvent(evidence);
      for (TradingLabReportSection section : dedicatedSections) {
        Object value = section == TradingLabReportSection.API_TRACE
            ? validationApiTrace(event)
            : event.toSafeMap();
        writer.appendEvent(
            freshFence(runId, claimOwner),
            section,
            evidence.sequence(),
            value);
      }
    }
  }

  private static Set<TradingLabReportSection> missingDedicatedSections(
      TradingLabCoordinatorEvidence evidence,
      Map<TradingLabReportSection, Long> cursors
  ) {
    if (!TradingLabCoordinatorEvidenceStore.VALIDATION_EVENT.equals(evidence.eventType())) {
      return Set.of();
    }
    ValidationRunEvent event = validationEvent(evidence);
    java.util.EnumSet<TradingLabReportSection> missing =
        java.util.EnumSet.noneOf(TradingLabReportSection.class);
    for (TradingLabReportSection section : dedicatedSections(event)) {
      if (evidence.sequence() > cursors.get(section)) {
        missing.add(section);
      }
    }
    return missing;
  }

  private static TradingLabCoordinatorEvidenceStore.ReportProjectionCursors projectionCursors(
      Map<TradingLabReportSection, Long> cursors
  ) {
    return new TradingLabCoordinatorEvidenceStore.ReportProjectionCursors(
        cursors.get(TradingLabReportSection.LIFECYCLE),
        cursors.get(TradingLabReportSection.API_TRACE),
        cursors.get(TradingLabReportSection.MARKET_TICKS),
        cursors.get(TradingLabReportSection.CHECKPOINTS),
        cursors.get(TradingLabReportSection.ERRORS));
  }

  private static Set<TradingLabReportSection> dedicatedSections(
      ValidationRunEvent event
  ) {
    return switch (event.type()) {
      case "MARKET_TICK" -> Set.of(TradingLabReportSection.MARKET_TICKS);
      case "STATE_SNAPSHOT", "CHECKPOINT" ->
          Set.of(TradingLabReportSection.CHECKPOINTS);
      case "RUN_STATE_CHANGED" -> "FAILED".equals(event.payload().get("state"))
          ? Set.of(TradingLabReportSection.ERRORS)
          : Set.of();
      case "RUN_EXECUTION_FAILED" -> Set.of(TradingLabReportSection.ERRORS);
      case "API_TRACE" -> apiTraceSections(event);
      default -> Set.of();
    };
  }

  private static Set<TradingLabReportSection> apiTraceSections(ValidationRunEvent event) {
    java.util.EnumSet<TradingLabReportSection> sections =
        java.util.EnumSet.noneOf(TradingLabReportSection.class);
    Object trace = event.payload().get("trace");
    if (trace != null) {
      Object scope = event.payload().get("traceScope");
      if (!(trace instanceof Map<?, ?>)
          || !(scope instanceof String text)
          || !Set.of("HTTP_HOP", "COMMAND").contains(text)) {
        throw new IllegalArgumentException("Validation API trace payload is malformed");
      }
      sections.add(TradingLabReportSection.API_TRACE);
    }
    if ("FAILED".equals(event.payload().get("outcome"))) {
      sections.add(TradingLabReportSection.ERRORS);
    }
    return sections.isEmpty() ? Set.of() : Collections.unmodifiableSet(sections);
  }

  private SafeTradingLabHttpTrace validationApiTrace(ValidationRunEvent event) {
    Object rawTrace = event.payload().get("trace");
    if (!(rawTrace instanceof Map<?, ?> raw)
        || !SAFE_TRACE_KEYS.equals(raw.keySet())
        || raw.get("authentication") != null
        || raw.get("exception") != null) {
      throw new IllegalArgumentException("Validation API trace payload is malformed");
    }
    Map<String, Object> trace = stringMap(raw);
    Map<String, Object> requestBody = stringMap(trace.get("requestBody"));
    URI uri;
    try {
      uri = URI.create(requiredString(requestBody.get("url")));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Validation API trace URL is malformed", invalid);
    }
    SafeTradingLabHttpTrace sealed = traceSanitizer.sanitize(new TradingLabHttpTraceInput(
        uri,
        traceHeaders(trace.get("requestHeaders")),
        traceHeaders(trace.get("responseHeaders")),
        nullableString(trace.get("requestContentType")),
        requestBody,
        nullableString(trace.get("responseContentType")),
        stringMap(trace.get("responseBody")),
        null,
        null,
        List.of(),
        null));
    if (!sealed.toSafeMap().equals(trace)) {
      throw new IllegalArgumentException("Validation API trace is not canonical");
    }
    return sealed;
  }

  private static Map<String, List<String>> traceHeaders(Object value) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException("Validation API trace headers are malformed");
    }
    LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
    raw.forEach((key, nested) -> {
      if (!(key instanceof String text) || headers.containsKey(text)) {
        throw new IllegalArgumentException("Validation API trace headers are malformed");
      }
      if (!(nested instanceof List<?> values)) {
        throw new IllegalArgumentException("Validation API trace headers are malformed");
      }
      ArrayList<String> copied = new ArrayList<>(values.size());
      for (Object item : values) {
        if (!(item instanceof String headerValue)) {
          throw new IllegalArgumentException("Validation API trace headers are malformed");
        }
        copied.add(headerValue);
      }
      headers.put(text, List.copyOf(copied));
    });
    return Collections.unmodifiableMap(headers);
  }

  private static String nullableString(Object value) {
    return value == null ? null : requiredString(value);
  }

  private static ValidationRunEvent validationEvent(
      TradingLabCoordinatorEvidence evidence
  ) {
    if (!TradingLabCoordinatorEvidenceStore.VALIDATION_EVENT.equals(evidence.eventType())) {
      throw new IllegalArgumentException("Evidence is not a validation event");
    }
    Object raw = evidence.payload().get("evidence");
    if (!(raw instanceof Map<?, ?> safe)
        || !VALIDATION_EVENT_KEYS.equals(safe.keySet())) {
      throw new IllegalArgumentException("Validation event evidence is malformed");
    }
    UUID runId = canonicalUuid(safe.get("runId"));
    long sequence = exactLong(safe.get("sequence"));
    String durableKey = requiredString(safe.get("durableKey"));
    String fingerprint = requiredString(safe.get("fingerprint"));
    String type = requiredString(safe.get("type"));
    Instant virtualTime = optionalInstant(safe.get("virtualTime"));
    String correlationId = optionalString(safe.get("correlationId"));
    Map<String, Object> payload = stringMap(safe.get("payload"));
    ValidationRunEvent event = new ValidationRunEvent(
        runId,
        sequence,
        durableKey,
        fingerprint,
        type,
        virtualTime,
        correlationId,
        payload);
    if (!evidence.runId().equals(event.runId())
        || !Objects.equals(evidence.virtualTime(), event.virtualTime())
        || !Objects.equals(evidence.correlationId(), event.correlationId())
        || !Objects.equals(evidence.validationSequence(), event.sequence())) {
      throw new IllegalArgumentException("Validation event evidence identity is inconsistent");
    }
    return event;
  }

  private static UUID canonicalUuid(Object value) {
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("Validation event runId is malformed");
    }
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) {
        throw new IllegalArgumentException("Validation event runId is not canonical");
      }
      return parsed;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Validation event runId is malformed", invalid);
    }
  }

  private static long exactLong(Object value) {
    try {
      if (value instanceof BigInteger integer) {
        return integer.longValueExact();
      }
      if (value instanceof BigDecimal decimal) {
        return decimal.longValueExact();
      }
      if (value instanceof Byte number) {
        return number.longValue();
      }
      if (value instanceof Short number) {
        return number.longValue();
      }
      if (value instanceof Integer number) {
        return number.longValue();
      }
      if (value instanceof Long number) {
        return number;
      }
    } catch (ArithmeticException invalid) {
      throw new IllegalArgumentException("Validation event sequence is malformed", invalid);
    }
    throw new IllegalArgumentException("Validation event sequence is malformed");
  }

  private static String requiredString(Object value) {
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("Validation event string field is malformed");
    }
    return text;
  }

  private static String optionalString(Object value) {
    return value == null ? null : requiredString(value);
  }

  private static Instant optionalInstant(Object value) {
    if (value == null) {
      return null;
    }
    String text = requiredString(value);
    try {
      Instant instant = Instant.parse(text);
      if (!instant.toString().equals(text)) {
        throw new IllegalArgumentException("Validation event virtualTime is not canonical");
      }
      return instant;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Validation event virtualTime is malformed", invalid);
    }
  }

  private static Map<String, Object> stringMap(Object value) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException("Validation event payload is malformed");
    }
    Map<String, Object> typed = new LinkedHashMap<>();
    raw.forEach((key, nested) -> {
      if (!(key instanceof String text)) {
        throw new IllegalArgumentException("Validation event payload key is malformed");
      }
      typed.put(text, nested);
    });
    return typed;
  }

  private long durableCursor(
      UUID runId,
      String claimOwner,
      TradingLabReportSection section
  ) {
    TradingLabReportWriteFence fence = freshFence(runId, claimOwner);
    return writer.highestDurableSourceSequence(fence, section);
  }

  private void flushFresh(UUID runId, String claimOwner) {
    writer.flush(freshFence(runId, claimOwner));
  }

  private TradingLabReportWriteFence freshFence(UUID runId, String claimOwner) {
    return runLoader.requireLive(runId, claimOwner).reportFence();
  }

  private static ValidationHttpResult httpResult(TradingLabCoordinatorEvidence evidence) {
    Object raw = evidence.payload().get("evidence");
    if (!(raw instanceof Map<?, ?> safe)) {
      throw new IllegalArgumentException("HTTP evidence payload is missing");
    }
    ValidationHttpResult result = ValidationHttpResult.fromSafeMap(safe);
    if (result.sequence() != evidence.sequence()) {
      throw new IllegalArgumentException("HTTP evidence sequence is inconsistent");
    }
    return result;
  }
}
