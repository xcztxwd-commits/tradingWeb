package com.fxplatform.validation.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationDemoExecutionPolicyProvider;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Command;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.HttpResult;
import com.fxplatform.validation.service.ValidationMarketState;
import com.fxplatform.validation.service.ValidationResetGate;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import com.fxplatform.validation.service.ValidationRunEventStore;
import com.fxplatform.validation.service.ValidationRunEventStore.EventWrite;
import com.fxplatform.validation.service.ValidationRunRuntimeStore;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable validation runtime with generation, lease, identity, and event commit fences. */
@Profile("validation")
@Repository
public class JdbcValidationRunRuntimeStore implements ValidationRunRuntimeStore {

  private static final String RECOVERABLE_STATES =
      "'ACCEPTED','STARTING','RUNNING','RECOVERING','CANCELLING'";
  private static final String CONTROLLABLE_STATES =
      "'ACCEPTED','STARTING','RUNNING','PAUSED','RECOVERING','RECOVERY_BLOCKED','CANCELLING'";
  private static final String PAUSABLE_STATES =
      "'ACCEPTED','STARTING','RUNNING','RECOVERING'";
  private static final String TERMINAL_STATES = "'CANCELLED','FAILED','COMPLETED'";
  private static final int ADVISORY_NAMESPACE = 0x56414C49; // "VALI"
  private static final int RUN_OWNER_KEY = 0x52554E31; // "RUN1"

  private final JdbcTemplate jdbc;
  private final DataSource dataSource;
  private final ObjectMapper objectMapper;
  private final ValidationResetGate resetGate;
  private final ValidationMarketState marketState;
  private final ValidationDemoExecutionPolicyProvider executionPolicyProvider;
  private final ValidationRunEventStore eventStore;
  private final ValidationCanonicalJson canonicalJson;
  private final String leaseOwner = "validation-backend-" + UUID.randomUUID();
  private final ConcurrentMap<UUID, RunOwnership> liveLeases = new ConcurrentHashMap<>();

  public JdbcValidationRunRuntimeStore(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      ValidationResetGate resetGate,
      ValidationMarketState marketState,
      ValidationDemoExecutionPolicyProvider executionPolicyProvider,
      ValidationRunEventStore eventStore
  ) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.resetGate = Objects.requireNonNull(resetGate, "resetGate");
    this.marketState = Objects.requireNonNull(marketState, "marketState");
    this.executionPolicyProvider = Objects.requireNonNull(
        executionPolicyProvider, "executionPolicyProvider");
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    this.canonicalJson = new ValidationCanonicalJson(objectMapper);
  }

  @Override
  @Transactional
  public boolean accept(StartRequest request, EventWrite acceptedEvent) {
    resetGate.requireReadyGeneration(request.generation());
    requireRunEvent(request.runId(), acceptedEvent);
    StoredRun existing = findStoredRun(request.runId(), false);
    if (existing != null) {
      requireExactStart(existing, request);
      eventStore.append(acceptedEvent);
      return false;
    }
    int inserted = jdbc.update(
        """
            INSERT INTO validation_runtime.run_executions (
              id, generation, request_fingerprint, request_json, state,
              virtual_started_at, speed_multiplier
            )
            SELECT ?, ?, ?, ?::jsonb, 'ACCEPTED', ?, ?
             WHERE EXISTS (
               SELECT 1 FROM validation_control.reset_state
                WHERE singleton_key = 1 AND generation = ? AND state = 'READY'
             )
            ON CONFLICT DO NOTHING
            """,
        request.runId(),
        request.generation(),
        request.requestFingerprint(),
        json(request),
        Timestamp.from(request.virtualStart()),
        request.speedMultiplier(),
        request.generation());
    if (inserted == 1) {
      eventStore.append(acceptedEvent);
      return true;
    }
    StoredRun raced = findStoredRun(request.runId(), false);
    if (raced != null) {
      requireExactStart(raced, request);
      eventStore.append(acceptedEvent);
      return false;
    }
    Integer occupied = jdbc.queryForObject(
        "SELECT COUNT(*) FROM validation_runtime.run_executions WHERE generation = ?",
        Integer.class,
        request.generation());
    if (occupied != null && occupied > 0) {
      throw failure(
          "VALIDATION_RUN_GENERATION_OCCUPIED",
          "The reset generation already owns a validation run");
    }
    throw failure("VALIDATION_GENERATION_FENCED", "Validation generation is not ready");
  }

  @Override
  public LeaseClaim claim(UUID runId) {
    long generation = requireGeneration(runId);
    UUID token = UUID.randomUUID();
    RunOwnership ownership = tryAcquireRunOwnership(runId, generation, token);
    if (ownership == null) {
      throw notClaimable();
    }
    try {
      ownership.claimRow();
      liveLeases.put(token, ownership);
      return ownership.claim();
    } catch (RuntimeException failure) {
      ownership.close();
      throw failure;
    }
  }

  @Override
  public void releaseLease(LeaseClaim claim) {
    if (claim == null) {
      return;
    }
    RunOwnership ownership = liveLeases.remove(claim.token());
    if (ownership == null || !ownership.matches(claim)) {
      return;
    }
    try {
      jdbc.update(
          ("""
              UPDATE validation_runtime.run_executions
                 SET lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                     updated_at = now(), version = version + 1
               WHERE id = ? AND generation = ? AND lease_owner = ? AND lease_token = ?
                 AND state IN (%s)
              """).formatted(RECOVERABLE_STATES),
          claim.runId(),
          claim.generation(),
          claim.owner(),
          claim.token());
    } catch (RuntimeException ignored) {
      // Closing the authoritative session below is sufficient for immediate safe takeover.
    } finally {
      ownership.close();
    }
  }

  @Override
  @Transactional
  public void heartbeat(LeaseClaim claim) {
    requireLiveLease(claim);
    resetGate.requireReadyGeneration(claim.generation());
    int updated = jdbc.update(
        ("""
            UPDATE validation_runtime.run_executions
               SET updated_at = now(), version = version + 1
             WHERE id = ? AND generation = ? AND lease_owner = ? AND lease_token = ?
               AND state IN (%s)
            """).formatted(RECOVERABLE_STATES),
        claim.runId(),
        claim.generation(),
        claim.owner(),
        claim.token());
    if (updated != 1) {
      throw leaseLost();
    }
  }

  @Override
  @Transactional
  public List<UUID> takeOverRecoverableRunIds() {
    long generation = resetGate.readyGeneration();
    resetGate.requireReadyGeneration(generation);
    return jdbc.queryForList(
        "SELECT id FROM validation_runtime.run_executions WHERE state IN ("
            + RECOVERABLE_STATES
            + ") ORDER BY created_at, id",
        UUID.class);
  }

  @Override
  @Transactional
  public StartRequest require(UUID runId) {
    StoredRun stored = findStoredRun(runId, false);
    if (stored == null) {
      throw failure("VALIDATION_RUN_NOT_FOUND", "Validation run was not found");
    }
    resetGate.requireReadyGeneration(stored.generation());
    return startRequest(stored.requestJson());
  }

  @Override
  @Transactional
  public Optional<Boundary> lastCompletedBoundary(LeaseClaim claim) {
    StoredRun stored = requireLease(claim, false);
    if (stored.lastCompletedTickSequence() < 0L) {
      return Optional.empty();
    }
    StartRequest request = startRequest(stored.requestJson());
    String tickFingerprint = request.ticks().stream()
        .filter(tick -> tick.sequence() == stored.lastCompletedTickSequence())
        .map(tick -> tick.fingerprint())
        .findFirst()
        .orElseThrow(() -> failure(
            "VALIDATION_BOUNDARY_CORRUPT",
            "Durable boundary does not exist in the frozen market path"));
    Instant virtualTime = stored.virtualCurrentAt() == null
        ? request.virtualStart().plusSeconds(stored.lastCompletedTickSequence())
        : stored.virtualCurrentAt();
    return Optional.of(new Boundary(
        claim.runId(),
        claim.generation(),
        stored.lastCompletedTickSequence(),
        virtualTime,
        tickFingerprint,
        Integer.toHexString(request.executionPolicy().hashCode())));
  }

  @Override
  @Transactional
  public OperationIntent persistIntent(LeaseClaim claim, Command request) {
    requireCommandClaim(claim, request);
    StoredRun run = requireLease(claim, true);
    OperationRow existing = findOperation(request.runId(), request.idempotencyKey());
    if (existing != null) {
      requireExactOperation(existing, request);
      return intent(existing);
    }
    Long maximum = jdbc.queryForObject(
        "SELECT COALESCE(MAX(operation_sequence), -1) FROM validation_runtime.operations WHERE run_id = ?",
        Long.class,
        request.runId());
    long sequence = Math.addExact(maximum == null ? -1L : maximum, 1L);
    UUID operationId = UUID.randomUUID();
    Instant virtualTime = virtualTime(startRequest(run.requestJson()), request.tickSequence());
    jdbc.update(
        """
            INSERT INTO validation_runtime.operations (
              id, run_id, generation, operation_sequence, operation, idempotency_key,
              request_fingerprint, request_json, state, virtual_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'INTENT', ?)
            """,
        operationId,
        request.runId(),
        request.generation(),
        sequence,
        request.operation().name(),
        request.idempotencyKey(),
        request.requestFingerprint(),
        json(request),
        timestamp(virtualTime));
    return new OperationIntent(
        operationId,
        request,
        Instant.now(),
        OperationState.NEW,
        null);
  }

  @Override
  @Transactional
  public void completeIntent(
      LeaseClaim claim,
      UUID operationId,
      Completion completion,
      HttpResult result,
      List<EventWrite> events
  ) {
    Objects.requireNonNull(result, "result");
    requireLease(claim, true);
    OperationRow stored = requireOperation(operationId, claim);
    String targetState = completion == Completion.RECOVERED ? "RECOVERED" : "COMPLETED";
    if ("INTENT".equals(stored.state())) {
      jdbc.update(
          """
              UPDATE validation_runtime.operations
                 SET state = ?, http_status = ?, correlation_id = ?, response_json = ?::jsonb,
                     completed_at = now(), updated_at = now()
               WHERE id = ? AND run_id = ? AND generation = ? AND state = 'INTENT'
              """,
          targetState,
          result.status(),
          result.correlationId(),
          json(result.body()),
          operationId,
          claim.runId(),
          claim.generation());
    } else if (!Set.of("COMPLETED", "RECOVERED").contains(stored.state())
        || !sameResult(stored, result)) {
      throw failure("VALIDATION_OPERATION_CONFLICT", "Validation operation cannot be completed");
    }
    appendAll(claim.runId(), events);
  }

  @Override
  @Transactional
  public void recordFirstHttpObservation(
      LeaseClaim claim,
      UUID operationId,
      HttpResult result,
      EventWrite event
  ) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(event, "event");
    if (result.status() < 500) {
      throw new IllegalArgumentException("Only uncertain HTTP responses can be observed");
    }
    requireLease(claim, true);
    OperationRow stored = requireOperation(operationId, claim);
    if (!"INTENT".equals(stored.state())) {
      throw failure(
          "VALIDATION_OPERATION_CONFLICT",
          "Validation operation cannot record an HTTP observation");
    }
    if (stored.httpStatus() != null || stored.responseJson() != null) {
      if (stored.httpStatus() == null || stored.responseJson() == null) {
        throw failure(
            "VALIDATION_OPERATION_CORRUPT",
            "Validation operation HTTP observation is incomplete");
      }
      return;
    }
    jdbc.update(
        """
            UPDATE validation_runtime.operations
               SET http_status = ?, correlation_id = ?, response_json = ?::jsonb,
                   updated_at = now()
             WHERE id = ? AND run_id = ? AND generation = ?
               AND state = 'INTENT' AND http_status IS NULL
            """,
        result.status(),
        result.correlationId(),
        json(result.body()),
        operationId,
        claim.runId(),
        claim.generation());
    appendAll(claim.runId(), List.of(event));
  }

  @Override
  @Transactional
  public void failIntent(
      LeaseClaim claim,
      UUID operationId,
      String failureCode,
      String failureMessage,
      HttpResult result,
      List<EventWrite> events
  ) {
    requireLease(claim, true);
    OperationRow stored = requireOperation(operationId, claim);
    String safeMessage = safeFailureMessage(failureMessage);
    if ("INTENT".equals(stored.state())) {
      jdbc.update(
          """
              UPDATE validation_runtime.operations
                 SET state = 'FAILED', failure_code = ?, failure_message = ?,
                     http_status = ?, correlation_id = ?, response_json = ?::jsonb,
                     completed_at = now(), updated_at = now()
               WHERE id = ? AND run_id = ? AND generation = ? AND state = 'INTENT'
              """,
          failureCode,
          safeMessage,
          result == null ? stored.httpStatus() : result.status(),
          result == null ? stored.correlationId() : result.correlationId(),
          result == null
              ? stored.responseJson()
              : json(result.body()),
          operationId,
          claim.runId(),
          claim.generation());
    } else if (!"FAILED".equals(stored.state())
        || !Objects.equals(stored.failureCode(), failureCode)
        || !Objects.equals(stored.failureMessage(), safeMessage)
        || result != null && !sameResult(stored, result)) {
      throw failure("VALIDATION_OPERATION_CONFLICT", "Validation operation cannot be failed");
    }
    appendAll(claim.runId(), events);
  }

  @Override
  @Transactional
  public void appendEvents(LeaseClaim claim, List<EventWrite> events) {
    requireLease(claim, true);
    appendAll(claim.runId(), events);
  }

  @Override
  @Transactional
  public void completeBoundary(
      LeaseClaim claim,
      Boundary boundary,
      List<EventWrite> events
  ) {
    requireBoundaryClaim(claim, boundary);
    StoredRun run = requireLease(claim, true);
    if (run.lastCompletedTickSequence() > boundary.tickSequence()
        || (run.lastCompletedTickSequence() == boundary.tickSequence()
            && !Objects.equals(run.virtualCurrentAt(), boundary.virtualTime()))) {
      throw failure("VALIDATION_BOUNDARY_CONFLICT", "Validation boundary conflicts with durable state");
    }
    jdbc.update(
        """
            UPDATE validation_runtime.run_executions
               SET last_completed_tick_sequence = ?, virtual_current_at = ?,
                   updated_at = now(), version = version + 1
             WHERE id = ? AND generation = ? AND lease_owner = ? AND lease_token = ?
            """,
        boundary.tickSequence(),
        Timestamp.from(boundary.virtualTime()),
        claim.runId(),
        claim.generation(),
        claim.owner(),
        claim.token());
    appendAll(claim.runId(), events);
  }

  @Override
  @Transactional
  public void restore(LeaseClaim claim, Boundary boundary) {
    requireBoundaryClaim(claim, boundary);
    StoredRun run = requireLease(claim, true);
    if (run.lastCompletedTickSequence() != boundary.tickSequence()
        || !Objects.equals(run.virtualCurrentAt(), boundary.virtualTime())) {
      throw failure("VALIDATION_BOUNDARY_CONFLICT", "Durable validation boundary changed");
    }
    StartRequest request = startRequest(run.requestJson());
    var tick = request.ticks().stream()
        .filter(candidate -> candidate.sequence() == boundary.tickSequence())
        .findFirst()
        .orElseThrow(() -> failure(
            "VALIDATION_BOUNDARY_CORRUPT",
            "Durable boundary does not exist in the frozen market path"));
    marketState.restore(tick);
    executionPolicyProvider.restore(
        request.runId(),
        request.generation(),
        request.requestFingerprint(),
        request.executionPolicy());
  }

  @Override
  @Transactional
  public void transition(
      LeaseClaim claim,
      State state,
      String reason,
      EventWrite event
  ) {
    requireLease(claim, true);
    boolean releasesLease = releasesLease(state);
    int updated = jdbc.update(
        ("""
            UPDATE validation_runtime.run_executions
               SET state = ?,
                   failure_code = CASE WHEN ? IN ('FAILED','RECOVERY_BLOCKED') THEN ? ELSE failure_code END,
                   started_at = CASE WHEN ? = 'RUNNING' AND started_at IS NULL THEN now() ELSE started_at END,
                   finished_at = CASE WHEN ? IN ('CANCELLED','FAILED','COMPLETED') THEN now() ELSE finished_at END,
                   lease_owner = CASE WHEN ? THEN NULL ELSE lease_owner END,
                   lease_token = CASE WHEN ? THEN NULL ELSE lease_token END,
                   lease_until = CASE WHEN ? THEN NULL ELSE lease_until END,
                   updated_at = now(), version = version + 1
             WHERE id = ? AND generation = ? AND lease_owner = ? AND lease_token = ?
               AND state NOT IN (%s)
            """).formatted(TERMINAL_STATES),
        state.name(),
        state.name(),
        reason,
        state.name(),
        state.name(),
        releasesLease,
        releasesLease,
        releasesLease,
        claim.runId(),
        claim.generation(),
        claim.owner(),
        claim.token());
    if (updated != 1) {
      throw leaseLost();
    }
    eventStore.append(event);
  }

  @Override
  @Transactional
  public BoundaryOutcome settleBeforeWork(
      LeaseClaim claim,
      EventWrite pausedEvent,
      EventWrite cancelledEvent
  ) {
    StoredRun run = requireLease(claim, true);
    if (run.cancelRequested()) {
      terminalize(claim, State.CANCELLED, cancelledEvent);
      return BoundaryOutcome.CANCELLED;
    }
    if (run.pauseRequested()) {
      terminalize(claim, State.PAUSED, pausedEvent);
      return BoundaryOutcome.PAUSED;
    }
    return BoundaryOutcome.CONTINUE;
  }

  @Override
  @Transactional
  public BoundaryOutcome settleBoundary(
      LeaseClaim claim,
      boolean hasMoreTicks,
      EventWrite pausedEvent,
      EventWrite cancelledEvent,
      EventWrite completedEvent
  ) {
    StoredRun run = requireLease(claim, true);
    if (run.cancelRequested()) {
      terminalize(claim, State.CANCELLED, cancelledEvent);
      return BoundaryOutcome.CANCELLED;
    }
    if (run.pauseRequested()) {
      terminalize(claim, State.PAUSED, pausedEvent);
      return BoundaryOutcome.PAUSED;
    }
    if (!hasMoreTicks) {
      terminalize(claim, State.COMPLETED, completedEvent);
      return BoundaryOutcome.COMPLETED;
    }
    heartbeat(claim);
    return BoundaryOutcome.CONTINUE;
  }

  @Override
  @Transactional
  public void bindIdentity(LeaseClaim claim, UUID userId, UUID accountId) {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(accountId, "accountId");
    StoredRun run = requireLease(claim, true);
    if ((run.userId() != null && !run.userId().equals(userId))
        || (run.accountId() != null && !run.accountId().equals(accountId))) {
      throw failure(
          "VALIDATION_RUN_IDENTITY_CONFLICT",
          "Recovered validation identity does not match the frozen run");
    }
    jdbc.update(
        """
            UPDATE validation_runtime.run_executions
               SET user_id = ?, account_id = ?, updated_at = now(), version = version + 1
             WHERE id = ? AND generation = ? AND lease_owner = ? AND lease_token = ?
               AND (user_id IS NULL OR user_id = ?) AND (account_id IS NULL OR account_id = ?)
            """,
        userId,
        accountId,
        claim.runId(),
        claim.generation(),
        claim.owner(),
        claim.token(),
        userId,
        accountId);
  }

  @Override
  @Transactional
  public void requestPause(UUID runId) {
    StoredRun run = requireControlRun(runId);
    if (run.state() == State.PAUSED) {
      return;
    }
    int updated = jdbc.update(
        ("""
            UPDATE validation_runtime.run_executions
               SET pause_requested = TRUE, updated_at = now(), version = version + 1
             WHERE id = ? AND generation = ? AND state IN (%s)
            """).formatted(PAUSABLE_STATES),
        runId,
        run.generation());
    if (updated != 1) {
      throw controlRejected("PAUSE");
    }
  }

  @Override
  @Transactional
  public void requestResume(UUID runId) {
    long generation = requireGeneration(runId);
    int updated = jdbc.update(
        """
            UPDATE validation_runtime.run_executions
               SET pause_requested = FALSE, state = 'ACCEPTED',
                   lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                   updated_at = now(), version = version + 1
             WHERE id = ? AND generation = ? AND state = 'PAUSED' AND cancel_requested = FALSE
            """,
        runId,
        generation);
    if (updated != 1) {
      throw failure("VALIDATION_RUN_NOT_PAUSED", "Validation run is not resumable");
    }
  }

  @Override
  @Transactional
  public State requestCancel(UUID runId, EventWrite cancelledEvent) {
    StoredRun run = requireControlRun(runId);
    requireRunEvent(runId, cancelledEvent);
    if (run.state() == State.CANCELLED) {
      return State.CANCELLED;
    }
    if (Set.of(State.FAILED, State.COMPLETED).contains(run.state())) {
      throw controlRejected("CANCEL");
    }
    boolean immediate = run.state() == State.PAUSED
        || run.state() == State.RECOVERY_BLOCKED
        || (run.state() == State.ACCEPTED && run.leaseToken() == null);
    if (immediate) {
      jdbc.update(
          ("""
              UPDATE validation_runtime.run_executions
                 SET cancel_requested = TRUE, state = 'CANCELLED', finished_at = now(),
                     lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                     updated_at = now(), version = version + 1
               WHERE id = ? AND generation = ? AND state NOT IN (%s)
              """).formatted(TERMINAL_STATES),
          runId,
          run.generation());
      eventStore.append(cancelledEvent);
      return State.CANCELLED;
    }
    int updated = jdbc.update(
        ("""
            UPDATE validation_runtime.run_executions
               SET cancel_requested = TRUE, updated_at = now(), version = version + 1
             WHERE id = ? AND generation = ? AND state IN (%s)
            """).formatted(CONTROLLABLE_STATES),
        runId,
        run.generation());
    if (updated != 1) {
      throw controlRejected("CANCEL");
    }
    return run.state();
  }

  @Override
  @Transactional
  public State currentState(UUID runId) {
    StoredRun run = findStoredRun(runId, false);
    if (run == null) {
      throw failure("VALIDATION_RUN_NOT_FOUND", "Validation run was not found");
    }
    resetGate.requireReadyGeneration(run.generation());
    return run.state();
  }

  private StoredRun requireLease(LeaseClaim claim, boolean lock) {
    requireLiveLease(claim);
    resetGate.requireReadyGeneration(claim.generation());
    StoredRun stored = jdbc.query(
            """
                SELECT id, generation, request_fingerprint, request_json, state,
                       pause_requested, cancel_requested, user_id, account_id,
                       last_completed_tick_sequence, virtual_current_at,
                       lease_owner, lease_token
                  FROM validation_runtime.run_executions
                 WHERE id = ? AND generation = ? AND lease_owner = ? AND lease_token = ?
                """ + (lock ? " FOR UPDATE" : ""),
            this::mapStoredRun,
            claim.runId(),
            claim.generation(),
            claim.owner(),
            claim.token())
        .stream()
        .findFirst()
        .orElseThrow(JdbcValidationRunRuntimeStore::leaseLost);
    return stored;
  }

  private StoredRun requireControlRun(UUID runId) {
    StoredRun run = findStoredRun(runId, true);
    if (run == null) {
      throw failure("VALIDATION_RUN_NOT_FOUND", "Validation run was not found");
    }
    resetGate.requireReadyGeneration(run.generation());
    return run;
  }

  private void terminalize(LeaseClaim claim, State state, EventWrite event) {
    boolean terminal = state != State.PAUSED;
    int updated = jdbc.update(
        ("""
            UPDATE validation_runtime.run_executions
               SET state = ?,
                   finished_at = CASE WHEN ? THEN now() ELSE finished_at END,
                   lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                   updated_at = now(), version = version + 1
             WHERE id = ? AND generation = ? AND lease_owner = ? AND lease_token = ?
               AND state NOT IN (%s)
            """).formatted(TERMINAL_STATES),
        state.name(),
        terminal,
        claim.runId(),
        claim.generation(),
        claim.owner(),
        claim.token());
    if (updated != 1) {
      throw leaseLost();
    }
    eventStore.append(event);
  }

  private StoredRun findStoredRun(UUID runId, boolean lock) {
    return jdbc.query(
            """
                SELECT id, generation, request_fingerprint, request_json, state,
                       pause_requested, cancel_requested, user_id, account_id,
                       last_completed_tick_sequence, virtual_current_at,
                       lease_owner, lease_token
                  FROM validation_runtime.run_executions
                 WHERE id = ?
                """ + (lock ? " FOR UPDATE" : ""),
            this::mapStoredRun,
            runId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private StoredRun mapStoredRun(ResultSet resultSet, int rowNumber) throws SQLException {
    Timestamp virtualCurrent = resultSet.getTimestamp("virtual_current_at");
    return new StoredRun(
        resultSet.getObject("id", UUID.class),
        resultSet.getLong("generation"),
        resultSet.getString("request_fingerprint"),
        resultSet.getString("request_json"),
        State.valueOf(resultSet.getString("state")),
        resultSet.getBoolean("pause_requested"),
        resultSet.getBoolean("cancel_requested"),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getObject("account_id", UUID.class),
        resultSet.getLong("last_completed_tick_sequence"),
        virtualCurrent == null ? null : virtualCurrent.toInstant(),
        resultSet.getString("lease_owner"),
        resultSet.getObject("lease_token", UUID.class));
  }

  private OperationRow findOperation(UUID runId, String idempotencyKey) {
    return jdbc.query(
            """
                SELECT id, run_id, generation, request_fingerprint, request_json, state,
                       http_status, correlation_id, response_json, failure_code, failure_message,
                       created_at
                  FROM validation_runtime.operations
                 WHERE run_id = ? AND idempotency_key = ?
                """,
            this::mapOperation,
            runId,
            idempotencyKey)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private OperationRow requireOperation(UUID operationId, LeaseClaim claim) {
    return jdbc.query(
            """
                SELECT id, run_id, generation, request_fingerprint, request_json, state,
                       http_status, correlation_id, response_json, failure_code, failure_message,
                       created_at
                  FROM validation_runtime.operations
                 WHERE id = ? AND run_id = ? AND generation = ?
                 FOR UPDATE
                """,
            this::mapOperation,
            operationId,
            claim.runId(),
            claim.generation())
        .stream()
        .findFirst()
        .orElseThrow(() -> failure(
            "VALIDATION_OPERATION_NOT_FOUND", "Validation operation was not found"));
  }

  private OperationRow mapOperation(ResultSet resultSet, int rowNumber) throws SQLException {
    Number rawStatus = (Number) resultSet.getObject("http_status");
    Integer status = rawStatus == null ? null : rawStatus.intValue();
    return new OperationRow(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("run_id", UUID.class),
        resultSet.getLong("generation"),
        resultSet.getString("request_fingerprint"),
        resultSet.getString("request_json"),
        resultSet.getString("state"),
        status,
        resultSet.getString("correlation_id"),
        resultSet.getString("response_json"),
        resultSet.getString("failure_code"),
        resultSet.getString("failure_message"),
        resultSet.getTimestamp("created_at").toInstant());
  }

  private OperationIntent intent(OperationRow row) {
    OperationState state = switch (row.state()) {
      case "INTENT" -> OperationState.UNFINISHED;
      case "COMPLETED" -> OperationState.COMPLETED;
      case "RECOVERED" -> OperationState.RECOVERED;
      case "FAILED" -> OperationState.FAILED;
      default -> throw new IllegalStateException("Unknown validation operation state");
    };
    HttpResult result = Set.of(OperationState.COMPLETED, OperationState.RECOVERED).contains(state)
        ? result(row)
        : null;
    return new OperationIntent(row.id(), command(row.requestJson()), row.createdAt(), state, result);
  }

  private HttpResult result(OperationRow row) {
    if (row.httpStatus() == null || row.responseJson() == null) {
      throw failure("VALIDATION_OPERATION_CORRUPT", "Completed validation operation is incomplete");
    }
    return new HttpResult(row.httpStatus(), row.correlationId(), map(row.responseJson()));
  }

  private boolean sameResult(OperationRow row, HttpResult requested) {
    if (row.httpStatus() == null || row.responseJson() == null) {
      return false;
    }
    return row.httpStatus() == requested.status()
        && Objects.equals(row.correlationId(), requested.correlationId())
        && canonicalJson.equivalent(row.responseJson(), requested.body());
  }

  private void requireExactStart(StoredRun stored, StartRequest request) {
    if (stored.generation() != request.generation()
        || !stored.requestFingerprint().equals(request.requestFingerprint())
        || !canonicalJson.equivalent(stored.requestJson(), request)) {
      throw failure("VALIDATION_RUN_CONFLICT", "Validation run id already has different content");
    }
  }

  private void requireExactOperation(OperationRow stored, Command request) {
    if (!stored.requestFingerprint().equals(request.requestFingerprint())
        || !canonicalJson.equivalent(stored.requestJson(), request)) {
      throw failure(
          "VALIDATION_OPERATION_CONFLICT",
          "Validation operation idempotency key already has different content");
    }
  }

  private void appendAll(UUID runId, List<EventWrite> events) {
    for (EventWrite event : List.copyOf(events == null ? List.of() : events)) {
      requireRunEvent(runId, event);
      eventStore.append(event);
    }
  }

  private static void requireRunEvent(UUID runId, EventWrite event) {
    Objects.requireNonNull(event, "event");
    if (!runId.equals(event.runId())) {
      throw new IllegalArgumentException("Validation event belongs to another run");
    }
  }

  private static void requireCommandClaim(LeaseClaim claim, Command request) {
    if (!claim.runId().equals(request.runId()) || claim.generation() != request.generation()) {
      throw leaseLost();
    }
  }

  private static void requireBoundaryClaim(LeaseClaim claim, Boundary boundary) {
    if (!claim.runId().equals(boundary.runId()) || claim.generation() != boundary.generation()) {
      throw leaseLost();
    }
  }

  private RunOwnership tryAcquireRunOwnership(
      UUID runId,
      long generation,
      UUID token
  ) {
    Connection connection = null;
    try {
      connection = dataSource.getConnection();
      connection.setAutoCommit(true);
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT pg_try_advisory_lock(?, ?)")) {
        statement.setInt(1, ADVISORY_NAMESPACE);
        statement.setInt(2, RUN_OWNER_KEY);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next() || !resultSet.getBoolean(1)) {
            closeConnection(connection);
            return null;
          }
        }
      }
      return new RunOwnership(
          connection,
          new LeaseClaim(runId, generation, leaseOwner, token));
    } catch (SQLException | RuntimeException failure) {
      abortAndCloseConnection(connection);
      throw failure instanceof RuntimeException runtimeFailure
          ? runtimeFailure
          : new IllegalStateException("Validation run ownership is unavailable", failure);
    }
  }

  private void requireLiveLease(LeaseClaim claim) {
    Objects.requireNonNull(claim, "claim");
    RunOwnership ownership = liveLeases.get(claim.token());
    if (ownership == null || !ownership.matches(claim) || !ownership.isAlive()) {
      if (ownership != null && liveLeases.remove(claim.token(), ownership)) {
        ownership.close();
      }
      throw leaseLost();
    }
  }

  @PreDestroy
  void closeLiveLeases() {
    List<RunOwnership> owned = List.copyOf(liveLeases.values());
    liveLeases.clear();
    owned.forEach(RunOwnership::close);
  }

  private static void closeConnection(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException | RuntimeException ignored) {
      // A broken physical session releases its PostgreSQL advisory locks fail-closed.
    }
  }

  private static void abortAndCloseConnection(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException ignored) {
      // Closing below is still required to recycle a pool proxy.
    } finally {
      closeConnection(connection);
    }
  }

  private long requireGeneration(UUID runId) {
    Long generation = jdbc.query(
            "SELECT generation FROM validation_runtime.run_executions WHERE id = ?",
            (resultSet, rowNumber) -> resultSet.getLong(1),
            runId)
        .stream()
        .findFirst()
        .orElseThrow(() -> failure("VALIDATION_RUN_NOT_FOUND", "Validation run was not found"));
    resetGate.requireReadyGeneration(generation);
    return generation;
  }

  private StartRequest startRequest(String value) {
    return read(value, StartRequest.class, "Stored validation run JSON is invalid");
  }

  private Command command(String value) {
    return read(value, Command.class, "Stored validation operation JSON is invalid");
  }

  private <T> T read(String value, Class<T> type, String message) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(message, exception);
    }
  }

  private Map<String, Object> map(String value) {
    try {
      return objectMapper.readValue(value, new TypeReference<>() { });
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored validation result JSON is invalid", exception);
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Validation runtime value is not serializable", exception);
    }
  }

  private static Instant virtualTime(StartRequest request, long tickSequence) {
    if (tickSequence == 0L) {
      return request.virtualStart();
    }
    return request.ticks().stream()
        .filter(tick -> tick.sequence() == tickSequence)
        .map(com.fxplatform.validation.service.ValidationMarketState.CompositeTick::virtualTime)
        .findFirst()
        .orElseThrow(() -> failure(
            "VALIDATION_OPERATION_TICK_INVALID",
            "Validation operation references a Tick outside the frozen path"));
  }

  private static boolean releasesLease(State state) {
    return state == State.PAUSED
        || state == State.RECOVERY_BLOCKED
        || state == State.CANCELLED
        || state == State.FAILED
        || state == State.COMPLETED;
  }

  private static String safeFailureMessage(String value) {
    if (value == null || value.isBlank()) {
      return "Validation operation failed";
    }
    return value.length() <= 500 ? value : value.substring(0, 500);
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static BusinessException controlRejected(String control) {
    return failure("VALIDATION_RUN_CONTROL_REJECTED", control + " control was rejected");
  }

  private static BusinessException leaseLost() {
    return failure("VALIDATION_RUN_LEASE_LOST", "Validation run lease is no longer owned");
  }

  private static BusinessException notClaimable() {
    return failure("VALIDATION_RUN_NOT_CLAIMABLE", "Validation run could not be claimed");
  }

  private static BusinessException failure(String code, String message) {
    return new BusinessException(code, message);
  }

  private record StoredRun(
      UUID id,
      long generation,
      String requestFingerprint,
      String requestJson,
      State state,
      boolean pauseRequested,
      boolean cancelRequested,
      UUID userId,
      UUID accountId,
      long lastCompletedTickSequence,
      Instant virtualCurrentAt,
      String leaseOwner,
      UUID leaseToken
  ) {
  }

  private record OperationRow(
      UUID id,
      UUID runId,
      long generation,
      String requestFingerprint,
      String requestJson,
      String state,
      Integer httpStatus,
      String correlationId,
      String responseJson,
      String failureCode,
      String failureMessage,
      Instant createdAt
  ) {
  }

  private final class RunOwnership implements AutoCloseable {

    private final Connection connection;
    private final LeaseClaim claim;
    private final AtomicBoolean closed = new AtomicBoolean();

    private RunOwnership(Connection connection, LeaseClaim claim) {
      this.connection = connection;
      this.claim = claim;
    }

    private LeaseClaim claim() {
      return claim;
    }

    private boolean matches(LeaseClaim candidate) {
      return claim.equals(candidate);
    }

    private synchronized void claimRow() {
      if (closed.get()) {
        throw notClaimable();
      }
      try {
        connection.setAutoCommit(false);
        requireDurableReady(connection, claim.generation());
        int updated;
        try (PreparedStatement statement = connection.prepareStatement(("""
            UPDATE validation_runtime.run_executions
               SET lease_owner = ?, lease_token = ?, lease_until = 'infinity'::timestamptz,
                   updated_at = now(), version = version + 1
             WHERE id = ? AND generation = ? AND state IN (%s)
            """).formatted(RECOVERABLE_STATES))) {
          statement.setString(1, claim.owner());
          statement.setObject(2, claim.token());
          statement.setObject(3, claim.runId());
          statement.setLong(4, claim.generation());
          updated = statement.executeUpdate();
        }
        if (updated != 1) {
          throw notClaimable();
        }
        connection.commit();
        connection.setAutoCommit(true);
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException | RuntimeException rollbackFailure) {
          suppressCleanupFailure(failure, rollbackFailure);
        }
        try {
          connection.setAutoCommit(true);
        } catch (SQLException | RuntimeException autoCommitFailure) {
          suppressCleanupFailure(failure, autoCommitFailure);
        }
        throw failure instanceof RuntimeException runtimeFailure
            ? runtimeFailure
            : new IllegalStateException("Validation run claim failed", failure);
      }
    }

    private boolean isAlive() {
      if (closed.get()) {
        return false;
      }
      try {
        return connection.isValid(2);
      } catch (SQLException | RuntimeException failure) {
        return false;
      }
    }

    @Override
    public synchronized void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      boolean unlocked = false;
      try {
        if (!connection.getAutoCommit()) {
          connection.rollback();
          connection.setAutoCommit(true);
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT pg_advisory_unlock(?, ?)")) {
          statement.setInt(1, ADVISORY_NAMESPACE);
          statement.setInt(2, RUN_OWNER_KEY);
          try (ResultSet resultSet = statement.executeQuery()) {
            unlocked = resultSet.next() && resultSet.getBoolean(1);
          }
        }
      } catch (SQLException | RuntimeException ignored) {
        // A session whose unlock result is uncertain must be physically aborted below.
      } finally {
        if (unlocked) {
          closeConnection(connection);
        } else {
          abortAndCloseConnection(connection);
        }
      }
    }
  }

  private static void suppressCleanupFailure(Throwable original, Throwable cleanup) {
    if (cleanup != original) {
      original.addSuppressed(cleanup);
    }
  }

  private static void requireDurableReady(Connection connection, long generation)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT generation, state, redis_generation, memory_generation
          FROM validation_control.reset_state
         WHERE singleton_key = 1
         FOR SHARE
        """); ResultSet resultSet = statement.executeQuery()) {
      if (!resultSet.next()) {
        throw new IllegalStateException("Validation generation is not ready");
      }
      long storedGeneration = resultSet.getLong("generation");
      Long redisGeneration = nullableLong(resultSet, "redis_generation");
      Long memoryGeneration = nullableLong(resultSet, "memory_generation");
      if (storedGeneration != generation
          || !"READY".equals(resultSet.getString("state"))
          || !Long.valueOf(generation).equals(redisGeneration)
          || !Long.valueOf(generation).equals(memoryGeneration)) {
        throw new IllegalStateException("Validation generation is not ready");
      }
    }
  }

  private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
    long value = resultSet.getLong(column);
    return resultSet.wasNull() ? null : value;
  }
}
