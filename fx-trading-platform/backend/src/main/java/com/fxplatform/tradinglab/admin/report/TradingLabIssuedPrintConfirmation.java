package com.fxplatform.tradinglab.admin.report;

import java.time.Instant;

public record TradingLabIssuedPrintConfirmation(
    String token,
    Instant expiresAt
) {
}
