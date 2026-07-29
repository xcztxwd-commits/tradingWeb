package com.fxplatform.validation.service;

import com.fxplatform.validation.service.ValidationAdministrativeDatabase.ResetControlState;
import com.fxplatform.validation.service.ValidationMarketClock.Tick;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Sanitized control-plane snapshot used by the supervisor and coordinator. */
@Profile("validation")
@Service
public class ValidationStateService {

  private static final Set<ValidationRunRuntimeStore.State> TERMINAL_STATES = Set.of(
      ValidationRunRuntimeStore.State.CANCELLED,
      ValidationRunRuntimeStore.State.FAILED,
      ValidationRunRuntimeStore.State.COMPLETED);

  private final ValidationAdministrativeDatabase administrativeDatabase;
  private final ValidationResetGate resetGate;
  private final ValidationMarketClock marketClock;
  private final ValidationMarketState marketState;
  private final ValidationDemoExecutionPolicyProvider executionPolicyProvider;
  private final ValidationRedisResetter redisResetter;
  private final ValidationDatabaseFence databaseFence;
  private final JdbcTemplate jdbc;

  public ValidationStateService(
      ValidationAdministrativeDatabase administrativeDatabase,
      ValidationResetGate resetGate,
      ValidationMarketClock marketClock,
      ValidationMarketState marketState,
      ValidationDemoExecutionPolicyProvider executionPolicyProvider,
      ValidationRedisResetter redisResetter,
      ValidationDatabaseFence databaseFence,
      JdbcTemplate jdbc
  ) {
    this.administrativeDatabase = administrativeDatabase;
    this.resetGate = resetGate;
    this.marketClock = marketClock;
    this.marketState = marketState;
    this.executionPolicyProvider = executionPolicyProvider;
    this.redisResetter = redisResetter;
    this.databaseFence = databaseFence;
    this.jdbc = jdbc;
  }

  public Snapshot snapshot(UUID requestedRunId) {
    ResetControlState durable = administrativeDatabase.currentResetState();
    ValidationResetGate.State memoryState = resetGate.state();
    long memoryGeneration = resetGate.generation();
    Long observedRedisGeneration = observedRedisGeneration();
    boolean generationReady = readyGeneration(
        memoryState,
        memoryGeneration,
        observedRedisGeneration,
        durable);
    RunSnapshot run = null;
    Tick clock = null;
    CompositeTick market = null;
    boolean policyFrozen = false;
    if (generationReady) {
      Optional<ValidationDatabaseFence.RequestFence> entered =
          databaseFence.tryEnterRequest(durable.generation());
      if (entered.isEmpty()) {
        generationReady = false;
      } else {
        try (ValidationDatabaseFence.RequestFence ignored = entered.orElseThrow()) {
          memoryState = resetGate.state();
          memoryGeneration = resetGate.generation();
          observedRedisGeneration = observedRedisGeneration();
          generationReady = readyGeneration(
              memoryState,
              memoryGeneration,
              observedRedisGeneration,
              durable);
          if (generationReady) {
            RunSnapshot selectedRun = requestedRunId == null
                ? latestRun(durable.generation())
                : findRun(requestedRunId, durable.generation());
            run = selectedRun;
            Optional<Tick> currentClock = marketClock.current();
            Optional<CompositeTick> currentMarket = marketState.current();
            clock = currentClock
                .filter(value -> belongsTo(value, selectedRun, durable.generation()))
                .orElse(null);
            market = currentMarket
                .filter(value -> belongsTo(value, selectedRun, durable.generation()))
                .orElse(null);
            if (clock != null && market != null && clock.sequence() != market.sequence()) {
              clock = null;
              market = null;
            }
            if (clock != null
                && market != null
                && executionPolicyProvider.generation() == durable.generation()) {
              try {
                executionPolicyProvider.current();
                policyFrozen = true;
              } catch (RuntimeException ignoredPolicy) {
                policyFrozen = false;
              }
            }
          }
        }
      }
    }
    return new Snapshot(
        memoryState,
        memoryGeneration,
        durable.state(),
        durable.generation(),
        durable.redisGeneration(),
        observedRedisGeneration,
        durable.memoryGeneration(),
        generationReady,
        run,
        clock,
        market == null ? null : market.sequence(),
        policyFrozen);
  }

  private RunSnapshot latestRun(long generation) {
    return jdbc.query(
            """
                SELECT id, generation, request_fingerprint, state,
                       pause_requested, cancel_requested, last_completed_tick_sequence,
                       virtual_current_at, next_event_sequence, failure_code, updated_at
                  FROM validation_runtime.run_executions
                 WHERE generation = ?
                 ORDER BY CASE WHEN state IN (
                   'ACCEPTED','STARTING','RUNNING','PAUSED','RECOVERING',
                   'RECOVERY_BLOCKED','CANCELLING'
                 ) THEN 0 ELSE 1 END, updated_at DESC, id
                 LIMIT 1
                """,
            this::mapRun,
            generation)
        .stream()
        .filter(run -> run.generation() == generation)
        .findFirst()
        .orElse(null);
  }

  private RunSnapshot findRun(UUID runId, long generation) {
    return jdbc.query(
            """
                SELECT id, generation, request_fingerprint, state,
                       pause_requested, cancel_requested, last_completed_tick_sequence,
                       virtual_current_at, next_event_sequence, failure_code, updated_at
                  FROM validation_runtime.run_executions
                 WHERE id = ? AND generation = ?
                """,
            this::mapRun,
            runId,
            generation)
        .stream()
        .filter(run -> run.generation() == generation)
        .findFirst()
        .orElse(null);
  }

  private RunSnapshot mapRun(ResultSet resultSet, int rowNumber) throws SQLException {
    ValidationRunRuntimeStore.State state =
        ValidationRunRuntimeStore.State.valueOf(resultSet.getString("state"));
    return new RunSnapshot(
        resultSet.getObject("id", UUID.class),
        resultSet.getLong("generation"),
        resultSet.getString("request_fingerprint"),
        state,
        resultSet.getBoolean("pause_requested"),
        resultSet.getBoolean("cancel_requested"),
        resultSet.getLong("last_completed_tick_sequence"),
        instant(resultSet.getTimestamp("virtual_current_at")),
        resultSet.getLong("next_event_sequence"),
        resultSet.getString("failure_code"),
        resultSet.getTimestamp("updated_at").toInstant(),
        TERMINAL_STATES.contains(state));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static boolean readyGeneration(
      ValidationResetGate.State memoryState,
      long memoryGeneration,
      Long observedRedisGeneration,
      ResetControlState durable
  ) {
    return memoryState == ValidationResetGate.State.READY
        && "READY".equals(durable.state())
        && durable.generation() > 0L
        && memoryGeneration == durable.generation()
        && Long.valueOf(durable.generation()).equals(durable.redisGeneration())
        && Long.valueOf(durable.generation()).equals(observedRedisGeneration)
        && Long.valueOf(durable.generation()).equals(durable.memoryGeneration());
  }

  private Long observedRedisGeneration() {
    try {
      return redisResetter.currentGeneration();
    } catch (RuntimeException unavailableOrInvalid) {
      return null;
    }
  }

  private static boolean belongsTo(Tick tick, RunSnapshot run, long generation) {
    return run != null
        && tick.generation() == generation
        && tick.runId().equals(run.runId());
  }

  private static boolean belongsTo(CompositeTick tick, RunSnapshot run, long generation) {
    return run != null
        && tick.generation() == generation
        && tick.runId().equals(run.runId());
  }

  public record Snapshot(
      ValidationResetGate.State memoryResetState,
      long memoryGeneration,
      String durableResetState,
      long durableGeneration,
      Long redisGeneration,
      Long observedRedisGeneration,
      Long durableMemoryGeneration,
      boolean generationCoherent,
      RunSnapshot run,
      Tick clock,
      Long marketTickSequence,
      boolean executionPolicyFrozen
  ) {
  }

  public record RunSnapshot(
      UUID runId,
      long generation,
      String requestFingerprint,
      ValidationRunRuntimeStore.State state,
      boolean pauseRequested,
      boolean cancelRequested,
      long lastCompletedTickSequence,
      Instant virtualTime,
      long eventHighWatermark,
      String failureCode,
      Instant updatedAt,
      boolean terminal
  ) {
  }
}
