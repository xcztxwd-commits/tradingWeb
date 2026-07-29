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
public class BinanceRealtimeRotationScheduler {

  private final BinanceRealtimeClient binanceRealtimeClient;

  @Scheduled(fixedDelayString = "${market.realtime.rotation-check-ms:60000}")
  public void runMaintenance() {
    binanceRealtimeClient.runMaintenance();
  }
}
