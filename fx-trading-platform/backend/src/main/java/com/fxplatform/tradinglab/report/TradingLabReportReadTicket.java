package com.fxplatform.tradinglab.report;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable proof that a terminal report passed the bounded synchronous read preflight.
 */
public record TradingLabReportReadTicket(
    UUID reportId,
    long reportVersion,
    long uncompressedBytes,
    long compressedBytes,
    int chunkCount
) {

  public TradingLabReportReadTicket {
    Objects.requireNonNull(reportId, "reportId");
    if (reportVersion < 0L
        || uncompressedBytes < 0L
        || compressedBytes < 0L
        || chunkCount < 0) {
      throw new IllegalArgumentException("Trading Lab report read ticket is invalid");
    }
  }
}
