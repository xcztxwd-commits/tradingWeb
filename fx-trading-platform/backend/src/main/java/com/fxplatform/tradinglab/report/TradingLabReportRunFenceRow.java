package com.fxplatform.tradinglab.report;

import java.time.Instant;
import java.util.UUID;

public record TradingLabReportRunFenceRow(
    UUID runId,
    UUID reportId,
    String state,
    Integer leaseKey,
    String leaseOwner,
    Instant leaseUntil,
    boolean leaseLive
) {
}
