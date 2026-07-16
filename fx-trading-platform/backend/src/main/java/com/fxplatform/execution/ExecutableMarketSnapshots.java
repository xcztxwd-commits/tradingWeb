package com.fxplatform.execution;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.model.ProductType;
import java.math.BigDecimal;

/** Shared structural validator for one complete executable provider bundle. */
public final class ExecutableMarketSnapshots {

  private ExecutableMarketSnapshots() {
  }

  public static void requireComplete(
      String platformSymbol,
      ProductType productType,
      ExecutableMarketSnapshot snapshot
  ) {
    if (snapshot == null
        || !normalize(platformSymbol).equals(normalize(snapshot.platformSymbol()))
        || productType != snapshot.productType()
        || blank(snapshot.providerCode())
        || blank(snapshot.providerSymbol())
        || snapshot.sourceMode() == null
        || snapshot.asOf() == null
        || snapshot.expiresAt() == null) {
      throw incomplete("Executable market snapshot does not match the request");
    }
    requirePositive(snapshot.bid(), "Executable bid is missing");
    requirePositive(snapshot.ask(), "Executable ask is missing");
    requirePositive(snapshot.last(), "Executable last is missing");
    if (productType == ProductType.LINEAR_PERP) {
      requirePositive(snapshot.mark(), "Perpetual mark price is missing");
      requirePositive(snapshot.index(), "Perpetual index price is missing");
    }
    if (snapshot.asOf().isAfter(snapshot.expiresAt())) {
      throw incomplete("Executable market snapshot timestamps are inconsistent");
    }
  }

  private static void requirePositive(BigDecimal value, String message) {
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw incomplete(message);
    }
  }

  private static BusinessException incomplete(String message) {
    return new BusinessException(ErrorCode.MARKET_BUNDLE_INCOMPLETE, message);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? "" : SymbolNormalizer.normalize(value.trim());
  }
}
