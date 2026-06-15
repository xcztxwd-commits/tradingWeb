package com.fxplatform.market.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ProviderRegistry {

  private final Map<String, MarketDataProviderAdapter> adaptersByCode;

  public ProviderRegistry(List<MarketDataProviderAdapter> adapters) {
    Map<String, MarketDataProviderAdapter> adaptersByCode = new LinkedHashMap<>();
    for (MarketDataProviderAdapter adapter : adapters) {
      String code = normalize(adapter.code());
      MarketDataProviderAdapter previous = adaptersByCode.putIfAbsent(code, adapter);
      if (previous != null) {
        throw new IllegalStateException("Duplicate market data provider adapter: " + adapter.code());
      }
    }
    this.adaptersByCode = Map.copyOf(adaptersByCode);
  }

  public Optional<MarketDataProviderAdapter> find(String code) {
    return Optional.ofNullable(adaptersByCode.get(normalize(code)));
  }

  private String normalize(String code) {
    return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
  }
}
