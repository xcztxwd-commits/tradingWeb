package com.fxplatform.tradinglab.report;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Allocation-free upper bounds followed by boundary-safe exact JSON numeric lengths. */
final class TradingLabJsonNumberBounds {

  private static final long LOG10_2_SCALED_UP = 30_103L;
  private static final long LOG10_SCALE = 100_000L;

  private TradingLabJsonNumberBounds() {
  }

  static long requireCanonicalLength(Object value, long maxBytes) {
    if (maxBytes < 0L) {
      throw unsafe();
    }
    long length;
    if (value instanceof BigDecimal decimal && value.getClass() == BigDecimal.class) {
      length = exactPlainDecimalLength(decimal, maxBytes);
    } else if (value instanceof BigInteger integer && value.getClass() == BigInteger.class) {
      length = exactIntegerLength(integer, maxBytes);
    } else if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      length = value.toString().length();
    } else if (value instanceof Float floating && Float.isFinite(floating)) {
      length = floating.toString().length();
    } else if (value instanceof Double floating && Double.isFinite(floating)) {
      length = floating.toString().length();
    } else {
      throw unsafe();
    }
    if (length < 1L || length > maxBytes) {
      throw unsafe();
    }
    return length;
  }

  private static long exactIntegerLength(BigInteger value, long maxBytes) {
    long upper = Math.addExact(
        integerMagnitudeDigitsUpperBound(value),
        value.signum() < 0 ? 1L : 0L);
    requireNearBoundary(upper, maxBytes);
    // The upper bound proves this allocation is at most maxBytes + 1.
    return value.toString().length();
  }

  private static long exactPlainDecimalLength(BigDecimal value, long maxBytes) {
    long scale = value.scale();
    int sign = value.signum();
    long upper;
    if (sign == 0) {
      upper = scale <= 0L ? 1L : Math.addExact(scale, 2L);
    } else {
      long digitsUpper = integerMagnitudeDigitsUpperBound(value.unscaledValue());
      if (scale > 0L) {
        upper = Math.max(
            Math.addExact(digitsUpper, 1L),
            Math.addExact(scale, 2L));
      } else {
        upper = Math.addExact(digitsUpper, -scale);
      }
      upper = Math.addExact(upper, sign < 0 ? 1L : 0L);
    }
    requireNearBoundary(upper, maxBytes);

    // Only a maxBytes+1-bounded magnitude reaches precision(), so it cannot expand arbitrarily.
    long digits = value.precision();
    long exact;
    if (sign == 0) {
      exact = scale <= 0L ? 1L : Math.addExact(scale, 2L);
    } else if (scale == 0L) {
      exact = digits;
    } else if (scale > 0L) {
      exact = scale < digits
          ? Math.addExact(digits, 1L)
          : Math.addExact(scale, 2L);
    } else {
      exact = Math.addExact(digits, -scale);
    }
    return Math.addExact(exact, sign < 0 ? 1L : 0L);
  }

  private static long integerMagnitudeDigitsUpperBound(BigInteger value) {
    if (value.signum() == 0) {
      return 1L;
    }
    long bits = Math.max(1L, value.bitLength());
    return Math.addExact(
        Math.multiplyExact(bits, LOG10_2_SCALED_UP),
        LOG10_SCALE - 1L) / LOG10_SCALE;
  }

  private static void requireNearBoundary(long upper, long maxBytes) {
    long boundary = Math.addExact(maxBytes, 1L);
    if (upper > boundary) {
      throw unsafe();
    }
  }

  private static IllegalArgumentException unsafe() {
    return new IllegalArgumentException("Unsafe Trading Lab JSON number");
  }
}
