package com.fxplatform.market.realtime;

import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class RealtimeMarketSnapshotCache {

  private static final int MAX_RECENT_TRADES = 100;

  private final ConcurrentHashMap<String, RealtimeMarketEvent.TickerStats> tickerStatsBySymbol = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, MarketDepthResponse> orderBookBySymbol = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ArrayDeque<RecentTradeResponse>> tradesBySymbol = new ConcurrentHashMap<>();

  public Optional<RealtimeMarketEvent.TickerStats> tickerStats(String symbol) {
    return Optional.ofNullable(tickerStatsBySymbol.get(SymbolNormalizer.normalize(symbol)));
  }

  public void putTickerStats(RealtimeMarketEvent.TickerStats stats) {
    tickerStatsBySymbol.put(SymbolNormalizer.normalize(stats.symbol()), stats);
  }

  public Optional<MarketDepthResponse> orderBook(String symbol) {
    return Optional.ofNullable(orderBookBySymbol.get(SymbolNormalizer.normalize(symbol)));
  }

  public void putOrderBook(MarketDepthResponse depth) {
    orderBookBySymbol.put(SymbolNormalizer.normalize(depth.symbol()), depth);
  }

  public List<RecentTradeResponse> recentTrades(String symbol, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    ArrayDeque<RecentTradeResponse> trades = tradesBySymbol.get(SymbolNormalizer.normalize(symbol));
    if (trades == null) {
      return List.of();
    }
    synchronized (trades) {
      return trades.stream().limit(limit).toList();
    }
  }

  public List<RecentTradeResponse> addTrade(RecentTradeResponse trade) {
    ArrayDeque<RecentTradeResponse> trades = tradesBySymbol.computeIfAbsent(
        SymbolNormalizer.normalize(trade.symbol()),
        ignored -> new ArrayDeque<>());
    synchronized (trades) {
      trades.addFirst(trade);
      while (trades.size() > MAX_RECENT_TRADES) {
        trades.removeLast();
      }
      return new ArrayList<>(trades);
    }
  }
}
