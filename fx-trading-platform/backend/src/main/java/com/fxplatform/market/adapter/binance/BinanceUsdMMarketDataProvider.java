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
import com.fxplatform.market.provider.MarketRequestDeadline;
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
    return fetchLatestQuote(symbol, providerSymbol, null);
  }

  private Optional<QuoteResponse> fetchLatestQuote(
      String symbol,
      String providerSymbol,
      MarketRequestDeadline deadline
  ) {
    String ticker = venueSymbol(symbol, providerSymbol);
    Optional<JsonNode> statistics = sendJson(
        uri("/fapi/v1/ticker/24hr?symbol=" + encode(ticker)), deadline);
    if (statistics.isEmpty()) {
      return Optional.empty();
    }
    Optional<JsonNode> book = sendJson(
        uri("/fapi/v1/ticker/bookTicker?symbol=" + encode(ticker)), deadline);
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
    if (from == null || to == null || !from.isBefore(to)) {
      return List.of();
    }
    String ticker = venueSymbol(symbol, providerSymbol);
    String query = "/fapi/v1/klines?symbol=%s&interval=%s&startTime=%d&endTime=%d".formatted(
        encode(ticker), encode(normalizeInterval(timeframe)), from.toEpochMilli(), to.toEpochMilli());
    return sendJson(uri(query), deadline).map(this::parseCandles).orElse(List.of());
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
    String ticker = venueSymbol(symbol, providerSymbol);
    return sendJson(uri("/fapi/v1/depth?symbol=" + encode(ticker) + "&limit=20"), deadline)
        .flatMap(body -> parseDepth(symbol, body));
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
    String ticker = venueSymbol(symbol, providerSymbol);
    int safeLimit = Math.min(Math.max(limit, 1), 1000);
    return sendJson(uri("/fapi/v1/trades?symbol=" + encode(ticker) + "&limit=" + safeLimit), deadline)
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
    MarketRequestDeadline deadline = MarketRequestDeadline.start(freshness);
    Optional<QuoteResponse> quote = fetchLatestQuote(platformSymbol, ticker, deadline);
    Instant quoteFetchedAt = clock.instant();
    if (quote.isEmpty()) {
      return Optional.empty();
    }
    Optional<Reference> reference = fetchReference(ticker, deadline);
    Instant referenceFetchedAt = clock.instant();
    if (reference.isEmpty()) {
      return Optional.empty();
    }
    Optional<MarketDepthResponse> depth = fetchOrderBook(platformSymbol, ticker, deadline);
    Instant depthFetchedAt = clock.instant();
    if (depth.isEmpty()) {
      return Optional.empty();
    }
    List<RecentTradeResponse> trades = fetchRecentTrades(platformSymbol, ticker, 40, deadline);
    Instant tradesFetchedAt = clock.instant();
    if (trades.isEmpty()) {
      return Optional.empty();
    }
    List<CandleResponse> candles = fetchCandles(
        platformSymbol, ticker, candleRequest.timeframe(), candleRequest.from(), candleRequest.to(), deadline);
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

  private Optional<Reference> fetchReference(String ticker, MarketRequestDeadline deadline) {
    return sendJson(uri("/fapi/v1/premiumIndex?symbol=" + encode(ticker)), deadline).flatMap(body -> {
      Optional<BigDecimal> mark = decimal(body, "markPrice");
      Optional<BigDecimal> index = decimal(body, "indexPrice");
      Optional<Long> timestamp = positiveEpochMillis(body, "time");
      if (mark.isEmpty() || index.isEmpty() || timestamp.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new Reference(mark.get(), index.get(), Instant.ofEpochMilli(timestamp.get())));
    });
  }

  private Optional<QuoteResponse> parseTicker(String platformSymbol, JsonNode statistics, JsonNode book) {
    Optional<BigDecimal> bid = decimal(book, "bidPrice");
    Optional<BigDecimal> ask = decimal(book, "askPrice");
    Optional<BigDecimal> last = decimal(statistics, "lastPrice");
    Optional<Long> statisticsTimestamp = positiveEpochMillis(statistics, "closeTime");
    Optional<Long> bookTimestamp = positiveEpochMillis(book, "time");
    if (bid.isEmpty() || ask.isEmpty() || last.isEmpty()
        || statisticsTimestamp.isEmpty() || bookTimestamp.isEmpty()) {
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
        Math.min(statisticsTimestamp.get(), bookTimestamp.get()),
        decimal(statistics, "priceChangePercent").orElse(null),
        decimal(statistics, "highPrice").orElse(last.get()),
        decimal(statistics, "lowPrice").orElse(last.get()),
        decimal(statistics, "volume").orElse(null)));
  }

  private Optional<MarketDepthResponse> parseDepth(String platformSymbol, JsonNode body) {
    Optional<Long> observedAt = positiveEpochMillis(body, "E");
    if (observedAt.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new MarketDepthResponse(
        platformSymbol,
        observedAt.get(),
        parseLevels(body.path("bids")),
        parseLevels(body.path("asks"))));
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
    return sendJson(uri, null);
  }

  private Optional<JsonNode> sendJson(URI uri, MarketRequestDeadline deadline) {
    if (!configured()) {
      return Optional.empty();
    }
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
