package com.fxplatform.market.provider;

import java.time.Duration;
import java.util.Optional;

/** Monotonic total request budget for one public market bundle candidate. */
public final class MarketRequestDeadline {

  private final long startedAtNanos;
  private final long budgetNanos;

  private MarketRequestDeadline(Duration budget) {
    this.startedAtNanos = System.nanoTime();
    this.budgetNanos = toNanosSaturated(budget);
  }

  public static MarketRequestDeadline start(Duration budget) {
    return new MarketRequestDeadline(budget);
  }

  public Optional<Duration> remainingTimeout(Duration configuredTimeout) {
    long elapsedNanos = System.nanoTime() - startedAtNanos;
    long remainingNanos = budgetNanos - elapsedNanos;
    if (remainingNanos <= 0L) {
      return Optional.empty();
    }
    long timeoutNanos = Math.min(remainingNanos, toNanosSaturated(configuredTimeout));
    return timeoutNanos > 0L ? Optional.of(Duration.ofNanos(timeoutNanos)) : Optional.empty();
  }

  public boolean expired() {
    return remainingTimeout(Duration.ofNanos(Long.MAX_VALUE)).isEmpty();
  }

  private static long toNanosSaturated(Duration duration) {
    try {
      return duration.toNanos();
    } catch (ArithmeticException ignored) {
      return Long.MAX_VALUE;
    }
  }
}
