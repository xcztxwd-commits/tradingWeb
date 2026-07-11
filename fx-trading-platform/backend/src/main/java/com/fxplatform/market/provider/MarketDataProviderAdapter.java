package com.fxplatform.market.provider;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.adapter.MarketDataProvider;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.SpotMarketBundle;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface MarketDataProviderAdapter extends MarketDataProvider {

  String code();

  Set<MarketDataCapability> capabilities();

  boolean configured();

  default boolean supports(MarketDataCapability capability) {
    return capabilities().contains(capability);
  }

  @Override
  default boolean isConfigured() {
    return configured();
  }

  @Override
  default Optional<QuoteResponse> fetchLatestQuote(String symbol, String providerSymbol) {
    return Optional.empty();
  }

  @Override
  default Optional<QuoteResponse> fetchIndicativeQuote(String symbol, String providerSymbol, Instant to) {
    return Optional.empty();
  }

  default Map<String, QuoteResponse> fetchLatestQuotes(Map<String, String> providerSymbolsBySymbol) {
    LinkedHashMap<String, QuoteResponse> quotes = new LinkedHashMap<>();
    providerSymbolsBySymbol.forEach((symbol, providerSymbol) ->
        fetchLatestQuote(symbol, providerSymbol).ifPresent(quote -> quotes.put(symbol, quote)));
    return quotes;
  }

  @Override
  default Map<String, QuoteResponse> fetchMarketSnapshots(String assetClass, int limit) {
    return Map.of();
  }

  @Override
  default List<SymbolResponse> fetchSymbols(String assetClass, int limit) {
    return List.of();
  }

  @Override
  default List<CandleResponse> fetchCandles(String symbol, String providerSymbol, String timeframe, Instant from, Instant to) {
    return List.of();
  }

  default Optional<MarketDepthResponse> fetchOrderBook(String symbol, String providerSymbol) {
    return Optional.empty();
  }

  default List<RecentTradeResponse> fetchRecentTrades(String symbol, String providerSymbol, int limit) {
    return List.of();
  }

  default Optional<SpotMarketBundle> fetchSpotBundle(
      String platformSymbol,
      String providerSymbol,
      CandleRequest candleRequest
  ) {
    return Optional.empty();
  }

  default Optional<PerpetualMarketBundle> fetchPerpetualBundle(
      String platformSymbol,
      String providerSymbol,
      CandleRequest candleRequest
  ) {
    return Optional.empty();
  }
}
