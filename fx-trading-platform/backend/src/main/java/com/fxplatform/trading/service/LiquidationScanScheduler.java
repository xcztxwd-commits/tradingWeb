package com.fxplatform.trading.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "trading.liquidation", name = "enabled", havingValue = "true")
public class LiquidationScanScheduler {

  private final LiquidationService liquidationService;

  @Scheduled(fixedDelayString = "${trading.liquidation.scan-interval-ms:60000}")
  public int scanAccounts() {
    return liquidationService.scanAllAccounts();
  }
}
