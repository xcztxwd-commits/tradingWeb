package com.fxplatform.tradinglab.report;

import java.util.UUID;

public interface TradingLabReportWriter {

  void append(UUID reportId, TradingLabReportSection section, Object value);

  void flush(UUID reportId);

  void complete(UUID reportId);

  void fail(UUID reportId, String failureCode, String failureMessage);

  void cancel(UUID reportId, String reason);
}
