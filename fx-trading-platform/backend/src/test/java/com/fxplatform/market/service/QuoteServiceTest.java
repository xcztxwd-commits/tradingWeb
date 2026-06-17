package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.provider.MarketDataRouter;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

  @Mock
  private MarketDataRouter marketDataRouter;

  @Mock
  private StringRedisTemplate redisTemplate;

  @Mock
  private ValueOperations<String, String> valueOperations;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void quoteStaleWindowPrefersSharedMarketConfigWithMassiveFallback() throws NoSuchFieldException {
    Field field = QuoteService.class.getDeclaredField("quoteStaleMs");
    Value value = field.getAnnotation(Value.class);

    assertThat(value.value()).isEqualTo("${market.quote-stale-ms:${massive.quote-stale-ms:3000}}");
  }

  @Test
  void latestQuoteNormalizesCommonSymbolSeparatorsBeforeRouting() {
    QuoteResponse providerQuote = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.1"),
        new BigDecimal("1.2"),
        new BigDecimal("1.15"),
        new BigDecimal("0.1"),
        "massive-quote",
        System.currentTimeMillis() + 60000);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(null);
    when(marketDataRouter.latestQuote("EURUSD")).thenReturn(providerQuote);

    QuoteService service = service(false);

    QuoteResponse quote = service.latestQuote("eur-usd");

    assertThat(quote).isSameAs(providerQuote);
    verify(marketDataRouter).latestQuote("EURUSD");
    verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("quote:EURUSD"), anyString());
  }

  @Test
  void latestQuoteUsesFreshCachedExternalProviderQuoteForMarketDisplay() throws Exception {
    QuoteResponse cachedProviderQuote = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.1"),
        new BigDecimal("1.2"),
        new BigDecimal("1.15"),
        new BigDecimal("0.1"),
        "massive-aggregate",
        System.currentTimeMillis());

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(objectMapper.writeValueAsString(cachedProviderQuote));

    QuoteService service = service(false);
    ReflectionTestUtils.setField(service, "quoteStaleMs", 600_000);

    QuoteResponse quote = service.latestQuote("EURUSD");

    assertThat(quote).isEqualTo(cachedProviderQuote);
  }

  @Test
  void latestQuotesUsesFreshCacheAndFetchesMissingSymbolsInOneBatch() throws Exception {
    QuoteResponse cached = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.1"),
        new BigDecimal("1.2"),
        new BigDecimal("1.15"),
        new BigDecimal("0.1"),
        "massive-snapshot",
        System.currentTimeMillis());
    QuoteResponse fetched = new QuoteResponse(
        "quote",
        "BTCUSDT",
        new BigDecimal("65000"),
        new BigDecimal("65001"),
        new BigDecimal("65000.5"),
        new BigDecimal("1"),
        "binance-spot",
        System.currentTimeMillis());

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(objectMapper.writeValueAsString(cached));
    when(valueOperations.get("quote:BTCUSDT")).thenReturn(null);
    when(marketDataRouter.snapshots(List.of("BTCUSDT"))).thenReturn(Map.of("BTCUSDT", fetched));

    QuoteService service = service(false);
    ReflectionTestUtils.setField(service, "quoteStaleMs", 600_000);

    Map<String, QuoteResponse> quotes = service.latestQuotes(List.of("eur-usd", "BTCUSDT", "EURUSD"));

    assertThat(quotes.keySet()).containsExactly("EURUSD", "BTCUSDT");
    assertThat(quotes.get("EURUSD")).isEqualTo(cached);
    assertThat(quotes.get("BTCUSDT")).isSameAs(fetched);
    verify(marketDataRouter).snapshots(List.of("BTCUSDT"));
  }

  @Test
  void latestQuoteRefreshesStaleCachedExternalProviderQuoteForMarketDisplay() throws Exception {
    QuoteResponse cachedProviderQuote = quote("EURUSD", "massive-aggregate");
    QuoteResponse providerQuote = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.2"),
        new BigDecimal("1.3"),
        new BigDecimal("1.25"),
        new BigDecimal("0.1"),
        "massive-aggregate",
        System.currentTimeMillis());

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(objectMapper.writeValueAsString(cachedProviderQuote));
    when(marketDataRouter.latestQuote("EURUSD")).thenReturn(providerQuote);

    QuoteService service = service(false);
    ReflectionTestUtils.setField(service, "quoteStaleMs", 100);

    QuoteResponse quote = service.latestQuote("EURUSD");

    assertThat(quote).isSameAs(providerQuote);
    verify(marketDataRouter).latestQuote("EURUSD");
    verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("quote:EURUSD"), anyString());
  }

  @Test
  void latestQuoteRejectsDemoFallbackWhenDisabled() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(null);
    when(marketDataRouter.latestQuote("EURUSD"))
        .thenThrow(new BusinessException("MARKET_PROVIDER_UNAVAILABLE", "Market data provider unavailable"));

    QuoteService service = service(false);

    assertThatThrownBy(() -> service.latestQuote("EURUSD"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.getCode()).isEqualTo("MARKET_PROVIDER_UNAVAILABLE"));
  }

  @Test
  void latestQuoteUsesDemoOnlyWhenExplicitlyEnabled() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(null);
    when(marketDataRouter.latestQuote("EURUSD"))
        .thenThrow(new BusinessException("MARKET_PROVIDER_UNAVAILABLE", "Market data provider unavailable"));

    QuoteService service = service(true);

    QuoteResponse quote = service.latestQuote("EURUSD");

    assertThat(quote.symbol()).isEqualTo("EURUSD");
    assertThat(quote.source()).isEqualTo("demo");
  }

  @Test
  void latestQuoteUsesDemoWhenProviderBindingIsMissingAndDemoIsEnabled() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(null);
    when(marketDataRouter.latestQuote("EURUSD"))
        .thenThrow(new BusinessException("MARKET_PROVIDER_BINDING_NOT_FOUND", "No enabled provider binding supports QUOTE"));

    QuoteService service = service(true);

    QuoteResponse quote = service.latestQuote("EURUSD");

    assertThat(quote.symbol()).isEqualTo("EURUSD");
    assertThat(quote.source()).isEqualTo("demo");
  }

  @Test
  void freshQuoteRefreshesStaleCachedQuoteBeforeTrading() throws Exception {
    QuoteResponse stale = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.08318"),
        new BigDecimal("1.08322"),
        new BigDecimal("1.08320"),
        new BigDecimal("0.00004"),
        "cache",
        System.currentTimeMillis() - 5000);
    QuoteResponse providerQuote = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.1"),
        new BigDecimal("1.2"),
        new BigDecimal("1.15"),
        new BigDecimal("0.1"),
        "massive-quote",
        System.currentTimeMillis() + 60000);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(objectMapper.writeValueAsString(stale));
    when(marketDataRouter.latestQuote("EURUSD")).thenReturn(providerQuote);

    QuoteService service = service(false);
    ReflectionTestUtils.setField(service, "quoteStaleMs", 100);

    QuoteResponse quote = service.freshQuote("EURUSD");

    assertThat(quote).isSameAs(providerQuote);
  }

  @Test
  void freshQuoteFallsBackToDemoWhenProviderQuoteIsStaleAndDemoIsEnabled() {
    QuoteResponse staleProviderQuote = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.16025"),
        new BigDecimal("1.16029"),
        new BigDecimal("1.16027"),
        new BigDecimal("0.00004"),
        "massive-aggregate",
        System.currentTimeMillis() - 60_000);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(null);
    when(marketDataRouter.latestQuote("EURUSD")).thenReturn(staleProviderQuote);

    QuoteService service = service(true);
    ReflectionTestUtils.setField(service, "quoteStaleMs", 1000);

    QuoteResponse quote = service.freshQuote("EURUSD");

    assertThat(quote.symbol()).isEqualTo("EURUSD");
    assertThat(quote.source()).isEqualTo("demo");
    assertThat(quote.timestamp()).isGreaterThan(staleProviderQuote.timestamp());
  }

  @Test
  void freshQuoteRejectsStaleProviderQuoteWhenDemoIsDisabled() {
    QuoteResponse staleProviderQuote = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.16025"),
        new BigDecimal("1.16029"),
        new BigDecimal("1.16027"),
        new BigDecimal("0.00004"),
        "massive-aggregate",
        System.currentTimeMillis() - 60_000);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(null);
    when(marketDataRouter.latestQuote("EURUSD")).thenReturn(staleProviderQuote);

    QuoteService service = service(false);
    ReflectionTestUtils.setField(service, "quoteStaleMs", 1000);

    assertThatThrownBy(() -> service.freshQuote("EURUSD"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.getCode()).isEqualTo("QUOTE_STALE"));
  }

  @Test
  void statusReportsProviderRoutingMode() {
    QuoteService service = service(false);
    ReflectionTestUtils.setField(service, "quoteStaleMs", 2500);

    var status = service.status();

    assertThat(status.status()).isEqualTo("PROVIDER_ROUTING_ENABLED");
    assertThat(status.demoQuotesEnabled()).isFalse();
    assertThat(status.sourceMode()).isEqualTo("provider-router");
    assertThat(status.providerStatus()).isEqualTo("configured-by-admin");
  }

  private QuoteService service(boolean demoEnabled) {
    QuoteService service = new QuoteService(marketDataRouter, redisTemplate, objectMapper);
    ReflectionTestUtils.setField(service, "demoQuotesEnabled", demoEnabled);
    return service;
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
