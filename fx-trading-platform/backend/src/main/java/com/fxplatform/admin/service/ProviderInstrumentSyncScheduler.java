package com.fxplatform.admin.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "market.provider-instrument-sync",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class ProviderInstrumentSyncScheduler {

  private static final Logger log = LoggerFactory.getLogger(ProviderInstrumentSyncScheduler.class);

  private final AdminMarketDataProviderService providerService;

  @Scheduled(
      initialDelayString = "${market.provider-instrument-sync.initial-delay-ms:60000}",
      fixedDelayString = "${market.provider-instrument-sync.fixed-delay-ms:21600000}"
  )
  public void syncProviderInstruments() {
    try {
      int syncedCount = providerService.syncEnabledProviderInstruments();
      log.info("Provider instrument sync completed, syncedCount={}", syncedCount);
    } catch (RuntimeException ex) {
      log.warn("Provider instrument sync failed: {}", ex.getMessage());
    }
  }
}
