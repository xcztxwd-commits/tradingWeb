package com.fxplatform.tradinglab.admin.report;

import java.time.Instant;
import java.util.UUID;

public record TradingLabReportResponse(
    UUID id,
    UUID runId,
    UUID scenarioId,
    String status,
    String modelVersion,
    String configSnapshotHash,
    String codeVersion,
    long uncompressedBytes,
    long compressedBytes,
    int chunkCount,
    Instant retainedUntil,
    boolean permanent,
    String failureCode,
    String failureMessage,
    Instant createdAt,
    Instant completedAt,
    long version
) {
}
