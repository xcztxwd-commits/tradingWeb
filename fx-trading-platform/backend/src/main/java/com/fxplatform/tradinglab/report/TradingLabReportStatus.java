package com.fxplatform.tradinglab.report;

public enum TradingLabReportStatus {
  PENDING,
  WRITING,
  COMPLETED,
  FAILED,
  CANCELLED;

  public boolean terminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED;
  }
}
