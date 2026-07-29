package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.model.MarketSourceMode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidationQuoteFreshnessAuthorityTest {

  private static final long GENERATION = 7L;
  private static final Instant WALL_NOW = Instant.parse("2026-07-25T00:00:00Z");
  private static final Instant VIRTUAL_START = Instant.parse("2020-01-01T00:00:00Z");

  @Test
  void acceptsTheExactHistoricalCurrentTickDespiteTheWallDerivedStaleFlag() {
    ValidationMarketClock marketClock = startedClock(VIRTUAL_START);
    Instant virtualTime = marketClock.advance(UUID.fromString(
        "00000000-0000-0000-0000-000000000007"), GENERATION).virtualTime();
    ValidationQuoteFreshnessAuthority authority = authority(marketClock);

    assertThat(authority.isStale(
        quote(
            "validation",
            "validation",
            MarketSourceMode.LOCAL_SIMULATED,
            virtualTime,
            virtualTime.plusSeconds(60),
            virtualTime.toEpochMilli(),
            true),
        3_000L)).isFalse();
  }

  @Test
  void requiresAnActiveCurrentTickAndRejectsThePreviousTick() {
    ValidationMarketClock inactive = new ValidationMarketClock();
    inactive.reset(GENERATION);
    ValidationQuoteFreshnessAuthority inactiveAuthority = authority(inactive);
    QuoteResponse firstTick = exactQuote(VIRTUAL_START);

    assertThat(inactiveAuthority.isStale(firstTick, 3_000L)).isTrue();

    ValidationMarketClock active = startedClock(VIRTUAL_START);
    ValidationQuoteFreshnessAuthority activeAuthority = authority(active);
    assertThat(activeAuthority.isStale(firstTick, 3_000L)).isFalse();

    active.advance(
        UUID.fromString("00000000-0000-0000-0000-000000000007"),
        GENERATION);
    assertThat(activeAuthority.isStale(firstTick, 3_000L)).isTrue();
  }

  @Test
  void rejectsInexactValidationProvenanceAndTimelineMetadata() {
    ValidationMarketClock marketClock = startedClock(VIRTUAL_START);
    ValidationQuoteFreshnessAuthority authority = authority(marketClock);

    assertThat(authority.isStale(quote(
        "VALIDATION",
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        VIRTUAL_START,
        VIRTUAL_START.plusSeconds(60),
        VIRTUAL_START.toEpochMilli(),
        false), 3_000L)).isTrue();
    assertThat(authority.isStale(quote(
        "validation",
        " validation ",
        MarketSourceMode.LOCAL_SIMULATED,
        VIRTUAL_START,
        VIRTUAL_START.plusSeconds(60),
        VIRTUAL_START.toEpochMilli(),
        false), 3_000L)).isTrue();
    assertThat(authority.isStale(quote(
        "validation",
        "validation",
        MarketSourceMode.PUBLIC_EXTERNAL,
        VIRTUAL_START,
        VIRTUAL_START.plusSeconds(60),
        VIRTUAL_START.toEpochMilli(),
        false), 3_000L)).isTrue();
    assertThat(authority.isStale(quote(
        "validation",
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        VIRTUAL_START,
        VIRTUAL_START.plusSeconds(60),
        VIRTUAL_START.plusMillis(1).toEpochMilli(),
        false), 3_000L)).isTrue();
    assertThat(authority.isStale(quote(
        "validation",
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        VIRTUAL_START,
        VIRTUAL_START,
        VIRTUAL_START.toEpochMilli(),
        false), 3_000L)).isTrue();
    assertThat(authority.isStale(quote(
        "validation",
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        VIRTUAL_START,
        null,
        VIRTUAL_START.toEpochMilli(),
        false), 3_000L)).isTrue();
  }

  @Test
  void keepsOrdinaryQuotesOnTheWallClockInsideTheValidationProfile() {
    ValidationMarketClock marketClock = startedClock(VIRTUAL_START);
    ValidationQuoteFreshnessAuthority authority = authority(marketClock);

    assertThat(authority.isStale(quote(
        "local-perp",
        "local-perp",
        MarketSourceMode.LOCAL_SIMULATED,
        VIRTUAL_START,
        VIRTUAL_START.plusSeconds(60),
        VIRTUAL_START.toEpochMilli(),
        false), 3_000L)).isTrue();
  }

  private static ValidationMarketClock startedClock(Instant virtualStart) {
    ValidationMarketClock marketClock = new ValidationMarketClock();
    marketClock.reset(GENERATION);
    marketClock.start(
        UUID.fromString("00000000-0000-0000-0000-000000000007"),
        GENERATION,
        virtualStart);
    return marketClock;
  }

  private static ValidationQuoteFreshnessAuthority authority(
      ValidationMarketClock marketClock
  ) {
    return new ValidationQuoteFreshnessAuthority(
        marketClock,
        Clock.fixed(WALL_NOW, ZoneOffset.UTC));
  }

  private static QuoteResponse exactQuote(Instant asOf) {
    return quote(
        "validation",
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        asOf,
        asOf.plusSeconds(60),
        asOf.toEpochMilli(),
        true);
  }

  private static QuoteResponse quote(
      String source,
      String providerCode,
      MarketSourceMode sourceMode,
      Instant asOf,
      Instant expiresAt,
      long timestamp,
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
        timestamp,
        null,
        null,
        null,
        null,
        sourceMode,
        providerCode,
        "BTCUSDT",
        asOf,
        expiresAt,
        stale);
  }
}
