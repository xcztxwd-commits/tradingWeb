package com.fxplatform.wallet.service;

import com.fxplatform.wallet.model.WalletReconciliationIssue;
import com.fxplatform.wallet.model.WalletReconciliationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WalletReconciliationJob {

  private final WalletReconciliationService walletReconciliationService;

  @Scheduled(fixedDelayString = "${wallet.reconciliation.scan-ms:3600000}")
  public void run() {
    WalletReconciliationReport report = walletReconciliationService.reconcileAll();
    if (report.balanced()) {
      log.info("Wallet reconciliation completed without issues");
      return;
    }
    log.warn("Wallet reconciliation found {} issue(s)", report.issues().size());
    report.issues().stream()
        .limit(20)
        .forEach(this::logIssue);
  }

  private void logIssue(WalletReconciliationIssue issue) {
    log.warn(
        "Wallet reconciliation issue code={} accountId={} walletType={} asset={} expected={} actual={} message={}",
        issue.code(),
        issue.accountId(),
        issue.walletType(),
        issue.asset(),
        issue.expected(),
        issue.actual(),
        issue.message());
  }
}
