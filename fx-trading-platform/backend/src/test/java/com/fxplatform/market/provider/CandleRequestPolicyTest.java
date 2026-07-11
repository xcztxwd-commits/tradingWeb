package com.fxplatform.market.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.model.CandleRequest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CandleRequestPolicyTest {

  private static final Instant NOW = Instant.parse("2026-07-12T00:00:10Z");

  @ParameterizedTest
  @ValueSource(strings = {"1s", "1m", "5m", "15m", "1h", "4h", "1d"})
  void acceptsOnlyTheDocumentedExactTimeframes(String timeframe) {
    assertThat(CandleRequestPolicy.isValid(
        new CandleRequest(timeframe, NOW.minusSeconds(1), NOW))).isTrue();
  }

  @Test
  void acceptsTheInclusiveMaximumRangeButRejectsOneNanosecondMore() {
    assertThat(CandleRequestPolicy.isValid(
        new CandleRequest("1d", NOW.minus(Duration.ofDays(366)), NOW))).isTrue();
    assertThat(CandleRequestPolicy.isValid(
        new CandleRequest("1d", NOW.minus(Duration.ofDays(366)).minusNanos(1), NOW))).isFalse();
  }
}
