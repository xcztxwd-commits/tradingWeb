package com.fxplatform.common.market;

import java.util.Locale;

public final class SymbolNormalizer {

  private SymbolNormalizer() {
  }

  public static String normalize(String symbol) {
    return symbol.replace("-", "").replace("_", "").replace("/", "").toUpperCase(Locale.ROOT);
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
}
