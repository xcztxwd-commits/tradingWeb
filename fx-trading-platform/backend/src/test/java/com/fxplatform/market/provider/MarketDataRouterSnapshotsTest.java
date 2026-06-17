package com.fxplatform.market.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import com.fxplatform.market.service.ProviderHealthRecorder;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class MarketDataRouterSnapshotsTest {

  @Mock
  private ProviderResolver providerResolver;

  @Mock
  private StringRedisTemplate redisTemplate;

  @Mock
  private ValueOperations<String, String> valueOperations;

  @Mock
  private ProviderHealthRecorder healthRecorder;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void snapshotsGroupsSymbolsByProviderCachesSuccessesAndSkipsFailingProvider() {
    DataProviderEntity massiveProvider = provider("massive");
    DataProviderEntity binanceProvider = provider("binance");
    RoutingAdapter massive = new RoutingAdapter("massive", Map.of(
        "EURUSD",
        quote("EURUSD", "massive-snapshot")));
    RoutingAdapter binance = new RoutingAdapter("binance", Map.of());
    binance.failure = new IllegalStateException("binance unavailable");
    ProviderResolution eurusd = resolution("EURUSD", "C:EURUSD", massiveProvider, massive);
    ProviderResolution btcusdt = resolution("BTCUSDT", "BTCUSDT", binanceProvider, binance);
    when(providerResolver.resolveAll(List.of("EURUSD", "BTCUSDT"), MarketDataCapability.QUOTE))
        .thenReturn(Map.of("EURUSD", eurusd, "BTCUSDT", btcusdt));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    MarketDataRouter router = new MarketDataRouter(providerResolver, redisTemplate, objectMapper, healthRecorder);

    Map<String, QuoteResponse> snapshots = router.snapshots(List.of("eur-usd", "BTCUSDT", "EURUSD"));

    assertThat(snapshots).containsOnlyKeys("EURUSD");
    assertThat(snapshots.get("EURUSD").source()).isEqualTo("massive-snapshot");
    assertThat(massive.lastRequest).containsExactlyEntriesOf(Map.of("EURUSD", "C:EURUSD"));
    assertThat(binance.lastRequest).containsExactlyEntriesOf(Map.of("BTCUSDT", "BTCUSDT"));
    verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("quote:EURUSD"), org.mockito.ArgumentMatchers.anyString());
    verify(healthRecorder).recordQuoteSuccess(
        org.mockito.ArgumentMatchers.same(massiveProvider),
        org.mockito.ArgumentMatchers.same(snapshots.get("EURUSD")),
        anyLong());
    verify(healthRecorder).recordFailure(org.mockito.ArgumentMatchers.same(binanceProvider), anyLong());
  }

  @Test
  void snapshotsIgnoresDisabledQuoteSymbolsBeforeCallingProvider() {
    DataProviderEntity provider = provider("massive");
    RoutingAdapter adapter = new RoutingAdapter("massive", Map.of("EURUSD", quote("EURUSD", "massive-snapshot")));
    ProviderResolution disabledQuote = resolution("EURUSD", "C:EURUSD", provider, adapter);
    disabledQuote.symbol().setQuoteEnabled(false);
    when(providerResolver.resolveAll(List.of("EURUSD"), MarketDataCapability.QUOTE))
        .thenReturn(Map.of("EURUSD", disabledQuote));

    MarketDataRouter router = new MarketDataRouter(providerResolver, redisTemplate, objectMapper, healthRecorder);

    assertThat(router.snapshots(List.of("EURUSD"))).isEmpty();
    assertThat(adapter.lastRequest).isEmpty();
  }

  private ProviderResolution resolution(
      String symbolCode,
      String providerSymbol,
      DataProviderEntity provider,
      MarketDataProviderAdapter adapter
  ) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(symbolCode);
    symbol.setEnabled(true);
    symbol.setQuoteEnabled(true);
    SymbolProviderBindingEntity binding = new SymbolProviderBindingEntity();
    binding.setId(UUID.randomUUID());
    binding.setSymbolId(symbol.getId());
    binding.setProviderId(provider.getId());
    binding.setProviderSymbol(providerSymbol);
    binding.setEnabled(true);
    return new ProviderResolution(symbol, provider, binding, adapter);
  }

  private DataProviderEntity provider(String code) {
    DataProviderEntity provider = new DataProviderEntity();
    provider.setId(UUID.randomUUID());
    provider.setCode(code);
    provider.setEnabled(true);
    return provider;
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

  private static final class RoutingAdapter implements MarketDataProviderAdapter {
    private final String code;
    private final Map<String, QuoteResponse> quotes;
    private RuntimeException failure;
    private Map<String, String> lastRequest = Map.of();

    private RoutingAdapter(String code, Map<String, QuoteResponse> quotes) {
      this.code = code;
      this.quotes = quotes;
    }

    @Override
    public String code() {
      return code;
    }

    @Override
    public Set<MarketDataCapability> capabilities() {
      return Set.of(MarketDataCapability.QUOTE);
    }

    @Override
    public boolean configured() {
      return true;
    }

    @Override
    public Map<String, QuoteResponse> fetchLatestQuotes(Map<String, String> providerSymbolsBySymbol) {
      lastRequest = Map.copyOf(providerSymbolsBySymbol);
      if (failure != null) {
        throw failure;
      }
      return quotes;
    }
  }
}
