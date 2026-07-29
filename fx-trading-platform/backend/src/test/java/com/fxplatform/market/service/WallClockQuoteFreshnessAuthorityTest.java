package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.model.MarketSourceMode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WallClockQuoteFreshnessAuthorityTest {

  private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

  private final WallClockQuoteFreshnessAuthority authority =
      new WallClockQuoteFreshnessAuthority(Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void preservesStaleFlagAndExpiryBoundaryForOrdinaryQuotes() {
    assertThat(authority.isStale(
        quote("local-perp", "local-perp", NOW, NOW.plusSeconds(60), true),
        3_000L)).isTrue();
    assertThat(authority.isStale(
        quote("local-perp", "local-perp", NOW.minusSeconds(60), NOW, false),
        3_000L)).isTrue();
    assertThat(authority.isStale(
        quote("local-perp", "local-perp", NOW, NOW.plusMillis(1), false),
        3_000L)).isFalse();
  }

  @Test
  void preservesStrictLegacyAgeBoundaryWhenExpiryIsAbsent() {
    assertThat(authority.isStale(
        quote("massive", "massive", NOW.minusMillis(1_000), null, false),
        1_000L)).isFalse();
    assertThat(authority.isStale(
        quote("massive", "massive", NOW.minusMillis(1_001), null, false),
        1_000L)).isTrue();
  }

  @Test
  void rejectsFutureValidationMarkedQuotesOutsideTheValidationProfile() {
    Instant future = Instant.parse("2030-01-01T00:00:01Z");

    assertThat(authority.isStale(
        quote("validation", "validation", future, future.plusSeconds(60), false),
        3_000L)).isTrue();
    assertThat(authority.isStale(
        quote(" Validation ", "local-perp", future, future.plusSeconds(60), false),
        3_000L)).isTrue();
    assertThat(authority.isStale(
        quote("local-perp", "VALIDATION", future, future.plusSeconds(60), false),
        3_000L)).isTrue();
  }

  private static QuoteResponse quote(
      String source,
      String providerCode,
      Instant asOf,
      Instant expiresAt,
      boolean stale
  ) {
    return new QuoteResponse(
        "quote",
        "BTCUSDT-PERP",
        new BigDecimal("49999"),
        new BigDecimal("50001"),
        new BigDecimal("50000"),
        new BigDecimal("50000"),
        new BigDecimal("2"),
        source,
        asOf.toEpochMilli(),
        null,
        null,
        null,
        null,
        MarketSourceMode.LOCAL_SIMULATED,
        providerCode,
        "BTCUSDT",
        asOf,
        expiresAt,
        stale);
  }
}
