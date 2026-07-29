package com.fxplatform.tradinglab.client;

import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizer;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Sanitized durable HTTP evidence named by the Phase 2 Task 7 contract. */
public record ValidationHttpResult(
    long sequence,
    String environment,
    String method,
    URI url,
    Instant virtualTime,
    Instant realTime,
    int status,
    Duration duration,
    Map<String, Object> sanitizedRequest,
    Map<String, Object> sanitizedResponse,
    String traceId,
    String correlationId,
    ThrowableInfo exception
) {

  private static final Set<String> SAFE_KEYS = Set.of(
      "sequence",
      "environment",
      "method",
      "url",
      "virtualTime",
      "realTime",
      "status",
      "duration",
      "sanitizedRequest",
      "sanitizedResponse",
      "traceId",
      "correlationId",
      "exception");

  public ValidationHttpResult {
    if (sequence < 0L
        || !"validation".equals(environment)
        || (!"GET".equals(method) && !"POST".equals(method))
        || !allowedUri(url)
        || !allowedMethod(method, url)
        || realTime == null
        || status < 0
        || status > 599
        || (status > 0 && status < 100)
        || duration == null
        || duration.isNegative()
        || duration.compareTo(Duration.ofHours(1)) > 0
        || !safeIdentifier(traceId)
        || !safeIdentifier(correlationId)
        || (status == 0 && exception == null)) {
      throw new IllegalArgumentException("Validation HTTP evidence is inconsistent");
    }
    sanitizedRequest = ValidationClientSafeValues.freezeMap(sanitizedRequest);
    sanitizedResponse = ValidationClientSafeValues.freezeMap(sanitizedResponse);
  }

  public ValidationHttpResult withSequence(long durableSequence) {
    return new ValidationHttpResult(
        durableSequence,
        environment,
        method,
        url,
        virtualTime,
        realTime,
        status,
        duration,
        sanitizedRequest,
        sanitizedResponse,
        traceId,
        correlationId,
        exception);
  }

  public Map<String, Object> toSafeMap() {
    Map<String, Object> safe = new LinkedHashMap<>();
    safe.put("sequence", sequence);
    safe.put("environment", environment);
    safe.put("method", method);
    safe.put("url", url.toASCIIString());
    safe.put("virtualTime", virtualTime == null ? null : virtualTime.toString());
    safe.put("realTime", realTime.toString());
    safe.put("status", status);
    safe.put("duration", duration.toString());
    safe.put("sanitizedRequest", sanitizedRequest);
    safe.put("sanitizedResponse", sanitizedResponse);
    safe.put("traceId", traceId);
    safe.put("correlationId", correlationId);
    safe.put("exception", exception == null ? null : exception.toSafeMap());
    return java.util.Collections.unmodifiableMap(safe);
  }

  public static ValidationHttpResult fromSafeMap(Map<?, ?> safe) {
    if (safe == null || !safe.keySet().equals(SAFE_KEYS)) {
      throw invalidShape();
    }
    long sequence = exactLong(safe.get("sequence"), "sequence");
    int status = Math.toIntExact(exactLong(safe.get("status"), "status"));
    String environment = text(safe.get("environment"), "environment");
    String method = text(safe.get("method"), "method");
    URI url;
    Instant virtualTime;
    Instant realTime;
    Duration duration;
    try {
      url = URI.create(text(safe.get("url"), "url"));
      virtualTime = nullableInstant(safe.get("virtualTime"));
      realTime = Instant.parse(text(safe.get("realTime"), "realTime"));
      duration = Duration.parse(text(safe.get("duration"), "duration"));
    } catch (IllegalArgumentException exception) {
      throw invalidShape();
    }
    Map<String, Object> request = safeMap(safe.get("sanitizedRequest"));
    Map<String, Object> response = safeMap(safe.get("sanitizedResponse"));
    String traceId = nullableText(safe.get("traceId"), "traceId");
    String correlationId = nullableText(safe.get("correlationId"), "correlationId");
    ThrowableInfo failure = null;
    Object rawFailure = safe.get("exception");
    if (rawFailure != null) {
      if (!(rawFailure instanceof Map<?, ?> failureMap)) {
        throw invalidShape();
      }
      failure = ThrowableInfo.fromSafeMap(failureMap);
    }
    return new ValidationHttpResult(
        sequence,
        environment,
        method,
        url,
        virtualTime,
        realTime,
        status,
        duration,
        request,
        response,
        traceId,
        correlationId,
        failure);
  }

  /** Re-seals journaled sanitized evidence after a process restart. */
  public SafeTradingLabHttpTrace rebuildReportTrace(
      TradingLabHttpTraceSanitizer sanitizer
  ) {
    return ValidationReportTraceAdapter.rebuild(this, sanitizer);
  }

  /** Re-seals the durable envelope and inherits dynamic secrets from the live raw exchange. */
  public SafeTradingLabHttpTrace rebuildReportTrace(
      TradingLabHttpTraceSanitizer sanitizer,
      SafeTradingLabHttpTrace source
  ) {
    return ValidationReportTraceAdapter.rebuild(this, sanitizer, source);
  }

  private static Map<String, Object> safeMap(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw invalidShape();
    }
    return ValidationClientSafeValues.freezeMap(map);
  }

  private static long exactLong(Object value, String name) {
    if (!(value instanceof Number number)) {
      throw invalidShape();
    }
    try {
      if (number instanceof java.math.BigDecimal decimal) {
        return decimal.longValueExact();
      }
      if (number instanceof java.math.BigInteger integer) {
        return integer.longValueExact();
      }
      if (number instanceof Byte
          || number instanceof Short
          || number instanceof Integer
          || number instanceof Long) {
        return number.longValue();
      }
    } catch (ArithmeticException exception) {
      throw invalidShape();
    }
    throw new IllegalArgumentException("Validation " + name + " must be an exact integer");
  }

  private static String text(Object value, String name) {
    if (!(value instanceof String text) || text.isBlank() || text.length() > 1_048_576) {
      throw new IllegalArgumentException("Validation " + name + " is invalid");
    }
    return text;
  }

  private static String nullableText(Object value, String name) {
    return value == null ? null : text(value, name);
  }

  private static Instant nullableInstant(Object value) {
    return value == null ? null : Instant.parse(text(value, "virtualTime"));
  }

  private static boolean safeIdentifier(String value) {
    if (value == null) {
      return true;
    }
    if (value.isBlank() || value.length() > 256) {
      return false;
    }
    return value.codePoints().allMatch(codePoint ->
        (codePoint >= 'a' && codePoint <= 'z')
            || (codePoint >= 'A' && codePoint <= 'Z')
            || (codePoint >= '0' && codePoint <= '9')
            || codePoint == '-'
            || codePoint == '_'
            || codePoint == '.'
            || codePoint == ':');
  }

  private static boolean allowedUri(URI uri) {
    if (uri == null
        || !"http".equals(uri.getScheme())
        || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1
        || uri.getUserInfo() != null
        || uri.getFragment() != null) {
      return false;
    }
    String path = uri.getPath();
    if ("/internal/validation/reset".equals(path)
        || "/internal/validation/runs".equals(path)) {
      return uri.getRawQuery() == null;
    }
    if ("/internal/validation/state".equals(path)) {
      if (uri.getRawQuery() == null
          || !uri.getRawQuery().matches("runId=[0-9a-f]{8}-[0-9a-f]{4}-"
              + "[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
        return false;
      }
      try {
        UUID.fromString(uri.getRawQuery().substring("runId=".length()));
        return true;
      } catch (IllegalArgumentException invalid) {
        return false;
      }
    }
    String prefix = "/internal/validation/runs/";
    if (path == null || !path.startsWith(prefix)) {
      return false;
    }
    String suffix = path.substring(prefix.length());
    String[] parts = suffix.split("/", -1);
    try {
      UUID.fromString(parts[0]);
    } catch (IllegalArgumentException exception) {
      return false;
    }
    if (parts.length == 1) {
      return uri.getRawQuery() == null;
    }
    if (parts.length != 2) {
      return false;
    }
    if ("events".equals(parts[1])) {
      return uri.getRawQuery() != null
          && uri.getRawQuery().matches("afterSequence=[0-9]+&limit=1");
    }
    return Set.of("pause", "resume", "cancel").contains(parts[1])
        && uri.getRawQuery() == null;
  }

  private static boolean allowedMethod(String method, URI uri) {
    String path = uri.getPath();
    if ("GET".equals(method)) {
      return "/internal/validation/state".equals(path)
          || (path != null && path.endsWith("/events"));
    }
    return "/internal/validation/reset".equals(path)
        || "/internal/validation/runs".equals(path)
        || (path != null
            && (path.endsWith("/pause")
                || path.endsWith("/resume")
                || path.endsWith("/cancel")));
  }

  private static IllegalArgumentException invalidShape() {
    return new IllegalArgumentException("Validation HTTP evidence shape is invalid");
  }
}
