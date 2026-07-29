package com.fxplatform.execution;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Ordinary-runtime authority; validation-marked snapshots are never accepted here. */
@Component
@Profile("!validation")
public class WallClockExecutableMarketTimeAuthority implements ExecutableMarketTimeAuthority {

  private final Clock clock;

  @Autowired
  public WallClockExecutableMarketTimeAuthority() {
    this(Clock.systemUTC());
  }

  public WallClockExecutableMarketTimeAuthority(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Instant currentTime(ExecutableMarketSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    return currentTime(snapshot.providerCode());
  }

  @Override
  public Instant currentTime(FullFillResult result) {
    Objects.requireNonNull(result, "result");
    return currentTime(result.providerCode());
  }

  private Instant currentTime(String providerCode) {
    if (ExecutableMarketProvenance.validationMarked(providerCode)) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_STALE,
          "Validation market snapshots require the validation time authority");
    }
    return clock.instant();
  }
}
