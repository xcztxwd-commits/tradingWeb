package com.fxplatform.market.adapter.binance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BinanceMarketProxyClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void fetchMarketOverviewSourceReturnsProductsAndFearGreed() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/bapi/asset/v2/public/asset-service/product/get-products", exchange -> writeJson(exchange, """
        {
          "code": "000000",
          "data": [
            { "s": "BTCUSDT", "st": "TRADING", "b": "BTC", "q": "USDT", "c": "65000" }
          ]
        }
        """));
    server.createContext("/fng", exchange -> writeJson(exchange, """
        {
          "data": [
            { "value": "29", "value_classification": "Fear", "timestamp": "1781510400" }
          ]
        }
        """));
    server.start();
    try {
      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
      BinanceMarketProxyClient client = new BinanceMarketProxyClient(
          objectMapper,
          baseUrl,
          baseUrl,
          baseUrl + "/fng");

      BinanceMarketOverviewSourceResponse response = client.fetchMarketOverviewSource();

      assertThat(response.products()).hasSize(1);
      assertThat(response.products().getFirst().path("s").asText()).isEqualTo("BTCUSDT");
      assertThat(response.fearGreed()).isNotNull();
      assertThat(response.fearGreed().value()).isEqualTo(29);
      assertThat(response.fearGreed().label()).isEqualTo("Fear");
      assertThat(response.fearGreed().source()).isEqualTo("alternative.me");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchFuturesDashboardSourceUsesConfiguredFuturesEndpoints() throws IOException {
    AtomicReference<String> tickerQuery = new AtomicReference<>();
    AtomicReference<String> openInterestQuery = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/fapi/v1/ticker/24hr", exchange -> {
      tickerQuery.set(exchange.getRequestURI().getQuery());
      writeJson(exchange, """
          { "symbol": "BTCUSDT", "lastPrice": "65000", "priceChangePercent": "1.2" }
          """);
    });
    server.createContext("/futures/data/openInterestHist", exchange -> {
      openInterestQuery.set(exchange.getRequestURI().getQuery());
      writeJson(exchange, """
          [{ "sumOpenInterest": "10", "timestamp": 1781510400000 }]
          """);
    });
    server.createContext("/futures/data/topLongShortAccountRatio", exchange -> writeJson(exchange, """
        [{ "longShortRatio": "1.1", "timestamp": 1781510400000 }]
        """));
    server.createContext("/futures/data/topLongShortPositionRatio", exchange -> writeJson(exchange, """
        [{ "longShortRatio": "1.2", "timestamp": 1781510400000 }]
        """));
    server.createContext("/futures/data/globalLongShortAccountRatio", exchange -> writeJson(exchange, """
        [{ "longShortRatio": "1.3", "timestamp": 1781510400000 }]
        """));
    server.createContext("/futures/data/takerlongshortRatio", exchange -> writeJson(exchange, """
        [{ "buyVol": "2", "sellVol": "1", "timestamp": 1781510400000 }]
        """));
    server.createContext("/futures/data/basis", exchange -> writeJson(exchange, """
        [{ "basis": "-1", "timestamp": 1781510400000 }]
        """));
    server.createContext("/fapi/v1/fundingRate", exchange -> writeJson(exchange, """
        [{ "fundingRate": "0.0001", "fundingTime": 1781510400000 }]
        """));
    server.start();
    try {
      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
      BinanceMarketProxyClient client = new BinanceMarketProxyClient(
          objectMapper,
          baseUrl,
          baseUrl,
          baseUrl + "/fng");

      BinanceFuturesDashboardSourceResponse response = client.fetchFuturesDashboardSource("btc-usdt", "5m&limit=999");

      assertThat(tickerQuery.get()).isEqualTo("symbol=BTCUSDT");
      assertThat(openInterestQuery.get()).isEqualTo("symbol=BTCUSDT&period=5m&limit=14");
      assertThat(response.ticker().path("symbol").asText()).isEqualTo("BTCUSDT");
      assertThat(response.openInterest()).hasSize(1);
      assertThat(response.topAccountRatio()).hasSize(1);
      assertThat(response.topPositionRatio()).hasSize(1);
      assertThat(response.globalLongShortRatio()).hasSize(1);
      assertThat(response.takerBuySell()).hasSize(1);
      assertThat(response.basis()).hasSize(1);
      assertThat(response.fundingRates()).hasSize(1);
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

  private void writeJson(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }
}
