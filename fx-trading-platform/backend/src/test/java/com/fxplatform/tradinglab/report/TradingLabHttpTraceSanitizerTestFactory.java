package com.fxplatform.tradinglab.report;

import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import java.util.List;

/** Test-only access to the package-private trusted trace construction seam. */
public final class TradingLabHttpTraceSanitizerTestFactory {

  private TradingLabHttpTraceSanitizerTestFactory() {
  }

  public static TradingLabHttpTraceSanitizer create(
      List<String> fixedSecrets,
      int maxTraceBytes
  ) {
    return new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(),
        fixedSecrets,
        maxTraceBytes);
  }
}
