package com.fxplatform.market.model;

import java.time.Instant;

public record CandleRequest(
    String timeframe,
    Instant from,
    Instant to
) {
}
