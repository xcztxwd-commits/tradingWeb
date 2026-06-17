package com.fxplatform.market.service;

import com.fxplatform.common.market.SymbolNormalizer;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class MarketSymbolFilter {

  private static final Pattern SEPARATOR = Pattern.compile("[,;\\s]+");

  private MarketSymbolFilter() {
  }

  public static Set<String> normalizeSymbols(String symbols) {
    if (symbols == null || symbols.isBlank()) {
      return Set.of();
    }
    return normalizeSymbols(List.of(symbols));
  }

  public static Set<String> normalizeSymbols(Collection<String> symbols) {
    if (symbols == null || symbols.isEmpty()) {
      return Set.of();
    }
    Set<String> normalized = symbols.stream()
        .filter(Objects::nonNull)
        .flatMap(value -> SEPARATOR.splitAsStream(value.trim()))
        .filter(value -> !value.isBlank())
        .map(SymbolNormalizer::normalize)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return Collections.unmodifiableSet(new LinkedHashSet<>(normalized));
  }

  public static boolean allows(Set<String> allowedSymbols, String symbol) {
    return allowedSymbols.isEmpty() || allowedSymbols.contains(SymbolNormalizer.normalize(symbol));
  }
}
