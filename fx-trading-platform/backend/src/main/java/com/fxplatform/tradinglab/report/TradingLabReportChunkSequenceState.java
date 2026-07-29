package com.fxplatform.tradinglab.report;

public record TradingLabReportChunkSequenceState(
    long chunkCount,
    Long minSequence,
    Long maxSequence
) {
}
