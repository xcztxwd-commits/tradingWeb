# Binance 实时行情升级可行性分析

日期：2026-06-17

## 结论

方案可行，且适合按“后端统一接入 Binance、公网连接只在后端、前端只连接本系统 `/ws`、REST 保留首屏和断线兜底”的路线执行。

本次实现已经完成代码链路和运行态验证：后端可在 `market.realtime.enabled=true` 时连接 Binance Spot WebSocket，REST quote 能读到 `source=binance-ws-bookTicker` 的新鲜行情；两个前端式 STOMP 客户端同时订阅 `BTCUSDT` 时，后端 Binance 侧仍只维护一组 symbol stream。

## 外部约束

实现按 Binance 官方约束设计：

- Spot WebSocket Streams：stream name 使用 lowercase symbol，combined stream 使用 `stream/data` 包装，连接最长约 24 小时，服务端 ping 后客户端需 pong。
  <https://developers.binance.com/docs/binance-spot-api-docs/web-socket-streams>
- Spot Market Data REST：Kline 使用 `GET /api/v3/klines`，open time 是 candle 唯一标识。
  <https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints>

## 架构判断

核心链路如下：

1. `BinanceRealtimeClient` 负责 Binance WebSocket 连接、ping/pong、reconnect、`serverShutdown`、3h50m 主动轮换和控制消息发送。
2. `BinanceSubscriptionManager` 负责 active symbol、desired stream、订阅/退订和控制消息限流。
3. `BinanceStreamMessageParser` 把 `bookTicker`、`ticker`、`aggTrade`、`depth`、`kline` 转成统一 `RealtimeMarketEvent`。
4. `RealtimeDeduplicationState` 过滤重复或倒退事件。
5. `RealtimeQuoteSink` 是 quote cache、order book/trade snapshot、candle DB、WebSocket fan-out 的统一入口。
6. `RealtimeBackfillService` 在启动、恢复和 test-control 结束后用 REST candle 补齐历史窗口。
7. `RealtimeSubscriptionRegistry` 把前端 STOMP topic demand 汇总成 symbol demand，避免每个浏览器 tab 直接放大 Binance 连接。
8. `MarketRealtimeLifecycle` 只在显式启用 realtime 时启动，并在 demo test data 冲突时 fail-fast。
9. `MarketTestControlService` 是默认关闭的测试覆盖层，只用于可控 smoke，不污染 Binance realtime 主逻辑。

## 关键设计取舍

- 后端单一 Binance 接入优先于前端直连 Binance：便于限流、观测、回补和故障隔离，也避免多个浏览器 tab 消耗 Binance 连接。
- REST fallback 保留：quote、order book、trades、candles 不依赖 WebSocket 首包才能渲染。
- realtime/backfill/demo/test-control 统一经过 `RealtimeQuoteSink`，减少缓存、广播、写库路径分叉。
- Binance `bookTicker` 没有业务事件时间时，quote timestamp 使用接收时间，避免被 `QuoteService.freshQuote(...)` 误判 stale。
- test-control 覆盖只存内存 TTL，不写数据库；POST override 后立即发布 deterministic quote，DELETE 或 TTL 结束后触发 backfill 恢复真实行情连续性。
- bootstrap symbols 先进入 subscription manager，再启动 Binance client；配置超限会在外部连接前 fail-fast，连接成功后由 `onConnected()` replay desired streams。

## 可行性证据

- 单元/集成层：`mvn.cmd test` 通过 608 tests，覆盖 parser、subscription、client reconnect/jitter、lifecycle、sink、backfill、controller 和现有交易回归。
- 前端层：`web:test` 通过 395 tests，包含 `marketStream` 不直连 Binance WS、同页多 handler 复用 `/ws` session、symbol normalization。
- 构建层：`web:build` 通过，`verify:architecture` 通过。
- 运行态：临时 Docker Postgres/Redis + backend `SERVER_PORT=18090` smoke 通过；`/api/admin/market/realtime/status` 返回 `connected=true`、`activeSymbols=["BTCUSDT"]`、`desiredStreamCount=11`。
- live quote：`scripts/smoke-binance-realtime.mjs` 验证 REST quote 返回 `source=binance-ws-bookTicker`，bid/ask/mid 均为正数。
- fan-out：两个前端式 `@stomp/stompjs` 客户端同时连接 `ws://127.0.0.1:18090/ws` 并订阅 quote/order-book/trades，两个客户端均收到 quote，后端 Binance stream 数仍保持 `11`；断开后 `activeSymbols=[]`、`desiredStreamCount=0`。

## 残余风险

- Binance 公网可达性仍取决于部署环境网络；代码已覆盖 reconnect、jitter、主动轮换和 backfill，但生产前仍需要目标环境长时间 soak。
- 本次内置浏览器打开本地 Vite URL 被客户端拦截，无法完成真实 DOM 视觉浏览器 smoke；已用项目同款 `@stomp/stompjs` 做协议级双客户端 fan-out 验证。
- 工作区存在大量与本任务无关的既有修改；本次没有回滚或清理这些文件。
