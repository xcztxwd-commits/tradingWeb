package com.fxplatform.tradinglab.environment;

import com.fxplatform.common.exception.BusinessException;
import java.util.Objects;
import org.springframework.http.HttpStatus;

public final class TradingLabEnvironmentException extends BusinessException {

  private final HttpStatus httpStatus;

  private TradingLabEnvironmentException(
      HttpStatus httpStatus,
      String code,
      String message
  ) {
    super(code, message);
    this.httpStatus = Objects.requireNonNull(httpStatus, "httpStatus");
  }

  public HttpStatus httpStatus() {
    return httpStatus;
  }

  public static TradingLabEnvironmentException invalidAction() {
    return new TradingLabEnvironmentException(
        HttpStatus.BAD_REQUEST,
        "TRADING_LAB_ENVIRONMENT_ACTION_INVALID",
        "Environment action must be start, stop, or restart");
  }

  public static TradingLabEnvironmentException mutationBusy() {
    return new TradingLabEnvironmentException(
        HttpStatus.CONFLICT,
        "TRADING_LAB_ENVIRONMENT_BUSY",
        "Another environment mutation is in progress");
  }

  public static TradingLabEnvironmentException activeRunConflict() {
    return new TradingLabEnvironmentException(
        HttpStatus.CONFLICT,
        "TRADING_LAB_ACTIVE_RUN_CONFLICT",
        "Environment stop or restart is blocked by a non-terminal run");
  }

  public static TradingLabEnvironmentException supervisorUnavailable() {
    return new TradingLabEnvironmentException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "TRADING_LAB_SUPERVISOR_UNAVAILABLE",
        "Validation Supervisor is unavailable");
  }
}
