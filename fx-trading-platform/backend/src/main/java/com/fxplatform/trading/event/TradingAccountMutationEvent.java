package com.fxplatform.trading.event;

import com.fxplatform.trading.dto.response.TradingSessionEventResponse;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable refresh hint for one authenticated account mutation. */
public record TradingAccountMutationEvent(
    UUID userId,
    UUID accountId,
    String type,
    String resourceType,
    UUID resourceId,
    UUID relatedResourceId,
    Long version,
    Instant occurredAt
) {

  public TradingAccountMutationEvent {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(accountId, "accountId");
    type = required(type, "type");
    resourceType = required(resourceType, "resourceType");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  public TradingSessionEventResponse toResponse() {
    return new TradingSessionEventResponse(
        type,
        accountId,
        resourceType,
        resourceId,
        relatedResourceId,
        version,
        occurredAt);
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }
}
