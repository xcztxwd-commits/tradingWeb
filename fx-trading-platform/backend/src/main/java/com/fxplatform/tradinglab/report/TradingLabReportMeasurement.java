package com.fxplatform.tradinglab.report;

public record TradingLabReportMeasurement(
    long reportVersion,
    long exactUncompressedBytes,
    long compressedBytes,
    int chunkCount
) {
}
