package com.fxplatform.tradinglab.sse;

import java.util.Objects;
import org.springframework.http.HttpStatus;

final class TradingLabSseRequestException extends RuntimeException {

  private final HttpStatus status;

  private TradingLabSseRequestException(HttpStatus status, String message) {
    super(message);
    this.status = Objects.requireNonNull(status, "status");
  }

  static TradingLabSseRequestException badLastEventId() {
    return new TradingLabSseRequestException(
        HttpStatus.BAD_REQUEST,
        "Last-Event-ID must be a non-negative base-10 long");
  }

  static TradingLabSseRequestException runNotFound() {
    return new TradingLabSseRequestException(
        HttpStatus.NOT_FOUND,
        "Trading Lab run was not found");
  }

  HttpStatus status() {
    return status;
  }
}
