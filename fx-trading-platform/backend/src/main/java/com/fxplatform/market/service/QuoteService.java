package com.fxplatform.market.service;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.dto.MarketStatusResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.model.MarketBundleProducts;
import com.fxplatform.market.provider.MarketDataRouter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * QuoteService 是行情模块的业务服务。
 */
@Service
public class QuoteService {

  private final MarketDataRouter marketDataRouter;
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final QuoteFreshnessAuthority freshnessAuthority;

  @Autowired
  public QuoteService(
      MarketDataRouter marketDataRouter,
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      QuoteFreshnessAuthority freshnessAuthority
  ) {
    this.marketDataRouter = Objects.requireNonNull(marketDataRouter, "marketDataRouter");
    this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.freshnessAuthority = Objects.requireNonNull(freshnessAuthority, "freshnessAuthority");
  }

  public QuoteService(
      MarketDataRouter marketDataRouter,
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper
  ) {
    this(
        marketDataRouter,
        redisTemplate,
        objectMapper,
        new WallClockQuoteFreshnessAuthority());
  }

  @Value("${market.quote-stale-ms:${massive.quote-stale-ms:3000}}")
  private long quoteStaleMs;

  @Value("${market.demo-quotes.enabled:false}")
  private boolean demoQuotesEnabled = false;

  public QuoteResponse latestQuote(String symbol) {
    String normalizedSymbol = SymbolNormalizer.normalize(symbol);

    if (MarketBundleProducts.isP0(normalizedSymbol)) {
      QuoteResponse quote = fetchLatest(normalizedSymbol);
      cacheAuthoritative(quote);
      return quote;
    }

    QuoteResponse cached = readCached(normalizedSymbol);
    if (cached != null && canUseCachedForDisplay(cached)) {
      return cached;
    }

    QuoteResponse quote = fetchLatest(normalizedSymbol);
    cache(quote);
    return quote;
  }

  public Map<String, QuoteResponse> latestQuotes(Collection<String> symbols) {
    List<String> normalizedSymbols = normalizeSymbols(symbols);
    LinkedHashMap<String, QuoteResponse> quotes = new LinkedHashMap<>();
    ArrayList<String> missingSymbols = new ArrayList<>();

    for (String symbol : normalizedSymbols) {
      if (MarketBundleProducts.isP0(symbol)) {
        try {
          QuoteResponse quote = marketDataRouter.latestQuote(symbol);
          cacheAuthoritative(quote);
          quotes.put(symbol, quote);
        } catch (BusinessException ignored) {
          // Keep batch display behavior: omit unavailable symbols.
        }
        continue;
      }
      QuoteResponse cached = readCached(symbol);
      if (cached != null && canUseCachedForDisplay(cached)) {
        quotes.put(symbol, cached);
      } else {
        missingSymbols.add(symbol);
      }
    }

    if (!missingSymbols.isEmpty()) {
      Map<String, QuoteResponse> fetchedQuotes = marketDataRouter.snapshots(missingSymbols);
      for (String symbol : missingSymbols) {
        QuoteResponse quote = fetchedQuotes.get(symbol);
        if (quote == null) {
          continue;
        }
        cache(quote);
        quotes.put(symbol, quote);
      }
    }

    return quotes;
  }

  public QuoteResponse freshQuote(String symbol) {
    String normalizedSymbol = SymbolNormalizer.normalize(symbol);
    if (MarketBundleProducts.isP0(normalizedSymbol)) {
      QuoteResponse quote = fetchLatest(normalizedSymbol);
      if (isStale(quote)) {
        throw new BusinessException("QUOTE_STALE", "Quote is stale");
      }
      return quote;
    }
    QuoteResponse cached = readCached(normalizedSymbol);
    if (cached != null && !isStale(cached)) {
      return cached;
    }

    QuoteResponse quote = fetchLatest(normalizedSymbol);
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

  private void cacheAuthoritative(QuoteResponse quote) {
    try {
      cache(quote);
    } catch (RuntimeException ignored) {
      // Redis is only a display cache for P0 bundles; a cache write must not hide valid market data.
    }
  }

  public MarketStatusResponse status() {
    return new MarketStatusResponse(
        true,
        true,
        quoteStaleMs,
        "PROVIDER_ROUTING_ENABLED",
        demoQuotesEnabled,
        "provider-router",
        "configured-by-admin",
        null,
        "Providers are resolved by admin-managed symbol bindings");
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

  private QuoteResponse fetchLatest(String symbol) {
    try {
      QuoteResponse quote = marketDataRouter.latestQuote(symbol);
      if (demoQuotesEnabled && isStale(quote)) {
        return mockQuote(symbol);
      }
      return quote;
    } catch (BusinessException ex) {
      if (MarketBundleProducts.isP0(symbol) || !demoQuotesEnabled || !allowsDemoFallback(ex.getCode())) {
        throw ex;
      }
      return mockQuote(symbol);
    }
  }

  private boolean allowsDemoFallback(String code) {
    return "MARKET_PROVIDER_UNAVAILABLE".equals(code)
        || "MARKET_PROVIDER_BINDING_NOT_FOUND".equals(code);
  }

  private boolean isStale(QuoteResponse quote) {
    return freshnessAuthority.isStale(quote, quoteStaleMs);
  }

  private boolean canUseCachedForDisplay(QuoteResponse quote) {
    if (isDemoSource(quote.source())) {
      return demoQuotesEnabled && !isStale(quote);
    }
    if (isProviderBackedSource(quote.source())) {
      return !isStale(quote);
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

  private List<String> normalizeSymbols(Collection<String> symbols) {
    ArrayList<String> normalizedSymbols = new ArrayList<>();
    for (String symbol : symbols) {
      String normalizedSymbol = SymbolNormalizer.normalize(symbol);
      if (!normalizedSymbol.isBlank() && !normalizedSymbols.contains(normalizedSymbol)) {
        normalizedSymbols.add(normalizedSymbol);
      }
    }
    return normalizedSymbols;
  }

}
