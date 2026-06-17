package com.fxplatform.market.adapter.massive;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MassiveRestClientTest {

  @Test
  void fetchLatestQuoteMapsForexLastQuoteResponse() throws IOException {
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/v1/last_quote/currencies/EUR/USD", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] body = """
          {
            "status": "success",
            "symbol": "EUR/USD",
            "last": {
              "bid": 1.08318,
              "ask": 1.08322,
              "exchange": 48,
              "timestamp": 1710000000123
            },
            "request_id": "test-request"
          }
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      MassiveRestClient client = new MassiveRestClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort());

      Optional<QuoteResponse> quote = client.fetchLatestQuote("EURUSD", "C:EURUSD");

      assertThat(quote).isPresent();
      assertThat(quote.get().symbol()).isEqualTo("EURUSD");
      assertThat(quote.get().bid()).isEqualByComparingTo("1.08318");
      assertThat(quote.get().ask()).isEqualByComparingTo("1.08322");
      assertThat(quote.get().source()).isEqualTo("massive");
      assertThat(quote.get().timestamp()).isEqualTo(1710000000123L);
      assertThat(authorization.get()).isEqualTo("Bearer test-key");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchSymbolsMapsForexTickerResponse() throws IOException {
    AtomicReference<String> query = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/v3/reference/tickers", exchange -> {
      query.set(exchange.getRequestURI().getQuery());
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] body = """
          {
            "status": "OK",
            "results": [
              {
                "ticker": "C:EURUSD",
                "name": "Euro / US Dollar",
                "market": "fx",
                "active": true,
                "base_currency_symbol": "EUR",
                "currency_symbol": "USD"
              },
              {
                "ticker": "C:USDJPY",
                "name": "US Dollar / Japanese Yen",
                "market": "fx",
                "active": true,
                "base_currency_symbol": "USD",
                "currency_symbol": "JPY"
              }
            ]
          }
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      MassiveRestClient client = new MassiveRestClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort());

      List<SymbolResponse> symbols = client.fetchSymbols("FOREX", 2);

      assertThat(symbols).extracting(SymbolResponse::symbol).containsExactly("EURUSD", "USDJPY");
      assertThat(symbols.getFirst().provider()).isEqualTo("massive");
      assertThat(symbols.getFirst().providerSymbol()).isEqualTo("C:EURUSD");
      assertThat(symbols.getFirst().tradable()).isFalse();
      assertThat(query.get()).isEqualTo("market=fx&active=true&limit=2");
      assertThat(authorization.get()).isEqualTo("Bearer test-key");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchSymbolsFollowsMassiveNextUrlUntilRequestedLimit() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    List<String> queries = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/v3/reference/tickers", exchange -> {
      int call = calls.incrementAndGet();
      queries.add(exchange.getRequestURI().getQuery());
      int port = server.getAddress().getPort();
      String response = call == 1
          ? """
          {
            "status": "OK",
            "results": [
              {
                "ticker": "C:AEDAUD",
                "name": "UAE Dirham / Australian Dollar",
                "market": "fx",
                "active": true,
                "base_currency_symbol": "AED",
                "currency_symbol": "AUD"
              }
            ],
            "next_url": "http://127.0.0.1:%d/v3/reference/tickers?cursor=page-2"
          }
          """.formatted(port)
          : """
          {
            "status": "OK",
            "results": [
              {
                "ticker": "C:AEDBHD",
                "name": "UAE Dirham / Bahraini Dinar",
                "market": "fx",
                "active": true,
                "base_currency_symbol": "AED",
                "currency_symbol": "BHD"
              },
              {
                "ticker": "C:AEDCAD",
                "name": "UAE Dirham / Canadian Dollar",
                "market": "fx",
                "active": true,
                "base_currency_symbol": "AED",
                "currency_symbol": "CAD"
              }
            ]
          }
          """;
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      MassiveRestClient client = new MassiveRestClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort());

      List<SymbolResponse> symbols = client.fetchSymbols("FOREX", 3);

      assertThat(symbols).extracting(SymbolResponse::symbol).containsExactly("AEDAUD", "AEDBHD", "AEDCAD");
      assertThat(calls.get()).isEqualTo(2);
      assertThat(queries).containsExactly("market=fx&active=true&limit=3", "cursor=page-2");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchMarketSnapshotsMapsForexFullMarketSnapshotResponse() throws IOException {
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/v2/snapshot/locale/global/markets/forex/tickers", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] body = """
          {
            "status": "OK",
            "tickers": [
              {
                "ticker": "C:EURUSD",
                "day": {
                  "c": 1.08320,
                  "h": 1.09000,
                  "l": 1.07000,
                  "v": 12345
                },
                "lastQuote": {
                  "b": 1.08318,
                  "a": 1.08322,
                  "t": 1710000000123
                },
                "prevDay": {
                  "c": 1.08000
                },
                "todaysChangePerc": 0.296296,
                "updated": 1710000000999
              }
            ]
          }
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      MassiveRestClient client = new MassiveRestClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort());

      Map<String, QuoteResponse> snapshots = client.fetchMarketSnapshots("FOREX", 1);

      assertThat(snapshots).containsOnlyKeys("EURUSD");
      QuoteResponse quote = snapshots.get("EURUSD");
      assertThat(quote.bid()).isEqualByComparingTo("1.08318");
      assertThat(quote.ask()).isEqualByComparingTo("1.08322");
      assertThat(quote.mid()).isEqualByComparingTo("1.08320");
      assertThat(quote.spread()).isEqualByComparingTo("0.00004");
      assertThat(quote.changePercent()).isEqualByComparingTo("0.296296");
      assertThat(quote.high24h()).isEqualByComparingTo("1.09000");
      assertThat(quote.low24h()).isEqualByComparingTo("1.07000");
      assertThat(quote.volume24h()).isEqualByComparingTo("12345");
      assertThat(quote.source()).isEqualTo("massive-snapshot");
      assertThat(quote.timestamp()).isEqualTo(1710000000123L);
      assertThat(authorization.get()).isEqualTo("Bearer test-key");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchLatestQuotesUsesForexMarketSnapshotAndFiltersRequestedSymbols() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/v2/snapshot/locale/global/markets/forex/tickers", exchange -> {
      calls.incrementAndGet();
      byte[] body = """
          {
            "status": "OK",
            "tickers": [
              {
                "ticker": "C:EURUSD",
                "day": { "c": 1.08320, "h": 1.09000, "l": 1.07000, "v": 12345 },
                "lastQuote": { "b": 1.08318, "a": 1.08322, "t": 1710000000123 }
              },
              {
                "ticker": "C:USDJPY",
                "day": { "c": 156.420, "h": 157.000, "l": 155.000, "v": 22222 },
                "lastQuote": { "b": 156.415, "a": 156.425, "t": 1710000000456 }
              },
              {
                "ticker": "C:AUDUSD",
                "day": { "c": 0.66000 },
                "lastQuote": { "b": 0.65998, "a": 0.66002, "t": 1710000000789 }
              }
            ]
          }
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      MassiveRestClient client = new MassiveRestClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort());

      Map<String, QuoteResponse> quotes = client.fetchLatestQuotes(Map.of(
          "EURUSD", "C:EURUSD",
          "USDJPY", "C:USDJPY"));

      assertThat(quotes).containsOnlyKeys("EURUSD", "USDJPY");
      assertThat(quotes.get("EURUSD").mid()).isEqualByComparingTo("1.08320");
      assertThat(quotes.get("USDJPY").mid()).isEqualByComparingTo("156.420");
      assertThat(calls.get()).isEqualTo(1);
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchMarketSnapshotsFallsBackToGroupedDailyForexSummary() throws IOException {
    List<String> paths = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/v2/snapshot/locale/global/markets/forex/tickers", exchange -> {
      paths.add(exchange.getRequestURI().getPath());
      byte[] body = """
          {
            "status": "NOT_AUTHORIZED",
            "message": "not entitled"
          }
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(403, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.createContext("/v2/aggs/grouped/locale/global/market/fx", exchange -> {
      paths.add(exchange.getRequestURI().getPath());
      byte[] body = """
          {
            "status": "OK",
            "results": [
              {
                "T": "C:JPYZAR",
                "v": 122525,
                "o": 0.10164,
                "c": 0.10141,
                "h": 0.10196,
                "l": 0.10131,
                "t": 1781297940000
              },
              {
                "T": "C:EURUSD",
                "v": 250146,
                "o": 1.15783,
                "c": 1.15655,
                "h": 1.15895,
                "l": 1.15560,
                "t": 1781297940000
              }
            ]
          }
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      MassiveRestClient client = new MassiveRestClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort());

      Map<String, QuoteResponse> snapshots = client.fetchMarketSnapshots("FOREX", 1);

      assertThat(paths.getFirst()).isEqualTo("/v2/snapshot/locale/global/markets/forex/tickers");
      assertThat(paths.get(1)).contains("/v2/aggs/grouped/locale/global/market/fx/");
      assertThat(snapshots).containsKeys("JPYZAR", "EURUSD");
      QuoteResponse quote = snapshots.get("EURUSD");
      assertThat(quote.mid()).isEqualByComparingTo("1.15655");
      assertThat(quote.volume24h()).isEqualByComparingTo("250146");
      assertThat(quote.high24h()).isEqualByComparingTo("1.15895");
      assertThat(quote.low24h()).isEqualByComparingTo("1.15560");
      assertThat(quote.changePercent()).isEqualByComparingTo("-0.110552");
      assertThat(quote.source()).isEqualTo("massive-grouped-daily");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchCandlesMapsForexAggregateResponse() throws IOException {
    AtomicReference<String> query = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/v2/aggs/ticker/C:EURUSD/range/5/minute/2026-06-05/2026-06-12", exchange -> {
      query.set(exchange.getRequestURI().getQuery());
      byte[] body = """
          {
            "status": "OK",
            "ticker": "C:EURUSD",
            "results": [
              {
                "o": 1.15710,
                "h": 1.15740,
                "l": 1.15690,
                "c": 1.15730,
                "v": 0,
                "t": 1781222400000
              }
            ],
            "resultsCount": 1
          }
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      MassiveRestClient client = new MassiveRestClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort());

      List<CandleResponse> candles = client.fetchCandles(
          "EURUSD",
          "C:EURUSD",
          "5m",
          Instant.parse("2026-06-05T00:00:00Z"),
          Instant.parse("2026-06-12T00:00:00Z"));

      assertThat(candles).hasSize(1);
      assertThat(candles.getFirst().timestamp()).isEqualTo(1781222400000L);
      assertThat(candles.getFirst().open()).isEqualByComparingTo("1.15710");
      assertThat(candles.getFirst().high()).isEqualByComparingTo("1.15740");
      assertThat(candles.getFirst().low()).isEqualByComparingTo("1.15690");
      assertThat(candles.getFirst().close()).isEqualByComparingTo("1.15730");
      assertThat(query.get()).isEqualTo("adjusted=true&sort=asc&limit=50000");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchCandlesUsesRecentAvailableForexWindowForShortChartRequests() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    List<String> paths = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/v2/aggs/ticker/C:EURUSD/range/1/minute", exchange -> {
      calls.incrementAndGet();
      paths.add(exchange.getRequestURI().getPath());
      String response = """
          {
            "status": "OK",
            "ticker": "C:EURUSD",
            "results": [
              { "o": 1.15610, "h": 1.15640, "l": 1.15600, "c": 1.15630, "v": 100, "t": 1781222400000 },
              { "o": 1.15630, "h": 1.15690, "l": 1.15620, "c": 1.15680, "v": 125, "t": 1781222460000 }
            ],
            "resultsCount": 2
          }
          """;
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      MassiveRestClient client = new MassiveRestClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort());

      List<CandleResponse> candles = client.fetchCandles(
          "EURUSD",
          "C:EURUSD",
          "1m",
          Instant.ofEpochMilli(1781460000000L),
          Instant.ofEpochMilli(1781460120000L));
      List<CandleResponse> nextCandles = client.fetchCandles(
          "EURUSD",
          "C:EURUSD",
          "1m",
          Instant.ofEpochMilli(1781460001000L),
          Instant.ofEpochMilli(1781460121000L));

      assertThat(calls.get()).isEqualTo(1);
      assertThat(paths.getFirst()).contains("/2026-06-07/2026-06-14");
      assertThat(candles).hasSize(2);
      assertThat(candles.getFirst().close()).isEqualByComparingTo("1.15630");
      assertThat(candles.get(1).close()).isEqualByComparingTo("1.15680");
      assertThat(nextCandles).hasSize(2);
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchIndicativeQuoteIncludesAggregateMarketSummary() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/v2/aggs/ticker/C:EURUSD/range/1/minute", exchange -> {
      byte[] body = """
          {
            "status": "OK",
            "ticker": "C:EURUSD",
            "results": [
              { "o": 1.10000, "h": 1.11000, "l": 1.09000, "c": 1.10500, "v": 100, "t": 1781376000000 },
              { "o": 1.10500, "h": 1.12000, "l": 1.10100, "c": 1.11500, "v": 150, "t": 1781462340000 },
              { "o": 1.11500, "h": 1.13000, "l": 1.11200, "c": 1.12500, "v": 250, "t": 1781462400000 }
            ],
            "resultsCount": 3
          }
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      MassiveRestClient client = new MassiveRestClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort());

      Optional<QuoteResponse> quote = client.fetchIndicativeQuote(
          "EURUSD",
          "C:EURUSD",
          Instant.ofEpochMilli(1781462400000L));

      assertThat(quote).isPresent();
      assertThat(quote.get().mid()).isEqualByComparingTo("1.12500");
      assertThat(quote.get().high24h()).isEqualByComparingTo("1.13000");
      assertThat(quote.get().low24h()).isEqualByComparingTo("1.09000");
      assertThat(quote.get().volume24h()).isEqualByComparingTo("500");
      assertThat(quote.get().changePercent()).isEqualByComparingTo("2.272727");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private ExecutorService daemonExecutor() {
    return Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task);
      thread.setDaemon(true);
      return thread;
    });
  }
}
