package com.fxplatform.market.funding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.provider.MarketDataDurations;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OkxFundingRateProvider implements FundingRateProvider {

  private static final int HISTORY_LIMIT = 400;
  private static final int DEFAULT_INTERVAL_MINUTES = 480;

  private final String restBaseUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Duration requestTimeout;

  @Autowired
  public OkxFundingRateProvider(
      @Value("${okx.rest-base-url:https://www.okx.com}") String restBaseUrl,
      @Value("${market.public-http-timeout:2s}") String requestTimeoutValue
  ) {
    this(
        restBaseUrl,
        newHttpClient(MarketDataDurations.parsePositive(requestTimeoutValue, Duration.ofSeconds(2))),
        new ObjectMapper(),
        Clock.systemUTC(),
        MarketDataDurations.parsePositive(requestTimeoutValue, Duration.ofSeconds(2)));
  }

  OkxFundingRateProvider(
      String restBaseUrl,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Clock clock,
      Duration requestTimeout
  ) {
    this.restBaseUrl = restBaseUrl;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.requestTimeout = MarketDataDurations.positive(requestTimeout, Duration.ofSeconds(2));
  }

  @Override
  public FundingSource source() {
    return FundingSource.OKX;
  }

  @Override
  public Optional<FundingRateSnapshot> current(Query query) {
    String symbol = platformSymbol(query);
    String instrument = venueSymbol(query);
    if (symbol.isBlank() || instrument.isBlank()) {
      return Optional.empty();
    }
    Optional<JsonNode> funding = sendJson(uri(
        "/api/v5/public/funding-rate?instId=" + encode(instrument)))
        .flatMap(this::firstData);
    if (funding.isEmpty()
        || !instrument.equals(normalizeVenueSymbol(funding.get().path("instId").asText("")))) {
      return Optional.empty();
    }
    Optional<BigDecimal> rate = decimal(funding.get(), "fundingRate");
    Optional<Instant> fundingTime = instant(funding.get(), "fundingTime");
    Optional<Instant> nextFundingTime = instant(funding.get(), "nextFundingTime");
    Optional<Instant> fundingAsOf = instant(funding.get(), "ts");
    if (rate.isEmpty() || fundingTime.isEmpty() || nextFundingTime.isEmpty()
        || fundingAsOf.isEmpty()) {
      return Optional.empty();
    }
    Optional<Integer> interval = intervalMinutes(fundingTime.get(), nextFundingTime.get());
    if (interval.isEmpty()) {
      return Optional.empty();
    }
    Optional<JsonNode> mark = sendJson(uri(
        "/api/v5/public/mark-price?instType=SWAP&instId=" + encode(instrument)))
        .flatMap(this::firstData);
    if (mark.isEmpty()
        || !instrument.equals(normalizeVenueSymbol(mark.get().path("instId").asText("")))) {
      return Optional.empty();
    }
    Optional<BigDecimal> markPrice = positiveDecimal(mark.get(), "markPx");
    Optional<Instant> markAsOf = instant(mark.get(), "ts");
    if (markPrice.isEmpty() || markAsOf.isEmpty()) {
      return Optional.empty();
    }
    Instant asOf = fundingAsOf.get().isBefore(markAsOf.get())
        ? fundingAsOf.get()
        : markAsOf.get();
    return Optional.of(new FundingRateSnapshot(
        symbol,
        rate.get(),
        fundingTime.get(),
        nextFundingTime.get(),
        markPrice.get(),
        asOf,
        source().providerCode(),
        MarketSourceMode.PUBLIC_EXTERNAL,
        interval.get(),
        sha256(funding.get() + "|" + mark.get())));
  }

  @Override
  public List<FundingRateSnapshot> history(
      Query query,
      Instant afterExclusive,
      Instant atOrBefore
  ) {
    String symbol = platformSymbol(query);
    String instrument = venueSymbol(query);
    if (symbol.isBlank() || instrument.isBlank() || afterExclusive == null || atOrBefore == null
        || !afterExclusive.isBefore(atOrBefore)) {
      return List.of();
    }
    Optional<Long> initialCursor = plusOneMillis(atOrBefore);
    if (initialCursor.isEmpty()) {
      return List.of();
    }
    TreeMap<Instant, RateRow> rows = new TreeMap<>();
    long cursor = initialCursor.get();
    while (true) {
      Optional<JsonNode> body = sendJson(uri(
          "/api/v5/public/funding-rate-history?instId=" + encode(instrument)
              + "&after=" + cursor
              + "&limit=" + HISTORY_LIMIT));
      if (body.isEmpty()) {
        break;
      }
      JsonNode data = body.get().path("data");
      if (!data.isArray() || data.isEmpty()) {
        break;
      }
      long oldestObserved = Long.MAX_VALUE;
      for (JsonNode item : data) {
        Optional<Instant> fundingTime = instant(item, "fundingTime");
        if (fundingTime.isPresent()) {
          oldestObserved = Math.min(oldestObserved, fundingTime.get().toEpochMilli());
        }
        if (!instrument.equals(normalizeVenueSymbol(item.path("instId").asText("")))
            || fundingTime.isEmpty()
            || !fundingTime.get().isAfter(afterExclusive)
            || fundingTime.get().isAfter(atOrBefore)) {
          continue;
        }
        Optional<BigDecimal> rate = settledRate(item);
        if (rate.isEmpty()) {
          continue;
        }
        rows.putIfAbsent(fundingTime.get(), new RateRow(
            rate.get(), fundingTime.get(), item.toString()));
      }
      if (data.size() < HISTORY_LIMIT || oldestObserved == Long.MAX_VALUE
          || oldestObserved >= cursor) {
        break;
      }
      cursor = oldestObserved;
      if (Instant.ofEpochMilli(cursor).compareTo(afterExclusive) <= 0) {
        break;
      }
    }
    return snapshotsWithHistoricalMarks(symbol, instrument, rows.values().stream().toList());
  }

  private List<FundingRateSnapshot> snapshotsWithHistoricalMarks(
      String symbol,
      String instrument,
      List<RateRow> rows
  ) {
    if (rows.isEmpty()) {
      return List.of();
    }
    List<FundingRateSnapshot> snapshots = new ArrayList<>(rows.size());
    for (int index = 0; index < rows.size(); index++) {
      RateRow row = rows.get(index);
      Optional<MarkObservation> mark = historicalMark(instrument, row.fundingTime());
      if (mark.isEmpty()) {
        continue;
      }
      Instant nextTime = index + 1 < rows.size() ? rows.get(index + 1).fundingTime() : null;
      Optional<Integer> interval = nextTime == null
          ? trailingInterval(rows, index)
          : intervalMinutes(row.fundingTime(), nextTime);
      if (interval.isEmpty()) {
        continue;
      }
      if (nextTime == null) {
        try {
          nextTime = row.fundingTime().plusSeconds(Math.multiplyExact(interval.get(), 60L));
        } catch (ArithmeticException | java.time.DateTimeException ignored) {
          continue;
        }
      }
      Instant asOf = row.fundingTime().isBefore(mark.get().asOf())
          ? row.fundingTime()
          : mark.get().asOf();
      snapshots.add(new FundingRateSnapshot(
          symbol,
          row.rate(),
          row.fundingTime(),
          nextTime,
          mark.get().price(),
          asOf,
          source().providerCode(),
          MarketSourceMode.PUBLIC_EXTERNAL,
          interval.get(),
          sha256(row.rawPayload() + "|" + mark.get().rawPayload())));
    }
    return List.copyOf(snapshots);
  }

  private Optional<MarkObservation> historicalMark(String instrument, Instant fundingTime) {
    Optional<Long> cursor = plusOneMillis(fundingTime);
    if (cursor.isEmpty()) {
      return Optional.empty();
    }
    Optional<JsonNode> body = sendJson(uri(
        "/api/v5/market/history-mark-price-candles?instId=" + encode(instrument)
            + "&bar=1m&after=" + cursor.get() + "&limit=1"));
    if (body.isEmpty()) {
      return Optional.empty();
    }
    JsonNode data = body.get().path("data");
    if (!data.isArray() || data.isEmpty()) {
      return Optional.empty();
    }
    JsonNode candle = data.get(0);
    if (!candle.isArray() || candle.size() < 5) {
      return Optional.empty();
    }
    Optional<Instant> asOf = instantAt(candle, 0);
    Optional<BigDecimal> close = positiveDecimalAt(candle, 4);
    if (asOf.isEmpty() || close.isEmpty() || asOf.get().isAfter(fundingTime)) {
      return Optional.empty();
    }
    return Optional.of(new MarkObservation(close.get(), asOf.get(), candle.toString()));
  }

  private Optional<BigDecimal> settledRate(JsonNode item) {
    String realized = item.path("realizedRate").asText("").trim();
    return realized.isBlank() ? Optional.empty() : decimalText(realized);
  }

  private Optional<Integer> trailingInterval(List<RateRow> rows, int index) {
    if (index > 0) {
      return intervalMinutes(rows.get(index - 1).fundingTime(), rows.get(index).fundingTime());
    }
    return Optional.of(DEFAULT_INTERVAL_MINUTES);
  }

  private Optional<Integer> intervalMinutes(Instant from, Instant to) {
    long seconds;
    try {
      seconds = Duration.between(from, to).getSeconds();
    } catch (ArithmeticException ignored) {
      return Optional.empty();
    }
    if (seconds <= 0L || seconds % 60L != 0L || seconds / 60L > Integer.MAX_VALUE) {
      return Optional.empty();
    }
    return Optional.of((int) (seconds / 60L));
  }

  private Optional<JsonNode> firstData(JsonNode body) {
    JsonNode data = body.path("data");
    return data.isArray() && !data.isEmpty() ? Optional.of(data.get(0)) : Optional.empty();
  }

  private String platformSymbol(Query query) {
    return query == null || query.symbol() == null
        ? ""
        : query.symbol().trim().toUpperCase(Locale.ROOT);
  }

  private String venueSymbol(Query query) {
    if (query == null) {
      return "";
    }
    String candidate = query.providerSymbol() == null || query.providerSymbol().isBlank()
        ? query.symbol()
        : query.providerSymbol();
    return normalizeVenueSymbol(candidate);
  }

  private String normalizeVenueSymbol(String value) {
    if (value == null) {
      return "";
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT)
        .replace("_", "-")
        .replace("/", "-");
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
    return normalized.isBlank() ? "" : normalized + "-SWAP";
  }

  private Optional<JsonNode> sendJson(URI uri) {
    if (restBaseUrl == null || restBaseUrl.isBlank()) {
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
      JsonNode body = objectMapper.readTree(response.body());
      return "0".equals(body.path("code").asText()) ? Optional.of(body) : Optional.empty();
    } catch (IOException | RuntimeException ignored) {
      return Optional.empty();
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private Optional<BigDecimal> decimal(JsonNode node, String field) {
    if (!node.hasNonNull(field) || node.path(field).asText().isBlank()) {
      return Optional.empty();
    }
    return decimalText(node.path(field).asText());
  }

  private Optional<BigDecimal> decimalText(String value) {
    try {
      return Optional.of(new BigDecimal(value));
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private Optional<BigDecimal> positiveDecimal(JsonNode node, String field) {
    return decimal(node, field).filter(value -> value.compareTo(BigDecimal.ZERO) > 0);
  }

  private Optional<BigDecimal> positiveDecimalAt(JsonNode node, int index) {
    return index < node.size()
        ? decimalText(node.get(index).asText()).filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
        : Optional.empty();
  }

  private Optional<Instant> instant(JsonNode node, String field) {
    return node.hasNonNull(field) ? instantText(node.path(field).asText()) : Optional.empty();
  }

  private Optional<Instant> instantAt(JsonNode node, int index) {
    return index < node.size() ? instantText(node.get(index).asText()) : Optional.empty();
  }

  private Optional<Instant> instantText(String value) {
    try {
      long millis = Long.parseLong(value);
      return millis > 0L ? Optional.of(Instant.ofEpochMilli(millis)) : Optional.empty();
    } catch (NumberFormatException | java.time.DateTimeException ignored) {
      return Optional.empty();
    }
  }

  private Optional<Long> plusOneMillis(Instant instant) {
    try {
      long millis = instant.toEpochMilli();
      return millis == Long.MAX_VALUE ? Optional.empty() : Optional.of(millis + 1L);
    } catch (ArithmeticException ignored) {
      return Optional.empty();
    }
  }

  private URI uri(String pathAndQuery) {
    String base = restBaseUrl.endsWith("/")
        ? restBaseUrl.substring(0, restBaseUrl.length() - 1)
        : restBaseUrl;
    return URI.create(base + pathAndQuery);
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static HttpClient newHttpClient(Duration timeout) {
    return HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  private record RateRow(BigDecimal rate, Instant fundingTime, String rawPayload) {
  }

  private record MarkObservation(BigDecimal price, Instant asOf, String rawPayload) {
  }
}
