package com.fxplatform.market.adapter.binance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.model.ProductType;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BinanceSpotMarketDataProviderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void configuredPublicTimeoutStopsWholeBundleBeforeDownstreamRequests() throws IOException {
    AtomicInteger downstreamRequests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v3/ticker/24hr", exchange -> {
      java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(250).toNanos());
      writeJson(exchange, """
          {"bidPrice":"1","askPrice":"2","lastPrice":"1.5","closeTime":1783814410000}
          """);
    });
    server.createContext("/api/v3/depth", exchange -> {
      downstreamRequests.incrementAndGet();
      writeJson(exchange, "{}");
    });
    server.createContext("/api/v3/trades", exchange -> {
      downstreamRequests.incrementAndGet();
      writeJson(exchange, "[]");
    });
    server.createContext("/api/v3/klines", exchange -> {
      downstreamRequests.incrementAndGet();
      writeJson(exchange, "[]");
    });
    server.start();
    try {
      var provider = new BinanceSpotMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          HttpClient.newHttpClient(), objectMapper, Clock.systemUTC(),
          Duration.ofSeconds(5), Duration.ofMillis(50));

      var bundle = provider.fetchSpotBundle(
          "BTCUSDT", "BTCUSDT",
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
    Instant observedAt = now.minusSeconds(1);
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v3/ticker/24hr", exchange -> writeJson(exchange, """
        {"symbol":"BTCUSDT","bidPrice":"65000.10","askPrice":"65000.20",
         "lastPrice":"65000.15","priceChangePercent":"1.25","highPrice":"66000",
         "lowPrice":"64000","volume":"1234.5","closeTime":%d}
        """.formatted(observedAt.toEpochMilli())));
    server.createContext("/api/v3/depth", exchange -> writeJson(exchange, """
        {"bids":[["65000.10","1.2"]],"asks":[["65000.20","2.3"]]}
        """));
    server.createContext("/api/v3/trades", exchange -> writeJson(exchange, """
        [{"id":10,"price":"65000.15","qty":"0.3","isBuyerMaker":false,"time":1700000000000}]
        """));
    server.createContext("/api/v3/klines", exchange -> writeJson(exchange, """
        [[1700000000000,"64000","65100","63900","65000","12.4"]]
        """));
    server.start();
    try {
      BinanceSpotMarketDataProvider provider = new BinanceSpotMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          HttpClient.newHttpClient(), objectMapper, Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(5));

      var bundle = provider.fetchSpotBundle(
          "BTCUSDT", "BTCUSDT",
          new com.fxplatform.market.model.CandleRequest("1m", now.minusSeconds(3600), now));

      assertThat(bundle).isPresent();
      assertThat(bundle.get().providerCode()).isEqualTo("binance");
      assertThat(bundle.get().changePercent()).isEqualByComparingTo("1.25");
      assertThat(bundle.get().high24h()).isEqualByComparingTo("66000");
      assertThat(bundle.get().low24h()).isEqualByComparingTo("64000");
      assertThat(bundle.get().volume24h()).isEqualByComparingTo("1234.5");
      assertThat(bundle.get().asOf()).isEqualTo(observedAt);
      assertThat(bundle.get().expiresAt()).isEqualTo(observedAt.plusSeconds(5));
      assertThat(bundle.get().orderBook().providerCode()).isEqualTo("binance");
      assertThat(bundle.get().recentTrades()).allMatch(trade -> observedAt.equals(trade.asOf()));
      assertThat(bundle.get().candles()).allMatch(candle -> observedAt.equals(candle.asOf()));
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchLatestQuoteMapsSpotTickerResponseForSol() throws IOException {
    AtomicReference<String> query = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v3/ticker/24hr", exchange -> {
      query.set(exchange.getRequestURI().getQuery());
      byte[] body = """
          {
            "symbol": "SOLUSDT",
            "bidPrice": "152.12000000",
            "askPrice": "152.13000000",
            "lastPrice": "152.12500000",
            "priceChangePercent": "3.250",
            "highPrice": "155.00000000",
            "lowPrice": "147.50000000",
            "volume": "1823456.78000000",
            "closeTime": 1781462400000
          }
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      BinanceSpotMarketDataProvider provider = new BinanceSpotMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort());

      Optional<QuoteResponse> quote = provider.fetchLatestQuote("SOLUSDT", "SOLUSDT");

      assertThat(quote).isPresent();
      assertThat(quote.get().symbol()).isEqualTo("SOLUSDT");
      assertThat(quote.get().bid()).isEqualByComparingTo("152.12000000");
      assertThat(quote.get().ask()).isEqualByComparingTo("152.13000000");
      assertThat(quote.get().mid()).isEqualByComparingTo("152.12500000");
      assertThat(quote.get().spread()).isEqualByComparingTo("0.01000000");
      assertThat(quote.get().source()).isEqualTo("binance-spot");
      assertThat(quote.get().changePercent()).isEqualByComparingTo("3.250");
      assertThat(quote.get().high24h()).isEqualByComparingTo("155.00000000");
      assertThat(quote.get().low24h()).isEqualByComparingTo("147.50000000");
      assertThat(quote.get().volume24h()).isEqualByComparingTo("1823456.78000000");
      assertThat(quote.get().timestamp()).isEqualTo(1781462400000L);
      assertThat(query.get()).isEqualTo("symbol=SOLUSDT");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchSymbolsExposesMainstreamCryptoExtensionSlots() {
    BinanceSpotMarketDataProvider provider = new BinanceSpotMarketDataProvider("http://127.0.0.1:1");

    List<SymbolResponse> symbols = provider.fetchSymbols("CRYPTO", 10);

    assertThat(symbols).extracting(SymbolResponse::symbol)
        .contains("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT");
    SymbolResponse sol = symbols.stream()
        .filter(symbol -> "SOLUSDT".equals(symbol.symbol()))
        .findFirst()
        .orElseThrow();
    assertThat(sol.provider()).isEqualTo("binance");
    assertThat(sol.providerSymbol()).isEqualTo("SOLUSDT");
    assertThat(sol.assetClass()).isEqualTo("CRYPTO");
    assertThat(sol.tradable()).isTrue();
  }

  @Test
  void fetchLatestQuotesUsesBinanceBatchTickerEndpoint() throws IOException {
    AtomicReference<String> query = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v3/ticker/24hr", exchange -> {
      query.set(exchange.getRequestURI().getQuery());
      byte[] body = """
          [
            {
              "symbol": "BTCUSDT",
              "bidPrice": "65000.10000000",
              "askPrice": "65000.20000000",
              "lastPrice": "65000.15000000",
              "priceChangePercent": "1.250",
              "highPrice": "66000.00000000",
              "lowPrice": "64000.00000000",
              "volume": "1234.56000000",
              "closeTime": 1781462400000
            },
            {
              "symbol": "ETHUSDT",
              "bidPrice": "3400.10000000",
              "askPrice": "3400.20000000",
              "lastPrice": "3400.15000000",
              "priceChangePercent": "-0.500",
              "highPrice": "3500.00000000",
              "lowPrice": "3300.00000000",
              "volume": "2222.00000000",
              "closeTime": 1781462401000
            }
          ]
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      BinanceSpotMarketDataProvider provider = new BinanceSpotMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort());

      Map<String, QuoteResponse> quotes = provider.fetchLatestQuotes(Map.of(
          "BTCUSDT", "BTCUSDT",
          "ETHUSDT", "ETHUSDT"));

      assertThat(quotes).containsOnlyKeys("BTCUSDT", "ETHUSDT");
      assertThat(quotes.get("BTCUSDT").mid()).isEqualByComparingTo("65000.15000000");
      assertThat(quotes.get("ETHUSDT").changePercent()).isEqualByComparingTo("-0.500");
      assertThat(query.get()).contains("symbols=");
      assertThat(query.get()).contains("BTCUSDT");
      assertThat(query.get()).contains("ETHUSDT");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchSymbolsUsesBinanceExchangeInfoSpotUniverseAndKeepsCryptoSpotProductType() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v3/exchangeInfo", exchange -> writeJson(exchange, """
        {
          "symbols": [
            {
              "symbol": "BTCUSDT",
              "status": "TRADING",
              "baseAsset": "BTC",
              "quoteAsset": "USDT",
              "isSpotTradingAllowed": true,
              "isMarginTradingAllowed": true,
              "filters": [
                { "filterType": "PRICE_FILTER", "tickSize": "0.01000000" },
                { "filterType": "LOT_SIZE", "minQty": "0.00001000", "maxQty": "9000.00000000", "stepSize": "0.00001000" },
                { "filterType": "NOTIONAL", "minNotional": "5.00000000" }
              ]
            },
            {
              "symbol": "ETHUSDT",
              "status": "TRADING",
              "baseAsset": "ETH",
              "quoteAsset": "USDT",
              "isSpotTradingAllowed": true,
              "filters": []
            },
            {
              "symbol": "SOLUSDT",
              "status": "TRADING",
              "baseAsset": "SOL",
              "quoteAsset": "USDT",
              "isSpotTradingAllowed": true,
              "filters": []
            },
            {
              "symbol": "XRPUSDT",
              "status": "TRADING",
              "baseAsset": "XRP",
              "quoteAsset": "USDT",
              "isSpotTradingAllowed": true,
              "filters": []
            },
            {
              "symbol": "BCHUSDT",
              "status": "TRADING",
              "baseAsset": "BCH",
              "quoteAsset": "USDT",
              "isSpotTradingAllowed": true,
              "filters": []
            },
            {
              "symbol": "UNIUSDT",
              "status": "TRADING",
              "baseAsset": "UNI",
              "quoteAsset": "USDT",
              "isSpotTradingAllowed": true,
              "filters": []
            },
            {
              "symbol": "JTOUSDT",
              "status": "TRADING",
              "baseAsset": "JTO",
              "quoteAsset": "USDT",
              "isSpotTradingAllowed": true,
              "filters": []
            },
            {
              "symbol": "BNBBTC",
              "status": "TRADING",
              "baseAsset": "BNB",
              "quoteAsset": "BTC",
              "isSpotTradingAllowed": true,
              "filters": []
            },
            {
              "symbol": "OLDUSDT",
              "status": "BREAK",
              "baseAsset": "OLD",
              "quoteAsset": "USDT",
              "isSpotTradingAllowed": true,
              "filters": []
            }
          ]
        }
        """));
    server.start();
    try {
      BinanceSpotMarketDataProvider provider = new BinanceSpotMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort());

      List<SymbolResponse> symbols = provider.fetchSymbols("CRYPTO", 2000);

      assertThat(symbols).extracting(SymbolResponse::symbol)
          .contains("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "BCHUSDT", "UNIUSDT", "JTOUSDT")
          .doesNotContain("BNBBTC", "OLDUSDT");
      assertThat(symbols)
          .filteredOn(symbol -> List.of("BTCUSDT", "BCHUSDT", "UNIUSDT", "JTOUSDT").contains(symbol.symbol()))
          .allSatisfy(symbol -> {
            assertThat(symbol.assetClass()).isEqualTo("CRYPTO");
            assertThat(symbol.productType()).isEqualTo(ProductType.CRYPTO_SPOT);
            assertThat(symbol.provider()).isEqualTo("binance");
            assertThat(symbol.providerSymbol()).isEqualTo(symbol.symbol());
            assertThat(symbol.quoteCurrency()).isEqualTo("USDT");
          });
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchSymbolsMapsBinanceAssetLogosToIconUrls() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/bapi/asset/v2/public/asset/asset/get-all-asset", exchange -> {
      byte[] body = """
          {
            "code": "000000",
            "success": true,
            "data": [
              {
                "assetCode": "SOL",
                "logoUrl": "https://bin.bnbstatic.com/image/admin_mgs_image_upload/sol-small.png",
                "fullLogoUrl": "https://bin.bnbstatic.com/image/admin_mgs_image_upload/sol.png"
              },
              {
                "assetCode": "BTC",
                "logoUrl": "https://bin.bnbstatic.com/image/admin_mgs_image_upload/btc.png"
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
      BinanceSpotMarketDataProvider provider = new BinanceSpotMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort());

      List<SymbolResponse> symbols = provider.fetchSymbols("CRYPTO", 10);

      SymbolResponse sol = symbols.stream()
          .filter(symbol -> "SOLUSDT".equals(symbol.symbol()))
          .findFirst()
          .orElseThrow();
      assertThat(sol.iconUrl()).isEqualTo("https://bin.bnbstatic.com/image/admin_mgs_image_upload/sol.png");
      SymbolResponse eth = symbols.stream()
          .filter(symbol -> "ETHUSDT".equals(symbol.symbol()))
          .findFirst()
          .orElseThrow();
      assertThat(eth.iconUrl()).isNull();
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void fetchSymbolsMapsBinanceTradingRulesIntoProviderMetadata() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    ExecutorService executor = daemonExecutor();
    server.setExecutor(executor);
    server.createContext("/api/v3/exchangeInfo", exchange -> {
      byte[] body = """
          {
            "symbols": [
              {
                "symbol": "BTCUSDT",
                "status": "TRADING",
                "baseAsset": "BTC",
                "quoteAsset": "USDT",
                "isSpotTradingAllowed": true,
                "isMarginTradingAllowed": true,
                "permissions": ["SPOT", "MARGIN"],
                "filters": [
                  { "filterType": "PRICE_FILTER", "minPrice": "0.01000000", "maxPrice": "1000000.00000000", "tickSize": "0.01000000" },
                  { "filterType": "LOT_SIZE", "minQty": "0.00001000", "maxQty": "9000.00000000", "stepSize": "0.00001000" },
                  { "filterType": "MIN_NOTIONAL", "minNotional": "5.00000000", "applyToMarket": true, "avgPriceMins": 5 }
                ]
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
      BinanceSpotMarketDataProvider provider = new BinanceSpotMarketDataProvider(
          "http://127.0.0.1:" + server.getAddress().getPort());

      SymbolResponse btc = provider.fetchSymbols("CRYPTO", 10).stream()
          .filter(symbol -> "BTCUSDT".equals(symbol.symbol()))
          .findFirst()
          .orElseThrow();

      var metadata = objectMapper.readTree(btc.providerMetadataJson());
      assertThat(metadata.path("rules").path("tickSize").asText()).isEqualTo("0.01000000");
      assertThat(metadata.path("rules").path("stepSize").asText()).isEqualTo("0.00001000");
      assertThat(metadata.path("rules").path("minLot").asText()).isEqualTo("0.00001000");
      assertThat(metadata.path("rules").path("minNotional").asText()).isEqualTo("5.00000000");
      assertThat(metadata.path("margin").path("spotTradingAllowed").asBoolean()).isTrue();
      assertThat(metadata.path("margin").path("marginTradingAllowed").asBoolean()).isTrue();
      assertThat(metadata.path("margin").path("leverageSource").asText()).contains("spot exchangeInfo");
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
