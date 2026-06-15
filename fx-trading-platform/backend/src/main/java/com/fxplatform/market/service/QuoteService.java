package com.fxplatform.market.service;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.adapter.MarketDataProvider;
import com.fxplatform.market.dto.MarketStatusResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * QuoteService 是行情模块的业务服务。
 */
@Service
@RequiredArgsConstructor
public class QuoteService {

  private final SymbolRepository symbolRepository;
  private final MarketDataProvider marketDataProvider;
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  @Value("${massive.quote-stale-ms}")
  private long quoteStaleMs;

  @Value("${market.demo-quotes.enabled:false}")
  private boolean demoQuotesEnabled = false;

  public QuoteResponse latestQuote(String symbol) {
    String normalizedSymbol = SymbolNormalizer.normalize(symbol);
    Optional<SymbolEntity> symbolEntity = symbolRepository.findBySymbol(normalizedSymbol);
    String providerSymbol = symbolEntity
        .map(SymbolEntity::getProviderSymbol)
        .orElseGet(() -> defaultForexProviderSymbol(normalizedSymbol));

    QuoteResponse cached = readCached(normalizedSymbol);
    if (cached != null && canUseCachedForDisplay(cached)) {
      return cached;
    }

    QuoteResponse quote = fetchLatest(normalizedSymbol, providerSymbol);
    cache(quote);
    return quote;
  }

  public QuoteResponse freshQuote(String symbol) {
    String normalizedSymbol = SymbolNormalizer.normalize(symbol);
    SymbolEntity symbolEntity = symbolRepository.findBySymbol(normalizedSymbol)
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));

    QuoteResponse cached = readCached(normalizedSymbol);
    if (cached != null && !isStale(cached)) {
      return cached;
    }

    QuoteResponse quote = fetchLatest(normalizedSymbol, symbolEntity.getProviderSymbol());
    if (isStale(quote)) {
      throw new BusinessException("QUOTE_STALE", "Quote is stale");
    }
    return quote;
  }

  public void cache(QuoteResponse quote) {
    try {
      redisTemplate.opsForValue().set(key(quote.symbol()), objectMapper.writeValueAsString(quote));
    } catch (JsonProcessingException ex) {
      throw new BusinessException("QUOTE_CACHE_ERROR", ex.getMessage());
    }
  }

  public MarketStatusResponse status() {
    return new MarketStatusResponse(
        marketDataProvider.isConfigured(),
        true,
        quoteStaleMs,
        marketDataProvider.isConfigured()
            ? "MASSIVE_CONFIGURED"
            : demoQuotesEnabled ? "USING_DEMO_QUOTES" : "QUOTE_PROVIDER_REQUIRED");
  }

  private QuoteResponse readCached(String symbol) {
    String json = redisTemplate.opsForValue().get(key(symbol));
    if (StrUtil.isBlank(json)) {
      return null;
    }
    try {
      return objectMapper.readValue(json, QuoteResponse.class);
    } catch (JsonProcessingException ex) {
      return null;
    }
  }

  private QuoteResponse fetchLatest(String symbol, String providerSymbol) {
    return marketDataProvider.fetchLatestQuote(symbol, providerSymbol)
        .or(() -> marketDataProvider.fetchIndicativeQuote(symbol, providerSymbol, Instant.now()))
        .orElseGet(() -> {
          if (!demoQuotesEnabled) {
            throw new BusinessException("QUOTE_PROVIDER_UNAVAILABLE", "Quote provider unavailable");
          }
          return mockQuote(symbol);
        });
  }

  private boolean isStale(QuoteResponse quote) {
    long ageMs = DateUtil.date().toInstant().toEpochMilli() - quote.timestamp();
    return ageMs > quoteStaleMs;
  }

  private boolean canUseCachedForDisplay(QuoteResponse quote) {
    if (isDemoSource(quote.source())) {
      return demoQuotesEnabled && !isStale(quote);
    }
    if (isProviderBackedSource(quote.source())) {
      return true;
    }
    return !isStale(quote);
  }

  private boolean isDemoSource(String source) {
    return source != null && source.startsWith("demo");
  }

  private boolean isProviderBackedSource(String source) {
    return StrUtil.isNotBlank(source) && !"cache".equals(source);
  }

  private QuoteResponse mockQuote(String symbol) {
    BigDecimal mid = switch (symbol) {
      case "GBPUSD" -> new BigDecimal("1.27120");
      case "USDJPY" -> new BigDecimal("156.420");
      case "AUDUSD" -> new BigDecimal("0.66420");
      default -> new BigDecimal("1.08320");
    };
    BigDecimal halfSpread = symbol.endsWith("JPY") ? new BigDecimal("0.005") : new BigDecimal("0.00002");
    BigDecimal bid = mid.subtract(halfSpread).setScale(10, RoundingMode.HALF_UP);
    BigDecimal ask = mid.add(halfSpread).setScale(10, RoundingMode.HALF_UP);
    return new QuoteResponse("quote", symbol, bid, ask, mid, ask.subtract(bid), "demo", DateUtil.date().toInstant().toEpochMilli());
  }

  private String key(String symbol) {
    return "quote:" + symbol;
  }

  private String defaultForexProviderSymbol(String normalizedSymbol) {
    if (normalizedSymbol.length() != 6 || !marketDataProvider.isConfigured()) {
      throw new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found");
    }
    return "C:" + normalizedSymbol.toUpperCase(Locale.ROOT);
  }

}
