package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketRealtimeLifecycleTest {

  @Mock
  private BinanceRealtimeClient client;

  @Mock
  private BinanceSubscriptionManager subscriptionManager;

  @Mock
  private RealtimeBackfillService backfillService;

  @Test
  void skipsStartupWhenRealtimeDisabled() {
    MarketRealtimeProperties properties = new MarketRealtimeProperties();

    lifecycle(properties, false, "").run(null);

    verifyNoInteractions(client, subscriptionManager, backfillService);
  }

  @Test
  void connectsActivatesBootstrapSymbolsAndBackfillsWhenEnabled() {
    MarketRealtimeProperties properties = new MarketRealtimeProperties();
    properties.setEnabled(true);
    properties.setSymbols(List.of("btcusdt", "ETH-USDT", "BTCUSDT"));

    lifecycle(properties, false, "").run(null);

    verify(client).connect();
    verify(subscriptionManager).activateSymbol("BTCUSDT");
    verify(subscriptionManager).activateSymbol("ETHUSDT");
    ArgumentCaptor<List<String>> symbols = ArgumentCaptor.forClass(List.class);
    verify(backfillService).backfill(symbols.capture());
    org.assertj.core.api.Assertions.assertThat(symbols.getValue()).containsExactly("BTCUSDT", "ETHUSDT");
  }

  @Test
  void skipsBackfillWhenBackfillDisabled() {
    MarketRealtimeProperties properties = new MarketRealtimeProperties();
    properties.setEnabled(true);
    properties.setBackfillEnabled(false);
    properties.setSymbols(List.of("BTCUSDT"));

    lifecycle(properties, false, "").run(null);

    verify(client).connect();
    verify(subscriptionManager).activateSymbol("BTCUSDT");
    verify(backfillService, never()).backfill(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void rejectsRealtimeWhenDemoDataRunsForAllSymbols() {
    MarketRealtimeProperties properties = new MarketRealtimeProperties();
    properties.setEnabled(true);
    properties.setSymbols(List.of("BTCUSDT"));

    assertThatThrownBy(() -> lifecycle(properties, true, "").run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MARKET_TEST_DATA_ENABLED=false");

    verifyNoInteractions(client, subscriptionManager, backfillService);
  }

  @Test
  void rejectsRealtimeWhenDemoDataOverlapsBootstrapSymbols() {
    MarketRealtimeProperties properties = new MarketRealtimeProperties();
    properties.setEnabled(true);
    properties.setSymbols(List.of("BTCUSDT", "ETHUSDT"));

    assertThatThrownBy(() -> lifecycle(properties, true, "eth-usdt, SOLUSDT").run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ETHUSDT")
        .hasMessageContaining("MARKET_TEST_DATA_ENABLED=false");

    verifyNoInteractions(client, subscriptionManager, backfillService);
  }

  @Test
  void allowsRealtimeWhenDemoDataUsesNonOverlappingSymbols() {
    MarketRealtimeProperties properties = new MarketRealtimeProperties();
    properties.setEnabled(true);
    properties.setSymbols(List.of("BTCUSDT"));

    lifecycle(properties, true, "ETHUSDT").run(null);

    verify(client).connect();
    verify(subscriptionManager).activateSymbol("BTCUSDT");
    verify(backfillService).backfill(List.of("BTCUSDT"));
  }

  private MarketRealtimeLifecycle lifecycle(
      MarketRealtimeProperties properties,
      boolean testDataEnabled,
      String testDataSymbols) {
    return new MarketRealtimeLifecycle(
        properties,
        client,
        subscriptionManager,
        backfillService,
        testDataEnabled,
        testDataSymbols);
  }
}
