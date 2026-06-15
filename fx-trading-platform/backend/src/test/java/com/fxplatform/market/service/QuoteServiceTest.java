package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.adapter.MarketDataProvider;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private MarketDataProvider marketDataProvider;

  @Mock
  private StringRedisTemplate redisTemplate;

  @Mock
  private ValueOperations<String, String> valueOperations;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void latestQuoteNormalizesCommonSymbolSeparators() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProviderSymbol("C:EURUSD");

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(null);
    when(marketDataProvider.fetchLatestQuote("EURUSD", "C:EURUSD")).thenReturn(Optional.empty());
    when(marketDataProvider.fetchIndicativeQuote(org.mockito.ArgumentMatchers.eq("EURUSD"), org.mockito.ArgumentMatchers.eq("C:EURUSD"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.empty());

    QuoteService service = new QuoteService(symbolRepository, marketDataProvider, redisTemplate, objectMapper);
    ReflectionTestUtils.setField(service, "demoQuotesEnabled", true);

    QuoteResponse quote = service.latestQuote("eur-usd");

    assertThat(quote.symbol()).isEqualTo("EURUSD");
    assertThat(quote.bid()).isPositive();
    assertThat(quote.ask()).isGreaterThan(quote.bid());
    verify(symbolRepository).findBySymbol("EURUSD");
    verify(marketDataProvider).fetchLatestQuote("EURUSD", "C:EURUSD");
  }

  @Test
  void latestQuoteRejectsDemoFallbackWhenDisabled() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProviderSymbol("C:EURUSD");

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(null);
    when(marketDataProvider.fetchLatestQuote("EURUSD", "C:EURUSD")).thenReturn(Optional.empty());
    when(marketDataProvider.fetchIndicativeQuote(org.mockito.ArgumentMatchers.eq("EURUSD"), org.mockito.ArgumentMatchers.eq("C:EURUSD"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.empty());

    QuoteService service = new QuoteService(symbolRepository, marketDataProvider, redisTemplate, objectMapper);
    ReflectionTestUtils.setField(service, "demoQuotesEnabled", false);

    assertThatThrownBy(() -> service.latestQuote("EURUSD"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Quote provider unavailable");
  }

  @Test
  void latestQuoteCanReadProviderForexPairWithoutLocalTradeConfig() {
    QuoteResponse providerQuote = new QuoteResponse(
        "quote",
        "USDCHF",
        new BigDecimal("0.80418"),
        new BigDecimal("0.80422"),
        new BigDecimal("0.80420"),
        new BigDecimal("0.00004"),
        "massive-aggregate",
        1781222400000L
    );

    when(symbolRepository.findBySymbol("USDCHF")).thenReturn(Optional.empty());
    when(marketDataProvider.isConfigured()).thenReturn(true);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:USDCHF")).thenReturn(null);
    when(marketDataProvider.fetchLatestQuote("USDCHF", "C:USDCHF")).thenReturn(Optional.empty());
    when(marketDataProvider.fetchIndicativeQuote(org.mockito.ArgumentMatchers.eq("USDCHF"), org.mockito.ArgumentMatchers.eq("C:USDCHF"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.of(providerQuote));

    QuoteService service = new QuoteService(symbolRepository, marketDataProvider, redisTemplate, objectMapper);
    ReflectionTestUtils.setField(service, "demoQuotesEnabled", false);

    QuoteResponse quote = service.latestQuote("usd-chf");

    assertThat(quote).isEqualTo(providerQuote);
  }

  @Test
  void freshQuoteRefreshesStaleCachedQuoteBeforeTrading() throws Exception {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProviderSymbol("C:EURUSD");
    QuoteResponse stale = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.08318"),
        new BigDecimal("1.08322"),
        new BigDecimal("1.08320"),
        new BigDecimal("0.00004"),
        "cache",
        System.currentTimeMillis() - 5000
    );

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(objectMapper.writeValueAsString(stale));
    when(marketDataProvider.fetchLatestQuote("EURUSD", "C:EURUSD")).thenReturn(Optional.empty());
    when(marketDataProvider.fetchIndicativeQuote(org.mockito.ArgumentMatchers.eq("EURUSD"), org.mockito.ArgumentMatchers.eq("C:EURUSD"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.empty());

    QuoteService service = new QuoteService(symbolRepository, marketDataProvider, redisTemplate, objectMapper);
    ReflectionTestUtils.setField(service, "quoteStaleMs", 100);
    ReflectionTestUtils.setField(service, "demoQuotesEnabled", true);

    QuoteResponse quote = service.freshQuote("EURUSD");

    assertThat(quote.source()).isEqualTo("demo");
    assertThat(quote.timestamp()).isGreaterThan(stale.timestamp());
    verify(marketDataProvider).fetchLatestQuote("EURUSD", "C:EURUSD");
  }

  @Test
  void latestQuoteRefreshesStaleCachedDemoQuoteForMarketDisplay() throws Exception {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProviderSymbol("C:EURUSD");
    QuoteResponse stale = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.08191"),
        new BigDecimal("1.08195"),
        new BigDecimal("1.08193"),
        new BigDecimal("0.00004"),
        "demo-realtime",
        System.currentTimeMillis() - 5000
    );
    QuoteResponse providerQuote = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.15653"),
        new BigDecimal("1.15657"),
        new BigDecimal("1.15655"),
        new BigDecimal("0.00004"),
        "massive-aggregate",
        1781297940000L,
        new BigDecimal("-0.117324"),
        new BigDecimal("1.15800"),
        new BigDecimal("1.15500"),
        new BigDecimal("30176")
    );

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(objectMapper.writeValueAsString(stale));
    when(marketDataProvider.fetchLatestQuote("EURUSD", "C:EURUSD")).thenReturn(Optional.empty());
    when(marketDataProvider.fetchIndicativeQuote(org.mockito.ArgumentMatchers.eq("EURUSD"), org.mockito.ArgumentMatchers.eq("C:EURUSD"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.of(providerQuote));

    QuoteService service = new QuoteService(symbolRepository, marketDataProvider, redisTemplate, objectMapper);
    ReflectionTestUtils.setField(service, "quoteStaleMs", 100);
    ReflectionTestUtils.setField(service, "demoQuotesEnabled", false);

    QuoteResponse quote = service.latestQuote("EURUSD");

    assertThat(quote).isEqualTo(providerQuote);
    verify(marketDataProvider).fetchLatestQuote("EURUSD", "C:EURUSD");
  }

  @Test
  void latestQuoteUsesCachedExternalProviderQuoteForMarketDisplayWhenMarketIsClosed() throws Exception {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProviderSymbol("C:EURUSD");
    QuoteResponse cachedProviderQuote = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.15653"),
        new BigDecimal("1.15657"),
        new BigDecimal("1.15655"),
        new BigDecimal("0.00004"),
        "massive-aggregate",
        1781297940000L,
        new BigDecimal("-0.117324"),
        new BigDecimal("1.15800"),
        new BigDecimal("1.15500"),
        new BigDecimal("30176")
    );

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(objectMapper.writeValueAsString(cachedProviderQuote));

    QuoteService service = new QuoteService(symbolRepository, marketDataProvider, redisTemplate, objectMapper);
    ReflectionTestUtils.setField(service, "quoteStaleMs", 100);
    ReflectionTestUtils.setField(service, "demoQuotesEnabled", false);

    QuoteResponse quote = service.latestQuote("EURUSD");

    assertThat(quote).isEqualTo(cachedProviderQuote);
    verifyNoInteractions(marketDataProvider);
  }

  @Test
  void latestQuoteCachesFetchedProviderQuoteForMarketDisplay() throws Exception {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProviderSymbol("C:EURUSD");
    QuoteResponse providerQuote = new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.15653"),
        new BigDecimal("1.15657"),
        new BigDecimal("1.15655"),
        new BigDecimal("0.00004"),
        "massive-aggregate",
        1781297940000L
    );

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("quote:EURUSD")).thenReturn(null);
    when(marketDataProvider.fetchLatestQuote("EURUSD", "C:EURUSD")).thenReturn(Optional.of(providerQuote));

    QuoteService service = new QuoteService(symbolRepository, marketDataProvider, redisTemplate, objectMapper);
    ReflectionTestUtils.setField(service, "demoQuotesEnabled", false);

    QuoteResponse quote = service.latestQuote("EURUSD");

    assertThat(quote).isEqualTo(providerQuote);
    verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("quote:EURUSD"), anyString());
  }
}
