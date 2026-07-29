package com.fxplatform.validation.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.ExecutableMarketProvenance;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutableMarketTimeAuthority;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.market.model.MarketSourceMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Validation-runtime authority backed only by the active virtual market Tick. */
@Component
@Profile("validation")
public class ValidationExecutableMarketTimeAuthority implements ExecutableMarketTimeAuthority {

  private final ValidationMarketClock marketClock;
  private final Clock wallClock;

  @Autowired
  public ValidationExecutableMarketTimeAuthority(ValidationMarketClock marketClock) {
    this(marketClock, Clock.systemUTC());
  }

  public ValidationExecutableMarketTimeAuthority(
      ValidationMarketClock marketClock,
      Clock wallClock
  ) {
    this.marketClock = Objects.requireNonNull(marketClock, "marketClock");
    this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
  }

  @Override
  public Instant currentTime(ExecutableMarketSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    return currentTime(
        snapshot.providerCode(),
        snapshot.sourceMode(),
        snapshot.asOf(),
        null);
  }

  @Override
  public Instant currentTime(FullFillResult result) {
    Objects.requireNonNull(result, "result");
    return currentTime(
        result.providerCode(),
        result.sourceMode(),
        result.asOf(),
        result.filledAt());
  }

  private Instant currentTime(
      String providerCode,
      MarketSourceMode sourceMode,
      Instant asOf,
      Instant filledAt
  ) {
    if (!ExecutableMarketProvenance.validationMarked(providerCode)) {
      return wallClock.instant();
    }
    if (!ExecutableMarketProvenance.exactValidation(providerCode, sourceMode)) {
      throw stale("Validation market provenance is not exact");
    }
    Instant virtualTime = marketClock.current()
        .map(ValidationMarketClock.Tick::virtualTime)
        .orElseThrow(() -> stale("Validation market clock is not active"));
    if (!virtualTime.equals(asOf)) {
      throw stale("Executable market snapshot is not current for the validation Tick");
    }
    if (filledAt != null && !virtualTime.equals(filledAt)) {
      throw stale("Canonical validation fill time does not match the validation Tick");
    }
    return virtualTime;
  }

  private static BusinessException stale(String message) {
    return new BusinessException(ErrorCode.MARKET_DATA_STALE, message);
  }
}
