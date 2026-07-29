package com.fxplatform.tradinglab.client;

import java.util.Objects;

public record ValidationSupervisorHealth(String status) {

  public ValidationSupervisorHealth {
    Objects.requireNonNull(status, "status");
  }
}
