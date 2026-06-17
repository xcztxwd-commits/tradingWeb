# Binance Realtime Market Data Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Binance 行情从 REST 拉取升级为后端统一维护 Binance WebSocket，上游订阅在后端按 symbol 去重，前端仍只连接本项目 `/ws`，交易、挂单、平仓、止盈止损、K线和盘口读取同一份后端价格状态。

**Architecture:** 前端 STOMP 订阅只作为需求信号，后端监听 `/topic/market/*/{symbol}` 的订阅变化并维护 ref-count；`BinanceRealtimeClient` 只维护少量上游 WebSocket 连接，`BinanceSubscriptionManager` 统一下发 Binance `SUBSCRIBE` / `UNSUBSCRIBE`，所有 WS 和测试行情事件进入 `RealtimeQuoteSink`，再写 `QuoteService.cache(...)`、`RealtimeCandleRepository`、内存盘口/成交缓存并由 `MarketWsPublisher` fan-out。REST 保留为历史 K线、首屏快照和重启/重连 backfill。

**Tech Stack:** Java 21, Spring Boot 3.5, STOMP WebSocket, `java.net.http.WebSocket`, Jackson, JdbcTemplate, Redis via `StringRedisTemplate`, MyBatis-Plus repositories, Maven tests, existing React/STOMP frontend.

---

## Source-of-Truth Context

Read these files before implementation and keep edits scoped to the plan:

- `fx-trading-platform/apps/web/src/services/marketStream.ts`: frontend already connects only to backend `/ws` and exposes `subscribeQuote`, `subscribeOrderBook`, `subscribeRecentTrades`.
- `fx-trading-platform/backend/src/main/java/com/fxplatform/common/websocket/MarketWebSocketConfig.java`: existing STOMP broker and `/ws` endpoint.
- `fx-trading-platform/backend/src/main/java/com/fxplatform/market/websocket/MarketWsPublisher.java`: existing backend fan-out publisher.
- `fx-trading-platform/backend/src/main/java/com/fxplatform/market/adapter/binance/BinanceSpotMarketDataProvider.java`: existing Binance REST provider for quote, candles, order book, trades.
- `fx-trading-platform/backend/src/main/java/com/fxplatform/market/service/QuoteService.java`: trading price cache and freshness gate.
- `fx-trading-platform/backend/src/main/java/com/fxplatform/market/repository/RealtimeCandleRepository.java`: existing demo tick candle upsert, must be extended for full OHLCV.
- `fx-trading-platform/backend/src/main/java/com/fxplatform/market/service/MarketTestDataService.java`: current demo/test realtime path.
- `fx-trading-platform/backend/src/main/resources/application.yml` and `application-dev.yml`: current config defaults.

Official constraints verified against Binance Spot docs:

- Binance Spot stream symbols are lowercase; raw streams use `/ws/<streamName>`, combined streams wrap events as `{"stream":"...","data":...}`.
- A Binance Spot WebSocket connection is valid for 24 hours, sends ping frames every 20 seconds, requires pong within 1 minute, and has a 5 incoming-messages-per-second limit covering ping, pong, and JSON control messages.
- One connection can listen to at most 1024 streams.
- `@bookTicker` gives best bid/ask only; it does not provide multi-level order book.
- `@depth5`, `@depth10`, or `@depth20` provide partial top-of-book levels; payload does not include symbol, so parsing must use combined stream `stream` name or an equivalent stream-to-symbol mapping.
- `@ticker` provides 24h rolling stats such as price change percent, high, low, and volume.
- `@kline_<interval>` supports intervals including `1s`, `1m`, `5m`, `15m`, `1h`, `4h`, `1d`.
- REST `/api/v3/klines` remains the right backfill source; klines are keyed by open time.

References:

- https://developers.binance.com/docs/binance-spot-api-docs/web-socket-streams
- https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints

## Non-Negotiable Rules

- Do not let frontend code connect to Binance directly.
- Do not delete REST code; REST is used for history, snapshots, and repair.
- Do not introduce unrelated refactors in auth, wallet, admin, or trading modules.
- Do not stage, commit, reset, or revert existing workspace changes unless the user explicitly asks.
- Do not leave demo realtime and Binance realtime writing the same symbols at the same time in smoke runs.
- Every task below has a self-check. A task is not complete until its self-check command or inspection passes.

## Target Data Flow

```text
Frontend STOMP SUBSCRIBE /topic/market/quotes/BTCUSDT
  -> RealtimeSubscriptionEventListener
  -> RealtimeSubscriptionRegistry ref count 0 -> 1
  -> BinanceSubscriptionManager desired streams:
       btcusdt@bookTicker
       btcusdt@ticker
       btcusdt@aggTrade
       btcusdt@depth20@100ms
       btcusdt@kline_1s / 1m / 5m / 15m / 1h / 4h / 1d
  -> BinanceRealtimeClient
  -> BinanceStreamMessageParser
  -> RealtimeDeduplicationState
  -> RealtimeQuoteSink
       -> QuoteService.cache(...)
       -> RealtimeCandleRepository.upsertCandle(...)
       -> RealtimeMarketSnapshotCache
       -> MarketWsPublisher.publishQuote(...)
       -> MarketWsPublisher.publishOrderBook(...)
       -> MarketWsPublisher.publishRecentTrades(...)
  -> Frontend receives only backend /ws topics

Backend startup / Binance reconnect / subscription activation
  -> RealtimeBackfillService
  -> BinanceSpotMarketDataProvider.fetchCandles(...)
  -> RealtimeCandleRepository.upsertCandle(...)
```

## Failure Model To Handle

| Failure | Expected behavior | Self-check signal |
| --- | --- | --- |
| Binance closes at 24h | client reconnects, replays desired subscriptions, triggers backfill | status shows new `connectedAt`, tests verify reconnect replay |
| `serverShutdown` event | reconnect immediately with small jitter | parser emits shutdown event, client schedules reconnect |
| Ping/pong pressure | pong is prioritized; JSON commands rate-limited below 5/s | command limiter test reserves capacity |
| Many users same symbol | one upstream symbol subscription set only | registry and manager tests show ref-count fan-out |
| One user unsubscribes | upstream remains if other refs exist | registry test |
| Last user unsubscribes then quickly resubscribes | grace timer is canceled | manager test |
| Duplicate/old bookTicker | ignored by update id | dedup test |
| Duplicate/old aggTrade | ignored by aggregate trade id | dedup test |
| Old kline event | ignored by open time plus event time / last trade id | dedup test |
| `@depth20` payload has no symbol | parser resolves symbol from combined stream name | parser test |
| Redis unavailable | sink logs cache failure, does not kill WS receive loop; status exposes cache failure count | sink test with throwing `QuoteService.cache` |
| Database unavailable | sink logs candle failure, still publishes quote; status exposes candle failure count | sink test with throwing repository |
| Backfill returns same candle twice | idempotent upsert overwrites full OHLCV, volume is not added twice | repository/backfill test |
| Demo test data enabled with Binance realtime | startup validation or status warns; smoke env disables test data | config self-check |
| Too many active streams | manager refuses extra symbols before Binance 1024-stream limit | manager test |

## File Map

Create:

- `backend/src/main/java/com/fxplatform/market/realtime/MarketRealtimeProperties.java`
- `backend/src/main/java/com/fxplatform/market/realtime/RealtimeStreamKind.java`
- `backend/src/main/java/com/fxplatform/market/realtime/RealtimeSubscriptionRegistry.java`
- `backend/src/main/java/com/fxplatform/market/realtime/RealtimeSubscriptionEventListener.java`
- `backend/src/main/java/com/fxplatform/market/realtime/BinanceStreamName.java`
- `backend/src/main/java/com/fxplatform/market/realtime/BinanceStreamCommandLimiter.java`
- `backend/src/main/java/com/fxplatform/market/realtime/BinanceSubscriptionManager.java`
- `backend/src/main/java/com/fxplatform/market/realtime/BinanceRealtimeClient.java`
- `backend/src/main/java/com/fxplatform/market/realtime/BinanceRealtimeConnection.java`
- `backend/src/main/java/com/fxplatform/market/realtime/JdkBinanceRealtimeConnection.java`
- `backend/src/main/java/com/fxplatform/market/realtime/BinanceStreamMessageParser.java`
- `backend/src/main/java/com/fxplatform/market/realtime/RealtimeMarketEvent.java`
- `backend/src/main/java/com/fxplatform/market/realtime/RealtimeDeduplicationState.java`
- `backend/src/main/java/com/fxplatform/market/realtime/RealtimeMarketSnapshotCache.java`
- `backend/src/main/java/com/fxplatform/market/realtime/RealtimeQuoteSink.java`
- `backend/src/main/java/com/fxplatform/market/realtime/RealtimeBackfillService.java`
- `backend/src/main/java/com/fxplatform/market/realtime/MarketRealtimeStatus.java`
- `backend/src/main/java/com/fxplatform/market/realtime/MarketRealtimeStatusController.java`
- `backend/src/test/java/com/fxplatform/market/realtime/*Test.java`

Modify:

- `backend/src/main/java/com/fxplatform/FxPlatformApplication.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-dev.yml`
- `backend/src/main/java/com/fxplatform/market/service/QuoteService.java`
- `backend/src/main/java/com/fxplatform/market/repository/RealtimeCandleRepository.java`
- `backend/src/main/java/com/fxplatform/market/service/MarketTestDataService.java`
- `backend/src/main/java/com/fxplatform/market/controller/MarketController.java`
- `backend/src/main/java/com/fxplatform/chart/service/ChartService.java`
- `backend/src/test/java/com/fxplatform/market/service/MarketTestDataServiceTest.java`
- `backend/src/test/java/com/fxplatform/market/controller/MarketControllerTest.java`
- `backend/src/test/java/com/fxplatform/chart/service/ChartServiceTest.java`
- `apps/web/src/services/marketStream.ts`
- `apps/web/src/services/marketStream.test.ts`

## Task 0: Preflight And Dirty Workspace Guard

**Goal:** Establish a safe execution baseline without touching unrelated existing changes.

**Files:**

- Read only: repository status and source files listed above.

- [ ] **Step 0.1: Record current workspace state**

Run:

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
git status --short
```

Expected: The output may include pre-existing modified/untracked files. Record them in the worker notes. Do not reset or revert anything.

- [ ] **Step 0.2: Confirm backend module command root**

Run:

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
where mvn
```

Expected: `mvn` or `mvn.cmd` is available. If unavailable, stop and report the missing Maven executable.

- [ ] **Step 0.3: Confirm no frontend Binance direct connection exists**

Run:

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "binance|stream.binance|wss://|ws://" fx-trading-platform/apps/web/src
```

Expected: Only backend `/ws` resolution or harmless text appears. If direct Binance frontend code exists, stop and report because it violates the target architecture.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "stream.binance.com|data-stream.binance.vision" fx-trading-platform/apps/web/src
```

Expected: no matches.

## Task 1: Configuration And Freshness Alignment

**Goal:** Add realtime Binance config without creating parallel stale-time semantics.

**Files:**

- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-dev.yml`
- Modify: `backend/src/main/java/com/fxplatform/FxPlatformApplication.java`
- Modify: `backend/src/main/java/com/fxplatform/market/service/QuoteService.java`
- Create: `backend/src/main/java/com/fxplatform/market/realtime/MarketRealtimeProperties.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/MarketRealtimePropertiesTest.java`
- Test: `backend/src/test/java/com/fxplatform/market/service/QuoteServiceTest.java`

- [ ] **Step 1.1: Add properties test first**

Create `MarketRealtimePropertiesTest` with assertions for defaults and stream budget. Use `ApplicationContextRunner` or instantiate the record directly if the project avoids Spring context tests.

Required assertions:

```java
assertThat(properties.enabled()).isFalse();
assertThat(properties.provider()).isEqualTo("binance");
assertThat(properties.symbols()).containsExactly("BTCUSDT", "ETHUSDT");
assertThat(properties.klineIntervals()).contains("1s", "1m", "5m", "15m", "1h", "4h", "1d");
assertThat(properties.maxStreamsPerConnection()).isEqualTo(1024);
assertThat(properties.effectiveStreamsPerSymbol()).isEqualTo(11);
assertThat(properties.maxActiveSymbols()).isLessThanOrEqualTo(90);
```

Run:

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=MarketRealtimePropertiesTest
```

Expected: fail because the class does not exist.

- [ ] **Step 1.2: Implement `MarketRealtimeProperties`**

Create `MarketRealtimeProperties` with `@ConfigurationProperties(prefix = "market.realtime")` and these fields:

```java
boolean enabled = false;
String provider = "binance";
List<String> symbols = List.of("BTCUSDT", "ETHUSDT");
String websocketBaseUrl = "wss://stream.binance.com:9443/ws";
Duration quoteStale = Duration.ofMillis(3000);
Duration reconnectInitial = Duration.ofMillis(1000);
Duration reconnectMax = Duration.ofMillis(30000);
Duration reconnectJitter = Duration.ofMillis(250);
Duration unsubscribeGrace = Duration.ofMillis(10000);
int binanceCommandPerSecond = 4;
boolean backfillEnabled = true;
Duration backfillLookback = Duration.ofMinutes(60);
List<String> klineIntervals = List.of("1s", "1m", "5m", "15m", "1h", "4h", "1d");
int orderBookLevels = 20;
boolean orderBookFast = true;
int maxStreamsPerConnection = 1024;
int maxActiveSymbols = 80;
boolean dynamicSymbolsEnabled = true;
```

Add helper methods:

```java
public int effectiveStreamsPerSymbol() {
  return 4 + klineIntervals.size();
}

public String orderBookStreamSuffix() {
  return orderBookFast ? "depth%d@100ms".formatted(orderBookLevels) : "depth%d".formatted(orderBookLevels);
}
```

Enable binding by changing `FxPlatformApplication` from:

```java
@EnableConfigurationProperties(AdminBootstrapProperties.class)
```

to:

```java
@EnableConfigurationProperties({AdminBootstrapProperties.class, MarketRealtimeProperties.class})
```

- [ ] **Step 1.3: Add config keys**

In `application.yml`, add:

```yaml
binance:
  rest-base-url: ${BINANCE_REST_BASE_URL:https://api.binance.com}
  websocket-base-url: ${BINANCE_WS_BASE_URL:wss://stream.binance.com:9443/ws}

market:
  quote-stale-ms: ${MARKET_QUOTE_STALE_MS:3000}
  realtime:
    enabled: ${MARKET_REALTIME_ENABLED:false}
    provider: ${MARKET_REALTIME_PROVIDER:binance}
    websocket-base-url: ${BINANCE_WS_BASE_URL:wss://stream.binance.com:9443/ws}
    symbols: ${MARKET_REALTIME_SYMBOLS:BTCUSDT,ETHUSDT}
    quote-stale: ${MARKET_REALTIME_QUOTE_STALE:3s}
    reconnect-initial: ${MARKET_REALTIME_RECONNECT_INITIAL:1s}
    reconnect-max: ${MARKET_REALTIME_RECONNECT_MAX:30s}
    reconnect-jitter: ${MARKET_REALTIME_RECONNECT_JITTER:250ms}
    unsubscribe-grace: ${MARKET_REALTIME_UNSUBSCRIBE_GRACE:10s}
    binance-command-per-second: ${MARKET_BINANCE_COMMAND_PER_SECOND:4}
    backfill-enabled: ${MARKET_REALTIME_BACKFILL_ENABLED:true}
    backfill-lookback: ${MARKET_REALTIME_BACKFILL_LOOKBACK:60m}
    kline-intervals: ${MARKET_REALTIME_KLINE_INTERVALS:1s,1m,5m,15m,1h,4h,1d}
    order-book-levels: ${MARKET_REALTIME_ORDER_BOOK_LEVELS:20}
    order-book-fast: ${MARKET_REALTIME_ORDER_BOOK_FAST:true}
    max-streams-per-connection: ${MARKET_REALTIME_MAX_STREAMS_PER_CONNECTION:1024}
    max-active-symbols: ${MARKET_REALTIME_MAX_ACTIVE_SYMBOLS:80}
    dynamic-symbols-enabled: ${MARKET_REALTIME_DYNAMIC_SYMBOLS_ENABLED:true}
```

Keep existing `massive.quote-stale-ms` for compatibility, but make `QuoteService` prefer the shared market key:

```java
@Value("${market.quote-stale-ms:${massive.quote-stale-ms:3000}}")
private long quoteStaleMs;
```

In `application-dev.yml`, add a comment-free explicit guard:

```yaml
market:
  realtime:
    enabled: ${MARKET_REALTIME_ENABLED:false}
```

- [ ] **Step 1.4: Run focused tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=MarketRealtimePropertiesTest,QuoteServiceTest
```

Expected: pass.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "market\\.quote-stale-ms|massive\\.quote-stale-ms|websocket-base-url|max-active-symbols" fx-trading-platform/backend/src/main/java fx-trading-platform/backend/src/main/resources
```

Expected: `QuoteService` uses `market.quote-stale-ms` fallback; config contains realtime keys exactly once under `market.realtime`.

## Task 2: Realtime Candle Repository Full OHLCV Upsert

**Goal:** Make candle writes idempotent for Binance REST and WS candles; never add full-candle volume repeatedly.

**Files:**

- Modify: `backend/src/main/java/com/fxplatform/market/repository/RealtimeCandleRepository.java`
- Test: `backend/src/test/java/com/fxplatform/market/repository/RealtimeCandleRepositorySqlTest.java`
- Potential integration check: `backend/src/test/java/com/fxplatform/database/PostgresDatabaseIT.java`

- [ ] **Step 2.1: Add SQL string test first**

Create `RealtimeCandleRepositorySqlTest` that reads the repository source and asserts:

```java
assertThat(source).contains("upsertCandle");
assertThat(source).contains("findLastOpenTime");
assertThat(source).contains("source = EXCLUDED.source");
assertThat(source).contains("volume = EXCLUDED.volume");
assertThat(source).doesNotContain("volume = market.candles.volume + EXCLUDED.volume");
```

Run:

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=RealtimeCandleRepositorySqlTest
```

Expected: fail because methods are not present yet.

- [ ] **Step 2.2: Add full candle methods while preserving demo tick method**

Add methods:

```java
public Optional<Instant> findLastOpenTime(String symbol, String timeframe) {
  return jdbcTemplate.query("""
      SELECT open_time
      FROM market.candles
      WHERE symbol = ? AND timeframe = ?
      ORDER BY open_time DESC
      LIMIT 1
      """,
      (rs, rowNum) -> rs.getTimestamp("open_time").toInstant(),
      symbol,
      timeframe)
      .stream()
      .findFirst();
}

public void upsertCandle(
    String symbol,
    String timeframe,
    Instant openTime,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    BigDecimal volume,
    String source
) {
  jdbcTemplate.update("""
      INSERT INTO market.candles (symbol, timeframe, open_time, open, high, low, close, volume, source)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (symbol, timeframe, open_time) DO UPDATE SET
        open = EXCLUDED.open,
        high = EXCLUDED.high,
        low = EXCLUDED.low,
        close = EXCLUDED.close,
        volume = EXCLUDED.volume,
        source = EXCLUDED.source
      """,
      symbol,
      timeframe,
      Timestamp.from(openTime),
      open,
      high,
      low,
      close,
      volume,
      source);
}
```

Keep the existing demo tick `upsert(...)`, but change its `source` argument to call `upsertCandle(...)` with source `demo-realtime-tick` only if tests do not depend on the old SQL text. If preserving demo high/low accumulation is required, keep both methods separate.

- [ ] **Step 2.3: Run tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=RealtimeCandleRepositorySqlTest,MarketTestDataServiceTest
```

Expected: pass. If `MarketTestDataServiceTest` fails because it expects old `upsert`, keep the old method and add the new method without changing demo path in this task.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "volume = market\\.candles\\.volume \\+ EXCLUDED\\.volume|upsertCandle|findLastOpenTime" fx-trading-platform/backend/src/main/java/com/fxplatform/market/repository/RealtimeCandleRepository.java
```

Expected: full candle method does not add `EXCLUDED.volume`; old demo tick method may still add volume if intentionally retained.

## Task 3: Event Model, Stream Names, Parser, And Deduplication

**Goal:** Parse Binance combined-stream messages into typed realtime events and drop duplicate or stale messages deterministically.

**Files:**

- Create: `backend/src/main/java/com/fxplatform/market/realtime/RealtimeStreamKind.java`
- Create: `backend/src/main/java/com/fxplatform/market/realtime/BinanceStreamName.java`
- Create: `backend/src/main/java/com/fxplatform/market/realtime/RealtimeMarketEvent.java`
- Create: `backend/src/main/java/com/fxplatform/market/realtime/BinanceStreamMessageParser.java`
- Create: `backend/src/main/java/com/fxplatform/market/realtime/RealtimeDeduplicationState.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/BinanceStreamNameTest.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/BinanceStreamMessageParserTest.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/RealtimeDeduplicationStateTest.java`

- [ ] **Step 3.1: Write stream name tests**

Test expectations:

```java
assertThat(BinanceStreamName.bookTicker("BTCUSDT")).isEqualTo("btcusdt@bookTicker");
assertThat(BinanceStreamName.ticker("BTCUSDT")).isEqualTo("btcusdt@ticker");
assertThat(BinanceStreamName.aggTrade("BTCUSDT")).isEqualTo("btcusdt@aggTrade");
assertThat(BinanceStreamName.depth("BTCUSDT", 20, true)).isEqualTo("btcusdt@depth20@100ms");
assertThat(BinanceStreamName.kline("BTCUSDT", "1m")).isEqualTo("btcusdt@kline_1m");
assertThat(BinanceStreamName.symbolFromStream("btcusdt@depth20@100ms")).isEqualTo("BTCUSDT");
```

- [ ] **Step 3.2: Implement `RealtimeMarketEvent` records**

Use a sealed interface and records:

```java
public sealed interface RealtimeMarketEvent permits
    RealtimeMarketEvent.Quote,
    RealtimeMarketEvent.TickerStats,
    RealtimeMarketEvent.OrderBook,
    RealtimeMarketEvent.Trade,
    RealtimeMarketEvent.Candle,
    RealtimeMarketEvent.ServerShutdown {

  String symbol();
  long eventTime();

  record Quote(String symbol, long eventTime, long updateId, BigDecimal bid, BigDecimal ask) implements RealtimeMarketEvent {}

  record TickerStats(String symbol, long eventTime, BigDecimal changePercent, BigDecimal high24h, BigDecimal low24h, BigDecimal volume24h) implements RealtimeMarketEvent {}

  record OrderBook(String symbol, long eventTime, long updateId, List<MarketDepthLevelResponse> bids, List<MarketDepthLevelResponse> asks) implements RealtimeMarketEvent {}

  record Trade(String symbol, long eventTime, long aggregateTradeId, BigDecimal price, BigDecimal amount, String side) implements RealtimeMarketEvent {}

  record Candle(String symbol, long eventTime, String interval, long openTime, long closeTime, long lastTradeId, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume, boolean closed) implements RealtimeMarketEvent {}

  record ServerShutdown(long eventTime) implements RealtimeMarketEvent {
    @Override
    public String symbol() {
      return "";
    }
  }
}
```

- [ ] **Step 3.3: Write parser tests for each payload**

Use combined-wrapper input for depth because partial depth payload does not include `s`:

```json
{"stream":"btcusdt@depth20@100ms","data":{"lastUpdateId":160,"bids":[["100.00","1.2"]],"asks":[["101.00","2.4"]]}}
```

Also test raw inputs for `bookTicker`, `24hrTicker`, `aggTrade`, `kline`, and `serverShutdown`.

- [ ] **Step 3.4: Implement parser**

Rules:

- If root has `stream` and `data`, parse `data` and keep stream name for symbol fallback.
- `e=aggTrade` maps to `Trade`.
- `e=24hrTicker` maps to `TickerStats`.
- `e=kline` maps to `Candle`.
- Presence of `u`, `b`, and `a` maps to `Quote` for `bookTicker`.
- Presence of `lastUpdateId`, `bids`, and `asks` maps to `OrderBook`; use stream name to infer symbol.
- `e=serverShutdown` maps to `ServerShutdown`.
- Bad JSON or unsupported payload returns `Optional.empty()` and does not throw.

- [ ] **Step 3.5: Write dedup tests**

Required cases:

```java
assertThat(state.shouldProcess(new Quote("BTCUSDT", 1000L, 10L, bid, ask))).isTrue();
assertThat(state.shouldProcess(new Quote("BTCUSDT", 1001L, 10L, bid, ask))).isFalse();
assertThat(state.shouldProcess(new Trade("BTCUSDT", 1000L, 50L, price, qty, "BUY"))).isTrue();
assertThat(state.shouldProcess(new Trade("BTCUSDT", 1001L, 49L, price, qty, "SELL"))).isFalse();
assertThat(state.shouldProcess(new Candle("BTCUSDT", 2000L, "1m", 1000L, 59999L, 10L, o, h, l, c, v, false))).isTrue();
assertThat(state.shouldProcess(new Candle("BTCUSDT", 1999L, "1m", 1000L, 59999L, 9L, o, h, l, c, v, false))).isFalse();
```

- [ ] **Step 3.6: Run focused tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=BinanceStreamNameTest,BinanceStreamMessageParserTest,RealtimeDeduplicationStateTest
```

Expected: pass.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "depth20@100ms|serverShutdown|lastUpdateId|24hrTicker|aggTrade|kline" fx-trading-platform/backend/src/main/java/com/fxplatform/market/realtime fx-trading-platform/backend/src/test/java/com/fxplatform/market/realtime
```

Expected: parser and tests cover all listed stream types.

## Task 4: Realtime Snapshot Cache And Sink

**Goal:** Centralize quote cache writes, candle writes, order book/trade snapshots, and backend fan-out.

**Files:**

- Create: `backend/src/main/java/com/fxplatform/market/realtime/RealtimeMarketSnapshotCache.java`
- Create: `backend/src/main/java/com/fxplatform/market/realtime/RealtimeQuoteSink.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/RealtimeMarketSnapshotCacheTest.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/RealtimeQuoteSinkTest.java`

- [ ] **Step 4.1: Write cache tests**

Expected behavior:

- `latestQuoteStats(symbol)` returns empty before ticker stats arrive.
- `updateTickerStats` stores 24h fields.
- `recentTrades(symbol, limit)` returns newest first and caps at 100.
- `orderBook(symbol)` returns last depth snapshot.

- [ ] **Step 4.2: Implement `RealtimeMarketSnapshotCache`**

Use `ConcurrentHashMap<String, TickerStats>`, `ConcurrentHashMap<String, MarketDepthResponse>`, and `ConcurrentHashMap<String, ArrayDeque<RecentTradeResponse>>`. Synchronize per trade deque while mutating.

Expose:

```java
public Optional<RealtimeMarketEvent.TickerStats> tickerStats(String symbol);
public void putTickerStats(RealtimeMarketEvent.TickerStats stats);
public Optional<MarketDepthResponse> orderBook(String symbol);
public void putOrderBook(MarketDepthResponse depth);
public List<RecentTradeResponse> recentTrades(String symbol, int limit);
public List<RecentTradeResponse> addTrade(RecentTradeResponse trade);
```

- [ ] **Step 4.3: Write sink tests**

Cases:

- `Quote` writes `QuoteService.cache(...)` and publishes quote.
- `TickerStats` updates cache but does not publish by itself unless a latest quote exists.
- `Quote` after `TickerStats` includes `changePercent/high24h/low24h/volume24h`.
- `OrderBook` stores and publishes depth.
- `Trade` stores and publishes recent trades list.
- `Candle` calls `RealtimeCandleRepository.upsertCandle(...)`.
- Throwing `QuoteService.cache` does not prevent `MarketWsPublisher.publishQuote(...)`.
- Throwing candle repository does not prevent quote publishing.

- [ ] **Step 4.4: Implement `RealtimeQuoteSink`**

Implementation rules:

- Use `SymbolNormalizer.normalize(...)` for every symbol.
- For `Quote`, compute `mid = bid.add(ask).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP)`, `spread = ask.subtract(bid)`, source `binance-ws-bookTicker`.
- Merge latest ticker stats into `QuoteResponse`.
- For `Trade`, map side: Binance `m=true` means buyer is maker, so taker side is `SELL`; otherwise `BUY`.
- For `Candle`, call full OHLCV upsert with source `binance-ws-kline`.
- Catch and record failures separately for cache write, candle write, and publish.

Expose counters for status:

```java
public long processedCount();
public long cacheFailureCount();
public long candleFailureCount();
public long publishFailureCount();
public OptionalLong lastEventTime();
```

- [ ] **Step 4.5: Run focused tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=RealtimeMarketSnapshotCacheTest,RealtimeQuoteSinkTest
```

Expected: pass.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "binance-ws-bookTicker|binance-ws-kline|cacheFailureCount|candleFailureCount|publishRecentTrades|publishOrderBook" fx-trading-platform/backend/src/main/java/com/fxplatform/market/realtime fx-trading-platform/backend/src/test/java/com/fxplatform/market/realtime
```

Expected: sink handles quote, candle, depth, trades, and failure counters.

## Task 5: STOMP Subscription Registry And Event Listener

**Goal:** Convert frontend backend-topic subscriptions into backend upstream demand with exact ref-count semantics.

**Files:**

- Create: `backend/src/main/java/com/fxplatform/market/realtime/RealtimeSubscriptionRegistry.java`
- Create: `backend/src/main/java/com/fxplatform/market/realtime/RealtimeSubscriptionEventListener.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/RealtimeSubscriptionRegistryTest.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/RealtimeSubscriptionEventListenerTest.java`

- [ ] **Step 5.1: Write registry tests**

Cases:

- Same session subscribes quote and order book for same symbol: symbol ref count is one for upstream symbol activation if registry counts unique session+symbol demand.
- Two sessions same symbol: ref count is two.
- Unsubscribe one subscription id leaves other session active.
- Disconnect removes all subscriptions from that session.
- Invalid destination is ignored.

Use topics:

```text
/topic/market/quotes/BTCUSDT
/topic/market/order-book/BTCUSDT
/topic/market/trades/BTCUSDT
```

- [ ] **Step 5.2: Implement registry**

Data model:

```java
record SessionSubscription(String sessionId, String subscriptionId, String destination, String symbol, EnumSet<RealtimeStreamKind> kinds) {}
record SymbolDemandChange(String symbol, int previousRefCount, int currentRefCount) {
  boolean activated() { return previousRefCount == 0 && currentRefCount > 0; }
  boolean deactivated() { return previousRefCount > 0 && currentRefCount == 0; }
}
```

Topic mapping:

- quotes: `QUOTE`, `TICKER_STATS`, all configured `KLINE`
- order-book: `ORDER_BOOK`
- trades: `TRADE`

For upstream symbol activation, count unique `(sessionId, symbol)`, not number of topics, so a single browser subscribing quote/orderbook/trades does not triple-count the same symbol.

- [ ] **Step 5.3: Implement listener**

Use:

```java
@EventListener
public void onSubscribe(SessionSubscribeEvent event) { ... }

@EventListener
public void onUnsubscribe(SessionUnsubscribeEvent event) { ... }

@EventListener
public void onDisconnect(SessionDisconnectEvent event) { ... }
```

Read session id, subscription id, and destination through `StompHeaderAccessor.wrap(event.getMessage())`. Call `BinanceSubscriptionManager` only when registry reports symbol activation/deactivation.

- [ ] **Step 5.4: Run tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=RealtimeSubscriptionRegistryTest,RealtimeSubscriptionEventListenerTest
```

Expected: pass.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "SessionSubscribeEvent|SessionUnsubscribeEvent|SessionDisconnectEvent|/topic/market/quotes|unique" fx-trading-platform/backend/src/main/java/com/fxplatform/market/realtime fx-trading-platform/backend/src/test/java/com/fxplatform/market/realtime
```

Expected: listener handles all three STOMP lifecycle events; registry tests prove fan-out ref-count behavior.

## Task 6: Binance Subscription Manager And Command Limiter

**Goal:** Translate active symbol demand into deduplicated Binance stream sets with grace-period unsubscribe and command-rate safety.

**Files:**

- Create: `backend/src/main/java/com/fxplatform/market/realtime/BinanceStreamCommandLimiter.java`
- Create: `backend/src/main/java/com/fxplatform/market/realtime/BinanceSubscriptionManager.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/BinanceStreamCommandLimiterTest.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/BinanceSubscriptionManagerTest.java`

- [ ] **Step 6.1: Write command limiter tests**

Use a fake clock. Required assertions:

- At most 4 JSON control commands per second are released.
- A pong-reserved slot remains available conceptually; the limiter never emits 5 commands in one second.
- Batch command contains multiple params when several symbols activate at once.

- [ ] **Step 6.2: Write manager tests**

Cases:

- `activate("BTCUSDT")` sends one batched `SUBSCRIBE` with stream names:

```text
btcusdt@bookTicker
btcusdt@ticker
btcusdt@aggTrade
btcusdt@depth20@100ms
btcusdt@kline_1s
btcusdt@kline_1m
btcusdt@kline_5m
btcusdt@kline_15m
btcusdt@kline_1h
btcusdt@kline_4h
btcusdt@kline_1d
```

- Repeated `activate("BTCUSDT")` does not send duplicate commands.
- `deactivate("BTCUSDT")` schedules but does not immediately send `UNSUBSCRIBE`.
- Re-activation before grace cancels unsubscribe.
- `onReconnect()` resends all desired active streams.
- Activation over `maxActiveSymbols` is rejected and status reports rejected symbol count.

- [ ] **Step 6.3: Implement manager**

Use these public methods:

```java
public void activateSymbol(String symbol);
public void deactivateSymbol(String symbol);
public void onConnected();
public void onDisconnected();
public Set<String> activeSymbols();
public Set<String> desiredStreams();
public MarketRealtimeStatus snapshotStatus();
```

Do not connect to Binance here; depend on `BinanceRealtimeClient` interface method:

```java
void sendControlMessage(String method, List<String> params);
```

Generate command ids with `AtomicLong`.

- [ ] **Step 6.4: Run tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=BinanceStreamCommandLimiterTest,BinanceSubscriptionManagerTest
```

Expected: pass.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "SUBSCRIBE|UNSUBSCRIBE|SET_PROPERTY|combined|depth20@100ms|maxActiveSymbols|desiredStreams" fx-trading-platform/backend/src/main/java/com/fxplatform/market/realtime fx-trading-platform/backend/src/test/java/com/fxplatform/market/realtime
```

Expected: command manager covers stream names, dedupe, grace, reconnect replay, and stream budget.

## Task 7: Binance Realtime Client

**Goal:** Maintain a resilient Binance WebSocket connection without tying tests to the network.

**Files:**

- Create: `backend/src/main/java/com/fxplatform/market/realtime/BinanceRealtimeConnection.java`
- Create: `backend/src/main/java/com/fxplatform/market/realtime/JdkBinanceRealtimeConnection.java`
- Create: `backend/src/main/java/com/fxplatform/market/realtime/BinanceRealtimeClient.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/BinanceRealtimeClientTest.java`

- [ ] **Step 7.1: Write client tests with fake connection**

Fake connection must support:

```java
connect(URI uri, Listener listener)
sendText(String text)
sendPong(ByteBuffer payload)
close()
```

Cases:

- On open, send `SET_PROPERTY ["combined", true]`.
- On text payload, parser is called; dedup accepted event goes to sink.
- On `serverShutdown`, reconnect is scheduled immediately and active streams are replayed after open.
- On close/error, reconnect backoff sequence is `1s`, `2s`, `4s`, capped at `30s`.
- On reconnect success, `RealtimeBackfillService.backfill(activeSymbols)` is called.
- On ping, pong with same payload is sent before queued JSON control commands.
- At 23h50m active age, client rotates connection.

- [ ] **Step 7.2: Implement `BinanceRealtimeClient`**

Responsibilities:

- No Spring scheduling annotations inside low-level client; inject a `TaskScheduler` or `ScheduledExecutorService`.
- Keep `connectedAt`, `lastMessageAt`, `lastDisconnectAt`, `reconnectAttempt`.
- Use `MarketRealtimeProperties.websocketBaseUrl()`.
- Send `SET_PROPERTY combined true` after each connect because partial depth stream needs stream name.
- Never throw from message receive loop. Parse errors increment counter.
- Call `subscriptionManager.onConnected()` after combined mode command is queued.
- Call `backfillService.backfill(subscriptionManager.activeSymbols())` after reconnect and after initial connect if backfill is enabled.

- [ ] **Step 7.3: Implement `JdkBinanceRealtimeConnection`**

Use Java 21 `HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(...)`. In listener:

- `onText`: forward complete messages to client.
- `onPing`: call client pong handler.
- `onClose`: call client close handler.
- `onError`: call client error handler.

- [ ] **Step 7.4: Run tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=BinanceRealtimeClientTest
```

Expected: pass with no external network.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "newWebSocketBuilder|sendPong|serverShutdown|23h50|combined|lastMessageAt|backfill" fx-trading-platform/backend/src/main/java/com/fxplatform/market/realtime fx-trading-platform/backend/src/test/java/com/fxplatform/market/realtime
```

Expected: client handles combined mode, ping/pong, reconnect, rotate, and backfill.

## Task 8: REST Backfill Service

**Goal:** Fill candle gaps on startup, first subscription, and reconnect using existing Binance REST provider.

**Files:**

- Create: `backend/src/main/java/com/fxplatform/market/realtime/RealtimeBackfillService.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/RealtimeBackfillServiceTest.java`
- Modify: `backend/src/main/java/com/fxplatform/market/repository/RealtimeCandleRepository.java`

- [ ] **Step 8.1: Write backfill tests**

Cases:

- No DB candle: start = `now - backfillLookback`.
- Existing DB candle: start = last open time.
- `fetchCandles` empty: no writes, no exception.
- Multiple intervals: calls REST per interval.
- REST failure/business exception: logs and increments failure counter, continues next symbol.
- Volume is written with `upsertCandle`, not old demo tick `upsert`.

- [ ] **Step 8.2: Implement backfill service**

Inject:

```java
MarketRealtimeProperties properties;
BinanceSpotMarketDataProvider binanceProvider;
RealtimeCandleRepository realtimeCandleRepository;
```

Public API:

```java
public void backfill(Collection<String> symbols);
public void backfill(String symbol);
public long successCount();
public long failureCount();
```

For each `(symbol, interval)`:

- `from = repository.findLastOpenTime(symbol, interval).orElse(now.minus(properties.backfillLookback()))`
- `to = now`
- call `binanceProvider.fetchCandles(symbol, symbol, interval, from, to)`
- write each candle through `upsertCandle(..., "binance-rest-backfill")`

- [ ] **Step 8.3: Run tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=RealtimeBackfillServiceTest
```

Expected: pass.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "binance-rest-backfill|findLastOpenTime|fetchCandles|backfillLookback|upsertCandle" fx-trading-platform/backend/src/main/java/com/fxplatform/market/realtime fx-trading-platform/backend/src/test/java/com/fxplatform/market/realtime
```

Expected: service uses REST candles and full OHLCV upsert.

## Task 9: Wire Runtime Lifecycle

**Goal:** Start realtime services only when explicitly enabled and avoid conflict with demo test data.

**Files:**

- Create or modify: `backend/src/main/java/com/fxplatform/market/realtime/MarketRealtimeLifecycle.java`
- Modify: `backend/src/main/java/com/fxplatform/market/service/MarketTestDataService.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/MarketRealtimeLifecycleTest.java`

- [ ] **Step 9.1: Write lifecycle tests**

Cases:

- `market.realtime.enabled=false`: client does not connect.
- `market.realtime.enabled=true`: client connects and bootstrap symbols activate/backfill.
- `market.realtime.enabled=true` and `market.test-data.enabled=true`: lifecycle rejects startup or emits a clear disabled-demo decision. Use the safer behavior: throw `IllegalStateException` unless `market.test-data.symbols` does not overlap realtime symbols.

- [ ] **Step 9.2: Implement lifecycle**

Use `ApplicationRunner`:

```java
@Component
@RequiredArgsConstructor
public class MarketRealtimeLifecycle implements ApplicationRunner {
  @Override
  public void run(ApplicationArguments args) {
    if (!properties.enabled()) return;
    validateDemoConflict();
    client.connect();
    properties.symbols().forEach(subscriptionManager::activateSymbol);
    if (properties.backfillEnabled()) {
      backfillService.backfill(properties.symbols());
    }
  }
}
```

Do not annotate this service with `@Scheduled`.

- [ ] **Step 9.3: Run tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=MarketRealtimeLifecycleTest,MarketTestDataServiceTest
```

Expected: pass.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "MarketRealtimeLifecycle|ApplicationRunner|validateDemoConflict|MARKET_TEST_DATA_ENABLED=false" fx-trading-platform/backend/src/main/java fx-trading-platform/backend/src/test/java
```

Expected: realtime is opt-in and demo conflict is explicit.

## Task 10: Route Test Data Through The Same Sink

**Goal:** Ensure demo/test行情 covers the same quote cache, candle write, and fan-out path as Binance realtime.

**Files:**

- Modify: `backend/src/main/java/com/fxplatform/market/service/MarketTestDataService.java`
- Modify: `backend/src/test/java/com/fxplatform/market/service/MarketTestDataServiceTest.java`
- Potential create: `backend/src/main/java/com/fxplatform/market/realtime/DemoRealtimeEventFactory.java`

- [ ] **Step 10.1: Update tests first**

Change `MarketTestDataServiceTest` expectations:

- verify `RealtimeQuoteSink.accept(...)` receives quote, order book, trade, and candle events.
- no direct `MarketWsPublisher` call from `MarketTestDataService`.
- no direct `QuoteService.cache(...)` call from `MarketTestDataService`.

- [ ] **Step 10.2: Modify service**

Replace direct calls:

```java
cacheQuote(quote);
persistRealtimeCandles(normalizedSymbol, quote, trade);
marketWsPublisher.publishQuote(quote);
marketWsPublisher.publishOrderBook(depth);
marketWsPublisher.publishRecentTrades(normalizedSymbol, recentTrades);
```

with calls to `RealtimeQuoteSink.accept(...)` using demo source events or a dedicated sink method:

```java
realtimeQuoteSink.acceptDemoTick(quote, depth, trade, List.of("1s", "5m", "15m", "1h"));
```

If adding `acceptDemoTick`, its implementation must still call the same private sink branches used by Binance events.

- [ ] **Step 10.3: Run tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=MarketTestDataServiceTest,RealtimeQuoteSinkTest
```

Expected: pass.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "MarketWsPublisher|QuoteService|RealtimeQuoteSink" fx-trading-platform/backend/src/main/java/com/fxplatform/market/service/MarketTestDataService.java
```

Expected: `MarketTestDataService` depends on `RealtimeQuoteSink`, not directly on `MarketWsPublisher` or `QuoteService`.

## Task 11: REST Snapshot Fallback And Chart Merge

**Goal:** Keep REST as first-screen fallback while exposing realtime cache where it is fresher.

**Files:**

- Modify: `backend/src/main/java/com/fxplatform/market/controller/MarketController.java`
- Modify: `backend/src/main/java/com/fxplatform/chart/service/ChartService.java`
- Test: `backend/src/test/java/com/fxplatform/market/controller/MarketControllerTest.java`
- Test: `backend/src/test/java/com/fxplatform/chart/service/ChartServiceTest.java`

- [ ] **Step 11.1: Write controller tests**

Cases:

- `/api/market/order-book/{symbol}` returns realtime cache if present.
- If realtime cache is empty, it falls back to `marketDataRouter.orderBook(symbol)`.
- `/api/market/trades/{symbol}` returns realtime recent trades if present.
- If realtime recent trades are empty, it falls back to router.

- [ ] **Step 11.2: Modify `MarketController`**

Inject `RealtimeMarketSnapshotCache`. Prefer cache for order book and trades:

```java
return ApiResponse.success(realtimeCache.orderBook(symbol)
    .orElseGet(() -> marketDataRouter.orderBook(symbol)));
```

For trades:

```java
List<RecentTradeResponse> cached = realtimeCache.recentTrades(symbol, limit);
return ApiResponse.success(cached.isEmpty() ? marketDataRouter.recentTrades(symbol, limit) : cached);
```

- [ ] **Step 11.3: Write chart merge tests**

Cases:

- Provider REST returns historical candles and DB has overlapping realtime last candle: response contains DB realtime candle for same open time, not duplicate.
- Provider REST unavailable but DB has candles: existing fallback still works.

- [ ] **Step 11.4: Modify `ChartService` conservatively**

After provider candles are fetched, read DB candles for the same range and merge by timestamp with DB candle winning only for overlapping open times where source is realtime/backfill. Since `CandleResponse` lacks source, implement repository merge by reading `CandleEntity` and mapping. Keep old behavior if provider returns empty.

- [ ] **Step 11.5: Run tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=MarketControllerTest,ChartServiceTest
```

Expected: pass.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "RealtimeMarketSnapshotCache|orderBook\\(|recentTrades\\(|databaseCandles|merge" fx-trading-platform/backend/src/main/java/com/fxplatform/market/controller/MarketController.java fx-trading-platform/backend/src/main/java/com/fxplatform/chart/service/ChartService.java
```

Expected: REST endpoints and chart service preserve fallback while using realtime cache/DB where useful.

## Task 12: Admin Status Endpoint

**Goal:** Provide a read-only way to inspect realtime connection health and active subscriptions.

**Files:**

- Create: `backend/src/main/java/com/fxplatform/market/realtime/MarketRealtimeStatus.java`
- Create: `backend/src/main/java/com/fxplatform/market/realtime/MarketRealtimeStatusController.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/MarketRealtimeStatusControllerTest.java`

- [ ] **Step 12.1: Write controller test**

Expected endpoint:

```text
GET /api/admin/market/realtime/status
```

Response fields:

```json
{
  "enabled": true,
  "provider": "binance",
  "connected": true,
  "connectedAt": 1780000000000,
  "lastMessageAt": 1780000001000,
  "activeSymbols": ["BTCUSDT"],
  "desiredStreamCount": 11,
  "processedCount": 100,
  "cacheFailureCount": 0,
  "candleFailureCount": 0,
  "publishFailureCount": 0,
  "backfillSuccessCount": 1,
  "backfillFailureCount": 0,
  "reconnectAttempt": 0,
  "rejectedSymbolCount": 0
}
```

- [ ] **Step 12.2: Implement status model and controller**

Keep it read-only. If admin security rules block the test, use existing admin controller test patterns rather than weakening security.

- [ ] **Step 12.3: Run tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=MarketRealtimeStatusControllerTest
```

Expected: pass.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "/api/admin/market/realtime/status|processedCount|cacheFailureCount|desiredStreamCount" fx-trading-platform/backend/src/main/java/com/fxplatform/market/realtime fx-trading-platform/backend/src/test/java/com/fxplatform/market/realtime
```

Expected: endpoint and status fields are present.

## Task 13: Frontend Guardrails

**Goal:** Preserve backend-only WS design and improve symbol normalization without direct Binance references.

**Files:**

- Modify: `apps/web/src/services/marketStream.ts`
- Modify: `apps/web/src/services/marketStream.test.ts`

- [ ] **Step 13.1: Add tests**

Add assertions:

```ts
subscribeQuote('btc-usdt', null, () => {})
// expected topic: /topic/market/quotes/BTCUSDT

subscribeOrderBook('btc/usdt', null, () => {})
// expected topic: /topic/market/order-book/BTCUSDT

subscribeRecentTrades('btc_usdt', null, () => {})
// expected topic: /topic/market/trades/BTCUSDT
```

Add source scan test:

```ts
assert.doesNotMatch(source, /stream\.binance\.com|data-stream\.binance\.vision/)
```

- [ ] **Step 13.2: Implement normalization helper**

In `marketStream.ts`:

```ts
function normalizeMarketStreamSymbol(symbol: string) {
  return symbol.replace(/[-_/]/g, '').toUpperCase()
}
```

Use it in `subscribeQuote`, `subscribeOrderBook`, and `subscribeRecentTrades`.

- [ ] **Step 13.3: Run tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
```

Expected: pass.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "stream\\.binance\\.com|data-stream\\.binance\\.vision" fx-trading-platform/apps/web/src
```

Expected: no matches.

## Task 14: Controlled Test Market Override

**Goal:** Add a high-priority test-control layer for deterministic Kline/quote movement without polluting Binance realtime code.

**Files:**

- Create: `backend/src/main/java/com/fxplatform/market/realtime/MarketTestControlService.java`
- Create: `backend/src/main/java/com/fxplatform/market/realtime/MarketTestControlController.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/MarketTestControlServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/market/realtime/MarketTestControlControllerTest.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 14.1: Add config**

```yaml
market:
  test-control:
    enabled: ${MARKET_TEST_CONTROL_ENABLED:false}
    max-ttl: ${MARKET_TEST_CONTROL_MAX_TTL:5m}
```

- [ ] **Step 14.2: Write service tests**

Cases:

- Disabled config rejects override requests.
- Enabled config accepts fixed quote for symbol with TTL.
- While override active, `RealtimeQuoteSink` ignores Binance quote for that symbol and publishes override quote.
- After TTL, Binance events are accepted again.
- Ending override triggers `RealtimeBackfillService.backfill(symbol)`.

- [ ] **Step 14.3: Implement service**

Do not store overrides in database for this phase. Use in-memory `ConcurrentHashMap<String, OverrideState>` with expiry. Keep endpoint admin-only.

Request model:

```java
record MarketTestControlRequest(String symbol, BigDecimal bid, BigDecimal ask, Duration ttl) {}
```

- [ ] **Step 14.4: Integrate with sink**

At the start of quote handling:

```java
Optional<QuoteResponse> override = testControlService.overrideQuote(symbol);
if (override.isPresent()) {
  publishAndCache(override.get(), "test-control");
  return;
}
```

Do not let Binance override messages write cache or publish while active.

- [ ] **Step 14.5: Run tests**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=MarketTestControlServiceTest,MarketTestControlControllerTest,RealtimeQuoteSinkTest
```

Expected: pass.

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
rg -n "MARKET_TEST_CONTROL_ENABLED|test-control|overrideQuote|backfill\\(symbol\\)" fx-trading-platform/backend/src/main/java fx-trading-platform/backend/src/test/java fx-trading-platform/backend/src/main/resources
```

Expected: test-control is opt-in and isolated from default Binance behavior.

## Task 15: End-To-End Runtime Smoke

**Goal:** Prove the integrated runtime behavior with a real backend, real frontend, and controlled environment.

**Files:**

- Potential create: `scripts/smoke-binance-realtime.mjs`
- Potential test: `scripts/smoke-binance-realtime.test.mjs`

- [ ] **Step 15.1: Run backend with realtime enabled and demo test data disabled**

Use a temporary port. Adjust env for local DB/Redis as required by existing project setup.

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
set SERVER_PORT=18090
set SPRING_PROFILES_ACTIVE=dev
set MARKET_REALTIME_ENABLED=true
set MARKET_REALTIME_SYMBOLS=BTCUSDT
set MARKET_TEST_DATA_ENABLED=false
set EXECUTION_MODE=demo
mvn.cmd spring-boot:run
```

Expected:

- backend starts
- no demo/test-data conflict
- realtime status endpoint shows enabled
- `activeSymbols` includes `BTCUSDT`
- desired stream count equals 11 with default intervals

- [ ] **Step 15.2: Verify status endpoint**

In another terminal:

```bat
curl http://127.0.0.1:18090/api/admin/market/realtime/status
```

Expected: If admin auth blocks it, authenticate using existing admin smoke helpers. If endpoint is reachable, confirm `connected=true` after Binance connection succeeds.

- [ ] **Step 15.3: Verify quote cache through REST**

```bat
curl http://127.0.0.1:18090/api/market/quotes/BTCUSDT
```

Expected:

- `symbol` is `BTCUSDT`
- `source` is `binance-ws-bookTicker` after WS messages arrive
- `bid`, `ask`, and `mid` are positive
- `timestamp` age is under configured stale ms when runtime is healthy

- [ ] **Step 15.4: Verify frontend has no external Binance WS**

Start web:

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:dev"
```

Open trading page and inspect Network:

- expected WebSocket: backend `/ws`
- forbidden WebSocket: `stream.binance.com`, `data-stream.binance.vision`

- [ ] **Step 15.5: Verify multi-browser fan-out**

Open two browser sessions to the same trading page symbol. Check backend status:

- active symbol remains one `BTCUSDT`
- desired stream count remains 11
- frontend instances both receive quote updates

- [ ] **Step 15.6: Verify reconnect and backfill**

Temporarily block or close Binance connection by stopping backend network access if safe, or call a test-only client close method in a local test profile. Then restore connection.

Expected:

- backend reconnects
- `connectedAt` updates
- `backfillSuccessCount` increments
- chart endpoint still returns candles

**Self-check:**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test
```

Expected: all non-environment-blocked tests pass. If `PostgresDatabaseIT` is blocked by Docker/Testcontainers, record it explicitly instead of claiming full integration coverage.

## Task 16: Architecture And Regression Sweep

**Goal:** Ensure the new realtime pipeline did not break existing frontend, backend, trading, or provider routing behavior.

**Files:**

- No intended source edits.

- [ ] **Step 16.1: Run backend focused suite**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test -Dtest=QuoteServiceTest,BinanceSpotMarketDataProviderTest,MarketDataRouterTest,MarketTestDataServiceTest,MarketControllerTest,ChartServiceTest,SimulatedExecutionAdapterFeeTest,PendingOrderExecutionServiceTest,ProtectiveOrderExecutionServiceTest,PositionServiceTest
```

Expected: pass.

- [ ] **Step 16.2: Run full backend suite**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn.cmd test
```

Expected: pass except known environment-blocked integration tests. Any failure in realtime unit tests blocks completion.

- [ ] **Step 16.3: Run frontend and architecture checks**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

Expected: pass.

- [ ] **Step 16.4: Review diff scope**

```bat
cd /d C:\Users\User\Desktop\workspace\tradingView-KlineChart
git diff -- fx-trading-platform/backend/src/main/java/com/fxplatform/market fx-trading-platform/backend/src/main/java/com/fxplatform/chart fx-trading-platform/backend/src/main/resources fx-trading-platform/apps/web/src/services fx-trading-platform/backend/src/test/java/com/fxplatform/market fx-trading-platform/backend/src/test/java/com/fxplatform/chart
```

Expected: diff only touches planned files. If unrelated files appear, stop and ask before changing them.

**Self-check:**

Prepare a completion matrix with:

- Implemented tasks
- Tests run
- Tests blocked with exact reason
- Runtime smoke evidence
- Known residual risks

Do not claim completion without this matrix.

## Acceptance Criteria

The upgrade is complete only when all items below are true:

- Frontend code contains no direct Binance WebSocket or REST endpoint.
- Backend status proves one active upstream stream set per active symbol, not per browser.
- Two browser sessions on `BTCUSDT` keep upstream desired stream count unchanged.
- `QuoteService.freshQuote("BTCUSDT")` can return a fresh cached `binance-ws-bookTicker` quote after WS messages arrive.
- Market order execution, pending order trigger checks, protective order checks, position close, and position floating PnL still read `QuoteService.freshQuote(...)`.
- `@depth20` or `@depth20@100ms` is used for multi-level order book fan-out.
- `@ticker` or merged cached stats preserve 24h fields for frontend market display.
- Kline writes use full OHLCV idempotent upsert and do not double-add REST/WS candle volume.
- Reconnect replays desired streams and triggers REST backfill.
- Command limiter keeps JSON control messages under Binance limit with pong priority.
- Demo/test realtime path goes through the same sink, or is explicitly isolated from Binance runtime.
- Runtime smoke uses `MARKET_TEST_DATA_ENABLED=false` when `MARKET_REALTIME_ENABLED=true`.

## Execution Notes For Codex

- Start each task by reading the files listed in that task.
- Write the test first, run it, confirm it fails for the expected reason, then implement.
- After each task, run the task self-check command before moving on.
- If a task uncovers pre-existing unrelated failures, record them and keep the realtime diff focused.
- If Binance docs differ from the assumptions in this plan, stop and update the plan before coding against stale behavior.
- Do not stage or commit unless the user explicitly asks.

## Plan Self-Review

Spec coverage:

- Backend Binance WS: Tasks 3, 6, 7.
- Backend fan-out only: Tasks 4, 5, 13.
- REST backfill: Task 8.
- Quote cache shared by trading: Tasks 1, 4, 16.
- Candle persistence: Tasks 2, 8, 11.
- Order book/trades: Tasks 3, 4, 11.
- Disconnect/reconnect/heartbeat/rate limit: Tasks 6, 7, 15.
- Subscribe/unsubscribe/ref-count: Tasks 5, 6.
- Duplicate/乱序: Task 3.
- Multi-user fan-out: Tasks 5, 6, 15.
- Test market coverage: Tasks 10, 14.
- Self-check after each phase: every task includes a self-check section.

Red-flag scan:

- The plan intentionally avoids broad refactors.
- The plan keeps REST.
- The plan keeps frontend backend-only.
- The plan contains explicit stop conditions for docs drift, missing Maven, direct frontend Binance code, and unrelated diffs.

Type consistency:

- Realtime event records use `RealtimeMarketEvent`.
- Stream kinds use `RealtimeStreamKind`.
- Subscription lifecycle uses `RealtimeSubscriptionRegistry` and `BinanceSubscriptionManager`.
- Runtime client uses `BinanceRealtimeClient` and testable `BinanceRealtimeConnection`.
- All candle full writes use `RealtimeCandleRepository.upsertCandle(...)`.
