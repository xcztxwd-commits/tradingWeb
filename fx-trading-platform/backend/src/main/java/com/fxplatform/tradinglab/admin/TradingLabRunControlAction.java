package com.fxplatform.tradinglab.admin;

public enum TradingLabRunControlAction {
  PAUSE("pause"),
  RESUME("resume"),
  CANCEL("cancel");

  private final String auditValue;

  TradingLabRunControlAction(String auditValue) {
    this.auditValue = auditValue;
  }

  public String auditValue() {
    return auditValue;
  }
}
