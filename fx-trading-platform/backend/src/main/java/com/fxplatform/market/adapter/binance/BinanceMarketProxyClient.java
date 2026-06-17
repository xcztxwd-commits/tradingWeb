package com.fxplatform.market.adapter.binance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BinanceMarketProxyClient {

  private static final int DASHBOARD_POINT_LIMIT = 14;
  private static final Set<String> SUPPORTED_FUTURES_PERIODS = Set.of("5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d");

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String webBaseUrl;
  private final String futuresBaseUrl;
  private final String fearGreedUrl;

  public BinanceMarketProxyClient(
      ObjectMapper objectMapper,
      @Value("${binance.web-base-url:https://www.binance.com}") String webBaseUrl,
      @Value("${binance.futures-base-url:https://fapi.binance.com}") String futuresBaseUrl,
      @Value("${market.fear-greed-url:https://api.alternative.me/fng/?limit=1&format=json}") String fearGreedUrl
  ) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    this.webBaseUrl = trimTrailingSlash(webBaseUrl);
    this.futuresBaseUrl = trimTrailingSlash(futuresBaseUrl);
    this.fearGreedUrl = fearGreedUrl;
  }

  public BinanceMarketOverviewSourceResponse fetchMarketOverviewSource() {
    JsonNode productBody = fetchJson(URI.create("%s/bapi/asset/v2/public/asset-service/product/get-products?includeEtf=true".formatted(webBaseUrl)));
    return new BinanceMarketOverviewSourceResponse(
        arrayItems(productBody.path("data")),
        fetchFearGreed().orElse(null));
  }

  public BinanceFuturesDashboardSourceResponse fetchFuturesDashboardSource(String symbol, String period) {
    String normalizedSymbol = normalizeSymbol(symbol);
    String safePeriod = encode(normalizePeriod(period));
    return new BinanceFuturesDashboardSourceResponse(
        fetchJson(URI.create("%s/fapi/v1/ticker/24hr?symbol=%s".formatted(futuresBaseUrl, encode(normalizedSymbol)))),
        fetchJsonArray("%s/futures/data/openInterestHist?symbol=%s&period=%s&limit=%d".formatted(
            futuresBaseUrl, encode(normalizedSymbol), safePeriod, DASHBOARD_POINT_LIMIT)),
        fetchJsonArray("%s/futures/data/topLongShortAccountRatio?symbol=%s&period=%s&limit=%d".formatted(
            futuresBaseUrl, encode(normalizedSymbol), safePeriod, DASHBOARD_POINT_LIMIT)),
        fetchJsonArray("%s/futures/data/topLongShortPositionRatio?symbol=%s&period=%s&limit=%d".formatted(
            futuresBaseUrl, encode(normalizedSymbol), safePeriod, DASHBOARD_POINT_LIMIT)),
        fetchJsonArray("%s/futures/data/globalLongShortAccountRatio?symbol=%s&period=%s&limit=%d".formatted(
            futuresBaseUrl, encode(normalizedSymbol), safePeriod, DASHBOARD_POINT_LIMIT)),
        fetchJsonArray("%s/futures/data/takerlongshortRatio?symbol=%s&period=%s&limit=%d".formatted(
            futuresBaseUrl, encode(normalizedSymbol), safePeriod, DASHBOARD_POINT_LIMIT)),
        fetchJsonArray("%s/futures/data/basis?pair=%s&contractType=PERPETUAL&period=%s&limit=%d".formatted(
            futuresBaseUrl, encode(normalizedSymbol), safePeriod, DASHBOARD_POINT_LIMIT)),
        fetchJsonArray("%s/fapi/v1/fundingRate?symbol=%s&limit=%d".formatted(
            futuresBaseUrl, encode(normalizedSymbol), DASHBOARD_POINT_LIMIT)));
  }

  private Optional<BinanceFearGreedResponse> fetchFearGreed() {
    try {
      JsonNode latest = fetchJson(URI.create(fearGreedUrl)).path("data").path(0);
      int value = parsePositiveInt(latest.path("value").asText(""));
      if (value <= 0) {
        return Optional.empty();
      }
      long timestampSeconds = parseLong(latest.path("timestamp").asText(""));
      String label = latest.path("value_classification").asText("");
      return Optional.of(new BinanceFearGreedResponse(
          value,
          label.isBlank() ? classifyFearGreed(value) : label,
          timestampSeconds > 0 ? timestampSeconds * 1000 : null,
          "alternative.me"));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private JsonNode fetchJson(URI uri) {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json")
        .GET()
        .build();
    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Binance proxy request failed: " + response.statusCode());
      }
      return objectMapper.readTree(response.body());
    } catch (IOException ex) {
      throw new IllegalStateException("Binance proxy request failed", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Binance proxy request interrupted", ex);
    }
  }

  private List<JsonNode> fetchJsonArray(String uri) {
    try {
      return arrayItems(fetchJson(URI.create(uri)));
    } catch (RuntimeException ex) {
      return List.of();
    }
  }

  private List<JsonNode> arrayItems(JsonNode node) {
    if (!node.isArray()) {
      return List.of();
    }
    return StreamSupport.stream(node.spliterator(), false).toList();
  }

  private String normalizeSymbol(String symbol) {
    return symbol == null ? "BTCUSDT" : symbol.replaceAll("[-_/]", "").trim().toUpperCase(Locale.ROOT);
  }

  private String normalizePeriod(String period) {
    String normalized = period == null ? "" : period.trim();
    return SUPPORTED_FUTURES_PERIODS.contains(normalized) ? normalized : "5m";
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private int parsePositiveInt(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private long parseLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private String classifyFearGreed(int value) {
    if (value <= 24) return "Extreme Fear";
    if (value <= 49) return "Fear";
    if (value <= 50) return "Neutral";
    if (value <= 74) return "Greed";
    return "Extreme Greed";
  }

  private String trimTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
