package com.fxplatform.tradinglab.client;

import java.util.Objects;
import java.util.UUID;

public record ValidationControlReceipt(
    UUID runId,
    String control,
    ValidationRunState state
) {

  public ValidationControlReceipt {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(control, "control");
    Objects.requireNonNull(state, "state");
    if (control.isBlank() || control.length() > 64) {
      throw new IllegalArgumentException("Validation control receipt is incomplete");
    }
  }
}
