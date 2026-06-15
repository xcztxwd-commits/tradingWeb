package com.fxplatform.market.provider;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarketDataRouter {

  private final ProviderResolver providerResolver;

  public QuoteResponse latestQuote(String symbol) {
    ProviderResolution resolution = providerResolver.resolve(symbol, MarketDataCapability.QUOTE);
    if (!Boolean.TRUE.equals(resolution.symbol().getQuoteEnabled())) {
      throw new BusinessException("SYMBOL_QUOTE_DISABLED", "Symbol quote is disabled");
    }
    return resolution.adapter()
        .fetchLatestQuote(resolution.symbol().getSymbol(), resolution.providerSymbol())
        .or(() -> resolution.adapter().fetchIndicativeQuote(resolution.symbol().getSymbol(), resolution.providerSymbol(), Instant.now()))
        .orElseThrow(() -> new BusinessException("MARKET_PROVIDER_UNAVAILABLE", "Market data provider unavailable"));
  }

  public List<CandleResponse> candles(String symbol, String timeframe, Instant from, Instant to) {
    ProviderResolution resolution = providerResolver.resolve(symbol, MarketDataCapability.CANDLES);
    if (!Boolean.TRUE.equals(resolution.symbol().getChartEnabled())) {
      throw new BusinessException("SYMBOL_CHART_DISABLED", "Symbol chart is disabled");
    }
    return resolution.adapter().fetchCandles(
        resolution.symbol().getSymbol(),
        resolution.providerSymbol(),
        timeframe,
        from,
        to);
  }

  public MarketDepthResponse orderBook(String symbol) {
    ProviderResolution resolution = providerResolver.resolve(symbol, MarketDataCapability.ORDER_BOOK);
    if (!Boolean.TRUE.equals(resolution.symbol().getOrderBookEnabled())) {
      throw new BusinessException("SYMBOL_ORDER_BOOK_DISABLED", "Symbol order book is disabled");
    }
    return resolution.adapter()
        .fetchOrderBook(resolution.symbol().getSymbol(), resolution.providerSymbol())
        .orElseThrow(() -> new BusinessException("MARKET_PROVIDER_UNAVAILABLE", "Market data provider unavailable"));
  }

  public List<RecentTradeResponse> recentTrades(String symbol, int limit) {
    ProviderResolution resolution = providerResolver.resolve(symbol, MarketDataCapability.TRADES);
    return resolution.adapter().fetchRecentTrades(resolution.symbol().getSymbol(), resolution.providerSymbol(), limit);
  }

  public Map<String, QuoteResponse> snapshots(String providerCode, String assetClass, int limit) {
    return Map.of();
  }
}
