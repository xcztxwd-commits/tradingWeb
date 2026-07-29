package com.fxplatform.tradinglab.evidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record TradingLabCoordinatorEvidence(
    UUID id,
    UUID runId,
    long sequence,
    String eventType,
    Instant virtualTime,
    Instant realTime,
    String correlationId,
    Map<String, Object> payload
) {

  public TradingLabCoordinatorEvidence {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(realTime, "realTime");
    payload = Map.copyOf(payload == null ? Map.of() : payload);
    if (sequence < 0L || eventType.isBlank()) {
      throw new IllegalArgumentException("Invalid Trading Lab evidence identity");
    }
  }

  public Map<String, Object> toLifecycleValue() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("sequence", sequence);
    value.put("eventType", eventType);
    value.put("virtualTime", virtualTime == null ? null : virtualTime.toString());
    value.put("realTime", realTime.toString());
    value.put("correlationId", correlationId);
    value.put("payload", payload);
    return value;
  }

  public Long validationSequence() {
    Object value = payload.get("validationSequence");
    return value instanceof Number number ? number.longValue() : null;
  }
}
