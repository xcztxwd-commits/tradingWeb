package com.fxplatform.market.funding;

import com.fxplatform.trading.entity.FundingRateEntity;
import java.time.DateTimeException;
import java.time.Instant;

public final class FundingRateFreshnessPolicy {

  private static final int DEFAULT_STALE_SECONDS = 900;

  private FundingRateFreshnessPolicy() {
  }

  public static FundingRateEntity activeOrNull(
      FundingRateEntity candidate,
      Integer staleSeconds,
      Instant now
  ) {
    if (candidate == null
        || candidate.getFundingTime() == null
        || !candidate.getFundingTime().isAfter(now)
        || candidate.getAsOf() == null) {
      return null;
    }
    int effectiveStaleSeconds = staleSeconds == null || staleSeconds <= 0
        ? DEFAULT_STALE_SECONDS
        : staleSeconds;
    Instant freshAtOrAfter;
    try {
      freshAtOrAfter = now.minusSeconds(effectiveStaleSeconds);
    } catch (ArithmeticException | DateTimeException ignored) {
      freshAtOrAfter = Instant.MIN;
    }
    return candidate.getAsOf().isBefore(freshAtOrAfter) ? null : candidate;
  }
}
