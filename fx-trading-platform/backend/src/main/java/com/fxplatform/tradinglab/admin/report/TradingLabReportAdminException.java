package com.fxplatform.tradinglab.admin.report;

public final class TradingLabReportAdminException extends RuntimeException {

  private final String code;
  private final int httpStatus;

  public TradingLabReportAdminException(
      String code,
      String message,
      int httpStatus
  ) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
  }

  public TradingLabReportAdminException(
      String code,
      String message,
      int httpStatus,
      Throwable cause
  ) {
    super(message, cause);
    this.code = code;
    this.httpStatus = httpStatus;
  }

  public String getCode() {
    return code;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public static TradingLabReportAdminException notFound() {
    return new TradingLabReportAdminException(
        "TRADING_LAB_REPORT_NOT_FOUND",
        "Trading Lab report was not found",
        404);
  }

  public static TradingLabReportAdminException conflict(String message) {
    return new TradingLabReportAdminException(
        "TRADING_LAB_REPORT_STATE_CONFLICT",
        message,
        409);
  }

  public static TradingLabReportAdminException confirmationInvalid() {
    return new TradingLabReportAdminException(
        "TRADING_LAB_PRINT_CONFIRMATION_INVALID",
        "Trading Lab print confirmation is missing, expired, mismatched, or already used",
        403);
  }

  public static TradingLabReportAdminException confirmationUnavailable(
      Throwable cause
  ) {
    return new TradingLabReportAdminException(
        "TRADING_LAB_PRINT_CONFIRMATION_UNAVAILABLE",
        "Trading Lab print confirmation service is unavailable",
        503,
        cause);
  }
}
