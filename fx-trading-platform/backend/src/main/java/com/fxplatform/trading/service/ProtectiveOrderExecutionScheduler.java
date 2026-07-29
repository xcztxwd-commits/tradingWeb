package com.fxplatform.trading.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Enables background protection scans without hiding the explicit execution service. */
@Service
@ConditionalOnProperty(
    prefix = "trading",
    name = "protective-order-execution-enabled",
    havingValue = "true")
public class ProtectiveOrderExecutionScheduler {

  private final ProtectiveOrderExecutionService executionService;

  public ProtectiveOrderExecutionScheduler(ProtectiveOrderExecutionService executionService) {
    this.executionService = executionService;
  }

  @Scheduled(fixedDelayString = "${trading.protective-order-scan-ms:1000}")
  public int scanProtectiveOrders() {
    return executionService.executeProtectiveOrders();
  }
}
