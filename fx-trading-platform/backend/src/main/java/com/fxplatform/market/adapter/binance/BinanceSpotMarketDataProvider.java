package com.fxplatform.market.adapter.binance;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.MarketDataProviderAdapter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
  private static final List<CryptoSymbol> CRYPTO_SYMBOLS = List.of(
      new CryptoSymbol("BTCUSDT", "Bitcoin / Tether", "BTC", "USDT"),
      new CryptoSymbol("ETHUSDT", "Ethereum / Tether", "ETH", "USDT"),
      new CryptoSymbol("SOLUSDT", "Solana / Tether", "SOL", "USDT"),
      new CryptoSymbol("XRPUSDT", "XRP / Tether", "XRP", "USDT"));

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
    return CRYPTO_SYMBOLS.stream()
        .limit(limit)
        .map(symbol -> toSymbol(symbol, iconUrls.get(symbol.baseCurrency())))
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

  private SymbolResponse toSymbol(CryptoSymbol symbol, String iconUrl) {
    return new SymbolResponse(
        symbol.symbol(),
        symbol.displayName(),
        "CRYPTO",
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
        null);
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
