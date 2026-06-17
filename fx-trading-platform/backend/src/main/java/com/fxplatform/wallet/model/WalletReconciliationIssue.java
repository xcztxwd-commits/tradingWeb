package com.fxplatform.wallet.model;

import java.math.BigDecimal;
import java.util.UUID;

public record WalletReconciliationIssue(
    String code,
    UUID accountId,
    String walletType,
    String asset,
    BigDecimal expected,
    BigDecimal actual,
    String message
) {
}
