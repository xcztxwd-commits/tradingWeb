package com.fxplatform.market.adapter.binance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.provider.MarketBundleAssembler;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.MarketDataDurations;
import com.fxplatform.market.provider.MarketDataProviderAdapter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BinanceUsdMMarketDataProvider implements MarketDataProviderAdapter {

  private final String restBaseUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Duration freshness;
  private final Duration requestTimeout;

  @Autowired
  public BinanceUsdMMarketDataProvider(
      @Value("${binance.futures-base-url:https://fapi.binance.com}") String restBaseUrl,
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

  public BinanceUsdMMarketDataProvider(
      String restBaseUrl,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Clock clock,
      Duration freshness
  ) {
    this(restBaseUrl, httpClient, objectMapper, clock, freshness, Duration.ofSeconds(2));
  }

  public BinanceUsdMMarketDataProvider(
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
    return "binance-usdm";
  }

  @Override
  public Set<MarketDataCapability> capabilities() {
    return Set.of(MarketDataCapability.QUOTE, MarketDataCapability.CANDLES,
        MarketDataCapability.ORDER_BOOK, MarketDataCapability.TRADES);
  }

  @Override
  public boolean configured() {
    return restBaseUrl != null && !restBaseUrl.isBlank();
  }

  @Override
  public Optional<QuoteResponse> fetchLatestQuote(String symbol, String providerSymbol) {
    String ticker = venueSymbol(symbol, providerSymbol);
    Optional<JsonNode> statistics = sendJson(uri("/fapi/v1/ticker/24hr?symbol=" + encode(ticker)));
    if (statistics.isEmpty()) {
      return Optional.empty();
    }
    Optional<JsonNode> book = sendJson(uri("/fapi/v1/ticker/bookTicker?symbol=" + encode(ticker)));
    if (book.isEmpty()) {
      return Optional.empty();
    }
    return parseTicker(symbol, statistics.get(), book.get());
  }

  @Override
  public List<CandleResponse> fetchCandles(
      String symbol,
      String providerSymbol,
      String timeframe,
      Instant from,
      Instant to
  ) {
    if (from == null || to == null || !from.isBefore(to)) {
      return List.of();
    }
    String ticker = venueSymbol(symbol, providerSymbol);
    String query = "/fapi/v1/klines?symbol=%s&interval=%s&startTime=%d&endTime=%d".formatted(
        encode(ticker), encode(normalizeInterval(timeframe)), from.toEpochMilli(), to.toEpochMilli());
    return sendJson(uri(query)).map(this::parseCandles).orElse(List.of());
  }

  @Override
  public Optional<MarketDepthResponse> fetchOrderBook(String symbol, String providerSymbol) {
    String ticker = venueSymbol(symbol, providerSymbol);
    return sendJson(uri("/fapi/v1/depth?symbol=" + encode(ticker) + "&limit=20"))
        .map(body -> parseDepth(symbol, body));
  }

  @Override
  public List<RecentTradeResponse> fetchRecentTrades(String symbol, String providerSymbol, int limit) {
    String ticker = venueSymbol(symbol, providerSymbol);
    int safeLimit = Math.min(Math.max(limit, 1), 1000);
    return sendJson(uri("/fapi/v1/trades?symbol=" + encode(ticker) + "&limit=" + safeLimit))
        .map(body -> parseTrades(symbol, body))
        .orElse(List.of());
  }

  @Override
  public Optional<PerpetualMarketBundle> fetchPerpetualBundle(
      String platformSymbol,
      String providerSymbol,
      CandleRequest candleRequest
  ) {
    if (!configured() || candleRequest == null) {
      return Optional.empty();
    }
    String ticker = venueSymbol(platformSymbol, providerSymbol);
    Optional<QuoteResponse> quote = fetchLatestQuote(platformSymbol, ticker);
    Instant quoteFetchedAt = clock.instant();
    if (quote.isEmpty()) {
      return Optional.empty();
    }
    Optional<Reference> reference = fetchReference(ticker);
    Instant referenceFetchedAt = clock.instant();
    if (reference.isEmpty()) {
      return Optional.empty();
    }
    Optional<MarketDepthResponse> depth = fetchOrderBook(platformSymbol, ticker);
    Instant depthFetchedAt = clock.instant();
    if (depth.isEmpty()) {
      return Optional.empty();
    }
    List<RecentTradeResponse> trades = fetchRecentTrades(platformSymbol, ticker, 40);
    Instant tradesFetchedAt = clock.instant();
    if (trades.isEmpty()) {
      return Optional.empty();
    }
    List<CandleResponse> candles = fetchCandles(
        platformSymbol, ticker, candleRequest.timeframe(), candleRequest.from(), candleRequest.to());
    Instant candlesFetchedAt = clock.instant();
    if (candles.isEmpty()) {
      return Optional.empty();
    }
    Reference prices = reference.get();
    return Optional.of(MarketBundleAssembler.perpetual(
        platformSymbol,
        ticker,
        code(),
        MarketSourceMode.PUBLIC_EXTERNAL,
        quote.get(),
        prices.mark(),
        prices.index(),
        depth.get(),
        trades,
        candles,
        MarketBundleAssembler.ComponentObservations.perpetual(
            MarketBundleAssembler.snapshotObservedAt(quote.get().timestamp(), quoteFetchedAt),
            MarketBundleAssembler.snapshotObservedAt(depth.get().timestamp(), depthFetchedAt),
            tradesFetchedAt,
            candlesFetchedAt,
            MarketBundleAssembler.snapshotObservedAt(prices.asOf(), referenceFetchedAt)),
        freshness));
  }

  private Optional<Reference> fetchReference(String ticker) {
    return sendJson(uri("/fapi/v1/premiumIndex?symbol=" + encode(ticker))).flatMap(body -> {
      Optional<BigDecimal> mark = decimal(body, "markPrice");
      Optional<BigDecimal> index = decimal(body, "indexPrice");
      if (mark.isEmpty() || index.isEmpty()) {
        return Optional.empty();
      }
      long timestamp = body.path("time").asLong(clock.instant().toEpochMilli());
      return Optional.of(new Reference(mark.get(), index.get(), Instant.ofEpochMilli(timestamp)));
    });
  }

  private Optional<QuoteResponse> parseTicker(String platformSymbol, JsonNode statistics, JsonNode book) {
    Optional<BigDecimal> bid = decimal(book, "bidPrice");
    Optional<BigDecimal> ask = decimal(book, "askPrice");
    Optional<BigDecimal> last = decimal(statistics, "lastPrice");
    if (bid.isEmpty() || ask.isEmpty() || last.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new QuoteResponse(
        "quote",
        platformSymbol,
        bid.get(),
        ask.get(),
        last.get(),
        null,
        ask.get().subtract(bid.get()),
        code(),
        quoteObservedAt(statistics, book),
        decimal(statistics, "priceChangePercent").orElse(null),
        decimal(statistics, "highPrice").orElse(last.get()),
        decimal(statistics, "lowPrice").orElse(last.get()),
        decimal(statistics, "volume").orElse(null)));
  }

  private long quoteObservedAt(JsonNode statistics, JsonNode book) {
    long statisticsTimestamp = statistics.path("closeTime").asLong(0L);
    long bookTimestamp = book.path("time").asLong(book.path("T").asLong(0L));
    if (statisticsTimestamp <= 0L) {
      return bookTimestamp > 0L ? bookTimestamp : clock.instant().toEpochMilli();
    }
    if (bookTimestamp <= 0L) {
      return statisticsTimestamp;
    }
    return Math.min(statisticsTimestamp, bookTimestamp);
  }

  private MarketDepthResponse parseDepth(String platformSymbol, JsonNode body) {
    return new MarketDepthResponse(
        platformSymbol,
        body.path("E").asLong(clock.instant().toEpochMilli()),
        parseLevels(body.path("bids")),
        parseLevels(body.path("asks")));
  }

  private List<MarketDepthLevelResponse> parseLevels(JsonNode rows) {
    if (!rows.isArray()) {
      return List.of();
    }
    return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
        .filter(JsonNode::isArray)
        .filter(row -> row.size() >= 2)
        .map(row -> new MarketDepthLevelResponse(decimalAt(row, 0), decimalAt(row, 1)))
        .toList();
  }

  private List<RecentTradeResponse> parseTrades(String platformSymbol, JsonNode body) {
    if (!body.isArray()) {
      return List.of();
    }
    return java.util.stream.StreamSupport.stream(body.spliterator(), false)
        .map(row -> new RecentTradeResponse(
            row.path("id").asText(),
            platformSymbol,
            decimalAt(row, "price"),
            decimalAt(row, "qty"),
            row.path("isBuyerMaker").asBoolean(false) ? "SELL" : "BUY",
            row.path("time").asLong(clock.instant().toEpochMilli())))
        .toList();
  }

  private List<CandleResponse> parseCandles(JsonNode body) {
    if (!body.isArray()) {
      return List.of();
    }
    return java.util.stream.StreamSupport.stream(body.spliterator(), false)
        .filter(JsonNode::isArray)
        .filter(row -> row.size() >= 6)
        .map(row -> new CandleResponse(
            row.get(0).asLong(), decimalAt(row, 1), decimalAt(row, 2),
            decimalAt(row, 3), decimalAt(row, 4), decimalAt(row, 5)))
        .toList();
  }

  private Optional<JsonNode> sendJson(URI uri) {
    if (!configured()) {
      return Optional.empty();
    }
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(requestTimeout)
        .header("Accept", "application/json")
        .GET()
        .build();
    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readTree(response.body()));
    } catch (IOException ignored) {
      return Optional.empty();
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private static HttpClient newHttpClient(Duration timeout) {
    return HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  private Optional<BigDecimal> decimal(JsonNode node, String field) {
    try {
      return node.hasNonNull(field) ? Optional.of(new BigDecimal(node.path(field).asText())) : Optional.empty();
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private BigDecimal decimalAt(JsonNode row, int index) {
    try {
      return new BigDecimal(row.get(index).asText());
    } catch (RuntimeException ignored) {
      return BigDecimal.ZERO;
    }
  }

  private BigDecimal decimalAt(JsonNode row, String field) {
    return decimal(row, field).orElse(BigDecimal.ZERO);
  }

  private String venueSymbol(String platformSymbol, String providerSymbol) {
    String candidate = providerSymbol == null || providerSymbol.isBlank() ? platformSymbol : providerSymbol;
    String normalized = candidate.trim().toUpperCase(Locale.ROOT).replace("_", "-").replace("/", "-");
    if (normalized.endsWith("-PERP")) {
      normalized = normalized.substring(0, normalized.length() - "-PERP".length());
    }
    return normalized.replace("-", "");
  }

  private String normalizeInterval(String timeframe) {
    return timeframe == null || timeframe.isBlank() ? "1m" : timeframe.trim();
  }

  private URI uri(String pathAndQuery) {
    String base = restBaseUrl.endsWith("/") ? restBaseUrl.substring(0, restBaseUrl.length() - 1) : restBaseUrl;
    return URI.create(base + pathAndQuery);
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private record Reference(BigDecimal mark, BigDecimal index, Instant asOf) {
  }
}
