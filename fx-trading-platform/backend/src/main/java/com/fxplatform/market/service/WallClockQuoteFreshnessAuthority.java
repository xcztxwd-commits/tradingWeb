package com.fxplatform.market.service;

import com.fxplatform.market.dto.QuoteResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Ordinary-runtime quote freshness; validation-marked quotes fail closed here. */
@Component
@Profile("!validation")
public class WallClockQuoteFreshnessAuthority implements QuoteFreshnessAuthority {

  private static final String VALIDATION_SOURCE = "validation";

  private final Clock clock;

  @Autowired
  public WallClockQuoteFreshnessAuthority() {
    this(Clock.systemUTC());
  }

  public WallClockQuoteFreshnessAuthority(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public boolean isStale(QuoteResponse quote, long quoteStaleMs) {
    Objects.requireNonNull(quote, "quote");
    if (validationMarked(quote.source()) || validationMarked(quote.providerCode())) {
      return true;
    }
    if (quote.stale()) {
      return true;
    }
    Instant now = clock.instant();
    if (quote.expiresAt() != null) {
      return !now.isBefore(quote.expiresAt());
    }
    return now.toEpochMilli() - quote.timestamp() > quoteStaleMs;
  }

  private static boolean validationMarked(String value) {
    return value != null && VALIDATION_SOURCE.equalsIgnoreCase(value.strip());
  }
}
