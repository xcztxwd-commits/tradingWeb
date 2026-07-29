package com.fxplatform.validation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.validation.service.ValidationResetOperations.DatabaseInspection;
import com.fxplatform.validation.service.ValidationResetOperations.ResetCommandResolution;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Validation-profile adapter for inspecting and rebuilding the isolated application schemas. */
@Profile("validation")
@Component
public class ValidationAdministrativeDatabase implements AutoCloseable {

  private static final Duration DEFAULT_RESET_DRAIN_TIMEOUT = Duration.ofSeconds(15L);

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final ValidationDatabaseFence databaseFence;
  private final long resetDrainTimeoutMillis;
  private UUID resetOwnerId;
  private String resetDatabaseName;
  private ResetPlan resetPlan;
  private ValidationDatabaseFence.ResetOwnership resetOwnership;

  public ValidationAdministrativeDatabase(DataSource dataSource, ObjectMapper objectMapper) {
    this(
        dataSource,
        objectMapper,
        new ValidationDatabaseFence(dataSource),
        DEFAULT_RESET_DRAIN_TIMEOUT);
  }

  @Autowired
  public ValidationAdministrativeDatabase(
      DataSource dataSource,
      ObjectMapper objectMapper,
      ValidationDatabaseFence databaseFence
  ) {
    this(dataSource, objectMapper, databaseFence, DEFAULT_RESET_DRAIN_TIMEOUT);
  }

  public ValidationAdministrativeDatabase(
      DataSource dataSource,
      ObjectMapper objectMapper,
      ValidationDatabaseFence databaseFence,
      Duration resetDrainTimeout
  ) {
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.objectMapper = objectMapper;
    this.databaseFence = databaseFence;
    if (resetDrainTimeout == null
        || resetDrainTimeout.isZero()
        || resetDrainTimeout.isNegative()) {
      throw new IllegalArgumentException("Validation reset drain timeout must be positive");
    }
    this.resetDrainTimeoutMillis = Math.max(1L, resetDrainTimeout.toMillis());
  }

  public synchronized void markResetting(String databaseName, Instant startedAt) {
    markResetting(null, databaseName, startedAt);
  }

  public ResetCommandResolution resolveResetCommand(ValidationResetCommand command) {
    java.util.Optional<DurableResetCommand> operation = findResetCommand(
        """
            SELECT operation_id, run_id, mode, expected_generation, target_generation,
                   reset_id, request_fingerprint, status, receipt_json::text, error_code
              FROM validation_control.reset_receipts
             WHERE operation_id = ?
            """,
        command.operationId());
    if (operation.isPresent()) {
      return resolveExistingResetCommand(command, operation.orElseThrow());
    }
    if (findResetCommand(
        """
            SELECT operation_id, run_id, mode, expected_generation, target_generation,
                   reset_id, request_fingerprint, status, receipt_json::text, error_code
              FROM validation_control.reset_receipts
             WHERE run_id = ?
               AND mode = ?
            """,
        command.runId(),
        command.mode().name()).isPresent()) {
      throw ValidationResetCommand.rejected(
          ValidationResetCommand.CONFLICT_ERROR,
          "Validation reset command conflicts with a durable operation");
    }
    ResetAdmissionState state = jdbcTemplate.query(
            """
                SELECT generation, state
                  FROM validation_control.reset_state
                 WHERE singleton_key = 1
                """,
            (resultSet, rowNumber) -> new ResetAdmissionState(
                resultSet.getLong("generation"),
                resultSet.getString("state")))
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "Validation reset control state is unavailable"));
    validateNewResetCommand(
        command,
        state,
        runtimeRun(command.expectedGeneration()),
        hasSuccessfulInitialResetProof(
            command,
            findInitialResetCommand(command.runId()).orElse(null)));
    return ResetCommandResolution.start();
  }

  /**
   * Returns only the exact identity of a durable operation whose reset fence is still RESETTING.
   * Any split identity fails closed instead of being treated as a pristine generation.
   */
  public Optional<ValidationResetCommand> interruptedResetCommand() {
    ResetControlState state = currentResetState();
    if (!"RESETTING".equals(state.state())) {
      return Optional.empty();
    }
    DurableResetCommand durable = findResetCommand(
            """
                SELECT receipt.operation_id, receipt.run_id, receipt.mode,
                       receipt.expected_generation, receipt.target_generation,
                       receipt.reset_id, receipt.request_fingerprint, receipt.status,
                       receipt.receipt_json::text, receipt.error_code
                  FROM validation_control.reset_state state
                  JOIN validation_control.reset_receipts receipt
                    ON receipt.operation_id = state.reset_id
                 WHERE state.singleton_key = 1
                   AND state.state = 'RESETTING'
                """)
        .orElseThrow(() -> new IllegalStateException(
            "Interrupted validation reset identity is unavailable"));
    ValidationResetCommand command = command(durable);
    requireInterruptedResetIdentity(command, durable, state.generation());
    return Optional.of(command);
  }

  public synchronized void markResetting(
      ValidationResetCommand command,
      String databaseName,
      Instant startedAt
  ) {
    if (resetOwnerId != null) {
      throw new IllegalStateException("Validation reset ownership is already held");
    }
    ValidationDatabaseFence.ResetOwnership candidateOwnership = databaseFence
        .tryAcquireResetOwnership()
        .orElseThrow(() -> new IllegalStateException(
            "Validation reset ownership is already held"));
    UUID candidateOwnerId = command == null
        ? UUID.randomUUID()
        : command.operationId();
    try {
      ResetPlan candidatePlan = candidateOwnership.inTransaction(connection -> {
        requireDatabaseName(connection, databaseName);
        setLocalLockTimeout(connection, resetDrainTimeoutMillis);
        ResetAdmissionState admissionState = lockResetAdmissionState(connection);
        if (command != null) {
          requireNewResetCommand(connection, command, admissionState);
        }
        long targetGeneration = Math.addExact(admissionState.generation(), 1L);
        if (command != null) {
          insertResetCommand(
              connection,
              command,
              targetGeneration,
              candidateOwnerId,
              startedAt);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE validation_control.reset_state
               SET state = 'RESETTING',
                   reset_id = ?,
                   database_name = ?,
                   redis_generation = NULL,
                   memory_generation = NULL,
                   started_at = ?,
                   finished_at = NULL,
                   steps_json = '[]'::jsonb,
                   error_code = NULL,
                   version = version + 1,
                   updated_at = now()
             WHERE singleton_key = 1
             RETURNING generation, version
            """)) {
          statement.setObject(1, candidateOwnerId);
          statement.setString(2, databaseName);
          statement.setTimestamp(3, Timestamp.from(startedAt));
          try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
              throw new IllegalStateException(
                  "Validation reset control state is unavailable");
            }
            long persistedGeneration = resultSet.getLong("generation");
            if (persistedGeneration != admissionState.generation()) {
              throw new IllegalStateException(
                  "Validation reset control generation changed");
            }
            return new ResetPlan(
                targetGeneration,
                resultSet.getLong("version"));
          }
        }
      });
      resetOwnerId = candidateOwnerId;
      resetDatabaseName = databaseName;
      resetPlan = candidatePlan;
      resetOwnership = candidateOwnership;
    } catch (RuntimeException failure) {
      candidateOwnership.close();
      throw failure;
    }
  }

  /**
   * Rebuilds only process-local ownership for an exact interrupted operation. The durable
   * generation, epoch, reset identity, timestamps, and receipt remain untouched until the normal
   * atomic terminal persistence path completes.
   */
  public synchronized void recoverResetting(
      ValidationResetCommand command,
      String databaseName,
      Instant startedAt
  ) {
    if (command == null || databaseName == null || startedAt == null) {
      throw new IllegalArgumentException("Interrupted validation reset recovery is incomplete");
    }
    if (resetOwnerId != null) {
      throw new IllegalStateException("Validation reset ownership is already held");
    }
    ValidationDatabaseFence.ResetOwnership candidateOwnership = databaseFence
        .tryAcquireResetOwnership()
        .orElseThrow(() -> new IllegalStateException(
            "Validation reset ownership is already held"));
    try {
      ResetPlan candidatePlan = candidateOwnership.inTransaction(connection -> {
        requireDatabaseName(connection, databaseName);
        setLocalLockTimeout(connection, resetDrainTimeoutMillis);
        InterruptedResetState state = lockInterruptedResetState(connection);
        DurableResetCommand durable = findResetCommand(
            connection,
            """
                SELECT operation_id, run_id, mode, expected_generation, target_generation,
                       reset_id, request_fingerprint, status, receipt_json::text, error_code
                  FROM validation_control.reset_receipts
                 WHERE operation_id = ?
                 FOR UPDATE
                """,
            command.operationId(),
            null);
        if (durable == null) {
          throw new IllegalStateException(
              "Interrupted validation reset identity is unavailable");
        }
        requireInterruptedResetIdentity(command, durable, state.generation());
        if (!"RESETTING".equals(state.state())
            || !command.operationId().equals(state.resetId())
            || !databaseName.equals(state.databaseName())
            || state.startedAt() == null
            || state.version() <= 0L) {
          throw new IllegalStateException("Interrupted validation reset fence changed");
        }
        return new ResetPlan(durable.targetGeneration(), state.version());
      });
      resetOwnerId = command.operationId();
      resetDatabaseName = databaseName;
      resetPlan = candidatePlan;
      resetOwnership = candidateOwnership;
    } catch (RuntimeException failure) {
      candidateOwnership.close();
      throw failure;
    }
  }

  public synchronized long currentGeneration() {
    ValidationDatabaseFence.ResetOwnership ownership = requireResetOwnershipCapability();
    return ownership.inTransaction(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT generation FROM validation_control.reset_state WHERE singleton_key = 1");
           ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next() || resultSet.getLong(1) < 0L) {
          throw new IllegalStateException("Validation reset generation is unavailable");
        }
        return resultSet.getLong(1);
      }
    });
  }

  public synchronized ResetPlan currentResetPlan() {
    requireResetOwnershipCapability();
    if (resetPlan == null) {
      throw new IllegalStateException("Validation reset plan is unavailable");
    }
    return resetPlan;
  }

  public ResetControlState currentResetState() {
    return jdbcTemplate.query(
            """
                SELECT generation, state, redis_generation, memory_generation
                  FROM validation_control.reset_state
                 WHERE singleton_key = 1
                """,
            (resultSet, rowNumber) -> new ResetControlState(
                resultSet.getLong("generation"),
                resultSet.getString("state"),
                nullableLong(resultSet, "redis_generation"),
                nullableLong(resultSet, "memory_generation")))
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Validation reset control state is unavailable"));
  }

  public synchronized void persistResetReceipt(ValidationResetReceipt receipt) {
    persistResetReceipt(null, receipt);
  }

  public synchronized void persistResetReceipt(
      ValidationResetCommand command,
      ValidationResetReceipt receipt
  ) {
    UUID ownerId = resetOwnerId;
    ValidationDatabaseFence.ResetOwnership ownership = resetOwnership;
    ResetPlan plan = resetPlan;
    if (ownerId == null || ownership == null || plan == null) {
      throw new IllegalStateException("Validation reset ownership is unavailable");
    }
    try {
      String persistedState = receipt.status() == ValidationResetReceipt.Status.SUCCEEDED
          ? "READY"
          : "FAILED";
      Long generation = receipt.redisGeneration();
      if (generation != null && generation != plan.targetGeneration()) {
        throw new IllegalArgumentException("Validation reset receipt generation changed");
      }
      if (receipt.status() == ValidationResetReceipt.Status.SUCCEEDED
          && (generation == null
              || generation <= 0
              || !generation.equals(receipt.memoryGeneration()))) {
        throw new IllegalArgumentException("Successful validation reset has no stable generation");
      }
      String steps = serializeSteps(receipt);
      String serializedReceipt = command == null ? null : serializeReceipt(receipt);
      TerminalUpdate terminalUpdate = ownership.inTransaction(connection -> {
        int resetStateUpdated;
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE validation_control.reset_state
               SET generation = COALESCE(?, generation),
                   state = ?,
                   redis_generation = ?,
                   memory_generation = ?,
                   finished_at = ?,
                   steps_json = CAST(? AS jsonb),
                   error_code = ?,
                   version = version + 1,
                   updated_at = now()
             WHERE singleton_key = 1
               AND state = 'RESETTING'
               AND reset_id = ?
               AND generation = ?
               AND version = ?
            """)) {
          if (generation == null) {
            statement.setNull(1, java.sql.Types.BIGINT);
          } else {
            statement.setLong(1, generation);
          }
          statement.setString(2, persistedState);
          setNullableLong(statement, 3, receipt.redisGeneration());
          setNullableLong(statement, 4, receipt.memoryGeneration());
          statement.setTimestamp(5, Timestamp.from(receipt.finishedAt()));
          statement.setString(6, steps);
          statement.setString(7, receipt.errorCode());
          statement.setObject(8, ownerId);
          statement.setLong(9, plan.targetGeneration() - 1L);
          statement.setLong(10, plan.epoch());
          resetStateUpdated = statement.executeUpdate();
        }
        int commandReceiptUpdated = command == null
            ? 1
            : persistCommandReceipt(
                connection,
                command,
                receipt,
                serializedReceipt,
                plan,
                ownerId);
        requireSingletonUpdate(resetStateUpdated);
        requireSingletonUpdate(commandReceiptUpdated);
        return new TerminalUpdate(resetStateUpdated, commandReceiptUpdated);
      });
      requireSingletonUpdate(terminalUpdate.resetStateUpdated());
      requireSingletonUpdate(terminalUpdate.commandReceiptUpdated());
    } finally {
      releaseResetOwnership();
    }
  }

  private ResetCommandResolution resolveExistingResetCommand(
      ValidationResetCommand command,
      DurableResetCommand durable
  ) {
    if (!durable.operationId().equals(command.operationId())
        || !durable.runId().equals(command.runId())
        || !durable.mode().equals(command.mode().name())
        || durable.expectedGeneration() != command.expectedGeneration()
        || durable.targetGeneration() != command.expectedGeneration() + 1L
        || !durable.requestFingerprint().trim().equals(command.requestFingerprint())) {
      throw ValidationResetCommand.rejected(
          ValidationResetCommand.CONFLICT_ERROR,
          "Validation reset command conflicts with a durable operation");
    }
    if ("IN_PROGRESS".equals(durable.status())) {
      throw ValidationResetCommand.rejected(
          ValidationResetCommand.IN_PROGRESS_ERROR,
          "Validation reset operation has no durable terminal receipt");
    }
    if (!"SUCCEEDED".equals(durable.status()) && !"FAILED".equals(durable.status())) {
      throw new IllegalStateException("Validation reset receipt status is invalid");
    }
    ValidationResetReceipt receipt = deserializeReceipt(durable.receiptJson());
    if (!durable.status().equals(receipt.status().name())
        || !java.util.Objects.equals(durable.errorCode(), receipt.errorCode())
        || ("SUCCEEDED".equals(durable.status())
            && (!Long.valueOf(durable.targetGeneration()).equals(receipt.redisGeneration())
                || !Long.valueOf(durable.targetGeneration()).equals(
                    receipt.memoryGeneration())))) {
      throw new IllegalStateException("Validation reset durable receipt is inconsistent");
    }
    return ResetCommandResolution.replay(receipt);
  }

  private java.util.Optional<DurableResetCommand> findResetCommand(
      String sql,
      Object... parameters
  ) {
    return jdbcTemplate.query(
            sql,
            (resultSet, rowNumber) -> mapResetCommand(resultSet),
            parameters)
        .stream()
        .findFirst();
  }

  private RuntimeRun runtimeRun(long generation) {
    return jdbcTemplate.query(
            """
                SELECT id, state
                  FROM validation_runtime.run_executions
                 WHERE generation = ?
                """,
            (resultSet, rowNumber) -> new RuntimeRun(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("state")),
            generation)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static ResetAdmissionState lockResetAdmissionState(Connection connection)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT generation, state
          FROM validation_control.reset_state
         WHERE singleton_key = 1
         FOR UPDATE
        """);
         ResultSet resultSet = statement.executeQuery()) {
      if (!resultSet.next()) {
        throw new IllegalStateException("Validation reset control state is unavailable");
      }
      return new ResetAdmissionState(
          resultSet.getLong("generation"),
          resultSet.getString("state"));
    }
  }

  private static InterruptedResetState lockInterruptedResetState(Connection connection)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT generation, state, reset_id, database_name, started_at, version
          FROM validation_control.reset_state
         WHERE singleton_key = 1
         FOR UPDATE
        """);
         ResultSet resultSet = statement.executeQuery()) {
      if (!resultSet.next()) {
        throw new IllegalStateException("Validation reset control state is unavailable");
      }
      return new InterruptedResetState(
          resultSet.getLong("generation"),
          resultSet.getString("state"),
          resultSet.getObject("reset_id", UUID.class),
          resultSet.getString("database_name"),
          instant(resultSet.getTimestamp("started_at")),
          resultSet.getLong("version"));
    }
  }

  private static ValidationResetCommand command(DurableResetCommand durable) {
    try {
      return new ValidationResetCommand(
          durable.runId(),
          durable.operationId(),
          ValidationResetCommand.Mode.valueOf(durable.mode()),
          durable.expectedGeneration());
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("Interrupted validation reset identity is invalid");
    }
  }

  private static void requireInterruptedResetIdentity(
      ValidationResetCommand command,
      DurableResetCommand durable,
      long durableGeneration
  ) {
    if (!durable.operationId().equals(command.operationId())
        || !durable.runId().equals(command.runId())
        || !durable.mode().equals(command.mode().name())
        || durable.expectedGeneration() != command.expectedGeneration()
        || durable.expectedGeneration() != durableGeneration
        || durable.targetGeneration() != command.expectedGeneration() + 1L
        || !durable.resetId().equals(command.operationId())
        || !durable.requestFingerprint().trim().equals(command.requestFingerprint())
        || !"IN_PROGRESS".equals(durable.status())
        || durable.receiptJson() != null
        || durable.errorCode() != null) {
      throw new IllegalStateException("Interrupted validation reset identity changed");
    }
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private void requireNewResetCommand(
      Connection connection,
      ValidationResetCommand command,
      ResetAdmissionState state
  ) throws SQLException {
    DurableResetCommand sameOperation = findResetCommand(
        connection,
        """
            SELECT operation_id, run_id, mode, expected_generation, target_generation,
                   reset_id, request_fingerprint, status, receipt_json::text, error_code
              FROM validation_control.reset_receipts
             WHERE operation_id = ?
             FOR UPDATE
            """,
        command.operationId(),
        null);
    if (sameOperation != null) {
      ResetCommandResolution resolution = resolveExistingResetCommand(command, sameOperation);
      if (resolution.isReplay()) {
        throw ValidationResetCommand.rejected(
            ValidationResetCommand.CONFLICT_ERROR,
            "Validation reset command completed concurrently");
      }
    }
    DurableResetCommand sameRunMode = findResetCommand(
        connection,
        """
            SELECT operation_id, run_id, mode, expected_generation, target_generation,
                   reset_id, request_fingerprint, status, receipt_json::text, error_code
              FROM validation_control.reset_receipts
             WHERE run_id = ?
               AND mode = ?
             FOR UPDATE
            """,
        command.runId(),
        command.mode().name());
    if (sameRunMode != null) {
      throw ValidationResetCommand.rejected(
          ValidationResetCommand.CONFLICT_ERROR,
          "Validation reset command conflicts with a durable operation");
    }
    validateNewResetCommand(
        command,
        state,
        runtimeRun(connection, command.expectedGeneration()),
        hasSuccessfulInitialResetProof(
            command,
            findInitialResetCommand(connection, command.runId())));
  }

  private void validateNewResetCommand(
      ValidationResetCommand command,
      ResetAdmissionState state,
      RuntimeRun runtimeRun,
      boolean successfulInitialResetProof
  ) {
    if ("RESETTING".equals(state.state())) {
      throw ValidationResetCommand.rejected(
          ValidationResetCommand.IN_PROGRESS_ERROR,
          "Another validation reset is already in progress");
    }
    if (state.generation() != command.expectedGeneration()) {
      throw ValidationResetCommand.rejected(
          ValidationResetCommand.STALE_GENERATION_ERROR,
          "Validation reset expected generation is stale");
    }
    if (command.mode() == ValidationResetCommand.Mode.INITIAL) {
      if (runtimeRun != null) {
        throw ValidationResetCommand.rejected(
            ValidationResetCommand.INITIAL_RUN_PRESENT_ERROR,
            "Initial validation reset cannot erase an existing run");
      }
      return;
    }
    if (runtimeRun == null) {
      if (successfulInitialResetProof) {
        return;
      }
      throw ValidationResetCommand.rejected(
          ValidationResetCommand.FINAL_RUN_MISMATCH_ERROR,
          "Final validation reset does not own the current run");
    }
    if (!runtimeRun.runId().equals(command.runId())) {
      throw ValidationResetCommand.rejected(
          ValidationResetCommand.FINAL_RUN_MISMATCH_ERROR,
          "Final validation reset does not own the current run");
    }
    if (!Set.of("CANCELLED", "FAILED", "COMPLETED").contains(runtimeRun.state())) {
      throw ValidationResetCommand.rejected(
          ValidationResetCommand.FINAL_RUN_NOT_TERMINAL_ERROR,
          "Final validation reset requires a terminal run");
    }
  }

  private java.util.Optional<DurableResetCommand> findInitialResetCommand(UUID runId) {
    return findResetCommand(
        """
            SELECT operation_id, run_id, mode, expected_generation, target_generation,
                   reset_id, request_fingerprint, status, receipt_json::text, error_code
              FROM validation_control.reset_receipts
             WHERE run_id = ?
               AND mode = 'INITIAL'
            """,
        runId);
  }

  private static DurableResetCommand findInitialResetCommand(
      Connection connection,
      UUID runId
  ) throws SQLException {
    return findResetCommand(
        connection,
        """
            SELECT operation_id, run_id, mode, expected_generation, target_generation,
                   reset_id, request_fingerprint, status, receipt_json::text, error_code
              FROM validation_control.reset_receipts
             WHERE run_id = ?
               AND mode = 'INITIAL'
             FOR SHARE
            """,
        runId,
        null);
  }

  private boolean hasSuccessfulInitialResetProof(
      ValidationResetCommand finalCommand,
      DurableResetCommand durable
  ) {
    if (finalCommand.mode() != ValidationResetCommand.Mode.FINAL
        || durable == null
        || !durable.runId().equals(finalCommand.runId())
        || !"INITIAL".equals(durable.mode())
        || durable.expectedGeneration() < 0L
        || durable.expectedGeneration() == Long.MAX_VALUE
        || durable.targetGeneration() != durable.expectedGeneration() + 1L
        || durable.targetGeneration() != finalCommand.expectedGeneration()
        || !durable.resetId().equals(durable.operationId())
        || !"SUCCEEDED".equals(durable.status())
        || durable.errorCode() != null) {
      return false;
    }
    ValidationResetCommand initialCommand = new ValidationResetCommand(
        durable.runId(),
        durable.operationId(),
        ValidationResetCommand.Mode.INITIAL,
        durable.expectedGeneration());
    if (!initialCommand.requestFingerprint().equals(durable.requestFingerprint().trim())) {
      return false;
    }
    try {
      ValidationResetReceipt receipt = deserializeReceipt(durable.receiptJson());
      return receipt.status() == ValidationResetReceipt.Status.SUCCEEDED
          && receipt.errorCode() == null
          && Long.valueOf(durable.targetGeneration()).equals(receipt.redisGeneration())
          && Long.valueOf(durable.targetGeneration()).equals(receipt.memoryGeneration());
    } catch (IllegalStateException invalidReceipt) {
      return false;
    }
  }

  private static RuntimeRun runtimeRun(Connection connection, long generation)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT id, state
          FROM validation_runtime.run_executions
         WHERE generation = ?
        """)) {
      statement.setLong(1, generation);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return new RuntimeRun(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("state"));
      }
    }
  }

  private static DurableResetCommand findResetCommand(
      Connection connection,
      String sql,
      Object firstParameter,
      String secondParameter
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, firstParameter);
      if (secondParameter != null) {
        statement.setString(2, secondParameter);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? mapResetCommand(resultSet) : null;
      }
    }
  }

  private static DurableResetCommand mapResetCommand(ResultSet resultSet)
      throws SQLException {
    return new DurableResetCommand(
        resultSet.getObject("operation_id", UUID.class),
        resultSet.getObject("run_id", UUID.class),
        resultSet.getString("mode"),
        resultSet.getLong("expected_generation"),
        resultSet.getLong("target_generation"),
        resultSet.getObject("reset_id", UUID.class),
        resultSet.getString("request_fingerprint"),
        resultSet.getString("status"),
        resultSet.getString("receipt_json"),
        resultSet.getString("error_code"));
  }

  private static void insertResetCommand(
      Connection connection,
      ValidationResetCommand command,
      long targetGeneration,
      UUID resetId,
      Instant startedAt
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO validation_control.reset_receipts (
          operation_id, run_id, mode, expected_generation, target_generation,
          reset_id, request_fingerprint, status, started_at, updated_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, now())
        """)) {
      statement.setObject(1, command.operationId());
      statement.setObject(2, command.runId());
      statement.setString(3, command.mode().name());
      statement.setLong(4, command.expectedGeneration());
      statement.setLong(5, targetGeneration);
      statement.setObject(6, resetId);
      statement.setString(7, command.requestFingerprint());
      statement.setTimestamp(8, Timestamp.from(startedAt));
      requireSingletonUpdate(statement.executeUpdate());
    }
  }

  private static int persistCommandReceipt(
      Connection connection,
      ValidationResetCommand command,
      ValidationResetReceipt receipt,
      String serializedReceipt,
      ResetPlan plan,
      UUID ownerId
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE validation_control.reset_receipts
           SET status = ?,
               receipt_json = CAST(? AS jsonb),
               error_code = ?,
               finished_at = ?,
               updated_at = now()
         WHERE operation_id = ?
           AND run_id = ?
           AND mode = ?
           AND expected_generation = ?
           AND target_generation = ?
           AND reset_id = ?
           AND request_fingerprint = ?
           AND status = 'IN_PROGRESS'
        """)) {
      statement.setString(1, receipt.status().name());
      statement.setString(2, serializedReceipt);
      statement.setString(3, receipt.errorCode());
      statement.setTimestamp(4, Timestamp.from(receipt.finishedAt()));
      statement.setObject(5, command.operationId());
      statement.setObject(6, command.runId());
      statement.setString(7, command.mode().name());
      statement.setLong(8, command.expectedGeneration());
      statement.setLong(9, plan.targetGeneration());
      statement.setObject(10, ownerId);
      statement.setString(11, command.requestFingerprint());
      return statement.executeUpdate();
    }
  }

  public DatabaseInspection inspect() {
    String databaseName = jdbcTemplate.queryForObject(
        "SELECT current_database()",
        String.class);
    List<String> allSchemas = jdbcTemplate.queryForList(
        "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name",
        String.class);
    Set<String> applicationSchemas = new LinkedHashSet<>();
    for (String schema : allSchemas) {
      if (!"information_schema".equals(schema) && !schema.startsWith("pg_")) {
        applicationSchemas.add(schema);
      }
    }
    return new DatabaseInspection(databaseName, applicationSchemas);
  }

  public synchronized void cleanAndMigrate(List<String> schemaAllowlist) {
    ValidationDatabaseFence.ResetOwnership ownership = requireResetOwnershipCapability();
    List<String> requestedSchemas = List.copyOf(schemaAllowlist);
    if (!ValidationResetOperations.RESET_SCHEMAS.equals(requestedSchemas)) {
      throw new IllegalArgumentException("Validation schema allowlist mismatch");
    }
    ownership.awaitRunOwnership(Duration.ofMillis(resetDrainTimeoutMillis));

    DatabaseInspection ownerInspection = inspect(ownership);
    if (!resetDatabaseName.equals(ownerInspection.databaseName())
        || !ValidationResetOperations.ALLOWED_SCHEMA_INVENTORY.equals(
            ownerInspection.applicationSchemas())) {
      throw new IllegalStateException("Validation reset database identity changed");
    }

    String[] schemas = requestedSchemas.toArray(String[]::new);
    Flyway flyway = Flyway.configure()
        .dataSource(ownership.dataSource())
        .locations("classpath:db/migration")
        .schemas(schemas)
        .defaultSchema("public")
        .cleanDisabled(false)
        .load();
    flyway.clean();
    flyway.migrate();
    requireResetOwnership();
  }

  public synchronized void requireResetOwnership() {
    requireResetOwnershipCapability();
  }

  public synchronized int resetOwnerBackendPid() {
    return requireResetOwnershipCapability().backendPid();
  }

  private ValidationDatabaseFence.ResetOwnership requireResetOwnershipCapability() {
    ValidationDatabaseFence.ResetOwnership ownership = resetOwnership;
    UUID ownerId = resetOwnerId;
    ResetPlan plan = resetPlan;
    if (ownership == null || ownerId == null || plan == null) {
      throw new IllegalStateException("Validation reset ownership is unavailable");
    }
    ownership.requireAlive();
    int owned = ownership.inTransaction(connection -> {
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT COUNT(*)
            FROM validation_control.reset_state
           WHERE singleton_key = 1
             AND state = 'RESETTING'
             AND reset_id = ?
             AND generation = ?
             AND version = ?
          """)) {
        statement.setObject(1, ownerId);
        statement.setLong(2, plan.targetGeneration() - 1L);
        statement.setLong(3, plan.epoch());
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return 0;
          }
          return resultSet.getInt(1);
        }
      }
    });
    if (owned != 1) {
      throw new IllegalStateException("Validation reset ownership is unavailable");
    }
    return ownership;
  }

  @Override
  @PreDestroy
  public synchronized void close() {
    releaseResetOwnership();
  }

  private void releaseResetOwnership() {
    ValidationDatabaseFence.ResetOwnership ownership = resetOwnership;
    resetOwnership = null;
    resetOwnerId = null;
    resetDatabaseName = null;
    resetPlan = null;
    if (ownership != null) {
      ownership.close();
    }
  }

  private String serializeSteps(ValidationResetReceipt receipt) {
    try {
      return objectMapper.writeValueAsString(receipt.steps());
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Validation reset receipt serialization failed");
    }
  }

  private String serializeReceipt(ValidationResetReceipt receipt) {
    try {
      return objectMapper.writeValueAsString(receipt);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Validation reset receipt serialization failed");
    }
  }

  private ValidationResetReceipt deserializeReceipt(String receiptJson) {
    if (receiptJson == null) {
      throw new IllegalStateException("Validation reset durable receipt is unavailable");
    }
    try {
      return objectMapper.readValue(receiptJson, ValidationResetReceipt.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Validation reset durable receipt is invalid");
    }
  }

  private static void requireSingletonUpdate(int updated) {
    if (updated != 1) {
      throw new IllegalStateException("Validation reset control state is unavailable");
    }
  }

  private static void requireDatabaseName(Connection connection, String expected)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT current_database()");
         ResultSet resultSet = statement.executeQuery()) {
      if (!resultSet.next() || !expected.equals(resultSet.getString(1))) {
        throw new IllegalStateException("Validation reset database identity changed");
      }
    }
  }

  private static void setLocalLockTimeout(Connection connection, long timeoutMillis)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT set_config('lock_timeout', ?, true)")) {
      statement.setString(1, timeoutMillis + "ms");
      statement.executeQuery();
    }
  }

  private static DatabaseInspection inspect(
      ValidationDatabaseFence.ResetOwnership ownership
  ) {
    return ownership.inTransaction(connection -> {
      String databaseName;
      try (PreparedStatement statement = connection.prepareStatement("SELECT current_database()");
           ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new IllegalStateException("Validation database identity is unavailable");
        }
        databaseName = resultSet.getString(1);
      }
      Set<String> schemas = new LinkedHashSet<>();
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name");
           ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String schema = resultSet.getString(1);
          if (!"information_schema".equals(schema) && !schema.startsWith("pg_")) {
            schemas.add(schema);
          }
        }
      }
      return new DatabaseInspection(databaseName, schemas);
    });
  }

  private static void setNullableLong(
      PreparedStatement statement,
      int parameterIndex,
      Long value
  ) throws SQLException {
    if (value == null) {
      statement.setNull(parameterIndex, java.sql.Types.BIGINT);
    } else {
      statement.setLong(parameterIndex, value);
    }
  }

  private static Long nullableLong(java.sql.ResultSet resultSet, String column)
      throws java.sql.SQLException {
    long value = resultSet.getLong(column);
    return resultSet.wasNull() ? null : value;
  }

  public record ResetControlState(
      long generation,
      String state,
      Long redisGeneration,
      Long memoryGeneration
  ) {
  }

  private record DurableResetCommand(
      UUID operationId,
      UUID runId,
      String mode,
      long expectedGeneration,
      long targetGeneration,
      UUID resetId,
      String requestFingerprint,
      String status,
      String receiptJson,
      String errorCode
  ) {
  }

  private record ResetAdmissionState(long generation, String state) {
  }

  private record InterruptedResetState(
      long generation,
      String state,
      UUID resetId,
      String databaseName,
      Instant startedAt,
      long version
  ) {
  }

  private record RuntimeRun(UUID runId, String state) {
  }

  private record TerminalUpdate(int resetStateUpdated, int commandReceiptUpdated) {
  }

  public record ResetPlan(long targetGeneration, long epoch) {

    public ResetPlan {
      if (targetGeneration <= 0L || epoch <= 0L) {
        throw new IllegalArgumentException("Validation reset target and epoch must be positive");
      }
    }
  }
}
