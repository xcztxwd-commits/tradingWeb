package com.fxplatform.common.market;

import java.util.Locale;

public final class SymbolNormalizer {

  private SymbolNormalizer() {
  }

  public static String normalize(String symbol) {
    String upper = symbol.toUpperCase(Locale.ROOT);
    if (upper.endsWith("-PERP") || upper.endsWith("_PERP") || upper.endsWith("/PERP")) {
      String baseSymbol = upper.substring(0, upper.length() - "-PERP".length());
      return stripSeparators(baseSymbol) + "-PERP";
    }
    return stripSeparators(upper);
  }

  private static String stripSeparators(String symbol) {
    return symbol.replace("-", "").replace("_", "").replace("/", "");
  }

  public static String quoteTopic(String symbol) {
    return "/topic/market/quotes/" + normalize(symbol);
  }

  public static String orderBookTopic(String symbol) {
    return "/topic/market/order-book/" + normalize(symbol);
  }

  public static String tradesTopic(String symbol) {
    return "/topic/market/trades/" + normalize(symbol);
  }

  public static String perpetualReferenceTopic(String symbol) {
    return "/topic/market/perp-reference/" + normalize(symbol);
  }

  public static String sourceChangesTopic(String symbol) {
    return "/topic/market/source-changes/" + normalize(symbol);
  }
}
