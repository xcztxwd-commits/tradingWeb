package com.fxplatform.market.realtime;

import java.util.Set;

public record MarketRealtimeStatus(
    boolean enabled,
    String provider,
    boolean connected,
    Long connectedAt,
    Long lastMessageAt,
    Set<String> activeSymbols,
    int desiredStreamCount,
    long processedCount,
    long cacheFailureCount,
    long candleFailureCount,
    long publishFailureCount,
    long backfillSuccessCount,
    long backfillFailureCount,
    int reconnectAttempt,
    long rejectedSymbolCount
) {
}
