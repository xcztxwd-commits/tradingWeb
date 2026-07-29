package com.fxplatform.common.money;

import java.math.BigDecimal;

/** Safe, allocation-bounded checks for values destined for a SQL {@code NUMERIC(p, s)}. */
public final class ExactNumeric {

  private ExactNumeric() {
  }

  public static boolean fits(BigDecimal value, int precision, int scale) {
    if (precision <= 0 || scale < 0 || scale > precision) {
      throw new IllegalArgumentException("Invalid NUMERIC precision/scale");
    }
    if (value == null) {
      return false;
    }

    BigDecimal normalized;
    try {
      normalized = value.stripTrailingZeros();
    } catch (ArithmeticException exception) {
      return false;
    }

    long normalizedScale = normalized.scale();
    long fractionalDigits = Math.max(normalizedScale, 0L);
    long integerDigits = Math.max((long) normalized.precision() - normalizedScale, 0L);
    long allowedIntegerDigits = (long) precision - scale;
    return fractionalDigits <= scale && integerDigits <= allowedIntegerDigits;
  }

  /**
   * Returns true when a value misses {@code NUMERIC(p, s)} only because it has too many
   * fractional digits while its integer magnitude still fits. This lets callers preserve the
   * exchange-facing step/integrality error instead of collapsing it into a generic overflow.
   */
  public static boolean exceedsScaleOnly(BigDecimal value, int precision, int scale) {
    if (precision <= 0 || scale < 0 || scale > precision) {
      throw new IllegalArgumentException("Invalid NUMERIC precision/scale");
    }
    if (value == null) {
      return false;
    }

    BigDecimal normalized;
    try {
      normalized = value.stripTrailingZeros();
    } catch (ArithmeticException exception) {
      return false;
    }

    long normalizedScale = normalized.scale();
    long fractionalDigits = Math.max(normalizedScale, 0L);
    long integerDigits = Math.max((long) normalized.precision() - normalizedScale, 0L);
    long allowedIntegerDigits = (long) precision - scale;
    return fractionalDigits > scale && integerDigits <= allowedIntegerDigits;
  }
}
