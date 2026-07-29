package com.fxplatform.tradinglab.client;

import java.util.Objects;

public record ValidationSupervisorActionResult(
    ValidationSupervisorAction action,
    boolean relayRunning
) {

  public ValidationSupervisorActionResult {
    Objects.requireNonNull(action, "action");
  }

  public static ValidationSupervisorActionResult start(boolean relayRunning) {
    return new ValidationSupervisorActionResult(
        ValidationSupervisorAction.START,
        relayRunning);
  }

  public static ValidationSupervisorActionResult stop(boolean relayRunning) {
    return new ValidationSupervisorActionResult(
        ValidationSupervisorAction.STOP,
        relayRunning);
  }

  public static ValidationSupervisorActionResult restart(boolean relayRunning) {
    return new ValidationSupervisorActionResult(
        ValidationSupervisorAction.RESTART,
        relayRunning);
  }
}
