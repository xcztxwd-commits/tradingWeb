package com.fxplatform.wallet.model;

import java.time.Instant;
import java.util.List;

public record WalletReconciliationReport(
    Instant reconciledAt,
    List<WalletReconciliationIssue> issues
) {

  public boolean balanced() {
    return issues.isEmpty();
  }
}
