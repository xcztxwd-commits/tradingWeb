package com.fxplatform.tradinglab.report;

import java.util.Objects;

public record TradingLabReportOutcome(
    TradingLabReportStatus status,
    String failureCode,
    String failureMessage
) {

  public TradingLabReportOutcome {
    Objects.requireNonNull(status, "status");
    if (!status.terminal()) {
      throw new IllegalArgumentException("Trading Lab report outcome must be terminal");
    }
    if (status == TradingLabReportStatus.COMPLETED
        && (failureCode != null || failureMessage != null)) {
      throw new IllegalArgumentException("Completed Trading Lab report cannot retain a failure");
    }
    if (status == TradingLabReportStatus.FAILED
        && (failureCode == null || failureCode.isBlank())) {
      throw new IllegalArgumentException("Failed Trading Lab report requires a failure code");
    }
    if (status == TradingLabReportStatus.CANCELLED
        && !"CANCELLED".equals(failureCode)) {
      throw new IllegalArgumentException("Cancelled Trading Lab report requires CANCELLED code");
    }
  }

  public static TradingLabReportOutcome completed() {
    return new TradingLabReportOutcome(TradingLabReportStatus.COMPLETED, null, null);
  }

  public static TradingLabReportOutcome failed(String code, String message) {
    return new TradingLabReportOutcome(TradingLabReportStatus.FAILED, code, message);
  }

  public static TradingLabReportOutcome cancelled(String reason) {
    return new TradingLabReportOutcome(TradingLabReportStatus.CANCELLED, "CANCELLED", reason);
  }
}
