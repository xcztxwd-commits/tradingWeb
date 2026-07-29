package com.fxplatform.tradinglab.queue;

import com.fxplatform.tradinglab.state.TradingLabRunState;
import java.time.Instant;
import java.util.UUID;

public record TradingLabRunClaim(
    UUID runId,
    TradingLabRunState state,
    long runVersion,
    long queueSequence,
    String claimOwner,
    Instant leaseUntil
) {
}
