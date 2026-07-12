package com.fxplatform.market.funding;

import com.fxplatform.market.model.MarketSourceMode;
import java.math.BigDecimal;
import java.time.Instant;

public record FundingRateSnapshot(
    String symbol,
    BigDecimal fundingRate,
    Instant fundingTime,
    Instant nextFundingTime,
    BigDecimal markPrice,
    Instant asOf,
    String providerCode,
    MarketSourceMode sourceMode,
    int intervalMinutes,
    String rawPayloadHash
) {
}
