package com.fxplatform.tradinglab.admin.report;

import java.util.UUID;

public record TradingLabPermanentResponse(
    UUID reportId,
    boolean permanent,
    long version
) {
}
