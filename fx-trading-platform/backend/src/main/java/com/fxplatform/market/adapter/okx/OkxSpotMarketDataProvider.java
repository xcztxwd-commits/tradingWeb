package com.fxplatform.market.adapter.okx;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleAssembler;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.MarketDataDurations;
import com.fxplatform.market.provider.MarketDataProviderAdapter;
import com.fxplatform.market.provider.MarketRequestDeadline;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OkxSpotMarketDataProvider implements MarketDataProviderAdapter {

  private static final BigDecimal DEFAULT_MIN_LOT = new BigDecimal("0.01");
  private static final BigDecimal DEFAULT_MAX_LOT = new BigDecimal("100");
  private static final int DEFAULT_LEVERAGE = 20;
  private static final List<CryptoSymbol> CRYPTO_SYMBOLS = List.of(
      new CryptoSymbol("BTCUSDT", "BTC-USDT", "Bitcoin / Tether", "BTC", "USDT"),
      new CryptoSymbol("ETHUSDT", "ETH-USDT", "Ethereum / Tether", "ETH", "USDT"),
      new CryptoSymbol("BNBUSDT", "BNB-USDT", "BNB / Tether", "BNB", "USDT"),
      new CryptoSymbol("SOLUSDT", "SOL-USDT", "Solana / Tether", "SOL", "USDT"),
      new CryptoSymbol("XRPUSDT", "XRP-USDT", "XRP / Tether", "XRP", "USDT"));

  private final String restBaseUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Duration freshness;
  private final Duration requestTimeout;

  @Autowired
  public OkxSpotMarketDataProvider(
      @Value("${okx.rest-base-url:https://www.okx.com}") String restBaseUrl,
      @Value("${market.bundle-freshness:3s}") String freshnessValue,
      @Value("${market.public-http-timeout:2s}") String requestTimeoutValue
  ) {
    this(
        restBaseUrl,
        newHttpClient(MarketDataDurations.parsePositive(requestTimeoutValue, Duration.ofSeconds(2))),
        new ObjectMapper(),
        Clock.systemUTC(),
        MarketDataDurations.parsePositive(freshnessValue, Duration.ofSeconds(3)),
        MarketDataDurations.parsePositive(requestTimeoutValue, Duration.ofSeconds(2)));
  }

  public OkxSpotMarketDataProvider(String restBaseUrl) {
    this(restBaseUrl, newHttpClient(Duration.ofSeconds(2)),
        new ObjectMapper(), Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(2));
  }

  public OkxSpotMarketDataProvider(
      String restBaseUrl,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Clock clock,
      Duration freshness
  ) {
    this(restBaseUrl, httpClient, objectMapper, clock, freshness, Duration.ofSeconds(2));
  }

  public OkxSpotMarketDataProvider(
      String restBaseUrl,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Clock clock,
      Duration freshness,
      Duration requestTimeout
  ) {
    this.restBaseUrl = restBaseUrl;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.freshness = MarketDataDurations.positive(freshness, Duration.ofSeconds(3));
    this.requestTimeout = MarketDataDurations.positive(requestTimeout, Duration.ofSeconds(2));
  }

  @Override
  public String code() {
    return "okx";
  }

  @Override
  public Set<MarketDataCapability> capabilities() {
    return Set.of(
        MarketDataCapability.SYMBOLS,
        MarketDataCapability.QUOTE,
        MarketDataCapability.SNAPSHOT,
        MarketDataCapability.CANDLES,
        MarketDataCapability.ORDER_BOOK,
        MarketDataCapability.TRADES);
  }

  @Override
  public boolean configured() {
    return StrUtil.isNotBlank(restBaseUrl);
  }

  @Override
  public Optional<QuoteResponse> fetchLatestQuote(String symbol, String providerSymbol) {
    return fetchLatestQuote(symbol, providerSymbol, null);
  }

  private Optional<QuoteResponse> fetchLatestQuote(
      String symbol,
      String providerSymbol,
      MarketRequestDeadline deadline
  ) {
    if (!configured()) {
      return Optional.empty();
    }
    String instId = normalizeInstrument(StrUtil.blankToDefault(providerSymbol, symbol));
    if (!instId.endsWith("-USDT")) {
      return Optional.empty();
    }
    return sendJson(tickerUri(instId), deadline)
        .flatMap(body -> firstData(body).flatMap(data -> parseTicker(symbol, data)));
  }

  @Override
  public Optional<QuoteResponse> fetchIndicativeQuote(String symbol, String providerSymbol, Instant to) {
    return fetchLatestQuote(symbol, providerSymbol);
  }

  @Override
  public Map<String, QuoteResponse> fetchLatestQuotes(Map<String, String> providerSymbolsBySymbol) {
    if (!configured() || providerSymbolsBySymbol == null || providerSymbolsBySymbol.isEmpty()) {
      return Map.of();
    }
    Map<String, String> platformSymbolByInstrument = new LinkedHashMap<>();
    providerSymbolsBySymbol.forEach((symbol, providerSymbol) ->
        platformSymbolByInstrument.put(
            normalizeInstrument(StrUtil.blankToDefault(providerSymbol, symbol)),
            normalizePlatformSymbol(symbol)));
    return sendJson(tickersUri())
        .map(body -> parseTickerBatch(body, platformSymbolByInstrument))
        .orElse(Map.of());
  }

  @Override
  public Map<String, QuoteResponse> fetchMarketSnapshots(String assetClass, int limit) {
    if (!"CRYPTO".equals(normalizeAssetClass(assetClass)) || limit <= 0) {
      return Map.of();
    }
    Map<String, QuoteResponse> snapshots = new LinkedHashMap<>();
    for (SymbolResponse symbol : fetchSymbols(assetClass, limit)) {
      fetchLatestQuote(symbol.symbol(), symbol.providerSymbol())
          .ifPresent(quote -> snapshots.put(quote.symbol(), quote));
    }
    return Map.copyOf(snapshots);
  }

  @Override
  public List<SymbolResponse> fetchSymbols(String assetClass, int limit) {
    if (!"CRYPTO".equals(normalizeAssetClass(assetClass)) || limit <= 0) {
      return List.of();
    }
    return CRYPTO_SYMBOLS.stream()
        .limit(limit)
        .map(this::toSymbol)
        .toList();
  }

  @Override
  public List<CandleResponse> fetchCandles(String symbol, String providerSymbol, String timeframe, Instant from, Instant to) {
    return fetchCandles(symbol, providerSymbol, timeframe, from, to, null);
  }

  private List<CandleResponse> fetchCandles(
      String symbol,
      String providerSymbol,
      String timeframe,
      Instant from,
      Instant to,
      MarketRequestDeadline deadline
  ) {
    if (!configured() || from == null || to == null || !from.isBefore(to)) {
      return List.of();
    }
    String instId = normalizeInstrument(StrUtil.blankToDefault(providerSymbol, symbol));
    return sendJson(candlesUri(instId, timeframe, from, to), deadline)
        .map(this::parseCandles)
        .orElse(List.of());
  }

  @Override
  public Optional<MarketDepthResponse> fetchOrderBook(String symbol, String providerSymbol) {
    return fetchOrderBook(symbol, providerSymbol, null);
  }

  private Optional<MarketDepthResponse> fetchOrderBook(
      String symbol,
      String providerSymbol,
      MarketRequestDeadline deadline
  ) {
    if (!configured()) {
      return Optional.empty();
    }
    String instId = normalizeInstrument(StrUtil.blankToDefault(providerSymbol, symbol));
    return sendJson(booksUri(instId), deadline)
        .flatMap(body -> firstData(body).flatMap(data -> parseDepth(symbol, data)));
  }

  @Override
  public List<RecentTradeResponse> fetchRecentTrades(String symbol, String providerSymbol, int limit) {
    return fetchRecentTrades(symbol, providerSymbol, limit, null);
  }

  private List<RecentTradeResponse> fetchRecentTrades(
      String symbol,
      String providerSymbol,
      int limit,
      MarketRequestDeadline deadline
  ) {
    if (!configured()) {
      return List.of();
    }
    String instId = normalizeInstrument(StrUtil.blankToDefault(providerSymbol, symbol));
    return sendJson(tradesUri(instId, limit), deadline)
        .map(body -> parseTrades(symbol, body))
        .orElse(List.of());
  }

  @Override
  public Optional<SpotMarketBundle> fetchSpotBundle(
      String platformSymbol,
      String providerSymbol,
      CandleRequest candleRequest
  ) {
    if (!configured() || candleRequest == null) {
      return Optional.empty();
    }
    String instrument = normalizeInstrument(StrUtil.blankToDefault(providerSymbol, platformSymbol));
    MarketRequestDeadline deadline = MarketRequestDeadline.start(freshness);
    Optional<QuoteResponse> quote = fetchLatestQuote(platformSymbol, instrument, deadline);
    Instant quoteFetchedAt = clock.instant();
    if (quote.isEmpty()) {
      return Optional.empty();
    }
    Optional<MarketDepthResponse> depth = fetchOrderBook(platformSymbol, instrument, deadline);
    Instant depthFetchedAt = clock.instant();
    if (depth.isEmpty()) {
      return Optional.empty();
    }
    List<RecentTradeResponse> trades = fetchRecentTrades(platformSymbol, instrument, 40, deadline);
    Instant tradesFetchedAt = clock.instant();
    if (trades.isEmpty()) {
      return Optional.empty();
    }
    List<CandleResponse> candles = fetchCandles(
        platformSymbol, instrument, candleRequest.timeframe(), candleRequest.from(), candleRequest.to(), deadline);
    Instant candlesFetchedAt = clock.instant();
    if (candles.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(MarketBundleAssembler.spot(
        normalizePlatformSymbol(platformSymbol),
        instrument,
        code(),
        MarketSourceMode.PUBLIC_EXTERNAL,
        quote.get(),
        depth.get(),
        trades,
        candles,
        MarketBundleAssembler.ComponentObservations.spot(
            MarketBundleAssembler.snapshotObservedAt(quote.get().timestamp(), quoteFetchedAt),
            MarketBundleAssembler.snapshotObservedAt(depth.get().timestamp(), depthFetchedAt),
            tradesFetchedAt,
            candlesFetchedAt),
        freshness));
  }

  private URI tickerUri(String instId) {
    return URI.create("%s/api/v5/market/ticker?instId=%s".formatted(baseUrl(), instId));
  }

  private URI tickersUri() {
    return URI.create("%s/api/v5/market/tickers?instType=SPOT".formatted(baseUrl()));
  }

  private URI candlesUri(String instId, String timeframe, Instant from, Instant to) {
    return URI.create("%s/api/v5/market/candles?instId=%s&bar=%s&after=%d&before=%d&limit=100".formatted(
        baseUrl(),
        instId,
        normalizeInterval(timeframe),
        to.toEpochMilli(),
        from.toEpochMilli()));
  }

  private URI booksUri(String instId) {
    return URI.create("%s/api/v5/market/books?instId=%s&sz=20".formatted(baseUrl(), instId));
  }

  private URI tradesUri(String instId, int limit) {
    int normalizedLimit = Math.min(Math.max(limit, 1), 100);
    return URI.create("%s/api/v5/market/trades?instId=%s&limit=%d".formatted(baseUrl(), instId, normalizedLimit));
  }

  private String baseUrl() {
    return restBaseUrl.endsWith("/") ? restBaseUrl.substring(0, restBaseUrl.length() - 1) : restBaseUrl;
  }

  private Optional<JsonNode> sendJson(URI uri) {
    return sendJson(uri, null);
  }

  private Optional<JsonNode> sendJson(URI uri, MarketRequestDeadline deadline) {
    Optional<Duration> timeout = deadline == null
        ? Optional.of(requestTimeout)
        : deadline.remainingTimeout(requestTimeout);
    if (timeout.isEmpty()) {
      return Optional.empty();
    }
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(timeout.get())
        .header("Accept", "application/json")
        .GET()
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return Optional.empty();
      }
      if (deadline != null && deadline.expired()) {
        return Optional.empty();
      }
      JsonNode body = objectMapper.readTree(response.body());
      if (!"0".equals(body.path("code").asText("0"))) {
        return Optional.empty();
      }
      return Optional.of(body);
    } catch (IOException ex) {
      return Optional.empty();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private Optional<JsonNode> firstData(JsonNode body) {
    JsonNode data = body.path("data");
    if (!data.isArray() || data.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(data.get(0));
  }

  private Optional<QuoteResponse> parseTicker(String requestedSymbol, JsonNode data) {
    if (!data.hasNonNull("bidPx") || !data.hasNonNull("askPx") || !data.hasNonNull("last")) {
      return Optional.empty();
    }
    Optional<Long> observedAt = positiveEpochMillis(data, "ts");
    if (observedAt.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal bid = decimalText(data, "bidPx");
    BigDecimal ask = decimalText(data, "askPx");
    BigDecimal mid = decimalText(data, "last");
    return Optional.of(new QuoteResponse(
        "quote",
        normalizePlatformSymbol(requestedSymbol),
        bid,
        ask,
        mid,
        ask.subtract(bid),
        "okx-spot",
        observedAt.get(),
        changePercent(data, mid),
        decimalValue(data, "high24h").orElse(mid),
        decimalValue(data, "low24h").orElse(mid),
        decimalValue(data, "vol24h").orElse(null)));
  }

  private static HttpClient newHttpClient(Duration timeout) {
    return HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  private BigDecimal changePercent(JsonNode data, BigDecimal last) {
    Optional<BigDecimal> open = decimalValue(data, "open24h");
    if (open.isEmpty() || open.get().signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return last.subtract(open.get())
        .divide(open.get(), 10, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100));
  }

  private Map<String, QuoteResponse> parseTickerBatch(JsonNode body, Map<String, String> platformSymbolByInstrument) {
    JsonNode data = body.path("data");
    if (!data.isArray()) {
      return Map.of();
    }
    Map<String, QuoteResponse> quotes = new LinkedHashMap<>();
    for (JsonNode item : data) {
      String instId = normalizeInstrument(item.path("instId").asText(""));
      String platformSymbol = platformSymbolByInstrument.get(instId);
      if (platformSymbol == null) {
        continue;
      }
      parseTicker(platformSymbol, item).ifPresent(quote -> quotes.put(quote.symbol(), quote));
    }
    return Map.copyOf(quotes);
  }

  private List<CandleResponse> parseCandles(JsonNode body) {
    JsonNode data = body.path("data");
    if (!data.isArray()) {
      return List.of();
    }
    return java.util.stream.StreamSupport.stream(data.spliterator(), false)
        .filter(JsonNode::isArray)
        .filter(row -> row.size() >= 6)
        .map(row -> new CandleResponse(
            row.get(0).asLong(),
            decimalAt(row, 1),
            decimalAt(row, 2),
            decimalAt(row, 3),
            decimalAt(row, 4),
            decimalAt(row, 5)))
        .sorted(Comparator.comparingLong(CandleResponse::timestamp))
        .toList();
  }

  private Optional<MarketDepthResponse> parseDepth(String requestedSymbol, JsonNode data) {
    Optional<Long> observedAt = positiveEpochMillis(data, "ts");
    if (observedAt.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new MarketDepthResponse(
        normalizePlatformSymbol(requestedSymbol),
        observedAt.get(),
        parseDepthLevels(data.path("bids")),
        parseDepthLevels(data.path("asks"))));
  }

  private List<MarketDepthLevelResponse> parseDepthLevels(JsonNode rows) {
    if (!rows.isArray()) {
      return List.of();
    }
    return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
        .filter(JsonNode::isArray)
        .filter(row -> row.size() >= 2)
        .map(row -> new MarketDepthLevelResponse(decimalAt(row, 0), decimalAt(row, 1)))
        .toList();
  }

  private Optional<Long> positiveEpochMillis(JsonNode node, String field) {
    if (!node.hasNonNull(field)) {
      return Optional.empty();
    }
    try {
      long value = Long.parseLong(node.path(field).asText());
      return value > 0L ? Optional.of(value) : Optional.empty();
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private List<RecentTradeResponse> parseTrades(String requestedSymbol, JsonNode body) {
    JsonNode data = body.path("data");
    if (!data.isArray()) {
      return List.of();
    }
    String normalizedSymbol = normalizePlatformSymbol(requestedSymbol);
    return java.util.stream.StreamSupport.stream(data.spliterator(), false)
        .map(row -> new RecentTradeResponse(
            row.path("tradeId").asText(),
            normalizedSymbol,
            decimalText(row, "px"),
            decimalText(row, "sz"),
            row.path("side").asText("buy").toUpperCase(Locale.ROOT),
            row.path("ts").asLong(clock.instant().toEpochMilli())))
        .toList();
  }

  private SymbolResponse toSymbol(CryptoSymbol symbol) {
    return new SymbolResponse(
        symbol.symbol(),
        symbol.displayName(),
        "CRYPTO",
        ProductType.CRYPTO_SPOT,
        symbol.baseCurrency(),
        symbol.quoteCurrency(),
        DEFAULT_MIN_LOT,
        DEFAULT_MAX_LOT,
        DEFAULT_LEVERAGE,
        true,
        "okx",
        symbol.providerSymbol(),
        true);
  }

  private BigDecimal decimalAt(JsonNode row, int index) {
    try {
      return new BigDecimal(row.get(index).asText());
    } catch (RuntimeException ex) {
      return BigDecimal.ZERO;
    }
  }

  private BigDecimal decimalText(JsonNode node, String field) {
    return decimalValue(node, field).orElse(BigDecimal.ZERO);
  }

  private Optional<BigDecimal> decimalValue(JsonNode node, String field) {
    if (!node.hasNonNull(field)) {
      return Optional.empty();
    }
    try {
      return Optional.of(new BigDecimal(node.path(field).asText()));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private String normalizeAssetClass(String assetClass) {
    return assetClass == null ? "" : assetClass.trim().toUpperCase(Locale.ROOT);
  }

  private String normalizePlatformSymbol(String symbol) {
    return symbol == null ? "" : symbol.replace("-", "").trim().toUpperCase(Locale.ROOT);
  }

  private String normalizeInstrument(String symbol) {
    String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT).replace("_", "-");
    if (normalized.contains("-")) {
      return normalized;
    }
    if (normalized.endsWith("USDT") && normalized.length() > 4) {
      return normalized.substring(0, normalized.length() - 4) + "-USDT";
    }
    return normalized;
  }

  private String normalizeInterval(String timeframe) {
    String interval = StrUtil.blankToDefault(timeframe, "1m").trim();
    if (interval.toLowerCase(Locale.ROOT).endsWith("h")) {
      return interval.substring(0, interval.length() - 1) + "H";
    }
    if ("1d".equalsIgnoreCase(interval)) {
      return "1D";
    }
    return interval;
  }

  private record CryptoSymbol(
      String symbol,
      String providerSymbol,
      String displayName,
      String baseCurrency,
      String quoteCurrency
  ) {
  }
}
