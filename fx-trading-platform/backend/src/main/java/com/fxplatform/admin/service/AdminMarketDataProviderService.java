package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.request.AdminDataProviderRequest;
import com.fxplatform.admin.dto.request.AdminSymbolDisplayRequest;
import com.fxplatform.admin.dto.request.AdminSymbolProviderBindingRequest;
import com.fxplatform.admin.dto.response.AdminDataProviderResponse;
import com.fxplatform.admin.dto.response.AdminProviderInstrumentResponse;
import com.fxplatform.admin.dto.response.AdminProviderSyncResponse;
import com.fxplatform.admin.dto.response.AdminSymbolProviderBindingResponse;
import com.fxplatform.admin.dto.response.AdminSymbolResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.entity.DataProviderCapabilityEntity;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.ProviderInstrumentEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import com.fxplatform.market.enums.ProviderHealthStatus;
import com.fxplatform.market.provider.MarketDataProviderAdapter;
import com.fxplatform.market.provider.ProviderRegistry;
import com.fxplatform.market.repository.DataProviderCapabilityRepository;
import com.fxplatform.market.repository.DataProviderRepository;
import com.fxplatform.market.repository.ProviderInstrumentRepository;
import com.fxplatform.market.repository.SymbolProviderBindingRepository;
import com.fxplatform.market.repository.SymbolRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminMarketDataProviderService {

  private final DataProviderRepository providerRepository;
  private final DataProviderCapabilityRepository capabilityRepository;
  private final ProviderInstrumentRepository instrumentRepository;
  private final SymbolProviderBindingRepository bindingRepository;
  private final SymbolRepository symbolRepository;
  private final ProviderRegistry providerRegistry;

  public List<AdminDataProviderResponse> providers() {
    return providerRepository.findAll().stream()
        .map(provider -> AdminDataProviderResponse.from(provider, capabilities(provider.getId())))
        .toList();
  }

  @Transactional
  public AdminDataProviderResponse createProvider(AdminDataProviderRequest request) {
    providerRepository.findByCode(normalizeCode(request.code()))
        .ifPresent(existing -> {
          throw new BusinessException("MARKET_PROVIDER_ALREADY_EXISTS", "Market provider already exists");
        });
    DataProviderEntity provider = new DataProviderEntity();
    provider.setCode(normalizeCode(request.code()));
    applyProvider(provider, request);
    DataProviderEntity saved = providerRepository.save(provider);
    return AdminDataProviderResponse.from(saved, capabilities(saved.getId()));
  }

  @Transactional
  public AdminDataProviderResponse updateProvider(UUID providerId, AdminDataProviderRequest request) {
    DataProviderEntity provider = findProvider(providerId);
    applyProvider(provider, request);
    DataProviderEntity saved = providerRepository.save(provider);
    return AdminDataProviderResponse.from(saved, capabilities(saved.getId()));
  }

  @Transactional
  public AdminDataProviderResponse testProvider(UUID providerId) {
    DataProviderEntity provider = findProvider(providerId);
    boolean configured = providerRegistry.find(provider.getCode())
        .map(MarketDataProviderAdapter::configured)
        .orElse(false);
    provider.setHealthStatus(ProviderHealthStatus.fromConfigured(configured));
    provider.setLastHealthCheckAt(Instant.now());
    DataProviderEntity saved = providerRepository.save(provider);
    return AdminDataProviderResponse.from(saved, capabilities(saved.getId()));
  }

  @Transactional
  public AdminProviderSyncResponse syncInstruments(UUID providerId) {
    DataProviderEntity provider = findProvider(providerId);
    return syncProviderInstruments(provider);
  }

  @Transactional
  public int syncEnabledProviderInstruments() {
    int count = 0;
    for (DataProviderEntity provider : providerRepository.findAll()) {
      if (!Boolean.TRUE.equals(provider.getEnabled())) {
        continue;
      }
      try {
        count += syncProviderInstruments(provider).syncedCount();
        provider.setHealthStatus(ProviderHealthStatus.UP);
      } catch (RuntimeException ex) {
        provider.setHealthStatus(ProviderHealthStatus.DOWN);
      }
      provider.setLastHealthCheckAt(Instant.now());
      providerRepository.save(provider);
    }
    return count;
  }

  private AdminProviderSyncResponse syncProviderInstruments(DataProviderEntity provider) {
    MarketDataProviderAdapter adapter = providerRegistry.find(provider.getCode())
        .orElseThrow(() -> new BusinessException("MARKET_PROVIDER_NOT_CONFIGURED", "Provider adapter not configured"));
    int count = 0;
    for (String assetClass : provider.getAssetClasses()) {
      for (SymbolResponse symbol : adapter.fetchSymbols(assetClass, 2000)) {
        upsertInstrument(provider.getId(), symbol);
        count++;
      }
    }
    return new AdminProviderSyncResponse(provider.getId(), count);
  }

  public List<AdminProviderInstrumentResponse> instruments(UUID providerId) {
    return instrumentRepository.findByProviderId(providerId).stream()
        .map(AdminProviderInstrumentResponse::from)
        .toList();
  }

  public List<AdminSymbolProviderBindingResponse> bindings(UUID symbolId) {
    return bindingRepository.findBySymbolId(symbolId).stream()
        .map(AdminSymbolProviderBindingResponse::from)
        .toList();
  }

  @Transactional
  public AdminSymbolProviderBindingResponse createBinding(UUID symbolId, AdminSymbolProviderBindingRequest request) {
    findSymbol(symbolId);
    findProvider(request.providerId());
    SymbolProviderBindingEntity binding = new SymbolProviderBindingEntity();
    binding.setSymbolId(symbolId);
    applyBinding(binding, request);
    return AdminSymbolProviderBindingResponse.from(bindingRepository.save(binding));
  }

  @Transactional
  public AdminSymbolProviderBindingResponse updateBinding(
      UUID symbolId,
      UUID bindingId,
      AdminSymbolProviderBindingRequest request
  ) {
    SymbolProviderBindingEntity binding = bindingRepository.findByIdAndSymbolId(bindingId, symbolId)
        .orElseThrow(() -> new BusinessException("MARKET_PROVIDER_BINDING_NOT_FOUND", "Provider binding not found"));
    applyBinding(binding, request);
    return AdminSymbolProviderBindingResponse.from(bindingRepository.save(binding));
  }

  @Transactional
  public AdminSymbolResponse updateSymbolDisplay(UUID symbolId, AdminSymbolDisplayRequest request) {
    SymbolEntity symbol = findSymbol(symbolId);
    symbol.setIconUrl(request.iconUrl());
    symbol.setDisplayEnabled(defaultBoolean(request.displayEnabled(), true));
    symbol.setQuoteEnabled(defaultBoolean(request.quoteEnabled(), true));
    symbol.setChartEnabled(defaultBoolean(request.chartEnabled(), true));
    symbol.setOrderBookEnabled(defaultBoolean(request.orderBookEnabled(), true));
    symbol.setTradable(defaultBoolean(request.tradable(), true));
    symbol.setFeatured(defaultBoolean(request.featured(), false));
    symbol.setDisplayGroup(request.displayGroup());
    symbol.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
    return AdminSymbolResponse.from(symbolRepository.save(symbol));
  }

  private void upsertInstrument(UUID providerId, SymbolResponse symbol) {
    ProviderInstrumentEntity instrument = instrumentRepository
        .findByProviderIdAndProviderSymbol(providerId, symbol.providerSymbol())
        .orElseGet(ProviderInstrumentEntity::new);
    instrument.setProviderId(providerId);
    instrument.setProviderSymbol(symbol.providerSymbol());
    instrument.setAssetClass(symbol.assetClass());
    instrument.setBaseAsset(symbol.baseCurrency());
    instrument.setQuoteAsset(symbol.quoteCurrency());
    instrument.setDisplayName(symbol.displayName());
    instrument.setListed(true);
    instrument.setRawJson(metadataJson(symbol.providerMetadataJson()));
    instrument.setLastSyncedAt(Instant.now());
    instrumentRepository.save(instrument);
  }

  private String metadataJson(String rawJson) {
    return rawJson == null || rawJson.isBlank() ? "{}" : rawJson;
  }

  private void applyProvider(DataProviderEntity provider, AdminDataProviderRequest request) {
    provider.setName(request.name());
    provider.setProviderType(request.providerType());
    provider.setAssetClasses(request.assetClasses() == null ? List.of() : request.assetClasses());
    provider.setRestBaseUrl(request.restBaseUrl());
    provider.setWsUrl(request.wsUrl());
    provider.setEnabled(defaultBoolean(request.enabled(), true));
    provider.setPriority(request.priority() == null ? 100 : request.priority());
    provider.setTimeoutMs(request.timeoutMs() == null ? 5000 : request.timeoutMs());
    provider.setRateLimitPerMinute(request.rateLimitPerMinute() == null ? 1200 : request.rateLimitPerMinute());
    provider.setConfigJson(request.configJson() == null || request.configJson().isBlank() ? "{}" : request.configJson());
  }

  private void applyBinding(SymbolProviderBindingEntity binding, AdminSymbolProviderBindingRequest request) {
    findProvider(request.providerId());
    binding.setProviderId(request.providerId());
    binding.setProviderInstrumentId(request.providerInstrumentId());
    binding.setProviderSymbol(request.providerSymbol());
    binding.setPriority(request.priority() == null ? 100 : request.priority());
    binding.setEnabled(defaultBoolean(request.enabled(), true));
    binding.setConfigJson(request.configJson() == null || request.configJson().isBlank() ? "{}" : request.configJson());
  }

  private List<String> capabilities(UUID providerId) {
    return capabilityRepository.findByProviderId(providerId).stream()
        .filter(capability -> Boolean.TRUE.equals(capability.getEnabled()))
        .map(DataProviderCapabilityEntity::getCapability)
        .toList();
  }

  private DataProviderEntity findProvider(UUID providerId) {
    return providerRepository.findById(providerId)
        .orElseThrow(() -> new BusinessException("MARKET_PROVIDER_NOT_FOUND", "Market provider not found"));
  }

  private SymbolEntity findSymbol(UUID symbolId) {
    return symbolRepository.findById(symbolId)
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
  }

  private boolean defaultBoolean(Boolean value, boolean fallback) {
    return value == null ? fallback : value;
  }

  private String normalizeCode(String code) {
    return code.trim().toLowerCase(Locale.ROOT);
  }
}
