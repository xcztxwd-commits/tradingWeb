package com.fxplatform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class WallClockExecutableMarketTimeAuthorityTest {

  private static final Instant WALL_TIME = Instant.parse("2026-07-25T00:00:00Z");

  private final WallClockExecutableMarketTimeAuthority authority =
      new WallClockExecutableMarketTimeAuthority(
          Clock.fixed(WALL_TIME, ZoneOffset.UTC));

  @Test
  void ordinaryPublicAndLocalProvenanceUseOnlyTheWallClock() {
    assertThat(authority.currentTime(snapshot(
        "binance", MarketSourceMode.PUBLIC_EXTERNAL, WALL_TIME.minusSeconds(1))))
        .isEqualTo(WALL_TIME);
    assertThat(authority.currentTime(snapshot(
        "local-spot", MarketSourceMode.LOCAL_SIMULATED, WALL_TIME.minusSeconds(1))))
        .isEqualTo(WALL_TIME);
    assertThat(authority.currentTime(result(
        "local-spot",
        MarketSourceMode.LOCAL_SIMULATED,
        WALL_TIME.minusSeconds(1),
        WALL_TIME)))
        .isEqualTo(WALL_TIME);
  }

  @Test
  void everyValidationMarkedVariantFailsClosedOutsideValidationProfile() {
    assertStale(snapshot(
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        Instant.parse("2030-01-01T00:00:01Z")));
    assertStale(snapshot(
        "validation",
        MarketSourceMode.PUBLIC_EXTERNAL,
        Instant.parse("2030-01-01T00:00:01Z")));
    assertStale(snapshot(
        "VALIDATION",
        MarketSourceMode.LOCAL_SIMULATED,
        Instant.parse("2030-01-01T00:00:01Z")));
    assertStale(snapshot(
        " validation ",
        MarketSourceMode.LOCAL_SIMULATED,
        Instant.parse("2030-01-01T00:00:01Z")));
  }

  @Test
  void beanIsExcludedFromTheValidationProfile() {
    assertThat(WallClockExecutableMarketTimeAuthority.class.getAnnotation(Profile.class).value())
        .containsExactly("!validation");
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

  private void assertStale(ExecutableMarketSnapshot snapshot) {
    assertThatThrownBy(() -> authority.currentTime(snapshot))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MARKET_DATA_STALE"));
  }
}
