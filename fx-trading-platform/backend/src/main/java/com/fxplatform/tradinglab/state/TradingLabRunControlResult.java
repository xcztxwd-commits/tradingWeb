package com.fxplatform.tradinglab.state;

import java.util.UUID;

public record TradingLabRunControlResult(
    UUID runId,
    TradingLabRunState state,
    long runVersion,
    boolean pauseRequested,
    boolean cancelRequested
) {
}
