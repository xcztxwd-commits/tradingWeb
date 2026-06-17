package com.fxplatform.market.realtime;

import java.math.BigDecimal;
import java.time.Duration;

public record MarketTestControlRequest(
    String symbol,
    BigDecimal bid,
    BigDecimal ask,
    Duration ttl
) {
}
