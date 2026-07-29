package com.fxplatform.tradinglab.report;

public record TradingLabReportChunkTotals(
    long uncompressedBytes,
    long compressedBytes,
    long chunkCount
) {
}
