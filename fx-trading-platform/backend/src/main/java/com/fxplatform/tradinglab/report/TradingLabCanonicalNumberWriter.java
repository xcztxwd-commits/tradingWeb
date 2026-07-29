package com.fxplatform.tradinglab.report;

import java.math.BigDecimal;
import java.util.function.IntConsumer;

/** Emits a preflight-bounded deterministic JSON number without materializing plain decimals. */
final class TradingLabCanonicalNumberWriter {

  private TradingLabCanonicalNumberWriter() {
  }

  static void write(Object value, int maxBytes, IntConsumer sink) {
    TradingLabJsonNumberBounds.requireCanonicalLength(value, maxBytes);
    if (value instanceof BigDecimal decimal) {
      writeDecimal(decimal, sink);
      return;
    }
    // The bounds check precedes this conversion, including for attacker-supplied BigInteger.
    writeAscii(value.toString(), sink);
  }

  private static void writeDecimal(BigDecimal value, IntConsumer sink) {
    int sign = value.signum();
    int scale = value.scale();
    if (sign < 0) {
      sink.accept('-');
    }
    if (sign == 0) {
      sink.accept('0');
      if (scale > 0) {
        sink.accept('.');
        writeZeros(scale, sink);
      }
      return;
    }

    // The conservative preflight bounds this conversion before any magnitude copy/allocation.
    String digits = value.unscaledValue().toString();
    int digitStart = sign < 0 ? 1 : 0;
    int digitLength = digits.length() - digitStart;
    if (scale <= 0) {
      writeAscii(digits, digitStart, digits.length(), sink);
      writeZeros(-(long) scale, sink);
      return;
    }

    long point = (long) digitLength - scale;
    if (point > 0L) {
      writeAscii(digits, digitStart, digitStart + (int) point, sink);
      sink.accept('.');
      writeAscii(digits, digitStart + (int) point, digits.length(), sink);
      return;
    }
    sink.accept('0');
    sink.accept('.');
    writeZeros(-point, sink);
    writeAscii(digits, digitStart, digits.length(), sink);
  }

  private static void writeZeros(long count, IntConsumer sink) {
    for (long index = 0L; index < count; index++) {
      sink.accept('0');
    }
  }

  private static void writeAscii(String value, IntConsumer sink) {
    writeAscii(value, 0, value.length(), sink);
  }

  private static void writeAscii(
      String value,
      int start,
      int end,
      IntConsumer sink
  ) {
    for (int index = start; index < end; index++) {
      char next = value.charAt(index);
      if (next > 0x7f) {
        throw new IllegalArgumentException("Unsafe Trading Lab JSON number");
      }
      sink.accept(next);
    }
  }
}
