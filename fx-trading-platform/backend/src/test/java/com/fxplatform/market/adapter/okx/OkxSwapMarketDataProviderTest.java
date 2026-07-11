package com.fxplatform.market.adapter.okx;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.market.model.CandleRequest;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OkxSwapMarketDataProviderTest {

  private static final Instant NOW = Instant.parse("2026-07-12T00:00:10Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void configuredPublicTimeoutStopsWholeBundleBeforeReferenceAndDownstreamRequests() throws IOException {
    AtomicInteger downstreamRequests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v5/market/ticker", exchange -> {
      java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(250).toNanos());
      writeJson(exchange, ok("""
          {"bidPx":"1","askPx":"2","last":"1.5","ts":"1783814410000"}
          """));
    });
    for (String path : List.of(
        "/api/v5/public/mark-price", "/api/v5/market/index-tickers", "/api/v5/market/books",
        "/api/v5/market/trades", "/api/v5/market/candles")) {
      server.createContext(path, exchange -> {
        downstreamRequests.incrementAndGet();
        writeJson(exchange, "{\"code\":\"0\",\"data\":[]}");
      });
    }
    server.start();
    try {
      var provider = new OkxSwapMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          HttpClient.newHttpClient(), new ObjectMapper(), CLOCK,
          Duration.ofSeconds(5), Duration.ofMillis(50));

      var bundle = provider.fetchPerpetualBundle(
          "BTCUSDT-PERP", "BTC-USDT-SWAP",
          new CandleRequest("1m", NOW.minusSeconds(60), NOW));

      assertThat(bundle).isEmpty();
      assertThat(downstreamRequests).hasValue(0);
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fixtureMapsCanonicalPerpetualToSwapAndIndexInAdapter() throws IOException {
    List<String> queries = new CopyOnWriteArrayList<>();
    HttpServer server = completeServer(queries);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.start();
    try {
      OkxSwapMarketDataProvider provider = new OkxSwapMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          HttpClient.newHttpClient(),
          new ObjectMapper(),
          CLOCK,
          Duration.ofSeconds(5));

      var bundle = provider.fetchPerpetualBundle(
          "BTCUSDT-PERP",
          "",
          new CandleRequest("4h", NOW.minus(Duration.ofHours(1)), NOW));

      assertThat(bundle).isPresent();
      assertThat(bundle.get().platformSymbol()).isEqualTo("BTCUSDT-PERP");
      assertThat(bundle.get().providerSymbol()).isEqualTo("BTC-USDT-SWAP");
      assertThat(bundle.get().providerCode()).isEqualTo("okx-swap");
      assertThat(bundle.get().sourceMode()).isEqualTo(MarketSourceMode.PUBLIC_EXTERNAL);
      assertThat(bundle.get().mark()).isEqualByComparingTo("65000.25");
      assertThat(bundle.get().index()).isEqualByComparingTo("65000.05");
      assertThat(bundle.get().changePercent()).isPositive();
      assertThat(bundle.get().high24h()).isEqualByComparingTo("66000");
      assertThat(bundle.get().low24h()).isEqualByComparingTo("63000");
      assertThat(bundle.get().volume24h()).isEqualByComparingTo("1234.5");
      assertThat(bundle.get().asOf()).isEqualTo(NOW.minusSeconds(3));
      assertThat(bundle.get().candles().getFirst().timestamp()).isEqualTo(1_700_000_000_000L);
      assertThat(bundle.get().asOf()).isAfter(Instant.ofEpochMilli(bundle.get().candles().getFirst().timestamp()));
      assertThat(queries).anyMatch(query -> query.contains("instId=BTC-USDT-SWAP"));
      assertThat(queries).anyMatch(query -> query.contains("instId=BTC-USDT") && query.contains("index"));
      assertThat(queries).anyMatch(query -> query.contains("bar=4H"));
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void missingIndexRejectsWholeSwapBundle() throws IOException {
    HttpServer server = completeServer(new CopyOnWriteArrayList<>());
    server.removeContext("/api/v5/market/index-tickers");
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.start();
    try {
      OkxSwapMarketDataProvider provider = new OkxSwapMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          HttpClient.newHttpClient(), new ObjectMapper(), CLOCK, Duration.ofSeconds(5));

      assertThat(provider.fetchPerpetualBundle(
          "BTCUSDT-PERP", "BTC-USDT-SWAP",
          new CandleRequest("1m", NOW.minusSeconds(60), NOW))).isEmpty();
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private HttpServer completeServer(List<String> queries) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    long quoteObservedAt = NOW.minusSeconds(1).toEpochMilli();
    long depthObservedAt = NOW.minusSeconds(2).toEpochMilli();
    long referenceObservedAt = NOW.minusSeconds(3).toEpochMilli();
    server.createContext("/api/v5/market/ticker", exchange -> {
      queries.add(exchange.getRequestURI().getQuery());
      writeJson(exchange, ok("""
          {"instId":"BTC-USDT-SWAP","bidPx":"65000.10","askPx":"65000.20",
           "last":"65000.15","open24h":"64000","high24h":"66000",
           "low24h":"63000","vol24h":"1234.5","ts":"%d"}
          """.formatted(quoteObservedAt)));
    });
    server.createContext("/api/v5/public/mark-price", exchange -> {
      queries.add(exchange.getRequestURI().getQuery());
      writeJson(exchange, ok("""
          {"instId":"BTC-USDT-SWAP","markPx":"65000.25","ts":"%d"}
          """.formatted(referenceObservedAt)));
    });
    server.createContext("/api/v5/market/index-tickers", exchange -> {
      queries.add("index:" + exchange.getRequestURI().getQuery());
      writeJson(exchange, ok("""
          {"instId":"BTC-USDT","idxPx":"65000.05","ts":"%d"}
          """.formatted(referenceObservedAt)));
    });
    server.createContext("/api/v5/market/books", exchange -> writeJson(exchange, ok("""
        {"ts":"%d","bids":[["65000.10","1.2","0","1"]],
         "asks":[["65000.20","2.3","0","1"]]}
        """.formatted(depthObservedAt))));
    server.createContext("/api/v5/market/trades", exchange -> writeJson(exchange, ok("""
        {"tradeId":"10","px":"65000.15","sz":"0.3","side":"buy","ts":"%d"}
        """.formatted(1_700_000_000_000L))));
    server.createContext("/api/v5/market/candles", exchange -> {
      queries.add(exchange.getRequestURI().getRawQuery());
      writeJson(exchange, """
        {"code":"0","data":[["1700000000000","64000","65100","63900","65000","12.4"]]}
        """);
    });
    return server;
  }

  private String ok(String dataItem) {
    return "{\"code\":\"0\",\"data\":[" + dataItem + "]}";
  }

  private void writeJson(HttpExchange exchange, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private ExecutorService daemonExecutor() {
    return Executors.newCachedThreadPool(task -> {
      Thread thread = new Thread(task);
      thread.setDaemon(true);
      return thread;
    });
  }
}
