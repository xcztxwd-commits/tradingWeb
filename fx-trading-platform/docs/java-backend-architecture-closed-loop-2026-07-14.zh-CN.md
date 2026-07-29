# Java 后端架构、业务闭环与关键代码深度说明

更新时间：2026-07-14

分析范围：fx-trading-platform/backend

事实来源：当前 Java 源码、application 配置、Flyway V1–V45、Maven 测试、架构校验脚本

定位：当前实现说明，不是目标架构设想；源码和 Flyway 迁移仍是最终事实来源

## 1. 结论先行

当前 Java 后端是一个按业务域分包的 Spring Boot 模块化单体，不是微服务，也不是严格的六边形架构。

它的核心形态可以概括为：

1. Controller 负责 HTTP 参数、当前用户和统一响应包装。
2. Service 负责业务编排、事务、权限后的领域校验和状态流转。
3. Repository 以 MyBatis-Plus Mapper 为主，少量复杂 PostgreSQL upsert 使用 JdbcTemplate 封装在 repository 内。
4. PostgreSQL 是业务状态和审计事实库；Redis 主要承担最新报价缓存。
5. 外部行情通过 ProviderResolver + MarketDataRouter + provider adapter 统一路由。
6. 交易通过 RiskCheckService + ExecutionAdapter + OrderFillService + PositionEngine/SpotSettlementService 形成后端主导闭环。
7. 资金不是一套表，而是“保证金账户 + 资产钱包 + 两套流水”的并存模型。
8. 异步能力主要由 Spring Scheduler 和 STOMP WebSocket 提供，不存在消息队列。
9. 默认 execution.mode 为 disabled；dev profile 才是 demo。broker、fix、lp 都是明确拒绝启动/拒绝成交的占位适配器。
10. 当前测试覆盖较广，但真实 PostgreSQL Testcontainers 集成测试不属于默认 mvn test，且本次因 Docker 不可用被跳过。

从业务成熟度看，它已经是“可运行的多产品 demo 交易后端”，不是生产级真实券商/交易所内核。订单幂等、挂单抢占、条件平仓、资金费/隔夜息结算幂等、审计等基础边界已经存在；钱包并发、WebSocket 订阅授权、调度默认开关、Redis 故障降级、多实例任务协调仍是进入生产前必须补齐的部分。

## 2. 代码规模与事实快照

| 指标 | 当前值 |
| --- | ---: |
| main Java 文件 | 493 |
| main Java 代码行 | 约 28,028 |
| test Java 文件 | 145 |
| test Java 代码行 | 约 20,639 |
| REST Controller 基础路径 | 28 |
| Mapping 方法 | 142 |
| Service 类目录文件 | 116 |
| Repository 类目录文件 | 59 |
| Entity 类目录文件 | 57 |
| DTO 类目录文件 | 124 |
| Transactional 标记 | 120 |
| Scheduled 任务 | 13 |
| Flyway SQL | 45 |
| PostgreSQL 业务表 | 59 |
| 本次默认 Maven 测试 | 612，通过 612，失败 0 |

顶层业务包分布：

| 包 | 文件数 | 主要职责 |
| --- | ---: | --- |
| admin | 193 | 后台 API、RBAC、运营配置、审核、审计编排、通用 feature 页面 |
| market | 86 | 品种、provider 路由、REST 行情、实时 Binance 流、缓存 |
| trading | 51 | 订单、成交、持仓、挂单、保护单、资金费、隔夜息、清算 |
| common | 26 | 安全、统一响应、异常、MyBatis、WebSocket、请求过滤 |
| auth | 25 | 注册、登录、JWT、session、refresh rotation、token 撤销 |
| finance | 17 | 充值/提现申请、支付账户和支付方式领域 |
| wallet | 16 | 多钱包余额、资产流水、兑换、快照、对账 |
| account | 15 | 交易账户、动态账户摘要、账户归属 |
| risk | 15 | 产品分类、交易规则、保证金、PnL、清算规则 |
| execution | 11 | demo/disabled/live 占位执行适配器及启动校验 |
| audit | 10 | 操作审计、请求日志、验证码日志 |
| home | 7 | 首页计数和推广卡 |
| ledger | 6 | 现金/保证金流水的记录与用户可见合并查询 |
| chart | 5 | K 线 API、provider/数据库合并 |
| config | 5 | 系统配置与敏感配置加密 |
| content | 4 | 消息、文章 |

这些包之间存在双向依赖，例如 account 同时依赖 trading/market/risk，trading 依赖 account/market/risk/wallet/ledger，wallet 又依赖 trading/account。由此可见：

- 它是业务域组织良好的模块化单体。
- 它不是依赖方向严格单向的 DDD 分层。
- 它没有通过 Maven module、ArchUnit 或接口模块强制隔离业务域。
- 当前一致性主要依靠单 JVM 内的 Spring 事务和同一个 PostgreSQL，而不是跨服务协议。

## 3. 总体架构

~~~mermaid
flowchart TB
  Web["用户端 apps/web"] --> HTTP["REST /api/**"]
  Admin["后台端 apps/admin"] --> HTTP
  Web --> WS["STOMP /ws"]
  Admin --> WS

  HTTP --> Filters["RequestIdFilter / JwtAuthenticationFilter / RequestLogFilter"]
  Filters --> Controllers["Controller + ApiResponse"]
  Controllers --> Services["领域 Service / 事务编排"]

  Services --> Risk["Risk / Execution / Provider 抽象"]
  Services --> Mappers["MyBatis-Plus Repository"]
  Services --> Redis["Redis 最新报价缓存"]
  Mappers --> PG["PostgreSQL + Flyway"]

  Risk --> Providers["Massive / Binance / OKX"]
  Risk --> Exec["disabled / demo / broker / fix / lp"]

  Providers --> Realtime["Realtime parser / dedup / sink"]
  Realtime --> Redis
  Realtime --> PG
  Realtime --> WS
  Services --> WS
~~~

### 3.1 运行时边界

后端是一个进程、一个 Spring ApplicationContext、一个数据库事务管理器：

- HTTP：Spring MVC。
- 认证与授权：Spring Security。
- WebSocket：Spring STOMP simple broker。
- ORM/数据访问：MyBatis-Plus。
- 数据库迁移：Flyway。
- 缓存：Spring Data Redis。
- 定时任务：Spring Scheduling。
- 外部 HTTP/WebSocket：JDK HTTP 客户端及 provider adapter。
- API 文档：springdoc OpenAPI。
- 监控：Actuator，暴露 health、info、metrics、prometheus；Security 只匿名放行 health/info。

没有使用：

- JPA/Hibernate Repository。
- Kafka/RabbitMQ。
- 独立撮合服务。
- 分布式事务。
- 事件溯源数据库。
- 真实 broker/FIX/LP 执行。

### 3.2 典型调用分层

~~~text
HTTP/STOMP
  -> Controller / ChannelInterceptor
  -> Application/Domain Service
  -> Repository 或 Adapter
  -> PostgreSQL / Redis / 外部 Provider
  -> DTO + ApiResponse 或 STOMP topic
~~~

Controller 基本不直接访问数据库。复杂业务状态变化集中在 Service，Repository 封装查询、条件更新和 upsert。当前 Entity 同时承担数据库映射对象，领域模型没有完全独立于持久化模型。

## 4. 启动、配置与运行模式

### 4.1 启动入口

关键文件：backend/src/main/java/com/fxplatform/FxPlatformApplication.java

启动入口做四件关键事情：

1. SpringBootApplication 启动整个单体。
2. MapperScan 扫描所有业务域 repository。
3. EnableScheduling 全局启用调度器。
4. 从当前目录及父目录读取 .env，并作为 Spring 默认属性注入。

.env 的优先级低于显式系统属性和环境变量，因此部署环境仍可以覆盖本地文件。

### 4.2 启动时发生的主要动作

大致顺序如下：

1. Spring 加载 application.yml 和 profile 覆盖。
2. 数据源、Redis、Security、MyBatis、WebSocket 等 Bean 初始化。
3. Flyway 执行 V1–V45。
4. ProductionSecuritySettingsValidator 在非 dev/test profile 检查 JWT、数据库密码、配置加密密钥。
5. ExecutionModeStartupValidator 检查 live 模式所需配置，并拒绝未就绪的占位 adapter。
6. AdminBootstrapRunner 根据 admin.bootstrap.enabled 决定是否创建/重置管理员。
7. MarketRealtimeLifecycle 根据 market.realtime.enabled 决定是否回填 K 线并连接 Binance WebSocket。
8. Spring Scheduler 开始触发已注册任务。

### 4.3 profile 和 execution 安全边界

| profile | execution.mode | market test data | demo quote | 含义 |
| --- | --- | --- | --- | --- |
| 默认 | disabled | false | false | 不允许成交 |
| dev | demo | true | false，需显式开启 | 本地模拟成交与测试行情 |
| prod | disabled | false | false | 默认不成交 |

ExecutionMode 分为：

| 模式 | 实现 | 当前真实行为 |
| --- | --- | --- |
| disabled | DisabledExecutionAdapter | 下单执行时报 EXECUTION_DISABLED |
| demo | SimulatedExecutionAdapter | 用 fresh quote、固定滑点和费率模拟成交 |
| broker | BrokerExecutionAdapter | 占位，启动校验判定未就绪 |
| fix | FixExecutionAdapter | 占位，启动校验判定未就绪 |
| lp | LpExecutionAdapter | 占位，启动校验判定未就绪 |

这部分符合 demo/live 不混淆要求：生产配置不会因为 adapter 名称存在就伪装真实成交成功。

### 4.4 本地基础设施

infra/docker-compose.yml 提供：

- PostgreSQL 16，数据库 fx_platform，端口 5432。
- Redis 7，端口 6379。

Flyway 是表结构唯一所有者。application.yml 中没有 ddl-auto，pom.xml 也没有 JPA starter。

## 5. 通用横切架构

### 5.1 HTTP 安全链

关键文件：

- common/security/SecurityConfig.java
- common/security/JwtAuthenticationFilter.java
- common/security/JwtService.java
- auth/service/AuthSessionService.java
- common/security/TokenRevocationService.java

授权规则：

| 路径 | 规则 |
| --- | --- |
| 注册、登录、刷新、session 探测 | 匿名 |
| /ws | HTTP 握手匿名 |
| health/info、OpenAPI | 匿名 |
| GET /api/market/**、/api/chart/**、/api/public/** | 匿名 |
| /api/admin/** | ROLE_ADMIN |
| 其他 API | 已认证 |

管理员写操作还会使用方法级 PreAuthorize，例如：

- finance:adjustment:create
- finance:fund-order:approve / reject
- market:symbol:create / update / disable
- market:data-provider:update
- trading:order:cancel
- trading:position:force-close
- user:update / disable / force-logout

JwtAuthenticationFilter 不直接相信 JWT 中的角色。它会：

1. 校验签名、token type、过期时间、jti 和 sid。
2. 检查 revoked_tokens。
3. 检查 user_sessions 是否仍 ACTIVE 且未过期。
4. 重新加载 auth.users，确认用户为 ACTIVE。
5. 通过 AdminAuthorityService 从 RBAC 表重新计算动态 authorities。
6. 把 UserPrincipal 放入 SecurityContext。

因此用户禁用、强制退出、角色权限变化可以在服务端生效，不必等 JWT 自然过期。

### 5.2 统一响应与异常

所有常规 Controller 返回 ApiResponse：

~~~text
success, code, message, data, timestamp
~~~

GlobalExceptionHandler 负责：

- AuthorizationException -> 403。
- BusinessException -> 400。
- Bean Validation -> 400。
- 未知异常 -> 500，并隐藏内部异常详情。

Security 发生在 Controller 之前，因此 SecurityErrorResponseWriter 单独负责 401/403 的 JSON 格式。

### 5.3 请求追踪与审计

RequestIdFilter：

- 接收或生成 X-Request-Id。
- 回写响应头。
- 放入 MDC。

RequestLogFilter：

- 只记录 /api/**。
- 记录 method、path、query、client IP、User-Agent、状态码、耗时和异常消息。
- 日志写入失败不会阻断主请求。

后台领域写操作还会单独写 audit.audit_logs，记录 actor、action、target 和 JSON details。请求日志回答“发生了什么请求”，审计日志回答“谁修改了什么业务对象”。

### 5.4 MyBatis-Plus 约定

FxBaseMapper 提供与旧 Repository 习惯相似的：

- findById
- save
- findAll
- count
- 白名单分页排序

AuditFieldFillHandler 统一填充 createdAt、updatedAt、executedAt、openedAt。

复杂并发条件使用显式 SQL：

- TradingAccountRepository.reserveMarginIfAvailable：原子检查 free margin 并增加 used margin。
- OrderRepository.claimPending：PENDING -> WORKING 抢占。
- OrderRepository.cancelPending：只取消仍为 PENDING 的订单。
- PositionRepository.closeIfOpen：只关闭仍为 OPEN 的持仓。
- RealtimeCandleRepository：PostgreSQL ON CONFLICT upsert。

## 6. 认证与账户初始化闭环

### 6.1 注册闭环

~~~mermaid
sequenceDiagram
  participant C as AuthController
  participant A as AuthService
  participant U as UserRepository
  participant AC as AccountService
  participant L as LedgerService
  participant W as WalletService
  participant S as AuthSessionService

  C->>A: register
  A->>U: 检查 email/phone 并保存 BCrypt 密码
  A->>AC: createDemoAccount
  AC->>L: DEMO_DEPOSIT
  AC->>W: FX_MARGIN/USD 初始余额
  AC->>W: SPOT/USDT 初始余额
  A->>S: 创建 ACTIVE session
  A-->>C: access token + refresh token + authorities
~~~

关键事实：

- 注册和创建 demo 账户在同一个 AuthService 事务链中。
- core.trading_accounts 初始化 balance/equity/free_margin。
- ledger.ledger_entries 写 DEMO_DEPOSIT。
- core.wallet_balances 同时创建 FX_MARGIN/USD 与 SPOT/USDT。
- ledger.asset_ledger_entries 为两次 wallet credit 留痕。
- auth.user_sessions 保存 refresh token hash、当前 access jti、设备/IP/UA、过期时间。

### 6.2 登录、刷新、退出

登录：

1. email 或 phone 查用户。
2. BCrypt 校验密码。
3. 必须是 ACTIVE。
4. 创建新的 session id。
5. access/refresh token 共享 sid，但各自有独立 jti。

刷新：

1. 只接受 refresh 类型 token。
2. 按 sid、userId、旧 refresh hash、ACTIVE、未过期查 session。
3. 生成新 access/refresh。
4. 原子更新 refresh hash 和 access jti。
5. 旧 refresh 无法再次使用。

退出：

- access/refresh jti 写 auth.revoked_tokens。
- 对应 auth.user_sessions 标为 REVOKED。

后台禁用用户和 force logout 都调用 revokeAllUserSessions。

## 7. 行情闭环

### 7.1 行情数据模型

平台把“平台品种”和“供应商品种”分开：

- market.symbols：平台标准 symbol、产品类型、交易规则和展示开关。
- market.data_providers：provider 配置和健康指标。
- market.data_provider_capabilities：QUOTE、CANDLES、ORDER_BOOK、TRADES 等能力。
- market.provider_instruments：provider 同步回来的原始 instrument。
- market.symbol_provider_bindings：平台 symbol 到 provider symbol 的绑定和优先级。

因此前端永远使用平台 symbol，不直接理解 Massive/Binance/OKX 的差异。

### 7.2 Provider 配置闭环

~~~mermaid
flowchart LR
  Admin["后台 Provider API"] --> Sync["AdminMarketDataProviderService"]
  Scheduler["ProviderInstrumentSyncScheduler"] --> Sync
  Sync --> Registry["ProviderRegistry"]
  Registry --> Adapter["Massive/Binance/OKX adapter"]
  Adapter --> External["外部 instrument API"]
  Sync --> Instruments["provider_instruments"]
  Admin --> Binding["symbol_provider_bindings"]
  Binding --> Resolver["ProviderResolver"]
~~~

AdminMarketDataProviderService 负责 provider CRUD、连通性测试、instrument 同步、symbol binding 和展示开关。ProviderRegistry 以 code 注册 Java adapter；重复 code 会直接启动失败。

ProviderResolver 的选择条件依次是：

1. platform symbol 存在且 enabled。
2. binding enabled，按 priority 排序。
3. provider enabled。
4. provider capability enabled。
5. 对应 Java adapter 存在且 configured。
6. adapter supports 当前 capability。

找不到时返回 MARKET_PROVIDER_BINDING_NOT_FOUND，而不是静默换成任意 provider。

### 7.3 REST quote 闭环

~~~mermaid
sequenceDiagram
  participant API as MarketController
  participant Q as QuoteService
  participant R as Redis
  participant Router as MarketDataRouter
  participant Resolver as ProviderResolver
  participant P as Provider Adapter
  participant H as ProviderHealthRecorder

  API->>Q: latestQuote / freshQuote
  Q->>R: GET quote:SYMBOL
  alt 缓存新鲜
    R-->>Q: QuoteResponse
  else 缺失或过期
    Q->>Router: latestQuote
    Router->>Resolver: resolve QUOTE
    Resolver-->>Router: symbol/provider/binding/adapter
    Router->>P: fetchLatestQuote
    P-->>Router: normalized QuoteResponse
    Router->>H: success/failure/latency/staleness
    Q->>R: SET quote:SYMBOL
  end
  Q-->>API: 标准报价
~~~

latestQuote 面向展示；freshQuote 面向交易和风控。freshQuote 会拒绝超过 quote-stale-ms 的报价，避免用过期价成交。

批量 quote 会按 provider 分组调用 fetchLatestQuotes，减少逐品种请求。MarketDataRouter 中另一个按 providerCode/assetClass 查询 snapshots 的重载目前仍返回空 Map，不是完整实现。

### 7.4 实时行情闭环

~~~mermaid
flowchart LR
  Lifecycle["MarketRealtimeLifecycle"] --> Backfill["RealtimeBackfillService"]
  Lifecycle --> Client["BinanceRealtimeClient"]
  Demand["STOMP subscribe/unsubscribe"] --> Registry["RealtimeSubscriptionRegistry"]
  Registry --> Manager["BinanceSubscriptionManager"]
  Manager --> Client
  Client --> Parser["BinanceStreamMessageParser"]
  Parser --> Dedup["RealtimeDeduplicationState"]
  Dedup --> Sink["RealtimeQuoteSink"]
  Sink --> Redis["quote cache"]
  Sink --> Memory["order book/trades/ticker 内存快照"]
  Sink --> Candles["market.candles upsert"]
  Sink --> Topics["STOMP market topics"]
~~~

RealtimeQuoteSink 按事件类型处理：

| 事件 | 结果 |
| --- | --- |
| Quote | 标准化、测试覆盖、Redis cache、报价 topic |
| TickerStats | 内存缓存 24h 统计 |
| OrderBook | 内存快照、order-book topic |
| Trade | 最近 100 条内存队列、trades topic |
| Candle | PostgreSQL upsert |

BinanceRealtimeClient 包含连接代次、防旧连接消息、指数退避、随机 jitter、23h50m 主动轮换和断线 backfill。SubscriptionManager 有最大活跃 symbol、命令限速、延迟退订和动态订阅。

### 7.5 K 线闭环

ChartService：

1. 经 MarketDataRouter 拉 provider candles。
2. 同时读取 market.candles。
3. provider 数据非空时，只把 realtime/backfill/demo 来源的数据库 candle 合并进去。
4. provider binding 缺失或 provider unavailable 时，允许完整数据库 fallback。
5. 其他业务错误不吞掉。

## 8. 交易与风控闭环

### 8.1 下单总链

~~~mermaid
sequenceDiagram
  participant C as TradingController
  participant O as OrderService
  participant R as RiskCheckService
  participant E as ExecutionAdapter
  participant F as OrderFillService
  participant P as PositionEngine/SpotSettlement
  participant L as Wallet/Ledger/Account
  participant V as OrderEventService

  C->>O: createOrder
  O->>O: clientOrderId/idempotencyKey 去重
  O->>R: checkOrder
  R-->>O: required margin/hold
  alt LIMIT 或 STOP
    O->>L: reserve margin 或 lock wallet
    O->>V: ORDER_PENDING
    O-->>C: PENDING
  else MARKET
    O->>E: execute
    E-->>O: ExecutionResult
    O->>F: fill
    F->>P: 产品类型分流
    P->>L: 账户/钱包/流水
    F->>V: FILLED/PARTIALLY_FILLED
    O-->>C: 成交结果
  end
~~~

### 8.2 InstrumentRulesEngine

当前规则引擎不是空壳，已经合并三类规则：

- market.symbols 的平台配置。
- provider_instruments.raw_json 中的 tickSize、stepSize、min/max quantity、min/max notional。
- risk.risk_configs 的 maxLots、maxLeverage 等覆盖。

下单前检查：

- symbol enabled、tradable、order enabled。
- 市价单必须有 quote capability。
- quantity > 0。
- step size、min/max quantity。
- limit/stop price tick size。
- max leverage。
- min/max notional。

### 8.3 RiskCheckService

RiskCheckService 是后端权威风控入口：

1. 查 platform symbol。
2. 调 InstrumentRulesEngine。
3. 取 fresh quote。
4. BUY 使用 ask，SELL 使用 bid。
5. TradingInstrumentClassifier 根据 product_type 分类。
6. 现货检查目标钱包 available。
7. 非现货计算 effective leverage 和 required margin。
8. 通过 AccountSnapshotService 的动态 free margin 判断资金是否足够。

产品类型：

| ProductType | InstrumentKind | 核心口径 |
| --- | --- | --- |
| FX_MARGIN | FOREX | 标准 lot，默认 unitSize 100000 |
| CRYPTO_SPOT | SPOT | base asset 数量，现金交易 |
| LINEAR_PERP | LINEAR_PERPETUAL | 线性合约、quote/margin asset |
| INVERSE_PERP | INVERSE_PERPETUAL | 反向合约、base settlement |

V38 已把 market.symbols.product_type 设为 NOT NULL 并增加枚举约束，避免仅凭 symbol 名称猜产品。

### 8.4 订单状态与幂等

订单同时保留旧 idempotency_key 和 OMS client_order_id。创建时：

1. 先查 userId + accountId + clientOrderId。
2. 再查 userId + idempotencyKey。
3. 数据库唯一索引作为最终并发兜底。
4. 如果并发 insert 触发 DataIntegrityViolationException，重新查已有订单并返回。

order_events 保存每次状态变化，OrderEventService 同时向 /topic/trading/accounts/{accountId}/events 发布事件。

主要状态包括 RECEIVED、VALIDATING、ACCEPTED、PENDING、WORKING、PARTIALLY_FILLED、FILLED、REJECTED、CANCEL_PENDING、CANCELED、FAILED。

### 8.5 市价单成交

SimulatedExecutionAdapter：

- 使用 fresh quote。
- BUY 在 ask 上加固定 0.01% 滑点，SELL 在 bid 下减。
- 默认全量成交。
- 费率固定 0.1%。
- 反向永续 fee 可用 settlement asset 表示。

OrderFillService 是成交写入总入口：

1. 更新 order 成交状态、价格、数量、fee、slippage。
2. 写 trading.trades。
3. 加载 symbol 和 product profile。
4. SPOT 进入 SpotSettlementService。
5. FX/linear perp/inverse perp 进入 PositionEngine。
6. 写/调整账户保证金。
7. 收取交易费并写 ledger。

### 8.6 挂单、改单和撤单

LIMIT/STOP 创建时不调用 ExecutionAdapter：

- SPOT：WalletService.lockAvailable。
- FX/永续：reserveMarginIfAvailable，增加 used_margin。
- order 进入 PENDING。

PendingOrderExecutionService 默认关闭。开启后：

1. 扫描 PENDING。
2. 用 fresh quote 判断 LIMIT/STOP 触价方向。
3. claimPending 抢占为 WORKING。
4. 复用 OrderFillService 成交。

用户撤单只允许 PENDING，并通过 cancelPending 条件更新防重复。管理员撤单支持更多非终态，但仍释放 wallet/margin hold 并写订单事件和审计。

改单只允许 PENDING。它先把旧 hold 从风控视图中还原，重新计算新 hold，再按差额锁定或释放。

### 8.7 净持仓引擎

PositionEngine 对 FX 和永续使用“同账户 + 同 symbol 的净持仓”：

~~~mermaid
flowchart TB
  Fill["新 fill"] --> Existing{"已有 OPEN 净持仓?"}
  Existing -->|否| Open["开仓"]
  Existing -->|是| Side{"同方向?"}
  Side -->|是| Increase["加仓：重算均价和保证金"]
  Side -->|否| Qty{"fill 数量与旧仓比较"}
  Qty -->|小于| Reduce["减仓：比例释放保证金、确认 PnL"]
  Qty -->|等于| Close["全平"]
  Qty -->|大于| Reverse["反手：平旧仓，再开剩余仓"]
~~~

线性产品用数量加权均价；反向永续用基于 USD notional 的调和均价。永续持仓会保存 notional、initial margin、maintenance margin、mark price、settlement asset、margin asset。

### 8.8 现货结算

SpotSettlementService：

| 场景 | 扣减 | 增加 | fee |
| --- | --- | --- | --- |
| BUY | quote locked/available | base available | 从 base 扣 |
| SELL | base locked/available | quote available | 从 quote 扣 |

每一步通过 WalletService 写 asset ledger，并以 trade id + operation type 幂等。剩余挂单 hold 会释放。

SpotPositionService 维护 trading.spot_positions：

- BUY 增加数量并重算 average cost。
- SELL 降低数量并累计 realized PnL 与 fee cost。
- PositionService 把 spot positions 合并进统一持仓查询 DTO。

### 8.9 主动平仓和保护单

PositionService.closePosition：

1. 校验账户归属。
2. 只允许 OPEN。
3. fresh quote：多仓用 bid，空仓用 ask。
4. 计算 realized PnL。
5. closeIfOpen 条件更新抢占平仓权。
6. balance += PnL。
7. used_margin -= marginHeld。
8. 重算 free margin。
9. 写 MARGIN_RELEASE 和 TRADE_PNL。
10. 系统强平额外写 FORCED_CLOSE。

ProtectiveOrderExecutionService 默认关闭。开启后扫描 OPEN 仓位，止损/止盈只负责判断，实际结算复用 PositionService，避免两套平仓资金逻辑。

### 8.10 强平、资金费、隔夜息

LiquidationService：

- FX：margin level <= stop-out level。
- 永续：equity <= maintenance margin + liquidation fee buffer。
- fresh quote 重算永续 mark、PnL、notional、maintenance margin。
- 选择风险贡献最高的仓位循环平仓，直到不再 breach。
- 永续额外收 liquidation fee。

FundingService：

- 只处理永续。
- 按 position_id + funding_time 唯一。
- 先 insertIfAbsent settlement，再改账户/持仓和写 ledger。

ForexFinancingService：

- 只处理 FX。
- 按 position_id + settlement_date 唯一。
- 支持周三三倍天数等 rollover 口径。
- 必要时通过 ForexConversionService 转成账户币种。

三类 Scheduler 默认都关闭：

- trading.liquidation.enabled
- trading.funding.enabled
- trading.fx-financing.enabled

## 9. 账户、钱包和账本闭环

### 9.1 四个资金事实

当前必须同时理解四类数据：

| 数据 | 表 | 含义 |
| --- | --- | --- |
| 保证金账户 | core.trading_accounts | balance/equity/used/free margin，FX 和永续主口径 |
| 资产钱包 | core.wallet_balances | wallet_type + asset 的 total/available/locked |
| 现金/保证金流水 | ledger.ledger_entries | demo deposit、margin hold/release、PnL、fee、funding、financing、admin adjustment |
| 资产流水 | ledger.asset_ledger_entries | spot/perp wallet 的 credit/debit/lock/release/fee/conversion |

这不是简单重复：

- trading_accounts 是保证金账户聚合。
- wallet_balances 是多资产库存。
- ledger_entries 解释账户 balance 的变化。
- asset_ledger_entries 解释 wallet total/available/locked 的变化。

### 9.2 WalletService 原子业务语义

| 方法语义 | total | available | locked |
| --- | ---: | ---: | ---: |
| creditAvailable | +amount | +amount | 不变 |
| debitAvailable | -amount | -amount | 不变 |
| lockAvailable | 不变 | -amount | +amount |
| releaseLocked | 不变 | +amount | -amount |
| debitLocked | -amount | 不变 | -amount |

数据库约束 total = available + locked，且三者非负。

每次 wallet 变化与 asset ledger 写入位于同一事务。V45 增加 operation_type 和业务唯一索引：

~~~text
account_id + wallet_type + asset + reference_type + reference_id + operation_type
~~~

### 9.3 账户摘要

AccountSnapshotService 不直接相信 trading_accounts.equity：

1. 查所有 OPEN positions。
2. 每仓获取 fresh quote。
3. 重新计算 floating PnL。
4. 永续按 mark price 重算 position value 和 maintenance margin。
5. equity = balance + open floating PnL。
6. used margin 优先取持仓 margin 汇总。
7. free margin = equity - used margin。
8. 若持久化 usedMargin 与仓位汇总不一致，返回 warning。

因此 Account API 返回的是读时动态快照，而不是简单 Entity 映射。

### 9.4 对账与日快照

WalletReconciliationService 检查：

- wallet total = available + locked。
- asset ledger 总增量 = wallet total。
- 活跃 spot order hold = wallet locked。
- open position margin + pending margin hold = account.usedMargin。
- cash ledger 可影响余额的增量 = account.balance。

WalletSnapshotService 写：

- core.wallet_daily_snapshots。
- core.account_daily_snapshots。

两张快照表按 account/wallet/asset/date upsert。

### 9.5 资产兑换

AssetConversionService 当前只支持 demo USDT -> USD，汇率 1:1：

1. 源 wallet debit，operation CONVERT_OUT。
2. 目标 wallet credit，operation CONVERT_IN。
3. 同一个 conversionId 作为幂等 reference。

它没有连接真实 FX rate，也不支持反向或任意币对。

## 10. 充值、提现与后台闭环

### 10.1 用户资金申请

FundOrderController：

1. 用户提交 RECHARGE 或 WITHDRAWAL。
2. FundOrderService 校验 account 归属。
3. 写 finance.fund_orders，状态 PENDING_REVIEW。

### 10.2 后台审核

AdminFundOrderService：

1. 管理员必须有 approve/reject authority。
2. approve 还要求确认文本。
3. 订单必须仍为 pending review。
4. approve 调 AdminFinanceCommandService.deposit/withdraw。
5. 资金操作使用 fund-order-{id} 作为 idempotency key。
6. finance.admin_fund_operations 保存 before/after balance。
7. 更新 core.trading_accounts。
8. LedgerService 写 ADMIN_ADJUSTMENT。
9. fund order 关联 fund_operation_id。
10. 写 audit log。

当前这条闭环只改变保证金账户和 cash ledger，不会同步修改任意 wallet balance/asset ledger。它应被理解为“保证金账户入出金审核”，不是“给某个现货/合约钱包充值”。如果产品希望审核后资金直接进入 SPOT/USDT 或其他钱包，需要明确 walletType/asset 并新增同事务资产流水。

### 10.3 后台架构

后台代码分两类：

1. 明确领域 API：用户、财务、订单、持仓、行情、provider、RBAC、内容、配置、日志。
2. 通用 Feature 页面：AdminFeatureCatalogService 定义页面结构，AdminFeatureOperationService 编排动作，AdminFeatureActionHandler 注册表处理领域副作用，feature_records 保存通用页面记录。

后台写操作的共同模式：

~~~text
Controller ROLE_ADMIN / authority
  -> CommandService
  -> 领域校验 + confirmation
  -> Entity/领域 Service
  -> AuditLogService
  -> DTO
~~~

后台强平复用 PositionService；后台撤单复用 wallet/margin release 口径；没有在 Controller 中复制交易算法。

## 11. WebSocket 与事件

### 11.1 broker 配置

MarketWebSocketConfig：

- endpoint：/ws。
- application prefix：/app。
- simple broker：/topic、/queue。
- user prefix：/user。
- CONNECT 时由 WebSocketJwtChannelInterceptor 尝试解析 Bearer token。

### 11.2 topic

| topic | 内容 |
| --- | --- |
| /topic/market/quotes/{symbol} | 标准报价 |
| /topic/market/order-book/{symbol} | 盘口 |
| /topic/market/trades/{symbol} | 最近成交 |
| /topic/trading/accounts/{accountId}/events | 订单状态事件 |

行情订阅会驱动 RealtimeSubscriptionRegistry 的引用计数，进而动态 activate/deactivate Binance streams。

## 12. 数据库设计

### 12.1 schema 与表数量

| schema | 表数 | 主题 |
| --- | ---: | --- |
| auth | 6 | 用户、设备、资料、KYC、撤销 token、session |
| core | 6 | 交易账户、钱包、日快照、首页数据 |
| market | 10 | symbol、candle、provider、binding、收藏、行情管理 |
| trading | 10 | order、trade、position、spot position、funding、financing |
| ledger | 2 | cash ledger、asset ledger |
| risk | 1 | 风控配置 |
| finance | 4 | fund order、fund operation、支付方式/账户 |
| admin | 13 | RBAC、通用 feature、任务、列偏好、备注 |
| audit | 3 | 业务审计、请求、验证码 |
| content | 2 | 消息、文章 |
| config | 2 | 字典、系统设置 |

总计 59 张表。

### 12.2 核心关系

~~~mermaid
erDiagram
  USERS ||--o{ USER_SESSIONS : has
  USERS ||--o{ TRADING_ACCOUNTS : owns
  TRADING_ACCOUNTS ||--o{ ORDERS : places
  ORDERS ||--o{ TRADES : fills
  ORDERS ||--o{ ORDER_EVENTS : transitions
  TRADING_ACCOUNTS ||--o{ POSITIONS : holds
  TRADING_ACCOUNTS ||--o{ SPOT_POSITIONS : holds
  TRADING_ACCOUNTS ||--o{ WALLET_BALANCES : has
  TRADING_ACCOUNTS ||--o{ LEDGER_ENTRIES : explains
  TRADING_ACCOUNTS ||--o{ ASSET_LEDGER_ENTRIES : explains
  SYMBOLS ||--o{ SYMBOL_PROVIDER_BINDINGS : routes
  DATA_PROVIDERS ||--o{ SYMBOL_PROVIDER_BINDINGS : supplies
  DATA_PROVIDERS ||--o{ PROVIDER_INSTRUMENTS : syncs
  POSITIONS ||--o{ FUNDING_SETTLEMENTS : settles
  POSITIONS ||--o{ FX_FINANCING_SETTLEMENTS : settles
~~~

### 12.3 Flyway 演进主线

| 版本 | 主题 |
| --- | --- |
| V1–V9 | schema、用户、账户、行情、交易、账本、风控、审计、种子 |
| V10–V12 | position marginHeld、OMS 字段、order events |
| V13–V22 | 后台用户/行情/财务/内容/RBAC/feature/table tools |
| V23–V28 | fee/slippage、请求 ID、幂等、杠杆、crypto provider、收藏 |
| V29–V32 | 动态 provider、OKX、首页、图标 |
| V33–V40 | 多资产 wallet、永续 margin/funding、FX financing、product type、wallet type |
| V41–V42 | token revocation、server-side session |
| V43–V44 | provider health metrics，两份内容相同且均为 IF NOT EXISTS |
| V45 | ledger 幂等、账户/钱包快照、spot positions |

### 12.4 主要数据库幂等边界

| 业务 | 唯一边界 |
| --- | --- |
| 订单 | user + account + client_order_id；user + idempotency_key |
| 钱包操作 | account + wallet + asset + reference + operation |
| cash ledger 特定操作 | account + reference + operation |
| funding | position + funding_time |
| FX financing | position + settlement_date |
| admin fund operation | account + type + idempotency_key |
| daily snapshot | account + wallet + asset + date |

## 13. 关键代码索引

### 13.1 启动与基础设施

| 文件 | 作用 |
| --- | --- |
| FxPlatformApplication.java | 启动、调度、Mapper 扫描、.env |
| application.yml | 默认安全模式、数据源、Redis、market、execution、trading |
| application-dev.yml | demo execution、测试行情、dev 管理员引导 |
| application-prod.yml | 关闭 demo/test，execution disabled |
| SecurityConfig.java | HTTP 安全、CORS、无状态会话 |
| JwtAuthenticationFilter.java | token + session + user + RBAC 权限加载 |
| MarketWebSocketConfig.java | STOMP broker 和 endpoint |
| FxBaseMapper.java | MyBatis-Plus 通用 Mapper 语义 |
| AuditFieldFillHandler.java | 审计时间字段自动填充 |
| GlobalExceptionHandler.java | 统一业务异常 |

### 13.2 认证、账户、资金

| 文件 | 作用 |
| --- | --- |
| AuthService.java | 注册、登录、刷新、退出、token rotation |
| AuthSessionService.java | session 创建、刷新、撤销和强制退出 |
| AccountService.java | demo 账户创建、归属校验、钱包/流水入口 |
| AccountSnapshotService.java | fresh quote 动态账户摘要 |
| WalletService.java | wallet 原子业务动作与 asset ledger |
| WalletReconciliationService.java | wallet/account/ledger/order/position 对账 |
| WalletSnapshotService.java | 账户和钱包日快照 |
| LedgerService.java | cash ledger 与用户可见合并流水 |
| AdminFinanceCommandService.java | 后台保证金账户入出金/调整 |
| AdminFundOrderService.java | 资金申请审核闭环 |

### 13.3 行情

| 文件 | 作用 |
| --- | --- |
| ProviderRegistry.java | provider code -> Java adapter |
| ProviderResolver.java | DB binding/capability/priority 解析 |
| MarketDataRouter.java | quote/candle/book/trade 统一路由 |
| QuoteService.java | Redis、fresh/stale、demo fallback |
| ChartService.java | provider candle + realtime DB merge |
| MarketRealtimeLifecycle.java | realtime 启动和冲突检查 |
| BinanceRealtimeClient.java | 连接、重连、轮换、解析入口 |
| BinanceSubscriptionManager.java | 动态 stream、限流、延迟退订 |
| RealtimeQuoteSink.java | cache、K 线、内存快照、STOMP |
| AdminMarketDataProviderService.java | provider 管理、同步、binding |

### 13.4 交易、风控、执行

| 文件 | 作用 |
| --- | --- |
| InstrumentRulesEngine.java | 统一交易规则与 provider/risk 覆盖 |
| RiskCheckService.java | 下单前后端权威风控 |
| TradingInstrumentClassifier.java | product type -> instrument profile |
| TradingAlgorithmEngine.java | margin/PnL/fee/liquidation 数学核心 |
| ExecutionAdapter.java | 执行边界 |
| ExecutionModeStartupValidator.java | 防 live 占位适配器假启动 |
| OrderService.java | 下单、改撤单、hold、订单幂等 |
| OrderFillService.java | 成交写入和产品分流总入口 |
| PositionEngine.java | 加仓/减仓/反手净持仓状态机 |
| SpotSettlementService.java | 现货钱包结算 |
| PositionService.java | 持仓查询、保护参数、主动/系统平仓 |
| PendingOrderExecutionService.java | 挂单触价扫描 |
| ProtectiveOrderExecutionService.java | TP/SL 扫描 |
| LiquidationService.java | FX/永续清算 |
| FundingService.java | 永续资金费结算 |
| ForexFinancingService.java | FX 隔夜息结算 |

## 14. 一致性、并发与恢复能力

### 14.1 已有保护

当前已有的可靠性设计：

- 业务写操作大量使用 Spring Transactional。
- 订单有应用层查询 + 数据库唯一键双重幂等。
- 保证金预占使用带余额条件的单 SQL。
- 挂单执行用 PENDING -> WORKING 条件抢占。
- 平仓用 OPEN 条件抢占。
- wallet/cash ledger 有业务 operation 唯一索引。
- funding/financing 先插 settlement，再应用余额。
- provider 失败和 quote 延迟写健康指标。
- realtime sink 的 cache/candle/publish 失败相互隔离并计数。
- 后台高风险动作有 authority、确认文本、幂等和 audit。

### 14.2 事务边界的真实含义

同一个 Service 方法内的 PostgreSQL 写入可以一起回滚，例如：

- order + trade + position + account + ledger。
- wallet balance + asset ledger。
- fund order review + admin operation + account + ledger + audit。

但以下动作不参与数据库事务：

- 外部 provider HTTP/WebSocket。
- Redis。
- STOMP publish。
- Scheduler 的进程内状态。

因此当前没有 outbox。数据库已提交但 STOMP 发布失败时，客户端必须通过 REST 重读最终状态。

## 15. 当前风险、缺口和优先级

以下结论来自当前代码，不等于本次已修复。

### P0：交易账户 WebSocket topic 缺少订阅级授权

证据：

- HTTP /ws 握手 permitAll。
- WebSocketJwtChannelInterceptor 对缺失/非法 token 只是不设置 Principal，不拒绝 CONNECT。
- 没有 Spring Messaging 的 destination authorization 配置。
- TradingWsPublisher 向 /topic/trading/accounts/{accountId}/events 广播。

结果：知道 accountId 的匿名或其他用户可能订阅账户交易事件。行情 topic 可以公开，但 trading account topic 必须按 principal 与 account ownership 授权，或改为 /user/queue。

### P1：WalletService 存在并发丢更新窗口

WalletService 当前是：

~~~text
select balance -> Java 加减 -> updateById -> insert ledger
~~~

wallet_balances 没有 version 字段，查询也没有 FOR UPDATE。业务唯一索引能阻止同一 operation 重复流水，却不能阻止两个不同 operation 同时读取旧余额并互相覆盖。生产资金路径应使用：

- 行锁 SELECT FOR UPDATE；或
- 带 available/locked 条件的原子 UPDATE；或
- 乐观锁 version + 重试。

### P1：部分 Scheduler 默认开启或无条件注册

仓库规则要求 scheduler 默认关闭，但当前：

- ProviderInstrumentSyncScheduler：matchIfMissing=true，application.yml 默认 enabled=true。
- WalletDailySnapshotJob：无 ConditionalOnProperty。
- WalletReconciliationJob：无 ConditionalOnProperty。
- HomeCountersService.growUsersCounter：无开关，并且每秒随机增加用户计数。

MarketTestDataService 和 QuoteBroadcastService 虽然每秒触发，但方法内部默认 no-op。Wallet 和首页任务会真实访问/修改数据库。测试/多实例环境存在意外跑批风险。

### P1：资金模型存在产品语义断点

AdminFinanceCommandService 只改 trading_accounts + cash ledger，不改 wallet。注册却同时初始化 FX_MARGIN/USD 和 SPOT/USDT。结果是：

- 后台入金后 account summary 增加。
- spot wallet 不增加。
- 用户不能直接用这笔资金买 spot。

这可能是有意区分“保证金账户资金”和“资产钱包资金”，但 API 请求没有 walletType/asset，产品语义不够明确。需要先明确目标，再决定是否跨钱包联动。

### P1：Redis 对 fresh quote 路径接近硬依赖

QuoteService.readCached 和 cache 没有捕获 Redis 连接异常。即使 provider 正常，Redis 故障也可能让 latestQuote/freshQuote 直接失败，从而阻断风控和下单。MarketDataRouter 的批量 cache 写有降级，但单 quote 主路径没有对称降级。

### P1：净持仓并发缺少数据库唯一约束

PositionEngine 先 findOpenNetPosition，再决定开仓/加减仓。数据库没有约束“同账户同 symbol 只能有一个 OPEN 净持仓”，也没有对 position 行加锁。两个不同订单并发成交时可能各自看不到对方并开出两个 OPEN 净仓。

现有 OrderPositionConcurrencyTest 保护了若干条件更新语义，但不能替代真实 PostgreSQL 并发约束测试。

### P2：现货持仓查询与统一 close API 不完全对称

PositionService.openPositions 会把 spot_positions 合并进持仓列表；但 closePosition 只查 trading.positions。现货退出必须通过反向 SELL order，而不能拿 spot position id 调统一 close endpoint。前端/契约需要明确，不应让用户误以为所有 PositionResponse 都支持同一 close。

### P2：查询和对账存在 N+1/全表扫描

- AccountSnapshotService 对每个持仓拉 fresh quote 和 symbol。
- PositionService 对每个 OPEN position 拉 quote。
- WalletReconciliationService 对所有 wallet/account 全表遍历，并逐项查 ledger/order/position。
- 多个 Scheduler 直接 findAll 或 findByStatus，没有分页。

小规模 demo 可接受，数据量增大后会放大数据库和外部行情压力。

### P2：行情状态接口不是实际健康聚合

QuoteService.status 当前硬编码 available=true、provider-router 等描述，没有汇总：

- Redis 状态。
- provider last success/failure。
- quote staleness。
- realtime connection。

后台有 provider health 和 realtime status，但公共 /api/market/status 不能代表真实可交易状态。

### P2：多实例调度协调不足

Scheduler 没有 ShedLock、DB lease 或 leader election。部分任务靠业务唯一键避免重复结算，但：

- provider sync 会多实例重复外部请求。
- home counter 会按实例数倍增。
- wallet snapshot 会重复 upsert。
- reconciliation 会重复全表扫描。

### P3：迁移与注释维护债

- V43 和 V44 内容相同，依赖 IF NOT EXISTS 保证无害，但时间线噪声较大。
- AdminUserService 的 forceLogout 注释仍称“尚未引入 token 黑名单”，与 V41/V42 和当前实现不符。
- 旧文档 current-project-full-architecture-business-logic-cn.md 更新时间为 2026-06-16，部分结论已过期。

## 16. 建议的后续治理顺序

1. 为 trading WebSocket topic 增加 destination ownership 授权，补匿名和跨账户订阅测试。
2. 把 WalletService 改成行锁或原子 UPDATE，并用真实 PostgreSQL 做并发测试。
3. 所有会访问/修改数据库的 Scheduler 增加 enabled=false 默认值；多实例任务增加锁。
4. 明确 margin account 与 asset wallet 的产品资金流，给 admin deposit/withdraw 增加 walletType/asset 或明确命名。
5. 给 Redis 读写增加 fail-open provider fallback，并暴露 Redis/provider/realtime 综合健康。
6. 给 OPEN 净持仓建立数据库级并发策略。
7. 批量化 account snapshot、position quote 和 reconciliation 查询。
8. 把 PostgresDatabaseIT 纳入 CI 的 failsafe/integration-test 阶段，并保证 Docker/Testcontainers 可用。

## 17. 测试与验证

### 17.1 本次执行

~~~powershell
cd fx-trading-platform/backend
mvn test
~~~

结果：

~~~text
Tests run: 612, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
~~~

默认 Surefire 不会按命名规则自动包含 PostgresDatabaseIT，因此额外显式执行：

~~~powershell
cd fx-trading-platform/backend
mvn "-Dtest=PostgresDatabaseIT" test
~~~

结果：

~~~text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 3
BUILD SUCCESS
原因：Docker/Testcontainers 环境不可用，真实 PostgreSQL 测试未实际运行
~~~

架构校验：

~~~powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
~~~

结果：

~~~text
Architecture verification passed.
~~~

### 17.2 测试覆盖结构

当前 145 个测试文件重点覆盖：

- market：36 个文件，provider、router、realtime、cache、backfill、controller。
- admin：33 个文件，RBAC、财务、内容、provider、feature、审计。
- trading：17 个文件，order、fill、position、pending、protection、funding、financing、liquidation。
- audit：13 个文件，包含 Step01–Step10 全交易审计链。
- risk：8 个文件，规则、分类、margin、PnL、永续算法。
- account/auth/wallet/ledger：账户摘要、session 生命周期、钱包动作、对账与流水。
- ArchitectureRulesTest：MyBatis/Flyway、no JPA、execution/risk 边界、默认关闭的高级交易任务等。

测试通过说明当前代码与这些测试定义一致，不代表前述未覆盖的并发、WebSocket destination 授权、多实例调度和真实数据库链路已经安全。

## 18. 阅读顺序

第一次接手建议按以下顺序读：

1. application.yml、application-dev.yml、application-prod.yml。
2. FxPlatformApplication、SecurityConfig、JwtAuthenticationFilter。
3. AuthService、AccountService、AccountSnapshotService。
4. ProviderResolver、MarketDataRouter、QuoteService、RealtimeQuoteSink。
5. InstrumentRulesEngine、RiskCheckService。
6. OrderService、OrderFillService。
7. PositionEngine、SpotSettlementService、PositionService。
8. WalletService、LedgerService、WalletReconciliationService。
9. AdminFinanceCommandService、AdminFundOrderService、AdminTradingCommandService。
10. V29、V33–V45 Flyway 迁移。
11. ArchitectureRulesTest 和 audit/Step01–Step10。

如果只想抓住系统最核心的一条线，请读：

~~~text
TradingController
  -> OrderService
  -> RiskCheckService
  -> ExecutionAdapter
  -> OrderFillService
  -> PositionEngine / SpotSettlementService
  -> TradingAccount / Wallet
  -> Ledger
  -> OrderEvent / REST query
~~~

这条线就是当前 Java 后端最重要的业务闭环。
