package com.fxplatform.tradinglab.report;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SafeTradingLabHttpTrace {

  private final String url;
  private final Map<String, Object> queryParameters;
  private final Map<String, Object> requestHeaders;
  private final String requestContentType;
  private final Object requestBody;
  private final Map<String, Object> responseHeaders;
  private final String responseContentType;
  private final Object responseBody;
  private final Map<String, Object> exception;
  private final TradingLabCredentialMetadata authentication;
  private final TradingLabTraceSecretRegistry secretRegistry;

  SafeTradingLabHttpTrace(
      String url,
      Map<String, Object> queryParameters,
      Map<String, Object> requestHeaders,
      String requestContentType,
      Object requestBody,
      Map<String, Object> responseHeaders,
      String responseContentType,
      Object responseBody,
      Map<String, Object> exception,
      TradingLabCredentialMetadata authentication,
      TradingLabTraceSecretRegistry secretRegistry
  ) {
    this.url = url;
    this.queryParameters = freezeMap(queryParameters);
    this.requestHeaders = freezeMap(requestHeaders);
    this.requestContentType = requestContentType;
    this.requestBody = freeze(requestBody);
    this.responseHeaders = freezeMap(responseHeaders);
    this.responseContentType = responseContentType;
    this.responseBody = freeze(responseBody);
    this.exception = exception == null ? null : freezeMap(exception);
    this.authentication = authentication;
    this.secretRegistry = Objects.requireNonNull(secretRegistry, "secretRegistry");
  }

  void registerSecrets(TradingLabReportSecretRegistry reportSecrets) {
    secretRegistry.mergeInto(Objects.requireNonNull(reportSecrets, "reportSecrets"));
  }

  void inheritSecretsFrom(SafeTradingLabHttpTrace source) {
    secretRegistry.inherit(Objects.requireNonNull(source, "source").secretRegistry);
  }

  void copySecretsTo(TradingLabTraceSecretRegistry target) {
    Objects.requireNonNull(target, "target").inherit(secretRegistry);
  }

  List<String> registeredSecrets() {
    return secretRegistry.snapshot();
  }

  public Map<String, Object> toSafeMap() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("url", url);
    value.put("queryParameters", queryParameters);
    value.put("requestHeaders", requestHeaders);
    value.put("requestContentType", requestContentType);
    value.put("requestBody", requestBody);
    value.put("responseHeaders", responseHeaders);
    value.put("responseContentType", responseContentType);
    value.put("responseBody", responseBody);
    value.put("exception", exception);
    value.put("authentication",
        authentication == null ? null : freeze(authentication.toSafeMap()));
    return Collections.unmodifiableMap(value);
  }

  /** Returns whether a candidate durable identifier contains a raw exchange secret. */
  public boolean containsRegisteredSecret(String value) {
    return value != null
        && TradingLabCanonicalCanaryScanner.canaryMatcher(secretRegistry.snapshot())
            .containsUtf8(value);
  }

  /** Fail-closed boundary used before any value derived from this exchange is journaled. */
  public void requireSafeEvidence(Object value) {
    new TradingLabCanonicalCanaryScanner(
        secretRegistry.snapshot(), TradingLabReportProperties.MAX_LOGICAL_VALUE_BYTES)
        .scan(value);
  }

  /** Scans a bounded serialized DTO before the caller may expose or journal it. */
  public void requireSafeJson(byte[] json) {
    Objects.requireNonNull(json, "json");
    if (json.length > TradingLabReportProperties.MAX_LOGICAL_VALUE_BYTES) {
      throw new IllegalArgumentException("Unsafe Trading Lab HTTP trace value");
    }
    new TradingLabCanonicalCanaryScanner(
        secretRegistry.snapshot(), TradingLabReportProperties.MAX_LOGICAL_VALUE_BYTES)
        .scan(json);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> freezeMap(Map<String, Object> value) {
    return (Map<String, Object>) freeze(value == null ? Map.of() : value);
  }

  private static Object freeze(Object value) {
    if (value == null
        || value instanceof String
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
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach((key, child) -> {
        if (!(key instanceof String textKey) || copy.containsKey(textKey)) {
          throw unsafeSafeValue();
        }
        copy.put(textKey, freeze(child));
      });
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      list.forEach(child -> copy.add(freeze(child)));
      return Collections.unmodifiableList(copy);
    }
    if (value.getClass().isArray()) {
      List<Object> copy = new ArrayList<>(Array.getLength(value));
      for (int index = 0; index < Array.getLength(value); index++) {
        copy.add(freeze(Array.get(value, index)));
      }
      return Collections.unmodifiableList(copy);
    }
    throw unsafeSafeValue();
  }

  private static IllegalArgumentException unsafeSafeValue() {
    return new IllegalArgumentException("Unsafe Trading Lab HTTP trace value");
  }
}
