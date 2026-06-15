package com.fxplatform.market.adapter.massive;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.MarketDataProviderAdapter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MassiveRestClient is the market module external data provider.
 */
@Component
public class MassiveRestClient implements MarketDataProviderAdapter {

  private static final BigDecimal DEFAULT_MIN_LOT = new BigDecimal("0.01");
  private static final BigDecimal DEFAULT_MAX_LOT = new BigDecimal("100");
  private static final int DEFAULT_LEVERAGE = 100;
  private static final int MAX_TICKER_PAGE_SIZE = 1000;
  private static final int AGGREGATE_LIMIT = 50000;
  private static final int GROUPED_DAILY_LOOKBACK_DAYS = 7;
  private static final Duration RECENT_AVAILABLE_LOOKBACK = Duration.ofDays(7);
  private static final Duration AGGREGATE_CACHE_TTL = Duration.ofMinutes(15);

  private final String apiKey;
  private final String restBaseUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final MassiveQuoteNormalizer quoteNormalizer = new MassiveQuoteNormalizer();
  private final Map<AggregateCacheKey, AggregateCacheEntry> aggregateCache = new ConcurrentHashMap<>();

  public MassiveRestClient(
      @Value("${massive.api-key}") String apiKey,
      @Value("${massive.rest-base-url}") String restBaseUrl
  ) {
    this.apiKey = apiKey;
    this.restBaseUrl = restBaseUrl;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public String code() {
    return "massive";
  }

  @Override
  public Set<MarketDataCapability> capabilities() {
    return Set.of(
        MarketDataCapability.SYMBOLS,
        MarketDataCapability.QUOTE,
        MarketDataCapability.SNAPSHOT,
        MarketDataCapability.CANDLES);
  }

  @Override
  public boolean configured() {
    return isConfigured();
  }

  @Override
  public boolean isConfigured() {
    return StrUtil.isAllNotBlank(apiKey, restBaseUrl);
  }

  @Override
  public Optional<QuoteResponse> fetchLatestQuote(String symbol, String providerSymbol) {
    if (!isConfigured()) {
      return Optional.empty();
    }

    Optional<ForexPair> pair = toForexPair(providerSymbol);
    if (pair.isEmpty()) {
      return Optional.empty();
    }

    return sendJson(lastQuoteUri(pair.get()))
        .flatMap(body -> parseQuote(symbol, providerSymbol, body));
  }

  @Override
  public Optional<QuoteResponse> fetchIndicativeQuote(String symbol, String providerSymbol, Instant to) {
    List<CandleResponse> candles = fetchCandles(symbol, providerSymbol, "1m", to.minus(Duration.ofDays(7)), to);
    if (candles.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(toIndicativeQuote(symbol, candles));
  }

  @Override
  public Map<String, QuoteResponse> fetchMarketSnapshots(String assetClass, int limit) {
    if (!isConfigured() || !"FOREX".equals(normalizeAssetClass(assetClass)) || limit <= 0) {
      return Map.of();
    }

    Map<String, QuoteResponse> snapshots = sendJson(forexMarketSnapshotUri())
        .map(body -> parseForexSnapshots(body, Integer.MAX_VALUE))
        .orElse(Map.of());
    if (!snapshots.isEmpty()) {
      return snapshots;
    }

    return fetchGroupedDailyForexSnapshots(Integer.MAX_VALUE, Instant.now());
  }

  @Override
  public List<SymbolResponse> fetchSymbols(String assetClass, int limit) {
    if (!isConfigured() || !"FOREX".equals(normalizeAssetClass(assetClass)) || limit <= 0) {
      return List.of();
    }

    List<SymbolResponse> symbols = new ArrayList<>();
    URI nextUri = forexTickersUri(Math.min(limit, MAX_TICKER_PAGE_SIZE));
    while (nextUri != null && symbols.size() < limit) {
      Optional<JsonNode> body = sendJson(nextUri);
      if (body.isEmpty()) {
        break;
      }

      parseForexSymbols(body.get(), limit - symbols.size()).forEach(symbols::add);
      String nextUrl = body.get().path("next_url").asText("");
      nextUri = StrUtil.isBlank(nextUrl) ? null : URI.create(nextUrl);
    }
    return List.copyOf(symbols);
  }

  @Override
  public List<CandleResponse> fetchCandles(String symbol, String providerSymbol, String timeframe, Instant from, Instant to) {
    if (!isConfigured() || from == null || to == null || !from.isBefore(to)) {
      return List.of();
    }

    Optional<String> ticker = toMassiveForexTicker(providerSymbol);
    Optional<AggregateWindow> window = toAggregateWindow(timeframe);
    if (ticker.isEmpty() || window.isEmpty()) {
      return List.of();
    }

    if (Duration.between(from, to).compareTo(RECENT_AVAILABLE_LOOKBACK) < 0) {
      List<CandleResponse> recentCandles = fetchAggregateCandles(
          ticker.get(),
          window.get(),
          to.minus(RECENT_AVAILABLE_LOOKBACK),
          to);
      if (!recentCandles.isEmpty()) {
        return tail(recentCandles, requestedBarCount(window.get(), from, to));
      }
    }

    return fetchAggregateCandles(ticker.get(), window.get(), from, to);
  }

  private synchronized List<CandleResponse> fetchAggregateCandles(String ticker, AggregateWindow window, Instant from, Instant to) {
    AggregateCacheKey cacheKey = new AggregateCacheKey(
        ticker,
        window.multiplier(),
        window.timespan(),
        aggregateDate(from),
        aggregateDate(to));
    Instant now = Instant.now();
    AggregateCacheEntry cached = aggregateCache.get(cacheKey);
    if (cached != null && cached.expiresAt().isAfter(now)) {
      return cached.candles();
    }

    Optional<JsonNode> body = sendJson(aggregateUri(ticker, window, from, to));
    if (body.isEmpty() && cached != null) {
      return cached.candles();
    }

    List<CandleResponse> candles = body
        .map(this::parseCandles)
        .orElse(List.of());
    if (!candles.isEmpty()) {
      aggregateCache.put(cacheKey, new AggregateCacheEntry(candles, now.plus(AGGREGATE_CACHE_TTL)));
    }
    return candles;
  }

  private URI forexTickersUri(int limit) {
    return URI.create("%s/v3/reference/tickers?market=fx&active=true&limit=%d".formatted(baseUrl(), limit));
  }

  private URI forexMarketSnapshotUri() {
    return URI.create("%s/v2/snapshot/locale/global/markets/forex/tickers".formatted(baseUrl()));
  }

  private URI groupedDailyForexUri(Instant date) {
    return URI.create("%s/v2/aggs/grouped/locale/global/market/fx/%s?adjusted=true".formatted(
        baseUrl(),
        aggregateDate(date)));
  }

  private URI aggregateUri(String ticker, AggregateWindow window, Instant from, Instant to) {
    return URI.create("%s/v2/aggs/ticker/%s/range/%d/%s/%s/%s?adjusted=true&sort=asc&limit=%d".formatted(
        baseUrl(),
        ticker,
        window.multiplier(),
        window.timespan(),
        aggregateDate(from),
        aggregateDate(to),
        AGGREGATE_LIMIT));
  }

  private String aggregateDate(Instant value) {
    return DateTimeFormatter.ISO_LOCAL_DATE.format(value.atZone(ZoneOffset.UTC));
  }

  private URI lastQuoteUri(ForexPair pair) {
    return URI.create("%s/v1/last_quote/currencies/%s/%s".formatted(baseUrl(), pair.from(), pair.to()));
  }

  private String baseUrl() {
    return restBaseUrl.endsWith("/") ? restBaseUrl.substring(0, restBaseUrl.length() - 1) : restBaseUrl;
  }

  private Optional<JsonNode> sendJson(URI uri) {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + apiKey)
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

  private Optional<QuoteResponse> parseQuote(String symbol, String providerSymbol, JsonNode body) {
    JsonNode last = body.path("last");
    if (!last.hasNonNull("bid") || !last.hasNonNull("ask") || !last.hasNonNull("timestamp")) {
      return Optional.empty();
    }

    MassiveQuotePayload payload = new MassiveQuotePayload(
        providerSymbol,
        last.path("bid").decimalValue(),
        last.path("ask").decimalValue(),
        last.path("timestamp").asLong());
    return Optional.of(quoteNormalizer.normalize(symbol, payload));
  }

  private List<SymbolResponse> parseForexSymbols(JsonNode body, int remaining) {
    List<SymbolResponse> symbols = new ArrayList<>();
    JsonNode results = body.path("results");
    if (!results.isArray()) {
      return symbols;
    }

    for (JsonNode item : results) {
      if (symbols.size() >= remaining) {
        break;
      }
      Optional<SymbolResponse> symbol = toForexSymbol(item);
      symbol.ifPresent(symbols::add);
    }
    return symbols;
  }

  private Map<String, QuoteResponse> parseForexSnapshots(JsonNode body, int limit) {
    Map<String, QuoteResponse> snapshots = new LinkedHashMap<>();
    JsonNode tickers = body.path("tickers");
    if (!tickers.isArray()) {
      tickers = body.path("results");
    }
    if (!tickers.isArray()) {
      return snapshots;
    }

    for (JsonNode item : tickers) {
      if (snapshots.size() >= limit) {
        break;
      }
      Optional<QuoteResponse> quote = toForexSnapshotQuote(item);
      quote.ifPresent(value -> snapshots.put(value.symbol(), value));
    }
    return Map.copyOf(snapshots);
  }

  private Map<String, QuoteResponse> fetchGroupedDailyForexSnapshots(int limit, Instant now) {
    for (int dayOffset = 0; dayOffset < GROUPED_DAILY_LOOKBACK_DAYS; dayOffset++) {
      Map<String, QuoteResponse> snapshots = sendJson(groupedDailyForexUri(now.minus(Duration.ofDays(dayOffset))))
          .map(body -> parseGroupedDailyForexSnapshots(body, limit))
          .orElse(Map.of());
      if (!snapshots.isEmpty()) {
        return snapshots;
      }
    }
    return Map.of();
  }

  private Map<String, QuoteResponse> parseGroupedDailyForexSnapshots(JsonNode body, int limit) {
    Map<String, QuoteResponse> snapshots = new LinkedHashMap<>();
    JsonNode results = body.path("results");
    if (!results.isArray()) {
      return snapshots;
    }

    for (JsonNode item : results) {
      if (snapshots.size() >= limit) {
        break;
      }
      Optional<QuoteResponse> quote = toGroupedDailyForexQuote(item);
      quote.ifPresent(value -> snapshots.put(value.symbol(), value));
    }
    return Map.copyOf(snapshots);
  }

  private Optional<QuoteResponse> toGroupedDailyForexQuote(JsonNode item) {
    Optional<String> symbol = toNormalizedForexSymbol(item.path("T").asText(""));
    Optional<BigDecimal> close = firstDecimal(item, "c", "close");
    if (symbol.isEmpty() || close.isEmpty()) {
      return Optional.empty();
    }

    BigDecimal halfSpread = halfSpread(symbol.get());
    BigDecimal bid = close.get().subtract(halfSpread).setScale(10, RoundingMode.HALF_UP);
    BigDecimal ask = close.get().add(halfSpread).setScale(10, RoundingMode.HALF_UP);
    BigDecimal changePercent = groupedDailyChangePercent(close.get(), firstDecimal(item, "o", "open"))
        .orElse(BigDecimal.ZERO);
    return Optional.of(new QuoteResponse(
        "quote",
        symbol.get(),
        bid,
        ask,
        close.get(),
        ask.subtract(bid),
        "massive-grouped-daily",
        firstLong(item, "t", "timestamp").orElse(Instant.now().toEpochMilli()),
        changePercent,
        firstDecimal(item, "h", "high").orElse(close.get()),
        firstDecimal(item, "l", "low").orElse(close.get()),
        firstDecimal(item, "v", "volume").orElse(null)));
  }

  private Optional<QuoteResponse> toForexSnapshotQuote(JsonNode item) {
    Optional<String> symbol = toNormalizedForexSymbol(item.path("ticker").asText(""));
    if (symbol.isEmpty()) {
      return Optional.empty();
    }

    JsonNode day = item.path("day");
    JsonNode minute = item.path("min");
    JsonNode lastQuote = item.path("lastQuote");
    Optional<BigDecimal> bid = firstDecimal(lastQuote, "b", "bid");
    Optional<BigDecimal> ask = firstDecimal(lastQuote, "a", "ask");
    Optional<BigDecimal> dayClose = firstDecimal(day, "c", "close");
    Optional<BigDecimal> minuteClose = firstDecimal(minute, "c", "close");
    Optional<BigDecimal> mid = midPrice(bid, ask)
        .or(() -> dayClose)
        .or(() -> minuteClose)
        .or(() -> firstDecimal(item.path("prevDay"), "c", "close"));
    if (mid.isEmpty()) {
      return Optional.empty();
    }

    BigDecimal resolvedBid = bid.orElse(mid.get());
    BigDecimal resolvedAsk = ask.orElse(mid.get());
    BigDecimal spread = resolvedAsk.subtract(resolvedBid);
    BigDecimal high24h = firstDecimal(day, "h", "high")
        .or(() -> firstDecimal(minute, "h", "high"))
        .orElse(mid.get());
    BigDecimal low24h = firstDecimal(day, "l", "low")
        .or(() -> firstDecimal(minute, "l", "low"))
        .orElse(mid.get());
    BigDecimal volume24h = firstDecimal(day, "v", "volume")
        .or(() -> firstDecimal(minute, "v", "volume"))
        .orElse(null);
    BigDecimal changePercent = firstDecimal(item, "todaysChangePerc", "todaysChangePercent")
        .or(() -> snapshotChangePercent(dayClose, item.path("prevDay")))
        .orElse(BigDecimal.ZERO);
    long timestamp = firstLong(lastQuote, "t", "timestamp")
        .or(() -> firstLong(item, "updated"))
        .orElse(Instant.now().toEpochMilli());

    return Optional.of(new QuoteResponse(
        "quote",
        symbol.get(),
        resolvedBid,
        resolvedAsk,
        mid.get(),
        spread,
        "massive-snapshot",
        timestamp,
        changePercent,
        high24h,
        low24h,
        volume24h));
  }

  private Optional<SymbolResponse> toForexSymbol(JsonNode item) {
    String providerSymbol = item.path("ticker").asText("");
    Optional<String> normalizedSymbol = toNormalizedForexSymbol(providerSymbol);
    if (normalizedSymbol.isEmpty()) {
      return Optional.empty();
    }

    String baseCurrency = firstText(item, "base_currency_symbol", normalizedSymbol.get().substring(0, 3));
    String quoteCurrency = firstText(item, "currency_symbol", normalizedSymbol.get().substring(3));
    String displayName = firstText(item, "name", baseCurrency + " / " + quoteCurrency);
    return Optional.of(new SymbolResponse(
        normalizedSymbol.get(),
        displayName,
        "FOREX",
        baseCurrency,
        quoteCurrency,
        DEFAULT_MIN_LOT,
        DEFAULT_MAX_LOT,
        DEFAULT_LEVERAGE,
        item.path("active").asBoolean(true),
        "massive",
        providerSymbol,
        false));
  }

  private List<CandleResponse> parseCandles(JsonNode body) {
    List<CandleResponse> candles = new ArrayList<>();
    JsonNode results = body.path("results");
    if (!results.isArray()) {
      return candles;
    }

    for (JsonNode item : results) {
      if (!item.hasNonNull("t")
          || !item.hasNonNull("o")
          || !item.hasNonNull("h")
          || !item.hasNonNull("l")
          || !item.hasNonNull("c")) {
        continue;
      }
      candles.add(new CandleResponse(
          item.path("t").asLong(),
          item.path("o").decimalValue(),
          item.path("h").decimalValue(),
          item.path("l").decimalValue(),
          item.path("c").decimalValue(),
          item.hasNonNull("v") ? item.path("v").decimalValue() : BigDecimal.ZERO));
    }
    return List.copyOf(candles);
  }

  private Optional<BigDecimal> midPrice(Optional<BigDecimal> bid, Optional<BigDecimal> ask) {
    if (bid.isEmpty() || ask.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(bid.get().add(ask.get()).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP));
  }

  private Optional<BigDecimal> snapshotChangePercent(Optional<BigDecimal> close, JsonNode prevDay) {
    Optional<BigDecimal> previousClose = firstDecimal(prevDay, "c", "close");
    if (close.isEmpty() || previousClose.isEmpty() || previousClose.get().compareTo(BigDecimal.ZERO) <= 0) {
      return Optional.empty();
    }
    return Optional.of(close.get()
        .subtract(previousClose.get())
        .divide(previousClose.get(), 10, RoundingMode.HALF_UP)
        .multiply(new BigDecimal("100"))
        .setScale(6, RoundingMode.HALF_UP));
  }

  private Optional<BigDecimal> groupedDailyChangePercent(BigDecimal close, Optional<BigDecimal> open) {
    if (open.isEmpty() || open.get().compareTo(BigDecimal.ZERO) <= 0) {
      return Optional.empty();
    }
    return Optional.of(close
        .subtract(open.get())
        .divide(open.get(), 10, RoundingMode.HALF_UP)
        .multiply(new BigDecimal("100"))
        .setScale(6, RoundingMode.HALF_UP));
  }

  private QuoteResponse toIndicativeQuote(String symbol, List<CandleResponse> candles) {
    CandleResponse candle = candles.getLast();
    MarketSummary summary = summarizeRecentCandles(candles);
    BigDecimal mid = candle.close();
    BigDecimal halfSpread = halfSpread(symbol);
    BigDecimal bid = mid.subtract(halfSpread).setScale(10, RoundingMode.HALF_UP);
    BigDecimal ask = mid.add(halfSpread).setScale(10, RoundingMode.HALF_UP);
    return new QuoteResponse(
        "quote",
        symbol,
        bid,
        ask,
        mid,
        ask.subtract(bid),
        "massive-aggregate",
        candle.timestamp(),
        summary.changePercent(),
        summary.high(),
        summary.low(),
        summary.volume());
  }

  private MarketSummary summarizeRecentCandles(List<CandleResponse> candles) {
    CandleResponse last = candles.getLast();
    long recentFrom = Instant.ofEpochMilli(last.timestamp()).minus(Duration.ofHours(24)).toEpochMilli();
    List<CandleResponse> recentCandles = candles.stream()
        .filter(candle -> candle.timestamp() >= recentFrom)
        .toList();
    List<CandleResponse> source = recentCandles.isEmpty() ? candles : recentCandles;
    CandleResponse first = source.getFirst();
    BigDecimal high = source.stream()
        .map(CandleResponse::high)
        .max(BigDecimal::compareTo)
        .orElse(last.high());
    BigDecimal low = source.stream()
        .map(CandleResponse::low)
        .min(BigDecimal::compareTo)
        .orElse(last.low());
    BigDecimal volume = source.stream()
        .map(CandleResponse::volume)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal changePercent = BigDecimal.ZERO;
    if (first.open().compareTo(BigDecimal.ZERO) > 0) {
      changePercent = last.close()
          .subtract(first.open())
          .divide(first.open(), 10, RoundingMode.HALF_UP)
          .multiply(new BigDecimal("100"))
          .setScale(6, RoundingMode.HALF_UP);
    }
    return new MarketSummary(changePercent, high, low, volume);
  }

  private List<CandleResponse> tail(List<CandleResponse> candles, int count) {
    if (candles.size() <= count) {
      return candles;
    }
    return List.copyOf(candles.subList(candles.size() - count, candles.size()));
  }

  private int requestedBarCount(AggregateWindow window, Instant from, Instant to) {
    long durationMs = Math.max(1, Duration.between(from, to).toMillis());
    long intervalMs = Math.max(1, window.duration().toMillis());
    long count = Math.max(1, (long) Math.ceil(durationMs / (double) intervalMs));
    return (int) Math.min(AGGREGATE_LIMIT, count);
  }

  private Optional<ForexPair> toForexPair(String providerSymbol) {
    return toNormalizedForexSymbol(providerSymbol)
        .map(symbol -> new ForexPair(symbol.substring(0, 3), symbol.substring(3)));
  }

  private Optional<String> toMassiveForexTicker(String providerSymbol) {
    return toNormalizedForexSymbol(providerSymbol).map(symbol -> "C:" + symbol);
  }

  private Optional<String> toNormalizedForexSymbol(String providerSymbol) {
    if (StrUtil.isBlank(providerSymbol)) {
      return Optional.empty();
    }

    String normalized = providerSymbol
        .trim()
        .toUpperCase(Locale.ROOT)
        .replace("C:", "")
        .replace("/", "")
        .replace("-", "")
        .replace("_", "");
    return normalized.length() == 6 ? Optional.of(normalized) : Optional.empty();
  }

  private Optional<AggregateWindow> toAggregateWindow(String timeframe) {
    if (StrUtil.isBlank(timeframe)) {
      return Optional.empty();
    }
    if ("time".equals(timeframe)) {
      return Optional.of(new AggregateWindow(1, "minute", Duration.ofMinutes(1)));
    }

    String normalized = timeframe.trim();
    if (normalized.length() < 2) {
      return Optional.empty();
    }

    String unit = normalized.substring(normalized.length() - 1);
    String multiplierText = normalized.substring(0, normalized.length() - 1);
    try {
      int multiplier = Integer.parseInt(multiplierText);
      if (multiplier <= 0) {
        return Optional.empty();
      }
      return switch (unit) {
        case "s" -> Optional.of(new AggregateWindow(multiplier, "second", Duration.ofSeconds(multiplier)));
        case "m" -> Optional.of(new AggregateWindow(multiplier, "minute", Duration.ofMinutes(multiplier)));
        case "h" -> Optional.of(new AggregateWindow(multiplier, "hour", Duration.ofHours(multiplier)));
        case "d" -> Optional.of(new AggregateWindow(multiplier, "day", Duration.ofDays(multiplier)));
        case "w" -> Optional.of(new AggregateWindow(multiplier, "week", Duration.ofDays(7L * multiplier)));
        case "M" -> Optional.of(new AggregateWindow(multiplier, "month", Duration.ofDays(30L * multiplier)));
        default -> Optional.empty();
      };
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private String normalizeAssetClass(String assetClass) {
    return StrUtil.isBlank(assetClass) ? "" : assetClass.trim().toUpperCase(Locale.ROOT);
  }

  private String firstText(JsonNode node, String field, String fallback) {
    String value = node.path(field).asText("");
    return StrUtil.isBlank(value) ? fallback : value;
  }

  private Optional<BigDecimal> firstDecimal(JsonNode node, String... fields) {
    for (String field : fields) {
      if (node.hasNonNull(field)) {
        return Optional.of(node.path(field).decimalValue());
      }
    }
    return Optional.empty();
  }

  private Optional<Long> firstLong(JsonNode node, String... fields) {
    for (String field : fields) {
      if (node.hasNonNull(field)) {
        return Optional.of(node.path(field).asLong());
      }
    }
    return Optional.empty();
  }

  private BigDecimal halfSpread(String symbol) {
    return symbol.endsWith("JPY") ? new BigDecimal("0.005") : new BigDecimal("0.00002");
  }

  private record ForexPair(String from, String to) {
  }

  private record AggregateWindow(int multiplier, String timespan, Duration duration) {
  }

  private record AggregateCacheKey(String ticker, int multiplier, String timespan, String fromDate, String toDate) {
  }

  private record AggregateCacheEntry(List<CandleResponse> candles, Instant expiresAt) {
  }

  private record MarketSummary(BigDecimal changePercent, BigDecimal high, BigDecimal low, BigDecimal volume) {
  }
}
