package com.fxplatform.market.model;

import com.fxplatform.common.market.SymbolNormalizer;
import java.util.Set;

public final class MarketBundleProducts {

  private static final Set<String> SPOT = Set.of(
      "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT");
  private static final Set<String> PERPETUAL = Set.of(
      "BTCUSDT-PERP", "ETHUSDT-PERP", "BNBUSDT-PERP", "SOLUSDT-PERP", "XRPUSDT-PERP");

  private MarketBundleProducts() {
  }

  public static boolean isSpot(String symbol) {
    return SPOT.contains(SymbolNormalizer.normalize(symbol));
  }

  public static boolean isPerpetual(String symbol) {
    return PERPETUAL.contains(SymbolNormalizer.normalize(symbol));
  }

  public static boolean isP0(String symbol) {
    return isSpot(symbol) || isPerpetual(symbol);
  }
}
