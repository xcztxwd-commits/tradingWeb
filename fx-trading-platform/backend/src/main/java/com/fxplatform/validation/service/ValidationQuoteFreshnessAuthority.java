package com.fxplatform.validation.service;

import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.service.QuoteFreshnessAuthority;
import com.fxplatform.market.service.WallClockQuoteFreshnessAuthority;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Validation quote freshness owned by the active virtual market Tick. */
@Component
@Profile("validation")
public class ValidationQuoteFreshnessAuthority implements QuoteFreshnessAuthority {

  private static final String VALIDATION_SOURCE = "validation";

  private final ValidationMarketClock marketClock;
  private final QuoteFreshnessAuthority wallAuthority;

  @Autowired
  public ValidationQuoteFreshnessAuthority(ValidationMarketClock marketClock) {
    this(marketClock, Clock.systemUTC());
  }

  public ValidationQuoteFreshnessAuthority(
      ValidationMarketClock marketClock,
      Clock wallClock
  ) {
    this.marketClock = Objects.requireNonNull(marketClock, "marketClock");
    this.wallAuthority = new WallClockQuoteFreshnessAuthority(
        Objects.requireNonNull(wallClock, "wallClock"));
  }

  @Override
  public boolean isStale(QuoteResponse quote, long quoteStaleMs) {
    Objects.requireNonNull(quote, "quote");
    boolean validationMarked =
        validationMarked(quote.source()) || validationMarked(quote.providerCode());
    if (!validationMarked) {
      return wallAuthority.isStale(quote, quoteStaleMs);
    }
    if (!exactValidation(quote)) {
      return true;
    }
    Instant asOf = quote.asOf();
    Instant expiresAt = quote.expiresAt();
    if (asOf == null
        || expiresAt == null
        || !expiresAt.isAfter(asOf)
        || quote.timestamp() != asOf.toEpochMilli()) {
      return true;
    }
    return marketClock.current()
        .map(ValidationMarketClock.Tick::virtualTime)
        .map(virtualTime ->
            !virtualTime.equals(asOf) || !virtualTime.isBefore(expiresAt))
        .orElse(true);
  }

  private static boolean exactValidation(QuoteResponse quote) {
    return VALIDATION_SOURCE.equals(quote.source())
        && VALIDATION_SOURCE.equals(quote.providerCode())
        && quote.sourceMode() == MarketSourceMode.LOCAL_SIMULATED;
  }

  private static boolean validationMarked(String value) {
    return value != null && VALIDATION_SOURCE.equalsIgnoreCase(value.strip());
  }
}
