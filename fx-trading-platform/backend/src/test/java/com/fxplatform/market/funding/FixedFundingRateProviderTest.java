package com.fxplatform.market.funding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.model.MarketSourceMode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FixedFundingRateProviderTest {

  @Test
  void exactUtcEpochBoundaryIsTheCurrentFundingTime() {
    Instant boundary = Instant.parse("2026-07-13T08:00:00Z");
    FixedFundingRateProvider provider = provider(boundary);

    FundingRateSnapshot snapshot = provider.current(query("0.0001", 480)).orElseThrow();

    assertThat(snapshot.fundingTime()).isEqualTo(boundary);
    assertThat(snapshot.nextFundingTime()).isEqualTo(Instant.parse("2026-07-13T16:00:00Z"));
    assertThat(snapshot.asOf()).isEqualTo(boundary);
    assertThat(snapshot.providerCode()).isEqualTo("fixed");
    assertThat(snapshot.sourceMode()).isEqualTo(MarketSourceMode.LOCAL_SIMULATED);
    assertThat(snapshot.markPrice()).isNull();
    assertThat(provider.source()).isEqualTo(FundingSource.FIXED);
  }

  @Test
  void instantAfterBoundarySelectsTheNextUtcEpochBoundary() {
    FixedFundingRateProvider provider = provider(Instant.parse("2026-07-13T08:00:00.001Z"));

    FundingRateSnapshot snapshot = provider.current(query("-0.0002", 480)).orElseThrow();

    assertThat(snapshot.fundingRate()).isEqualByComparingTo("-0.0002");
    assertThat(snapshot.fundingTime()).isEqualTo(Instant.parse("2026-07-13T16:00:00Z"));
    assertThat(snapshot.nextFundingTime()).isEqualTo(Instant.parse("2026-07-14T00:00:00Z"));
  }

  @Test
  void historyGeneratesEveryCustomBoundaryInAfterExclusiveToInclusiveRange() {
    FixedFundingRateProvider provider = provider(Instant.parse("2026-07-13T07:30:00Z"));

    List<FundingRateSnapshot> snapshots = provider.history(
        query("0.0003", 120),
        Instant.parse("2026-07-13T01:59:59.999Z"),
        Instant.parse("2026-07-13T08:00:00Z"));

    assertThat(snapshots).extracting(FundingRateSnapshot::fundingTime).containsExactly(
        Instant.parse("2026-07-13T02:00:00Z"),
        Instant.parse("2026-07-13T04:00:00Z"),
        Instant.parse("2026-07-13T06:00:00Z"),
        Instant.parse("2026-07-13T08:00:00Z"));
    assertThat(snapshots).allMatch(snapshot -> snapshot.intervalMinutes() == 120
        && snapshot.fundingRate().compareTo(new BigDecimal("0.0003")) == 0
        && snapshot.rawPayloadHash().length() == 64);
  }

  @Test
  void nonPositiveIntervalIsUnavailable() {
    FixedFundingRateProvider provider = provider(Instant.parse("2026-07-13T08:00:00Z"));

    assertThat(provider.current(query("0.0001", 0))).isEmpty();
    assertThat(provider.history(query("0.0001", -1),
        Instant.parse("2026-07-13T00:00:00Z"),
        Instant.parse("2026-07-13T08:00:00Z"))).isEmpty();
  }

  private FixedFundingRateProvider provider(Instant now) {
    return new FixedFundingRateProvider(Clock.fixed(now, ZoneOffset.UTC));
  }

  private FundingRateProvider.Query query(String rate, int minutes) {
    return new FundingRateProvider.Query(
        "BTCUSDT-PERP", "BTCUSDT-PERP", new BigDecimal(rate), minutes);
  }
}
