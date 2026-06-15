# 专业交易后端架构设计

## 1. 文档目的

本文档基于当前 `fx-trading-platform` 前端交易终端和现有 Spring Boot 后端骨架，整理一套面向专业交易平台的后端设计方案。目标不是推倒重写，而是在现有认证、账户、行情、交易、风控、执行、资金流水和审计边界上，补齐下单、挂单、止盈止损、策略单、仓位和资金一致性所需的后端能力。

本文档仅描述架构和设计边界，不包含代码实现。

## 2. 当前系统现状

### 2.1 前端交易终端能力

当前交易终端位于：

```text
fx-trading-platform/apps/web/src/pages/trading
```

主要能力包括：

- 左侧市场列表。
- 中间 K 线图表和图表工具区。
- 右侧盘口和最新成交面板。
- 中下部专业下单面板。
- 底部账户面板，包含当前委托、历史委托、当前仓位、历史仓位、资产、策略。
- 移动端抽屉和下单 sheet。

下单面板已经具备以下表单入口：

- 限价委托。
- 市价委托。
- 止盈止损。
- 移动止盈止损。
- 计划委托。
- 高级限价委托。
- 大单拆分。
- 冰山策略。
- TWAP。

其中拆单、冰山和 TWAP 当前前端标记为未开放，但类型和入口已经存在，因此后端设计需要预留策略母单和子单模型。

### 2.2 当前前端数据流

交易页当前通过 `useTradingSession` 加载 demo 账户，并周期性刷新：

- 账户摘要。
- 委托列表。
- 持仓列表。
- 资金流水。

当前刷新模式是轮询，默认约每 2 秒刷新一次。专业交易平台应升级为：

```text
REST 快照 + 私有实时事件流
```

即首屏和断线恢复走 REST，订单、成交、仓位和资金变化通过私有 WebSocket 或 SSE 推送。

### 2.3 当前后端能力

当前后端位于：

```text
fx-trading-platform/backend
```

已有主要模块：

- `auth`: 登录、注册、JWT。
- `account`: 交易账户。
- `market`: 行情、报价、盘口、最新成交。
- `chart`: K 线数据。
- `trading`: 订单、成交、仓位。
- `risk`: 保证金和风控检查。
- `execution`: 执行适配器。
- `ledger`: 资金流水。
- `admin`: 管理后台。
- `audit`: 审计日志。

当前关键规则已经正确：

- 前端不直接访问行情供应商或数据库。
- 下单必须经过 `RiskCheckService`。
- 成交写入统一走 `OrderFillService`。
- 资金变化统一写入 `LedgerService`。
- 市价单、挂单、止盈止损虽然触发入口不同，但应复用同一套订单、仓位、保证金和流水逻辑。

## 3. 设计边界和核心判断

### 3.1 推荐定位

推荐将当前系统定位为：

```text
券商型 / 交易终端型 OMS + EMS 系统
```

而不是：

```text
自建交易所撮合引擎
```

原因：

- 当前后端已有 `ExecutionAdapter`，适合把订单路由到模拟执行、Broker、LP 或 FIX。
- 当前行情来自外部供应商或 demo 生成器，不是自建中央订单簿。
- 前端是交易终端体验，重点是专业下单、风控、仓位、资金、事件反馈，而不是撮合撮单。
- 自建撮合需要独立撮合引擎、订单簿、撮合规则、清结算、风控隔离和高并发撮合基础设施，当前阶段不应引入。

### 3.2 架构选型

推荐采用：

```text
模块化单体 + 事件驱动边界
```

不建议当前阶段直接拆微服务。订单、仓位、保证金、资金流水之间存在强一致性要求，过早微服务化会引入分布式事务、消息补偿、追踪和部署复杂度。

## 4. 架构方案对比

| 方案 | 描述 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- | --- |
| 简单单体 | 在现有 `OrderService` 上继续追加逻辑 | 快速，适合 demo | 订单状态、策略单、资金冻结会逐渐混乱 | 不推荐作为专业化方向 |
| 模块化单体 | 保持一个 Spring Boot 应用，按领域模块隔离 | 一致性强，改造成本低，贴合当前代码 | 需要明确模块边界和事件模型 | 推荐 |
| 微服务 | 行情、订单、风控、执行、账户分别拆服务 | 可扩展性强 | 当前阶段复杂度过高 | 后期演进方向 |

## 5. 推荐总体架构

```text
Frontend Trading Terminal
  |
  | REST: 下单、撤单、改单、快照查询
  | WS/SSE: 订单、成交、仓位、余额事件
  v
API Layer
  |
  +-- Auth / Permission
  +-- Account
  +-- Instrument
  +-- Market Data
  +-- Order Management System
  +-- Risk / Margin
  +-- Trigger Engine
  +-- Algo Order Engine
  +-- Execution Management
  +-- Fill / Position
  +-- Ledger
  +-- Audit / Admin
  +-- Outbox Event Publisher
```

核心原则：

- API 层只做认证、参数校验和响应转换。
- 订单状态变化必须通过订单状态机。
- 成交写入必须统一进入成交处理路径。
- 仓位和资金流水不得由多个服务各自随意写入。
- 前端不得自行推断订单已经成交，必须以后端事件或后端查询结果为准。

## 6. 后端模块设计

### 6.1 Auth / Permission

职责：

- 用户登录、注册、JWT 签发和刷新。
- WebSocket 私有通道认证。
- 区分普通交易用户和管理员。

关键规则：

- 下单、撤单、改单必须绑定用户身份。
- 私有交易事件只能推给订单所属用户。
- 管理端只能通过后台接口查询全局数据。

### 6.2 Account

职责：

- 管理交易账户。
- 维护账户基础状态。
- 提供账户摘要。

账户核心字段：

- `balance`: 账户余额。
- `equity`: 权益。
- `usedMargin`: 已用保证金。
- `freeMargin`: 可用保证金。
- `marginLevel`: 保证金率。
- `leverage`: 杠杆倍数。
- `status`: 账户状态。

设计要求：

- 账户余额不应被订单服务直接随意修改。
- 所有余额变化必须有对应资金流水。
- 保证金冻结和释放需要可追踪。

### 6.3 Instrument

职责：

- 管理可交易品种。
- 提供价格精度、数量精度、最小下单量、最大下单量、最小成交额、交易状态、交易时段、手续费规则。

建议字段：

| 字段 | 含义 |
| --- | --- |
| `symbol` | 内部交易品种，如 `BTCUSDT` |
| `displayName` | 展示名，如 `BTC/USDT` |
| `baseAsset` | 基础资产 |
| `quoteAsset` | 计价资产 |
| `priceTick` | 价格最小跳动 |
| `quantityStep` | 数量步进 |
| `minQuantity` | 最小数量 |
| `minNotional` | 最小成交额 |
| `maxLeverage` | 最大杠杆 |
| `tradingStatus` | 是否允许交易 |

设计要求：

- 前端校验可以提前提示，但后端必须再次校验。
- 所有价格和数量应使用 decimal 类型，不使用浮点数。

### 6.4 Market Data

职责：

- 行情快照。
- 实时报价。
- 盘口。
- 最新成交。
- K 线。

推荐模式：

```text
REST snapshot -> WebSocket incremental updates -> REST fallback on reconnect
```

当前市场侧已有类似模式，应继续沿用。

设计要求：

- 外部行情供应商结构不能泄漏到前端。
- 后端统一输出平台标准化模型。
- 报价必须有时间戳和过期判断。
- 交易触发不能使用过期行情。

### 6.5 Order Management System

职责：

- 创建订单。
- 校验幂等。
- 管理订单状态机。
- 撤单。
- 改单。
- 查询当前委托和历史委托。
- 维护父子订单关系。
- 生成订单事件。

订单系统是整个交易后端的核心，不能只是简单保存一行 `orders`。

建议订单类型：

| 类型 | 含义 |
| --- | --- |
| `MARKET` | 市价单 |
| `LIMIT` | 限价单 |
| `STOP_MARKET` | 触发后转市价 |
| `STOP_LIMIT` | 触发后转限价 |
| `TAKE_PROFIT_MARKET` | 止盈市价 |
| `TAKE_PROFIT_LIMIT` | 止盈限价 |
| `TRAILING_STOP` | 移动止损或移动止盈 |
| `OCO` | 二选一订单组 |

建议订单属性：

| 字段 | 含义 |
| --- | --- |
| `clientOrderId` | 客户端幂等 ID |
| `parentOrderId` | 父订单 ID |
| `orderGroupId` | OCO 或策略组 ID |
| `strategyId` | 策略母单 ID |
| `side` | 买入或卖出 |
| `quantity` | 委托数量 |
| `filledQuantity` | 已成交数量 |
| `remainingQuantity` | 剩余数量 |
| `price` | 限价 |
| `triggerPrice` | 触发价 |
| `triggerPriceType` | `LAST` / `BID` / `ASK` / `MID` / `MARK` |
| `timeInForce` | `GTC` / `IOC` / `FOK` |
| `postOnly` | 只挂单 |
| `reduceOnly` | 只减仓 |
| `expiresAt` | 过期时间 |
| `avgFillPrice` | 平均成交价 |

### 6.6 Risk / Margin

职责：

- 下单前风控。
- 挂单触发前二次风控。
- 改单风控。
- 保证金计算。
- 最大仓位和最大订单规模限制。
- 只减仓校验。
- 价格保护和滑点保护。

风控检查建议拆分：

```text
Account status check
Instrument trading status check
Precision and min/max check
Balance / margin check
Position exposure check
Order count / rate limit check
Price band / slippage check
Reduce-only check
```

关键规则：

- 风控拒绝必须生成明确错误码。
- 风控通过时需要冻结资金或保证金。
- 挂单不能等成交时才第一次冻结资金，否则可用余额会被重复使用。

### 6.7 Trigger Engine

职责：

- 监听行情事件。
- 判断限价单、STOP、TP/SL、Trailing 是否触发。
- 将触发订单交给执行路径。
- 处理过期订单。

当前挂单和止盈止损是定时扫描。专业化后建议改为：

```text
行情事件驱动 + 低频扫描兜底
```

触发引擎关键要求：

- 触发时必须锁定订单，避免重复触发。
- 触发后必须做二次风控。
- 触发失败要记录失败原因。
- 行情断流时不能错误触发。

### 6.8 Algo Order Engine

职责：

- 管理策略母单。
- 生成普通子单。
- 跟踪策略执行进度。
- 支持暂停、恢复、取消。

适用前端入口：

- 大单拆分。
- 冰山策略。
- TWAP。

策略单不应直接成交。正确模型是：

```text
策略母单 -> 多个普通子订单 -> 子订单走标准 OMS / Risk / Execution / Fill
```

建议策略母单状态：

```text
CREATED -> RUNNING -> PAUSED -> COMPLETED
                  -> CANCELED
                  -> FAILED
```

### 6.9 Execution Management

职责：

- 统一执行接口。
- 支持模拟执行。
- 支持 Broker API。
- 支持 LP API。
- 支持 FIX。
- 标准化执行回报。

建议保留当前 `ExecutionAdapter` 思路，但扩展执行结果：

| 字段 | 含义 |
| --- | --- |
| `executionId` | 执行 ID |
| `externalOrderId` | 外部订单 ID |
| `filledQuantity` | 本次成交数量 |
| `filledPrice` | 本次成交价格 |
| `fee` | 手续费 |
| `liquidity` | `MAKER` / `TAKER` |
| `status` | 外部执行状态 |
| `executedAt` | 成交时间 |

设计要求：

- 业务服务不应知道具体执行来源。
- 模拟执行、Broker、LP、FIX 必须输出同一标准结果。
- 外部执行状态未知时，不能直接标记失败，应进入 `UNKNOWN` 或 `RECONCILING` 状态。

### 6.10 Fill / Position

职责：

- 写入成交。
- 更新订单成交数量和平均成交价。
- 开仓、加仓、减仓、平仓。
- 计算浮盈亏和已实现盈亏。

关键规则：

- 每一笔成交必须有独立 fill/trade 记录。
- 订单可部分成交，不能只支持一次性 `FILLED`。
- 仓位读取可按最新报价派生浮盈亏，但成交和平仓必须写入确定性资金结果。

### 6.11 Ledger

职责：

- 记录所有资金变化。
- 记录保证金冻结和释放。
- 记录手续费。
- 记录交易盈亏。
- 支持审计和对账。

建议流水类型：

| 类型 | 含义 |
| --- | --- |
| `DEPOSIT` | 入金 |
| `WITHDRAWAL` | 出金 |
| `ORDER_HOLD` | 下单冻结 |
| `ORDER_RELEASE` | 撤单或过期释放 |
| `MARGIN_HOLD` | 保证金占用 |
| `MARGIN_RELEASE` | 保证金释放 |
| `TRADE_FEE` | 手续费 |
| `TRADE_PNL` | 交易盈亏 |
| `ADJUSTMENT` | 后台调整 |

关键规则：

- 余额变化必须和流水一一对应。
- 资金流水应是事实来源之一，不依赖前端计算。
- 后续需要支持日终对账。

### 6.12 Private Event Stream

职责：

- 向前端推送私有交易事件。
- 替代当前账户轮询。
- 支持断线恢复。

建议事件通道：

```text
/user/queue/orders
/user/queue/fills
/user/queue/positions
/user/queue/balances
/user/queue/risk
```

建议事件类型：

| 事件 | 说明 |
| --- | --- |
| `ORDER_ACCEPTED` | 订单已接受 |
| `ORDER_REJECTED` | 订单被拒 |
| `ORDER_WORKING` | 订单进入工作状态 |
| `ORDER_PARTIALLY_FILLED` | 部分成交 |
| `ORDER_FILLED` | 完全成交 |
| `ORDER_CANCEL_PENDING` | 撤单中 |
| `ORDER_CANCELED` | 已撤单 |
| `ORDER_EXPIRED` | 已过期 |
| `FILL_CREATED` | 新成交 |
| `POSITION_UPDATED` | 仓位变化 |
| `BALANCE_UPDATED` | 资产变化 |
| `RISK_REJECTED` | 风控拒绝 |

事件设计要求：

- 每个事件都有递增序号或时间戳。
- 前端断线重连后能通过 REST 查询补齐。
- 事件发布建议通过 outbox 模式保证事务后可靠发送。

## 7. 订单生命周期设计

### 7.1 普通订单状态机

```text
RECEIVED
  -> VALIDATING
  -> ACCEPTED
  -> WORKING
  -> PARTIALLY_FILLED
  -> FILLED
```

异常和终态：

```text
REJECTED
CANCEL_PENDING
CANCELED
EXPIRED
FAILED
UNKNOWN
```

状态含义：

| 状态 | 含义 |
| --- | --- |
| `RECEIVED` | API 已收到请求 |
| `VALIDATING` | 正在做参数、账户、风控校验 |
| `ACCEPTED` | 后端接受订单 |
| `WORKING` | 订单正在等待成交或外部执行中 |
| `PARTIALLY_FILLED` | 部分成交 |
| `FILLED` | 全部成交 |
| `REJECTED` | 下单被拒绝 |
| `CANCEL_PENDING` | 撤单请求已发出 |
| `CANCELED` | 已撤单 |
| `EXPIRED` | 已过期 |
| `FAILED` | 内部处理失败 |
| `UNKNOWN` | 外部执行状态未知，需要对账恢复 |

### 7.2 市价单流程

```text
POST /orders
  -> 幂等检查
  -> 品种和精度校验
  -> 风控和保证金检查
  -> 冻结资金或保证金
  -> 调用 ExecutionAdapter
  -> 写 fill
  -> 更新 order
  -> 更新 position
  -> 写 ledger
  -> 写 outbox
  -> 推送私有事件
```

### 7.3 限价挂单流程

```text
POST /orders
  -> 幂等检查
  -> 品种和精度校验
  -> 风控检查
  -> 冻结资金或保证金
  -> order = WORKING
  -> 等待行情触发或外部成交回报
```

触发时：

```text
Market event
  -> 找到可触发订单
  -> 锁定订单
  -> 二次风控
  -> 执行
  -> 成交处理
  -> 事件推送
```

### 7.4 STOP / 计划委托流程

计划委托初始不应进入普通挂单队列，而应进入触发等待状态：

```text
ACCEPTED -> DORMANT -> TRIGGERED -> WORKING/FILLED
```

触发条件可以基于：

- 最新价。
- 买一价。
- 卖一价。
- 中间价。
- 标记价。

### 7.5 止盈止损和 OCO

推荐用父子订单模型：

```text
Parent order
  -> filled
  -> create take-profit child order
  -> create stop-loss child order
  -> OCO group active
```

当一个子单触发或成交：

```text
Triggered child order
  -> close or reduce position
  -> cancel sibling order
  -> update OCO group
  -> push order/position/balance events
```

关键要求：

- 子单不能在父单成交前误触发。
- OCO 中任意一侧成交后，另一侧必须可靠取消。
- 子单数量不能超过可减仓数量。

### 7.6 移动止盈止损

移动止盈止损需要维护动态跟踪状态：

| 字段 | 含义 |
| --- | --- |
| `activationPrice` | 激活价 |
| `callbackRatio` | 回调比例 |
| `highestPrice` | 多头激活后的最高价 |
| `lowestPrice` | 空头激活后的最低价 |
| `currentTriggerPrice` | 当前动态触发价 |

流程：

```text
未激活 -> 行情达到激活价 -> 激活
激活后更新最高/最低价 -> 重新计算触发价
行情回撤到触发价 -> 触发平仓或下子单
```

### 7.7 策略订单流程

以 TWAP 为例：

```text
Create algo order
  -> validate total quantity and duration
  -> status = RUNNING
  -> scheduler creates child limit/market orders
  -> child orders use standard OMS path
  -> aggregate fills
  -> complete/cancel/fail parent
```

冰山策略：

```text
母单总数量
  -> 暴露一小部分子单
  -> 子单成交后继续补单
  -> 直到总数量完成或取消
```

大单拆分：

```text
母单
  -> 根据数量、时间、盘口深度拆分
  -> 生成多个子单
  -> 按策略节奏提交
```

## 8. 数据模型建议

### 8.1 `trading.orders`

建议补强字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 内部订单 ID |
| `user_id` | 用户 ID |
| `account_id` | 账户 ID |
| `client_order_id` | 客户端幂等 ID |
| `external_order_id` | 外部执行系统订单 ID |
| `parent_order_id` | 父订单 ID |
| `order_group_id` | OCO 或订单组 ID |
| `strategy_id` | 策略母单 ID |
| `symbol` | 交易品种 |
| `side` | 买卖方向 |
| `order_type` | 订单类型 |
| `status` | 订单状态 |
| `time_in_force` | 有效期类型 |
| `price` | 委托价格 |
| `trigger_price` | 触发价格 |
| `trigger_price_type` | 触发价格类型 |
| `quantity` | 委托数量 |
| `filled_quantity` | 已成交数量 |
| `remaining_quantity` | 剩余数量 |
| `avg_fill_price` | 平均成交价格 |
| `reduce_only` | 是否只减仓 |
| `post_only` | 是否只挂单 |
| `expires_at` | 过期时间 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

### 8.2 `trading.order_events`

用于审计和前端事件恢复。

| 字段 | 说明 |
| --- | --- |
| `id` | 事件 ID |
| `order_id` | 订单 ID |
| `event_type` | 事件类型 |
| `from_status` | 原状态 |
| `to_status` | 新状态 |
| `reason_code` | 原因码 |
| `message` | 描述 |
| `payload` | JSON 详情 |
| `created_at` | 事件时间 |

### 8.3 `trading.fills`

用于支持部分成交和成交明细。

| 字段 | 说明 |
| --- | --- |
| `id` | 成交 ID |
| `order_id` | 订单 ID |
| `account_id` | 账户 ID |
| `symbol` | 交易品种 |
| `side` | 买卖方向 |
| `quantity` | 成交数量 |
| `price` | 成交价格 |
| `fee` | 手续费 |
| `fee_currency` | 手续费币种 |
| `liquidity` | `MAKER` / `TAKER` |
| `external_trade_id` | 外部成交 ID |
| `executed_at` | 成交时间 |

### 8.4 `trading.positions`

当前已有基础持仓表，建议补充：

| 字段 | 说明 |
| --- | --- |
| `position_mode` | `NET` / `HEDGE` |
| `avg_open_price` | 平均开仓价 |
| `quantity` | 当前数量 |
| `available_quantity` | 可平数量 |
| `realized_pnl` | 已实现盈亏 |
| `unrealized_pnl` | 未实现盈亏 |
| `margin_held` | 占用保证金 |
| `liquidation_price` | 预估强平价 |

### 8.5 `account.balance_holds`

用于记录资金或保证金冻结。

| 字段 | 说明 |
| --- | --- |
| `id` | 冻结 ID |
| `account_id` | 账户 ID |
| `order_id` | 订单 ID |
| `currency` | 币种 |
| `amount` | 冻结金额 |
| `status` | `ACTIVE` / `RELEASED` / `CONSUMED` |
| `created_at` | 创建时间 |
| `released_at` | 释放时间 |

### 8.6 `trading.algo_orders`

用于 TWAP、冰山、大单拆分。

| 字段 | 说明 |
| --- | --- |
| `id` | 策略母单 ID |
| `account_id` | 账户 ID |
| `symbol` | 交易品种 |
| `strategy_type` | `TWAP` / `ICEBERG` / `SPLIT` |
| `side` | 买卖方向 |
| `total_quantity` | 总数量 |
| `executed_quantity` | 已执行数量 |
| `status` | 策略状态 |
| `config` | 策略配置 JSON |
| `started_at` | 开始时间 |
| `ended_at` | 结束时间 |

### 8.7 `outbox_events`

用于可靠事件发布。

| 字段 | 说明 |
| --- | --- |
| `id` | 事件 ID |
| `aggregate_type` | 聚合类型 |
| `aggregate_id` | 聚合 ID |
| `event_type` | 事件类型 |
| `payload` | JSON 内容 |
| `status` | `PENDING` / `PUBLISHED` / `FAILED` |
| `created_at` | 创建时间 |
| `published_at` | 发布时间 |

## 9. API 设计建议

### 9.1 订单 API

```text
POST   /api/trading/orders
GET    /api/trading/orders
GET    /api/trading/orders/{orderId}
POST   /api/trading/orders/{orderId}/cancel
POST   /api/trading/orders/{orderId}/amend
POST   /api/trading/orders/cancel-all
```

查询参数建议：

```text
accountId
symbol
status
from
to
limit
cursor
```

### 9.2 成交 API

```text
GET /api/trading/fills
GET /api/trading/fills/{fillId}
```

### 9.3 仓位 API

```text
GET  /api/trading/positions
POST /api/trading/positions/{positionId}/close
POST /api/trading/positions/close-all
```

### 9.4 策略订单 API

```text
POST /api/trading/algo-orders
GET  /api/trading/algo-orders
POST /api/trading/algo-orders/{algoOrderId}/pause
POST /api/trading/algo-orders/{algoOrderId}/resume
POST /api/trading/algo-orders/{algoOrderId}/cancel
```

### 9.5 私有实时事件

```text
/user/queue/orders
/user/queue/fills
/user/queue/positions
/user/queue/balances
/user/queue/risk
```

## 10. 前后端协作模型

### 10.1 前端提交订单

前端负责：

- 表单输入。
- 本地即时校验。
- 展示预估金额和手续费。
- 提供 `clientOrderId`。

后端负责：

- 最终参数校验。
- 精度校验。
- 风控校验。
- 幂等处理。
- 资金冻结。
- 订单状态机。
- 执行和事件推送。

### 10.2 前端展示订单

前端不应根据提交成功自行判断订单已成交。推荐展示逻辑：

```text
POST /orders 返回 ACCEPTED 或 WORKING
  -> 前端插入当前委托
  -> 等待 /user/queue/orders 和 /user/queue/fills
  -> 根据事件更新当前委托、历史委托、仓位、资产
```

### 10.3 断线恢复

前端 WebSocket 断线后：

```text
重新连接
  -> 查询订单快照
  -> 查询持仓快照
  -> 查询账户快照
  -> 从 lastEventId 后补事件，或直接以快照为准
```

## 11. 风控和一致性要求

### 11.1 幂等

每次下单必须有 `clientOrderId` 或 `idempotencyKey`。同一用户同一个幂等键：

- 如果已创建订单，返回原订单。
- 如果正在处理中，返回处理中状态。
- 不允许重复创建订单。

### 11.2 事务边界

一次成交处理应在同一事务内完成：

```text
更新 order
写 fill
更新 position
更新 account
写 ledger
写 order_event
写 outbox_event
```

事件推送应在事务提交后执行。

### 11.3 锁和并发

需要锁定的资源：

- 账户资金。
- 订单状态。
- 持仓。
- OCO 订单组。
- 策略母单。

典型并发风险：

- 同一订单重复触发。
- 撤单和成交同时发生。
- OCO 两个子单同时触发。
- 多个挂单同时消耗同一笔可用余额。
- 策略子单生成和用户取消同时发生。

### 11.4 对账和恢复

专业交易系统必须接受外部执行状态未知的情况：

- API 超时不等于失败。
- 外部 Broker 返回慢不等于未成交。
- WebSocket 断开不等于订单消失。

需要设计：

- `UNKNOWN` 或 `RECONCILING` 状态。
- 定时对账任务。
- 外部订单 ID 映射。
- 重放订单事件。

## 12. 分阶段落地计划

### Phase 1: 补强 OMS 基础

目标：

- 扩展订单状态机。
- 增加 `filledQuantity`、`remainingQuantity`、`avgFillPrice`。
- 增加订单事件表。
- 增加撤单接口。
- 保持现有前端接口兼容。

验证：

- 市价单能成交并写入订单、成交、仓位、流水。
- 限价单进入当前委托。
- 撤单后进入历史委托。
- 幂等键重复提交不生成重复订单。

### Phase 2: 私有交易事件流

目标：

- 新增订单、成交、仓位、资产私有推送。
- 前端从 2 秒轮询升级为事件驱动。
- REST 仍作为首屏和断线兜底。

验证：

- 下单后前端无需等待轮询即可看到订单状态。
- 成交后仓位和资产自动更新。
- WebSocket 断开重连后快照正确。

### Phase 3: 挂单和触发引擎升级

目标：

- 将挂单触发从全表扫描升级为行情事件驱动。
- 支持 `STOP_MARKET` 和 `STOP_LIMIT`。
- 支持过期订单。
- 保留低频扫描作为恢复机制。

验证：

- 买入限价在 ask 触达时成交。
- 卖出限价在 bid 触达时成交。
- STOP 单触发后生成正确执行路径。
- 过期订单释放冻结资金。

### Phase 4: TP/SL、OCO、Trailing

目标：

- 父子订单。
- OCO 订单组。
- 止盈止损可靠互斥。
- 移动止盈止损动态触发价。

验证：

- 父单未成交前子单不触发。
- 止盈成交后止损自动取消。
- 止损成交后止盈自动取消。
- trailing 激活后按最高或最低价格更新触发价。

### Phase 5: 策略单

目标：

- TWAP。
- 冰山。
- 大单拆分。
- 策略母单和子单关系。

验证：

- 策略母单只生成子单，不直接成交。
- 子单都走普通 OMS、Risk、Execution、Fill。
- 策略暂停、恢复、取消可控。
- 策略执行进度可在底部“策略”面板展示。

### Phase 6: 外部执行和对账

目标：

- 扩展 `ExecutionAdapter`。
- 支持 Broker、LP 或 FIX。
- 增加外部订单 ID。
- 增加状态对账任务。

验证：

- 外部执行超时时订单进入 `UNKNOWN` 或 `RECONCILING`。
- 对账后能恢复最终状态。
- 重复执行回报不会重复写成交。

## 13. 验证标准

### 13.1 后端单元测试

覆盖：

- 幂等下单。
- 市价单成交。
- 限价单挂起和触发。
- STOP 单触发。
- 部分成交。
- 撤单。
- 风控拒绝。
- 资金冻结和释放。
- OCO 互斥。
- trailing 动态触发价。
- 策略子单生成。

### 13.2 集成测试

覆盖：

- `POST /api/trading/orders`。
- `GET /api/trading/orders`。
- `POST /api/trading/orders/{id}/cancel`。
- 账户余额变化。
- 持仓变化。
- 资金流水。
- 私有事件推送。

### 13.3 前端联调验收

覆盖：

- 下单后当前委托立即出现。
- 成交后订单进入历史委托。
- 成交后当前仓位更新。
- 平仓后历史仓位更新。
- 资产和资金流水更新。
- 策略单显示在“策略”tab。
- 断线重连后状态不丢失。

### 13.4 关键不变量

必须长期成立：

- 没有风控通过就不能成交。
- 没有成交就不能产生仓位变化。
- 没有资金流水就不能改变余额。
- 同一成交不能重复入账。
- 同一订单不能重复触发。
- OCO 中只能有一个子单最终成交。
- 前端不能自行推断成交状态。

## 14. 风险和取舍

### 14.1 不建议立即做自建撮合

当前项目目标是专业交易终端和交易后端，不是交易所撮合系统。自建撮合会大幅扩大范围。

### 14.2 不建议策略单先行

TWAP、冰山、大单拆分依赖稳定的普通订单系统。应先补强订单状态机、资金冻结、事件流和成交处理。

### 14.3 不建议仅靠定时扫描触发挂单

定时扫描简单，但订单量增加后会慢，并且容易出现重复触发和状态延迟。推荐行情事件驱动，扫描只做恢复兜底。

### 14.4 不建议前端继续长期轮询账户状态

轮询可以保留为兜底，但专业体验需要私有事件流。否则当前委托、历史委托、仓位和资产都会存在明显延迟。

## 15. 推荐结论

当前最合理的后端演进路线是：

```text
保持 Spring Boot 模块化单体
  -> 补强 OMS 状态机
  -> 引入订单事件和 outbox
  -> 建立私有交易事件流
  -> 升级挂单/触发/TP-SL/OCO
  -> 最后实现策略母单
```

这条路线符合当前代码基础，也能支撑前端已经出现的专业交易功能入口。它避免过早微服务化和自建撮合，同时保留未来接入 Broker、LP 或 FIX 的扩展空间。
