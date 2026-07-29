package com.fxplatform.tradinglab.environment;

public record TradingLabEnvironmentActionResponse(
    String action,
    boolean relayRunning
) {
}
