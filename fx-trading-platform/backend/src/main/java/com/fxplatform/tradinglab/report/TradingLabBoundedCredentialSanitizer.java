package com.fxplatform.tradinglab.report;

import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Task3-only structural credential sanitizer. It never trusts caller collection size hints and
 * never expands a large string while redacting known secrets.
 */
final class TradingLabBoundedCredentialSanitizer {

  private static final int MAX_DEPTH = 64;
  private static final int MAX_KEY_CHARS = 16_384;
  private static final int MAX_SECRETS = 256;
  private static final int MAX_SECRET_CHARS = 65_536;
  private static final int MAX_PARTIAL_REDACTION_CHARS = 8_192;
  private static final String REDACTION = "[REDACTED]";

  private final TradingLabCredentialSanitizer keyPolicy;
  private final long maxNodes;

  TradingLabBoundedCredentialSanitizer(
      TradingLabCredentialSanitizer keyPolicy,
      int maxBytes
  ) {
    if (maxBytes < 1) {
      throw unsafe();
    }
    this.keyPolicy = Objects.requireNonNull(keyPolicy, "keyPolicy");
    this.maxNodes = Math.max(64L, Math.ceilDiv((long) maxBytes, 4L));
  }

  Object sanitizeAndRedact(
      Object value,
      Consumer<String> removedSecretSink,
      Supplier<? extends Iterable<String>> knownSecrets
  ) {
    Objects.requireNonNull(removedSecretSink, "removedSecretSink");
    Objects.requireNonNull(knownSecrets, "knownSecrets");
    Object sanitized = sanitizeValue(
        value, removedSecretSink, new IdentityHashMap<>(), 0, new NodeBudget(maxNodes));
    return redactKnownSecrets(sanitized, knownSecrets.get());
  }

  Object redactKnownSecrets(Object value, Iterable<String> knownSecrets) {
    SecretMatcher matcher = new SecretMatcher(knownSecrets);
    return redactValue(
        value, matcher, new IdentityHashMap<>(), 0, new NodeBudget(maxNodes));
  }

  private Object sanitizeValue(
      Object value,
      Consumer<String> removedSecretSink,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth,
      NodeBudget budget
  ) {
    checkDepth(depth);
    budget.consume();
    if (value == null || value instanceof String || value instanceof Boolean) {
      return value;
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
            throw unsafe();
          }
          boolean sensitive = isSensitiveKey(key);
          if (sensitive) {
            collectSensitiveValue(
                entry.getValue(), removedSecretSink, ancestors, depth + 1, budget);
          } else {
            if (copy.containsKey(key)) {
              throw unsafe();
            }
            copy.put(key, sanitizeValue(
                entry.getValue(), removedSecretSink, ancestors, depth + 1, budget));
          }
        }
        return copy;
      } finally {
        ancestors.remove(map);
      }
    }
    if (value instanceof List<?> list) {
      enter(list, ancestors);
      try {
        List<Object> copy = new ArrayList<>();
        for (Object child : list) {
          copy.add(sanitizeValue(
              child, removedSecretSink, ancestors, depth + 1, budget));
        }
        return copy;
      } finally {
        ancestors.remove(list);
      }
    }
    if (value.getClass().isArray()) {
      int length = Array.getLength(value);
      budget.requireCapacity(length);
      enter(value, ancestors);
      try {
        List<Object> copy = new ArrayList<>();
        for (int index = 0; index < length; index++) {
          copy.add(sanitizeValue(
              Array.get(value, index), removedSecretSink, ancestors, depth + 1, budget));
        }
        return copy;
      } finally {
        ancestors.remove(value);
      }
    }
    throw unsafe();
  }

  private void collectSensitiveValue(
      Object value,
      Consumer<String> removedSecretSink,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth,
      NodeBudget budget
  ) {
    checkDepth(depth);
    budget.consume();
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
            throw unsafe();
          }
          isSensitiveKey(key);
          collectSensitiveValue(
              entry.getValue(), removedSecretSink, ancestors, depth + 1, budget);
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
          collectSensitiveValue(
              child, removedSecretSink, ancestors, depth + 1, budget);
        }
      } finally {
        ancestors.remove(list);
      }
      return;
    }
    if (value.getClass().isArray()) {
      int length = Array.getLength(value);
      budget.requireCapacity(length);
      enter(value, ancestors);
      try {
        for (int index = 0; index < length; index++) {
          collectSensitiveValue(
              Array.get(value, index), removedSecretSink, ancestors, depth + 1, budget);
        }
      } finally {
        ancestors.remove(value);
      }
      return;
    }
    throw unsafe();
  }

  private Object redactValue(
      Object value,
      SecretMatcher matcher,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth,
      NodeBudget budget
  ) {
    checkDepth(depth);
    budget.consume();
    if (value == null || value instanceof Boolean) {
      return value;
    }
    if (value instanceof String text) {
      return matcher.redactValue(text);
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
            throw unsafe();
          }
          String redactedKey = matcher.redactKey(key);
          if (copy.containsKey(redactedKey)) {
            throw unsafe();
          }
          copy.put(redactedKey, redactValue(
              entry.getValue(), matcher, ancestors, depth + 1, budget));
        }
        return copy;
      } finally {
        ancestors.remove(map);
      }
    }
    if (value instanceof List<?> list) {
      enter(list, ancestors);
      try {
        List<Object> copy = new ArrayList<>();
        for (Object child : list) {
          copy.add(redactValue(child, matcher, ancestors, depth + 1, budget));
        }
        return copy;
      } finally {
        ancestors.remove(list);
      }
    }
    if (value.getClass().isArray()) {
      int length = Array.getLength(value);
      budget.requireCapacity(length);
      enter(value, ancestors);
      try {
        List<Object> copy = new ArrayList<>();
        for (int index = 0; index < length; index++) {
          copy.add(redactValue(
              Array.get(value, index), matcher, ancestors, depth + 1, budget));
        }
        return copy;
      } finally {
        ancestors.remove(value);
      }
    }
    throw unsafe();
  }

  private boolean isSensitiveKey(String key) {
    if (key.length() > MAX_KEY_CHARS) {
      throw unsafe();
    }
    return keyPolicy.isSensitiveKey(key);
  }

  private static boolean isJsonNumber(Object value) {
    Class<?> type = value.getClass();
    if (!(type == Byte.class
        || type == Short.class
        || type == Integer.class
        || type == Long.class
        || type == Float.class
        || type == Double.class
        || type == BigInteger.class
        || type == BigDecimal.class)) {
      return false;
    }
    if (value instanceof Double number && !Double.isFinite(number)) {
      throw unsafe();
    }
    if (value instanceof Float number && !Float.isFinite(number)) {
      throw unsafe();
    }
    return true;
  }

  private static void enter(
      Object value,
      IdentityHashMap<Object, Boolean> ancestors
  ) {
    if (ancestors.put(value, Boolean.TRUE) != null) {
      throw unsafe();
    }
  }

  private static void checkDepth(int depth) {
    if (depth > MAX_DEPTH) {
      throw unsafe();
    }
  }

  private static IllegalArgumentException unsafe() {
    return new IllegalArgumentException("Unsafe Trading Lab report value");
  }

  private static final class NodeBudget {
    private long remaining;

    private NodeBudget(long remaining) {
      this.remaining = remaining;
    }

    private void consume() {
      if (--remaining < 0L) {
        throw unsafe();
      }
    }

    private void requireCapacity(int count) {
      if (count < 0 || count > remaining) {
        throw unsafe();
      }
    }
  }

  /** UTF-16 Aho-Corasick matcher whose storage is bounded by the secret registry contract. */
  private static final class SecretMatcher {
    private static final int INDEXED_EDGE_THRESHOLD = 16;

    private final List<String> secrets = new ArrayList<>();
    private final int[] rootTarget = new int[Character.MAX_VALUE + 1];
    private int[] firstEdge = filled(16, -1);
    private int[] failure = new int[16];
    private boolean[] terminal = new boolean[16];
    private int[] edgeDegree = new int[16];
    @SuppressWarnings("unchecked")
    private Map<Character, Integer>[] indexedTarget =
        (Map<Character, Integer>[]) new Map<?, ?>[16];
    private char[] edgeSymbol = new char[16];
    private int[] edgeTarget = new int[16];
    private int[] edgeNext = new int[16];
    private int nodeCount = 1;
    private int edgeCount;
    private int minimumLength = Integer.MAX_VALUE;

    private SecretMatcher(Iterable<String> source) {
      if (source == null) {
        throw unsafe();
      }
      Set<String> unique = new LinkedHashSet<>();
      int visited = 0;
      long characters = 0L;
      for (String secret : source) {
        if (++visited > MAX_SECRETS) {
          throw unsafe();
        }
        if (secret == null || secret.isEmpty() || !unique.add(secret)) {
          continue;
        }
        characters += secret.length();
        if (characters > MAX_SECRET_CHARS) {
          throw unsafe();
        }
        secrets.add(secret);
        minimumLength = Math.min(minimumLength, secret.length());
        add(secret);
      }
      secrets.sort(
          Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo));
      build();
    }

    private String redactValue(String value) {
      if (!contains(value)) {
        return value;
      }
      if (value.length() > MAX_PARTIAL_REDACTION_CHARS
          || minimumLength < REDACTION.length()) {
        return REDACTION;
      }
      String redacted = value;
      for (String secret : secrets) {
        if (redacted.indexOf(secret) >= 0) {
          redacted = redacted.replace(secret, REDACTION);
        }
      }
      return redacted;
    }

    private String redactKey(String key) {
      return contains(key) ? REDACTION : key;
    }

    private boolean contains(String value) {
      if (secrets.isEmpty()) {
        return false;
      }
      int state = 0;
      for (int index = 0; index < value.length(); index++) {
        char symbol = value.charAt(index);
        int target = directTarget(state, symbol);
        while (state != 0 && target == 0) {
          state = failure[state];
          target = directTarget(state, symbol);
        }
        state = target;
        if (terminal[state]) {
          return true;
        }
      }
      return false;
    }

    private void add(String pattern) {
      int node = 0;
      for (int index = 0; index < pattern.length(); index++) {
        char symbol = pattern.charAt(index);
        int child = directTarget(node, symbol);
        if (child == 0) {
          child = newNode();
          newEdge(node, symbol, child);
        }
        node = child;
      }
      terminal[node] = true;
    }

    private void build() {
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      for (int edge = firstEdge[0]; edge >= 0; edge = edgeNext[edge]) {
        queue.add(edgeTarget[edge]);
      }
      while (!queue.isEmpty()) {
        int node = queue.remove();
        for (int edge = firstEdge[node]; edge >= 0; edge = edgeNext[edge]) {
          char symbol = edgeSymbol[edge];
          int child = edgeTarget[edge];
          int fallback = failure[node];
          int target = directTarget(fallback, symbol);
          while (fallback != 0 && target == 0) {
            fallback = failure[fallback];
            target = directTarget(fallback, symbol);
          }
          failure[child] = target;
          terminal[child] |= terminal[target];
          queue.add(child);
        }
      }
    }

    private int directTarget(int node, char symbol) {
      if (node == 0) {
        return rootTarget[symbol];
      }
      Map<Character, Integer> targets = indexedTarget[node];
      if (targets != null) {
        return targets.getOrDefault(symbol, 0);
      }
      for (int edge = firstEdge[node]; edge >= 0; edge = edgeNext[edge]) {
        if (edgeSymbol[edge] == symbol) {
          return edgeTarget[edge];
        }
      }
      return 0;
    }

    private int newNode() {
      ensureNodeCapacity(nodeCount + 1);
      firstEdge[nodeCount] = -1;
      return nodeCount++;
    }

    private void newEdge(int node, char symbol, int target) {
      ensureEdgeCapacity(edgeCount + 1);
      int edge = edgeCount++;
      edgeSymbol[edge] = symbol;
      edgeTarget[edge] = target;
      edgeNext[edge] = firstEdge[node];
      firstEdge[node] = edge;
      if (node == 0) {
        rootTarget[symbol] = target;
      } else if (++edgeDegree[node] == INDEXED_EDGE_THRESHOLD) {
        Map<Character, Integer> targets = new HashMap<>();
        for (int indexedEdge = edge;
            indexedEdge >= 0;
            indexedEdge = edgeNext[indexedEdge]) {
          targets.put(edgeSymbol[indexedEdge], edgeTarget[indexedEdge]);
        }
        indexedTarget[node] = targets;
      } else if (indexedTarget[node] != null) {
        indexedTarget[node].put(symbol, target);
      }
    }

    private void ensureNodeCapacity(int required) {
      if (required <= firstEdge.length) {
        return;
      }
      int oldLength = firstEdge.length;
      int next = Math.max(required, oldLength * 2);
      firstEdge = Arrays.copyOf(firstEdge, next);
      Arrays.fill(firstEdge, oldLength, next, -1);
      failure = Arrays.copyOf(failure, next);
      terminal = Arrays.copyOf(terminal, next);
      edgeDegree = Arrays.copyOf(edgeDegree, next);
      indexedTarget = Arrays.copyOf(indexedTarget, next);
    }

    private void ensureEdgeCapacity(int required) {
      if (required <= edgeSymbol.length) {
        return;
      }
      int next = Math.max(required, edgeSymbol.length * 2);
      edgeSymbol = Arrays.copyOf(edgeSymbol, next);
      edgeTarget = Arrays.copyOf(edgeTarget, next);
      edgeNext = Arrays.copyOf(edgeNext, next);
    }

    private static int[] filled(int length, int value) {
      int[] result = new int[length];
      Arrays.fill(result, value);
      return result;
    }
  }
}
