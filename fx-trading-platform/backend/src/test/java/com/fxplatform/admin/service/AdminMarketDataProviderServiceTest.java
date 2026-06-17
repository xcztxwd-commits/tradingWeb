package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminDataProviderRequest;
import com.fxplatform.admin.dto.request.AdminSymbolDisplayRequest;
import com.fxplatform.admin.dto.request.AdminSymbolProviderBindingRequest;
import com.fxplatform.market.adapter.binance.BinanceSpotMarketDataProvider;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.entity.DataProviderCapabilityEntity;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.ProviderInstrumentEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import com.fxplatform.market.enums.ProviderHealthStatus;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.MarketDataProviderAdapter;
import com.fxplatform.market.provider.ProviderRegistry;
import com.fxplatform.market.repository.DataProviderCapabilityRepository;
import com.fxplatform.market.repository.DataProviderRepository;
import com.fxplatform.market.repository.ProviderInstrumentRepository;
import com.fxplatform.market.repository.SymbolProviderBindingRepository;
import com.fxplatform.market.repository.SymbolRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
  void providerStatusToggleRequiresConfirmationBeforeSaving() {
    UUID providerId = UUID.randomUUID();
    DataProviderEntity provider = provider(providerId, "binance", "CRYPTO");
    provider.setEnabled(true);
    when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));

    assertThatThrownBy(() -> service(new FakeAdapter("binance", true)).updateProvider(
            providerId,
            new AdminDataProviderRequest(
                "binance",
                "binance",
                "REST",
                List.of("CRYPTO"),
                null,
                null,
                false,
                100,
                5000,
                1200,
                "{}")))
        .isInstanceOf(com.fxplatform.common.exception.BusinessException.class)
        .extracting("code")
        .isEqualTo("ADMIN_CONFIRMATION_REQUIRED");

    verify(providerRepository, never()).save(provider);
  }

  private HttpServer server;
  private ExecutorService executor;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

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
    assertThat(provider.getHealthStatus()).isEqualTo(ProviderHealthStatus.UP);
    verify(providerRepository).save(provider);
  }

  @Test
  void providersExposeHealthMetricsAndCapabilityStatuses() {
    UUID providerId = UUID.randomUUID();
    DataProviderEntity provider = provider(providerId, "binance", "CRYPTO");
    provider.setLastSuccessAt(Instant.parse("2026-06-16T01:02:03Z"));
    provider.setLastFailureAt(Instant.parse("2026-06-16T02:03:04Z"));
    provider.setFailureCount(3L);
    provider.setAvgLatencyMs(42L);
    provider.setQuoteStalenessMs(1250L);
    provider.setLastQuoteSuccessAt(Instant.parse("2026-06-16T03:04:05Z"));
    provider.setLastInstrumentSyncAt(Instant.parse("2026-06-16T04:05:06Z"));
    provider.setLastInstrumentSyncCount(1200);
    when(providerRepository.findAll()).thenReturn(List.of(provider));
    when(capabilityRepository.findByProviderId(providerId))
        .thenReturn(List.of(capability(providerId, MarketDataCapability.SYMBOLS)));

    var response = service(new FakeAdapter("binance", true)).providers().getFirst();

    assertThat(response.lastSuccessAt()).isEqualTo(Instant.parse("2026-06-16T01:02:03Z"));
    assertThat(response.lastFailureAt()).isEqualTo(Instant.parse("2026-06-16T02:03:04Z"));
    assertThat(response.failureCount()).isEqualTo(3);
    assertThat(response.avgLatencyMs()).isEqualTo(42);
    assertThat(response.quoteStalenessMs()).isEqualTo(1250);
    assertThat(response.lastQuoteSuccessAt()).isEqualTo(Instant.parse("2026-06-16T03:04:05Z"));
    assertThat(response.lastInstrumentSyncAt()).isEqualTo(Instant.parse("2026-06-16T04:05:06Z"));
    assertThat(response.lastInstrumentSyncCount()).isEqualTo(1200);
    assertThat(response.capabilityStatuses()).singleElement().satisfies(status -> {
      assertThat(status.capability()).isEqualTo("SYMBOLS");
      assertThat(status.enabled()).isTrue();
      assertThat(status.supported()).isTrue();
      assertThat(status.status()).isEqualTo("UP");
    });
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
  void syncProviderInstrumentsStoresProviderTradingRulesInRawJson() {
    UUID providerId = UUID.randomUUID();
    DataProviderEntity provider = provider(providerId, "binance", "CRYPTO");
    FakeAdapter adapter = new FakeAdapter("binance", true);
    adapter.symbols = List.of(new SymbolResponse(
        "BTCUSDT",
        "Bitcoin / Tether",
        "CRYPTO",
        "BTC",
        "USDT",
        new BigDecimal("0.00001000"),
        new BigDecimal("9000.00000000"),
        1,
        true,
        "binance",
        "BTCUSDT",
        true,
        null,
        true,
        true,
        true,
        true,
        false,
        null,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        """
        {"rules":{"tickSize":"0.01000000","stepSize":"0.00001000","minLot":"0.00001000","minNotional":"5.00000000"},"margin":{"marginTradingAllowed":true}}
        """));

    when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));
    when(instrumentRepository.findByProviderIdAndProviderSymbol(providerId, "BTCUSDT")).thenReturn(Optional.empty());

    service(adapter).syncInstruments(providerId);

    verify(instrumentRepository).save(org.mockito.ArgumentMatchers.argThat(instrument ->
        providerId.equals(instrument.getProviderId())
            && "BTCUSDT".equals(instrument.getProviderSymbol())
            && instrument.getRawJson().contains("\"tickSize\":\"0.01000000\"")
            && instrument.getRawJson().contains("\"stepSize\":\"0.00001000\"")
            && instrument.getRawJson().contains("\"minNotional\":\"5.00000000\"")));
    verify(symbolRepository, never()).save(org.mockito.ArgumentMatchers.any(SymbolEntity.class));
  }

  @Test
  void syncProviderInstrumentsIncludesDynamicBinanceSpotSymbols() throws IOException {
    UUID providerId = UUID.randomUUID();
    DataProviderEntity provider = provider(providerId, "binance", "CRYPTO");
    server = HttpServer.create(new InetSocketAddress(0), 0);
    executor = Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task);
      thread.setDaemon(true);
      return thread;
    });
    server.setExecutor(executor);
    server.createContext("/api/v3/exchangeInfo", exchange -> writeJson(exchange, """
        {
          "symbols": [
            {
              "symbol": "BCHUSDT",
              "status": "TRADING",
              "baseAsset": "BCH",
              "quoteAsset": "USDT",
              "isSpotTradingAllowed": true,
              "filters": [
                { "filterType": "PRICE_FILTER", "tickSize": "0.10000000" },
                { "filterType": "LOT_SIZE", "minQty": "0.00100000", "maxQty": "90000.00000000", "stepSize": "0.00100000" },
                { "filterType": "NOTIONAL", "minNotional": "5.00000000" }
              ]
            },
            {
              "symbol": "UNIUSDT",
              "status": "TRADING",
              "baseAsset": "UNI",
              "quoteAsset": "USDT",
              "isSpotTradingAllowed": true,
              "filters": []
            },
            {
              "symbol": "JTOUSDT",
              "status": "TRADING",
              "baseAsset": "JTO",
              "quoteAsset": "USDT",
              "isSpotTradingAllowed": true,
              "filters": []
            }
          ]
        }
        """));
    server.start();
    BinanceSpotMarketDataProvider adapter = new BinanceSpotMarketDataProvider(
        "http://127.0.0.1:" + server.getAddress().getPort());
    when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));
    when(instrumentRepository.findByProviderIdAndProviderSymbol(eq(providerId), anyString()))
        .thenReturn(Optional.empty());

    var response = service(adapter).syncInstruments(providerId);

    ArgumentCaptor<ProviderInstrumentEntity> instrumentCaptor = ArgumentCaptor.forClass(ProviderInstrumentEntity.class);
    verify(instrumentRepository, atLeast(1)).save(instrumentCaptor.capture());
    List<String> syncedSymbols = instrumentCaptor.getAllValues().stream()
        .map(ProviderInstrumentEntity::getProviderSymbol)
        .toList();
    assertThat(response.syncedCount()).isEqualTo(3);
    assertThat(syncedSymbols).contains("BCHUSDT", "UNIUSDT", "JTOUSDT");
    assertThat(instrumentCaptor.getAllValues()).filteredOn(instrument -> "BCHUSDT".equals(instrument.getProviderSymbol()))
        .singleElement()
        .satisfies(instrument -> {
          assertThat(instrument.getAssetClass()).isEqualTo("CRYPTO");
          assertThat(instrument.getBaseAsset()).isEqualTo("BCH");
          assertThat(instrument.getQuoteAsset()).isEqualTo("USDT");
          assertThat(instrument.getRawJson()).contains("\"tickSize\":\"0.10000000\"");
        });
  }

  @Test
  void syncEnabledProviderInstrumentsSkipsDisabledProvidersAndContinuesAfterFailure() {
    UUID firstProviderId = UUID.randomUUID();
    UUID disabledProviderId = UUID.randomUUID();
    UUID failingProviderId = UUID.randomUUID();
    UUID secondProviderId = UUID.randomUUID();
    DataProviderEntity firstProvider = provider(firstProviderId, "massive", "FOREX");
    DataProviderEntity disabledProvider = provider(disabledProviderId, "demo", "FOREX");
    disabledProvider.setEnabled(false);
    DataProviderEntity failingProvider = provider(failingProviderId, "missing", "FOREX");
    DataProviderEntity secondProvider = provider(secondProviderId, "binance", "CRYPTO");
    FakeAdapter massiveAdapter = new FakeAdapter("massive", true);
    massiveAdapter.symbols = List.of(new SymbolResponse(
        "EURUSD",
        "Euro / US Dollar",
        "FOREX",
        "EUR",
        "USD",
        new BigDecimal("0.01"),
        new BigDecimal("100"),
        100,
        true,
        "massive",
        "C:EURUSD",
        true));
    FakeAdapter binanceAdapter = new FakeAdapter("binance", true);
    binanceAdapter.symbols = List.of(new SymbolResponse(
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
    when(providerRepository.findAll()).thenReturn(List.of(
        firstProvider,
        disabledProvider,
        failingProvider,
        secondProvider));
    when(instrumentRepository.findByProviderIdAndProviderSymbol(firstProviderId, "C:EURUSD"))
        .thenReturn(Optional.empty());
    when(instrumentRepository.findByProviderIdAndProviderSymbol(secondProviderId, "BTCUSDT"))
        .thenReturn(Optional.empty());

    int syncedCount = service(massiveAdapter, binanceAdapter).syncEnabledProviderInstruments();

    assertThat(syncedCount).isEqualTo(2);
    assertThat(firstProvider.getHealthStatus()).isEqualTo(ProviderHealthStatus.UP);
    assertThat(disabledProvider.getHealthStatus()).isEqualTo(ProviderHealthStatus.UNKNOWN);
    assertThat(failingProvider.getHealthStatus()).isEqualTo(ProviderHealthStatus.DOWN);
    assertThat(secondProvider.getHealthStatus()).isEqualTo(ProviderHealthStatus.UP);
    verify(instrumentRepository).save(org.mockito.ArgumentMatchers.argThat(instrument ->
        firstProviderId.equals(instrument.getProviderId())
            && "C:EURUSD".equals(instrument.getProviderSymbol())));
    verify(instrumentRepository).save(org.mockito.ArgumentMatchers.argThat(instrument ->
        secondProviderId.equals(instrument.getProviderId())
            && "BTCUSDT".equals(instrument.getProviderSymbol())));
    verify(providerRepository, never()).save(disabledProvider);
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

  private DataProviderCapabilityEntity capability(UUID providerId, MarketDataCapability capability) {
    DataProviderCapabilityEntity entity = new DataProviderCapabilityEntity();
    entity.setId(UUID.randomUUID());
    entity.setProviderId(providerId);
    entity.setCapability(capability.name());
    entity.setEnabled(true);
    return entity;
  }

  private void writeJson(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
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
