package com.fxplatform.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.provider.MarketDataRouter;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.market.service.SymbolService;
import com.fxplatform.market.service.UserFavoriteSymbolService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
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

  @Test
  void symbolsEndpointDefaultsToFullForexUniverseLimit() throws NoSuchMethodException {
    Method method = MarketController.class.getMethod("symbols", String.class, int.class);
    RequestParam limitParam = method.getParameters()[1].getAnnotation(RequestParam.class);

    assertThat(limitParam.defaultValue()).isEqualTo("2000");
  }

  @Test
  void orderBookDelegatesToMarketDataRouter() {
    MarketDepthResponse depth = new MarketDepthResponse("BTCUSDT", 1781462400000L, List.of(), List.of());
    when(marketDataRouter.orderBook("BTCUSDT")).thenReturn(depth);

    MarketController controller = controller();

    assertThat(controller.orderBook("BTCUSDT").data()).isSameAs(depth);
  }

  @Test
  void tradesDelegateToMarketDataRouter() {
    RecentTradeResponse trade = new RecentTradeResponse(
        "1",
        "BTCUSDT",
        new BigDecimal("65000"),
        new BigDecimal("0.01"),
        "BUY",
        1781462400000L);
    when(marketDataRouter.recentTrades("BTCUSDT", 10)).thenReturn(List.of(trade));

    MarketController controller = controller();

    assertThat(controller.trades("BTCUSDT", 10).data()).containsExactly(trade);
  }

  private MarketController controller() {
    return new MarketController(symbolService, quoteService, marketDataRouter, userFavoriteSymbolService);
  }
}
