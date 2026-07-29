package com.fxplatform.market.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!validation")
@ConditionalOnProperty(
    prefix = "market.realtime",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
public class BinanceSubscriptionMaintenanceScheduler {

  private final BinanceSubscriptionManager binanceSubscriptionManager;

  @Scheduled(fixedDelayString = "${market.realtime.maintenance-delay:1000}")
  public void runMaintenance() {
    binanceSubscriptionManager.runMaintenance();
  }
}
