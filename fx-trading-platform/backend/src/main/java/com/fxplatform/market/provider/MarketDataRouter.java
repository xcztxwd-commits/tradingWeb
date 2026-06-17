package com.fxplatform.market.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.service.ProviderHealthRecorder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class MarketDataRouter {

  private final ProviderResolver providerResolver;
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final ProviderHealthRecorder healthRecorder;

  public MarketDataRouter(ProviderResolver providerResolver) {
    this(providerResolver, null, null, ProviderHealthRecorder.noop());
  }

  public QuoteResponse latestQuote(String symbol) {
    ProviderResolution resolution = providerResolver.resolve(symbol, MarketDataCapability.QUOTE);
    if (!Boolean.TRUE.equals(resolution.symbol().getQuoteEnabled())) {
      throw new BusinessException("SYMBOL_QUOTE_DISABLED", "Symbol quote is disabled");
    }
    long startedAt = System.currentTimeMillis();
    QuoteResponse quote = resolution.adapter()
        .fetchLatestQuote(resolution.symbol().getSymbol(), resolution.providerSymbol())
        .or(() -> resolution.adapter().fetchIndicativeQuote(resolution.symbol().getSymbol(), resolution.providerSymbol(), Instant.now()))
        .orElse(null);
    long latencyMs = System.currentTimeMillis() - startedAt;
    if (quote == null) {
      healthRecorder.recordFailure(resolution.provider(), latencyMs);
      throw new BusinessException("MARKET_PROVIDER_UNAVAILABLE", "Market data provider unavailable");
    }
    healthRecorder.recordQuoteSuccess(resolution.provider(), quote, latencyMs);
    return quote;
  }

  public List<CandleResponse> candles(String symbol, String timeframe, Instant from, Instant to) {
    ProviderResolution resolution = providerResolver.resolve(symbol, MarketDataCapability.CANDLES);
    if (!Boolean.TRUE.equals(resolution.symbol().getChartEnabled())) {
      throw new BusinessException("SYMBOL_CHART_DISABLED", "Symbol chart is disabled");
    }
    return resolution.adapter().fetchCandles(
        resolution.symbol().getSymbol(),
        resolution.providerSymbol(),
        timeframe,
        from,
        to);
  }

  public MarketDepthResponse orderBook(String symbol) {
    ProviderResolution resolution = providerResolver.resolve(symbol, MarketDataCapability.ORDER_BOOK);
    if (!Boolean.TRUE.equals(resolution.symbol().getOrderBookEnabled())) {
      throw new BusinessException("SYMBOL_ORDER_BOOK_DISABLED", "Symbol order book is disabled");
    }
    return resolution.adapter()
        .fetchOrderBook(resolution.symbol().getSymbol(), resolution.providerSymbol())
        .orElseThrow(() -> new BusinessException("MARKET_PROVIDER_UNAVAILABLE", "Market data provider unavailable"));
  }

  public List<RecentTradeResponse> recentTrades(String symbol, int limit) {
    ProviderResolution resolution = providerResolver.resolve(symbol, MarketDataCapability.TRADES);
    return resolution.adapter().fetchRecentTrades(resolution.symbol().getSymbol(), resolution.providerSymbol(), limit);
  }

  public Map<String, QuoteResponse> snapshots(Collection<String> symbols) {
    List<String> normalizedSymbols = normalizeSymbols(symbols);
    if (normalizedSymbols.isEmpty()) {
      return Map.of();
    }
    Map<String, ProviderResolution> resolutions = providerResolver.resolveAll(normalizedSymbols, MarketDataCapability.QUOTE);
    LinkedHashMap<String, LinkedHashMap<String, ProviderResolution>> grouped = new LinkedHashMap<>();
    resolutions.forEach((symbol, resolution) -> {
      if (Boolean.TRUE.equals(resolution.symbol().getQuoteEnabled())) {
        grouped.computeIfAbsent(resolution.provider().getCode(), ignored -> new LinkedHashMap<>())
            .put(symbol, resolution);
      }
    });

    LinkedHashMap<String, QuoteResponse> snapshots = new LinkedHashMap<>();
    grouped.values().forEach(group -> collectProviderSnapshots(group, snapshots));
    return snapshots;
  }

  public Map<String, QuoteResponse> snapshots(String providerCode, String assetClass, int limit) {
    return Map.of();
  }

  private List<String> normalizeSymbols(Collection<String> symbols) {
    if (symbols == null || symbols.isEmpty()) {
      return List.of();
    }
    ArrayList<String> normalized = new ArrayList<>();
    for (String symbol : symbols) {
      String normalizedSymbol = SymbolNormalizer.normalize(symbol);
      if (!normalizedSymbol.isBlank() && !normalized.contains(normalizedSymbol)) {
        normalized.add(normalizedSymbol);
      }
    }
    return normalized;
  }

  private void collectProviderSnapshots(
      LinkedHashMap<String, ProviderResolution> resolutions,
      LinkedHashMap<String, QuoteResponse> snapshots
  ) {
    if (resolutions.isEmpty()) {
      return;
    }
    ProviderResolution first = resolutions.values().iterator().next();
    LinkedHashMap<String, String> providerSymbolsBySymbol = new LinkedHashMap<>();
    resolutions.forEach((symbol, resolution) -> providerSymbolsBySymbol.put(symbol, resolution.providerSymbol()));
    long startedAt = System.currentTimeMillis();
    try {
      Map<String, QuoteResponse> providerQuotes = first.adapter().fetchLatestQuotes(providerSymbolsBySymbol);
      if (providerQuotes.isEmpty()) {
        healthRecorder.recordFailure(first.provider(), System.currentTimeMillis() - startedAt);
        return;
      }
      providerQuotes.forEach((symbol, quote) -> {
        snapshots.put(symbol, quote);
        cacheQuote(symbol, quote);
        healthRecorder.recordQuoteSuccess(first.provider(), quote, System.currentTimeMillis() - startedAt);
      });
    } catch (RuntimeException ex) {
      healthRecorder.recordFailure(first.provider(), System.currentTimeMillis() - startedAt);
    }
  }

  private void cacheQuote(String symbol, QuoteResponse quote) {
    if (redisTemplate == null || objectMapper == null) {
      return;
    }
    try {
      redisTemplate.opsForValue().set("quote:" + symbol, objectMapper.writeValueAsString(quote));
    } catch (JsonProcessingException | RuntimeException ignored) {
      // Display snapshots still return provider data when Redis is unavailable.
    }
  }
}
