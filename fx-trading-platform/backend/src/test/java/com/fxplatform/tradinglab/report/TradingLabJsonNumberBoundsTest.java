package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradingLabJsonNumberBoundsTest {

  @Test
  void exactSingleAndDoubleDigitBoundariesAreNotRejectedByTheBitLengthUpperBound() {
    assertThat(TradingLabJsonNumberBounds.requireCanonicalLength(BigInteger.valueOf(8), 1))
        .isEqualTo(1L);
    assertThat(TradingLabJsonNumberBounds.requireCanonicalLength(BigInteger.valueOf(9), 1))
        .isEqualTo(1L);
    assertThat(TradingLabJsonNumberBounds.requireCanonicalLength(BigInteger.TEN, 2))
        .isEqualTo(2L);
    assertThatThrownBy(() ->
        TradingLabJsonNumberBounds.requireCanonicalLength(BigInteger.TEN, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void powersAndPlainDecimalsSucceedAtTheExactCapAndFailAtCapMinusOne() {
    BigInteger power = BigInteger.ONE.shiftLeft(100);
    int powerBytes = power.toString().length();
    assertThat(TradingLabJsonNumberBounds.requireCanonicalLength(power, powerBytes))
        .isEqualTo(powerBytes);
    assertThatThrownBy(() ->
        TradingLabJsonNumberBounds.requireCanonicalLength(power, powerBytes - 1L))
        .isInstanceOf(IllegalArgumentException.class);

    for (BigDecimal decimal : List.of(
        new BigDecimal("1E+8"),
        new BigDecimal("0.001"),
        new BigDecimal("-123.4500"))) {
      int exactBytes = decimal.toPlainString().length();
      assertThat(TradingLabJsonNumberBounds.requireCanonicalLength(decimal, exactBytes))
          .isEqualTo(exactBytes);
      assertThatThrownBy(() ->
          TradingLabJsonNumberBounds.requireCanonicalLength(decimal, exactBytes - 1L))
          .isInstanceOf(IllegalArgumentException.class);

      ByteArrayOutputStream output = new ByteArrayOutputStream();
      TradingLabCanonicalNumberWriter.write(decimal, exactBytes, output::write);
      assertThat(output.toString(StandardCharsets.US_ASCII))
          .isEqualTo(decimal.toPlainString());
    }
  }
}
