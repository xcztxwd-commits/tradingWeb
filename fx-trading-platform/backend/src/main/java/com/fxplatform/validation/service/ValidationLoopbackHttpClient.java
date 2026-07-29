package com.fxplatform.validation.service;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public interface ValidationLoopbackHttpClient {

  URI LOOPBACK_BASE_URI = URI.create("http://127.0.0.1:8080");
  int MAX_HTTP_HOPS = 512;
  int MAX_HTTP_TRACE_NODES = 65_536;
  int MAX_HTTP_TRACE_DEPTH = 64;
  int MAX_HTTP_TRACE_TEXT_CHARS = 1_048_576;
  Set<String> SAFE_TRACE_KEYS = Set.of(
      "url",
      "queryParameters",
      "requestHeaders",
      "requestContentType",
      "requestBody",
      "responseHeaders",
      "responseContentType",
      "responseBody",
      "exception",
      "authentication");

  HttpResult execute(Command request);

  LookupResult lookup(Command request);

  /** Rebuilds process-local state for an already completed durable operation. */
  HttpResult rehydrate(Command request);

  enum Operation {
    REGISTER,
    SEED_ACCOUNT,
    CONFIGURE_ACCOUNT,
    START_MARKET_PATH,
    SYSTEM_STEP,
    PUBLIC_ACTION,
    QUERY_STATE
  }

  record Command(
      Operation operation,
      UUID runId,
      long generation,
      long tickSequence,
      String idempotencyKey,
      String requestFingerprint,
      Object body
  ) {

    public Command {
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(idempotencyKey, "idempotencyKey");
      Objects.requireNonNull(requestFingerprint, "requestFingerprint");
      if (generation < 0L || tickSequence < 0L) {
        throw new IllegalArgumentException("generation and tickSequence must be non-negative");
      }
      if (idempotencyKey.isBlank() || idempotencyKey.length() > 200
          || requestFingerprint.isBlank() || requestFingerprint.length() > 160) {
        throw new IllegalArgumentException("idempotencyKey and requestFingerprint are required");
      }
    }
  }

  record HttpHop(
      String method,
      String path,
      int status,
      String correlationId,
      long durationMillis,
      Map<String, Object> trace
  ) {

    public HttpHop {
      if (!Set.of("GET", "POST", "PATCH", "DELETE").contains(method)) {
        throw new IllegalArgumentException("HTTP hop method is invalid");
      }
      if (path == null
          || !path.startsWith("/")
          || path.length() > 16_384
          || path.contains("?")
          || path.contains("#")
          || path.contains("://")
          || path.contains("\\")) {
        throw new IllegalArgumentException("HTTP hop path is invalid");
      }
      if (status < 100 || status > 599) {
        throw new IllegalArgumentException("HTTP hop status is invalid");
      }
      if (correlationId == null
          || correlationId.isBlank()
          || correlationId.length() > 512) {
        throw new IllegalArgumentException("HTTP hop correlation is invalid");
      }
      if (durationMillis < 0L || durationMillis > 120_000L) {
        throw new IllegalArgumentException("HTTP hop duration is invalid");
      }
      if (trace == null || !SAFE_TRACE_KEYS.equals(trace.keySet())) {
        throw new IllegalArgumentException("HTTP hop trace shape is invalid");
      }
      trace = freezeTraceMap(trace);
    }
  }

  record HttpResult(
      int status,
      String correlationId,
      Map<String, Object> body,
      List<HttpHop> hops
  ) {

    public HttpResult {
      if (status < 100 || status > 599) {
        throw new IllegalArgumentException("status must be a valid HTTP status");
      }
      body = Collections.unmodifiableMap(new LinkedHashMap<>(
          body == null ? Map.of() : body));
      if (hops == null || hops.size() > MAX_HTTP_HOPS || hops.stream().anyMatch(Objects::isNull)) {
        throw new IllegalArgumentException("HTTP hop evidence exceeds its fixed bound");
      }
      hops = List.copyOf(hops);
    }

    public HttpResult(int status, String correlationId, Map<String, Object> body) {
      this(status, correlationId, body, List.of());
    }
  }

  enum LookupStatus {
    FOUND,
    DEFINITELY_ABSENT,
    UNKNOWN
  }

  record LookupResult(LookupStatus status, HttpResult result) {

    public LookupResult {
      Objects.requireNonNull(status, "status");
      if (status == LookupStatus.FOUND && result == null) {
        throw new IllegalArgumentException("FOUND lookup requires a result");
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> freezeTraceMap(Map<?, ?> source) {
    int[] nodes = {0};
    return (Map<String, Object>) freezeTraceValue(source, 0, nodes);
  }

  private static Object freezeTraceValue(Object value, int depth, int[] nodes) {
    if (depth > MAX_HTTP_TRACE_DEPTH || ++nodes[0] > MAX_HTTP_TRACE_NODES) {
      throw new IllegalArgumentException("HTTP hop trace exceeds its fixed bound");
    }
    if (value == null
        || value instanceof Boolean
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Float
        || value instanceof Double
        || value instanceof BigInteger
        || value instanceof BigDecimal) {
      return value;
    }
    if (value instanceof String text) {
      if (text.length() > MAX_HTTP_TRACE_TEXT_CHARS) {
        throw new IllegalArgumentException("HTTP hop trace exceeds its fixed bound");
      }
      return text;
    }
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
      map.forEach((key, child) -> {
        if (!(key instanceof String text)
            || text.length() > 512
            || copy.containsKey(text)) {
          throw new IllegalArgumentException("HTTP hop trace map is invalid");
        }
        copy.put(text, freezeTraceValue(child, depth + 1, nodes));
      });
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> list) {
      ArrayList<Object> copy = new ArrayList<>(list.size());
      list.forEach(child -> copy.add(freezeTraceValue(child, depth + 1, nodes)));
      return Collections.unmodifiableList(copy);
    }
    if (value.getClass().isArray()) {
      ArrayList<Object> copy = new ArrayList<>(Array.getLength(value));
      for (int index = 0; index < Array.getLength(value); index++) {
        copy.add(freezeTraceValue(Array.get(value, index), depth + 1, nodes));
      }
      return Collections.unmodifiableList(copy);
    }
    throw new IllegalArgumentException("HTTP hop trace value is invalid");
  }
}
