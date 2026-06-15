# 基础交易框架建设步骤文档

## 1. 文档目标

本文档用于指导 `fx-trading-platform` 建设一版“基础可用、后续可扩展”的交易后端框架。目标不是一次性做完整专业交易平台，而是先建立一条可靠的基础交易闭环：

```text
登录用户
  -> 账户
  -> 行情报价
  -> 下单
  -> 风控
  -> 模拟执行
  -> 成交
  -> 仓位
  -> 资金流水
  -> 查询展示
```

第一版必须支持：

- 市价单 `MARKET`。
- 限价单 `LIMIT`。
- 撤单 `CANCEL`。
- 平仓 `CLOSE_POSITION`。
- 订单查询。
- 持仓查询。
- 账户摘要查询。
- 资金流水查询。
- 模拟执行立即成交市价单。
- 限价单只进入 `WORKING` 状态，不自动触发成交。
- 撤单后释放冻结资金或保证金。

第一版暂不实现：

- 止盈止损。
- 移动止盈止损。
- OCO。
- 计划委托。
- TWAP。
- 冰山策略。
- 大单拆分。
- 外部 Broker。
- FIX。
- 私有 WebSocket 交易事件流。
- Outbox。
- 强平系统。
- 复杂对账。

这些暂不实现的功能不是不要，而是必须在基础框架稳定后，按扩展点逐步接入。

## 2. 当前项目基础

当前 `fx-trading-platform` 已经有一个可工作的交易后端雏形：

```text
fx-trading-platform/backend
```

已有模块：

- `auth`: 登录、注册、JWT。
- `account`: 交易账户和账户摘要。
- `market`: 交易品种、报价、盘口、近期成交、行情推送。
- `chart`: K 线。
- `trading`: 订单、成交、持仓。
- `risk`: 保证金和风控检查。
- `execution`: 执行适配器。
- `ledger`: 资金流水。
- `admin`: 后台查询。
- `audit`: 审计日志。

已有前端交易终端位于：

```text
fx-trading-platform/apps/web/src/pages/trading
```

前端当前已经能轮询：

- 账户摘要。
- 订单列表。
- 持仓列表。
- 资金流水。

这说明第一版后端框架不需要重新发明整个系统，只需要把现有交易链路从 demo 状态整理成可扩展的基础 OMS。

## 3. 设计原则

### 3.1 先做稳定闭环，再做高级功能

交易系统最怕在基础状态机不稳时叠加高级单。止盈止损、OCO、TWAP、冰山策略都依赖普通订单、成交、仓位、资金流水的正确性。

因此第一版只做：

```text
MARKET
LIMIT
CANCEL
CLOSE_POSITION
QUERY
```

原因：

- 市价单验证“下单 -> 执行 -> 成交 -> 仓位 -> 流水”闭环。
- 限价单验证“下单 -> 风控 -> 冻结 -> 当前委托”闭环。
- 撤单验证“当前委托 -> 释放冻结 -> 历史委托”闭环。
- 平仓验证“持仓 -> 反向执行 -> 释放保证金 -> 盈亏流水”闭环。

只要这四条路径稳定，后续高级订单都可以复用同一套底座。

### 3.2 模块化单体，不拆微服务

当前阶段推荐：

```text
Spring Boot 模块化单体
```

不推荐：

```text
微服务拆分
```

原因：

- 订单、账户、持仓、流水需要强一致性。
- 单体事务边界清晰，方便保证一次成交内的数据一致。
- 当前项目规模和团队协作阶段还不需要服务治理。
- 微服务会提前引入消息可靠性、分布式事务、链路追踪和部署复杂度。

模块化单体不是把所有逻辑写在一个类里，而是在同一个后端应用内按领域隔离：

```text
controller
application
domain service
repository
adapter
```

### 3.3 Controller 保持薄

Controller 只做：

- 接收请求。
- 参数基础校验。
- 获取当前用户身份。
- 调用 Application Service。
- 返回 DTO。

Controller 不做：

- 风控判断。
- 资金计算。
- 仓位计算。
- 订单状态流转。
- 成交写入。

原因：

- Controller 逻辑难复用。
- 后续 CLI、后台、定时任务、策略执行都可能复用同一交易能力。
- 业务逻辑集中在 Application Service 和领域服务，测试更容易。

### 3.4 资金变化必须有流水

任何影响账户资金或保证金的动作，都必须通过 `LedgerService` 记录。

包括：

- demo 入金。
- 限价单冻结。
- 撤单释放。
- 市价成交保证金占用。
- 平仓保证金释放。
- 手续费。
- 已实现盈亏。

原因：

- 账户余额只是当前快照。
- 流水是可审计事实。
- 后续对账、导出、后台审查都依赖流水。
- 没有流水的余额变化无法解释。

### 3.5 后端是订单状态的唯一事实来源

前端可以乐观展示“已提交”，但不能自行判断订单成交。

订单状态必须由后端给出：

```text
RECEIVED
VALIDATING
ACCEPTED
WORKING
FILLED
CANCELED
REJECTED
FAILED
```

原因：

- 成交价格来自行情或执行结果。
- 风控可能拒绝订单。
- 撤单和成交可能并发。
- 后续接外部 Broker 时，外部状态可能延迟或未知。

## 4. 总体框架

第一版推荐调用链：

```text
TradingController
  -> TradingApplicationService
      -> AccountService / TradingAccountRepository
      -> InstrumentQueryService / SymbolRepository
      -> RiskCheckService
      -> OrderDomainService
      -> ExecutionAdapter
      -> FillService
      -> PositionService
      -> LedgerService
      -> OrderEventService
```

### 4.1 为什么增加 `TradingApplicationService`

当前 `OrderService` 已经承担了太多职责：

- 幂等判断。
- 账户查询。
- 风控调用。
- 订单创建。
- 执行调用。
- 成交处理入口。

第一版可以继续复用现有类，但建议在框架上引入 `TradingApplicationService` 或 `OrderApplicationService` 作为交易用例编排层。

它负责：

- `placeOrder()`。
- `cancelOrder()`。
- `closePosition()`。
- `queryOrders()`。

它不负责：

- 具体风控算法。
- 具体执行算法。
- 具体仓位计算。
- 具体流水保存。

原因：

- 交易用例需要跨多个领域服务。
- 一个事务边界通常覆盖多个领域动作。
- 后续接策略单时，策略可以调用同一 Application Service 下子单。

### 4.2 模块职责

| 模块 | 第一版职责 | 未来扩展 |
| --- | --- | --- |
| `TradingController` | REST 接口入口 | 管理端、策略端复用 Application Service |
| `TradingApplicationService` | 编排下单、撤单、平仓事务 | 支持改单、批量撤单、策略子单 |
| `OrderDomainService` | 创建订单、状态流转、幂等 | 支持订单组、父子单、OCO |
| `RiskCheckService` | 基础风控和保证金估算 | 支持价格保护、限额、强平前置检查 |
| `ExecutionAdapter` | 模拟市价成交 | 支持 Broker、LP、FIX |
| `FillService` | 写成交、更新订单成交字段 | 支持部分成交、多笔成交 |
| `PositionService` | 净持仓更新和平仓 | 支持双向持仓、逐仓/全仓 |
| `LedgerService` | 冻结、释放、手续费、盈亏流水 | 支持对账、报表、资金调整 |
| `OrderEventService` | 记录订单状态变化 | 未来连接 Outbox 和 WebSocket |
| `SymbolRepository` | 品种精度和交易状态 | 支持不同资产类别和交易时段 |

## 5. 第一版领域模型

### 5.1 订单模型

当前项目已有 `trading.orders`，但字段偏 demo。第一版建议通过 migration 增量补齐，不直接破坏旧字段。

推荐字段：

```text
id
user_id
account_id
client_order_id
symbol
side
order_type
status
price
quantity
filled_quantity
remaining_quantity
avg_fill_price
hold_amount
hold_currency
reject_code
reject_message
created_at
updated_at
filled_at
canceled_at
```

与现有字段兼容关系：

| 新字段 | 当前字段 | 说明 |
| --- | --- | --- |
| `client_order_id` | `idempotency_key` | 可以先双写或映射，避免破坏前端 |
| `quantity` | `lots` | FX 场景可继续叫 lots，但内部建议统一 quantity 概念 |
| `price` | `requested_price` | 限价价格 |
| `avg_fill_price` | `execution_price` | 第一版完全成交时两者相同 |
| `filled_quantity` | 无 | 支持未来部分成交 |
| `remaining_quantity` | 无 | 支持当前委托剩余量 |
| `hold_amount` | 无 | 撤单释放需要知道冻结额 |

为什么第一版就要加 `filled_quantity` / `remaining_quantity`：

- 市价单第一版虽然全部成交，但未来接 Broker 后可能部分成交。
- 限价单 WORKING 时需要展示剩余数量。
- 撤单时需要知道哪些数量已经成交、哪些还可撤。
- 现在补字段成本低，后续补会影响查询和前端表格。

### 5.2 订单状态

第一版推荐状态：

```text
RECEIVED
VALIDATING
ACCEPTED
WORKING
PARTIALLY_FILLED
FILLED
REJECTED
CANCEL_PENDING
CANCELED
FAILED
```

状态含义：

| 状态 | 含义 | 第一版是否使用 |
| --- | --- | --- |
| `RECEIVED` | 请求已进入系统 | 使用 |
| `VALIDATING` | 正在风控和参数校验 | 使用 |
| `ACCEPTED` | 后端接受订单 | 使用 |
| `WORKING` | 限价单已挂起 | 使用 |
| `PARTIALLY_FILLED` | 部分成交 | 预留 |
| `FILLED` | 完全成交 | 使用 |
| `REJECTED` | 被风控或参数拒绝 | 使用 |
| `CANCEL_PENDING` | 撤单处理中 | 使用或预留 |
| `CANCELED` | 已撤单 | 使用 |
| `FAILED` | 内部异常失败 | 使用 |

为什么不用当前的 `PENDING`：

- `PENDING` 含义模糊，可能表示待校验、待触发、待成交。
- `WORKING` 更接近交易系统语义，表示订单已经被接受并在市场中等待。
- 后续 STOP/计划委托可以单独使用 `DORMANT` 或 `WAITING_TRIGGER`，不会和普通限价混淆。

迁移建议：

- 第一阶段新增 `WORKING`，旧 `PENDING` 只做兼容。
- 查询当前委托时同时识别 `PENDING` 和 `WORKING`。
- 新创建的限价单使用 `WORKING`。

### 5.3 成交模型

当前项目已有 `trading.trades`。第一版可以继续使用这张表作为 fills 表，或新增 `trading.fills`。更推荐兼容式扩展 `trades`：

```text
id
order_id
account_id
symbol
side
quantity
price
fee
fee_currency
realized_pnl
executed_at
created_at
```

原因：

- 当前已有 `TradeEntity` 和 `TradeRepository`。
- 直接新建 `fills` 会造成 trade/fill 两套概念并存。
- 对第一版来说，一笔 fill 就是一个 trade record。
- 后续若需要区分外部成交回报和内部成交统计，再引入更细模型。

### 5.4 持仓模型

第一版使用净持仓 `NET`，不做双向持仓 `HEDGE`。

推荐字段：

```text
id
account_id
symbol
position_side
quantity
avg_open_price
realized_pnl
unrealized_pnl
margin_held
status
created_at
updated_at
closed_at
```

为什么第一版使用净持仓：

- 实现简单。
- 前端底部“当前仓位”能清晰展示。
- 平仓逻辑更容易验收。
- 未来如果做合约、逐仓、对冲，再扩展 `position_mode`。

净持仓规则：

```text
BUY:
  如果当前无仓或同向多仓 -> 增加多头数量，更新加权均价
  如果当前为空仓 -> 先减少空头，超出部分转多头

SELL:
  如果当前无仓或同向空仓 -> 增加空头数量，更新加权均价
  如果当前为多仓 -> 先减少多头，超出部分转空头
```

第一版如果不想实现完整反向开仓，可以更保守：

```text
BUY 只增加多头
SELL 只允许平多或减少多头
```

但从未来扩展看，建议一开始就把 `position_side` 和净仓计算接口留出来。

### 5.5 资金流水模型

当前 `ledger.ledger_entries` 已经存在。第一版需要补充流水类型和引用能力。

推荐流水类型：

```text
DEMO_DEPOSIT
ORDER_HOLD
ORDER_RELEASE
MARGIN_HOLD
MARGIN_RELEASE
TRADE_FEE
TRADE_PNL
ADMIN_ADJUSTMENT
```

当前已有：

```text
DEMO_DEPOSIT
MARGIN_HOLD
MARGIN_RELEASE
TRADE_PNL
ADMIN_ADJUSTMENT
```

建议新增：

```text
ORDER_HOLD
ORDER_RELEASE
TRADE_FEE
```

为什么区分 `ORDER_HOLD` 和 `MARGIN_HOLD`：

- `ORDER_HOLD` 表示订单挂起时冻结的资金或保证金。
- `MARGIN_HOLD` 表示成交形成持仓后占用的保证金。
- 撤单释放的是订单冻结。
- 平仓释放的是持仓保证金。

如果第一版简化，也可以统一用 `MARGIN_HOLD` / `MARGIN_RELEASE`，但文档和代码必须说清楚：它既代表订单冻结，也代表持仓占用。长期看不建议混用。

### 5.6 订单事件模型

建议第一版新增 `trading.order_events`：

```text
id
order_id
event_type
from_status
to_status
reason_code
message
created_at
```

为什么第一版就要做订单事件：

- 风控拒绝需要记录原因。
- 撤单需要记录发起和完成。
- 后续前端实时推送可以直接复用事件。
- 后续 Outbox 也可以从订单事件演进。
- 测试能验证状态流转是否完整。

第一版不需要做 Outbox，但需要保存事件。

## 6. 第一版 API 设计

### 6.1 保持现有路径

为了不破坏前端，第一版继续使用当前路径：

```text
POST /api/trading/orders
GET  /api/trading/orders
GET  /api/trading/positions?accountId={accountId}
GET  /api/accounts/{accountId}/summary
GET  /api/ledger?accountId={accountId}
POST /api/trading/positions/{positionId}/close?accountId={accountId}
```

新增：

```text
GET  /api/trading/orders/{orderId}
POST /api/trading/orders/{orderId}/cancel
```

暂不强制新增：

```text
GET /api/trading/account
GET /api/trading/ledger
```

原因：

- 当前前端已经使用 `/api/accounts/{accountId}/summary` 和 `/api/ledger?accountId=`。
- 重复新增 `/api/trading/account` 会让 API 表面变宽。
- 第一版以兼容当前交易页为优先。

### 6.2 请求 DTO

当前前端请求：

```json
{
  "accountId": "uuid",
  "symbol": "EURUSD",
  "side": "BUY",
  "orderType": "MARKET",
  "lots": "0.01",
  "requestedPrice": "1.08000",
  "idempotencyKey": "web-uuid"
}
```

推荐内部目标模型：

```json
{
  "accountId": "uuid",
  "clientOrderId": "web-uuid",
  "symbol": "EURUSD",
  "side": "BUY",
  "orderType": "MARKET",
  "quantity": "0.01",
  "price": null
}
```

第一版兼容策略：

| 外部字段 | 内部字段 |
| --- | --- |
| `idempotencyKey` | `clientOrderId` |
| `lots` | `quantity` |
| `requestedPrice` | `price` |

原因：

- 前端已经存在 `OrderPayload`，不应为后端重构强迫前端同步大改。
- 后端可以在 DTO 层做字段映射，领域层使用更清晰的名称。
- 未来前端迁移到 `clientOrderId/quantity/price` 时，只需逐步兼容。

### 6.3 响应 DTO

订单响应建议包含：

```text
id
accountId
symbol
side
orderType
status
quantity
filledQuantity
remainingQuantity
price
avgFillPrice
holdAmount
holdCurrency
rejectCode
rejectMessage
createdAt
updatedAt
filledAt
canceledAt
```

兼容当前前端字段：

```text
lots
executionPrice
createdAt
```

原因：

- 当前订单表格依赖 `lots` 和 `executionPrice`。
- 新字段给未来表格和底部委托面板使用。

## 7. 关键业务流程

### 7.1 市价单下单流程

目标：

```text
市价单提交后立即模拟成交，生成订单、成交、仓位、流水。
```

流程：

```text
POST /api/trading/orders
  -> TradingController 接收请求
  -> TradingApplicationService.placeOrder()
  -> 幂等检查 user_id + account_id + client_order_id
  -> 创建订单 RECEIVED
  -> 订单事件 RECEIVED
  -> 状态改为 VALIDATING
  -> 订单事件 VALIDATING
  -> 查询账户并锁定账户
  -> 查询 symbol 配置
  -> RiskCheckService 校验账户、品种、数量、保证金
  -> 状态改为 ACCEPTED
  -> 订单事件 ACCEPTED
  -> ExecutionAdapter 使用 ask/bid 模拟执行
  -> FillService 写成交
  -> 更新订单 filled_quantity / remaining_quantity / avg_fill_price
  -> 状态改为 FILLED
  -> PositionService 更新净持仓
  -> LedgerService 写保证金、手续费或盈亏流水
  -> 订单事件 FILLED
  -> 返回 OrderResponse
```

买入成交价：

```text
BUY 使用 ask
```

卖出成交价：

```text
SELL 使用 bid
```

为什么不能用空价格或前端价格成交：

- 前端价格可能过期。
- 成交价格必须来自后端可信行情或执行结果。
- 后续接外部 Broker 时，价格来自外部成交回报。

### 7.2 限价单下单流程

目标：

```text
限价单第一版只创建当前委托，不自动成交。
```

流程：

```text
POST /api/trading/orders
  -> TradingController 接收请求
  -> TradingApplicationService.placeOrder()
  -> 幂等检查
  -> 创建订单 RECEIVED
  -> 状态改为 VALIDATING
  -> 查询账户并锁定账户
  -> 查询 symbol 配置
  -> 校验 price > 0
  -> 校验 price 精度
  -> 校验 quantity 精度
  -> RiskCheckService 估算冻结金额或保证金
  -> 冻结资金或保证金
  -> LedgerService 写 ORDER_HOLD 或 MARGIN_HOLD
  -> 状态改为 WORKING
  -> 写订单事件 WORKING
  -> 返回 OrderResponse
```

为什么第一版不自动触发：

- 自动触发需要 Trigger Engine。
- Trigger Engine 需要行情事件驱动或可靠扫描。
- 撤单和触发成交可能并发，需要更完整锁设计。
- 当前第一版目标是基础下单框架，不是完整挂单撮合。

这不是功能倒退，而是把基础框架先做稳。

### 7.3 撤单流程

目标：

```text
撤销 WORKING 限价单，并释放冻结资金或保证金。
```

流程：

```text
POST /api/trading/orders/{orderId}/cancel
  -> TradingController 接收请求
  -> TradingApplicationService.cancelOrder()
  -> 查询订单并校验用户归属
  -> 使用悲观锁锁定订单
  -> 校验订单状态必须是 WORKING
  -> 状态改为 CANCEL_PENDING
  -> 写订单事件 CANCEL_PENDING
  -> 释放 holdAmount
  -> 更新账户 freeMargin / usedMargin
  -> LedgerService 写 ORDER_RELEASE 或 MARGIN_RELEASE
  -> 状态改为 CANCELED
  -> 写订单事件 CANCELED
  -> 返回 CancelOrderResponse
```

不能撤的状态：

```text
FILLED
CANCELED
REJECTED
FAILED
```

为什么撤单要加锁：

- 后续自动成交或外部回报可能和撤单同时发生。
- 即使第一版不自动触发，也应把锁边界设计好。
- 并发安全是未来扩展的基础。

### 7.4 平仓流程

当前已有：

```text
POST /api/trading/positions/{positionId}/close?accountId={accountId}
```

第一版建议保留，但内部语义需要整理：

```text
closePosition
  -> 校验账户归属
  -> 锁定 position
  -> 获取 fresh quote
  -> BUY 仓位按 bid 平仓
  -> SELL 仓位按 ask 平仓
  -> 计算 realized_pnl
  -> 更新 position CLOSED
  -> 释放 margin_held
  -> 更新 account balance/equity/freeMargin/usedMargin
  -> LedgerService 写 MARGIN_RELEASE
  -> LedgerService 写 TRADE_PNL
  -> 返回 PositionResponse
```

为什么平仓可以先独立于订单：

- 第一版目标是验证“持仓可以关闭并释放保证金”。
- 更专业的做法是平仓也创建一张反向订单，再由成交更新仓位。
- 但第一版为了少改动，可以保留当前 close endpoint。

建议未来演进：

```text
Close position endpoint
  -> 创建 reduce-only MARKET order
  -> 订单成交后更新仓位
```

## 8. 风控设计

第一版 `RiskCheckService` 应做的事情：

```text
账户存在
账户属于当前用户
账户状态允许交易
品种存在
品种 enabled = true
orderType 只允许 MARKET / LIMIT
quantity > 0
quantity >= minLot
quantity <= maxLot
quantity 符合 lot 精度
LIMIT price 必须存在且 > 0
LIMIT price 符合 tickSize
MARKET price 不需要前端传
行情 freshQuote 可用
freeMargin 足够
```

风控输出不应只是 `boolean`。建议返回：

```text
requiredMargin
holdAmount
holdCurrency
referencePrice
reasonCode
message
```

原因：

- 限价单冻结需要知道冻结金额。
- 市价单成交后需要知道保证金占用。
- 风控拒绝要写订单事件。
- 前端需要明确错误码展示。

第一版错误码建议：

```text
ACCOUNT_NOT_FOUND
ACCOUNT_NOT_TRADABLE
SYMBOL_NOT_FOUND
SYMBOL_DISABLED
BAD_QUANTITY
QUANTITY_BELOW_MIN
QUANTITY_ABOVE_MAX
BAD_PRICE
PRICE_TICK_INVALID
QUOTE_STALE
INSUFFICIENT_MARGIN
UNSUPPORTED_ORDER_TYPE
```

## 9. 事务和并发设计

### 9.1 幂等

每次下单必须有客户端幂等 ID。

推荐唯一约束：

```text
user_id + account_id + client_order_id
```

兼容当前：

```text
user_id + idempotency_key
```

第一版建议：

- 新增 `client_order_id`。
- 从 `idempotency_key` 映射填充。
- 新增唯一约束 `user_id, account_id, client_order_id`。
- 保留旧约束直到前端迁移完成。

幂等规则：

```text
同一个 clientOrderId 重复提交，返回原订单
不能重复冻结资金
不能重复写成交
不能重复更新仓位
不能重复写流水
```

### 9.2 事务边界

市价单事务：

```text
order
fill
position
account
ledger
order_event
```

限价单事务：

```text
order
account hold
ledger hold
order_event
```

撤单事务：

```text
order lock
account release
ledger release
order_event
```

平仓事务：

```text
position lock
account update
ledger margin release
ledger pnl
position update
```

### 9.3 悲观锁使用位置

建议第一版加锁：

- 创建订单时锁账户。
- 撤单时锁订单。
- 平仓时锁持仓。
- 成交处理时锁账户和持仓。

原因：

- 防止多个订单同时消耗同一份 freeMargin。
- 防止重复撤单。
- 防止平仓并发。
- 为未来自动成交和外部回报打基础。

## 10. 分阶段建设步骤

### Phase 0: 对齐边界和关闭高级行为

目标：

- 明确第一版只做基础交易闭环。
- 暂不让限价单自动触发。
- 暂不让 TP/SL 保护单影响验收。

需要做：

1. 更新文档和 smoke 口径。
2. 将限价单预期状态从 `PENDING -> FILLED` 改为 `WORKING`。
3. 将保护单 smoke 从第一版验收中移除。
4. 如果保留 `PendingOrderExecutionService`，增加配置开关，第一版默认关闭。
5. 如果保留 `ProtectiveOrderExecutionService`，增加配置开关，第一版默认关闭。

为什么先做 Phase 0：

- 当前代码已有高级 demo 行为。
- 如果不先关掉，第一版测试会被自动触发任务干扰。
- 验收标准必须和目标一致。

验收：

- 创建 LIMIT 后保持当前委托状态。
- 后台定时任务不会自动把它变成 FILLED。

### Phase 1: 数据模型补齐

目标：

- 为基础 OMS 补齐必要字段。
- 不破坏现有数据结构。

建议 migration：

```text
V12__foundation_oms_fields.sql
```

内容：

- `orders` 增加 `client_order_id`。
- `orders` 增加 `price` 或继续使用 `requested_price` 并记录映射。
- `orders` 增加 `quantity` 或继续使用 `lots` 并记录映射。
- `orders` 增加 `filled_quantity`。
- `orders` 增加 `remaining_quantity`。
- `orders` 增加 `avg_fill_price`。
- `orders` 增加 `hold_amount`。
- `orders` 增加 `hold_currency`。
- `orders` 增加 `reject_code`。
- `orders` 增加 `reject_message`。
- `orders` 增加 `updated_at`。
- `orders` 增加 `canceled_at`。
- `trades` 增加 `fee`。
- `trades` 增加 `fee_currency`。
- `trades` 增加 `created_at`。
- `positions` 增加 `position_side`。
- `positions` 增加 `quantity` 或继续映射 `lots`。
- `positions` 增加 `avg_open_price` 或继续映射 `open_price`。
- `positions` 增加 `updated_at`。
- 新增 `trading.order_events`。
- 扩展 ledger enum 支持 `ORDER_HOLD`、`ORDER_RELEASE`、`TRADE_FEE`。

为什么用 migration：

- 当前 JPA 配置是 `ddl-auto: validate`。
- Schema 必须由 Flyway 管理。
- 直接改实体不改 migration 会启动失败。

验收：

- Flyway migration 能成功执行。
- JPA validate 通过。
- 旧字段仍能读写。

### Phase 2: DTO 和兼容层

目标：

- 建立面向未来的内部模型。
- 保持现有前端请求不坏。

需要做：

1. `CreateOrderRequest` 兼容：
   - `idempotencyKey`。
   - `clientOrderId`。
   - `lots`。
   - `quantity`。
   - `requestedPrice`。
   - `price`。
2. `OrderResponse` 同时返回：
   - 旧字段：`lots`、`executionPrice`。
   - 新字段：`quantity`、`filledQuantity`、`remainingQuantity`、`avgFillPrice`。
3. 新增 `CancelOrderResponse`。
4. 补齐订单详情响应。

为什么做兼容层：

- 前端已有 `OrderPayload`。
- 当前任务重点是后端框架，不应把前端也拉进大重构。
- 兼容层让后端内部可以更专业，外部 API 逐步迁移。

验收：

- 当前前端仍能提交订单。
- 新格式请求也能提交订单。
- 响应里有旧字段和新字段。

### Phase 3: Application Service 编排层

目标：

- 将交易用例从 `OrderService` 中拆出。
- 让 `OrderService` 更像订单领域服务。

推荐职责：

```text
TradingApplicationService.placeOrder()
TradingApplicationService.cancelOrder()
TradingApplicationService.closePosition()
TradingApplicationService.getOrders()
TradingApplicationService.getOrder()
```

为什么不是直接继续堆 `OrderService`：

- 下单本身跨账户、品种、风控、订单、执行、成交、仓位、流水。
- `OrderService` 如果继续变大，后续加入撤单和改单会难维护。
- Application Service 是用例编排，领域服务是单一职责。

验收：

- Controller 只调用 Application Service。
- `OrderService` 不直接处理所有跨模块逻辑。
- 单元测试可以直接测 Application Service 用例。

### Phase 4: 基础状态机和订单事件

目标：

- 订单状态变化可控。
- 每次状态变化有事件记录。

需要做：

1. 扩展 `OrderStatus`。
2. 新增 `OrderEventEntity`。
3. 新增 `OrderEventService`。
4. 所有状态变化通过统一方法：

```text
transition(order, nextStatus, reasonCode, message)
```

状态转移规则：

```text
RECEIVED -> VALIDATING
VALIDATING -> ACCEPTED
VALIDATING -> REJECTED
ACCEPTED -> FILLED
ACCEPTED -> WORKING
WORKING -> CANCEL_PENDING
CANCEL_PENDING -> CANCELED
任何非终态 -> FAILED
```

终态：

```text
FILLED
CANCELED
REJECTED
FAILED
```

为什么要状态机：

- 防止已成交订单被撤。
- 防止已撤订单被成交。
- 防止重复状态写入。
- 为未来自动触发、外部回报、部分成交打基础。

验收：

- 非法状态转移会报错。
- 每次合法转移都有 order_event。
- 风控失败也有 REJECTED 事件。

### Phase 5: 风控和冻结

目标：

- 下单前能正确判断能不能交易。
- 限价单能冻结资金或保证金。

需要做：

1. 查询账户并校验属于当前用户。
2. 查询 `market.symbols`。
3. 校验 symbol enabled。
4. 校验数量范围和精度。
5. 校验 LIMIT price。
6. 获取 fresh quote。
7. 估算 required margin。
8. 检查 freeMargin。
9. 返回风控结果。

冻结规则：

```text
estimatedMargin = quantity * referencePrice / leverage
```

reference price：

```text
MARKET BUY: ask
MARKET SELL: bid
LIMIT BUY: min(limit price, ask) 或 limit price
LIMIT SELL: max(limit price, bid) 或 limit price
```

第一版可以统一使用：

```text
quantity * price / leverage
```

其中 MARKET 使用当前 ask/bid，LIMIT 使用用户 limit price。

验收：

- freeMargin 不足时订单 REJECTED。
- LIMIT 下单后 freeMargin 减少，usedMargin 或 hold 记录增加。
- 撤单后释放。

### Phase 6: MARKET 成交流程

目标：

- 市价单立即模拟成交。
- 成交后订单、成交、仓位、流水一致。

需要做：

1. `ExecutionAdapter` 返回：
   - `filledQuantity`。
   - `filledPrice`。
   - `fee`。
   - `feeCurrency`。
   - `executedAt`。
2. `FillService` 写 `trades`。
3. 更新订单：
   - `filledQuantity = quantity`。
   - `remainingQuantity = 0`。
   - `avgFillPrice = filledPrice`。
   - `status = FILLED`。
4. `PositionService` 更新净持仓。
5. `LedgerService` 写：
   - `MARGIN_HOLD` 或保证金占用。
   - `TRADE_FEE` 如果第一版计算手续费。

为什么模拟执行也要走 `ExecutionAdapter`：

- 后续替换 Broker 或 FIX 时业务服务不变。
- 测试可以 mock adapter。
- 市价成交价格来源集中。

验收：

- MARKET BUY 成交价使用 ask。
- MARKET SELL 成交价使用 bid。
- 写入一条 trade/fill。
- 订单状态为 FILLED。
- 持仓变化。
- 资金流水变化。
- 重复 clientOrderId 不重复成交。

### Phase 7: LIMIT 挂单流程

目标：

- LIMIT 只进入 WORKING。
- 资金或保证金被冻结。
- 不调用执行器。

需要做：

1. 创建订单。
2. 状态流转到 WORKING。
3. `filledQuantity = 0`。
4. `remainingQuantity = quantity`。
5. `avgFillPrice = null`。
6. `holdAmount = estimatedMargin`。
7. 写 ledger hold。

为什么不调用 `ExecutionAdapter`：

- 第一版不做自动成交。
- LIMIT 是否成交取决于未来 Trigger Engine 或外部 Broker 回报。
- 当前阶段要先让“当前委托”和“撤单释放”稳定。

验收：

- LIMIT 返回 WORKING。
- 没有 trade/fill。
- 没有 position 变化。
- 有 ledger hold。
- 订单出现在当前委托。

### Phase 8: CANCEL 撤单流程

目标：

- 撤销 WORKING 订单。
- 释放冻结。

需要做：

1. 新增接口：

```text
POST /api/trading/orders/{orderId}/cancel
```

2. 校验：

```text
订单存在
订单属于当前用户
订单状态是 WORKING
```

3. 状态流转：

```text
WORKING -> CANCEL_PENDING -> CANCELED
```

4. 释放：

```text
freeMargin += holdAmount
usedMargin -= holdAmount
```

5. 写流水：

```text
ORDER_RELEASE 或 MARGIN_RELEASE
```

6. 写订单事件。

验收：

- WORKING 可以撤。
- FILLED 不能撤。
- CANCELED 不能重复撤。
- 撤单后冻结释放。
- 重复撤单不会重复释放资金。

### Phase 9: CLOSE_POSITION 平仓流程

目标：

- 基础持仓可以关闭。
- 平仓后释放保证金并记录盈亏。

第一版可保留现有接口：

```text
POST /api/trading/positions/{positionId}/close?accountId={accountId}
```

流程：

```text
查询并锁定 position
校验账户归属
获取 fresh quote
BUY position 使用 bid 平仓
SELL position 使用 ask 平仓
计算 realized_pnl
更新 position CLOSED
释放 margin_held
更新 account
写 MARGIN_RELEASE
写 TRADE_PNL
```

为什么不强制平仓也走订单：

- 第一版要快速形成闭环。
- 当前代码已有 closePosition。
- 后续可演进为 reduce-only MARKET order。

验收：

- 已开仓位可关闭。
- 平仓后 status = CLOSED。
- marginHeld = 0。
- ledger 有 MARGIN_RELEASE。
- ledger 有 TRADE_PNL。

### Phase 10: 查询和前端兼容

目标：

- 当前前端交易页可以继续轮询。
- 底部账户面板能展示正确状态。

订单查询：

```text
GET /api/trading/orders
```

建议支持过滤：

```text
accountId
symbol
status
from
to
limit
cursor
```

第一版可以先返回当前用户全部订单，保持现有前端兼容。

当前委托状态：

```text
WORKING
CANCEL_PENDING
PARTIALLY_FILLED
```

历史委托状态：

```text
FILLED
CANCELED
REJECTED
FAILED
```

持仓查询：

```text
GET /api/trading/positions?accountId=
```

账户摘要：

```text
GET /api/accounts/{accountId}/summary
```

流水：

```text
GET /api/ledger?accountId=
```

为什么第一版继续轮询：

- 当前前端已经有轮询机制。
- 私有 WebSocket 会引入认证、断线恢复、事件排序问题。
- 基础闭环稳定后再改实时推送更稳。

## 11. 后续扩展路线

### 11.1 自动触发限价单

依赖：

- `WORKING` 订单稳定。
- 撤单和成交状态机稳定。
- 订单锁稳定。

扩展方式：

```text
Market quote event
  -> TriggerService 找到可成交订单
  -> lock order
  -> re-check risk
  -> ExecutionAdapter
  -> FillService
```

### 11.2 止盈止损

依赖：

- 父订单。
- 子订单。
- 持仓数量。
- 只减仓。

扩展方式：

```text
主订单成交
  -> 创建 TP/SL 子单
  -> 子单等待触发
  -> 触发后走标准成交/平仓流程
```

### 11.3 OCO

依赖：

- 订单组。
- 子单互斥取消。
- 状态机锁。

扩展方式：

```text
TP child filled
  -> cancel SL child

SL child filled
  -> cancel TP child
```

### 11.4 策略单

依赖：

- 普通订单稳定。
- 子单稳定。
- 订单事件完整。

扩展方式：

```text
Algo parent order
  -> scheduler 生成普通子订单
  -> 子订单走 placeOrder
  -> 聚合子单执行进度
```

### 11.5 外部 Broker / FIX

依赖：

- `ExecutionAdapter` 结果模型完整。
- `externalOrderId`。
- 对账状态。

扩展方式：

```text
ExecutionAdapter
  -> BrokerExecutionAdapter
  -> FixExecutionAdapter
```

### 11.6 私有 WebSocket

依赖：

- `order_events`。
- 状态事件稳定。
- 前端快照查询稳定。

扩展方式：

```text
order_events
  -> event publisher
  -> /user/queue/orders
```

先有事件表，再做实时推送，避免推送失败导致状态丢失。

## 12. 测试和验收

### 12.1 单元测试

必须覆盖：

```text
MARKET 下单成功
MARKET 使用 ask/bid 成交
MARKET 写 trade
MARKET 更新 order filled fields
MARKET 更新 position
MARKET 写 ledger
MARKET 幂等重复不重复成交

LIMIT 下单成功
LIMIT 状态 WORKING
LIMIT 不写 trade
LIMIT 不更新 position
LIMIT 写 hold ledger

CANCEL 成功
CANCEL 释放 hold
CANCEL 写 release ledger
CANCEL 写 order event
CANCEL 不允许撤 FILLED
CANCEL 不允许重复撤

CLOSE_POSITION 成功
CLOSE_POSITION 释放 margin
CLOSE_POSITION 写 pnl ledger

风控失败订单 REJECTED
风控失败不写 trade
风控失败不更新 position
风控失败不改 balance
```

### 12.2 集成测试

建议覆盖 REST：

```text
POST /api/auth/register
GET /api/accounts
POST /api/trading/orders MARKET
GET /api/trading/orders
GET /api/trading/positions
GET /api/ledger
POST /api/trading/orders LIMIT
POST /api/trading/orders/{id}/cancel
POST /api/trading/positions/{id}/close
```

### 12.3 Smoke 验收

当前 `scripts/smoke-backend.mjs` 需要调整：

删除第一版不需要的预期：

- 等待 LIMIT 自动成交。
- 等待 takeProfit 自动平仓。

新增第一版预期：

- LIMIT 创建后状态为 `WORKING`。
- LIMIT 没有 execution price。
- LIMIT 有冻结流水。
- 撤单后状态为 `CANCELED`。
- 撤单后有释放流水。

### 12.4 验收标准

第一版完成标准：

1. 项目能编译。
2. Flyway migration 成功。
3. JPA validate 成功。
4. 能注册用户并获取 demo 账户。
5. 能通过 REST 创建 MARKET。
6. MARKET 立即 FILLED。
7. MARKET 写成交。
8. MARKET 更新持仓。
9. MARKET 写流水。
10. 重复 MARKET clientOrderId 不重复成交。
11. 能通过 REST 创建 LIMIT。
12. LIMIT 状态为 WORKING。
13. LIMIT 冻结资金或保证金。
14. LIMIT 不自动成交。
15. 能撤销 LIMIT。
16. 撤单后状态为 CANCELED。
17. 撤单释放冻结。
18. 能查询订单、持仓、账户、流水。
19. 能平仓。
20. 平仓释放保证金并写盈亏流水。
21. 风控失败不会成交。
22. 所有金额、价格、数量使用 `BigDecimal`。

## 13. 推荐文件调整清单

这是后续实施时的建议清单，不代表本文档已经修改这些文件。

### 13.1 后端新增或调整

```text
backend/src/main/java/com/fxplatform/trading/service/TradingApplicationService.java
backend/src/main/java/com/fxplatform/trading/service/OrderDomainService.java
backend/src/main/java/com/fxplatform/trading/service/FillService.java
backend/src/main/java/com/fxplatform/trading/service/OrderEventService.java
backend/src/main/java/com/fxplatform/trading/entity/OrderEventEntity.java
backend/src/main/java/com/fxplatform/trading/repository/OrderEventRepository.java
backend/src/main/java/com/fxplatform/trading/dto/response/CancelOrderResponse.java
```

### 13.2 后端扩展

```text
TradingController.java
CreateOrderRequest.java
OrderResponse.java
OrderEntity.java
TradeEntity.java
PositionEntity.java
OrderStatus.java
OrderType.java
OrderRepository.java
PositionRepository.java
RiskCheckService.java
ExecutionResult.java
SimulatedExecutionAdapter.java
LedgerService.java
LedgerEntryType.java
```

### 13.3 数据库迁移

```text
backend/src/main/resources/db/migration/V12__foundation_oms_fields.sql
```

### 13.4 测试

```text
OrderApplicationServiceTest.java
OrderCancelServiceTest.java
FillServiceTest.java
PositionServiceTest.java
RiskCheckServiceTest.java
scripts/smoke-backend.mjs
```

## 14. 为什么这个框架方便未来扩展

### 14.1 高级订单能复用普通订单

未来 TP/SL、OCO、TWAP、冰山都不需要单独写成交逻辑。它们只负责生成或触发普通订单。

```text
高级订单
  -> 生成普通订单
  -> 普通订单走 OMS
```

这避免每种订单类型都各自改账户、仓位和流水。

### 14.2 外部执行只替换 Adapter

第一版模拟执行：

```text
SimulatedExecutionAdapter
```

未来可以替换为：

```text
BrokerExecutionAdapter
FixExecutionAdapter
LpExecutionAdapter
```

只要输出统一 `ExecutionResult`，上层订单、成交、仓位、流水不用重写。

### 14.3 实时推送可以从订单事件演进

第一版只写：

```text
order_events
```

未来增加：

```text
Outbox
WebSocket
SSE
```

事件源已经存在，不需要从业务代码里到处补推送。

### 14.4 对账可以从幂等和事件开始

第一版先保证：

- 客户端幂等。
- 成交不重复。
- 流水不重复。
- 状态有事件。

未来接外部 Broker 时，对账就是在这些基础上补：

- `externalOrderId`。
- `externalTradeId`。
- `RECONCILING` 状态。
- 对账任务。

### 14.5 前端能平滑升级

第一版继续支持轮询。

未来再升级：

```text
REST snapshot + private WS events
```

前端不用改整体页面结构，只需要把 `useTradingSession` 的轮询数据源换成“快照 + 事件增量”。

## 15. 最终建议

推荐执行路线：

```text
第一步：关闭或隔离当前自动触发限价和保护单 demo 行为
第二步：用 migration 补齐基础 OMS 字段和 order_events
第三步：引入 TradingApplicationService 编排层
第四步：实现 MARKET 完整成交闭环
第五步：实现 LIMIT WORKING + 冻结
第六步：实现 CANCEL + 释放
第七步：整理 CLOSE_POSITION
第八步：补测试和 smoke
第九步：再考虑自动触发、TP/SL、OCO、策略单
```

这条路线的核心价值是：

- 第一版能实际下单和平仓。
- 当前前端可以继续使用。
- 后端边界清晰。
- 资金和仓位可解释。
- 后续高级功能不会推翻重做。
