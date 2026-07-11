package com.fxplatform.market.adapter.okx;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.SymbolResponse;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OkxSpotMarketDataProviderTest {

  @Test
  void configuredPublicTimeoutStopsWholeBundleBeforeDownstreamRequests() throws IOException {
    AtomicInteger downstreamRequests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v5/market/ticker", exchange -> {
      java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(250).toNanos());
      writeJson(exchange, """
          {"code":"0","data":[{"bidPx":"1","askPx":"2","last":"1.5","ts":"1783814410000"}]}
          """);
    });
    for (String path : List.of(
        "/api/v5/market/books", "/api/v5/market/trades", "/api/v5/market/candles")) {
      server.createContext(path, exchange -> {
        downstreamRequests.incrementAndGet();
        writeJson(exchange, "{\"code\":\"0\",\"data\":[]}");
      });
    }
    server.start();
    try {
      var provider = new OkxSpotMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          HttpClient.newHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(), Clock.systemUTC(),
          Duration.ofSeconds(5), Duration.ofMillis(50));

      var bundle = provider.fetchSpotBundle(
          "BTCUSDT", "BTC-USDT",
          new com.fxplatform.market.model.CandleRequest(
              "1m", Instant.now().minusSeconds(60), Instant.now()));

      assertThat(bundle).isEmpty();
      assertThat(downstreamRequests).hasValue(0);
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void completeSpotFixtureBuildsSingleProviderBundleWithInjectedDependencies() throws IOException {
    Instant now = Instant.parse("2026-07-12T00:00:10Z");
    Instant quoteObservedAt = now.minusSeconds(1);
    Instant depthObservedAt = now.minusSeconds(2);
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v5/market/ticker", exchange -> writeJson(exchange, """
        {"code":"0","data":[{"instId":"BTC-USDT","last":"65000.15",
         "bidPx":"65000.10","askPx":"65000.20","open24h":"64000",
         "high24h":"66000","low24h":"63000","vol24h":"1234.5",
         "ts":"1700000000000"}]}
        """.replace("1700000000000", Long.toString(quoteObservedAt.toEpochMilli()))));
    server.createContext("/api/v5/market/books", exchange -> writeJson(exchange, """
        {"code":"0","data":[{"ts":"1700000000000","bids":[["65000.10","1.2"]],
         "asks":[["65000.20","2.3"]]}]}
        """.replace("1700000000000", Long.toString(depthObservedAt.toEpochMilli()))));
    server.createContext("/api/v5/market/trades", exchange -> writeJson(exchange, """
        {"code":"0","data":[{"tradeId":"10","px":"65000.15","sz":"0.3",
         "side":"buy","ts":"1700000000000"}]}
        """));
    server.createContext("/api/v5/market/candles", exchange -> writeJson(exchange, """
        {"code":"0","data":[["1700000000000","64000","65100","63900","65000","12.4"]]}
        """));
    server.start();
    try {
      OkxSpotMarketDataProvider provider = new OkxSpotMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          HttpClient.newHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(),
          Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(5));

      var bundle = provider.fetchSpotBundle(
          "BTCUSDT", "BTC-USDT",
          new com.fxplatform.market.model.CandleRequest("1m", now.minusSeconds(3600), now));

      assertThat(bundle).isPresent();
      assertThat(bundle.get().providerCode()).isEqualTo("okx");
      assertThat(bundle.get().providerSymbol()).isEqualTo("BTC-USDT");
      assertThat(bundle.get().changePercent()).isPositive();
      assertThat(bundle.get().high24h()).isEqualByComparingTo("66000");
      assertThat(bundle.get().low24h()).isEqualByComparingTo("63000");
      assertThat(bundle.get().volume24h()).isEqualByComparingTo("1234.5");
      assertThat(bundle.get().asOf()).isEqualTo(depthObservedAt);
      assertThat(bundle.get().orderBook().providerCode()).isEqualTo("okx");
      assertThat(bundle.get().recentTrades()).allMatch(trade -> depthObservedAt.equals(trade.asOf()));
      assertThat(bundle.get().candles()).allMatch(candle -> depthObservedAt.equals(candle.asOf()));
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchLatestQuoteMapsTickerResponseAndNormalizesInstrumentId() throws IOException {
    AtomicReference<String> query = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v5/market/ticker", exchange -> {
      query.set(exchange.getRequestURI().getQuery());
      writeJson(exchange, """
          {
            "code": "0",
            "data": [{
              "instId": "BTC-USDT",
              "last": "65000.15",
              "bidPx": "65000.10",
              "askPx": "65000.20",
              "high24h": "66000",
              "low24h": "64000",
              "vol24h": "1234.56",
              "ts": "1781462400000"
            }]
          }
          """);
    });
    server.start();
    try {
      OkxSpotMarketDataProvider provider = new OkxSpotMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort());

      Optional<QuoteResponse> quote = provider.fetchLatestQuote("BTCUSDT", "BTCUSDT");

      assertThat(quote).isPresent();
      assertThat(quote.get().symbol()).isEqualTo("BTCUSDT");
      assertThat(quote.get().bid()).isEqualByComparingTo("65000.10");
      assertThat(quote.get().ask()).isEqualByComparingTo("65000.20");
      assertThat(quote.get().mid()).isEqualByComparingTo("65000.15");
      assertThat(quote.get().spread()).isEqualByComparingTo("0.10");
      assertThat(quote.get().source()).isEqualTo("okx-spot");
      assertThat(quote.get().high24h()).isEqualByComparingTo("66000");
      assertThat(quote.get().low24h()).isEqualByComparingTo("64000");
      assertThat(quote.get().volume24h()).isEqualByComparingTo("1234.56");
      assertThat(quote.get().timestamp()).isEqualTo(1781462400000L);
      assertThat(query.get()).isEqualTo("instId=BTC-USDT");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchLatestQuotesUsesOkxSpotTickersAndFiltersRequestedSymbols() throws IOException {
    AtomicReference<String> query = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v5/market/tickers", exchange -> {
      query.set(exchange.getRequestURI().getQuery());
      writeJson(exchange, """
          {
            "code": "0",
            "data": [
              {
                "instId": "BTC-USDT",
                "last": "65000.15",
                "bidPx": "65000.10",
                "askPx": "65000.20",
                "high24h": "66000",
                "low24h": "64000",
                "vol24h": "1234.56",
                "ts": "1781462400000"
              },
              {
                "instId": "ETH-USDT",
                "last": "3400.15",
                "bidPx": "3400.10",
                "askPx": "3400.20",
                "high24h": "3500",
                "low24h": "3300",
                "vol24h": "2222",
                "ts": "1781462401000"
              },
              {
                "instId": "DOGE-USDT",
                "last": "0.16",
                "bidPx": "0.15",
                "askPx": "0.17",
                "ts": "1781462402000"
              }
            ]
          }
          """);
    });
    server.start();
    try {
      OkxSpotMarketDataProvider provider = new OkxSpotMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort());

      Map<String, QuoteResponse> quotes = provider.fetchLatestQuotes(Map.of(
          "BTCUSDT", "BTC-USDT",
          "ETHUSDT", "ETH-USDT"));

      assertThat(quotes).containsOnlyKeys("BTCUSDT", "ETHUSDT");
      assertThat(quotes.get("BTCUSDT").mid()).isEqualByComparingTo("65000.15");
      assertThat(quotes.get("ETHUSDT").volume24h()).isEqualByComparingTo("2222");
      assertThat(query.get()).isEqualTo("instType=SPOT");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchSymbolsExposesOkxProviderSymbols() {
    OkxSpotMarketDataProvider provider = new OkxSpotMarketDataProvider("http://127.0.0.1:1");

    List<SymbolResponse> symbols = provider.fetchSymbols("CRYPTO", 10);

    assertThat(symbols).extracting(SymbolResponse::symbol)
        .contains("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT");
    SymbolResponse btc = symbols.stream()
        .filter(symbol -> "BTCUSDT".equals(symbol.symbol()))
        .findFirst()
        .orElseThrow();
    assertThat(btc.provider()).isEqualTo("okx");
    assertThat(btc.providerSymbol()).isEqualTo("BTC-USDT");
    assertThat(btc.assetClass()).isEqualTo("CRYPTO");
    assertThat(btc.tradable()).isTrue();
  }

  @Test
  void fetchCandlesOrderBookAndTradesMapOkxMarketResponses() throws IOException {
    List<String> candleQueries = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v5/market/candles", exchange -> {
      candleQueries.add(exchange.getRequestURI().getRawQuery());
      writeJson(exchange, """
        {
          "code": "0",
          "data": [
            ["1781462460000", "101", "103", "100", "102", "10"],
            ["1781462400000", "100", "102", "99", "101", "8"]
          ]
        }
        """);
    });
    server.createContext("/api/v5/market/books", exchange -> writeJson(exchange, """
        {
          "code": "0",
          "data": [{
            "ts": "1781462400000",
            "bids": [["65000.10", "1.5", "0", "1"]],
            "asks": [["65000.20", "2.5", "0", "1"]]
          }]
        }
        """));
    server.createContext("/api/v5/market/trades", exchange -> writeJson(exchange, """
        {
          "code": "0",
          "data": [{
            "tradeId": "1001",
            "px": "65000.12",
            "sz": "0.25",
            "side": "buy",
            "ts": "1781462400000"
          }]
        }
        """));
    server.start();
    try {
      OkxSpotMarketDataProvider provider = new OkxSpotMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort());

      var candles = provider.fetchCandles("BTCUSDT", "BTC-USDT", "1h", Instant.ofEpochMilli(1), Instant.ofEpochMilli(2));
      provider.fetchCandles("BTCUSDT", "BTC-USDT", "4h", Instant.ofEpochMilli(1), Instant.ofEpochMilli(2));
      var orderBook = provider.fetchOrderBook("BTCUSDT", "BTC-USDT");
      var trades = provider.fetchRecentTrades("BTCUSDT", "BTC-USDT", 20);

      assertThat(candles).hasSize(2);
      assertThat(candles.get(0).timestamp()).isEqualTo(1781462400000L);
      assertThat(candles.get(0).open()).isEqualByComparingTo("100");
      assertThat(candleQueries).anyMatch(query -> query.contains("bar=1H"));
      assertThat(candleQueries).anyMatch(query -> query.contains("bar=4H"));
      assertThat(orderBook).isPresent();
      assertThat(orderBook.get().symbol()).isEqualTo("BTCUSDT");
      assertThat(orderBook.get().bids().getFirst().price()).isEqualByComparingTo("65000.10");
      assertThat(orderBook.get().asks().getFirst().amount()).isEqualByComparingTo("2.5");
      assertThat(trades).hasSize(1);
      assertThat(trades.getFirst().id()).isEqualTo("1001");
      assertThat(trades.getFirst().side()).isEqualTo("BUY");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private void writeJson(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private ExecutorService daemonExecutor() {
    return Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task);
      thread.setDaemon(true);
      return thread;
    });
  }
}
