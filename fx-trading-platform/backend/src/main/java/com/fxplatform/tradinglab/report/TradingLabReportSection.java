package com.fxplatform.tradinglab.report;

public enum TradingLabReportSection {

  METADATA("metadata", Shape.OBJECT),
  ACTOR("actor", Shape.OBJECT),
  ENVIRONMENT("environment", Shape.OBJECT),
  SCENARIO("scenario", Shape.OBJECT),
  MODEL_VERSION("modelVersion", Shape.STRING),
  CONFIG_SNAPSHOT("configSnapshot", Shape.OBJECT),
  LOCAL_CALCULATION("localCalculation", Shape.OBJECT),
  LIFECYCLE("lifecycle", Shape.ARRAY),
  API_TRACE("apiTrace", Shape.ARRAY),
  MARKET_TICKS("marketTicks", Shape.ARRAY),
  CHECKPOINTS("checkpoints", Shape.ARRAY),
  ACTUAL_STATE("actualState", Shape.OBJECT),
  ERRORS("errors", Shape.ARRAY),
  CLEANUP("cleanup", Shape.OBJECT);

  private final String jsonKey;
  private final Shape shape;

  TradingLabReportSection(String jsonKey, Shape shape) {
    this.jsonKey = jsonKey;
    this.shape = shape;
  }

  public String jsonKey() {
    return jsonKey;
  }

  public boolean isArray() {
    return shape == Shape.ARRAY;
  }

  public boolean isString() {
    return shape == Shape.STRING;
  }

  public String emptyJson() {
    return switch (shape) {
      case OBJECT -> "{}";
      case STRING -> "\"\"";
      case ARRAY -> "[]";
    };
  }

  private enum Shape {
    OBJECT,
    STRING,
    ARRAY
  }
}
