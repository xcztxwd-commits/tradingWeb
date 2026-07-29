package com.fxplatform.tradinglab.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.tradinglab.entity.TradingLabRunEventEntity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The only journal-to-browser projection. It deliberately reconstructs bounded DTOs instead of
 * returning a persistence entity, the Task 7 journal envelope, or arbitrary journal payload.
 */
@Component
final class TradingLabSseEventProjector {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
  private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
  private static final int MAX_PUBLIC_DEPTH = 32;
  private static final int MAX_PUBLIC_COLLECTION = 2_048;
  private static final int MAX_PUBLIC_STRING = 64 * 1024;

  private final ObjectMapper json;

  TradingLabSseEventProjector(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json");
  }

  PublicEvent project(TradingLabRunEventEntity event) {
    requireIdentity(event);
    Map<String, Object> payload = parse(event.getPayloadJson());
    return switch (event.getEventType()) {
      case "VALIDATION_HTTP_INTENT" -> httpIntent(event, payload);
      case "VALIDATION_HTTP_RESULT" -> httpResult(event, payload);
      case "VALIDATION_EVENT" -> validationEvent(event, payload);
      case "VALIDATION_HIGH_WATERMARK" -> highWatermark(event, payload);
      default -> internalEvent(event);
    };
  }

  private PublicEvent httpIntent(
      TradingLabRunEventEntity event,
      Map<String, Object> envelope
  ) {
    Map<String, Object> data = base(event);
    data.put("phase", "intent");
    Object publicRequest = publicValue(envelope.get("evidence"), 0);
    if (publicRequest instanceof Map<?, ?> request && !request.isEmpty()) {
      data.put("request", publicRequest);
    }
    return new PublicEvent("api-trace", immutable(data));
  }

  private PublicEvent httpResult(
      TradingLabRunEventEntity event,
      Map<String, Object> envelope
  ) {
    Map<String, Object> evidence = requiredMap(envelope.get("evidence"));
    Map<String, Object> data = base(event);
    data.put("phase", "result");
    copyScalar(evidence, data, "environment");
    copyScalar(evidence, data, "method");
    copyScalar(evidence, data, "url");
    copyScalar(evidence, data, "virtualTime");
    copyScalar(evidence, data, "realTime");
    copyScalar(evidence, data, "status");
    copyScalar(evidence, data, "duration");
    copyScalar(evidence, data, "traceId");
    copyScalar(evidence, data, "correlationId");
    copyPublic(evidence, data, "sanitizedRequest", "request");
    copyPublic(evidence, data, "sanitizedResponse", "response");
    copyPublic(evidence, data, "exception", "exception");
    return new PublicEvent("api-trace", immutable(data));
  }

  private PublicEvent validationEvent(
      TradingLabRunEventEntity event,
      Map<String, Object> envelope
  ) {
    Map<String, Object> evidence = requiredMap(envelope.get("evidence"));
    Object rawType = evidence.get("type");
    if (!(rawType instanceof String type) || type.isBlank()) {
      throw corrupt();
    }
    String publicName = switch (type) {
      case "RUN_ACCEPTED", "RUN_STATE_CHANGED" -> "state";
      case "MARKET_TICK" -> "tick";
      case "CHECKPOINT" -> "checkpoint";
      case "API_TRACE" -> "api-trace";
      case "RECOVERY_RECONCILIATION" -> "warning";
      default -> failureType(type) ? "error" : "progress";
    };

    Map<String, Object> data = base(event);
    Object validationSequence = envelope.get("validationSequence");
    if (validationSequence instanceof Number number) {
      data.put("validationSequence", number.longValue());
    }
    copyScalar(evidence, data, "virtualTime");
    copyScalar(evidence, data, "correlationId");
    Object projectedPayload = publicValue(evidence.get("payload"), 0);
    if (projectedPayload instanceof Map<?, ?> map && !map.isEmpty()) {
      data.put("payload", projectedPayload);
    }
    if ("warning".equals(publicName)) {
      data.put("kind", "recovery");
    } else if ("progress".equals(publicName)) {
      data.put("kind", "progress");
    } else if ("error".equals(publicName)) {
      data.put("kind", "failure");
    }
    return new PublicEvent(publicName, immutable(data));
  }

  private PublicEvent highWatermark(
      TradingLabRunEventEntity event,
      Map<String, Object> envelope
  ) {
    Map<String, Object> data = base(event);
    data.put("kind", "high-watermark");
    Object value = envelope.get("highWatermark");
    if (value instanceof Number number) {
      data.put("highWatermark", number.longValue());
    }
    return new PublicEvent("progress", immutable(data));
  }

  private PublicEvent internalEvent(TradingLabRunEventEntity event) {
    Map<String, Object> data = base(event);
    boolean failure = failureType(event.getEventType());
    data.put("kind", failure ? "failure" : "internal-progress");
    return new PublicEvent(failure ? "error" : "warning", immutable(data));
  }

  private static Map<String, Object> base(TradingLabRunEventEntity event) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (event.getVirtualTime() != null) {
      data.put("virtualTime", event.getVirtualTime().toString());
    }
    data.put("realTime", event.getRealTime().toString());
    if (event.getCorrelationId() != null) {
      data.put("correlationId", event.getCorrelationId());
    }
    return data;
  }

  private Map<String, Object> parse(String source) {
    if (source == null
        || source.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
      throw corrupt();
    }
    try {
      return json.readValue(source, MAP);
    } catch (JsonProcessingException exception) {
      throw corrupt();
    }
  }

  private static Object publicValue(Object value, int depth) {
    if (value == null
        || value instanceof Boolean
        || value instanceof Number) {
      return value;
    }
    if (value instanceof String text) {
      if (text.length() > MAX_PUBLIC_STRING) {
        throw corrupt();
      }
      return text;
    }
    if (depth >= MAX_PUBLIC_DEPTH) {
      throw corrupt();
    }
    if (value instanceof Map<?, ?> map) {
      if (map.size() > MAX_PUBLIC_COLLECTION) {
        throw corrupt();
      }
      Map<String, Object> projected = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key) || projected.containsKey(key)) {
          throw corrupt();
        }
        if (!forbiddenKey(key)) {
          projected.put(key, publicValue(entry.getValue(), depth + 1));
        }
      }
      return immutable(projected);
    }
    if (value instanceof List<?> list) {
      if (list.size() > MAX_PUBLIC_COLLECTION) {
        throw corrupt();
      }
      List<Object> projected = new ArrayList<>(list.size());
      for (Object child : list) {
        projected.add(publicValue(child, depth + 1));
      }
      return Collections.unmodifiableList(projected);
    }
    throw corrupt();
  }

  private static boolean forbiddenKey(String key) {
    String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return normalized.contains("fingerprint")
        || normalized.contains("durablekey")
        || normalized.contains("sourcekey")
        || normalized.contains("sourcekind")
        || normalized.contains("logicalkey")
        || normalized.contains("idempotency")
        || normalized.contains("secretregistry")
        || normalized.contains("internaltoken")
        || normalized.contains("authorization")
        || normalized.contains("credential")
        || normalized.contains("password")
        || normalized.contains("privatekey")
        || normalized.contains("apikey")
        || normalized.contains("cookie")
        || normalized.equals("token")
        || normalized.endsWith("accesstoken")
        || normalized.endsWith("refreshtoken")
        || (normalized.contains("raw") && normalized.contains("body"));
  }

  private static void copyScalar(
      Map<String, Object> source,
      Map<String, Object> target,
      String key
  ) {
    Object value = source.get(key);
    if (value == null || value instanceof String
        || value instanceof Number || value instanceof Boolean) {
      if (value != null) {
        target.put(key, publicValue(value, 0));
      }
      return;
    }
    throw corrupt();
  }

  private static void copyPublic(
      Map<String, Object> source,
      Map<String, Object> target,
      String sourceKey,
      String targetKey
  ) {
    Object value = source.get(sourceKey);
    if (value == null) {
      return;
    }
    Object projected = publicValue(value, 0);
    if (projected instanceof Map<?, ?> map && map.isEmpty()) {
      return;
    }
    target.put(targetKey, projected);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> requiredMap(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw corrupt();
    }
    for (Object key : map.keySet()) {
      if (!(key instanceof String)) {
        throw corrupt();
      }
    }
    return (Map<String, Object>) map;
  }

  private static boolean failureType(String value) {
    String upper = value.toUpperCase(Locale.ROOT);
    return upper.contains("FAIL") || upper.contains("ERROR") || upper.contains("FATAL");
  }

  private static void requireIdentity(TradingLabRunEventEntity event) {
    if (event == null
        || event.getRunId() == null
        || event.getSequence() == null
        || event.getSequence() < 0L
        || event.getEventType() == null
        || event.getEventType().isBlank()
        || event.getRealTime() == null) {
      throw corrupt();
    }
  }

  private static Map<String, Object> immutable(Map<String, Object> source) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }

  private static IllegalArgumentException corrupt() {
    return new IllegalArgumentException("Trading Lab SSE evidence is corrupt or unsafe");
  }

  record PublicEvent(String name, Map<String, Object> data) {

    PublicEvent {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Public SSE event name is required");
      }
      data = immutable(Objects.requireNonNull(data, "data"));
    }
  }
}
