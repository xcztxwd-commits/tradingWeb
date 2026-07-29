package com.fxplatform.validation.service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ValidationAccountSeedReceipt(
    UUID seedId,
    UUID accountId,
    String requestFingerprint,
    BigDecimal perpetualUsdtBalance,
    Map<String, BigDecimal> spotBalances
) {

  public ValidationAccountSeedReceipt {
    if (spotBalances != null) {
      spotBalances = Collections.unmodifiableMap(new LinkedHashMap<>(spotBalances));
    }
  }
}
