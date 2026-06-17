package com.fxplatform.trading.dto.response;

import java.time.Instant;
import java.util.UUID;

public record TradingSessionEventResponse(
    String type,
    UUID accountId,
    UUID orderId,
    UUID eventId,
    String eventType,
    String status,
    Instant createdAt
) {
}
