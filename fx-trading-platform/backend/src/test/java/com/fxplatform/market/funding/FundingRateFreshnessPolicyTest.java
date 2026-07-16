package com.fxplatform.market.funding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.trading.entity.FundingRateEntity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FundingRateFreshnessPolicyTest {

  private static final Instant NOW = Instant.parse("2026-07-12T00:00:10Z");

  @Test
  void keepsSnapshotExactlyAtConfiguredFreshnessBoundary() {
    FundingRateEntity candidate = candidate(NOW.plusSeconds(1), NOW.minusSeconds(120));

    assertThat(FundingRateFreshnessPolicy.activeOrNull(candidate, 120, NOW))
        .isSameAs(candidate);
  }

  @Test
  void missingStaleConfigFallsBackToNineHundredSeconds() {
    FundingRateEntity candidate = candidate(NOW.plusSeconds(1), NOW.minusSeconds(900));

    assertThat(FundingRateFreshnessPolicy.activeOrNull(candidate, null, NOW))
        .isSameAs(candidate);
  }

  @Test
  void nonPositiveStaleConfigFallsBackToNineHundredSeconds() {
    FundingRateEntity candidate = candidate(NOW.plusSeconds(1), NOW.minusSeconds(901));

    assertThat(FundingRateFreshnessPolicy.activeOrNull(candidate, 0, NOW)).isNull();
    assertThat(FundingRateFreshnessPolicy.activeOrNull(candidate, -1, NOW)).isNull();
  }

  @Test
  void fundingTimeMustBeStrictlyAfterNow() {
    FundingRateEntity candidate = candidate(NOW, NOW);

    assertThat(FundingRateFreshnessPolicy.activeOrNull(candidate, 900, NOW)).isNull();
  }

  @Test
  void underflowSaturatesFreshnessBoundaryAtInstantMin() {
    Instant now = Instant.MIN.plusSeconds(1);
    FundingRateEntity candidate = candidate(now.plusSeconds(1), Instant.MIN);

    assertThat(FundingRateFreshnessPolicy.activeOrNull(candidate, 900, now))
        .isSameAs(candidate);
  }

  private FundingRateEntity candidate(Instant fundingTime, Instant asOf) {
    FundingRateEntity candidate = new FundingRateEntity();
    candidate.setFundingTime(fundingTime);
    candidate.setAsOf(asOf);
    return candidate;
  }
}
