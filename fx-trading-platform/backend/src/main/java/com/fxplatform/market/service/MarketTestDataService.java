package com.fxplatform.market.service;
import cn.hutool.core.date.DateUtil;

import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.realtime.DemoRealtimeEventFactory;
import com.fxplatform.market.realtime.RealtimeQuoteSink;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * MarketTestDataService 是行情模块的业务服务。
 */
@Service
@RequiredArgsConstructor
public class MarketTestDataService {

  private static final List<String> REALTIME_TIMEFRAMES = List.of("1s", "5m", "15m", "1h");
  private static final int MAX_RECENT_TRADES = 100;

  private final SymbolRepository symbolRepository;
  private final RealtimeQuoteSink realtimeQuoteSink;
  private final DemoMarketDataGenerator generator = new DemoMarketDataGenerator();
  private final DemoRealtimeEventFactory eventFactory = new DemoRealtimeEventFactory(generator);
  private final AtomicLong tick = new AtomicLong();
  private final Map<String, MarketDepthResponse> depthBySymbol = new ConcurrentHashMap<>();
  private final Map<String, ArrayDeque<RecentTradeResponse>> tradesBySymbol = new ConcurrentHashMap<>();

  @Value("${market.test-data.enabled:false}")
  private boolean enabled;

  @Value("${market.test-data.symbols:}")
  private String symbols;

  /**
   * 按调度配置执行 publishRealtimeTestData 定时任务。
   */
  public void publishRealtimeTestData() {
    if (!enabled) {
      return;
    }

    long nextTick = tick.incrementAndGet();
    Instant now = DateUtil.date().toInstant();
    Set<String> allowedSymbols = MarketSymbolFilter.normalizeSymbols(symbols);
    for (SymbolEntity symbol : symbolRepository.findByEnabledTrueOrderBySymbolAsc()) {
      if (!MarketSymbolFilter.allows(allowedSymbols, symbol.getSymbol())) {
        continue;
      }
      publishSymbolTick(symbol.getSymbol(), nextTick, now);
    }
  }

  public MarketDepthResponse orderBook(String symbol) {
    return depthBySymbol.computeIfAbsent(SymbolNormalizer.normalize(symbol), this::createInitialDepth);
  }

  public List<RecentTradeResponse> recentTrades(String symbol, int limit) {
    ArrayDeque<RecentTradeResponse> trades = tradesBySymbol.computeIfAbsent(SymbolNormalizer.normalize(symbol), this::createInitialTrades);
    return trades.stream().limit(Math.min(Math.max(limit, 1), MAX_RECENT_TRADES)).toList();
  }

  private void publishSymbolTick(String symbol, long nextTick, Instant now) {
    String normalizedSymbol = SymbolNormalizer.normalize(symbol);
    QuoteResponse quote = generator.quote(normalizedSymbol, nextTick, now);
    MarketDepthResponse depth = generator.depth(normalizedSymbol, quote);
    RecentTradeResponse trade = generator.trade(normalizedSymbol, nextTick, quote);

    depthBySymbol.put(normalizedSymbol, depth);
    addTrade(normalizedSymbol, trade);
    eventFactory.events(quote, depth, trade, REALTIME_TIMEFRAMES, nextTick)
        .forEach(realtimeQuoteSink::acceptDemo);
  }

  private MarketDepthResponse createInitialDepth(String symbol) {
    QuoteResponse quote = generator.quote(symbol, tick.get(), DateUtil.date().toInstant());
    return generator.depth(symbol, quote);
  }

  private ArrayDeque<RecentTradeResponse> createInitialTrades(String symbol) {
    QuoteResponse quote = generator.quote(symbol, tick.get(), DateUtil.date().toInstant());
    ArrayDeque<RecentTradeResponse> trades = new ArrayDeque<>();
    trades.addFirst(generator.trade(symbol, tick.get(), quote));
    return trades;
  }

  private List<RecentTradeResponse> addTrade(String symbol, RecentTradeResponse trade) {
    ArrayDeque<RecentTradeResponse> trades = tradesBySymbol.computeIfAbsent(symbol, ignored -> new ArrayDeque<>());
    synchronized (trades) {
      trades.addFirst(trade);
      while (trades.size() > MAX_RECENT_TRADES) {
        trades.removeLast();
      }
      return trades.stream().toList();
    }
  }

}
