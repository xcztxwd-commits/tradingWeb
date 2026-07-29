package com.fxplatform.trading.scenario;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record ScenarioPriceStep(
    PricePath path,
    int sequence,
    BigDecimal bid,
    BigDecimal ask,
    BigDecimal last,
    BigDecimal mark,
    BigDecimal index,
    String source,
    Instant asOf,
    Instant expiresAt,
    Set<MarketField> missingFields
) {

  public ScenarioPriceStep {
    Objects.requireNonNull(path, "path");
    missingFields = Set.copyOf(Objects.requireNonNull(missingFields, "missingFields"));
    requirePresence(MarketField.BID, bid, missingFields);
    requirePresence(MarketField.ASK, ask, missingFields);
    requirePresence(MarketField.LAST, last, missingFields);
    requirePresence(MarketField.MARK, mark, missingFields);
    requirePresence(MarketField.INDEX, index, missingFields);
    requirePresence(MarketField.SOURCE, source, missingFields);
    requirePresence(MarketField.AS_OF, asOf, missingFields);
    requirePresence(MarketField.EXPIRES_AT, expiresAt, missingFields);
  }

  private static void requirePresence(
      MarketField field,
      Object value,
      Set<MarketField> missingFields
  ) {
    if (missingFields.contains(field) == (value != null)) {
      throw new IllegalArgumentException(
          field + (value == null ? " must be declared missing" : " cannot be present when missing"));
    }
  }

  public enum MarketField {
    BID,
    ASK,
    LAST,
    MARK,
    INDEX,
    SOURCE,
    AS_OF,
    EXPIRES_AT
  }

  public enum PricePath {
    UP_UP_UP,
    UP_UP_DOWN,
    UP_DOWN_UP,
    UP_DOWN_DOWN,
    DOWN_UP_UP,
    DOWN_UP_DOWN,
    DOWN_DOWN_UP,
    DOWN_DOWN_DOWN,
    FLAT,
    TOUCH_EXACTLY,
    CROSS_THRESHOLD,
    GAP_THROUGH_THRESHOLD,
    OSCILLATE_AROUND_THRESHOLD,
    STALE_MARKET,
    PROVIDER_SWITCH_WITH_GAP
  }
}
