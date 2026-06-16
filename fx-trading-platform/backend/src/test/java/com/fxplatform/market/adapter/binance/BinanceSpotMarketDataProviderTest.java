package com.fxplatform.market.adapter.binance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BinanceSpotMarketDataProviderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

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
}
