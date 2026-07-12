package com.fxplatform.market.funding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.market.model.MarketSourceMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class OkxFundingRateProviderTest {

  private static final Instant NOW = Instant.parse("2026-07-13T01:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Instant FOUR = Instant.parse("2026-07-13T04:00:00Z");
  private static final Instant EIGHT = Instant.parse("2026-07-13T08:00:00Z");

  @Test
  void currentCombinesOkxFundingAndOkxMarkWithoutCrossVenueBundle() throws IOException {
    List<String> requests = new CopyOnWriteArrayList<>();
    HttpServer server = server();
    server.createContext("/api/v5/public/funding-rate", exchange -> {
      requests.add(exchange.getRequestURI().toString());
      writeJson(exchange, ok("""
          {"instId":"BTC-USDT-SWAP","fundingRate":"0.0002","fundingTime":"%d",
           "nextFundingTime":"%d","ts":"%d"}
          """.formatted(FOUR.toEpochMilli(), EIGHT.toEpochMilli(), NOW.minusSeconds(3).toEpochMilli())));
    });
    server.createContext("/api/v5/public/mark-price", exchange -> {
      requests.add(exchange.getRequestURI().toString());
      writeJson(exchange, ok("""
          {"instId":"BTC-USDT-SWAP","markPx":"64990.5","ts":"%d"}
          """.formatted(NOW.minusSeconds(7).toEpochMilli())));
    });
    ExecutorService executor = start(server);
    try {
      FundingRateSnapshot snapshot = provider(server).current(query("BTCUSDT-PERP", ""))
          .orElseThrow();

      assertThat(snapshot.symbol()).isEqualTo("BTCUSDT-PERP");
      assertThat(snapshot.fundingRate()).isEqualByComparingTo("0.0002");
      assertThat(snapshot.fundingTime()).isEqualTo(FOUR);
      assertThat(snapshot.nextFundingTime()).isEqualTo(EIGHT);
      assertThat(snapshot.intervalMinutes()).isEqualTo(240);
      assertThat(snapshot.markPrice()).isEqualByComparingTo("64990.5");
      assertThat(snapshot.asOf()).isEqualTo(NOW.minusSeconds(7));
      assertThat(snapshot.providerCode()).isEqualTo("okx-swap");
      assertThat(snapshot.sourceMode()).isEqualTo(MarketSourceMode.PUBLIC_EXTERNAL);
      assertThat(provider(server).source()).isEqualTo(FundingSource.OKX);
      assertThat(requests).anyMatch(path -> path.contains("funding-rate?instId=BTC-USDT-SWAP"));
      assertThat(requests).anyMatch(path -> path.contains(
          "mark-price?instType=SWAP&instId=BTC-USDT-SWAP"));
    } finally {
      stop(server, executor);
    }
  }

  @Test
  void historyRequiresRealizedRateAndUsesSameVenueHistoricalMarkCandles() throws IOException {
    List<String> markQueries = new CopyOnWriteArrayList<>();
    HttpServer server = server();
    server.createContext("/api/v5/public/funding-rate-history", exchange -> writeJson(exchange, """
        {"code":"0","data":[
          {"instId":"BTC-USDT-SWAP","fundingRate":"0.009","realizedRate":"0.0002",
           "fundingTime":"%d"},
          {"instId":"BTC-USDT-SWAP","fundingRate":"-0.0003","realizedRate":"",
           "fundingTime":"%d"}
        ]}
        """.formatted(EIGHT.toEpochMilli(), FOUR.toEpochMilli())));
    server.createContext("/api/v5/market/history-mark-price-candles", exchange -> {
      markQueries.add(exchange.getRequestURI().toString());
      long cursor = queryLong(exchange.getRequestURI(), "after");
      long fundingTime = cursor - 1L;
      String close = fundingTime == FOUR.toEpochMilli() ? "64000" : "65000";
      writeJson(exchange, """
          {"code":"0","data":[["%d","63000","65500","62000","%s","1"]]}
          """.formatted(fundingTime, close));
    });
    ExecutorService executor = start(server);
    try {
      List<FundingRateSnapshot> snapshots = provider(server).history(
          query("BTCUSDT-PERP", "BTC-USDT-SWAP"),
          FOUR.minusMillis(1),
          EIGHT);

      assertThat(snapshots).extracting(FundingRateSnapshot::fundingTime)
          .containsExactly(EIGHT);
      assertThat(snapshots).extracting(FundingRateSnapshot::fundingRate)
          .containsExactly(new java.math.BigDecimal("0.0002"));
      assertThat(snapshots).extracting(FundingRateSnapshot::markPrice)
          .containsExactly(new java.math.BigDecimal("65000"));
      assertThat(snapshots).extracting(FundingRateSnapshot::nextFundingTime)
          .containsExactly(Instant.parse("2026-07-13T16:00:00Z"));
      assertThat(markQueries).hasSize(1).allMatch(query ->
          query.contains("instId=BTC-USDT-SWAP") && query.contains("bar=1m")
              && query.contains("limit=1"));
    } finally {
      stop(server, executor);
    }
  }

  @Test
  void currentRejectsMissingSameVenueMark() throws IOException {
    HttpServer server = server();
    server.createContext("/api/v5/public/funding-rate", exchange -> writeJson(exchange, ok("""
        {"instId":"BTC-USDT-SWAP","fundingRate":"0.0002","fundingTime":"%d",
         "nextFundingTime":"%d","ts":"%d"}
        """.formatted(FOUR.toEpochMilli(), EIGHT.toEpochMilli(), NOW.toEpochMilli()))));
    server.createContext("/api/v5/public/mark-price", exchange -> writeJson(exchange,
        "{\"code\":\"0\",\"data\":[]}"));
    ExecutorService executor = start(server);
    try {
      assertThat(provider(server).current(query("BTCUSDT-PERP", "BTC-USDT-SWAP"))).isEmpty();
    } finally {
      stop(server, executor);
    }
  }

  private FundingRateProvider.Query query(String symbol, String providerSymbol) {
    return new FundingRateProvider.Query(symbol, providerSymbol,
        new java.math.BigDecimal("0.0001"), 480);
  }

  private OkxFundingRateProvider provider(HttpServer server) {
    return new OkxFundingRateProvider(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        HttpClient.newHttpClient(),
        new ObjectMapper(),
        CLOCK,
        Duration.ofSeconds(2));
  }

  private String ok(String item) {
    return "{\"code\":\"0\",\"data\":[" + item + "]}";
  }

  private long queryLong(URI uri, String name) {
    for (String part : uri.getRawQuery().split("&")) {
      String[] pair = part.split("=", 2);
      if (pair.length == 2 && pair[0].equals(name)) {
        return Long.parseLong(pair[1]);
      }
    }
    throw new IllegalArgumentException("Missing query parameter " + name);
  }

  private HttpServer server() throws IOException {
    return HttpServer.create(new InetSocketAddress(0), 0);
  }

  private ExecutorService start(HttpServer server) {
    ExecutorService executor = Executors.newCachedThreadPool(task -> {
      Thread thread = new Thread(task);
      thread.setDaemon(true);
      return thread;
    });
    server.setExecutor(executor);
    server.start();
    return executor;
  }

  private void stop(HttpServer server, ExecutorService executor) {
    server.stop(0);
    executor.shutdownNow();
  }

  private void writeJson(HttpExchange exchange, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
