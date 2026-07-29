package com.fxplatform.trading.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Enables background pending-order scans without hiding the explicit execution service. */
@Service
@ConditionalOnProperty(
    prefix = "trading",
    name = "pending-order-execution-enabled",
    havingValue = "true")
public class PendingOrderExecutionScheduler {

  private final PendingOrderExecutionService executionService;

  public PendingOrderExecutionScheduler(PendingOrderExecutionService executionService) {
    this.executionService = executionService;
  }

  @Scheduled(fixedDelayString = "${trading.pending-order-scan-ms:1000}")
  public int scanPendingOrders() {
    return executionService.executePendingOrders();
  }
}
