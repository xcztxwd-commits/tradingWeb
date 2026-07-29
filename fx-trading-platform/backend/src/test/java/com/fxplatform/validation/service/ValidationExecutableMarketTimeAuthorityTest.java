package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class ValidationExecutableMarketTimeAuthorityTest {

  private static final UUID RUN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000519");
  private static final long GENERATION = 41L;
  private static final Instant VIRTUAL_TIME = Instant.parse("2020-01-01T00:00:01Z");
  private static final Instant WALL_TIME = Instant.parse("2026-07-25T00:00:00Z");

  @Test
  void exactSnapshotAndResultUseTheCurrentVirtualTick() {
    ValidationMarketClock clock = startedClock();
    ValidationExecutableMarketTimeAuthority authority = authority(clock);
    ExecutableMarketSnapshot snapshot = snapshot(
        "validation", MarketSourceMode.LOCAL_SIMULATED, VIRTUAL_TIME);
    FullFillResult result = result(
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        VIRTUAL_TIME,
        VIRTUAL_TIME);

    assertThat(authority.currentTime(snapshot)).isEqualTo(VIRTUAL_TIME);
    assertThat(authority.currentTime(result)).isEqualTo(VIRTUAL_TIME);
  }

  @Test
  void missingOrAdvancedVirtualClockRejectsTheSnapshotEvenInsideItsExpiryWindow() {
    ValidationMarketClock missingClock = new ValidationMarketClock();
    missingClock.reset(GENERATION);
    ExecutableMarketSnapshot snapshot = snapshot(
        "validation", MarketSourceMode.LOCAL_SIMULATED, VIRTUAL_TIME);

    assertStale(() -> authority(missingClock).currentTime(snapshot));

    ValidationMarketClock advancedClock = startedClock();
    advancedClock.advance(RUN_ID, GENERATION);
    assertStale(() -> authority(advancedClock).currentTime(snapshot));
  }

  @Test
  void inexactValidationMarkersNeverFallBackToWallClock() {
    ValidationExecutableMarketTimeAuthority authority = authority(startedClock());

    assertStale(() -> authority.currentTime(snapshot(
        "validation", MarketSourceMode.PUBLIC_EXTERNAL, VIRTUAL_TIME)));
    assertStale(() -> authority.currentTime(snapshot(
        "VALIDATION", MarketSourceMode.LOCAL_SIMULATED, VIRTUAL_TIME)));
    assertStale(() -> authority.currentTime(snapshot(
        " validation ", MarketSourceMode.LOCAL_SIMULATED, VIRTUAL_TIME)));
  }

  @Test
  void ordinaryLocalProvenanceKeepsWallClockSemantics() {
    ValidationExecutableMarketTimeAuthority authority = authority(startedClock());

    assertThat(authority.currentTime(snapshot(
        "local-spot", MarketSourceMode.LOCAL_SIMULATED, VIRTUAL_TIME)))
        .isEqualTo(WALL_TIME);
  }

  @Test
  void validationResultCannotCarryAWallClockFillTime() {
    ValidationExecutableMarketTimeAuthority authority = authority(startedClock());
    FullFillResult mixedTimeline = result(
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        VIRTUAL_TIME,
        WALL_TIME);

    assertStale(() -> authority.currentTime(mixedTimeline));
  }

  @Test
  void beanIsRestrictedToTheValidationProfile() {
    assertThat(ValidationExecutableMarketTimeAuthority.class.getAnnotation(Profile.class).value())
        .containsExactly("validation");
  }

  private static ValidationMarketClock startedClock() {
    ValidationMarketClock clock = new ValidationMarketClock();
    clock.reset(GENERATION);
    clock.start(RUN_ID, GENERATION, VIRTUAL_TIME);
    return clock;
  }

  private static ValidationExecutableMarketTimeAuthority authority(
      ValidationMarketClock clock
  ) {
    return new ValidationExecutableMarketTimeAuthority(
        clock,
        Clock.fixed(WALL_TIME, ZoneOffset.UTC));
  }

  private static ExecutableMarketSnapshot snapshot(
      String providerCode,
      MarketSourceMode sourceMode,
      Instant asOf
  ) {
    return new ExecutableMarketSnapshot(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        providerCode,
        "BTCUSDT",
        sourceMode,
        new BigDecimal("99"),
        new BigDecimal("100"),
        new BigDecimal("99.5"),
        null,
        null,
        asOf,
        asOf.plusSeconds(60));
  }

  private static FullFillResult result(
      String providerCode,
      MarketSourceMode sourceMode,
      Instant asOf,
      Instant filledAt
  ) {
    return new FullFillResult(
        new BigDecimal("100"),
        filledAt,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        new BigDecimal("0.0005"),
        new BigDecimal("0.05"),
        "USDT",
        LiquidityRole.TAKER,
        BigDecimal.ZERO,
        sourceMode,
        providerCode,
        "BTCUSDT",
        asOf,
        asOf.plusSeconds(60));
  }

  private static void assertStale(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action
  ) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MARKET_DATA_STALE"));
  }
}
