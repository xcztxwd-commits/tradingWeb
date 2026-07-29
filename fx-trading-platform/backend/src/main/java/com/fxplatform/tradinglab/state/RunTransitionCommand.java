package com.fxplatform.tradinglab.state;

import java.time.Instant;
import java.util.UUID;

public record RunTransitionCommand(
    UUID runId,
    TradingLabRunState expected,
    TradingLabRunState target,
    String idempotencyKey,
    String reason,
    Instant virtualTime
) {
}
