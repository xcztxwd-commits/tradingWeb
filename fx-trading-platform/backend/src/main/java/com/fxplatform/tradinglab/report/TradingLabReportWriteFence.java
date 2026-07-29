package com.fxplatform.tradinglab.report;

import java.util.Objects;
import java.util.UUID;

public record TradingLabReportWriteFence(
    UUID runId,
    UUID reportId,
    String claimOwner
) {

  public TradingLabReportWriteFence {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(reportId, "reportId");
    if (claimOwner == null || claimOwner.isBlank()) {
      throw new IllegalArgumentException("Trading Lab report claim owner is required");
    }
  }
}
