package com.fxplatform.execution;

public enum ExecutionMode {
  DISABLED("disabled", false),
  DEMO("demo", false),
  BROKER("broker", true),
  FIX("fix", true),
  LP("lp", true);

  private final String propertyValue;
  private final boolean liveMode;

  ExecutionMode(String propertyValue, boolean liveMode) {
    this.propertyValue = propertyValue;
    this.liveMode = liveMode;
  }

  public String propertyValue() {
    return propertyValue;
  }

  public boolean liveMode() {
    return liveMode;
  }
}
