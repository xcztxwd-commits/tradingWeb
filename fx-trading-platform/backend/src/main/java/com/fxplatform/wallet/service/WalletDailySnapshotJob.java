package com.fxplatform.wallet.service;

import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!validation")
@ConditionalOnProperty(
    prefix = "wallet.snapshot",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class WalletDailySnapshotJob {

  private final WalletSnapshotService walletSnapshotService;
  private final Clock clock = Clock.systemUTC();

  @Scheduled(cron = "${wallet.snapshot.daily-cron:0 5 0 * * *}")
  public void runDailySnapshot() {
    walletSnapshotService.snapshot(LocalDate.now(clock));
  }
}
