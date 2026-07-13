package com.fxplatform.market.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import com.fxplatform.market.repository.DataProviderCapabilityRepository;
import com.fxplatform.market.repository.DataProviderRepository;
import com.fxplatform.market.repository.SymbolProviderBindingRepository;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.repository.FundingRateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketDataRouterTest {

  @Mock
  private ProviderResolver providerResolver;

  @Mock
  private MarketBundleResolver marketBundleResolver;

  @Mock
  private FundingRateRepository fundingRateRepository;

  @Test
  void p0SpotComponentsAlwaysComeFromAuthoritativeWholeBundle() {
    Instant now = Instant.parse("2026-07-12T00:00:10Z");
    SpotMarketBundle bundle = spotBundle(now);
    when(providerResolver.requireEnabledSymbol("BTCUSDT")).thenReturn(enabledSymbol("BTCUSDT"));
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle);
    MarketDataRouter router = new MarketDataRouter(
        providerResolver, marketBundleResolver, Clock.fixed(now, ZoneOffset.UTC));

    QuoteResponse quote = router.latestQuote("BTCUSDT");
    MarketDepthResponse depth = router.orderBook("BTCUSDT");
    List<RecentTradeResponse> trades = router.recentTrades("BTCUSDT", 10);
    List<CandleResponse> candles = router.candles(
        "BTCUSDT", "1m", now.minus(Duration.ofHours(1)), now);

    assertThat(quote.providerCode()).isEqualTo("okx");
    assertThat(quote.sourceMode()).isEqualTo(MarketSourceMode.PUBLIC_EXTERNAL);
    assertThat(quote.asOf()).isEqualTo(bundle.asOf());
    assertThat(depth).isSameAs(bundle.orderBook());
    assertThat(trades).containsExactlyElementsOf(bundle.recentTrades());
    assertThat(candles).containsExactlyElementsOf(bundle.candles());
  }

  @Test
  void p0PerpetualReferenceKeepsMarkIndexAndSourceMetadata() {
    Instant now = Instant.parse("2026-07-12T00:00:10Z");
    PerpetualMarketBundle bundle = perpBundle(now);
    when(providerResolver.requireEnabledSymbol("BTCUSDT-PERP")).thenReturn(enabledSymbol("BTCUSDT-PERP"));
    when(marketBundleResolver.resolvePerp(eq("BTCUSDT-PERP"), any())).thenReturn(bundle);
    MarketDataRouter router = new MarketDataRouter(
        providerResolver, marketBundleResolver, Clock.fixed(now, ZoneOffset.UTC));

    var reference = router.perpetualReference("BTCUSDT-PERP");

    assertThat(reference.symbol()).isEqualTo("BTCUSDT-PERP");
    assertThat(reference.providerSymbol()).isEqualTo("BTC-USDT-SWAP");
    assertThat(reference.providerCode()).isEqualTo("okx-swap");
    assertThat(reference.mark()).isEqualByComparingTo("100.2");
    assertThat(reference.index()).isEqualByComparingTo("100.1");
    assertThat(reference.stale()).isFalse();
  }

  @Test
  void p0PerpetualReferenceProjectsLatestFundingCycleWithoutReplacingMarketSource() {
    Instant now = Instant.parse("2026-07-12T00:00:10Z");
    Instant fundingTime = Instant.parse("2026-07-12T00:00:00Z");
    Instant nextFundingTime = Instant.parse("2026-07-12T08:00:00Z");
    PerpetualMarketBundle bundle = perpBundle(now);
    FundingRateEntity funding = new FundingRateEntity();
    funding.setSymbol("BTCUSDT-PERP");
    funding.setFundingRate(new BigDecimal("-0.00025"));
    funding.setFundingTime(fundingTime);
    funding.setNextFundingTime(nextFundingTime);
    funding.setProviderCode("fixed");
    when(providerResolver.requireEnabledSymbol("BTCUSDT-PERP"))
        .thenReturn(enabledSymbol("BTCUSDT-PERP"));
    when(marketBundleResolver.resolvePerp(eq("BTCUSDT-PERP"), any())).thenReturn(bundle);
    when(fundingRateRepository.findLatestBySymbol("BTCUSDT-PERP"))
        .thenReturn(Optional.of(funding));
    MarketDataRouter router = new MarketDataRouter(
        providerResolver,
        marketBundleResolver,
        fundingRateRepository,
        Clock.fixed(now, ZoneOffset.UTC));

    var reference = router.perpetualReference("BTCUSDT-PERP");

    assertThat(reference.providerCode()).isEqualTo("okx-swap");
    assertThat(reference.sourceMode()).isEqualTo(MarketSourceMode.PUBLIC_EXTERNAL);
    assertThat(reference.fundingRate()).isEqualByComparingTo("-0.00025");
    assertThat(reference.fundingTime()).isEqualTo(fundingTime);
    assertThat(reference.nextFundingTime()).isEqualTo(nextFundingTime);
    assertThat(reference.fundingSource()).isEqualTo("fixed");
    verify(fundingRateRepository).findLatestBySymbol("BTCUSDT-PERP");
  }

  @Test
  void p0EndpointsPreserveQuoteChartAndOrderBookFeatureFlags() {
    Instant now = Instant.parse("2026-07-12T00:00:10Z");
    SymbolEntity symbol = enabledSymbol("BTCUSDT");
    when(providerResolver.requireEnabledSymbol("BTCUSDT")).thenReturn(symbol);
    MarketDataRouter router = new MarketDataRouter(
        providerResolver, marketBundleResolver, Clock.fixed(now, ZoneOffset.UTC));

    symbol.setQuoteEnabled(false);
    assertThatThrownBy(() -> router.latestQuote("BTCUSDT"))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("SYMBOL_QUOTE_DISABLED"));

    symbol.setQuoteEnabled(true);
    symbol.setChartEnabled(false);
    assertThatThrownBy(() -> router.candles("BTCUSDT", "1m", now.minusSeconds(60), now))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("SYMBOL_CHART_DISABLED"));

    symbol.setChartEnabled(true);
    symbol.setOrderBookEnabled(false);
    assertThatThrownBy(() -> router.orderBook("BTCUSDT"))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("SYMBOL_ORDER_BOOK_DISABLED"));
  }

  @Test
  void p0TradesKeepLegacyEnabledSymbolSemanticsWithoutAnotherFeatureFlag() {
    Instant now = Instant.parse("2026-07-12T00:00:10Z");
    SymbolEntity symbol = enabledSymbol("BTCUSDT");
    symbol.setQuoteEnabled(false);
    symbol.setChartEnabled(false);
    symbol.setOrderBookEnabled(false);
    when(providerResolver.requireEnabledSymbol("BTCUSDT")).thenReturn(symbol);
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(spotBundle(now));
    MarketDataRouter router = new MarketDataRouter(
        providerResolver, marketBundleResolver, Clock.fixed(now, ZoneOffset.UTC));

    assertThat(router.recentTrades("BTCUSDT", 10)).hasSize(1);
  }

  @Test
  void latestQuoteRejectsQuoteDisabledSymbol() {
    ProviderResolution resolution = resolution(adapter(MarketDataCapability.QUOTE), symbol(false, true, true), "C:EURUSD");
    when(providerResolver.resolve("EURUSD", MarketDataCapability.QUOTE)).thenReturn(resolution);

    MarketDataRouter router = new MarketDataRouter(providerResolver);

    assertThatThrownBy(() -> router.latestQuote("EURUSD"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.getCode()).isEqualTo("SYMBOL_QUOTE_DISABLED"));
  }

  @Test
  void latestQuoteReturnsProviderQuote() {
    QuoteResponse quote = quote("EURUSD", "massive-quote");
    MarketDataProviderAdapter adapter = adapter(MarketDataCapability.QUOTE);
    ProviderResolution resolution = resolution(adapter, symbol(true, true, true), "C:EURUSD");
    when(providerResolver.resolve("EURUSD", MarketDataCapability.QUOTE)).thenReturn(resolution);
    when(adapter.fetchLatestQuote("EURUSD", "C:EURUSD")).thenReturn(Optional.of(quote));

    QuoteResponse result = new MarketDataRouter(providerResolver).latestQuote("EURUSD");

    assertThat(result).isSameAs(quote);
  }

  @Test
  void candlesRejectChartDisabledSymbol() {
    ProviderResolution resolution = resolution(adapter(MarketDataCapability.CANDLES), symbol(true, false, true), "C:EURUSD");
    when(providerResolver.resolve("EURUSD", MarketDataCapability.CANDLES)).thenReturn(resolution);

    MarketDataRouter router = new MarketDataRouter(providerResolver);

    assertThatThrownBy(() -> router.candles("EURUSD", "5m", Instant.EPOCH, Instant.EPOCH.plusSeconds(60)))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.getCode()).isEqualTo("SYMBOL_CHART_DISABLED"));
  }

  @Test
  void orderBookRejectsOrderBookDisabledSymbol() {
    ProviderResolution resolution = resolution(adapter(MarketDataCapability.ORDER_BOOK), symbol(true, true, false), "BTCUSDT");
    when(providerResolver.resolve("BTCUSDT", MarketDataCapability.ORDER_BOOK)).thenReturn(resolution);

    MarketDataRouter router = new MarketDataRouter(providerResolver);

    assertThatThrownBy(() -> router.orderBook("BTCUSDT"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.getCode()).isEqualTo("SYMBOL_ORDER_BOOK_DISABLED"));
  }

  @Test
  void quoteCandlesOrderBookAndTradesFollowCurrentProviderBinding() {
    UUID symbolId = UUID.randomUUID();
    SymbolEntity platformSymbol = symbol(true, true, true);
    platformSymbol.setId(symbolId);
    platformSymbol.setSymbol("BTCUSDT");
    UUID binanceId = UUID.randomUUID();
    UUID okxId = UUID.randomUUID();
    SymbolProviderBindingEntity binanceBinding = binding(symbolId, binanceId, "BTCUSDT");
    SymbolProviderBindingEntity okxBinding = binding(symbolId, okxId, "BTC-USDT");
    DataProviderEntity binanceProvider = provider(binanceId, "binance");
    DataProviderEntity okxProvider = provider(okxId, "okx");
    RoutingAdapter binance = new RoutingAdapter("binance", "BTCUSDT", new BigDecimal("65000"));
    RoutingAdapter okx = new RoutingAdapter("okx", "BTC-USDT", new BigDecimal("66000"));

    SymbolRepository symbolRepository = org.mockito.Mockito.mock(SymbolRepository.class);
    SymbolProviderBindingRepository bindingRepository = org.mockito.Mockito.mock(SymbolProviderBindingRepository.class);
    DataProviderRepository providerRepository = org.mockito.Mockito.mock(DataProviderRepository.class);
    DataProviderCapabilityRepository capabilityRepository = org.mockito.Mockito.mock(DataProviderCapabilityRepository.class);
    AtomicReference<List<SymbolProviderBindingEntity>> activeBindings =
        new AtomicReference<>(List.of(binanceBinding));

    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(platformSymbol));
    when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbolId))
        .thenAnswer(invocation -> activeBindings.get());
    when(providerRepository.findById(binanceId)).thenReturn(Optional.of(binanceProvider));
    when(providerRepository.findById(okxId)).thenReturn(Optional.of(okxProvider));
    for (MarketDataCapability capability : List.of(
        MarketDataCapability.QUOTE,
        MarketDataCapability.CANDLES,
        MarketDataCapability.ORDER_BOOK,
        MarketDataCapability.TRADES)) {
      when(capabilityRepository.existsEnabledCapability(binanceId, capability)).thenReturn(true);
      when(capabilityRepository.existsEnabledCapability(okxId, capability)).thenReturn(true);
    }

    MarketDataRouter router = new MarketDataRouter(new ProviderResolver(
        symbolRepository,
        bindingRepository,
        providerRepository,
        capabilityRepository,
        new ProviderRegistry(List.of(binance, okx))));

    assertThat(router.latestQuote("BTCUSDT").source()).isEqualTo("binance-quote:BTCUSDT");
    assertThat(router.candles("BTCUSDT", "1m", Instant.EPOCH, Instant.EPOCH.plusSeconds(60)).getFirst().open())
        .isEqualByComparingTo("65000");
    assertThat(router.orderBook("BTCUSDT").symbol()).isEqualTo("binance:BTCUSDT");
    assertThat(router.recentTrades("BTCUSDT", 5).getFirst().id()).isEqualTo("binance:BTCUSDT");

    activeBindings.set(List.of(okxBinding));

    assertThat(router.latestQuote("BTCUSDT").source()).isEqualTo("okx-quote:BTC-USDT");
    assertThat(router.candles("BTCUSDT", "1m", Instant.EPOCH, Instant.EPOCH.plusSeconds(60)).getFirst().open())
        .isEqualByComparingTo("66000");
    assertThat(router.orderBook("BTCUSDT").symbol()).isEqualTo("okx:BTC-USDT");
    assertThat(router.recentTrades("BTCUSDT", 5).getFirst().id()).isEqualTo("okx:BTC-USDT");
  }

  private SymbolEntity symbol(boolean quoteEnabled, boolean chartEnabled, boolean orderBookEnabled) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol("EURUSD");
    symbol.setEnabled(true);
    symbol.setQuoteEnabled(quoteEnabled);
    symbol.setChartEnabled(chartEnabled);
    symbol.setOrderBookEnabled(orderBookEnabled);
    return symbol;
  }

  private ProviderResolution resolution(MarketDataProviderAdapter adapter, SymbolEntity symbol, String providerSymbol) {
    DataProviderEntity provider = new DataProviderEntity();
    provider.setId(UUID.randomUUID());
    provider.setCode(adapter.code());
    provider.setEnabled(true);
    SymbolProviderBindingEntity binding = new SymbolProviderBindingEntity();
    binding.setId(UUID.randomUUID());
    binding.setSymbolId(symbol.getId());
    binding.setProviderId(provider.getId());
    binding.setProviderSymbol(providerSymbol);
    binding.setEnabled(true);
    return new ProviderResolution(symbol, provider, binding, adapter);
  }

  private DataProviderEntity provider(UUID id, String code) {
    DataProviderEntity provider = new DataProviderEntity();
    provider.setId(id);
    provider.setCode(code);
    provider.setEnabled(true);
    return provider;
  }

  private SymbolProviderBindingEntity binding(UUID symbolId, UUID providerId, String providerSymbol) {
    SymbolProviderBindingEntity binding = new SymbolProviderBindingEntity();
    binding.setId(UUID.randomUUID());
    binding.setSymbolId(symbolId);
    binding.setProviderId(providerId);
    binding.setProviderSymbol(providerSymbol);
    binding.setEnabled(true);
    binding.setPriority(100);
    return binding;
  }

  private MarketDataProviderAdapter adapter(MarketDataCapability... capabilities) {
    return org.mockito.Mockito.mock(
        MarketDataProviderAdapter.class,
        invocation -> {
          String method = invocation.getMethod().getName();
          if ("code".equals(method)) {
            return "test";
          }
          if ("configured".equals(method)) {
            return true;
          }
          if ("capabilities".equals(method)) {
            return Set.of(capabilities);
          }
          if ("supports".equals(method)) {
            return Set.of(capabilities).contains(invocation.getArgument(0));
          }
          if ("fetchCandles".equals(method)) {
            return List.of(new CandleResponse(0L, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));
          }
          if ("fetchOrderBook".equals(method)) {
            return Optional.<MarketDepthResponse>empty();
          }
          if ("fetchRecentTrades".equals(method)) {
            return List.<RecentTradeResponse>of();
          }
          if ("fetchMarketSnapshots".equals(method)) {
            return Map.<String, QuoteResponse>of();
          }
          return invocation.callRealMethod();
        });
  }

  private QuoteResponse quote(String symbol, String source) {
    return new QuoteResponse(
        "quote",
        symbol,
        new BigDecimal("1.1"),
        new BigDecimal("1.2"),
        new BigDecimal("1.15"),
        new BigDecimal("0.1"),
        source,
        1781462400000L);
  }

  private SymbolEntity enabledSymbol(String code) {
    SymbolEntity symbol = symbol(true, true, true);
    symbol.setSymbol(code);
    return symbol;
  }

  private SpotMarketBundle spotBundle(Instant now) {
    Instant expiresAt = now.plusSeconds(5);
    MarketDepthResponse depth = new MarketDepthResponse(
        "BTCUSDT", now.toEpochMilli(),
        List.of(new MarketDepthLevelResponse(new BigDecimal("99"), BigDecimal.ONE)),
        List.of(new MarketDepthLevelResponse(new BigDecimal("101"), BigDecimal.ONE)),
        "okx", "BTC-USDT", MarketSourceMode.PUBLIC_EXTERNAL, now, expiresAt, false);
    RecentTradeResponse trade = new RecentTradeResponse(
        "1", "BTCUSDT", new BigDecimal("100"), BigDecimal.ONE, "BUY", now.toEpochMilli(),
        "okx", "BTC-USDT", MarketSourceMode.PUBLIC_EXTERNAL, now, expiresAt, false);
    CandleResponse candle = new CandleResponse(
        now.minusSeconds(60).toEpochMilli(), new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100"), BigDecimal.ONE,
        "okx", "BTC-USDT", MarketSourceMode.PUBLIC_EXTERNAL, now, expiresAt, false);
    return new SpotMarketBundle(
        "BTCUSDT", "BTC-USDT", "okx", MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"), new BigDecimal("101"), new BigDecimal("100"),
        depth, List.of(trade), List.of(candle), now, expiresAt);
  }

  private PerpetualMarketBundle perpBundle(Instant now) {
    SpotMarketBundle spot = spotBundle(now);
    MarketDepthResponse depth = new MarketDepthResponse(
        "BTCUSDT-PERP", spot.orderBook().timestamp(), spot.orderBook().bids(), spot.orderBook().asks(),
        "okx-swap", "BTC-USDT-SWAP", MarketSourceMode.PUBLIC_EXTERNAL,
        spot.asOf(), spot.expiresAt(), false);
    RecentTradeResponse trade = new RecentTradeResponse(
        "1", "BTCUSDT-PERP", new BigDecimal("100"), BigDecimal.ONE, "BUY", now.toEpochMilli(),
        "okx-swap", "BTC-USDT-SWAP", MarketSourceMode.PUBLIC_EXTERNAL, now, spot.expiresAt(), false);
    CandleResponse candle = new CandleResponse(
        now.minusSeconds(60).toEpochMilli(), new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100"), BigDecimal.ONE,
        "okx-swap", "BTC-USDT-SWAP", MarketSourceMode.PUBLIC_EXTERNAL, now, spot.expiresAt(), false);
    return new PerpetualMarketBundle(
        "BTCUSDT-PERP", "BTC-USDT-SWAP", "okx-swap", MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"), new BigDecimal("101"), new BigDecimal("100"),
        new BigDecimal("100.2"), new BigDecimal("100.1"), depth,
        List.of(trade), List.of(candle), now, spot.expiresAt());
  }

  private record RoutingAdapter(
      String code,
      String expectedProviderSymbol,
      BigDecimal price
  ) implements MarketDataProviderAdapter {

    @Override
    public boolean configured() {
      return true;
    }

    @Override
    public Set<MarketDataCapability> capabilities() {
      return Set.of(
          MarketDataCapability.QUOTE,
          MarketDataCapability.CANDLES,
          MarketDataCapability.ORDER_BOOK,
          MarketDataCapability.TRADES);
    }

    @Override
    public Optional<QuoteResponse> fetchLatestQuote(String symbol, String providerSymbol) {
      assertThat(providerSymbol).isEqualTo(expectedProviderSymbol);
      return Optional.of(new QuoteResponse(
          "quote",
          symbol,
          price,
          price.add(BigDecimal.ONE),
          price,
          BigDecimal.ONE,
          code + "-quote:" + providerSymbol,
          1781462400000L));
    }

    @Override
    public List<CandleResponse> fetchCandles(String symbol, String providerSymbol, String timeframe, Instant from, Instant to) {
      assertThat(providerSymbol).isEqualTo(expectedProviderSymbol);
      return List.of(new CandleResponse(1781462400000L, price, price, price, price, BigDecimal.ONE));
    }

    @Override
    public Optional<MarketDepthResponse> fetchOrderBook(String symbol, String providerSymbol) {
      assertThat(providerSymbol).isEqualTo(expectedProviderSymbol);
      return Optional.of(new MarketDepthResponse(
          code + ":" + providerSymbol,
          1781462400000L,
          List.of(new MarketDepthLevelResponse(price, BigDecimal.ONE)),
          List.of()));
    }

    @Override
    public List<RecentTradeResponse> fetchRecentTrades(String symbol, String providerSymbol, int limit) {
      assertThat(providerSymbol).isEqualTo(expectedProviderSymbol);
      return List.of(new RecentTradeResponse(
          code + ":" + providerSymbol,
          symbol,
          price,
          BigDecimal.ONE,
          "BUY",
          1781462400000L));
    }
  }
}
