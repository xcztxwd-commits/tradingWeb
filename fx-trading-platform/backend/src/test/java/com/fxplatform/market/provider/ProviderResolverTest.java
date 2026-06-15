package com.fxplatform.market.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import com.fxplatform.market.repository.DataProviderCapabilityRepository;
import com.fxplatform.market.repository.DataProviderRepository;
import com.fxplatform.market.repository.SymbolProviderBindingRepository;
import com.fxplatform.market.repository.SymbolRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProviderResolverTest {

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private SymbolProviderBindingRepository bindingRepository;

  @Mock
  private DataProviderRepository providerRepository;

  @Mock
  private DataProviderCapabilityRepository capabilityRepository;

  @Test
  void selectsLowestPriorityEnabledBinding() {
    UUID symbolId = UUID.randomUUID();
    UUID slowProviderId = UUID.randomUUID();
    UUID fastProviderId = UUID.randomUUID();
    SymbolEntity symbol = symbol(symbolId, true);
    SymbolProviderBindingEntity slow = binding(symbolId, slowProviderId, "C:EURUSD", 200, true);
    SymbolProviderBindingEntity fast = binding(symbolId, fastProviderId, "EUR_USD", 10, true);
    DataProviderEntity slowProvider = provider(slowProviderId, "massive", true);
    DataProviderEntity fastProvider = provider(fastProviderId, "okx", true);
    FakeAdapter slowAdapter = new FakeAdapter("massive", true, MarketDataCapability.QUOTE);
    FakeAdapter fastAdapter = new FakeAdapter("okx", true, MarketDataCapability.QUOTE);

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol));
    when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbolId)).thenReturn(List.of(fast, slow));
    when(providerRepository.findById(fastProviderId)).thenReturn(Optional.of(fastProvider));
    when(capabilityRepository.existsEnabledCapability(fastProviderId, MarketDataCapability.QUOTE)).thenReturn(true);

    ProviderResolver resolver = resolver(List.of(slowAdapter, fastAdapter));

    ProviderResolution resolution = resolver.resolve("eur-usd", MarketDataCapability.QUOTE);

    assertThat(resolution.provider()).isSameAs(fastProvider);
    assertThat(resolution.adapter()).isSameAs(fastAdapter);
    assertThat(resolution.providerSymbol()).isEqualTo("EUR_USD");
  }

  @Test
  void skipsDisabledBinding() {
    UUID symbolId = UUID.randomUUID();
    UUID disabledProviderId = UUID.randomUUID();
    UUID enabledProviderId = UUID.randomUUID();
    SymbolProviderBindingEntity enabled = binding(symbolId, enabledProviderId, "BTCUSDT", 20, true);
    DataProviderEntity enabledProvider = provider(enabledProviderId, "binance", true);
    FakeAdapter adapter = new FakeAdapter("binance", true, MarketDataCapability.QUOTE);

    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol(symbolId, true)));
    when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbolId))
        .thenReturn(List.of(enabled));
    when(providerRepository.findById(enabledProviderId)).thenReturn(Optional.of(enabledProvider));
    when(capabilityRepository.existsEnabledCapability(enabledProviderId, MarketDataCapability.QUOTE)).thenReturn(true);

    ProviderResolution resolution = resolver(List.of(adapter)).resolve("BTCUSDT", MarketDataCapability.QUOTE);

    assertThat(resolution.binding().getProviderId()).isEqualTo(enabledProviderId);
    assertThat(resolution.binding().getProviderId()).isNotEqualTo(disabledProviderId);
  }

  @Test
  void skipsProviderWithoutCapability() {
    UUID symbolId = UUID.randomUUID();
    UUID quoteProviderId = UUID.randomUUID();
    UUID chartProviderId = UUID.randomUUID();
    SymbolProviderBindingEntity quoteOnly = binding(symbolId, quoteProviderId, "C:EURUSD", 10, true);
    SymbolProviderBindingEntity chartCapable = binding(symbolId, chartProviderId, "EUR_USD", 20, true);
    DataProviderEntity quoteProvider = provider(quoteProviderId, "massive", true);
    DataProviderEntity chartProvider = provider(chartProviderId, "okx", true);
    FakeAdapter quoteAdapter = new FakeAdapter("massive", true, MarketDataCapability.QUOTE);
    FakeAdapter chartAdapter = new FakeAdapter("okx", true, MarketDataCapability.CANDLES);

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol(symbolId, true)));
    when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbolId))
        .thenReturn(List.of(quoteOnly, chartCapable));
    when(providerRepository.findById(quoteProviderId)).thenReturn(Optional.of(quoteProvider));
    when(capabilityRepository.existsEnabledCapability(quoteProviderId, MarketDataCapability.CANDLES)).thenReturn(false);
    when(providerRepository.findById(chartProviderId)).thenReturn(Optional.of(chartProvider));
    when(capabilityRepository.existsEnabledCapability(chartProviderId, MarketDataCapability.CANDLES)).thenReturn(true);

    ProviderResolution resolution = resolver(List.of(quoteAdapter, chartAdapter))
        .resolve("EURUSD", MarketDataCapability.CANDLES);

    assertThat(resolution.provider()).isSameAs(chartProvider);
    assertThat(resolution.adapter()).isSameAs(chartAdapter);
  }

  @Test
  void throwsWhenNoBindingSupportsCapability() {
    UUID symbolId = UUID.randomUUID();
    UUID providerId = UUID.randomUUID();
    SymbolProviderBindingEntity binding = binding(symbolId, providerId, "C:EURUSD", 10, true);
    DataProviderEntity provider = provider(providerId, "massive", true);
    FakeAdapter adapter = new FakeAdapter("massive", true, MarketDataCapability.QUOTE);

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol(symbolId, true)));
    when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbolId)).thenReturn(List.of(binding));
    when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));
    when(capabilityRepository.existsEnabledCapability(providerId, MarketDataCapability.CANDLES)).thenReturn(false);

    ProviderResolver resolver = resolver(List.of(adapter));

    assertThatThrownBy(() -> resolver.resolve("EURUSD", MarketDataCapability.CANDLES))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.getCode()).isEqualTo("MARKET_PROVIDER_BINDING_NOT_FOUND"));
  }

  @Test
  void rejectsDisabledPlatformSymbol() {
    UUID symbolId = UUID.randomUUID();
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol(symbolId, false)));

    assertThatThrownBy(() -> resolver(List.of()).resolve("EURUSD", MarketDataCapability.QUOTE))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.getCode()).isEqualTo("SYMBOL_NOT_ENABLED"));
  }

  private ProviderResolver resolver(List<MarketDataProviderAdapter> adapters) {
    return new ProviderResolver(
        symbolRepository,
        bindingRepository,
        providerRepository,
        capabilityRepository,
        new ProviderRegistry(adapters));
  }

  private SymbolEntity symbol(UUID id, boolean enabled) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(id);
    symbol.setSymbol("EURUSD");
    symbol.setEnabled(enabled);
    return symbol;
  }

  private DataProviderEntity provider(UUID id, String code, boolean enabled) {
    DataProviderEntity provider = new DataProviderEntity();
    provider.setId(id);
    provider.setCode(code);
    provider.setEnabled(enabled);
    return provider;
  }

  private SymbolProviderBindingEntity binding(
      UUID symbolId,
      UUID providerId,
      String providerSymbol,
      int priority,
      boolean enabled
  ) {
    SymbolProviderBindingEntity binding = new SymbolProviderBindingEntity();
    binding.setId(UUID.randomUUID());
    binding.setSymbolId(symbolId);
    binding.setProviderId(providerId);
    binding.setProviderSymbol(providerSymbol);
    binding.setPriority(priority);
    binding.setEnabled(enabled);
    return binding;
  }

  private record FakeAdapter(
      String code,
      boolean configured,
      Set<MarketDataCapability> capabilities
  ) implements MarketDataProviderAdapter {

    FakeAdapter(String code, boolean configured, MarketDataCapability... capabilities) {
      this(code, configured, Set.of(capabilities));
    }
  }
}
