package com.fxplatform.market.adapter;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.SymbolResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Boundary for pluggable market data providers.
 */
public interface MarketDataProvider {

  boolean isConfigured();

  Optional<QuoteResponse> fetchLatestQuote(String symbol, String providerSymbol);

  Optional<QuoteResponse> fetchIndicativeQuote(String symbol, String providerSymbol, Instant to);

  default Map<String, QuoteResponse> fetchLatestQuotes(Map<String, String> providerSymbolsBySymbol) {
    if (providerSymbolsBySymbol == null || providerSymbolsBySymbol.isEmpty()) {
      return Map.of();
    }
    Map<String, QuoteResponse> quotes = new LinkedHashMap<>();
    providerSymbolsBySymbol.forEach((symbol, providerSymbol) ->
        fetchLatestQuote(symbol, providerSymbol).ifPresent(quote -> quotes.put(symbol, quote)));
    return Map.copyOf(quotes);
  }

  default Map<String, QuoteResponse> fetchMarketSnapshots(String assetClass, int limit) {
    return Map.of();
  }

  List<SymbolResponse> fetchSymbols(String assetClass, int limit);

  List<CandleResponse> fetchCandles(String symbol, String providerSymbol, String timeframe, Instant from, Instant to);
}
