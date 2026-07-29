package com.fxplatform.validation.service;

import java.time.Instant;
import java.util.List;

/** Sanitized reset outcome; exception messages and infrastructure values are never exposed. */
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
    steps = List.copyOf(steps);
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

  /** Per-step receipt contains only a stable error code, never an exception or error message. */
  public record StepResult(
      Step step,
      StepStatus status,
      Instant startedAt,
      Instant finishedAt,
      String errorCode
  ) {
  }
}
