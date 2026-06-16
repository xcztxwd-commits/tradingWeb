package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.ProviderResolver;
import com.fxplatform.market.repository.SymbolRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SymbolServiceTest {

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private ProviderResolver providerResolver;

  @Test
  void enabledSymbolsReturnsOnlyPlatformDisplayedSymbolsInDisplayOrder() {
    SymbolEntity hidden = symbol("USDJPY", "FOREX", true, false, 1);
    SymbolEntity second = symbol("BTCUSDT", "CRYPTO", true, true, 20);
    SymbolEntity first = symbol("EURUSD", "FOREX", true, true, 10);

    when(symbolRepository.findVisibleSymbols(null)).thenReturn(List.of(first, second));
    providerAvailable(first);
    providerAvailable(second);

    SymbolService service = new SymbolService(symbolRepository, providerResolver);

    List<SymbolResponse> symbols = service.enabledSymbols();

    assertThat(symbols).extracting(SymbolResponse::symbol).containsExactly("EURUSD", "BTCUSDT");
    assertThat(symbols.getFirst().displayEnabled()).isTrue();
    assertThat(symbols.getFirst().productType()).isEqualTo(ProductType.FX_MARGIN);
    assertThat(symbols.get(1).productType()).isEqualTo(ProductType.CRYPTO_SPOT);
    assertThat(symbols.getFirst().quoteEnabled()).isTrue();
    assertThat(symbols.getFirst().chartEnabled()).isTrue();
    assertThat(symbols.getFirst().displayOrder()).isEqualTo(10);
    assertThat(hidden.getDisplayEnabled()).isFalse();
  }

  @Test
  void enabledSymbolsAppliesAssetClassFilter() {
    SymbolEntity crypto = symbol("BTCUSDT", "CRYPTO", true, true, 20);

    when(symbolRepository.findVisibleSymbols("CRYPTO")).thenReturn(List.of(crypto));
    providerAvailable(crypto);

    List<SymbolResponse> symbols = new SymbolService(symbolRepository, providerResolver).enabledSymbols("crypto", 100);

    assertThat(symbols).extracting(SymbolResponse::symbol).containsExactly("BTCUSDT");
  }

  @Test
  void enabledSymbolsDoesNotFetchProviderDiscoveredSymbols() {
    when(symbolRepository.findVisibleSymbols(null)).thenReturn(List.of());

    List<SymbolResponse> symbols = new SymbolService(symbolRepository, providerResolver).enabledSymbols(null, 100);

    assertThat(symbols).isEmpty();
  }

  @Test
  void enabledSymbolsReturnsProductTypeForLegacyRows() {
    SymbolEntity legacy = symbol("EURUSD", "FOREX", true, true, 10);

    when(symbolRepository.findVisibleSymbols(null)).thenReturn(List.of(legacy));
    providerAvailable(legacy);

    List<SymbolResponse> symbols = new SymbolService(symbolRepository, providerResolver).enabledSymbols();

    assertThat(legacy.getProductType()).isNull();
    assertThat(symbols.getFirst().productType()).isEqualTo(ProductType.FX_MARGIN);
  }

  @Test
  void enabledSymbolsDisablesRuntimeUnavailableCapabilities() {
    SymbolEntity symbol = symbol("EURUSD", "FOREX", true, true, 10);

    when(symbolRepository.findVisibleSymbols(null)).thenReturn(List.of(symbol));
    when(providerResolver.canResolve(symbol, MarketDataCapability.QUOTE)).thenReturn(false);
    when(providerResolver.canResolve(symbol, MarketDataCapability.CANDLES)).thenReturn(true);
    when(providerResolver.canResolve(symbol, MarketDataCapability.ORDER_BOOK)).thenReturn(false);

    List<SymbolResponse> symbols = new SymbolService(symbolRepository, providerResolver).enabledSymbols();

    assertThat(symbols.getFirst().quoteEnabled()).isFalse();
    assertThat(symbols.getFirst().chartEnabled()).isTrue();
    assertThat(symbols.getFirst().orderBookEnabled()).isFalse();
  }

  private void providerAvailable(SymbolEntity symbol) {
    when(providerResolver.canResolve(symbol, MarketDataCapability.QUOTE)).thenReturn(true);
    when(providerResolver.canResolve(symbol, MarketDataCapability.CANDLES)).thenReturn(true);
    when(providerResolver.canResolve(symbol, MarketDataCapability.ORDER_BOOK)).thenReturn(true);
  }

  private SymbolEntity symbol(String symbol, String assetClass, boolean enabled, boolean displayEnabled, int displayOrder) {
    SymbolEntity entity = new SymbolEntity();
    entity.setSymbol(symbol);
    entity.setDisplayName(symbol);
    entity.setProvider("massive");
    entity.setProviderSymbol("C:" + symbol);
    entity.setAssetClass(assetClass);
    entity.setBaseCurrency(symbol.substring(0, 3));
    entity.setQuoteCurrency(symbol.substring(Math.max(symbol.length() - 3, 0)));
    entity.setMinLot(new BigDecimal("0.01"));
    entity.setMaxLot(new BigDecimal("100"));
    entity.setLeverage(100);
    entity.setEnabled(enabled);
    entity.setDisplayEnabled(displayEnabled);
    entity.setQuoteEnabled(true);
    entity.setChartEnabled(true);
    entity.setOrderBookEnabled(true);
    entity.setTradable(true);
    entity.setDisplayOrder(displayOrder);
    return entity;
  }
}
