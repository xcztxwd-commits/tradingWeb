package com.fxplatform.tradinglab.report;

enum TradingLabReportRecoveryState {
  EMPTY(false, false),
  QUARANTINED(false, true),
  TERMINAL(true, false),
  QUARANTINED_TERMINAL(true, true);

  private final boolean terminal;
  private final boolean quarantined;

  TradingLabReportRecoveryState(boolean terminal, boolean quarantined) {
    this.terminal = terminal;
    this.quarantined = quarantined;
  }

  boolean terminal() {
    return terminal;
  }

  boolean quarantined() {
    return quarantined;
  }
}
