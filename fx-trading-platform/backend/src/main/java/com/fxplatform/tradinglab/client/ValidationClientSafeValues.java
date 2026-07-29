package com.fxplatform.tradinglab.client;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded, getter-free snapshots for values that cross the validation HTTP seam. */
final class ValidationClientSafeValues {

  private static final int MAX_DEPTH = 64;
  private static final int MAX_NODES = 200_000;
  private static final int MAX_TEXT_CHARS = 1_048_576;

  private ValidationClientSafeValues() {
  }

  static Map<String, Object> freezeMap(Map<?, ?> value) {
    Object frozen = freeze(value == null ? Map.of() : value);
    if (!(frozen instanceof Map<?, ?> map)) {
      throw unsafe();
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> typed = (Map<String, Object>) map;
    return typed;
  }

  static List<Map<String, Object>> freezeMapList(List<? extends Map<?, ?>> value) {
    List<? extends Map<?, ?>> source = value == null ? List.of() : value;
    Counter counter = new Counter();
    List<Map<String, Object>> copy = new ArrayList<>(source.size());
    for (Map<?, ?> child : source) {
      copy.add(freezeMap(child, counter, 1));
    }
    return List.copyOf(copy);
  }

  static Object freeze(Object value) {
    return freeze(value, new Counter(), 0);
  }

  private static Object freeze(Object value, Counter counter, int depth) {
    counter.consume(depth);
    if (value == null || value instanceof Boolean || value instanceof String) {
      if (value instanceof String text && text.length() > MAX_TEXT_CHARS) {
        throw unsafe();
      }
      return value;
    }
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger
        || value instanceof BigDecimal) {
      return value;
    }
    if (value instanceof Float number) {
      if (!Float.isFinite(number)) {
        throw unsafe();
      }
      return number;
    }
    if (value instanceof Double number) {
      if (!Double.isFinite(number)) {
        throw unsafe();
      }
      return number;
    }
    if (value instanceof UUID
        || value instanceof URI
        || value instanceof Instant
        || value instanceof Duration
        || value instanceof Enum<?>) {
      return value.toString();
    }
    if (value instanceof Map<?, ?> map) {
      return freezeMap(map, counter, depth + 1);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      for (Object child : list) {
        copy.add(freeze(child, counter, depth + 1));
      }
      return Collections.unmodifiableList(copy);
    }
    throw unsafe();
  }

  private static Map<String, Object> freezeMap(
      Map<?, ?> map,
      Counter counter,
      int depth
  ) {
    counter.consume(depth);
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)
          || key.isEmpty()
          || key.length() > MAX_TEXT_CHARS
          || copy.containsKey(key)) {
        throw unsafe();
      }
      copy.put(key, freeze(entry.getValue(), counter, depth + 1));
    }
    return Collections.unmodifiableMap(copy);
  }

  private static IllegalArgumentException unsafe() {
    return new IllegalArgumentException("Unsafe validation client value");
  }

  private static final class Counter {

    private int nodes;

    private void consume(int depth) {
      if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
        throw unsafe();
      }
    }
  }
}
