package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.realtime.RealtimeMarketEvent.Candle;
import com.fxplatform.market.realtime.RealtimeMarketEvent.OrderBook;
import com.fxplatform.market.realtime.RealtimeMarketEvent.Quote;
import com.fxplatform.market.realtime.RealtimeMarketEvent.TickerStats;
import com.fxplatform.market.realtime.RealtimeMarketEvent.Trade;
import com.fxplatform.market.repository.RealtimeCandleRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.market.websocket.MarketWsPublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RealtimeQuoteSinkTest {

  private static final BigDecimal BID = new BigDecimal("100.00");
  private static final BigDecimal ASK = new BigDecimal("101.00");

  @Mock
  private QuoteService quoteService;

  @Mock
  private RealtimeCandleRepository candleRepository;

  @Mock
  private MarketWsPublisher marketWsPublisher;

  @Mock
  private MarketTestControlService testControlService;

  private final RealtimeMarketSnapshotCache snapshotCache = new RealtimeMarketSnapshotCache();

  @Test
  void quoteWritesCacheAndPublishesQuote() {
    RealtimeQuoteSink sink = sink();

    sink.process(new Quote("btc-usdt", 1000L, 10L, BID, ASK));

    ArgumentCaptor<QuoteResponse> quoteCaptor = ArgumentCaptor.forClass(QuoteResponse.class);
    verify(quoteService).cache(quoteCaptor.capture());
    verify(marketWsPublisher).publishQuote(quoteCaptor.getValue());
    QuoteResponse quote = quoteCaptor.getValue();
    assertThat(quote.symbol()).isEqualTo("BTCUSDT");
    assertThat(quote.mid()).isEqualByComparingTo(new BigDecimal("100.5000000000"));
    assertThat(quote.spread()).isEqualByComparingTo(new BigDecimal("1.00"));
    assertThat(quote.source()).isEqualTo("binance-ws-bookTicker");
    assertThat(quote.timestamp()).isEqualTo(1000L);
    assertThat(sink.processedCount()).isEqualTo(1L);
    assertThat(sink.lastEventTime()).hasValue(1000L);
  }

  @Test
  void bookTickerWithoutEventTimeUsesReceiveTimeForFreshQuoteTimestamp() {
    Clock fixedClock = Clock.fixed(Instant.parse("2026-06-17T12:00:00Z"), ZoneOffset.UTC);
    RealtimeQuoteSink sink = sink(fixedClock);

    sink.process(new Quote("BTCUSDT", 0L, 10L, BID, ASK));

    ArgumentCaptor<QuoteResponse> quoteCaptor = ArgumentCaptor.forClass(QuoteResponse.class);
    verify(quoteService).cache(quoteCaptor.capture());
    assertThat(quoteCaptor.getValue().source()).isEqualTo("binance-ws-bookTicker");
    assertThat(quoteCaptor.getValue().timestamp()).isEqualTo(fixedClock.instant().toEpochMilli());
  }

  @Test
  void tickerStatsUpdateCacheWithoutPublishingByThemselves() {
    RealtimeQuoteSink sink = sink();

    sink.process(stats());

    assertThat(snapshotCache.tickerStats("BTCUSDT")).contains(stats());
    verifyNoInteractions(quoteService, marketWsPublisher, candleRepository);
  }

  @Test
  void quoteAfterTickerStatsIncludesTwentyFourHourFields() {
    RealtimeQuoteSink sink = sink();
    sink.process(stats());

    sink.process(new Quote("BTCUSDT", 2000L, 11L, BID, ASK));

    ArgumentCaptor<QuoteResponse> quoteCaptor = ArgumentCaptor.forClass(QuoteResponse.class);
    verify(quoteService).cache(quoteCaptor.capture());
    QuoteResponse quote = quoteCaptor.getValue();
    assertThat(quote.changePercent()).isEqualByComparingTo(new BigDecimal("1.25"));
    assertThat(quote.high24h()).isEqualByComparingTo(new BigDecimal("110.00"));
    assertThat(quote.low24h()).isEqualByComparingTo(new BigDecimal("90.00"));
    assertThat(quote.volume24h()).isEqualByComparingTo(new BigDecimal("1000.00"));
  }

  @Test
  void orderBookStoresAndPublishesDepth() {
    RealtimeQuoteSink sink = sink();
    OrderBook orderBook = new OrderBook(
        "btc-usdt",
        1000L,
        10L,
        List.of(new MarketDepthLevelResponse(BID, BigDecimal.ONE)),
        List.of(new MarketDepthLevelResponse(ASK, BigDecimal.TWO)));

    sink.process(orderBook);

    ArgumentCaptor<MarketDepthResponse> depthCaptor = ArgumentCaptor.forClass(MarketDepthResponse.class);
    verify(marketWsPublisher).publishOrderBook(depthCaptor.capture());
    assertThat(depthCaptor.getValue().symbol()).isEqualTo("BTCUSDT");
    assertThat(snapshotCache.orderBook("BTCUSDT")).contains(depthCaptor.getValue());
  }

  @Test
  void tradeStoresAndPublishesRecentTrades() {
    RealtimeQuoteSink sink = sink();

    sink.process(new Trade("btc-usdt", 1000L, 42L, BID, new BigDecimal("0.25"), "BUY"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<RecentTradeResponse>> tradesCaptor = ArgumentCaptor.forClass(List.class);
    verify(marketWsPublisher).publishRecentTrades(org.mockito.ArgumentMatchers.eq("BTCUSDT"), tradesCaptor.capture());
    assertThat(tradesCaptor.getValue()).hasSize(1);
    assertThat(tradesCaptor.getValue().getFirst().id()).isEqualTo("42");
    assertThat(tradesCaptor.getValue().getFirst().side()).isEqualTo("BUY");
  }

  @Test
  void candleWritesFullOhlcvUpsert() {
    RealtimeQuoteSink sink = sink();
    Candle candle = new Candle(
        "btc-usdt",
        2000L,
        "1m",
        1000L,
        59999L,
        10L,
        new BigDecimal("1.00"),
        new BigDecimal("2.00"),
        new BigDecimal("0.50"),
        new BigDecimal("1.50"),
        new BigDecimal("12.30"),
        false);

    sink.process(candle);

    verify(candleRepository).upsertCandle(
        "BTCUSDT",
        "1m",
        Instant.ofEpochMilli(1000L),
        new BigDecimal("1.00"),
        new BigDecimal("2.00"),
        new BigDecimal("0.50"),
        new BigDecimal("1.50"),
        new BigDecimal("12.30"),
        "binance-ws-kline");
  }

  @Test
  void demoEventsUseDemoSourceWhileSharingSinkBranches() {
    RealtimeQuoteSink sink = sink();

    sink.acceptDemo(new Quote("btc-usdt", 1000L, 10L, BID, ASK));
    sink.acceptDemo(new Candle("btc-usdt", 2000L, "1s", 1000L, 1999L, 10L,
        BigDecimal.ONE, BigDecimal.TWO, BigDecimal.ONE, BigDecimal.TWO, BigDecimal.TEN, false));

    ArgumentCaptor<QuoteResponse> quoteCaptor = ArgumentCaptor.forClass(QuoteResponse.class);
    verify(quoteService).cache(quoteCaptor.capture());
    assertThat(quoteCaptor.getValue().source()).isEqualTo("demo-realtime");
    verify(candleRepository).upsertCandle(
        "BTCUSDT",
        "1s",
        Instant.ofEpochMilli(1000L),
        BigDecimal.ONE,
        BigDecimal.TWO,
        BigDecimal.ONE,
        BigDecimal.TWO,
        BigDecimal.TEN,
        "demo-realtime");
  }

  @Test
  void testControlOverrideSuppressesBinanceQuoteUntilExpired() {
    RealtimeQuoteSink sink = sink();
    QuoteResponse override = new QuoteResponse(
        "quote",
        "BTCUSDT",
        new BigDecimal("120.00"),
        new BigDecimal("122.00"),
        new BigDecimal("121.0000000000"),
        new BigDecimal("2.00"),
        "test-control",
        1000L);
    when(testControlService.overrideQuote("BTCUSDT"))
        .thenReturn(Optional.of(override))
        .thenReturn(Optional.empty());

    sink.process(new Quote("btc-usdt", 1000L, 10L, BID, ASK));
    sink.process(new Quote("btc-usdt", 2000L, 11L, BID, ASK));

    ArgumentCaptor<QuoteResponse> quoteCaptor = ArgumentCaptor.forClass(QuoteResponse.class);
    verify(quoteService, org.mockito.Mockito.times(2)).cache(quoteCaptor.capture());
    assertThat(quoteCaptor.getAllValues()).extracting(QuoteResponse::source)
        .containsExactly("test-control", "binance-ws-bookTicker");
    assertThat(quoteCaptor.getAllValues().getFirst()).isSameAs(override);
  }

  @Test
  void cacheFailureDoesNotPreventQuotePublish() {
    RealtimeQuoteSink sink = sink();
    doThrow(new RuntimeException("redis down")).when(quoteService).cache(any());

    sink.process(new Quote("BTCUSDT", 1000L, 10L, BID, ASK));

    verify(marketWsPublisher).publishQuote(any());
    assertThat(sink.cacheFailureCount()).isEqualTo(1L);
    assertThat(sink.publishFailureCount()).isZero();
  }

  @Test
  void candleFailureDoesNotPreventLaterQuotePublish() {
    RealtimeQuoteSink sink = sink();
    doThrow(new RuntimeException("db down")).when(candleRepository).upsertCandle(
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any());

    sink.process(new Candle("BTCUSDT", 2000L, "1m", 1000L, 59999L, 10L,
        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false));
    sink.process(new Quote("BTCUSDT", 3000L, 11L, BID, ASK));

    verify(marketWsPublisher).publishQuote(any());
    assertThat(sink.candleFailureCount()).isEqualTo(1L);
  }

  @Test
  void publishFailureIsCounted() {
    RealtimeQuoteSink sink = sink();
    doThrow(new RuntimeException("broker down")).when(marketWsPublisher).publishQuote(any());

    sink.process(new Quote("BTCUSDT", 1000L, 10L, BID, ASK));

    assertThat(sink.publishFailureCount()).isEqualTo(1L);
    verify(quoteService).cache(any());
  }

  private RealtimeQuoteSink sink() {
    return sink(Clock.systemUTC());
  }

  private RealtimeQuoteSink sink(Clock clock) {
    return new RealtimeQuoteSink(quoteService, snapshotCache, candleRepository, marketWsPublisher, testControlService, clock);
  }

  private TickerStats stats() {
    return new TickerStats(
        "BTCUSDT",
        1000L,
        new BigDecimal("1.25"),
        new BigDecimal("110.00"),
        new BigDecimal("90.00"),
        new BigDecimal("1000.00"));
  }
}
