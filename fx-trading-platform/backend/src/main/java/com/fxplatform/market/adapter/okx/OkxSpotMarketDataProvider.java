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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OkxSpotMarketDataProvider implements MarketDataProviderAdapter {

  private static final BigDecimal DEFAULT_MIN_LOT = new BigDecimal("0.01");
  private static final BigDecimal DEFAULT_MAX_LOT = new BigDecimal("100");
  private static final int DEFAULT_LEVERAGE = 20;
  private static final List<CryptoSymbol> CRYPTO_SYMBOLS = List.of(
      new CryptoSymbol("BTCUSDT", "BTC-USDT", "Bitcoin / Tether", "BTC", "USDT"),
      new CryptoSymbol("ETHUSDT", "ETH-USDT", "Ethereum / Tether", "ETH", "USDT"),
      new CryptoSymbol("SOLUSDT", "SOL-USDT", "Solana / Tether", "SOL", "USDT"),
      new CryptoSymbol("XRPUSDT", "XRP-USDT", "XRP / Tether", "XRP", "USDT"));

  private final String restBaseUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public OkxSpotMarketDataProvider(
      @Value("${okx.rest-base-url:https://www.okx.com}") String restBaseUrl
  ) {
    this.restBaseUrl = restBaseUrl;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    this.objectMapper = new ObjectMapper();
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
    if (!configured()) {
      return Optional.empty();
    }
    String instId = normalizeInstrument(StrUtil.blankToDefault(providerSymbol, symbol));
    if (!instId.endsWith("-USDT")) {
      return Optional.empty();
    }
    return sendJson(tickerUri(instId)).flatMap(body -> firstData(body).flatMap(data -> parseTicker(symbol, data)));
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
    return CRYPTO_SYMBOLS.stream()
        .limit(limit)
        .map(this::toSymbol)
        .toList();
  }

  @Override
  public List<CandleResponse> fetchCandles(String symbol, String providerSymbol, String timeframe, Instant from, Instant to) {
    if (!configured() || from == null || to == null || !from.isBefore(to)) {
      return List.of();
    }
    String instId = normalizeInstrument(StrUtil.blankToDefault(providerSymbol, symbol));
    return sendJson(candlesUri(instId, timeframe, from, to))
        .map(this::parseCandles)
        .orElse(List.of());
  }

  @Override
  public Optional<MarketDepthResponse> fetchOrderBook(String symbol, String providerSymbol) {
    if (!configured()) {
      return Optional.empty();
    }
    String instId = normalizeInstrument(StrUtil.blankToDefault(providerSymbol, symbol));
    return sendJson(booksUri(instId)).flatMap(body -> firstData(body).map(data -> parseDepth(symbol, data)));
  }

  @Override
  public List<RecentTradeResponse> fetchRecentTrades(String symbol, String providerSymbol, int limit) {
    if (!configured()) {
      return List.of();
    }
    String instId = normalizeInstrument(StrUtil.blankToDefault(providerSymbol, symbol));
    return sendJson(tradesUri(instId, limit))
        .map(body -> parseTrades(symbol, body))
        .orElse(List.of());
  }

  private URI tickerUri(String instId) {
    return URI.create("%s/api/v5/market/ticker?instId=%s".formatted(baseUrl(), instId));
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
        data.path("ts").asLong(Instant.now().toEpochMilli()),
        BigDecimal.ZERO,
        decimalValue(data, "high24h").orElse(mid),
        decimalValue(data, "low24h").orElse(mid),
        decimalValue(data, "vol24h").orElse(null)));
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

  private MarketDepthResponse parseDepth(String requestedSymbol, JsonNode data) {
    return new MarketDepthResponse(
        normalizePlatformSymbol(requestedSymbol),
        data.path("ts").asLong(Instant.now().toEpochMilli()),
        parseDepthLevels(data.path("bids")),
        parseDepthLevels(data.path("asks")));
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
            row.path("ts").asLong(Instant.now().toEpochMilli())))
        .toList();
  }

  private SymbolResponse toSymbol(CryptoSymbol symbol) {
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
