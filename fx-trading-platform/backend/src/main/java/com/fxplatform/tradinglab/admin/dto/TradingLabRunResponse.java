package com.fxplatform.tradinglab.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TradingLabRunResponse(
    UUID id,
    UUID scenarioId,
    UUID reportId,
    String state,
    long queueSequence,
    boolean pauseRequested,
    boolean cancelRequested,
    Instant virtualStartedAt,
    Instant virtualCurrentAt,
    long processedTicks,
    long totalTicks,
    BigDecimal speedMultiplier,
    long currentStep,
    String failureCode,
    String failureMessage,
    String configSnapshotHash,
    String modelVersion,
    String symbolConfigVersion,
    String codeVersion,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant finishedAt,
    long version
) {
}
