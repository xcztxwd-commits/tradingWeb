package com.fxplatform.market.funding;

import com.fxplatform.market.model.MarketSourceMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class FixedFundingRateProvider implements FundingRateProvider {

  private final Clock clock;

  public FixedFundingRateProvider() {
    this(Clock.systemUTC());
  }

  FixedFundingRateProvider(Clock clock) {
    this.clock = clock;
  }

  @Override
  public FundingSource source() {
    return FundingSource.FIXED;
  }

  @Override
  public Optional<FundingRateSnapshot> current(Query query) {
    Optional<FixedConfig> config = config(query);
    if (config.isEmpty()) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    Optional<Instant> fundingTime = boundaryAtOrAfter(now, config.get().intervalSeconds());
    if (fundingTime.isEmpty()) {
      return Optional.empty();
    }
    Optional<Instant> nextFundingTime = plusInterval(
        fundingTime.get(), config.get().intervalSeconds());
    return nextFundingTime.map(next -> snapshot(
        config.get(), fundingTime.get(), next, now));
  }

  @Override
  public List<FundingRateSnapshot> history(
      Query query,
      Instant afterExclusive,
      Instant atOrBefore
  ) {
    Optional<FixedConfig> config = config(query);
    if (config.isEmpty() || afterExclusive == null || atOrBefore == null
        || !afterExclusive.isBefore(atOrBefore)) {
      return List.of();
    }
    Optional<Instant> firstBoundary = boundaryAtOrAfter(
        afterExclusive, config.get().intervalSeconds());
    if (firstBoundary.isEmpty()) {
      return List.of();
    }
    Instant fundingTime = firstBoundary.get();
    if (!fundingTime.isAfter(afterExclusive)) {
      Optional<Instant> next = plusInterval(fundingTime, config.get().intervalSeconds());
      if (next.isEmpty()) {
        return List.of();
      }
      fundingTime = next.get();
    }
    Instant generatedAt = clock.instant();
    List<FundingRateSnapshot> snapshots = new ArrayList<>();
    while (!fundingTime.isAfter(atOrBefore)) {
      Optional<Instant> next = plusInterval(fundingTime, config.get().intervalSeconds());
      if (next.isEmpty()) {
        break;
      }
      snapshots.add(snapshot(config.get(), fundingTime, next.get(), generatedAt));
      fundingTime = next.get();
    }
    return List.copyOf(snapshots);
  }

  private FundingRateSnapshot snapshot(
      FixedConfig config,
      Instant fundingTime,
      Instant nextFundingTime,
      Instant asOf
  ) {
    String raw = config.symbol() + '|' + config.rate().toPlainString() + '|'
        + fundingTime + '|' + nextFundingTime + '|' + config.intervalMinutes();
    return new FundingRateSnapshot(
        config.symbol(),
        config.rate(),
        fundingTime,
        nextFundingTime,
        null,
        asOf,
        source().providerCode(),
        MarketSourceMode.LOCAL_SIMULATED,
        config.intervalMinutes(),
        sha256(raw));
  }

  private Optional<FixedConfig> config(Query query) {
    if (query == null || query.symbol() == null || query.symbol().isBlank()
        || query.fixedRate() == null || query.fixedIntervalMinutes() <= 0) {
      return Optional.empty();
    }
    try {
      long seconds = Math.multiplyExact((long) query.fixedIntervalMinutes(), 60L);
      return Optional.of(new FixedConfig(
          query.symbol().trim().toUpperCase(Locale.ROOT),
          query.fixedRate(),
          query.fixedIntervalMinutes(),
          seconds));
    } catch (ArithmeticException ignored) {
      return Optional.empty();
    }
  }

  private Optional<Instant> boundaryAtOrAfter(Instant instant, long intervalSeconds) {
    try {
      long floorIndex = Math.floorDiv(instant.getEpochSecond(), intervalSeconds);
      long floorSeconds = Math.multiplyExact(floorIndex, intervalSeconds);
      Instant floor = Instant.ofEpochSecond(floorSeconds);
      return instant.equals(floor) ? Optional.of(floor) : plusInterval(floor, intervalSeconds);
    } catch (ArithmeticException | java.time.DateTimeException ignored) {
      return Optional.empty();
    }
  }

  private Optional<Instant> plusInterval(Instant instant, long intervalSeconds) {
    try {
      return Optional.of(instant.plusSeconds(intervalSeconds));
    } catch (ArithmeticException | java.time.DateTimeException ignored) {
      return Optional.empty();
    }
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private record FixedConfig(
      String symbol,
      java.math.BigDecimal rate,
      int intervalMinutes,
      long intervalSeconds
  ) {
  }
}
