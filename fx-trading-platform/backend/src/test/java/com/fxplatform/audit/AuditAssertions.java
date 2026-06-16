package com.fxplatform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.math.BigDecimal;

final class AuditAssertions {

  static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.00000001");
  static final BigDecimal BTC_TOLERANCE = new BigDecimal("0.00000001");
  static final BigDecimal FX_CONVERSION_TOLERANCE = new BigDecimal("0.01");

  private AuditAssertions() {
  }

  static void assertAmountClose(BigDecimal actual, String expected) {
    assertThat(actual).isCloseTo(new BigDecimal(expected), offset(AMOUNT_TOLERANCE));
  }

  static void assertBtcClose(BigDecimal actual, String expected) {
    assertThat(actual).isCloseTo(new BigDecimal(expected), offset(BTC_TOLERANCE));
  }

  static void assertFxConversionClose(BigDecimal actual, String expected) {
    assertThat(actual).isCloseTo(new BigDecimal(expected), offset(FX_CONVERSION_TOLERANCE));
  }
}
