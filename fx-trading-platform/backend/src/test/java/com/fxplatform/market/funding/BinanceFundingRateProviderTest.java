package com.fxplatform.market.funding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.market.model.MarketSourceMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
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

class BinanceFundingRateProviderTest {

  private static final Instant NOW = Instant.parse("2026-07-13T01:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Instant PREVIOUS = Instant.parse("2026-07-13T00:00:00Z");
  private static final Instant FUNDING_TIME = Instant.parse("2026-07-13T04:00:00Z");

  @Test
  void currentMapsPremiumIndexAndDerivesDynamicIntervalFromLatestSettlement() throws IOException {
    List<String> queries = new CopyOnWriteArrayList<>();
    HttpServer server = server();
    server.createContext("/fapi/v1/premiumIndex", exchange -> {
      queries.add(exchange.getRequestURI().toString());
      writeJson(exchange, """
          {"symbol":"BTCUSDT","markPrice":"65000.25","lastFundingRate":"-0.00025",
           "nextFundingTime":%d,"time":%d}
          """.formatted(FUNDING_TIME.toEpochMilli(), NOW.minusSeconds(5).toEpochMilli()));
    });
    server.createContext("/fapi/v1/fundingRate", exchange -> {
      queries.add(exchange.getRequestURI().toString());
      writeJson(exchange, """
          [{"symbol":"BTCUSDT","fundingRate":"0.0001","fundingTime":%d,
            "markPrice":"64000"}]
          """.formatted(PREVIOUS.toEpochMilli()));
    });
    ExecutorService executor = start(server);
    try {
      FundingRateSnapshot snapshot = provider(server).current(query("BTCUSDT-PERP", ""))
          .orElseThrow();

      assertThat(snapshot.symbol()).isEqualTo("BTCUSDT-PERP");
      assertThat(snapshot.fundingRate()).isEqualByComparingTo("-0.00025");
      assertThat(snapshot.fundingTime()).isEqualTo(FUNDING_TIME);
      assertThat(snapshot.nextFundingTime()).isEqualTo(Instant.parse("2026-07-13T08:00:00Z"));
      assertThat(snapshot.asOf()).isEqualTo(NOW.minusSeconds(5));
      assertThat(snapshot.markPrice()).isEqualByComparingTo("65000.25");
      assertThat(snapshot.providerCode()).isEqualTo("binance-usdm");
      assertThat(snapshot.sourceMode()).isEqualTo(MarketSourceMode.PUBLIC_EXTERNAL);
      assertThat(snapshot.intervalMinutes()).isEqualTo(240);
      assertThat(snapshot.rawPayloadHash()).hasSize(64);
      assertThat(provider(server).source()).isEqualTo(FundingSource.BINANCE);
      assertThat(queries).anyMatch(query -> query.contains("premiumIndex?symbol=BTCUSDT"));
      assertThat(queries).anyMatch(query -> query.contains("fundingRate?symbol=BTCUSDT&limit=1"));
    } finally {
      stop(server, executor);
    }
  }

  @Test
  void historyReturnsCanonicalAscendingPeriodsWithPerPeriodMarkAndNextBoundary()
      throws IOException {
    Instant four = Instant.parse("2026-07-13T04:00:00Z");
    Instant eight = Instant.parse("2026-07-13T08:00:00Z");
    HttpServer server = server();
    server.createContext("/fapi/v1/fundingRate", exchange -> writeJson(exchange, """
        [
          {"symbol":"BTCUSDT","fundingRate":"0.0001","fundingTime":%d,"markPrice":"64000"},
          {"symbol":"BTCUSDT","fundingRate":"-0.0002","fundingTime":%d,"markPrice":"64500"},
          {"symbol":"BTCUSDT","fundingRate":"0.0003","fundingTime":%d,"markPrice":"65000"}
        ]
        """.formatted(PREVIOUS.toEpochMilli(), four.toEpochMilli(), eight.toEpochMilli())));
    ExecutorService executor = start(server);
    try {
      List<FundingRateSnapshot> snapshots = provider(server).history(
          query("BTCUSDT-PERP", "BTCUSDT"),
          PREVIOUS.minusMillis(1),
          eight);

      assertThat(snapshots).extracting(FundingRateSnapshot::fundingTime)
          .containsExactly(PREVIOUS, four, eight);
      assertThat(snapshots).extracting(FundingRateSnapshot::nextFundingTime)
          .containsExactly(four, eight, Instant.parse("2026-07-13T12:00:00Z"));
      assertThat(snapshots).extracting(FundingRateSnapshot::fundingRate)
          .containsExactly(new java.math.BigDecimal("0.0001"),
              new java.math.BigDecimal("-0.0002"), new java.math.BigDecimal("0.0003"));
      assertThat(snapshots).extracting(FundingRateSnapshot::markPrice)
          .containsExactly(new java.math.BigDecimal("64000"),
              new java.math.BigDecimal("64500"), new java.math.BigDecimal("65000"));
      assertThat(snapshots).allMatch(snapshot -> snapshot.intervalMinutes() == 240);
    } finally {
      stop(server, executor);
    }
  }

  @Test
  void currentRejectsIncompletePremiumPayloadWithoutUsingFabricatedTimes() throws IOException {
    HttpServer server = server();
    server.createContext("/fapi/v1/premiumIndex", exchange -> writeJson(exchange, """
        {"symbol":"BTCUSDT","markPrice":"65000","lastFundingRate":"0.0001",
         "nextFundingTime":%d}
        """.formatted(FUNDING_TIME.toEpochMilli())));
    server.createContext("/fapi/v1/fundingRate", exchange -> writeJson(exchange, "[]"));
    ExecutorService executor = start(server);
    try {
      assertThat(provider(server).current(query("BTCUSDT-PERP", "BTCUSDT"))).isEmpty();
    } finally {
      stop(server, executor);
    }
  }

  private FundingRateProvider.Query query(String symbol, String providerSymbol) {
    return new FundingRateProvider.Query(symbol, providerSymbol,
        new java.math.BigDecimal("0.0001"), 480);
  }

  private BinanceFundingRateProvider provider(HttpServer server) {
    return new BinanceFundingRateProvider(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        HttpClient.newHttpClient(),
        new ObjectMapper(),
        CLOCK,
        Duration.ofSeconds(2));
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
