package com.fxplatform.tradinglab.client;

import java.time.Instant;
import java.util.List;

public record ValidationResetReceipt(
    Status status,
    String databaseName,
    Long redisGeneration,
    Long memoryGeneration,
    Instant startedAt,
    Instant finishedAt,
    List<StepResult> steps,
    String errorCode
) {

  public ValidationResetReceipt {
    if (status == null) {
      throw new IllegalArgumentException("Validation reset receipt is incomplete");
    }
    if (status == Status.SUCCEEDED
        && (databaseName == null
            || databaseName.isBlank()
            || startedAt == null
            || finishedAt == null)) {
      throw new IllegalArgumentException("Successful validation reset receipt is incomplete");
    }
    if (status == Status.FAILED
        && (startedAt == null
            || finishedAt == null
            || errorCode == null
            || errorCode.isBlank())) {
      throw new IllegalArgumentException("Failed validation reset receipt is incomplete");
    }
    steps = List.copyOf(steps == null ? List.of() : steps);
  }

  public enum Status {
    SUCCEEDED,
    FAILED
  }

  public enum Step {
    DATABASE,
    REDIS,
    MARKET_STATE,
    VIRTUAL_CLOCK,
    EXECUTION_POLICY,
    RUNTIME_REGISTRY,
    MEMORY_GENERATION
  }

  public enum StepStatus {
    SUCCEEDED,
    FAILED,
    SKIPPED
  }

  public record StepResult(
      Step step,
      StepStatus status,
      Instant startedAt,
      Instant finishedAt,
      String errorCode
  ) {

    public StepResult {
      if (step == null || status == null
          || (status == StepStatus.SKIPPED
              && (startedAt != null || finishedAt != null || errorCode != null))
          || (status != StepStatus.SKIPPED
              && (startedAt == null || finishedAt == null))
          || (status == StepStatus.SUCCEEDED && errorCode != null)
          || (status == StepStatus.FAILED
              && (errorCode == null || errorCode.isBlank()))) {
        throw new IllegalArgumentException("Validation reset step receipt is incomplete");
      }
    }
  }
}
