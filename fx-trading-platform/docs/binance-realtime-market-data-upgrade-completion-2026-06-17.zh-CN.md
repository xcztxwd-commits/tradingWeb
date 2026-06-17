# Binance 实时行情升级完成记录

日期：2026-06-17

## 完成范围

已完成：

- 后端 Binance market proxy 与 realtime 接入，前端源码禁止直连 Binance REST/WS。
- `market.realtime.*` 配置、启动生命周期、demo test-data 冲突校验。
- realtime candle repository 的 full OHLCV upsert 和 last-open-time 查询。
- Binance stream name、parser、dedup、snapshot cache、统一 `RealtimeQuoteSink`。
- `RealtimeQuoteSink` 对 Binance `bookTicker` 无事件时间 payload 使用接收时间写入 quote timestamp。
- WebSocket client、JDK connection、重连、ping/pong、3h50m 主动轮换调度、`serverShutdown` 处理。
- `market.realtime.reconnect-jitter` 接入重连退避，生产默认随机 jitter，测试可注入确定性 offset。
- REST candle backfill。
- STOMP subscription registry 和 Binance subscription manager。
- REST order book/trades 优先读 realtime cache；chart candles 合并 DB realtime/backfill candle。
- Admin realtime status endpoint：`GET /api/admin/market/realtime/status`。
- 前端 `marketStream` symbol normalization，前端只连接后端 `/ws`。
- test-control override layer：`/api/admin/market/test-control/overrides`，默认关闭，TTL 内存覆盖，结束覆盖触发 backfill。
- 可重复 smoke 脚本：`scripts/smoke-binance-realtime.mjs` 和离线脚本测试；脚本断言 `connected=true`、live quote `source=binance-ws-bookTicker`，并保证失败时清理 test-control override。
- test-control POST override 后立即把 deterministic quote 发布到 REST quote cache，避免等待下一条 Binance live event。

## 运行态证据

使用临时 Docker 容器启动：

- `fx-binance-smoke-postgres`：host `15432`
- `fx-binance-smoke-redis`：host `16379`

后端启动关键环境：

```bat
set SERVER_PORT=18090
set SPRING_PROFILES_ACTIVE=dev
set DATABASE_URL=jdbc:postgresql://127.0.0.1:15432/fx_platform
set DATABASE_USERNAME=postgres
set DATABASE_PASSWORD=codex_runtime_pw
set REDIS_HOST=127.0.0.1
set REDIS_PORT=16379
set MARKET_REALTIME_ENABLED=true
set MARKET_REALTIME_SYMBOLS=BTCUSDT
set MARKET_TEST_DATA_ENABLED=false
set MARKET_TEST_CONTROL_ENABLED=true
set EXECUTION_MODE=demo
```

`node fx-trading-platform\scripts\smoke-binance-realtime.mjs` 通过：

- actuator health：`UP`
- admin login：通过
- realtime status：`connected=true`，`activeSymbols=["BTCUSDT"]`，`desiredStreamCount=11`
- live REST quote：`source=binance-ws-bookTicker`，bid/ask/mid 为正数
- test-control override：POST 后 REST quote 立即返回 `source=test-control`
- cleanup：DELETE override 成功

前端 fan-out 协议 smoke 通过：

- 两个 `@stomp/stompjs` 客户端连接 `ws://127.0.0.1:18090/ws`
- 两个客户端都订阅 `/topic/market/quotes/BTCUSDT`、`/topic/market/order-book/BTCUSDT`、`/topic/market/trades/BTCUSDT`
- 两个客户端都收到 quote
- 订阅期间后端 status 保持 `activeSymbols=["BTCUSDT"]`、`desiredStreamCount=11`
- 断开后后端 status 回到 `activeSymbols=[]`、`desiredStreamCount=0`

说明：内置浏览器打开本地 Vite URL 被客户端拦截，未完成真实 DOM 浏览器 smoke；协议 smoke 使用的连接库与前端 `marketStream` 一致。

## 验证矩阵

| 验证项 | 结果 |
| --- | --- |
| `mvn.cmd test -Dtest=MarketRealtimeLifecycleTest,MarketTestDataServiceTest` | 通过，9 tests |
| `mvn.cmd test -Dtest=MarketTestDataServiceTest,RealtimeQuoteSinkTest` | 通过，15 tests |
| `mvn.cmd test -Dtest=MarketControllerTest,ChartServiceTest` | 通过，12 tests |
| `mvn.cmd test -Dtest=MarketRealtimeStatusControllerTest` | 通过，2 tests |
| `mvn.cmd test -Dtest=BinanceRealtimeClientTest` | 通过，13 tests |
| `mvn.cmd test -Dtest=MarketTestControlControllerTest,MarketTestControlServiceTest,RealtimeQuoteSinkTest` | 通过，18 tests |
| `node fx-trading-platform\scripts\smoke-binance-realtime.test.mjs` | 通过，3 tests |
| `mvn.cmd test` | 通过，608 tests |
| `npm.cmd --prefix fx-trading-platform run web:test` | 通过，395 tests |
| `npm.cmd --prefix fx-trading-platform run web:build` | 通过 |
| `npm.cmd --prefix fx-trading-platform run verify:architecture` | 通过 |
| scoped `git diff --check` | 通过；仅 CRLF warning，无 whitespace error |
| live backend smoke | 通过 |
| front-end style STOMP fan-out smoke | 通过 |

## 清理状态

已清理：

- backend smoke 进程
- Vite dev smoke 进程
- `fx-binance-smoke-postgres`
- `fx-binance-smoke-redis`

未处理：

- 工作区中与本任务无关的大量既有修改；按要求未回滚、未整理、未 stage。

## 后续建议

上线前建议在目标部署环境执行 2 到 24 小时 soak：

- 观察 Binance 24h 连接轮换前后的 `connected`、`reconnectAttempt`、`lastMessageAt`
- 观察 `cacheFailureCount`、`candleFailureCount`、`publishFailureCount`、`backfillFailureCount`
- 用真实 Web 前端页面做一次人工 smoke，确认 UI 首屏 quote/order book/trades 与 REST fallback 行为符合预期
