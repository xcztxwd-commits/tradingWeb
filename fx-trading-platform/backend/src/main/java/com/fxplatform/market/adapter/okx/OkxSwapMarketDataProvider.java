package com.fxplatform.market.adapter.okx;

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
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OkxSwapMarketDataProvider implements MarketDataProviderAdapter {

  private final String restBaseUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Duration freshness;
  private final Duration requestTimeout;

  @Autowired
  public OkxSwapMarketDataProvider(
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

  public OkxSwapMarketDataProvider(
      String restBaseUrl,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Clock clock,
      Duration freshness
  ) {
    this(restBaseUrl, httpClient, objectMapper, clock, freshness, Duration.ofSeconds(2));
  }

  public OkxSwapMarketDataProvider(
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
    return "okx-swap";
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
    String instrument = venueSymbol(symbol, providerSymbol);
    return sendJson(uri("/api/v5/market/ticker?instId=" + encode(instrument)), deadline)
        .flatMap(this::firstData)
        .flatMap(data -> parseTicker(symbol, data));
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
    String instrument = venueSymbol(symbol, providerSymbol);
    String query = "/api/v5/market/candles?instId=%s&bar=%s&after=%d&before=%d&limit=100".formatted(
        encode(instrument), encode(normalizeInterval(timeframe)), to.toEpochMilli(), from.toEpochMilli());
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
    String instrument = venueSymbol(symbol, providerSymbol);
    return sendJson(uri("/api/v5/market/books?instId=" + encode(instrument) + "&sz=20"), deadline)
        .flatMap(this::firstData)
        .flatMap(data -> parseDepth(symbol, data));
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
    String instrument = venueSymbol(symbol, providerSymbol);
    int safeLimit = Math.min(Math.max(limit, 1), 100);
    return sendJson(
        uri("/api/v5/market/trades?instId=" + encode(instrument) + "&limit=" + safeLimit), deadline)
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
    String instrument = venueSymbol(platformSymbol, providerSymbol);
    MarketRequestDeadline deadline = MarketRequestDeadline.start(freshness);
    Optional<QuoteResponse> quote = fetchLatestQuote(platformSymbol, instrument, deadline);
    Instant quoteFetchedAt = clock.instant();
    if (quote.isEmpty()) {
      return Optional.empty();
    }
    Optional<Reference> reference = fetchReference(instrument, deadline);
    Instant referenceFetchedAt = clock.instant();
    if (reference.isEmpty()) {
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
    Reference prices = reference.get();
    return Optional.of(MarketBundleAssembler.perpetual(
        platformSymbol,
        instrument,
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

  private Optional<Reference> fetchReference(
      String swapInstrument,
      MarketRequestDeadline deadline
  ) {
    Optional<JsonNode> mark = sendJson(uri(
        "/api/v5/public/mark-price?instType=SWAP&instId=" + encode(swapInstrument)), deadline)
        .flatMap(this::firstData);
    if (mark.isEmpty()) {
      return Optional.empty();
    }
    String indexInstrument = swapInstrument.substring(0, swapInstrument.length() - "-SWAP".length());
    Optional<JsonNode> index = sendJson(uri(
        "/api/v5/market/index-tickers?instId=" + encode(indexInstrument)), deadline)
        .flatMap(this::firstData);
    if (index.isEmpty()) {
      return Optional.empty();
    }
    Optional<BigDecimal> markPrice = decimal(mark.get(), "markPx");
    Optional<BigDecimal> indexPrice = decimal(index.get(), "idxPx");
    Optional<Long> markTimestamp = positiveEpochMillis(mark.get(), "ts");
    Optional<Long> indexTimestamp = positiveEpochMillis(index.get(), "ts");
    if (markPrice.isEmpty() || indexPrice.isEmpty()
        || markTimestamp.isEmpty() || indexTimestamp.isEmpty()) {
      return Optional.empty();
    }
    Instant markAsOf = Instant.ofEpochMilli(markTimestamp.get());
    Instant indexAsOf = Instant.ofEpochMilli(indexTimestamp.get());
    return Optional.of(new Reference(
        markPrice.get(), indexPrice.get(), markAsOf.isBefore(indexAsOf) ? markAsOf : indexAsOf));
  }

  private Optional<QuoteResponse> parseTicker(String platformSymbol, JsonNode data) {
    Optional<BigDecimal> bid = decimal(data, "bidPx");
    Optional<BigDecimal> ask = decimal(data, "askPx");
    Optional<BigDecimal> last = decimal(data, "last");
    Optional<Long> observedAt = positiveEpochMillis(data, "ts");
    if (bid.isEmpty() || ask.isEmpty() || last.isEmpty() || observedAt.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new QuoteResponse(
        "quote", platformSymbol, bid.get(), ask.get(), last.get(), null,
        ask.get().subtract(bid.get()), code(), observedAt.get(),
        changePercent(data, last.get()),
        decimal(data, "high24h").orElse(last.get()),
        decimal(data, "low24h").orElse(last.get()),
        decimal(data, "vol24h").orElse(null)));
  }

  private BigDecimal changePercent(JsonNode data, BigDecimal last) {
    Optional<BigDecimal> open = decimal(data, "open24h");
    if (open.isEmpty() || open.get().signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return last.subtract(open.get())
        .divide(open.get(), 10, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100));
  }

  private Optional<MarketDepthResponse> parseDepth(String platformSymbol, JsonNode data) {
    Optional<Long> observedAt = positiveEpochMillis(data, "ts");
    if (observedAt.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new MarketDepthResponse(
        platformSymbol,
        observedAt.get(),
        parseLevels(data.path("bids")),
        parseLevels(data.path("asks"))));
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
    JsonNode data = body.path("data");
    if (!data.isArray()) {
      return List.of();
    }
    return java.util.stream.StreamSupport.stream(data.spliterator(), false)
        .map(row -> new RecentTradeResponse(
            row.path("tradeId").asText(),
            platformSymbol,
            decimalAt(row, "px"),
            decimalAt(row, "sz"),
            row.path("side").asText("buy").toUpperCase(Locale.ROOT),
            row.path("ts").asLong(clock.instant().toEpochMilli())))
        .toList();
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
            row.get(0).asLong(), decimalAt(row, 1), decimalAt(row, 2),
            decimalAt(row, 3), decimalAt(row, 4), decimalAt(row, 5)))
        .sorted(Comparator.comparingLong(CandleResponse::timestamp))
        .toList();
  }

  private Optional<JsonNode> firstData(JsonNode body) {
    JsonNode data = body.path("data");
    return data.isArray() && !data.isEmpty() ? Optional.of(data.get(0)) : Optional.empty();
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
      JsonNode body = objectMapper.readTree(response.body());
      return "0".equals(body.path("code").asText("0")) ? Optional.of(body) : Optional.empty();
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
    if (normalized.endsWith("-SWAP")) {
      return normalized;
    }
    String compact = normalized.replace("-", "");
    if (compact.endsWith("USDT") && compact.length() > 4) {
      return compact.substring(0, compact.length() - 4) + "-USDT-SWAP";
    }
    return normalized + "-SWAP";
  }

  private String normalizeInterval(String timeframe) {
    if (timeframe == null || timeframe.isBlank()) {
      return "1m";
    }
    String interval = timeframe.trim();
    if (interval.toLowerCase(Locale.ROOT).endsWith("h")) {
      return interval.substring(0, interval.length() - 1) + "H";
    }
    return "1d".equalsIgnoreCase(interval) ? "1D" : interval;
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
