package com.fxplatform.market.adapter.okx;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OkxSpotMarketDataProviderTest {

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
  void fetchSymbolsExposesOkxProviderSymbols() {
    OkxSpotMarketDataProvider provider = new OkxSpotMarketDataProvider("http://127.0.0.1:1");

    List<SymbolResponse> symbols = provider.fetchSymbols("CRYPTO", 10);

    assertThat(symbols).extracting(SymbolResponse::symbol)
        .contains("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT");
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
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v5/market/candles", exchange -> writeJson(exchange, """
        {
          "code": "0",
          "data": [
            ["1781462460000", "101", "103", "100", "102", "10"],
            ["1781462400000", "100", "102", "99", "101", "8"]
          ]
        }
        """));
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

      var candles = provider.fetchCandles("BTCUSDT", "BTC-USDT", "1m", Instant.ofEpochMilli(1), Instant.ofEpochMilli(2));
      var orderBook = provider.fetchOrderBook("BTCUSDT", "BTC-USDT");
      var trades = provider.fetchRecentTrades("BTCUSDT", "BTC-USDT", 20);

      assertThat(candles).hasSize(2);
      assertThat(candles.get(0).timestamp()).isEqualTo(1781462400000L);
      assertThat(candles.get(0).open()).isEqualByComparingTo("100");
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
