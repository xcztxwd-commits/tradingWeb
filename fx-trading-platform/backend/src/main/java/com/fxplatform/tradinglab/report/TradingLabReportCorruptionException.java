package com.fxplatform.tradinglab.report;

import com.fxplatform.common.exception.BusinessException;

public final class TradingLabReportCorruptionException extends BusinessException {

  public static final String CODE = "TRADING_LAB_REPORT_CORRUPT";

  public TradingLabReportCorruptionException() {
    super(CODE, "Trading Lab report data is corrupt");
  }

  public TradingLabReportCorruptionException(String message) {
    super(CODE, message);
  }

  public TradingLabReportCorruptionException(String message, Throwable cause) {
    super(CODE, message, cause);
  }
}
