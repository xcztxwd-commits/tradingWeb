package com.fxplatform.market.dto;

import com.fxplatform.market.model.MarketSourceMode;
import java.math.BigDecimal;
import java.time.Instant;

public record PerpetualReferenceResponse(
    String symbol,
    String providerSymbol,
    String providerCode,
    MarketSourceMode sourceMode,
    BigDecimal bid,
    BigDecimal ask,
    BigDecimal last,
    BigDecimal mark,
    BigDecimal index,
    Instant asOf,
    Instant expiresAt,
    boolean stale
) {
}
