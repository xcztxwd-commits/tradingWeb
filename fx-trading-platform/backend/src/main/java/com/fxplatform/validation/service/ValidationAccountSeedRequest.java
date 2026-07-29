package com.fxplatform.validation.service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ValidationAccountSeedRequest(
    UUID seedId,
    Map<String, BigDecimal> initialBalances
) {

  public ValidationAccountSeedRequest {
    if (initialBalances != null) {
      initialBalances = Collections.unmodifiableMap(new LinkedHashMap<>(initialBalances));
    }
  }
}
