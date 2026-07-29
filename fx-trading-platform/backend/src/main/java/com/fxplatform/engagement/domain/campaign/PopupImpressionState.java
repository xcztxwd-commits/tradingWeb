package com.fxplatform.engagement.domain.campaign;

import java.time.Instant;
import java.time.LocalDate;

/** Authoritative per-user counters used by popup eligibility calculation. */
public record PopupImpressionState(
    int totalImpressions,
    LocalDate dailyBucket,
    int dailyImpressions,
    Instant lastImpressionAt,
    Instant optedOutAt) {

  public PopupImpressionState {
    if (totalImpressions < 0 || dailyImpressions < 0 || dailyImpressions > totalImpressions) {
      throw new IllegalArgumentException("Impression counts are inconsistent");
    }
    if (dailyImpressions > 0 && dailyBucket == null) {
      throw new IllegalArgumentException("dailyBucket is required when dailyImpressions is positive");
    }
  }

  public static PopupImpressionState empty() {
    return new PopupImpressionState(0, null, 0, null, null);
  }
}
