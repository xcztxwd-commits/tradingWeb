package com.fxplatform.tradinglab.environment;

public record TradingLabEnvironmentStatusResponse(
    boolean relayRunning,
    String validationHealth
) {
}
