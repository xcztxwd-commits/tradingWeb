package com.fxplatform.market.provider;

import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;

public record ProviderResolution(
    SymbolEntity symbol,
    DataProviderEntity provider,
    SymbolProviderBindingEntity binding,
    MarketDataProviderAdapter adapter
) {
  public String providerSymbol() {
    return binding.getProviderSymbol();
  }
}
