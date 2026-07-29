package com.fxplatform.tradinglab.report;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Streams the deterministic JSON form through one multi-pattern matcher. It never materializes the
 * canonical trace and its matcher state deliberately spans string escapes and JSON tokens.
 */
final class TradingLabCanonicalCanaryScanner {

  private static final byte[] JSON_NULL = "null".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] JSON_TRUE = "true".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] JSON_FALSE = "false".getBytes(StandardCharsets.US_ASCII);
  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private final BytePatternMatcher matcher;
  private int matcherState;
  private long remainingBytes;
  private final int maxCanonicalBytes;

  TradingLabCanonicalCanaryScanner(List<String> secrets, int maxCanonicalBytes) {
    this(canaryMatcher(secrets), maxCanonicalBytes);
  }

  TradingLabCanonicalCanaryScanner(
      BytePatternMatcher matcher,
      int maxCanonicalBytes
  ) {
    if (maxCanonicalBytes < 1) {
      throw unsafe();
    }
    this.matcher = matcher;
    remainingBytes = maxCanonicalBytes;
    this.maxCanonicalBytes = maxCanonicalBytes;
  }

  static BytePatternMatcher canaryMatcher(Iterable<String> secrets) {
    if (secrets == null) {
      throw unsafe();
    }
    BytePatternMatcherBuilder builder = new BytePatternMatcherBuilder();
    for (String secret : secrets) {
      if (secret == null || secret.isEmpty()) {
        continue;
      }
      builder.addUtf8(secret);
      builder.add(escapedBytes(secret));
    }
    return builder.build();
  }

  static BytePatternMatcher rawMatcher(Iterable<String> secrets) {
    if (secrets == null) {
      throw unsafe();
    }
    BytePatternMatcherBuilder builder = new BytePatternMatcherBuilder();
    for (String secret : secrets) {
      if (secret != null && !secret.isEmpty()) {
        builder.addUtf8(secret);
      }
    }
    return builder.build();
  }

  static StreamingMatcher streamingMatcher(Iterable<String> secrets) {
    return new StreamingMatcher(canaryMatcher(secrets));
  }

  void scan(Object safeTrace) {
    emitValue(safeTrace, 0);
    accept((byte) '\n');
  }

  void scan(byte[] canonical) {
    accept(canonical);
  }

  private void emitValue(Object value, int depth) {
    if (depth > TradingLabTraceBudget.MAX_DEPTH) {
      throw unsafe();
    }
    if (value == null) {
      accept(JSON_NULL);
      return;
    }
    if (value instanceof String text) {
      emitString(text);
      return;
    }
    if (value instanceof Boolean booleanValue) {
      accept(booleanValue ? JSON_TRUE : JSON_FALSE);
      return;
    }
    if (isNumber(value)) {
      TradingLabCanonicalNumberWriter.write(
          value, maxCanonicalBytes, next -> accept((byte) next));
      return;
    }
    if (value instanceof Map<?, ?> map) {
      emitMap(map, depth);
      return;
    }
    if (value instanceof List<?> list) {
      accept((byte) '[');
      boolean first = true;
      for (Object child : list) {
        if (!first) {
          accept((byte) ',');
        }
        first = false;
        emitValue(child, depth + 1);
      }
      accept((byte) ']');
      return;
    }
    throw unsafe();
  }

  private void emitMap(Map<?, ?> map, int depth) {
    List<String> keys = new ArrayList<>();
    for (Object key : map.keySet()) {
      if (!(key instanceof String textKey)) {
        throw unsafe();
      }
      keys.add(textKey);
    }
    Collections.sort(keys);
    accept((byte) '{');
    boolean first = true;
    for (String key : keys) {
      if (!first) {
        accept((byte) ',');
      }
      first = false;
      emitString(key);
      accept((byte) ':');
      emitValue(map.get(key), depth + 1);
    }
    accept((byte) '}');
  }

  private void emitString(String value) {
    accept((byte) '"');
    writeEscaped(value, this::accept);
    accept((byte) '"');
  }

  private void accept(byte[] value) {
    for (byte next : value) {
      accept(next);
    }
  }

  private void accept(byte value) {
    if (--remainingBytes < 0L) {
      throw unsafe();
    }
    matcherState = matcher.nextState(matcherState, value);
    if (matcher.isTerminal(matcherState)) {
      throw unsafe();
    }
  }

  static byte[] escapedBytes(String value) {
    ByteArrayOutputStream output = new ByteArrayOutputStream(
        Math.min(1024, Math.max(16, value.length())));
    writeEscaped(value, output::write);
    return output.toByteArray();
  }

  private static void writeEscaped(String value, ByteSink sink) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      switch (current) {
        case '"' -> writeAscii("\\\"", sink);
        case '\\' -> writeAscii("\\\\", sink);
        case '\b' -> writeAscii("\\b", sink);
        case '\f' -> writeAscii("\\f", sink);
        case '\n' -> writeAscii("\\n", sink);
        case '\r' -> writeAscii("\\r", sink);
        case '\t' -> writeAscii("\\t", sink);
        default -> {
          if (current < 0x20) {
            sink.accept((byte) '\\');
            sink.accept((byte) 'u');
            sink.accept((byte) '0');
            sink.accept((byte) '0');
            sink.accept((byte) HEX[(current >>> 4) & 0x0f]);
            sink.accept((byte) HEX[current & 0x0f]);
          } else if (Character.isHighSurrogate(current)) {
            if (index + 1 >= value.length()
                || !Character.isLowSurrogate(value.charAt(index + 1))) {
              throw unsafe();
            }
            writeCodePoint(Character.toCodePoint(current, value.charAt(++index)), sink);
          } else if (Character.isLowSurrogate(current)) {
            throw unsafe();
          } else {
            writeCodePoint(current, sink);
          }
        }
      }
    }
  }

  private static void writeCodePoint(int codePoint, ByteSink sink) {
    if (codePoint <= 0x7f) {
      sink.accept((byte) codePoint);
    } else if (codePoint <= 0x7ff) {
      sink.accept((byte) (0xc0 | (codePoint >>> 6)));
      sink.accept((byte) (0x80 | (codePoint & 0x3f)));
    } else if (codePoint <= 0xffff) {
      sink.accept((byte) (0xe0 | (codePoint >>> 12)));
      sink.accept((byte) (0x80 | ((codePoint >>> 6) & 0x3f)));
      sink.accept((byte) (0x80 | (codePoint & 0x3f)));
    } else {
      sink.accept((byte) (0xf0 | (codePoint >>> 18)));
      sink.accept((byte) (0x80 | ((codePoint >>> 12) & 0x3f)));
      sink.accept((byte) (0x80 | ((codePoint >>> 6) & 0x3f)));
      sink.accept((byte) (0x80 | (codePoint & 0x3f)));
    }
  }

  private static void writeAscii(String value, ByteSink sink) {
    for (int index = 0; index < value.length(); index++) {
      sink.accept((byte) value.charAt(index));
    }
  }

  private static boolean isNumber(Object value) {
    return value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Float
        || value instanceof Double
        || value instanceof BigInteger
        || value instanceof BigDecimal;
  }

  private static IllegalArgumentException unsafe() {
    return new IllegalArgumentException("Unsafe Trading Lab HTTP trace value");
  }

  @FunctionalInterface
  private interface ByteSink {
    void accept(byte value);
  }

  static final class StreamingMatcher {
    private final BytePatternMatcher matcher;
    private int state;

    private StreamingMatcher(BytePatternMatcher matcher) {
      this.matcher = matcher;
    }

    void accept(int value) {
      state = matcher.nextState(state, (byte) value);
      if (matcher.isTerminal(state)) {
        throw new CanaryDetectedException();
      }
    }

    void accept(byte[] value, int offset, int length) {
      Objects.checkFromIndexSize(offset, length, value.length);
      int limit = offset + length;
      for (int index = offset; index < limit; index++) {
        accept(value[index]);
      }
    }
  }

  static final class CanaryDetectedException extends IllegalArgumentException {
    CanaryDetectedException() {
      super("Unsafe Trading Lab report value");
    }
  }

  /** Immutable Aho-Corasick automaton backed only by final primitive arrays. */
  static final class BytePatternMatcher {
    private final int[] rootTargetBySymbol;
    private final int[] edgeOffset;
    private final byte[] edgeSymbol;
    private final int[] edgeTarget;
    private final int[] failure;
    private final boolean[] terminal;

    private BytePatternMatcher(
        int[] rootTargetBySymbol,
        int[] edgeOffset,
        byte[] edgeSymbol,
        int[] edgeTarget,
        int[] failure,
        boolean[] terminal
    ) {
      this.rootTargetBySymbol = rootTargetBySymbol;
      this.edgeOffset = edgeOffset;
      this.edgeSymbol = edgeSymbol;
      this.edgeTarget = edgeTarget;
      this.failure = failure;
      this.terminal = terminal;
    }

    boolean contains(byte[] value) {
      int state = 0;
      for (byte symbol : value) {
        state = nextState(state, symbol);
        if (isTerminal(state)) {
          return true;
        }
      }
      return false;
    }

    boolean containsUtf8(String value) {
      int state = 0;
      for (int index = 0; index < value.length(); index++) {
        char current = value.charAt(index);
        int codePoint;
        if (Character.isHighSurrogate(current)) {
          if (index + 1 >= value.length()
              || !Character.isLowSurrogate(value.charAt(index + 1))) {
            throw unsafe();
          }
          codePoint = Character.toCodePoint(current, value.charAt(++index));
        } else if (Character.isLowSurrogate(current)) {
          throw unsafe();
        } else {
          codePoint = current;
        }

        if (codePoint <= 0x7f) {
          state = nextState(state, (byte) codePoint);
          if (isTerminal(state)) {
            return true;
          }
        } else if (codePoint <= 0x7ff) {
          state = nextState(state, (byte) (0xc0 | (codePoint >>> 6)));
          if (isTerminal(state)) {
            return true;
          }
          state = nextState(state, (byte) (0x80 | (codePoint & 0x3f)));
          if (isTerminal(state)) {
            return true;
          }
        } else if (codePoint <= 0xffff) {
          state = nextState(state, (byte) (0xe0 | (codePoint >>> 12)));
          if (isTerminal(state)) {
            return true;
          }
          state = nextState(state, (byte) (0x80 | ((codePoint >>> 6) & 0x3f)));
          if (isTerminal(state)) {
            return true;
          }
          state = nextState(state, (byte) (0x80 | (codePoint & 0x3f)));
          if (isTerminal(state)) {
            return true;
          }
        } else {
          state = nextState(state, (byte) (0xf0 | (codePoint >>> 18)));
          if (isTerminal(state)) {
            return true;
          }
          state = nextState(state, (byte) (0x80 | ((codePoint >>> 12) & 0x3f)));
          if (isTerminal(state)) {
            return true;
          }
          state = nextState(state, (byte) (0x80 | ((codePoint >>> 6) & 0x3f)));
          if (isTerminal(state)) {
            return true;
          }
          state = nextState(state, (byte) (0x80 | (codePoint & 0x3f)));
          if (isTerminal(state)) {
            return true;
          }
        }
      }
      return false;
    }

    int nextState(int state, byte symbol) {
      int target = directTarget(state, symbol);
      while (state != 0 && target == 0) {
        state = failure[state];
        target = directTarget(state, symbol);
      }
      return target;
    }

    boolean isTerminal(int state) {
      return terminal[state];
    }

    private int directTarget(int node, byte symbol) {
      return TradingLabCanonicalCanaryScanner.directTarget(
          rootTargetBySymbol, edgeOffset, edgeSymbol, edgeTarget, node, symbol);
    }
  }

  /** Mutable construction state that is discarded after the immutable matcher is frozen. */
  private static final class BytePatternMatcherBuilder {
    private final int[] rootTargetBySymbol = new int[256];
    private int[] firstEdge = filled(16, -1);
    private boolean[] terminal = new boolean[16];
    private byte[] edgeSymbol = new byte[16];
    private int[] edgeTarget = new int[16];
    private int[] edgeNext = new int[16];
    private int nodeCount = 1;
    private int edgeCount;

    private void add(byte[] pattern) {
      if (pattern.length == 0) {
        return;
      }
      int node = 0;
      for (byte symbol : pattern) {
        node = append(node, symbol);
      }
      terminal[node] = true;
    }

    private void addUtf8(String pattern) {
      if (pattern.isEmpty()) {
        return;
      }
      int node = 0;
      for (int index = 0; index < pattern.length(); index++) {
        char current = pattern.charAt(index);
        int codePoint;
        if (Character.isHighSurrogate(current)) {
          if (index + 1 >= pattern.length()
              || !Character.isLowSurrogate(pattern.charAt(index + 1))) {
            throw unsafe();
          }
          codePoint = Character.toCodePoint(current, pattern.charAt(++index));
        } else if (Character.isLowSurrogate(current)) {
          throw unsafe();
        } else {
          codePoint = current;
        }
        node = appendCodePoint(node, codePoint);
      }
      terminal[node] = true;
    }

    private int appendCodePoint(int node, int codePoint) {
      if (codePoint <= 0x7f) {
        return append(node, (byte) codePoint);
      }
      if (codePoint <= 0x7ff) {
        node = append(node, (byte) (0xc0 | (codePoint >>> 6)));
        return append(node, (byte) (0x80 | (codePoint & 0x3f)));
      }
      if (codePoint <= 0xffff) {
        node = append(node, (byte) (0xe0 | (codePoint >>> 12)));
        node = append(node, (byte) (0x80 | ((codePoint >>> 6) & 0x3f)));
        return append(node, (byte) (0x80 | (codePoint & 0x3f)));
      }
      node = append(node, (byte) (0xf0 | (codePoint >>> 18)));
      node = append(node, (byte) (0x80 | ((codePoint >>> 12) & 0x3f)));
      node = append(node, (byte) (0x80 | ((codePoint >>> 6) & 0x3f)));
      return append(node, (byte) (0x80 | (codePoint & 0x3f)));
    }

    private int append(int node, byte symbol) {
      int edge = findEdge(node, symbol);
      if (edge >= 0) {
        return edgeTarget[edge];
      }
      int child = newNode();
      newEdge(node, symbol, child);
      return child;
    }

    private BytePatternMatcher build() {
      int[] frozenRootTargets = Arrays.copyOf(rootTargetBySymbol, 256);
      int[] frozenEdgeOffset = new int[nodeCount + 1];
      int frozenEdgeCount = 0;
      for (int node = 0; node < nodeCount; node++) {
        frozenEdgeOffset[node] = frozenEdgeCount;
        if (node != 0) {
          for (int edge = firstEdge[node]; edge >= 0; edge = edgeNext[edge]) {
            frozenEdgeCount++;
          }
        }
      }
      frozenEdgeOffset[nodeCount] = frozenEdgeCount;

      byte[] frozenEdgeSymbols = new byte[frozenEdgeCount];
      int[] frozenEdgeTargets = new int[frozenEdgeCount];
      for (int node = 1; node < nodeCount; node++) {
        int index = frozenEdgeOffset[node];
        for (int edge = firstEdge[node]; edge >= 0; edge = edgeNext[edge]) {
          frozenEdgeSymbols[index] = edgeSymbol[edge];
          frozenEdgeTargets[index] = edgeTarget[edge];
          index++;
        }
        sortUnsigned(
            frozenEdgeSymbols,
            frozenEdgeTargets,
            frozenEdgeOffset[node],
            frozenEdgeOffset[node + 1]);
      }

      int[] frozenFailure = new int[nodeCount];
      boolean[] frozenTerminal = Arrays.copyOf(terminal, nodeCount);
      int[] queue = new int[nodeCount];
      int head = 0;
      int tail = 0;
      for (int target : frozenRootTargets) {
        if (target != 0) {
          queue[tail++] = target;
        }
      }
      while (head < tail) {
        int node = queue[head++];
        for (int edge = frozenEdgeOffset[node]; edge < frozenEdgeOffset[node + 1]; edge++) {
          byte symbol = frozenEdgeSymbols[edge];
          int child = frozenEdgeTargets[edge];
          int fallback = frozenFailure[node];
          int target = directTarget(
              frozenRootTargets,
              frozenEdgeOffset,
              frozenEdgeSymbols,
              frozenEdgeTargets,
              fallback,
              symbol);
          while (fallback != 0 && target == 0) {
            fallback = frozenFailure[fallback];
            target = directTarget(
                frozenRootTargets,
                frozenEdgeOffset,
                frozenEdgeSymbols,
                frozenEdgeTargets,
                fallback,
                symbol);
          }
          frozenFailure[child] = target;
          frozenTerminal[child] |= frozenTerminal[target];
          queue[tail++] = child;
        }
      }
      return new BytePatternMatcher(
          frozenRootTargets,
          frozenEdgeOffset,
          frozenEdgeSymbols,
          frozenEdgeTargets,
          frozenFailure,
          frozenTerminal);
    }

    private int findEdge(int node, byte symbol) {
      if (node == 0) {
        int target = rootTargetBySymbol[symbol & 0xff];
        if (target == 0) {
          return -1;
        }
        for (int edge = firstEdge[0]; edge >= 0; edge = edgeNext[edge]) {
          if (edgeTarget[edge] == target) {
            return edge;
          }
        }
        throw new IllegalStateException("Trading Lab canary matcher root is corrupt");
      }
      for (int edge = firstEdge[node]; edge >= 0; edge = edgeNext[edge]) {
        if (edgeSymbol[edge] == symbol) {
          return edge;
        }
      }
      return -1;
    }

    private int newNode() {
      ensureNodeCapacity(nodeCount + 1);
      firstEdge[nodeCount] = -1;
      return nodeCount++;
    }

    private void newEdge(int node, byte symbol, int target) {
      ensureEdgeCapacity(edgeCount + 1);
      int edge = edgeCount++;
      edgeSymbol[edge] = symbol;
      edgeTarget[edge] = target;
      edgeNext[edge] = firstEdge[node];
      firstEdge[node] = edge;
      if (node == 0) {
        rootTargetBySymbol[symbol & 0xff] = target;
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
      terminal = Arrays.copyOf(terminal, next);
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
  }

  private static int directTarget(
      int[] rootTargetBySymbol,
      int[] edgeOffset,
      byte[] edgeSymbol,
      int[] edgeTarget,
      int node,
      byte symbol
  ) {
    if (node == 0) {
      return rootTargetBySymbol[symbol & 0xff];
    }
    int searched = symbol & 0xff;
    int low = edgeOffset[node];
    int high = edgeOffset[node + 1] - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      int current = edgeSymbol[middle] & 0xff;
      if (current < searched) {
        low = middle + 1;
      } else if (current > searched) {
        high = middle - 1;
      } else {
        return edgeTarget[middle];
      }
    }
    return 0;
  }

  private static void sortUnsigned(
      byte[] symbols,
      int[] targets,
      int from,
      int to
  ) {
    for (int index = from + 1; index < to; index++) {
      byte symbol = symbols[index];
      int target = targets[index];
      int insertion = index - 1;
      while (insertion >= from
          && (symbols[insertion] & 0xff) > (symbol & 0xff)) {
        symbols[insertion + 1] = symbols[insertion];
        targets[insertion + 1] = targets[insertion];
        insertion--;
      }
      symbols[insertion + 1] = symbol;
      targets[insertion + 1] = target;
    }
  }

  private static int[] filled(int size, int value) {
    int[] result = new int[size];
    Arrays.fill(result, value);
    return result;
  }
}
