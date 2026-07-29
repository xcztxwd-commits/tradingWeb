package com.fxplatform.tradinglab.report;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TradingLabReportWriteBatch(
    UUID reportId,
    TradingLabReportWriteFence fence,
    List<TradingLabReportSectionWrite> sections
) {

  public TradingLabReportWriteBatch {
    Objects.requireNonNull(reportId, "reportId");
    sections = List.copyOf(sections);
    if (sections.isEmpty()) {
      throw new IllegalArgumentException("Trading Lab report write batch is empty");
    }
  }
}
