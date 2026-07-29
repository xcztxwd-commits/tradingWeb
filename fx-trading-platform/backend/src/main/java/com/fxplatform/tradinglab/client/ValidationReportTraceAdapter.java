package com.fxplatform.tradinglab.client;

import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceInput;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Trusted adapter for re-sealing already sanitized durable HTTP evidence. */
public final class ValidationReportTraceAdapter {

  private ValidationReportTraceAdapter() {
  }

  public static SafeTradingLabHttpTrace rebuild(
      ValidationHttpResult result,
      TradingLabHttpTraceSanitizer sanitizer
  ) {
    return rebuild(result, sanitizer, null);
  }

  public static SafeTradingLabHttpTrace rebuild(
      ValidationHttpResult result,
      TradingLabHttpTraceSanitizer sanitizer,
      SafeTradingLabHttpTrace source
  ) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(sanitizer, "sanitizer");
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("sequence", result.sequence());
    request.put("environment", result.environment());
    request.put("method", result.method());
    request.put("url", result.url().toASCIIString());
    request.put("virtualTime",
        result.virtualTime() == null ? null : result.virtualTime().toString());
    request.put("realTime", result.realTime().toString());
    request.put("sanitizedRequest", result.sanitizedRequest());
    Map<String, Object> response = new LinkedHashMap<>(result.sanitizedResponse());
    response.put("status", result.status());
    response.put("duration", result.duration().toMillis());
    response.put("traceId", result.traceId());
    response.put("correlationId", result.correlationId());
    response.put("recordedException",
        result.exception() == null ? null : result.exception().toSafeMap());
    TradingLabHttpTraceInput input = new TradingLabHttpTraceInput(
        result.url(),
        Map.of(),
        Map.of(),
        "application/json",
        request,
        "application/json",
        response,
        null,
        null,
        java.util.List.of(),
        null);
    return source == null
        ? sanitizer.sanitize(input)
        : sanitizer.sanitizeWithInheritedSecrets(input, source);
  }
}
