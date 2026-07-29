package com.fxplatform.tradinglab.state;

public enum TradingLabRunState {
  DRAFT,
  VALIDATING,
  QUEUED,
  RESETTING,
  RUNNING,
  PAUSED,
  CANCELLING,
  CANCELLED,
  FAILED,
  COMPLETED,
  CLEANING
}
