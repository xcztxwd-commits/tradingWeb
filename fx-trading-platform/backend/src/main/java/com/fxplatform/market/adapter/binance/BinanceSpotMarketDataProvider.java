package com.fxplatform.market.adapter.binance;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.MarketDataProviderAdapter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BinanceSpotMarketDataProvider implements MarketDataProviderAdapter {

  private static final BigDecimal DEFAULT_MIN_LOT = new BigDecimal("0.01");
  private static final BigDecimal DEFAULT_MAX_LOT = new BigDecimal("100");
  private static final int DEFAULT_LEVERAGE = 20;
  private static final List<CryptoSymbol> FALLBACK_CRYPTO_SYMBOLS = List.of(
      new CryptoSymbol("BTCUSDT", "Bitcoin / Tether", "BTC", "USDT"),
      new CryptoSymbol("ETHUSDT", "Ethereum / Tether", "ETH", "USDT"),
      new CryptoSymbol("SOLUSDT", "Solana / Tether", "SOL", "USDT"),
      new CryptoSymbol("XRPUSDT", "XRP / Tether", "XRP", "USDT"),
      new CryptoSymbol("BCHUSDT", "Bitcoin Cash / Tether", "BCH", "USDT"),
      new CryptoSymbol("UNIUSDT", "Uniswap / Tether", "UNI", "USDT"),
      new CryptoSymbol("JTOUSDT", "Jito / Tether", "JTO", "USDT"));

  private final String restBaseUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public BinanceSpotMarketDataProvider(
      @Value("${binance.rest-base-url:https://api.binance.com}") String restBaseUrl
  ) {
    this.restBaseUrl = restBaseUrl;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public String code() {
    return "binance";
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
    return isConfigured();
  }

  @Override
  public boolean isConfigured() {
    return StrUtil.isNotBlank(restBaseUrl);
  }

  @Override
  public Optional<QuoteResponse> fetchLatestQuote(String symbol, String providerSymbol) {
    if (!isConfigured()) {
      return Optional.empty();
    }
    String ticker = normalizeTicker(StrUtil.blankToDefault(providerSymbol, symbol));
    if (!ticker.endsWith("USDT")) {
      return Optional.empty();
    }
    return sendJson(ticker24hUri(ticker)).flatMap(body -> parseTicker(symbol, body));
  }

  @Override
  public Optional<QuoteResponse> fetchIndicativeQuote(String symbol, String providerSymbol, Instant to) {
    return fetchLatestQuote(symbol, providerSymbol);
  }

  @Override
  public Map<String, QuoteResponse> fetchLatestQuotes(Map<String, String> providerSymbolsBySymbol) {
    if (!isConfigured() || providerSymbolsBySymbol == null || providerSymbolsBySymbol.isEmpty()) {
      return Map.of();
    }
    Map<String, String> platformSymbolByTicker = new LinkedHashMap<>();
    providerSymbolsBySymbol.forEach((symbol, providerSymbol) -> {
      String ticker = normalizeTicker(StrUtil.blankToDefault(providerSymbol, symbol));
      if (ticker.endsWith("USDT")) {
        platformSymbolByTicker.put(ticker, normalizeTicker(symbol));
      }
    });
    if (platformSymbolByTicker.isEmpty()) {
      return Map.of();
    }
    return sendJson(ticker24hBatchUri(platformSymbolByTicker.keySet().stream().toList()))
        .map(body -> parseTickerBatch(body, platformSymbolByTicker))
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
    Map<String, String> iconUrls = fetchAssetIconUrls();
    Optional<JsonNode> exchangeInfo = fetchExchangeInfo();
    Map<String, String> tradingRules = exchangeInfo
        .map(this::parseTradingRules)
        .orElse(Map.of());
    List<CryptoSymbol> symbols = exchangeInfo
        .map(this::parseSpotSymbols)
        .filter(items -> !items.isEmpty())
        .orElse(FALLBACK_CRYPTO_SYMBOLS);
    return symbols.stream()
        .limit(limit)
        .map(symbol -> toSymbol(symbol, iconUrls.get(symbol.baseCurrency()), tradingRules.get(symbol.symbol())))
        .toList();
  }

  @Override
  public List<CandleResponse> fetchCandles(String symbol, String providerSymbol, String timeframe, Instant from, Instant to) {
    if (!isConfigured() || from == null || to == null || !from.isBefore(to)) {
      return List.of();
    }
    String ticker = normalizeTicker(StrUtil.blankToDefault(providerSymbol, symbol));
    if (!ticker.endsWith("USDT")) {
      return List.of();
    }
    return sendJson(klinesUri(ticker, timeframe, from, to))
        .map(this::parseCandles)
        .orElse(List.of());
  }

  @Override
  public Optional<MarketDepthResponse> fetchOrderBook(String symbol, String providerSymbol) {
    if (!isConfigured()) {
      return Optional.empty();
    }
    String ticker = normalizeTicker(StrUtil.blankToDefault(providerSymbol, symbol));
    return sendJson(depthUri(ticker)).map(body -> parseDepth(symbol, body));
  }

  @Override
  public List<RecentTradeResponse> fetchRecentTrades(String symbol, String providerSymbol, int limit) {
    if (!isConfigured()) {
      return List.of();
    }
    String ticker = normalizeTicker(StrUtil.blankToDefault(providerSymbol, symbol));
    return sendJson(tradesUri(ticker, limit))
        .map(body -> parseTrades(symbol, body))
        .orElse(List.of());
  }

  private URI ticker24hUri(String symbol) {
    return URI.create("%s/api/v3/ticker/24hr?symbol=%s".formatted(baseUrl(), symbol));
  }

  private URI ticker24hBatchUri(List<String> symbols) {
    String json = symbols.stream()
        .map(symbol -> "\"" + symbol + "\"")
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    return URI.create("%s/api/v3/ticker/24hr?symbols=%s".formatted(
        baseUrl(),
        URLEncoder.encode(json, StandardCharsets.UTF_8)));
  }

  private URI klinesUri(String symbol, String timeframe, Instant from, Instant to) {
    return URI.create("%s/api/v3/klines?symbol=%s&interval=%s&startTime=%d&endTime=%d".formatted(
        baseUrl(),
        symbol,
        normalizeInterval(timeframe),
        from.toEpochMilli(),
        to.toEpochMilli()));
  }

  private URI depthUri(String symbol) {
    return URI.create("%s/api/v3/depth?symbol=%s&limit=20".formatted(baseUrl(), symbol));
  }

  private URI tradesUri(String symbol, int limit) {
    int normalizedLimit = Math.min(Math.max(limit, 1), 1000);
    return URI.create("%s/api/v3/trades?symbol=%s&limit=%d".formatted(baseUrl(), symbol, normalizedLimit));
  }

  private URI assetCatalogUri() {
    return URI.create("%s/bapi/asset/v2/public/asset/asset/get-all-asset".formatted(assetBaseUrl()));
  }

  private URI exchangeInfoUri() {
    return URI.create("%s/api/v3/exchangeInfo".formatted(baseUrl()));
  }

  private String baseUrl() {
    return restBaseUrl.endsWith("/") ? restBaseUrl.substring(0, restBaseUrl.length() - 1) : restBaseUrl;
  }

  private String assetBaseUrl() {
    String baseUrl = baseUrl();
    return baseUrl.contains("api.binance.com") ? "https://www.binance.com" : baseUrl;
  }

  private Optional<JsonNode> sendJson(URI uri) {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json")
        .GET()
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readTree(response.body()));
    } catch (IOException ex) {
      return Optional.empty();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private Optional<QuoteResponse> parseTicker(String requestedSymbol, JsonNode body) {
    if (!body.hasNonNull("bidPrice") || !body.hasNonNull("askPrice") || !body.hasNonNull("lastPrice")) {
      return Optional.empty();
    }
    BigDecimal bid = decimalValue(body, "bidPrice").orElse(BigDecimal.ZERO);
    BigDecimal ask = decimalValue(body, "askPrice").orElse(BigDecimal.ZERO);
    BigDecimal mid = decimalValue(body, "lastPrice").orElse(BigDecimal.ZERO);
    return Optional.of(new QuoteResponse(
        "quote",
        normalizeTicker(requestedSymbol),
        bid,
        ask,
        mid,
        ask.subtract(bid),
        "binance-spot",
        body.path("closeTime").asLong(Instant.now().toEpochMilli()),
        firstDecimal(body, "priceChangePercent").orElse(BigDecimal.ZERO),
        firstDecimal(body, "highPrice").orElse(mid),
        firstDecimal(body, "lowPrice").orElse(mid),
        firstDecimal(body, "volume").orElse(null)));
  }

  private Map<String, QuoteResponse> parseTickerBatch(JsonNode body, Map<String, String> platformSymbolByTicker) {
    if (!body.isArray()) {
      return Map.of();
    }
    Map<String, QuoteResponse> quotes = new LinkedHashMap<>();
    for (JsonNode item : body) {
      String ticker = normalizeTicker(item.path("symbol").asText(""));
      String platformSymbol = platformSymbolByTicker.get(ticker);
      if (platformSymbol == null) {
        continue;
      }
      parseTicker(platformSymbol, item).ifPresent(quote -> quotes.put(quote.symbol(), quote));
    }
    return Map.copyOf(quotes);
  }

  private List<CandleResponse> parseCandles(JsonNode body) {
    if (!body.isArray()) {
      return List.of();
    }
    return java.util.stream.StreamSupport.stream(body.spliterator(), false)
        .filter(JsonNode::isArray)
        .filter(row -> row.size() >= 6)
        .map(row -> new CandleResponse(
            row.get(0).asLong(),
            decimalAt(row, 1),
            decimalAt(row, 2),
            decimalAt(row, 3),
            decimalAt(row, 4),
            decimalAt(row, 5)))
        .toList();
  }

  private MarketDepthResponse parseDepth(String requestedSymbol, JsonNode body) {
    return new MarketDepthResponse(
        normalizeTicker(requestedSymbol),
        Instant.now().toEpochMilli(),
        parseDepthLevels(body.path("bids")),
        parseDepthLevels(body.path("asks")));
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

  private List<RecentTradeResponse> parseTrades(String requestedSymbol, JsonNode body) {
    if (!body.isArray()) {
      return List.of();
    }
    String normalizedSymbol = normalizeTicker(requestedSymbol);
    return java.util.stream.StreamSupport.stream(body.spliterator(), false)
        .map(row -> new RecentTradeResponse(
            row.path("id").asText(),
            normalizedSymbol,
            decimalText(row, "price"),
            decimalText(row, "qty"),
            row.path("isBuyerMaker").asBoolean(false) ? "SELL" : "BUY",
            row.path("time").asLong(Instant.now().toEpochMilli())))
        .toList();
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

  private SymbolResponse toSymbol(CryptoSymbol symbol, String iconUrl, String providerMetadataJson) {
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
        "binance",
        symbol.symbol(),
        true,
        iconUrl,
        true,
        true,
        true,
        true,
        false,
        null,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        providerMetadataJson == null || providerMetadataJson.isBlank() ? "{}" : providerMetadataJson);
  }

  private Optional<JsonNode> fetchExchangeInfo() {
    if (!isConfigured()) {
      return Optional.empty();
    }
    return sendJson(exchangeInfoUri());
  }

  private List<CryptoSymbol> parseSpotSymbols(JsonNode body) {
    JsonNode symbols = body.path("symbols");
    if (!symbols.isArray()) {
      return List.of();
    }
    return java.util.stream.StreamSupport.stream(symbols.spliterator(), false)
        .filter(symbol -> "TRADING".equalsIgnoreCase(symbol.path("status").asText("")))
        .filter(symbol -> "USDT".equals(normalizeTicker(symbol.path("quoteAsset").asText(""))))
        .filter(this::spotTradingAllowed)
        .map(this::toCryptoSymbol)
        .flatMap(Optional::stream)
        .toList();
  }

  private boolean spotTradingAllowed(JsonNode symbol) {
    if (symbol.has("isSpotTradingAllowed")) {
      return symbol.path("isSpotTradingAllowed").asBoolean(false);
    }
    JsonNode permissions = symbol.path("permissions");
    if (permissions.isArray()
        && java.util.stream.StreamSupport.stream(permissions.spliterator(), false)
            .anyMatch(permission -> "SPOT".equalsIgnoreCase(permission.asText("")))) {
      return true;
    }
    JsonNode permissionSets = symbol.path("permissionSets");
    if (!permissionSets.isArray()) {
      return false;
    }
    return java.util.stream.StreamSupport.stream(permissionSets.spliterator(), false)
        .filter(JsonNode::isArray)
        .anyMatch(set -> java.util.stream.StreamSupport.stream(set.spliterator(), false)
            .anyMatch(permission -> "SPOT".equalsIgnoreCase(permission.asText(""))));
  }

  private Optional<CryptoSymbol> toCryptoSymbol(JsonNode symbol) {
    String symbolCode = normalizeTicker(symbol.path("symbol").asText(""));
    String baseAsset = normalizeTicker(symbol.path("baseAsset").asText(""));
    String quoteAsset = normalizeTicker(symbol.path("quoteAsset").asText(""));
    if (symbolCode.isBlank() || baseAsset.isBlank() || quoteAsset.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new CryptoSymbol(
        symbolCode,
        "%s / %s".formatted(baseAsset, quoteAsset),
        baseAsset,
        quoteAsset));
  }

  private Map<String, String> parseTradingRules(JsonNode body) {
    JsonNode symbols = body.path("symbols");
    if (!symbols.isArray()) {
      return Map.of();
    }
    Map<String, String> rules = new LinkedHashMap<>();
    symbols.forEach(symbol -> {
      String symbolCode = normalizeTicker(symbol.path("symbol").asText(""));
      if (symbolCode.isBlank()) {
        return;
      }
      toTradingRuleMetadata(symbol)
          .ifPresent(metadata -> rules.put(symbolCode, metadata));
    });
    return Map.copyOf(rules);
  }

  private Optional<String> toTradingRuleMetadata(JsonNode symbol) {
    ObjectNode metadata = objectMapper.createObjectNode();
    metadata.put("provider", "binance");
    metadata.put("marketType", "SPOT");
    putIfNotBlank(metadata, "providerSymbol", symbol.path("symbol").asText(""));
    putIfNotBlank(metadata, "status", symbol.path("status").asText(""));
    putIfNotBlank(metadata, "baseAsset", symbol.path("baseAsset").asText(""));
    putIfNotBlank(metadata, "quoteAsset", symbol.path("quoteAsset").asText(""));

    ObjectNode rules = metadata.putObject("rules");
    filter(symbol, "PRICE_FILTER").ifPresent(priceFilter ->
        putIfNotBlank(rules, "tickSize", priceFilter.path("tickSize").asText("")));
    filter(symbol, "LOT_SIZE").ifPresent(lotFilter -> {
      putIfNotBlank(rules, "stepSize", lotFilter.path("stepSize").asText(""));
      putIfNotBlank(rules, "minLot", lotFilter.path("minQty").asText(""));
      putIfNotBlank(rules, "maxLot", lotFilter.path("maxQty").asText(""));
    });
    filter(symbol, "MIN_NOTIONAL")
        .or(() -> filter(symbol, "NOTIONAL"))
        .ifPresent(notionalFilter -> {
          putIfNotBlank(rules, "minNotional", notionalFilter.path("minNotional").asText(""));
          putIfNotBlank(rules, "maxNotional", notionalFilter.path("maxNotional").asText(""));
        });

    ObjectNode margin = metadata.putObject("margin");
    margin.put("spotTradingAllowed", symbol.path("isSpotTradingAllowed").asBoolean(false));
    margin.put("marginTradingAllowed", symbol.path("isMarginTradingAllowed").asBoolean(false));
    putIfNotBlank(margin, "marginAsset", symbol.path("marginAsset").asText(""));
    putIfNotBlank(margin, "requiredMarginPercent", symbol.path("requiredMarginPercent").asText(""));
    putIfNotBlank(margin, "maintMarginPercent", symbol.path("maintMarginPercent").asText(""));
    margin.put("leverageSource", "Binance spot exchangeInfo exposes margin eligibility, not leverage brackets");

    JsonNode permissions = symbol.path("permissions");
    if (permissions.isArray()) {
      metadata.set("permissions", permissions);
    }
    metadata.set("filters", symbol.path("filters"));

    try {
      return Optional.of(objectMapper.writeValueAsString(metadata));
    } catch (IOException ex) {
      return Optional.empty();
    }
  }

  private Optional<JsonNode> filter(JsonNode symbol, String filterType) {
    JsonNode filters = symbol.path("filters");
    if (!filters.isArray()) {
      return Optional.empty();
    }
    return java.util.stream.StreamSupport.stream(filters.spliterator(), false)
        .filter(filter -> filterType.equals(filter.path("filterType").asText("")))
        .findFirst();
  }

  private void putIfNotBlank(ObjectNode node, String field, String value) {
    if (value != null && !value.isBlank()) {
      node.put(field, value);
    }
  }

  private Map<String, String> fetchAssetIconUrls() {
    if (!isConfigured()) {
      return Map.of();
    }
    return sendJson(assetCatalogUri())
        .map(this::parseAssetIconUrls)
        .orElse(Map.of());
  }

  private Map<String, String> parseAssetIconUrls(JsonNode body) {
    JsonNode data = body.path("data");
    if (!data.isArray()) {
      return Map.of();
    }
    Map<String, String> iconUrls = new LinkedHashMap<>();
    data.forEach(asset -> {
      String assetCode = normalizeTicker(asset.path("assetCode").asText(""));
      String iconUrl = firstText(asset, "fullLogoUrl", "logoUrl").orElse("");
      if (!assetCode.isBlank() && !iconUrl.isBlank()) {
        iconUrls.put(assetCode, iconUrl);
      }
    });
    return Map.copyOf(iconUrls);
  }

  private Optional<BigDecimal> firstDecimal(JsonNode node, String field) {
    return decimalValue(node, field);
  }

  private Optional<String> firstText(JsonNode node, String... fields) {
    for (String field : fields) {
      String text = node.path(field).asText("");
      if (!text.isBlank()) {
        return Optional.of(text);
      }
    }
    return Optional.empty();
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

  private String normalizeTicker(String ticker) {
    return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
  }

  private String normalizeInterval(String timeframe) {
    return StrUtil.blankToDefault(timeframe, "1m").trim();
  }

  private record CryptoSymbol(String symbol, String displayName, String baseCurrency, String quoteCurrency) {
  }
}
