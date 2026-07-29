package com.fxplatform.trading.scenario.oracle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class ScenarioDecimalMath {

  public static final int SCALE = 8;
  public static final int DIVISION_SCALE = 18;

  private ScenarioDecimalMath() {
  }

  public static BigDecimal s8(BigDecimal value) {
    return Objects.requireNonNull(value, "value")
        .setScale(SCALE, RoundingMode.HALF_UP);
  }

  public static BigDecimal ceil8(BigDecimal value) {
    return Objects.requireNonNull(value, "value")
        .setScale(SCALE, RoundingMode.CEILING);
  }

  public static BigDecimal divide18(BigDecimal numerator, BigDecimal denominator) {
    return Objects.requireNonNull(numerator, "numerator")
        .divide(Objects.requireNonNull(denominator, "denominator"),
            DIVISION_SCALE, RoundingMode.HALF_UP);
  }

  public static BigDecimal divide8(BigDecimal numerator, BigDecimal denominator) {
    return Objects.requireNonNull(numerator, "numerator")
        .divide(Objects.requireNonNull(denominator, "denominator"),
            SCALE, RoundingMode.HALF_UP);
  }

  public static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(step, "step");
    return value.divide(step, 0, RoundingMode.DOWN).multiply(step);
  }

  public static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
  }

  public static BigDecimal orZero(BigDecimal value) {
    return value == null ? zero() : s8(value);
  }
}
