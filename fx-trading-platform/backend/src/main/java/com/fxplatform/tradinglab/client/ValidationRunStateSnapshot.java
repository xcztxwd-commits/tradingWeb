package com.fxplatform.tradinglab.client;

import java.util.Objects;
import java.util.UUID;
import java.time.Instant;

/** Durable identity and control flags used to reconcile an uncertain HTTP outcome. */
public record ValidationRunStateSnapshot(
    UUID runId,
    long generation,
    String requestFingerprint,
    ValidationRunState state,
    boolean pauseRequested,
    boolean cancelRequested,
    long lastCompletedTickSequence,
    Instant virtualTime,
    long eventHighWatermark,
    String failureCode,
    Instant updatedAt,
    boolean terminal
) {

  public ValidationRunStateSnapshot {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(requestFingerprint, "requestFingerprint");
    Objects.requireNonNull(state, "state");
    if (failureCode != null && failureCode.isBlank()) {
      failureCode = null;
    }
    if (generation <= 0L
        || requestFingerprint.isBlank()
        || requestFingerprint.length() > 128
        || lastCompletedTickSequence < -1L
        || eventHighWatermark < 0L
        || updatedAt == null
        || (failureCode != null
            && (failureCode.length() > 128
                || !failureCode.matches("[A-Z0-9_]+")))
        || terminal != state.terminal()) {
      throw new IllegalArgumentException("Validation recovery state is inconsistent");
    }
  }

  public boolean matchesAcceptedIdentity(ValidationRunStartRequest request) {
    return request != null
        && runId.equals(request.runId())
        && generation == request.generation()
        && requestFingerprint.equals(request.requestFingerprint());
  }

  public long highWatermark() {
    return eventHighWatermark;
  }
}
