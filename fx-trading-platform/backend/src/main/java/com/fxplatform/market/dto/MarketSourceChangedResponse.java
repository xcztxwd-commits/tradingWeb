package com.fxplatform.market.dto;

import com.fxplatform.market.model.MarketSourceMode;
import java.time.Instant;

/** Public notification emitted when a symbol falls back to or recovers a whole-bundle source. */
public record MarketSourceChangedResponse(
    String type,
    String symbol,
    String previousProviderCode,
    MarketSourceMode previousSourceMode,
    String providerCode,
    MarketSourceMode sourceMode,
    Instant changedAt,
    Instant asOf,
    Instant expiresAt,
    boolean stale
) {
}
