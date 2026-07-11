package com.fxplatform.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketDataRouter;
import com.fxplatform.market.realtime.RealtimeMarketSnapshotCache;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.market.service.SymbolService;
import com.fxplatform.market.service.UserFavoriteSymbolService;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.RequestParam;

@ExtendWith(MockitoExtension.class)
class MarketControllerTest {

  @Mock
  private SymbolService symbolService;

  @Mock
  private QuoteService quoteService;

  @Mock
  private MarketDataRouter marketDataRouter;

  @Mock
  private UserFavoriteSymbolService userFavoriteSymbolService;

  @Mock
  private InstrumentRulesEngine instrumentRulesEngine;

  private final RealtimeMarketSnapshotCache realtimeCache = new RealtimeMarketSnapshotCache();

  @Test
  void symbolsEndpointDefaultsToFullForexUniverseLimit() throws NoSuchMethodException {
    Method method = MarketController.class.getMethod("symbols", String.class, int.class);
    RequestParam limitParam = method.getParameters()[1].getAnnotation(RequestParam.class);

    assertThat(limitParam.defaultValue()).isEqualTo("2000");
  }

  @Test
  void orderBookReturnsRealtimeCacheWhenPresent() {
    MarketDepthResponse depth = new MarketDepthResponse("EURUSD", 1781462400000L, List.of(), List.of());
    realtimeCache.putOrderBook(depth);

    MarketController controller = controller();

    assertThat(controller.orderBook("EURUSD").data()).isSameAs(depth);
    verifyNoInteractions(marketDataRouter);
  }

  @Test
  void orderBookFallsBackToMarketDataRouterWhenRealtimeCacheIsEmpty() {
    MarketDepthResponse depth = new MarketDepthResponse("BTCUSDT", 1781462400000L, List.of(), List.of());
    when(marketDataRouter.orderBook("BTCUSDT")).thenReturn(depth);

    MarketController controller = controller();

    assertThat(controller.orderBook("BTCUSDT").data()).isSameAs(depth);
  }

  @Test
  void tradesReturnRealtimeCacheWhenPresent() {
    RecentTradeResponse trade = new RecentTradeResponse(
        "1", "EURUSD", new BigDecimal("1.1"), BigDecimal.ONE, "BUY", 1781462400000L);
    realtimeCache.addTrade(trade);

    MarketController controller = controller();

    assertThat(controller.trades("EURUSD", 10).data()).containsExactly(trade);
    verifyNoInteractions(marketDataRouter);
  }

  @Test
  void p0OrderBookAndTradesBypassLegacyRealtimeCache() {
    MarketDepthResponse cachedDepth = new MarketDepthResponse("BTCUSDT", 1L, List.of(), List.of());
    RecentTradeResponse cachedTrade = trade("cached");
    realtimeCache.putOrderBook(cachedDepth);
    realtimeCache.addTrade(cachedTrade);
    MarketDepthResponse authoritativeDepth = new MarketDepthResponse("BTCUSDT", 2L, List.of(), List.of());
    RecentTradeResponse authoritativeTrade = trade("authoritative");
    when(marketDataRouter.orderBook("BTCUSDT")).thenReturn(authoritativeDepth);
    when(marketDataRouter.recentTrades("BTCUSDT", 10)).thenReturn(List.of(authoritativeTrade));

    MarketController controller = controller();

    assertThat(controller.orderBook("BTCUSDT").data()).isSameAs(authoritativeDepth);
    assertThat(controller.trades("BTCUSDT", 10).data()).containsExactly(authoritativeTrade);
  }

  @Test
  void perpetualReferenceEndpointDelegatesToAuthoritativeRouter() {
    var response = new com.fxplatform.market.dto.PerpetualReferenceResponse(
        "BTCUSDT-PERP", "BTC-USDT-SWAP", "okx-swap",
        com.fxplatform.market.model.MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"), new BigDecimal("101"), new BigDecimal("100"),
        new BigDecimal("100.2"), new BigDecimal("100.1"),
        java.time.Instant.parse("2026-07-12T00:00:00Z"),
        java.time.Instant.parse("2026-07-12T00:00:05Z"), false);
    when(marketDataRouter.perpetualReference("BTCUSDT-PERP")).thenReturn(response);

    assertThat(controller().perpetualReference("BTCUSDT-PERP").data()).isSameAs(response);
  }

  @Test
  void tradesFallBackToMarketDataRouterWhenRealtimeCacheIsEmpty() {
    RecentTradeResponse trade = trade("1");
    when(marketDataRouter.recentTrades("BTCUSDT", 10)).thenReturn(List.of(trade));

    MarketController controller = controller();

    assertThat(controller.trades("BTCUSDT", 10).data()).containsExactly(trade);
  }

  private RecentTradeResponse trade(String id) {
    RecentTradeResponse trade = new RecentTradeResponse(
        id,
        "BTCUSDT",
        new BigDecimal("65000"),
        new BigDecimal("0.01"),
        "BUY",
        1781462400000L);
    return trade;
  }

  @Test
  void batchQuotesEndpointNormalizesCommaSeparatedSymbols() {
    QuoteResponse eurusd = quote("EURUSD", "massive-snapshot");
    QuoteResponse btcusdt = quote("BTCUSDT", "binance-spot");
    when(quoteService.latestQuotes(List.of("EURUSD", "BTCUSDT")))
        .thenReturn(Map.of("EURUSD", eurusd, "BTCUSDT", btcusdt));

    MarketController controller = controller();

    assertThat(controller.quotes("eur-usd, BTCUSDT,EURUSD").data())
        .containsEntry("EURUSD", eurusd)
        .containsEntry("BTCUSDT", btcusdt);
  }

  @Test
  void singleSymbolRulesEndpointDelegatesToRulesEngine() {
    when(instrumentRulesEngine.rules("BTCUSDT")).thenReturn(rules("BTCUSDT", true));

    MarketController controller = controller();

    assertThat(controller.symbolRules("BTCUSDT").data().symbol()).isEqualTo("BTCUSDT");
    assertThat(controller.symbolRules("BTCUSDT").data().minNotional()).isEqualByComparingTo("5");
  }

  @Test
  void batchSymbolRulesEndpointNormalizesCommaSeparatedSymbols() {
    when(instrumentRulesEngine.rules(List.of("BTCUSDT", "ETHUSDT")))
        .thenReturn(List.of(rules("BTCUSDT", true), rules("ETHUSDT", true)));

    MarketController controller = controller();

    assertThat(controller.symbolRulesBatch("btcusdt, ETHUSDT").data())
        .extracting("symbol")
        .containsExactly("BTCUSDT", "ETHUSDT");
  }

  private MarketController controller() {
    return new MarketController(
        symbolService,
        quoteService,
        marketDataRouter,
        userFavoriteSymbolService,
        instrumentRulesEngine,
        realtimeCache);
  }

  private InstrumentRules rules(String symbol, boolean exists) {
    return new InstrumentRules(
        symbol,
        exists,
        true,
        true,
        true,
        true,
        true,
        true,
        ProductType.CRYPTO_SPOT,
        new BigDecimal("0.01"),
        new BigDecimal("0.00001"),
        new BigDecimal("0.00001"),
        new BigDecimal("100"),
        new BigDecimal("5"),
        null,
        new BigDecimal("0.00001"),
        new BigDecimal("100"),
        1,
        1,
        "USDT",
        "USDT",
        BigDecimal.ONE,
        "DEFAULT",
        "ALWAYS",
        "NONE",
        "NORMAL");
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
}
