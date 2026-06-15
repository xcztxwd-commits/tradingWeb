package com.fxplatform.market.service;

import com.fxplatform.common.market.SymbolNormalizer;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class MarketSymbolFilter {

  private static final Pattern SEPARATOR = Pattern.compile("[,;\\s]+");

  private MarketSymbolFilter() {
  }

  static Set<String> normalizeSymbols(String symbols) {
    if (symbols == null || symbols.isBlank()) {
      return Set.of();
    }
    return SEPARATOR.splitAsStream(symbols.trim())
        .filter(value -> !value.isBlank())
        .map(SymbolNormalizer::normalize)
        .collect(Collectors.toUnmodifiableSet());
  }

  static boolean allows(Set<String> allowedSymbols, String symbol) {
    return allowedSymbols.isEmpty() || allowedSymbols.contains(SymbolNormalizer.normalize(symbol));
  }
}
