package com.fxplatform.tradinglab.client;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ValidationRunEvent(
    UUID runId,
    long sequence,
    String durableKey,
    String fingerprint,
    String type,
    Instant virtualTime,
    String correlationId,
    Map<String, Object> payload
) {

  public ValidationRunEvent {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(durableKey, "durableKey");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(type, "type");
    if (sequence <= 0L
        || durableKey.isBlank()
        || durableKey.length() > 256
        || fingerprint.isBlank()
        || fingerprint.length() > 128
        || type.isBlank()
        || type.length() > 64
        || !type.matches("[A-Z0-9_]+")
        || (correlationId != null
            && (correlationId.isBlank() || correlationId.length() > 256))) {
      throw new IllegalArgumentException("Validation event identity is incomplete");
    }
    payload = ValidationClientSafeValues.freezeMap(payload);
  }

  public Map<String, Object> toSafeMap() {
    Map<String, Object> safe = new LinkedHashMap<>();
    safe.put("runId", runId.toString());
    safe.put("sequence", sequence);
    safe.put("durableKey", durableKey);
    safe.put("fingerprint", fingerprint);
    safe.put("type", type);
    safe.put("virtualTime", virtualTime == null ? null : virtualTime.toString());
    safe.put("correlationId", correlationId);
    safe.put("payload", payload);
    return java.util.Collections.unmodifiableMap(safe);
  }
}
