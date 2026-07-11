package com.fxplatform.market.adapter.binance;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BinanceUsdMMarketDataProviderTest {

  private static final Instant NOW = Instant.parse("2026-07-12T00:00:10Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void configuredPublicTimeoutStopsWholeBundleBeforeBookAndDownstreamRequests() throws IOException {
    AtomicInteger downstreamRequests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/fapi/v1/ticker/24hr", exchange -> {
      java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(250).toNanos());
      writeJson(exchange, "{\"lastPrice\":\"1.5\",\"closeTime\":1783814410000}");
    });
    for (String path : List.of(
        "/fapi/v1/ticker/bookTicker", "/fapi/v1/premiumIndex", "/fapi/v1/depth",
        "/fapi/v1/trades", "/fapi/v1/klines")) {
      server.createContext(path, exchange -> {
        downstreamRequests.incrementAndGet();
        writeJson(exchange, "{}");
      });
    }
    server.start();
    try {
      var provider = new BinanceUsdMMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          HttpClient.newHttpClient(), new ObjectMapper(), CLOCK,
          Duration.ofSeconds(5), Duration.ofMillis(50));

      var bundle = provider.fetchPerpetualBundle(
          "BTCUSDT-PERP", "BTCUSDT",
          new CandleRequest("1m", NOW.minusSeconds(60), NOW));

      assertThat(bundle).isEmpty();
      assertThat(downstreamRequests).hasValue(0);
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fixtureBuildsCompleteCanonicalPerpetualBundleAndIgnoresHistoricalCandleTimeForFreshness()
      throws IOException {
    HttpServer server = completeServer();
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.start();
    try {
      BinanceUsdMMarketDataProvider provider = provider(server);

      var bundle = provider.fetchPerpetualBundle(
          "BTCUSDT-PERP",
          "",
          new CandleRequest("1m", NOW.minus(Duration.ofHours(1)), NOW));

      assertThat(bundle).isPresent();
      assertThat(bundle.get().platformSymbol()).isEqualTo("BTCUSDT-PERP");
      assertThat(bundle.get().providerSymbol()).isEqualTo("BTCUSDT");
      assertThat(bundle.get().providerCode()).isEqualTo("binance-usdm");
      assertThat(bundle.get().sourceMode()).isEqualTo(MarketSourceMode.PUBLIC_EXTERNAL);
      assertThat(bundle.get().mark()).isEqualByComparingTo("65000.25");
      assertThat(bundle.get().index()).isEqualByComparingTo("65000.05");
      assertThat(bundle.get().changePercent()).isEqualByComparingTo("1.25");
      assertThat(bundle.get().high24h()).isEqualByComparingTo("66000");
      assertThat(bundle.get().low24h()).isEqualByComparingTo("64000");
      assertThat(bundle.get().volume24h()).isEqualByComparingTo("1234.5");
      assertThat(bundle.get().asOf()).isEqualTo(NOW.minusSeconds(3));
      assertThat(bundle.get().asOf()).isAfter(Instant.ofEpochMilli(1_700_000_000_000L));
      assertThat(bundle.get().expiresAt()).isEqualTo(NOW.plusSeconds(2));
      assertThat(bundle.get().orderBook().providerCode()).isEqualTo("binance-usdm");
      assertThat(bundle.get().recentTrades()).allMatch(trade ->
          "BTCUSDT".equals(trade.providerSymbol()) && bundle.get().asOf().equals(trade.asOf()));
      assertThat(bundle.get().candles()).allMatch(candle ->
          "binance-usdm".equals(candle.providerCode()) && bundle.get().asOf().equals(candle.asOf()));
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void missingMarkReferenceRejectsTheWholeBundle() throws IOException {
    HttpServer server = completeServer();
    server.removeContext("/fapi/v1/premiumIndex");
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.start();
    try {
      var bundle = provider(server).fetchPerpetualBundle(
          "BTCUSDT-PERP",
          "BTCUSDT",
          new CandleRequest("1m", NOW.minusSeconds(60), NOW));

      assertThat(bundle).isEmpty();
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private BinanceUsdMMarketDataProvider provider(HttpServer server) {
    return new BinanceUsdMMarketDataProvider(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        HttpClient.newHttpClient(),
        new ObjectMapper(),
        CLOCK,
        Duration.ofSeconds(5));
  }

  private HttpServer completeServer() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    long statisticsObservedAt = NOW.minusSeconds(3).toEpochMilli();
    long bookObservedAt = NOW.minusSeconds(1).toEpochMilli();
    long referenceObservedAt = NOW.minusSeconds(2).toEpochMilli();
    server.createContext("/fapi/v1/ticker/24hr", exchange -> writeJson(exchange, """
        {"symbol":"BTCUSDT","lastPrice":"65000.15","priceChangePercent":"1.25",
         "highPrice":"66000","lowPrice":"64000","volume":"1234.5","closeTime":%d}
        """.formatted(statisticsObservedAt)));
    server.createContext("/fapi/v1/ticker/bookTicker", exchange -> writeJson(exchange, """
        {"symbol":"BTCUSDT","bidPrice":"65000.10","bidQty":"1.2",
         "askPrice":"65000.20","askQty":"2.3","time":%d}
        """.formatted(bookObservedAt)));
    server.createContext("/fapi/v1/premiumIndex", exchange -> writeJson(exchange, """
        {"symbol":"BTCUSDT","markPrice":"65000.25","indexPrice":"65000.05","time":%d}
        """.formatted(referenceObservedAt)));
    server.createContext("/fapi/v1/depth", exchange -> writeJson(exchange, """
        {"lastUpdateId":1,"bids":[["65000.10","1.2"]],"asks":[["65000.20","2.3"]]}
        """));
    server.createContext("/fapi/v1/trades", exchange -> writeJson(exchange, """
        [{"id":10,"price":"65000.15","qty":"0.3","isBuyerMaker":false,"time":%d}]
        """.formatted(bookObservedAt)));
    server.createContext("/fapi/v1/klines", exchange -> writeJson(exchange, """
        [[1700000000000,"64000","65100","63900","65000","12.4"]]
        """));
    return server;
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
