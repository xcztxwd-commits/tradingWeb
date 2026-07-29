package com.fxplatform.tradinglab.report;

import com.fxplatform.tradinglab.entity.TradingLabReportEntity;

final class TradingLabReportQuarantinePolicy {

  static final String FAILURE_CODE = "TRADING_LAB_REPORT_UNSAFE_TRACE";
  static final String FAILURE_MESSAGE =
      "Trading Lab report contains unsafe trace evidence";
  static final String REDACTED_MODEL_VERSION = "[REDACTED]";

  private TradingLabReportQuarantinePolicy() {
  }

  static boolean hasOpenMarker(TradingLabReportEntity report) {
    return FAILURE_CODE.equals(report.getFailureCode())
        && FAILURE_MESSAGE.equals(report.getFailureMessage());
  }

  static boolean hasNormalizedOpenMarker(TradingLabReportEntity report) {
    return hasOpenMarker(report)
        && REDACTED_MODEL_VERSION.equals(report.getModelVersion())
        && Long.valueOf(0L).equals(report.getUncompressedBytes())
        && Long.valueOf(0L).equals(report.getCompressedBytes())
        && Integer.valueOf(0).equals(report.getChunkCount());
  }

  static boolean isGenericOutcome(TradingLabReportOutcome outcome) {
    return (outcome.status() == TradingLabReportStatus.FAILED
            && FAILURE_CODE.equals(outcome.failureCode())
            && FAILURE_MESSAGE.equals(outcome.failureMessage()))
        || (outcome.status() == TradingLabReportStatus.CANCELLED
            && "CANCELLED".equals(outcome.failureCode())
            && FAILURE_MESSAGE.equals(outcome.failureMessage()));
  }

  static boolean hasGenericTerminalOutcome(TradingLabReportEntity report) {
    return (TradingLabReportStatus.FAILED.name().equals(report.getStatus())
            && hasOpenMarker(report))
        || (TradingLabReportStatus.CANCELLED.name().equals(report.getStatus())
            && "CANCELLED".equals(report.getFailureCode())
            && FAILURE_MESSAGE.equals(report.getFailureMessage()));
  }

  static boolean isNormalizedGenericTerminal(TradingLabReportEntity report) {
    return report.getCompletedAt() != null
        && hasGenericTerminalOutcome(report)
        && REDACTED_MODEL_VERSION.equals(report.getModelVersion())
        && Long.valueOf(0L).equals(report.getCompressedBytes())
        && Integer.valueOf(0).equals(report.getChunkCount());
  }
}
