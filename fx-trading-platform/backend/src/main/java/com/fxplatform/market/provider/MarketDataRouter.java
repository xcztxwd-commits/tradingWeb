package com.fxplatform.market.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.dto.PerpetualReferenceResponse;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketBundleProducts;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.service.ProviderHealthRecorder;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.repository.FundingRateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class MarketDataRouter {

  private final ProviderResolver providerResolver;
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final ProviderHealthRecorder healthRecorder;
  private final MarketBundleResolver marketBundleResolver;
  private final FundingRateRepository fundingRateRepository;
  private final Clock clock;

  @Autowired
  public MarketDataRouter(
      ProviderResolver providerResolver,
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      ProviderHealthRecorder healthRecorder,
      MarketBundleResolver marketBundleResolver,
      FundingRateRepository fundingRateRepository
  ) {
    this(
        providerResolver,
        redisTemplate,
        objectMapper,
        healthRecorder,
        marketBundleResolver,
        fundingRateRepository,
        Clock.systemUTC());
  }

  public MarketDataRouter(ProviderResolver providerResolver) {
    this(providerResolver, null, null, ProviderHealthRecorder.noop(), null, null, Clock.systemUTC());
  }

  public MarketDataRouter(
      ProviderResolver providerResolver,
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      ProviderHealthRecorder healthRecorder
  ) {
    this(providerResolver, redisTemplate, objectMapper, healthRecorder, null, null, Clock.systemUTC());
  }

  public MarketDataRouter(
      ProviderResolver providerResolver,
      MarketBundleResolver marketBundleResolver,
      Clock clock
  ) {
    this(providerResolver, null, null, ProviderHealthRecorder.noop(), marketBundleResolver, null, clock);
  }

  public MarketDataRouter(
      ProviderResolver providerResolver,
      MarketBundleResolver marketBundleResolver,
      FundingRateRepository fundingRateRepository,
      Clock clock
  ) {
    this(
        providerResolver,
        null,
        null,
        ProviderHealthRecorder.noop(),
        marketBundleResolver,
        fundingRateRepository,
        clock);
  }

  private MarketDataRouter(
      ProviderResolver providerResolver,
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      ProviderHealthRecorder healthRecorder,
      MarketBundleResolver marketBundleResolver,
      FundingRateRepository fundingRateRepository,
      Clock clock
  ) {
    this.providerResolver = providerResolver;
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.healthRecorder = healthRecorder;
    this.marketBundleResolver = marketBundleResolver;
    this.fundingRateRepository = fundingRateRepository;
    this.clock = clock;
  }

  public QuoteResponse latestQuote(String symbol) {
    String normalized = SymbolNormalizer.normalize(symbol);
    if (usesAuthoritativeBundle(normalized)) {
      SymbolEntity platformSymbol = providerResolver.requireEnabledSymbol(normalized);
      if (!Boolean.TRUE.equals(platformSymbol.getQuoteEnabled())) {
        throw new BusinessException("SYMBOL_QUOTE_DISABLED", "Symbol quote is disabled");
      }
      if (MarketBundleProducts.isSpot(normalized)) {
        return MarketBundleAssembler.quote(
            marketBundleResolver.resolveSpot(normalized, defaultCandleRequest()), clock);
      }
      return MarketBundleAssembler.quote(
          marketBundleResolver.resolvePerp(normalized, defaultCandleRequest()), clock);
    }
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
    String normalized = SymbolNormalizer.normalize(symbol);
    if (usesAuthoritativeBundle(normalized)) {
      SymbolEntity platformSymbol = providerResolver.requireEnabledSymbol(normalized);
      if (!Boolean.TRUE.equals(platformSymbol.getChartEnabled())) {
        throw new BusinessException("SYMBOL_CHART_DISABLED", "Symbol chart is disabled");
      }
      CandleRequest request = new CandleRequest(timeframe, from, to);
      return MarketBundleProducts.isSpot(normalized)
          ? marketBundleResolver.resolveSpot(normalized, request).candles()
          : marketBundleResolver.resolvePerp(normalized, request).candles();
    }
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
    String normalized = SymbolNormalizer.normalize(symbol);
    if (usesAuthoritativeBundle(normalized)) {
      SymbolEntity platformSymbol = providerResolver.requireEnabledSymbol(normalized);
      if (!Boolean.TRUE.equals(platformSymbol.getOrderBookEnabled())) {
        throw new BusinessException("SYMBOL_ORDER_BOOK_DISABLED", "Symbol order book is disabled");
      }
      return MarketBundleProducts.isSpot(normalized)
          ? marketBundleResolver.resolveSpot(normalized, defaultCandleRequest()).orderBook()
          : marketBundleResolver.resolvePerp(normalized, defaultCandleRequest()).orderBook();
    }
    ProviderResolution resolution = providerResolver.resolve(symbol, MarketDataCapability.ORDER_BOOK);
    if (!Boolean.TRUE.equals(resolution.symbol().getOrderBookEnabled())) {
      throw new BusinessException("SYMBOL_ORDER_BOOK_DISABLED", "Symbol order book is disabled");
    }
    return resolution.adapter()
        .fetchOrderBook(resolution.symbol().getSymbol(), resolution.providerSymbol())
        .orElseThrow(() -> new BusinessException("MARKET_PROVIDER_UNAVAILABLE", "Market data provider unavailable"));
  }

  public List<RecentTradeResponse> recentTrades(String symbol, int limit) {
    String normalized = SymbolNormalizer.normalize(symbol);
    if (usesAuthoritativeBundle(normalized)) {
      providerResolver.requireEnabledSymbol(normalized);
      List<RecentTradeResponse> trades = MarketBundleProducts.isSpot(normalized)
          ? marketBundleResolver.resolveSpot(normalized, defaultCandleRequest()).recentTrades()
          : marketBundleResolver.resolvePerp(normalized, defaultCandleRequest()).recentTrades();
      return limit <= 0 ? List.of() : trades.stream().limit(limit).toList();
    }
    ProviderResolution resolution = providerResolver.resolve(symbol, MarketDataCapability.TRADES);
    return resolution.adapter().fetchRecentTrades(resolution.symbol().getSymbol(), resolution.providerSymbol(), limit);
  }

  public Map<String, QuoteResponse> snapshots(Collection<String> symbols) {
    List<String> normalizedSymbols = normalizeSymbols(symbols);
    if (normalizedSymbols.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<String, QuoteResponse> snapshots = new LinkedHashMap<>();
    List<String> legacySymbols = new ArrayList<>();
    for (String symbol : normalizedSymbols) {
      if (usesAuthoritativeBundle(symbol)) {
        try {
          QuoteResponse quote = latestQuote(symbol);
          snapshots.put(symbol, quote);
          cacheQuote(symbol, quote);
        } catch (RuntimeException ignored) {
          // Batch display queries omit unavailable symbols, matching legacy behavior.
        }
      } else {
        legacySymbols.add(symbol);
      }
    }
    if (legacySymbols.isEmpty()) {
      return snapshots;
    }
    Map<String, ProviderResolution> resolutions = providerResolver.resolveAll(legacySymbols, MarketDataCapability.QUOTE);
    LinkedHashMap<String, LinkedHashMap<String, ProviderResolution>> grouped = new LinkedHashMap<>();
    resolutions.forEach((symbol, resolution) -> {
      if (Boolean.TRUE.equals(resolution.symbol().getQuoteEnabled())) {
        grouped.computeIfAbsent(resolution.provider().getCode(), ignored -> new LinkedHashMap<>())
            .put(symbol, resolution);
      }
    });

    grouped.values().forEach(group -> collectProviderSnapshots(group, snapshots));
    return snapshots;
  }

  public PerpetualReferenceResponse perpetualReference(String symbol) {
    String normalized = SymbolNormalizer.normalize(symbol);
    if (!MarketBundleProducts.isPerpetual(normalized) || marketBundleResolver == null) {
      throw new BusinessException("PRODUCT_NOT_ALLOWED", "Perpetual reference is available only for P0 perpetuals");
    }
    SymbolEntity platformSymbol = providerResolver.requireEnabledSymbol(normalized);
    if (!Boolean.TRUE.equals(platformSymbol.getQuoteEnabled())) {
      throw new BusinessException("SYMBOL_QUOTE_DISABLED", "Symbol quote is disabled");
    }
    PerpetualMarketBundle bundle = marketBundleResolver.resolvePerp(normalized, defaultCandleRequest());
    FundingRateEntity funding = fundingRateRepository == null
        ? null
        : fundingRateRepository.findLatestBySymbol(normalized).orElse(null);
    return new PerpetualReferenceResponse(
        bundle.platformSymbol(),
        bundle.providerSymbol(),
        bundle.providerCode(),
        bundle.sourceMode(),
        bundle.bid(),
        bundle.ask(),
        bundle.last(),
        bundle.mark(),
        bundle.index(),
        bundle.asOf(),
        bundle.expiresAt(),
        !clock.instant().isBefore(bundle.expiresAt()),
        funding == null ? null : funding.getFundingRate(),
        funding == null ? null : funding.getFundingTime(),
        funding == null ? null : funding.getNextFundingTime(),
        funding == null ? null : funding.getProviderCode());
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

  private boolean usesAuthoritativeBundle(String symbol) {
    return marketBundleResolver != null && MarketBundleProducts.isP0(symbol);
  }

  private CandleRequest defaultCandleRequest() {
    Instant to = clock.instant();
    return new CandleRequest("1m", to.minus(Duration.ofHours(1)), to);
  }
}
