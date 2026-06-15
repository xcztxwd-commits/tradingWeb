package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.market.adapter.MarketDataProvider;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SymbolServiceTest {

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private MarketDataProvider marketDataProvider;

  @Test
  void enabledSymbolsMergesProviderForexSymbolsWithoutReplacingLocalTradeConfig() {
    SymbolEntity local = new SymbolEntity();
    local.setSymbol("EURUSD");
    local.setDisplayName("Local EURUSD");
    local.setProvider("massive");
    local.setProviderSymbol("C:EURUSD");
    local.setAssetClass("FOREX");
    local.setBaseCurrency("EUR");
    local.setQuoteCurrency("USD");
    local.setMinLot(new BigDecimal("0.10"));
    local.setMaxLot(new BigDecimal("50"));
    local.setLeverage(88);
    local.setEnabled(true);
    SymbolResponse externalDuplicate = new SymbolResponse(
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
        false);
    SymbolResponse externalOnly = new SymbolResponse(
        "USDJPY",
        "US Dollar / Japanese Yen",
        "FOREX",
        "USD",
        "JPY",
        new BigDecimal("0.01"),
        new BigDecimal("100"),
        100,
        true,
        "massive",
        "C:USDJPY",
        false);

    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(local));
    when(marketDataProvider.isConfigured()).thenReturn(true);
    when(marketDataProvider.fetchSymbols("FOREX", 1000)).thenReturn(List.of(externalDuplicate, externalOnly));

    SymbolService service = new SymbolService(symbolRepository, marketDataProvider);

    List<SymbolResponse> symbols = service.enabledSymbols(null, 1000);

    assertThat(symbols).extracting(SymbolResponse::symbol).containsExactly("EURUSD", "USDJPY");
    assertThat(symbols.getFirst().displayName()).isEqualTo("Local EURUSD");
    assertThat(symbols.getFirst().minLot()).isEqualByComparingTo("0.10");
    assertThat(symbols.getFirst().leverage()).isEqualTo(88);
    assertThat(symbols.getFirst().tradable()).isTrue();
    assertThat(symbols.get(1).tradable()).isFalse();
  }

  @Test
  void enabledSymbolsCapsMergedProviderSymbolsToRequestedLimit() {
    SymbolEntity local = new SymbolEntity();
    local.setSymbol("EURUSD");
    local.setDisplayName("Local EURUSD");
    local.setProvider("massive");
    local.setProviderSymbol("C:EURUSD");
    local.setAssetClass("FOREX");
    local.setBaseCurrency("EUR");
    local.setQuoteCurrency("USD");
    local.setMinLot(new BigDecimal("0.10"));
    local.setMaxLot(new BigDecimal("50"));
    local.setLeverage(88);
    local.setEnabled(true);
    SymbolResponse externalOne = new SymbolResponse(
        "USDJPY",
        "US Dollar / Japanese Yen",
        "FOREX",
        "USD",
        "JPY",
        new BigDecimal("0.01"),
        new BigDecimal("100"),
        100,
        true,
        "massive",
        "C:USDJPY",
        false);
    SymbolResponse externalTwo = new SymbolResponse(
        "GBPUSD",
        "British Pound / US Dollar",
        "FOREX",
        "GBP",
        "USD",
        new BigDecimal("0.01"),
        new BigDecimal("100"),
        100,
        true,
        "massive",
        "C:GBPUSD",
        false);

    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(local));
    when(marketDataProvider.isConfigured()).thenReturn(true);
    when(marketDataProvider.fetchSymbols("FOREX", 2)).thenReturn(List.of(externalOne, externalTwo));

    SymbolService service = new SymbolService(symbolRepository, marketDataProvider);

    List<SymbolResponse> symbols = service.enabledSymbols(null, 2);

    assertThat(symbols).extracting(SymbolResponse::symbol).containsExactly("EURUSD", "USDJPY");
  }

  @Test
  void enabledSymbolsUsesCachedProviderForexSymbolsWithinTtl() {
    SymbolEntity local = localForexSymbol();
    SymbolResponse external = providerForexSymbol("USDJPY", "US Dollar / Japanese Yen", "USD", "JPY");

    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(local));
    when(marketDataProvider.isConfigured()).thenReturn(true);
    when(marketDataProvider.fetchSymbols("FOREX", 1000)).thenReturn(List.of(external));

    SymbolService service = new SymbolService(symbolRepository, marketDataProvider);

    List<SymbolResponse> first = service.enabledSymbols("FOREX", 1000);
    List<SymbolResponse> second = service.enabledSymbols("FOREX", 1000);

    assertThat(first).extracting(SymbolResponse::symbol).containsExactly("EURUSD", "USDJPY");
    assertThat(second).extracting(SymbolResponse::symbol).containsExactly("EURUSD", "USDJPY");
    verify(marketDataProvider, times(1)).fetchSymbols("FOREX", 1000);
  }

  @Test
  void enabledSymbolsKeepsLargerCachedProviderForexSymbolsWhenRefreshReturnsLessData() {
    SymbolEntity local = localForexSymbol();
    SymbolResponse usdjpy = providerForexSymbol("USDJPY", "US Dollar / Japanese Yen", "USD", "JPY");
    SymbolResponse gbpusd = providerForexSymbol("GBPUSD", "British Pound / US Dollar", "GBP", "USD");

    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(local));
    when(marketDataProvider.isConfigured()).thenReturn(true);
    when(marketDataProvider.fetchSymbols("FOREX", 1000))
        .thenReturn(List.of(usdjpy, gbpusd))
        .thenReturn(List.of(usdjpy));

    SymbolService service = new SymbolService(symbolRepository, marketDataProvider);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "providerSymbolCacheTtlMs", 0L);

    List<SymbolResponse> first = service.enabledSymbols("FOREX", 1000);
    List<SymbolResponse> second = service.enabledSymbols("FOREX", 1000);

    assertThat(first).extracting(SymbolResponse::symbol).containsExactly("EURUSD", "USDJPY", "GBPUSD");
    assertThat(second).extracting(SymbolResponse::symbol).containsExactly("EURUSD", "USDJPY", "GBPUSD");
    verify(marketDataProvider, times(2)).fetchSymbols("FOREX", 1000);
  }

  @Test
  void enabledSymbolsIncludesProviderSnapshotMetricsForForexSymbols() {
    SymbolEntity local = localForexSymbol();
    SymbolResponse external = providerForexSymbol("USDJPY", "US Dollar / Japanese Yen", "USD", "JPY");

    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(local));
    when(marketDataProvider.isConfigured()).thenReturn(true);
    when(marketDataProvider.fetchSymbols("FOREX", 1000)).thenReturn(List.of(external));
    when(marketDataProvider.fetchMarketSnapshots("FOREX", 1000)).thenReturn(Map.of(
        "EURUSD", quote("EURUSD", "1.12498", "1.12502", "2.272727", "1.13000", "1.09000", "500"),
        "USDJPY", quote("USDJPY", "156.410", "156.430", "-0.125000", "157.000", "155.800", "300")));

    SymbolService service = new SymbolService(symbolRepository, marketDataProvider);

    List<SymbolResponse> symbols = service.enabledSymbols("FOREX", 1000);

    assertThat(symbols).extracting(SymbolResponse::symbol).containsExactly("EURUSD", "USDJPY");
    assertThat(symbols.getFirst().lastPrice()).isEqualByComparingTo("1.12500");
    assertThat(symbols.getFirst().changePercent()).isEqualByComparingTo("2.272727");
    assertThat(symbols.getFirst().volume24h()).isEqualByComparingTo("500");
    assertThat(symbols.getFirst().marketCap()).isEqualByComparingTo("4500.00000");
    assertThat(symbols.getFirst().quoteSource()).isEqualTo("massive-snapshot");
    assertThat(symbols.get(1).lastPrice()).isEqualByComparingTo("156.420");
    assertThat(symbols.get(1).spread()).isEqualByComparingTo("0.020");
  }

  private SymbolEntity localForexSymbol() {
    SymbolEntity local = new SymbolEntity();
    local.setSymbol("EURUSD");
    local.setDisplayName("Local EURUSD");
    local.setProvider("massive");
    local.setProviderSymbol("C:EURUSD");
    local.setAssetClass("FOREX");
    local.setBaseCurrency("EUR");
    local.setQuoteCurrency("USD");
    local.setMinLot(new BigDecimal("0.10"));
    local.setMaxLot(new BigDecimal("50"));
    local.setLeverage(88);
    local.setEnabled(true);
    return local;
  }

  private SymbolResponse providerForexSymbol(String symbol, String name, String base, String quote) {
    return new SymbolResponse(
        symbol,
        name,
        "FOREX",
        base,
        quote,
        new BigDecimal("0.01"),
        new BigDecimal("100"),
        100,
        true,
        "massive",
        "C:" + symbol,
        false);
  }

  private QuoteResponse quote(
      String symbol,
      String bid,
      String ask,
      String changePercent,
      String high24h,
      String low24h,
      String volume24h
  ) {
    BigDecimal bidPrice = new BigDecimal(bid);
    BigDecimal askPrice = new BigDecimal(ask);
    BigDecimal mid = bidPrice.add(askPrice).divide(new BigDecimal("2"));
    return new QuoteResponse(
        "quote",
        symbol,
        bidPrice,
        askPrice,
        mid,
        askPrice.subtract(bidPrice),
        "massive-snapshot",
        1781462400000L,
        new BigDecimal(changePercent),
        new BigDecimal(high24h),
        new BigDecimal(low24h),
        new BigDecimal(volume24h));
  }
}
