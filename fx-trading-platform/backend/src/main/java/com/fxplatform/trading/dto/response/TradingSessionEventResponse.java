package com.fxplatform.trading.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Identifier-only account mutation notification; clients refresh authoritative REST state. */
public record TradingSessionEventResponse(
    String type,
    UUID accountId,
    String resourceType,
    UUID resourceId,
    UUID relatedResourceId,
    Long version,
    Instant createdAt
) {
}
