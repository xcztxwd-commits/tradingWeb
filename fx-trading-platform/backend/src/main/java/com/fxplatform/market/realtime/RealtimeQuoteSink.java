package com.fxplatform.market.realtime;

import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.repository.RealtimeCandleRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.market.websocket.MarketWsPublisher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RealtimeQuoteSink {

  private static final String BOOK_TICKER_SOURCE = "binance-ws-bookTicker";
  private static final String KLINE_SOURCE = "binance-ws-kline";
  private static final String DEMO_SOURCE = "demo-realtime";

  private final QuoteService quoteService;
  private final RealtimeMarketSnapshotCache snapshotCache;
  private final RealtimeCandleRepository candleRepository;
  private final MarketWsPublisher marketWsPublisher;
  private final MarketTestControlService testControlService;
  private final Clock clock;

  private final AtomicLong processedCount = new AtomicLong();
  private final AtomicLong cacheFailureCount = new AtomicLong();
  private final AtomicLong candleFailureCount = new AtomicLong();
  private final AtomicLong publishFailureCount = new AtomicLong();
  private final AtomicLong lastEventTime = new AtomicLong(Long.MIN_VALUE);

  @Autowired
  public RealtimeQuoteSink(
      QuoteService quoteService,
      RealtimeMarketSnapshotCache snapshotCache,
      RealtimeCandleRepository candleRepository,
      MarketWsPublisher marketWsPublisher,
      MarketTestControlService testControlService
  ) {
    this(quoteService, snapshotCache, candleRepository, marketWsPublisher, testControlService, Clock.systemUTC());
  }

  RealtimeQuoteSink(
      QuoteService quoteService,
      RealtimeMarketSnapshotCache snapshotCache,
      RealtimeCandleRepository candleRepository,
      MarketWsPublisher marketWsPublisher,
      MarketTestControlService testControlService,
      Clock clock
  ) {
    this.quoteService = quoteService;
    this.snapshotCache = snapshotCache;
    this.candleRepository = candleRepository;
    this.marketWsPublisher = marketWsPublisher;
    this.testControlService = testControlService;
    this.clock = clock;
  }

  public void process(RealtimeMarketEvent event) {
    process(event, BOOK_TICKER_SOURCE, KLINE_SOURCE);
  }

  public void acceptDemo(RealtimeMarketEvent event) {
    process(event, DEMO_SOURCE, DEMO_SOURCE);
  }

  public void acceptTestControl(QuoteResponse response) {
    publishAndCache(response);
  }

  private void process(RealtimeMarketEvent event, String quoteSource, String candleSource) {
    processedCount.incrementAndGet();
    lastEventTime.accumulateAndGet(event.eventTime(), Math::max);
    switch (event) {
      case RealtimeMarketEvent.Quote quote -> processQuote(quote, quoteSource);
      case RealtimeMarketEvent.TickerStats tickerStats -> snapshotCache.putTickerStats(tickerStats);
      case RealtimeMarketEvent.OrderBook orderBook -> processOrderBook(orderBook);
      case RealtimeMarketEvent.Trade trade -> processTrade(trade);
      case RealtimeMarketEvent.Candle candle -> processCandle(candle, candleSource);
      case RealtimeMarketEvent.ServerShutdown ignored -> {
      }
    }
  }

  public long processedCount() {
    return processedCount.get();
  }

  public long cacheFailureCount() {
    return cacheFailureCount.get();
  }

  public long candleFailureCount() {
    return candleFailureCount.get();
  }

  public long publishFailureCount() {
    return publishFailureCount.get();
  }

  public OptionalLong lastEventTime() {
    long value = lastEventTime.get();
    return value == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(value);
  }

  private void processQuote(RealtimeMarketEvent.Quote quote, String source) {
    String symbol = SymbolNormalizer.normalize(quote.symbol());
    Optional<QuoteResponse> override = testControlService.overrideQuote(symbol);
    if (override != null && override.isPresent()) {
      publishAndCache(override.get());
      return;
    }
    QuoteResponse response = quoteResponse(quote, source);
    publishAndCache(response);
  }

  private void publishAndCache(QuoteResponse response) {
    try {
      quoteService.cache(response);
    } catch (RuntimeException ex) {
      cacheFailureCount.incrementAndGet();
    }
    publishQuote(response);
  }

  private QuoteResponse quoteResponse(RealtimeMarketEvent.Quote quote, String source) {
    String symbol = SymbolNormalizer.normalize(quote.symbol());
    BigDecimal mid = quote.bid().add(quote.ask()).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
    BigDecimal spread = quote.ask().subtract(quote.bid());
    Optional<RealtimeMarketEvent.TickerStats> stats = snapshotCache.tickerStats(symbol);
    return new QuoteResponse(
        "quote",
        symbol,
        quote.bid(),
        quote.ask(),
        mid,
        spread,
        source,
        quoteTimestamp(quote),
        stats.map(RealtimeMarketEvent.TickerStats::changePercent).orElse(null),
        stats.map(RealtimeMarketEvent.TickerStats::high24h).orElse(null),
        stats.map(RealtimeMarketEvent.TickerStats::low24h).orElse(null),
        stats.map(RealtimeMarketEvent.TickerStats::volume24h).orElse(null));
  }

  private long quoteTimestamp(RealtimeMarketEvent.Quote quote) {
    return quote.eventTime() > 0 ? quote.eventTime() : clock.instant().toEpochMilli();
  }

  private void processOrderBook(RealtimeMarketEvent.OrderBook orderBook) {
    MarketDepthResponse response = new MarketDepthResponse(
        SymbolNormalizer.normalize(orderBook.symbol()),
        orderBook.eventTime(),
        orderBook.bids(),
        orderBook.asks());
    try {
      snapshotCache.putOrderBook(response);
    } catch (RuntimeException ex) {
      cacheFailureCount.incrementAndGet();
    }
    try {
      marketWsPublisher.publishOrderBook(response);
    } catch (RuntimeException ex) {
      publishFailureCount.incrementAndGet();
    }
  }

  private void processTrade(RealtimeMarketEvent.Trade trade) {
    String symbol = SymbolNormalizer.normalize(trade.symbol());
    RecentTradeResponse response = new RecentTradeResponse(
        String.valueOf(trade.aggregateTradeId()),
        symbol,
        trade.price(),
        trade.amount(),
        trade.side(),
        trade.eventTime());
    List<RecentTradeResponse> trades;
    try {
      trades = snapshotCache.addTrade(response);
    } catch (RuntimeException ex) {
      cacheFailureCount.incrementAndGet();
      trades = List.of(response);
    }
    try {
      marketWsPublisher.publishRecentTrades(symbol, trades);
    } catch (RuntimeException ex) {
      publishFailureCount.incrementAndGet();
    }
  }

  private void processCandle(RealtimeMarketEvent.Candle candle, String source) {
    try {
      candleRepository.upsertCandle(
          SymbolNormalizer.normalize(candle.symbol()),
          candle.interval(),
          Instant.ofEpochMilli(candle.openTime()),
          candle.open(),
          candle.high(),
          candle.low(),
          candle.close(),
          candle.volume(),
          source);
    } catch (RuntimeException ex) {
      candleFailureCount.incrementAndGet();
    }
  }

  private void publishQuote(QuoteResponse response) {
    try {
      marketWsPublisher.publishQuote(response);
    } catch (RuntimeException ex) {
      publishFailureCount.incrementAndGet();
    }
  }
}
