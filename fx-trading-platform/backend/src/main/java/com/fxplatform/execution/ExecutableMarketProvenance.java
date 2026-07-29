package com.fxplatform.execution;

import com.fxplatform.market.model.MarketSourceMode;

/** Exact, fail-closed executable-market provenance classification. */
public final class ExecutableMarketProvenance {

  private static final String VALIDATION_PROVIDER = "validation";

  private ExecutableMarketProvenance() {
  }

  public static boolean validationMarked(String providerCode) {
    return providerCode != null
        && VALIDATION_PROVIDER.equalsIgnoreCase(providerCode.strip());
  }

  public static boolean exactValidation(
      String providerCode,
      MarketSourceMode sourceMode
  ) {
    return VALIDATION_PROVIDER.equals(providerCode)
        && sourceMode == MarketSourceMode.LOCAL_SIMULATED;
  }
}
