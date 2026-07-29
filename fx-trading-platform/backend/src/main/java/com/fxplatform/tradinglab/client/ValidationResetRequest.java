package com.fxplatform.tradinglab.client;

import java.util.Objects;
import java.util.UUID;

/** Stable downstream identity for an isolated validation reset. */
public record ValidationResetRequest(
    UUID runId,
    UUID operationId,
    Mode mode,
    long expectedGeneration
) {

  public ValidationResetRequest {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(mode, "mode");
    if (expectedGeneration < 0L
        || expectedGeneration == Long.MAX_VALUE
        || (mode == Mode.FINAL && expectedGeneration == 0L)) {
      throw new IllegalArgumentException("Reset mode and expected generation do not match");
    }
  }

  public enum Mode {
    INITIAL,
    FINAL
  }
}
