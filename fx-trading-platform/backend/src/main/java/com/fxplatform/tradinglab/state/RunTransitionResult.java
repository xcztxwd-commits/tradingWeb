package com.fxplatform.tradinglab.state;

import java.time.Instant;
import java.util.UUID;

public record RunTransitionResult(
    UUID transitionId,
    UUID runId,
    TradingLabRunState from,
    TradingLabRunState to,
    long runVersion,
    String idempotencyKey,
    String reason,
    Instant realTime,
    Instant virtualTime
) {
}
