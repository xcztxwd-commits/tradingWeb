package com.fxplatform.tradinglab.client;

import java.util.Objects;
import java.util.UUID;

public record ValidationRunAccepted(
    UUID runId,
    String requestFingerprint,
    ValidationRunState state
) {

  public ValidationRunAccepted {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(requestFingerprint, "requestFingerprint");
    Objects.requireNonNull(state, "state");
    if (requestFingerprint.isBlank() || requestFingerprint.length() > 128) {
      throw new IllegalArgumentException("Validation accepted identity is incomplete");
    }
  }
}
