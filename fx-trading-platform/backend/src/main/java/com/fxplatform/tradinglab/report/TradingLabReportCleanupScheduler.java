package com.fxplatform.tradinglab.report;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "trading-lab.report.cleanup",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public final class TradingLabReportCleanupScheduler {

  private final TradingLabReportRetentionService retentionService;
  private final TradingLabReportProperties reportProperties;

  public TradingLabReportCleanupScheduler(
      TradingLabReportRetentionService retentionService,
      TradingLabReportProperties reportProperties) {
    this.retentionService = retentionService;
    this.reportProperties = reportProperties;
  }

  @Scheduled(fixedDelayString = "${trading-lab.report.cleanup.fixed-delay:PT1H}")
  public void cleanup() {
    retentionService.deleteExpiredTerminalReports(reportProperties.cleanup().batchSize());
  }
}
