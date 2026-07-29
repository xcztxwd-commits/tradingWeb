package com.fxplatform.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class ExactNumericTest {

  @Test
  void acceptsExactNumericBoundaryAndTrailingZeros() {
    assertThat(ExactNumeric.fits(
        new BigDecimal("99999999999999.9999999999"), 24, 10)).isTrue();
    assertThat(ExactNumeric.fits(new BigDecimal("1.2300000000"), 24, 10)).isTrue();
    assertThat(ExactNumeric.fits(BigDecimal.ZERO, 24, 10)).isTrue();
  }

  @Test
  void rejectsIntegerAndFractionalDigitOverflow() {
    assertThat(ExactNumeric.fits(new BigDecimal("1E+14"), 24, 10)).isFalse();
    assertThat(ExactNumeric.fits(new BigDecimal("1E-11"), 24, 10)).isFalse();
    assertThat(ExactNumeric.fits(null, 24, 10)).isFalse();
  }

  @Test
  void rejectsExtremeScaleWithSafeStripCatchAndLongDigitArithmetic() {
    assertThat(ExactNumeric.fits(
        new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE), 24, 8)).isFalse();
    assertThat(ExactNumeric.fits(
        new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE), 24, 8)).isFalse();
  }
}
