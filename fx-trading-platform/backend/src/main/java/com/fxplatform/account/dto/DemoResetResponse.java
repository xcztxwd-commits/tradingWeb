package com.fxplatform.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DemoResetResponse(
    UUID accountId,
    UUID requestId,
    long demoGeneration,
    BigDecimal spotAvailable,
    BigDecimal perpBalance,
    BigDecimal perpFreeMargin,
    Instant resetAt,
    boolean replayed
) {
}
