package com.fxplatform.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record DemoExecutionPolicy(
    DemoMatchingMode matchingMode,
    BigDecimal makerFeeRate,
    BigDecimal takerFeeRate,
    BigDecimal liquidationFeeRate,
    BigDecimal slippageRate,
    List<DemoBookLevel> bids,
    List<DemoBookLevel> asks,
    BigDecimal maxFillQuantityPerTick
) {

  public DemoExecutionPolicy {
    matchingMode = Objects.requireNonNull(matchingMode, "matchingMode");
    requireRate(makerFeeRate, "makerFeeRate");
    requireRate(takerFeeRate, "takerFeeRate");
    requireRate(liquidationFeeRate, "liquidationFeeRate");
    requireRate(slippageRate, "slippageRate");
    bids = List.copyOf(bids == null ? List.of() : bids);
    asks = List.copyOf(asks == null ? List.of() : asks);
    if (maxFillQuantityPerTick != null && maxFillQuantityPerTick.signum() <= 0) {
      throw new IllegalArgumentException("maxFillQuantityPerTick must be positive");
    }
  }

  public static DemoExecutionPolicy defaults() {
    return new DemoExecutionPolicy(
        DemoMatchingMode.SIMPLE,
        new BigDecimal("0.0002"),
        new BigDecimal("0.0005"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(),
        List.of(),
        null);
  }

  private static void requireRate(BigDecimal rate, String name) {
    if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
      throw new IllegalArgumentException(name + " rate must be in [0, 1)");
    }
  }
}
