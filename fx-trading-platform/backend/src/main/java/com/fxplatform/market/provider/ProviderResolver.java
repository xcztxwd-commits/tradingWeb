package com.fxplatform.market.provider;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import com.fxplatform.market.repository.DataProviderCapabilityRepository;
import com.fxplatform.market.repository.DataProviderRepository;
import com.fxplatform.market.repository.SymbolProviderBindingRepository;
import com.fxplatform.market.repository.SymbolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProviderResolver {

  private final SymbolRepository symbolRepository;
  private final SymbolProviderBindingRepository bindingRepository;
  private final DataProviderRepository providerRepository;
  private final DataProviderCapabilityRepository capabilityRepository;
  private final ProviderRegistry providerRegistry;

  public ProviderResolution resolve(String symbol, MarketDataCapability capability) {
    String normalizedSymbol = SymbolNormalizer.normalize(symbol);
    SymbolEntity platformSymbol = symbolRepository.findBySymbol(normalizedSymbol)
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    if (!Boolean.TRUE.equals(platformSymbol.getEnabled())) {
      throw new BusinessException("SYMBOL_NOT_ENABLED", "Symbol is disabled");
    }

    for (SymbolProviderBindingEntity binding : bindingRepository.findEnabledBySymbolIdOrderByPriority(platformSymbol.getId())) {
      ProviderResolution resolution = resolveBinding(platformSymbol, binding, capability);
      if (resolution != null) {
        return resolution;
      }
    }

    throw new BusinessException("MARKET_PROVIDER_BINDING_NOT_FOUND", "No enabled provider binding supports " + capability);
  }

  public boolean canResolve(SymbolEntity platformSymbol, MarketDataCapability capability) {
    if (platformSymbol == null || !Boolean.TRUE.equals(platformSymbol.getEnabled())) {
      return false;
    }
    for (SymbolProviderBindingEntity binding : bindingRepository.findEnabledBySymbolIdOrderByPriority(platformSymbol.getId())) {
      if (resolveBinding(platformSymbol, binding, capability) != null) {
        return true;
      }
    }
    return false;
  }

  private ProviderResolution resolveBinding(
      SymbolEntity symbol,
      SymbolProviderBindingEntity binding,
      MarketDataCapability capability
  ) {
    DataProviderEntity provider = providerRepository.findById(binding.getProviderId()).orElse(null);
    if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
      return null;
    }
    if (!capabilityRepository.existsEnabledCapability(provider.getId(), capability)) {
      return null;
    }
    return providerRegistry.find(provider.getCode())
        .filter(MarketDataProviderAdapter::configured)
        .filter(adapter -> adapter.supports(capability))
        .map(adapter -> new ProviderResolution(symbol, provider, binding, adapter))
        .orElse(null);
  }
}
