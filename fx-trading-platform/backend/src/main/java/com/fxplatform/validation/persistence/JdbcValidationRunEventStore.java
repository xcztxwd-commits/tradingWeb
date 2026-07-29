package com.fxplatform.validation.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationResetGate;
import com.fxplatform.validation.service.ValidationRunEventStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL event journal with one row-locked sequence allocator per validation run. */
@Profile("validation")
@Repository
public class JdbcValidationRunEventStore implements ValidationRunEventStore {

  private static final String VALIDATION_EVENT_CONFLICT = EVENT_CONFLICT_CODE;
  private static final Set<String> TERMINAL_STATES =
      Set.of("CANCELLED", "FAILED", "COMPLETED");

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final ValidationResetGate resetGate;
  private final ValidationCanonicalJson canonicalJson;

  public JdbcValidationRunEventStore(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      ValidationResetGate resetGate
  ) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.resetGate = resetGate;
    this.canonicalJson = new ValidationCanonicalJson(objectMapper);
  }

  @Override
  @Transactional
  public RunEvent append(EventWrite request) {
    RunFence run = jdbc.query(
            """
                SELECT generation, next_event_sequence
                  FROM validation_runtime.run_executions
                 WHERE id = ?
                 FOR UPDATE
                """,
            (resultSet, rowNumber) -> new RunFence(
                resultSet.getLong("generation"),
                resultSet.getLong("next_event_sequence")),
            request.runId())
        .stream()
        .findFirst()
        .orElseThrow(() -> failure("VALIDATION_RUN_NOT_FOUND", "Validation run was not found"));
    resetGate.requireReadyGeneration(run.generation());

    RunEvent existing = findByDurableKey(request.runId(), request.durableKey());
    if (existing != null) {
      requireExactReplay(existing, request);
      return existing;
    }

    long sequence = Math.addExact(run.nextEventSequence(), 1L);
    int allocated = jdbc.update(
        """
            UPDATE validation_runtime.run_executions
               SET next_event_sequence = ?, updated_at = now(), version = version + 1
             WHERE id = ? AND generation = ?
            """,
        sequence,
        request.runId(),
        run.generation());
    if (allocated != 1) {
      throw failure("VALIDATION_GENERATION_FENCED", "Validation run generation changed");
    }
    jdbc.update(
        """
            INSERT INTO validation_runtime.run_events (
              run_id, generation, sequence, durable_event_key, request_fingerprint,
              event_type, virtual_time, correlation_id, payload_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """,
        request.runId(),
        run.generation(),
        sequence,
        request.durableKey(),
        request.fingerprint(),
        request.type(),
        timestamp(request.virtualTime()),
        request.correlationId(),
        json(request.payload()));
    return findByDurableKey(request.runId(), request.durableKey());
  }

  @Override
  @Transactional(readOnly = true)
  public EventPage eventsAfter(UUID runId, long afterSequence, int limit) {
    if (afterSequence < 0L || limit < 1 || limit > MAX_PAGE_SIZE) {
      throw failure(
          "VALIDATION_EVENT_PAGE_INVALID",
          "afterSequence and limit are outside the validation event page bounds");
    }
    RunStatus status = jdbc.query(
            """
                SELECT next_event_sequence, state
                  FROM validation_runtime.run_executions
                 WHERE id = ?
                """,
            (resultSet, rowNumber) -> new RunStatus(
                resultSet.getLong("next_event_sequence"),
                resultSet.getString("state")),
            runId)
        .stream()
        .findFirst()
        .orElseThrow(() -> failure("VALIDATION_RUN_NOT_FOUND", "Validation run was not found"));

    List<RunEvent> fetched = jdbc.query(
        """
            SELECT run_id, sequence, durable_event_key, request_fingerprint, event_type,
                   virtual_time, correlation_id, payload_json
              FROM validation_runtime.run_events
             WHERE run_id = ? AND sequence > ? AND sequence <= ?
             ORDER BY sequence
             LIMIT ?
            """,
        this::mapEvent,
        runId,
        afterSequence,
        status.highWatermark(),
        limit + 1);
    boolean hasMore = fetched.size() > limit;
    List<RunEvent> events = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
    return new EventPage(
        events,
        status.highWatermark(),
        TERMINAL_STATES.contains(status.state()),
        hasMore);
  }

  private RunEvent findByDurableKey(UUID runId, String durableKey) {
    return jdbc.query(
            """
                SELECT run_id, sequence, durable_event_key, request_fingerprint, event_type,
                       virtual_time, correlation_id, payload_json
                  FROM validation_runtime.run_events
                 WHERE run_id = ? AND durable_event_key = ?
                """,
            this::mapEvent,
            runId,
            durableKey)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private RunEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
    return new RunEvent(
        resultSet.getObject("run_id", UUID.class),
        resultSet.getLong("sequence"),
        resultSet.getString("durable_event_key"),
        resultSet.getString("request_fingerprint"),
        resultSet.getString("event_type"),
        instant(resultSet.getTimestamp("virtual_time")),
        resultSet.getString("correlation_id"),
        map(resultSet.getString("payload_json")));
  }

  private void requireExactReplay(RunEvent existing, EventWrite request) {
    if (!existing.fingerprint().equals(request.fingerprint())
        || !existing.type().equals(request.type())
        || !java.util.Objects.equals(existing.virtualTime(), request.virtualTime())
        || !java.util.Objects.equals(existing.correlationId(), request.correlationId())
        || !canonicalJson.equivalent(existing.payload(), request.payload())) {
      throw failure(
          VALIDATION_EVENT_CONFLICT,
          "Durable validation event key already has different content");
    }
  }

  private Map<String, Object> map(String value) {
    try {
      return objectMapper.readValue(value, new TypeReference<>() { });
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored validation event JSON is invalid", exception);
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Validation event payload is not serializable", exception);
    }
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static BusinessException failure(String code, String message) {
    return new BusinessException(code, message);
  }

  private record RunFence(long generation, long nextEventSequence) {
  }

  private record RunStatus(long highWatermark, String state) {
  }
}
