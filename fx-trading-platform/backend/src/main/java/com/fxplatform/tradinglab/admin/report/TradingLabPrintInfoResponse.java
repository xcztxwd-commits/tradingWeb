package com.fxplatform.tradinglab.admin.report;

public record TradingLabPrintInfoResponse(
    long uncompressedBytes,
    long estimatedPageCount,
    long thresholdBytes,
    boolean requiresConfirmation
) {
}
