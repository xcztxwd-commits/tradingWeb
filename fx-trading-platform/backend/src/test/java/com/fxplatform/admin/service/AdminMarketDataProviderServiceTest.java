package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminSymbolDisplayRequest;
import com.fxplatform.admin.dto.request.AdminSymbolProviderBindingRequest;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.ProviderInstrumentEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.MarketDataProviderAdapter;
import com.fxplatform.market.provider.ProviderRegistry;
import com.fxplatform.market.repository.DataProviderCapabilityRepository;
import com.fxplatform.market.repository.DataProviderRepository;
import com.fxplatform.market.repository.ProviderInstrumentRepository;
import com.fxplatform.market.repository.SymbolProviderBindingRepository;
import com.fxplatform.market.repository.SymbolRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminMarketDataProviderServiceTest {

  @Mock
  private DataProviderRepository providerRepository;

  @Mock
  private DataProviderCapabilityRepository capabilityRepository;

  @Mock
  private ProviderInstrumentRepository instrumentRepository;

  @Mock
  private SymbolProviderBindingRepository bindingRepository;

  @Mock
  private SymbolRepository symbolRepository;

  @Test
  void testProviderUpdatesHealthFromAdapterConfiguration() {
    UUID providerId = UUID.randomUUID();
    DataProviderEntity provider = provider(providerId, "binance", "CRYPTO");
    FakeAdapter adapter = new FakeAdapter("binance", true);
    when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));
    when(providerRepository.save(provider)).thenReturn(provider);

    AdminMarketDataProviderService service = service(adapter);

    var response = service.testProvider(providerId);

    assertThat(response.healthStatus()).isEqualTo("UP");
    assertThat(provider.getHealthStatus()).isEqualTo("UP");
    verify(providerRepository).save(provider);
  }

  @Test
  void syncProviderInstrumentsUpsertsFetchedSymbolsWithoutPublishingPlatformSymbols() {
    UUID providerId = UUID.randomUUID();
    DataProviderEntity provider = provider(providerId, "binance", "CRYPTO");
    FakeAdapter adapter = new FakeAdapter("binance", true);
    adapter.symbols = List.of(new SymbolResponse(
        "BTCUSDT",
        "Bitcoin / Tether",
        "CRYPTO",
        "BTC",
        "USDT",
        new BigDecimal("0.0001"),
        new BigDecimal("100"),
        20,
        true,
        "binance",
        "BTCUSDT",
        true));

    when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));
    when(instrumentRepository.findByProviderIdAndProviderSymbol(providerId, "BTCUSDT")).thenReturn(Optional.empty());

    var response = service(adapter).syncInstruments(providerId);

    assertThat(response.syncedCount()).isEqualTo(1);
    verify(instrumentRepository).save(org.mockito.ArgumentMatchers.argThat(instrument ->
        "BTCUSDT".equals(instrument.getProviderSymbol())
            && "CRYPTO".equals(instrument.getAssetClass())
            && providerId.equals(instrument.getProviderId())));
    verify(symbolRepository, never()).save(org.mockito.ArgumentMatchers.any(SymbolEntity.class));
  }

  @Test
  void createBindingPersistsProviderSymbolMapping() {
    UUID symbolId = UUID.randomUUID();
    UUID providerId = UUID.randomUUID();
    UUID instrumentId = UUID.randomUUID();
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(symbolId);
    when(symbolRepository.findById(symbolId)).thenReturn(Optional.of(symbol));
    when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider(providerId, "okx", "CRYPTO")));
    when(bindingRepository.save(org.mockito.ArgumentMatchers.any(SymbolProviderBindingEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var request = new AdminSymbolProviderBindingRequest(providerId, instrumentId, "BTC-USDT", 50, true, "{}");

    service().createBinding(symbolId, request);

    verify(bindingRepository).save(org.mockito.ArgumentMatchers.argThat(binding ->
        symbolId.equals(binding.getSymbolId())
            && providerId.equals(binding.getProviderId())
            && instrumentId.equals(binding.getProviderInstrumentId())
            && "BTC-USDT".equals(binding.getProviderSymbol())
            && binding.getPriority().equals(50)));
  }

  @Test
  void updateSymbolDisplayPersistsDisplaySwitches() {
    UUID symbolId = UUID.randomUUID();
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(symbolId);
    symbol.setSymbol("EURUSD");
    when(symbolRepository.findById(symbolId)).thenReturn(Optional.of(symbol));
    when(symbolRepository.save(symbol)).thenReturn(symbol);

    var request = new AdminSymbolDisplayRequest(
        "https://cdn.example.com/eurusd.png",
        true,
        false,
        true,
        false,
        true,
        true,
        "Majors",
        12);

    var response = service().updateSymbolDisplay(symbolId, request);

    assertThat(response.quoteEnabled()).isFalse();
    assertThat(response.displayOrder()).isEqualTo(12);
    verify(symbolRepository).save(symbol);
  }

  private AdminMarketDataProviderService service(MarketDataProviderAdapter... adapters) {
    return new AdminMarketDataProviderService(
        providerRepository,
        capabilityRepository,
        instrumentRepository,
        bindingRepository,
        symbolRepository,
        new ProviderRegistry(List.of(adapters)));
  }

  private DataProviderEntity provider(UUID id, String code, String assetClass) {
    DataProviderEntity provider = new DataProviderEntity();
    provider.setId(id);
    provider.setCode(code);
    provider.setName(code);
    provider.setProviderType("REST");
    provider.setAssetClasses(List.of(assetClass));
    provider.setEnabled(true);
    provider.setHealthStatus("UNKNOWN");
    return provider;
  }

  private static final class FakeAdapter implements MarketDataProviderAdapter {
    private final String code;
    private final boolean configured;
    private List<SymbolResponse> symbols = List.of();

    private FakeAdapter(String code, boolean configured) {
      this.code = code;
      this.configured = configured;
    }

    @Override
    public String code() {
      return code;
    }

    @Override
    public Set<MarketDataCapability> capabilities() {
      return Set.of(MarketDataCapability.SYMBOLS);
    }

    @Override
    public boolean configured() {
      return configured;
    }

    @Override
    public List<SymbolResponse> fetchSymbols(String assetClass, int limit) {
      return symbols;
    }
  }
}
