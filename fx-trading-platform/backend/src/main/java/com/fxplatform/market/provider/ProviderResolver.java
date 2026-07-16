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
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    SymbolEntity platformSymbol = requireEnabledSymbol(symbol);
    for (SymbolProviderBindingEntity binding
        : bindingRepository.findEnabledBySymbolIdOrderByPriority(platformSymbol.getId())) {
      ProviderResolution resolution = resolveBinding(platformSymbol, binding, capability);
      if (resolution != null) {
        return resolution;
      }
    }
    throw noBindingSupports(capability);
  }

  public List<ProviderResolution> resolveCandidates(String symbol, MarketDataCapability capability) {
    return resolveCandidates(symbol, Set.of(capability));
  }

  public List<ProviderResolution> resolveCandidates(
      String symbol,
      Set<MarketDataCapability> capabilities
  ) {
    SymbolEntity platformSymbol = requireEnabledSymbol(symbol);
    Set<MarketDataCapability> requiredCapabilities = copyRequiredCapabilities(capabilities);

    java.util.ArrayList<ProviderResolution> candidates = new java.util.ArrayList<>();
    for (SymbolProviderBindingEntity binding : bindingRepository.findEnabledBySymbolIdOrderByPriority(platformSymbol.getId())) {
      ProviderResolution resolution = resolveBinding(
          platformSymbol, binding, requiredCapabilities);
      if (resolution != null) {
        candidates.add(resolution);
      }
    }
    if (candidates.isEmpty()) {
      throw noBindingSupports(requiredCapabilities);
    }
    return List.copyOf(candidates);
  }

  public SymbolEntity requireEnabledSymbol(String symbol) {
    String normalizedSymbol = SymbolNormalizer.normalize(symbol);
    SymbolEntity platformSymbol = symbolRepository.findBySymbol(normalizedSymbol)
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    if (!Boolean.TRUE.equals(platformSymbol.getEnabled())) {
      throw new BusinessException("SYMBOL_NOT_ENABLED", "Symbol is disabled");
    }
    return platformSymbol;
  }

  public Map<String, ProviderResolution> resolveAll(Collection<String> symbols, MarketDataCapability capability) {
    List<String> normalizedSymbols = normalizeSymbols(symbols);
    if (normalizedSymbols.isEmpty()) {
      return Map.of();
    }

    Map<String, SymbolEntity> symbolsByCode = new LinkedHashMap<>();
    for (SymbolEntity symbol : symbolRepository.findBySymbols(normalizedSymbols)) {
      if (Boolean.TRUE.equals(symbol.getEnabled())) {
        symbolsByCode.put(symbol.getSymbol(), symbol);
      }
    }
    if (symbolsByCode.isEmpty()) {
      return Map.of();
    }

    List<UUID> symbolIds = symbolsByCode.values().stream()
        .map(SymbolEntity::getId)
        .toList();
    List<SymbolProviderBindingEntity> bindings = bindingRepository.findEnabledBySymbolIdsOrderByPriority(symbolIds);
    List<UUID> providerIds = bindings.stream()
        .map(SymbolProviderBindingEntity::getProviderId)
        .distinct()
        .toList();
    Map<UUID, DataProviderEntity> providersById = new LinkedHashMap<>();
    providerRepository.findByIds(providerIds)
        .forEach(provider -> providersById.put(provider.getId(), provider));
    Set<String> enabledCapabilities = capabilityRepository.findEnabledByProviderIds(providerIds).stream()
        .map(item -> capabilityKey(item.getProviderId(), item.getCapability()))
        .collect(java.util.stream.Collectors.toSet());
    Map<UUID, List<SymbolProviderBindingEntity>> bindingsBySymbolId = bindings.stream()
        .collect(java.util.stream.Collectors.groupingBy(
            SymbolProviderBindingEntity::getSymbolId,
            LinkedHashMap::new,
            java.util.stream.Collectors.toList()));

    Map<String, ProviderResolution> resolutions = new LinkedHashMap<>();
    for (String symbolCode : normalizedSymbols) {
      SymbolEntity symbol = symbolsByCode.get(symbolCode);
      if (symbol == null) {
        continue;
      }
      ProviderResolution resolution = resolveFirstAvailableBinding(
          symbol,
          bindingsBySymbolId.getOrDefault(symbol.getId(), List.of()),
          providersById,
          enabledCapabilities,
          capability);
      if (resolution != null) {
        resolutions.put(symbolCode, resolution);
      }
    }
    return Collections.unmodifiableMap(resolutions);
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
    return resolveBinding(symbol, binding, Set.of(capability));
  }

  private ProviderResolution resolveBinding(
      SymbolEntity symbol,
      SymbolProviderBindingEntity binding,
      Set<MarketDataCapability> capabilities
  ) {
    DataProviderEntity provider = providerRepository.findById(binding.getProviderId()).orElse(null);
    if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
      return null;
    }
    for (MarketDataCapability capability : capabilities) {
      if (!capabilityRepository.existsEnabledCapability(provider.getId(), capability)) {
        return null;
      }
    }
    return providerRegistry.find(provider.getCode())
        .filter(MarketDataProviderAdapter::configured)
        .filter(adapter -> supportsAll(adapter, capabilities))
        .map(adapter -> new ProviderResolution(symbol, provider, binding, adapter))
        .orElse(null);
  }

  private Set<MarketDataCapability> copyRequiredCapabilities(
      Set<MarketDataCapability> capabilities
  ) {
    if (capabilities == null || capabilities.isEmpty()) {
      throw new IllegalArgumentException("At least one market data capability is required");
    }
    return Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
  }

  private boolean supportsAll(
      MarketDataProviderAdapter adapter,
      Set<MarketDataCapability> capabilities
  ) {
    return capabilities.stream().allMatch(adapter::supports);
  }

  private BusinessException noBindingSupports(Object capabilities) {
    return new BusinessException(
        "MARKET_PROVIDER_BINDING_NOT_FOUND",
        "No enabled provider binding supports " + capabilities);
  }

  private ProviderResolution resolveFirstAvailableBinding(
      SymbolEntity symbol,
      List<SymbolProviderBindingEntity> bindings,
      Map<UUID, DataProviderEntity> providersById,
      Set<String> enabledCapabilities,
      MarketDataCapability capability
  ) {
    for (SymbolProviderBindingEntity binding : bindings) {
      DataProviderEntity provider = providersById.get(binding.getProviderId());
      if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
        continue;
      }
      if (!enabledCapabilities.contains(capabilityKey(provider.getId(), capability.name()))) {
        continue;
      }
      ProviderResolution resolution = providerRegistry.find(provider.getCode())
          .filter(MarketDataProviderAdapter::configured)
          .filter(adapter -> adapter.supports(capability))
          .map(adapter -> new ProviderResolution(symbol, provider, binding, adapter))
          .orElse(null);
      if (resolution != null) {
        return resolution;
      }
    }
    return null;
  }

  private List<String> normalizeSymbols(Collection<String> symbols) {
    if (symbols == null || symbols.isEmpty()) {
      return List.of();
    }
    return symbols.stream()
        .map(SymbolNormalizer::normalize)
        .filter(symbol -> !symbol.isBlank())
        .distinct()
        .toList();
  }

  private String capabilityKey(UUID providerId, String capability) {
    return providerId + ":" + capability;
  }
}
