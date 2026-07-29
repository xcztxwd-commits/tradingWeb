package com.fxplatform.tradinglab.application;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class TradingLabCredentialSanitizer {

  private static final int MAX_DEPTH = 64;
  private static final String REDACTION = "[REDACTED]";
  private static final List<String> SENSITIVE_KEY_FRAGMENTS = List.of(
      "authorization",
      "cookie",
      "password",
      "token",
      "apikey",
      "secret",
      "accesskey",
      "authcode",
      "credential",
      "privatekey",
      "encryptionkey");

  public Map<String, Object> sanitize(Map<String, Object> details) {
    if (details == null) {
      return new LinkedHashMap<>();
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> sanitized = (Map<String, Object>) sanitizeJsonValue(details);
    return sanitized;
  }

  /**
   * Sanitizes an untrusted value without invoking bean getters, custom serializers, or toString.
   */
  public Object sanitizeJsonValue(Object value) {
    return sanitizeJsonValue(value, ignored -> { });
  }

  /**
   * Sanitizes an untrusted value and reports string values removed beneath sensitive keys.
   */
  public Object sanitizeJsonValue(Object value, Consumer<String> removedSecretSink) {
    Objects.requireNonNull(removedSecretSink, "removedSecretSink");
    return sanitizeValue(value, new IdentityHashMap<>(), 0, removedSecretSink);
  }

  /**
   * Replaces known secret values in every string value and object key of a JSON data model.
   */
  public Object redactKnownSecrets(Object value, Collection<String> knownSecrets) {
    Objects.requireNonNull(knownSecrets, "knownSecrets");
    List<String> secrets = knownSecrets.stream()
        .filter(Objects::nonNull)
        .filter(secret -> !secret.isEmpty())
        .distinct()
        .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo))
        .toList();
    return redactValue(value, secrets, new IdentityHashMap<>(), 0);
  }

  /**
   * Applies the frozen conservative credential-key policy.
   */
  public boolean isSensitiveKey(String key) {
    if (key == null) {
      throw invalidDetails();
    }
    String normalized = Normalizer.normalize(key, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT);
    StringBuilder asciiKey = new StringBuilder(normalized.length());
    normalized.codePoints().forEach(codePoint -> {
      if ((codePoint >= 'a' && codePoint <= 'z')
          || (codePoint >= '0' && codePoint <= '9')) {
        asciiKey.appendCodePoint(codePoint);
      } else if (Character.isLetterOrDigit(codePoint)) {
        throw invalidDetails();
      }
    });
    return SENSITIVE_KEY_FRAGMENTS.stream()
        .anyMatch(fragment -> asciiKey.indexOf(fragment) >= 0);
  }

  private Object sanitizeValue(
      Object value,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth,
      Consumer<String> removedSecretSink
  ) {
    checkDepth(depth);
    if (value == null || value instanceof String || value instanceof Boolean) {
      return value;
    }
    if (isJsonNumber(value)) {
      return value;
    }
    if (value instanceof Map<?, ?> map) {
      return sanitizeMap(map, ancestors, depth, removedSecretSink);
    }
    if (value instanceof List<?> list) {
      return sanitizeList(list, ancestors, depth, removedSecretSink);
    }
    if (value.getClass().isArray()) {
      return sanitizeArray(value, ancestors, depth, removedSecretSink);
    }
    throw invalidDetails();
  }

  private Map<String, Object> sanitizeMap(
      Map<?, ?> source,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth,
      Consumer<String> removedSecretSink
  ) {
    enter(source, ancestors);
    try {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : source.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw invalidDetails();
        }
        if (isSensitiveKey(key)) {
          collectSensitiveValue(
              entry.getValue(), ancestors, depth + 1, removedSecretSink);
        } else {
          copy.put(key, sanitizeValue(
              entry.getValue(), ancestors, depth + 1, removedSecretSink));
        }
      }
      return copy;
    } finally {
      ancestors.remove(source);
    }
  }

  private List<Object> sanitizeList(
      List<?> source,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth,
      Consumer<String> removedSecretSink
  ) {
    enter(source, ancestors);
    try {
      List<Object> copy = new ArrayList<>(source.size());
      for (Object value : source) {
        copy.add(sanitizeValue(value, ancestors, depth + 1, removedSecretSink));
      }
      return copy;
    } finally {
      ancestors.remove(source);
    }
  }

  private List<Object> sanitizeArray(
      Object source,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth,
      Consumer<String> removedSecretSink
  ) {
    enter(source, ancestors);
    try {
      int length = Array.getLength(source);
      List<Object> copy = new ArrayList<>(length);
      for (int index = 0; index < length; index++) {
        copy.add(sanitizeValue(
            Array.get(source, index), ancestors, depth + 1, removedSecretSink));
      }
      return copy;
    } finally {
      ancestors.remove(source);
    }
  }

  private void collectSensitiveValue(
      Object value,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth,
      Consumer<String> removedSecretSink
  ) {
    checkDepth(depth);
    if (value == null || value instanceof Boolean || isJsonNumber(value)) {
      return;
    }
    if (value instanceof String text) {
      if (!text.isEmpty()) {
        removedSecretSink.accept(text);
      }
      return;
    }
    if (value instanceof Map<?, ?> map) {
      enter(map, ancestors);
      try {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String key)) {
            throw invalidDetails();
          }
          isSensitiveKey(key);
          collectSensitiveValue(
              entry.getValue(), ancestors, depth + 1, removedSecretSink);
        }
      } finally {
        ancestors.remove(map);
      }
      return;
    }
    if (value instanceof List<?> list) {
      enter(list, ancestors);
      try {
        for (Object child : list) {
          collectSensitiveValue(child, ancestors, depth + 1, removedSecretSink);
        }
      } finally {
        ancestors.remove(list);
      }
      return;
    }
    if (value.getClass().isArray()) {
      enter(value, ancestors);
      try {
        for (int index = 0; index < Array.getLength(value); index++) {
          collectSensitiveValue(
              Array.get(value, index), ancestors, depth + 1, removedSecretSink);
        }
      } finally {
        ancestors.remove(value);
      }
      return;
    }
    throw invalidDetails();
  }

  private Object redactValue(
      Object value,
      List<String> secrets,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth
  ) {
    checkDepth(depth);
    if (value == null || value instanceof Boolean) {
      return value;
    }
    if (value instanceof String text) {
      return redactString(text, secrets);
    }
    if (isJsonNumber(value)) {
      return value;
    }
    if (value instanceof Map<?, ?> map) {
      enter(map, ancestors);
      try {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String key)) {
            throw invalidDetails();
          }
          String redactedKey = redactString(key, secrets);
          if (copy.containsKey(redactedKey)) {
            throw invalidDetails();
          }
          copy.put(redactedKey,
              redactValue(entry.getValue(), secrets, ancestors, depth + 1));
        }
        return copy;
      } finally {
        ancestors.remove(map);
      }
    }
    if (value instanceof List<?> list) {
      enter(list, ancestors);
      try {
        List<Object> copy = new ArrayList<>(list.size());
        for (Object child : list) {
          copy.add(redactValue(child, secrets, ancestors, depth + 1));
        }
        return copy;
      } finally {
        ancestors.remove(list);
      }
    }
    if (value.getClass().isArray()) {
      enter(value, ancestors);
      try {
        List<Object> copy = new ArrayList<>(Array.getLength(value));
        for (int index = 0; index < Array.getLength(value); index++) {
          copy.add(redactValue(
              Array.get(value, index), secrets, ancestors, depth + 1));
        }
        return copy;
      } finally {
        ancestors.remove(value);
      }
    }
    throw invalidDetails();
  }

  private String redactString(String value, List<String> secrets) {
    String redacted = value;
    for (String secret : secrets) {
      redacted = redacted.replace(secret, REDACTION);
    }
    return redacted;
  }

  private void enter(Object container, IdentityHashMap<Object, Boolean> ancestors) {
    if (ancestors.put(container, Boolean.TRUE) != null) {
      throw invalidDetails();
    }
  }

  private void checkDepth(int depth) {
    if (depth > MAX_DEPTH) {
      throw invalidDetails();
    }
  }

  private boolean isJsonNumber(Object value) {
    if (!(value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Float
        || value instanceof Double
        || value instanceof BigInteger
        || value instanceof BigDecimal)) {
      return false;
    }
    if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)) {
      throw invalidDetails();
    }
    if (value instanceof Float floatValue && !Float.isFinite(floatValue)) {
      throw invalidDetails();
    }
    return true;
  }

  private IllegalArgumentException invalidDetails() {
    return new IllegalArgumentException("Unsafe Trading Lab report value");
  }
}
