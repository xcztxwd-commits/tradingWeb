package com.fxplatform.market.provider;

import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;

public final class MarketDataDurations {

  private MarketDataDurations() {
  }

  public static Duration parsePositive(String value, Duration fallback) {
    Duration parsed = value == null || value.isBlank()
        ? fallback
        : DurationStyle.detectAndParse(value.trim());
    return positive(parsed, fallback);
  }

  public static Duration positive(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }
}
