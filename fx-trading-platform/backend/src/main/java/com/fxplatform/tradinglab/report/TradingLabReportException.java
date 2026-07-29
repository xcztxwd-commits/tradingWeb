package com.fxplatform.tradinglab.report;

import com.fxplatform.common.exception.BusinessException;

public class TradingLabReportException extends BusinessException {

  public TradingLabReportException(String code, String message) {
    super(code, message);
  }

  public TradingLabReportException(String code, String message, Throwable cause) {
    super(code, message, cause);
  }
}
