package com.fxplatform.validation.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationResetOperations.DatabaseInspection;
import com.fxplatform.validation.service.ValidationResetOperations.ResetCommandResolution;
import com.fxplatform.validation.service.ValidationResetReceipt.Step;
import com.fxplatform.validation.service.ValidationResetReceipt.StepResult;
import com.fxplatform.validation.service.ValidationResetReceipt.StepStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Coordinates a fail-closed reset of the isolated validation runtime. */
@Profile("validation")
@Service
public class ValidationResetService {

  private static final String VALIDATION_DATABASE_PATTERN =
      "fx_validation_[a-z0-9][a-z0-9_]*";

  private final ValidationResetOperations operations;
  private final ValidationResetGate gate;
  private final Clock clock;

  @Autowired
  public ValidationResetService(
      ValidationResetOperations operations,
      ValidationResetGate gate
  ) {
    this(operations, gate, Clock.systemUTC());
  }

  public ValidationResetService(
      ValidationResetOperations operations,
      ValidationResetGate gate,
      Clock clock
  ) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.gate = Objects.requireNonNull(gate, "gate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public ValidationResetReceipt reset() {
    return resetInternal(null);
  }

  public ValidationResetReceipt reset(ValidationResetCommand command) {
    Objects.requireNonNull(command, "command");
    ResetCommandResolution resolution = Objects.requireNonNull(
        operations.resolveResetCommand(command),
        "reset command resolution");
    if (resolution.isReplay()) {
      return resolution.replayReceipt();
    }
    return resetInternal(command);
  }

  /**
   * Completes an exact durable reset abandoned by a previous process. A live local reset wins the
   * process gate; a live remote owner wins the PostgreSQL advisory lock inside recoverResetting.
   */
  public Optional<ValidationResetReceipt> recoverInterruptedReset() {
    Optional<ValidationResetCommand> interrupted = operations.interruptedResetCommand();
    if (interrupted.isEmpty() || !gate.tryBeginReset()) {
      return Optional.empty();
    }
    return Optional.of(resetInternal(interrupted.orElseThrow(), true));
  }

  private ValidationResetReceipt resetInternal(ValidationResetCommand command) {
    return resetInternal(command, false);
  }

  private ValidationResetReceipt resetInternal(
      ValidationResetCommand command,
      boolean resetAlreadyBegun
  ) {
    Instant startedAt = clock.instant();
    if (!resetAlreadyBegun && !gate.tryBeginReset()) {
      return rejectedConcurrentReset(startedAt);
    }

    List<StepResult> results = new ArrayList<>();
    String databaseName = null;
    Long redisGeneration = null;
    Long memoryGeneration = null;

    Instant stepStartedAt = clock.instant();
    DatabaseInspection inspection;
    try {
      inspection = operations.inspectDatabase();
    } catch (RuntimeException ignored) {
      return failedWithoutDurableState(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.DATABASE,
          stepStartedAt,
          "DATABASE_INSPECTION_FAILED");
    }
    if (inspection == null) {
      return failedWithoutDurableState(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.DATABASE,
          stepStartedAt,
          "DATABASE_INSPECTION_FAILED");
    }
    databaseName = inspection.databaseName();
    if (!isSafeValidationDatabase(databaseName)) {
      return failedWithoutDurableState(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.DATABASE,
          stepStartedAt,
          "UNSAFE_DATABASE");
    }
    if (!ValidationResetOperations.ALLOWED_SCHEMA_INVENTORY.equals(
        inspection.applicationSchemas())) {
      return failedWithoutDurableState(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.DATABASE,
          stepStartedAt,
          "UNEXPECTED_DATABASE_SCHEMA");
    }
    try {
      if (resetAlreadyBegun) {
        operations.recoverResetting(command, databaseName, startedAt);
      } else if (command == null) {
        operations.markResetting(databaseName, startedAt);
      } else {
        operations.markResetting(command, databaseName, startedAt);
      }
    } catch (BusinessException rejected) {
      gate.markFailed();
      throw rejected;
    } catch (RuntimeException ignored) {
      return failedWithoutDurableState(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.DATABASE,
          stepStartedAt,
          "RESET_STATE_PERSIST_FAILED");
    }
    try {
      operations.cleanAndMigrate(ValidationResetOperations.RESET_SCHEMAS);
    } catch (RuntimeException ignored) {
      return failed(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.DATABASE,
          stepStartedAt,
          "DATABASE_RESET_FAILED");
    }
    results.add(succeeded(Step.DATABASE, stepStartedAt));

    stepStartedAt = clock.instant();
    try {
      redisGeneration = operations.flushRedisDatabaseAndAdvanceGeneration();
      if (redisGeneration <= 0) {
        return failed(
            command,
            databaseName,
            redisGeneration,
            memoryGeneration,
            startedAt,
            results,
            Step.REDIS,
            stepStartedAt,
            "REDIS_RESET_FAILED");
      }
    } catch (RuntimeException ignored) {
      return failed(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.REDIS,
          stepStartedAt,
          "REDIS_RESET_FAILED");
    }
    results.add(succeeded(Step.REDIS, stepStartedAt));

    stepStartedAt = clock.instant();
    try {
      operations.clearMarketState(redisGeneration);
    } catch (RuntimeException ignored) {
      return failed(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.MARKET_STATE,
          stepStartedAt,
          "MARKET_STATE_RESET_FAILED");
    }
    results.add(succeeded(Step.MARKET_STATE, stepStartedAt));

    stepStartedAt = clock.instant();
    try {
      operations.resetVirtualClock(redisGeneration);
    } catch (RuntimeException ignored) {
      return failed(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.VIRTUAL_CLOCK,
          stepStartedAt,
          "VIRTUAL_CLOCK_RESET_FAILED");
    }
    results.add(succeeded(Step.VIRTUAL_CLOCK, stepStartedAt));

    stepStartedAt = clock.instant();
    try {
      operations.resetExecutionPolicy(redisGeneration);
    } catch (RuntimeException ignored) {
      return failed(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.EXECUTION_POLICY,
          stepStartedAt,
          "EXECUTION_POLICY_RESET_FAILED");
    }
    results.add(succeeded(Step.EXECUTION_POLICY, stepStartedAt));

    stepStartedAt = clock.instant();
    try {
      operations.clearRuntimeRegistry(redisGeneration);
    } catch (RuntimeException ignored) {
      return failed(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.RUNTIME_REGISTRY,
          stepStartedAt,
          "RUNTIME_REGISTRY_RESET_FAILED");
    }
    results.add(succeeded(Step.RUNTIME_REGISTRY, stepStartedAt));

    stepStartedAt = clock.instant();
    try {
      memoryGeneration = operations.publishMemoryGeneration(redisGeneration);
    } catch (RuntimeException ignored) {
      return failed(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.MEMORY_GENERATION,
          stepStartedAt,
          "MEMORY_GENERATION_PUBLISH_FAILED");
    }
    if (!Objects.equals(redisGeneration, memoryGeneration)) {
      return failed(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.MEMORY_GENERATION,
          stepStartedAt,
          "GENERATION_MISMATCH");
    }
    StepResult memoryGenerationResult = succeeded(Step.MEMORY_GENERATION, stepStartedAt);
    results.add(memoryGenerationResult);
    ValidationResetReceipt succeeded = new ValidationResetReceipt(
        ValidationResetReceipt.Status.SUCCEEDED,
        databaseName,
        redisGeneration,
        memoryGeneration,
        startedAt,
        clock.instant(),
        results,
        null);
    try {
      persistResetReceipt(command, succeeded);
    } catch (RuntimeException ignored) {
      results.removeLast();
      return failed(
          command,
          databaseName,
          redisGeneration,
          memoryGeneration,
          startedAt,
          results,
          Step.MEMORY_GENERATION,
          stepStartedAt,
          "RESET_STATE_PERSIST_FAILED");
    }
    gate.markReady(redisGeneration);
    return succeeded;
  }

  private ValidationResetReceipt failed(
      ValidationResetCommand command,
      String databaseName,
      Long redisGeneration,
      Long memoryGeneration,
      Instant startedAt,
      List<StepResult> completed,
      Step failedStep,
      Instant stepStartedAt,
      String errorCode
  ) {
    return failed(
        command,
        databaseName,
        redisGeneration,
        memoryGeneration,
        startedAt,
        completed,
        failedStep,
        stepStartedAt,
        errorCode,
        true);
  }

  private ValidationResetReceipt failedWithoutDurableState(
      ValidationResetCommand command,
      String databaseName,
      Long redisGeneration,
      Long memoryGeneration,
      Instant startedAt,
      List<StepResult> completed,
      Step failedStep,
      Instant stepStartedAt,
      String errorCode
  ) {
    return failed(
        command,
        databaseName,
        redisGeneration,
        memoryGeneration,
        startedAt,
        completed,
        failedStep,
        stepStartedAt,
        errorCode,
        false);
  }

  private ValidationResetReceipt failed(
      ValidationResetCommand command,
      String databaseName,
      Long redisGeneration,
      Long memoryGeneration,
      Instant startedAt,
      List<StepResult> completed,
      Step failedStep,
      Instant stepStartedAt,
      String errorCode,
      boolean persistDurableState
  ) {
    completed.add(new StepResult(
        failedStep,
        StepStatus.FAILED,
        stepStartedAt,
        clock.instant(),
        errorCode));
    appendSkipped(completed);
    ValidationResetReceipt failed = new ValidationResetReceipt(
        ValidationResetReceipt.Status.FAILED,
        databaseName,
        redisGeneration,
        memoryGeneration,
        startedAt,
        clock.instant(),
        completed,
        errorCode);
    if (persistDurableState) {
      try {
        persistResetReceipt(command, failed);
      } catch (RuntimeException ignored) {
        // The process-local gate still fails closed; infrastructure details remain undisclosed.
      }
    }
    gate.markFailed();
    return failed;
  }

  private void persistResetReceipt(
      ValidationResetCommand command,
      ValidationResetReceipt receipt
  ) {
    if (command == null) {
      operations.persistResetReceipt(receipt);
    } else {
      operations.persistResetReceipt(command, receipt);
    }
  }

  private ValidationResetReceipt rejectedConcurrentReset(Instant startedAt) {
    List<StepResult> skipped = new ArrayList<>();
    appendSkipped(skipped);
    return new ValidationResetReceipt(
        ValidationResetReceipt.Status.FAILED,
        null,
        null,
        null,
        startedAt,
        clock.instant(),
        skipped,
        "RESET_ALREADY_IN_PROGRESS");
  }

  private StepResult succeeded(Step step, Instant startedAt) {
    return new StepResult(step, StepStatus.SUCCEEDED, startedAt, clock.instant(), null);
  }

  private static void appendSkipped(List<StepResult> results) {
    for (int index = results.size(); index < Step.values().length; index++) {
      results.add(new StepResult(Step.values()[index], StepStatus.SKIPPED, null, null, null));
    }
  }

  private static boolean isSafeValidationDatabase(String databaseName) {
    return databaseName != null && databaseName.matches(VALIDATION_DATABASE_PATTERN);
  }
}
