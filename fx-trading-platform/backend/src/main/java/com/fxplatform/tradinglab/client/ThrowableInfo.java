package com.fxplatform.tradinglab.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Stable, stack-free failure evidence. Raw transport throwables never cross the client seam. */
public record ThrowableInfo(
    String type,
    String code,
    String message,
    boolean retryable
) {

  private static final Set<String> TYPES =
      Set.of("REQUEST", "TRANSPORT", "REMOTE", "PROTOCOL", "TRACE");

  public ThrowableInfo {
    if (!TYPES.contains(type)
        || code == null
        || code.isBlank()
        || code.length() > 128
        || message == null
        || message.isBlank()
        || message.length() > 512) {
      throw new IllegalArgumentException("Validation failure evidence is invalid");
    }
  }

  public Map<String, Object> toSafeMap() {
    Map<String, Object> safe = new LinkedHashMap<>();
    safe.put("type", type);
    safe.put("code", code);
    safe.put("message", message);
    safe.put("retryable", retryable);
    return java.util.Collections.unmodifiableMap(safe);
  }

  public static ThrowableInfo fromSafeMap(Map<?, ?> safe) {
    if (safe == null
        || !safe.keySet().equals(Set.of("type", "code", "message", "retryable"))
        || !(safe.get("type") instanceof String type)
        || !(safe.get("code") instanceof String code)
        || !(safe.get("message") instanceof String message)
        || !(safe.get("retryable") instanceof Boolean retryable)) {
      throw new IllegalArgumentException("Validation failure evidence shape is invalid");
    }
    return new ThrowableInfo(type, code, message, retryable);
  }
}
