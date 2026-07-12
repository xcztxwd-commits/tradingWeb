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
public class BinanceFundingRateProvider implements FundingRateProvider {

  private static final int HISTORY_LIMIT = 1000;
  private static final int DEFAULT_INTERVAL_MINUTES = 480;

  private final String restBaseUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Duration requestTimeout;

  @Autowired
  public BinanceFundingRateProvider(
      @Value("${binance.futures-base-url:https://fapi.binance.com}") String restBaseUrl,
      @Value("${market.public-http-timeout:2s}") String requestTimeoutValue
  ) {
    this(
        restBaseUrl,
        newHttpClient(MarketDataDurations.parsePositive(requestTimeoutValue, Duration.ofSeconds(2))),
        new ObjectMapper(),
        Clock.systemUTC(),
        MarketDataDurations.parsePositive(requestTimeoutValue, Duration.ofSeconds(2)));
  }

  BinanceFundingRateProvider(
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
    return FundingSource.BINANCE;
  }

  @Override
  public Optional<FundingRateSnapshot> current(Query query) {
    String symbol = platformSymbol(query);
    String ticker = venueSymbol(query);
    if (symbol.isBlank() || ticker.isBlank()) {
      return Optional.empty();
    }
    Optional<JsonNode> premiumBody = sendJson(uri(
        "/fapi/v1/premiumIndex?symbol=" + encode(ticker)));
    if (premiumBody.isEmpty()) {
      return Optional.empty();
    }
    JsonNode premium = premiumBody.get();
    if (!ticker.equals(normalizeVenueSymbol(premium.path("symbol").asText("")))) {
      return Optional.empty();
    }
    Optional<BigDecimal> rate = decimal(premium, "lastFundingRate");
    Optional<BigDecimal> mark = positiveDecimal(premium, "markPrice");
    Optional<Instant> fundingTime = instant(premium, "nextFundingTime");
    Optional<Instant> asOf = instant(premium, "time");
    if (rate.isEmpty() || mark.isEmpty() || fundingTime.isEmpty() || asOf.isEmpty()) {
      return Optional.empty();
    }

    Optional<JsonNode> latestBody = sendJson(uri(
        "/fapi/v1/fundingRate?symbol=" + encode(ticker) + "&limit=1"));
    if (latestBody.isEmpty() || !latestBody.get().isArray() || latestBody.get().isEmpty()) {
      return Optional.empty();
    }
    JsonNode latest = latestBody.get().get(latestBody.get().size() - 1);
    if (!ticker.equals(normalizeVenueSymbol(latest.path("symbol").asText("")))) {
      return Optional.empty();
    }
    Optional<Instant> latestFundingTime = instant(latest, "fundingTime");
    Optional<Integer> interval = latestFundingTime.flatMap(previous ->
        intervalMinutes(previous, fundingTime.get()));
    if (interval.isEmpty()) {
      return Optional.empty();
    }
    Instant nextFundingTime;
    try {
      nextFundingTime = fundingTime.get().plusSeconds(Math.multiplyExact(interval.get(), 60L));
    } catch (ArithmeticException | java.time.DateTimeException ignored) {
      return Optional.empty();
    }
    return Optional.of(new FundingRateSnapshot(
        symbol,
        rate.get(),
        fundingTime.get(),
        nextFundingTime,
        mark.get(),
        asOf.get(),
        source().providerCode(),
        MarketSourceMode.PUBLIC_EXTERNAL,
        interval.get(),
        sha256(premium + "|" + latest)));
  }

  @Override
  public List<FundingRateSnapshot> history(
      Query query,
      Instant afterExclusive,
      Instant atOrBefore
  ) {
    String symbol = platformSymbol(query);
    String ticker = venueSymbol(query);
    if (symbol.isBlank() || ticker.isBlank() || afterExclusive == null || atOrBefore == null
        || !afterExclusive.isBefore(atOrBefore)) {
      return List.of();
    }
    Optional<Long> startMillis = plusOneMillis(afterExclusive);
    if (startMillis.isEmpty()) {
      return List.of();
    }
    long endMillis;
    try {
      endMillis = atOrBefore.toEpochMilli();
    } catch (ArithmeticException ignored) {
      return List.of();
    }
    TreeMap<Instant, RateRow> rows = new TreeMap<>();
    long cursor = startMillis.get();
    while (cursor <= endMillis) {
      Optional<JsonNode> body = sendJson(uri(
          "/fapi/v1/fundingRate?symbol=" + encode(ticker)
              + "&startTime=" + cursor
              + "&endTime=" + endMillis
              + "&limit=" + HISTORY_LIMIT));
      if (body.isEmpty() || !body.get().isArray() || body.get().isEmpty()) {
        break;
      }
      long greatestObserved = Long.MIN_VALUE;
      for (JsonNode item : body.get()) {
        Optional<Instant> fundingTime = instant(item, "fundingTime");
        if (fundingTime.isPresent()) {
          greatestObserved = Math.max(greatestObserved, fundingTime.get().toEpochMilli());
        }
        if (!ticker.equals(normalizeVenueSymbol(item.path("symbol").asText("")))
            || fundingTime.isEmpty()
            || !fundingTime.get().isAfter(afterExclusive)
            || fundingTime.get().isAfter(atOrBefore)) {
          continue;
        }
        Optional<BigDecimal> rate = decimal(item, "fundingRate");
        Optional<BigDecimal> mark = positiveDecimal(item, "markPrice");
        if (rate.isEmpty() || mark.isEmpty()) {
          continue;
        }
        rows.putIfAbsent(fundingTime.get(), new RateRow(
            rate.get(), fundingTime.get(), mark.get(), sha256(item.toString())));
      }
      if (body.get().size() < HISTORY_LIMIT || greatestObserved == Long.MIN_VALUE
          || greatestObserved == Long.MAX_VALUE || greatestObserved + 1L <= cursor) {
        break;
      }
      cursor = greatestObserved + 1L;
    }
    return snapshots(symbol, rows.values().stream().toList());
  }

  private List<FundingRateSnapshot> snapshots(String symbol, List<RateRow> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    List<FundingRateSnapshot> snapshots = new ArrayList<>(rows.size());
    for (int index = 0; index < rows.size(); index++) {
      RateRow row = rows.get(index);
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
      snapshots.add(new FundingRateSnapshot(
          symbol,
          row.rate(),
          row.fundingTime(),
          nextTime,
          row.markPrice(),
          row.fundingTime(),
          source().providerCode(),
          MarketSourceMode.PUBLIC_EXTERNAL,
          interval.get(),
          row.rawPayloadHash()));
    }
    return List.copyOf(snapshots);
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
    return normalized.replace("-", "");
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
      return Optional.of(objectMapper.readTree(response.body()));
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
    try {
      return Optional.of(new BigDecimal(node.path(field).asText()));
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private Optional<BigDecimal> positiveDecimal(JsonNode node, String field) {
    return decimal(node, field).filter(value -> value.compareTo(BigDecimal.ZERO) > 0);
  }

  private Optional<Instant> instant(JsonNode node, String field) {
    if (!node.hasNonNull(field)) {
      return Optional.empty();
    }
    try {
      long millis = Long.parseLong(node.path(field).asText());
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

  private record RateRow(
      BigDecimal rate,
      Instant fundingTime,
      BigDecimal markPrice,
      String rawPayloadHash
  ) {
  }
}
