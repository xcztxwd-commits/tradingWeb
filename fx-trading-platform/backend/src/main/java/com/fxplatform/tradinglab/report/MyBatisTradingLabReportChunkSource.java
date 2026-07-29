package com.fxplatform.tradinglab.report;

import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import com.fxplatform.tradinglab.repository.TradingLabReportChunkRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.cursor.Cursor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MyBatisTradingLabReportChunkSource implements TradingLabReportChunkSource {

  private final TradingLabReportRepository reportRepository;
  private final TradingLabReportChunkRepository chunkRepository;

  @Override
  public TradingLabReportEntity lockReportForStream(UUID reportId) {
    if (reportId == null) {
      throw new IllegalArgumentException("Trading Lab report ID is required");
    }
    return reportRepository.lockForStream(reportId)
        .orElseThrow(() -> new TradingLabReportException(
            "TRADING_LAB_REPORT_NOT_FOUND",
            "Trading Lab report was not found"));
  }

  @Override
  public Cursor<TradingLabReportChunkEntity> openChunks(
      UUID reportId,
      TradingLabReportSection section
  ) {
    return chunkRepository.streamBySection(reportId, section.name());
  }
}
