package com.fxplatform.tradinglab.client;

import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import java.util.Objects;

/**
 * One HTTP outcome. Failures are values, so the coordinator can journal evidence before deciding
 * whether to retry or fail closed.
 */
public record ValidationBackendExchange<T>(
    T data,
    ValidationHttpResult result,
    SafeTradingLabHttpTrace reportTrace
) {

  public ValidationBackendExchange {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(reportTrace, "reportTrace");
    boolean successful = result.exception() == null
        && result.status() >= 200
        && result.status() < 300;
    if (successful != (data != null)) {
      throw new IllegalArgumentException("Validation exchange data and outcome disagree");
    }
  }

  public boolean succeeded() {
    return data != null;
  }
}
