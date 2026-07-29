package com.fxplatform.tradinglab.admin.report;

import java.time.Instant;
import java.util.UUID;

public record TradingLabPrintConfirmationResponse(
    UUID reportId,
    String token,
    Instant expiresAt
) {
}
