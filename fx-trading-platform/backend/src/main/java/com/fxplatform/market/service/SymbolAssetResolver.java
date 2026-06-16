package com.fxplatform.market.service;

import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.SymbolAssets;
import java.util.List;
import java.util.Locale;

public final class SymbolAssetResolver {

  private static final List<String> KNOWN_QUOTES = List.of("USDT", "USDC", "USD", "JPY", "EUR", "GBP", "AUD", "CAD", "CHF");

  private SymbolAssetResolver() {
  }

  public static SymbolAssets resolve(SymbolEntity symbol) {
    String configuredBase = normalize(symbol.getBaseCurrency());
    String configuredQuote = normalize(symbol.getQuoteCurrency());
    if (!configuredBase.isBlank() && !configuredQuote.isBlank()) {
      return new SymbolAssets(configuredBase, configuredQuote);
    }

    String symbolCode = normalize(symbol.getSymbol());
    for (String quote : KNOWN_QUOTES) {
      if (symbolCode.endsWith(quote) && symbolCode.length() > quote.length()) {
        return new SymbolAssets(symbolCode.substring(0, symbolCode.length() - quote.length()), quote);
      }
    }
    return new SymbolAssets(configuredBase, configuredQuote);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
