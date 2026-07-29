package com.fxplatform.tradinglab.report;

import java.util.List;
import java.util.Objects;

public record TradingLabReportSectionWrite(
    TradingLabReportSection section,
    long firstChunkSequence,
    List<TradingLabLogicalAppend> appends
) {

  public TradingLabReportSectionWrite {
    Objects.requireNonNull(section, "section");
    if (firstChunkSequence < 0L) {
      throw new IllegalArgumentException("Trading Lab first chunk sequence is invalid");
    }
    appends = List.copyOf(appends);
    if (appends.isEmpty()) {
      throw new IllegalArgumentException("Trading Lab section write is empty");
    }
  }
}
