package com.fxplatform.wallet.service;

import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WalletDailySnapshotJob {

  private final WalletSnapshotService walletSnapshotService;
  private final Clock clock = Clock.systemUTC();

  @Scheduled(cron = "${wallet.snapshot.daily-cron:0 5 0 * * *}")
  public void runDailySnapshot() {
    walletSnapshotService.snapshot(LocalDate.now(clock));
  }
}
