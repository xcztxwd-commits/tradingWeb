package com.fxplatform.market.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.entity.DataProviderCapabilityEntity;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import com.fxplatform.market.enums.ProviderHealthStatus;
import com.fxplatform.market.repository.DataProviderCapabilityRepository;
import com.fxplatform.market.repository.DataProviderRepository;
import com.fxplatform.market.repository.SymbolProviderBindingRepository;
import com.fxplatform.market.repository.SymbolRepository;
import java.util.List;
import java.util.Map;
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
  void legacyResolveReturnsPrimaryWhenSecondaryRepositoryLookupWouldFail() {
    UUID symbolId = UUID.randomUUID();
    UUID primaryProviderId = UUID.randomUUID();
    UUID secondaryProviderId = UUID.randomUUID();
    SymbolProviderBindingEntity primary = binding(
        symbolId, primaryProviderId, "BTCUSDT", 10, true);
    SymbolProviderBindingEntity secondary = binding(
        symbolId, secondaryProviderId, "BTC-USDT", 20, true);
    DataProviderEntity primaryProvider = provider(primaryProviderId, "binance", true);
    FakeAdapter primaryAdapter = new FakeAdapter(
        "binance", true, MarketDataCapability.QUOTE);

    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(symbol(symbolId, "BTCUSDT", true)));
    when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbolId))
        .thenReturn(List.of(primary, secondary));
    when(providerRepository.findById(any(UUID.class))).thenAnswer(invocation -> {
      UUID providerId = invocation.getArgument(0);
      if (providerId.equals(primaryProviderId)) {
        return Optional.of(primaryProvider);
      }
      throw new IllegalStateException("secondary repository unavailable");
    });
    when(capabilityRepository.existsEnabledCapability(
        primaryProviderId, MarketDataCapability.QUOTE)).thenReturn(true);

    ProviderResolution resolution = resolver(List.of(primaryAdapter))
        .resolve("BTCUSDT", MarketDataCapability.QUOTE);

    assertThat(resolution.provider()).isSameAs(primaryProvider);
    verify(providerRepository, never()).findById(secondaryProviderId);
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

  @Test
  void resolveAllUsesBatchRepositoriesAndSelectsCurrentBindingPerSymbol() {
    UUID eurusdId = UUID.randomUUID();
    UUID btcusdtId = UUID.randomUUID();
    UUID massiveId = UUID.randomUUID();
    UUID binanceId = UUID.randomUUID();
    SymbolEntity eurusd = symbol(eurusdId, "EURUSD", true);
    SymbolEntity btcusdt = symbol(btcusdtId, "BTCUSDT", true);
    SymbolProviderBindingEntity eurusdBinding = binding(eurusdId, massiveId, "C:EURUSD", 10, true);
    SymbolProviderBindingEntity btcusdtBinding = binding(btcusdtId, binanceId, "BTCUSDT", 10, true);
    DataProviderEntity massive = provider(massiveId, "massive", true);
    DataProviderEntity binance = provider(binanceId, "binance", true);
    DataProviderCapabilityEntity massiveQuote = capability(massiveId, MarketDataCapability.QUOTE);
    DataProviderCapabilityEntity binanceQuote = capability(binanceId, MarketDataCapability.QUOTE);
    FakeAdapter massiveAdapter = new FakeAdapter("massive", true, MarketDataCapability.QUOTE);
    FakeAdapter binanceAdapter = new FakeAdapter("binance", true, MarketDataCapability.QUOTE);

    when(symbolRepository.findBySymbols(List.of("EURUSD", "BTCUSDT"))).thenReturn(List.of(eurusd, btcusdt));
    when(bindingRepository.findEnabledBySymbolIdsOrderByPriority(List.of(eurusdId, btcusdtId)))
        .thenReturn(List.of(eurusdBinding, btcusdtBinding));
    when(providerRepository.findByIds(List.of(massiveId, binanceId))).thenReturn(List.of(massive, binance));
    when(capabilityRepository.findEnabledByProviderIds(List.of(massiveId, binanceId)))
        .thenReturn(List.of(massiveQuote, binanceQuote));

    Map<String, ProviderResolution> resolutions = resolver(List.of(massiveAdapter, binanceAdapter))
        .resolveAll(List.of("eur-usd", "BTCUSDT", "EURUSD"), MarketDataCapability.QUOTE);

    assertThat(resolutions).containsOnlyKeys("EURUSD", "BTCUSDT");
    assertThat(resolutions.get("EURUSD").provider()).isSameAs(massive);
    assertThat(resolutions.get("EURUSD").providerSymbol()).isEqualTo("C:EURUSD");
    assertThat(resolutions.get("BTCUSDT").provider()).isSameAs(binance);
    assertThat(resolutions.get("BTCUSDT").providerSymbol()).isEqualTo("BTCUSDT");
  }

  @Test
  void resolveCandidatesKeepsDownPrimaryEligibleForRecoveryProbe() {
    UUID symbolId = UUID.randomUUID();
    UUID binanceId = UUID.randomUUID();
    UUID okxId = UUID.randomUUID();
    SymbolEntity symbol = symbol(symbolId, "BTCUSDT", true);
    SymbolProviderBindingEntity binanceBinding = binding(symbolId, binanceId, "BTCUSDT", 10, true);
    SymbolProviderBindingEntity okxBinding = binding(symbolId, okxId, "BTC-USDT", 20, true);
    DataProviderEntity binance = provider(binanceId, "binance", true);
    binance.setHealthStatus(ProviderHealthStatus.DOWN);
    DataProviderEntity okx = provider(okxId, "okx", true);

    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbolId))
        .thenReturn(List.of(binanceBinding, okxBinding));
    when(providerRepository.findById(binanceId)).thenReturn(Optional.of(binance));
    when(providerRepository.findById(okxId)).thenReturn(Optional.of(okx));
    when(capabilityRepository.existsEnabledCapability(binanceId, MarketDataCapability.QUOTE)).thenReturn(true);
    when(capabilityRepository.existsEnabledCapability(okxId, MarketDataCapability.QUOTE)).thenReturn(true);

    List<ProviderResolution> candidates = resolver(List.of(
        new FakeAdapter("binance", true, MarketDataCapability.QUOTE),
        new FakeAdapter("okx", true, MarketDataCapability.QUOTE)))
        .resolveCandidates("BTCUSDT", MarketDataCapability.QUOTE);

    assertThat(candidates).extracting(candidate -> candidate.provider().getCode())
        .containsExactly("binance", "okx");
    assertThat(candidates.getFirst().provider().getHealthStatus()).isEqualTo(ProviderHealthStatus.DOWN);
  }

  @Test
  void resolveCandidatesRequiresEveryDatabaseAndAdapterCapability() {
    UUID symbolId = UUID.randomUUID();
    UUID databaseIncompleteId = UUID.randomUUID();
    UUID adapterIncompleteId = UUID.randomUUID();
    UUID completeId = UUID.randomUUID();
    SymbolProviderBindingEntity databaseIncompleteBinding = binding(
        symbolId, databaseIncompleteId, "BTCUSDT", 10, true);
    SymbolProviderBindingEntity adapterIncompleteBinding = binding(
        symbolId, adapterIncompleteId, "BTC-USDT", 20, true);
    SymbolProviderBindingEntity completeBinding = binding(
        symbolId, completeId, "BTCUSDT", 30, true);
    DataProviderEntity databaseIncomplete = provider(databaseIncompleteId, "binance", true);
    DataProviderEntity adapterIncomplete = provider(adapterIncompleteId, "okx", true);
    DataProviderEntity complete = provider(completeId, "local-spot", true);
    Set<MarketDataCapability> bundleCapabilities = Set.of(
        MarketDataCapability.QUOTE,
        MarketDataCapability.CANDLES,
        MarketDataCapability.ORDER_BOOK,
        MarketDataCapability.TRADES);

    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(symbol(symbolId, "BTCUSDT", true)));
    when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbolId))
        .thenReturn(List.of(
            databaseIncompleteBinding, adapterIncompleteBinding, completeBinding));
    when(providerRepository.findById(databaseIncompleteId))
        .thenReturn(Optional.of(databaseIncomplete));
    when(providerRepository.findById(adapterIncompleteId))
        .thenReturn(Optional.of(adapterIncomplete));
    when(providerRepository.findById(completeId)).thenReturn(Optional.of(complete));
    when(capabilityRepository.existsEnabledCapability(any(UUID.class), any()))
        .thenAnswer(invocation -> {
          UUID providerId = invocation.getArgument(0);
          MarketDataCapability capability = invocation.getArgument(1);
          return !providerId.equals(databaseIncompleteId)
              || capability != MarketDataCapability.TRADES;
        });

    List<ProviderResolution> candidates = resolver(List.of(
        new FakeAdapter("binance", true,
            MarketDataCapability.QUOTE,
            MarketDataCapability.CANDLES,
            MarketDataCapability.ORDER_BOOK,
            MarketDataCapability.TRADES),
        new FakeAdapter("okx", true,
            MarketDataCapability.QUOTE,
            MarketDataCapability.ORDER_BOOK,
            MarketDataCapability.TRADES),
        new FakeAdapter("local-spot", true,
            MarketDataCapability.QUOTE,
            MarketDataCapability.CANDLES,
            MarketDataCapability.ORDER_BOOK,
            MarketDataCapability.TRADES)))
        .resolveCandidates("BTCUSDT", bundleCapabilities);

    assertThat(candidates).extracting(candidate -> candidate.provider().getCode())
        .containsExactly("local-spot");
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
    return symbol(id, "EURUSD", enabled);
  }

  private SymbolEntity symbol(UUID id, String symbolCode, boolean enabled) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(id);
    symbol.setSymbol(symbolCode);
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

  private DataProviderCapabilityEntity capability(UUID providerId, MarketDataCapability capability) {
    DataProviderCapabilityEntity entity = new DataProviderCapabilityEntity();
    entity.setId(UUID.randomUUID());
    entity.setProviderId(providerId);
    entity.setCapability(capability.name());
    entity.setEnabled(true);
    return entity;
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
