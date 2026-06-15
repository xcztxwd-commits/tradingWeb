package com.fxplatform.market.adapter;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.provider.MarketDataRouter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class CompositeMarketDataProvider implements MarketDataProvider {

  private final MarketDataRouter marketDataRouter;

  public CompositeMarketDataProvider(MarketDataRouter marketDataRouter) {
    this.marketDataRouter = marketDataRouter;
  }

  @Override
  public boolean isConfigured() {
    return true;
  }

  @Override
  public Optional<QuoteResponse> fetchLatestQuote(String symbol, String providerSymbol) {
    return Optional.of(marketDataRouter.latestQuote(SymbolNormalizer.normalize(symbol)));
  }

  @Override
  public Optional<QuoteResponse> fetchIndicativeQuote(String symbol, String providerSymbol, Instant to) {
    return fetchLatestQuote(symbol, providerSymbol);
  }

  @Override
  public Map<String, QuoteResponse> fetchMarketSnapshots(String assetClass, int limit) {
    return marketDataRouter.snapshots(null, assetClass, limit);
  }

  @Override
  public List<SymbolResponse> fetchSymbols(String assetClass, int limit) {
    return List.of();
  }

  @Override
  public List<CandleResponse> fetchCandles(String symbol, String providerSymbol, String timeframe, Instant from, Instant to) {
    return marketDataRouter.candles(SymbolNormalizer.normalize(symbol), timeframe, from, to);
  }
}
