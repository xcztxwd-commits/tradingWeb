package com.fxplatform.market.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!validation")
@ConditionalOnProperty(
    prefix = "market.test-data",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
public class MarketTestDataScheduler {

  private final MarketTestDataService marketTestDataService;

  @Scheduled(fixedDelayString = "${market.test-data.realtime-ms:1000}")
  public void publishRealtimeTestData() {
    marketTestDataService.publishRealtimeTestData();
  }
}
