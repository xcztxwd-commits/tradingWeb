package com.fxplatform.tradinglab.report;

import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import org.springframework.stereotype.Service;

@Service
public final class TradingLabReportRetentionService {

  private final TradingLabReportRepository reportRepository;

  public TradingLabReportRetentionService(TradingLabReportRepository reportRepository) {
    this.reportRepository = reportRepository;
  }

  public int deleteExpiredTerminalReports(int batchSize) {
    if (batchSize < 1
        || batchSize > TradingLabReportProperties.MAX_CLEANUP_BATCH_SIZE) {
      throw new IllegalArgumentException("batchSize is outside the bounded cleanup range");
    }
    return reportRepository.deleteExpiredTerminalReports(batchSize);
  }
}
