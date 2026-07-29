package com.fxplatform.tradinglab.environment;

public enum TradingLabEnvironmentAction {
  START("start", false),
  STOP("stop", true),
  RESTART("restart", true);

  private final String pathValue;
  private final boolean idleEnvironmentRequired;

  TradingLabEnvironmentAction(
      String pathValue,
      boolean idleEnvironmentRequired
  ) {
    this.pathValue = pathValue;
    this.idleEnvironmentRequired = idleEnvironmentRequired;
  }

  public String pathValue() {
    return pathValue;
  }

  public boolean idleEnvironmentRequired() {
    return idleEnvironmentRequired;
  }

  public static TradingLabEnvironmentAction fromPath(String value) {
    return switch (value) {
      case "start" -> START;
      case "stop" -> STOP;
      case "restart" -> RESTART;
      default -> throw TradingLabEnvironmentException.invalidAction();
    };
  }
}
