package com.fxplatform.trading.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * OrderEventResponse carries a user-visible OMS event timeline item.
 */
public record OrderEventResponse(
    UUID id,
    UUID orderId,
    String eventType,
    String fromStatus,
    String toStatus,
    String reasonCode,
    String message,
    Instant createdAt
) {
}
