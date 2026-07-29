package com.fxplatform.tradinglab.client;

import java.util.EnumSet;
import java.util.Set;

public enum ValidationRunState {
  ACCEPTED,
  STARTING,
  RUNNING,
  PAUSED,
  RECOVERING,
  RECOVERY_BLOCKED,
  CANCELLING,
  CANCELLED,
  FAILED,
  COMPLETED;

  private static final Set<ValidationRunState> TERMINAL =
      EnumSet.of(CANCELLED, FAILED, COMPLETED);

  public boolean terminal() {
    return TERMINAL.contains(this);
  }
}
