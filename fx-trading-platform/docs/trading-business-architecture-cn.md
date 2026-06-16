# 交易业务与算法架构说明

本文档基于当前代码工作树整理，目标是把交易链路从账户、下单、保证金变化、持仓、浮盈亏、平仓/强平、账户变化完整串起来，并按三类产品分别说明：

- 外汇：有杠杆，按标准 lot / contract size 计算保证金和盈亏。
- 虚拟币现货：无杠杆，当前代码按全额占用资金模拟现货持仓，但还不是严格的钱包资产账本。
- 虚拟币永续合约：有杠杆，算法层已有线性/反向永续支持，但产品分类、资金费率、维持保证金、自动强平尚未完整落地。

## 1. 当前总体结论

当前系统的交易主链路已经存在：

```text
Frontend TradePanel
  -> toOrderPayload()
  -> TradingController
  -> OrderService.createOrder()
  -> RiskCheckService.checkOrder()
  -> ExecutionAdapter.execute()
  -> OrderFillService.fill()
  -> PositionEntity OPEN
  -> PositionService.openPositions()
  -> PositionService.closePosition()/closeSystemPosition()
  -> LedgerService
  -> TradingAccountEntity balance/equity/usedMargin/freeMargin
```

但是当前系统采用的是一个统一的保证金账户模型：

```text
TradingAccountEntity:
  balance
  equity
  usedMargin
  freeMargin
  marginLevel
  leverage
  baseCurrency
```

这对外汇和永续合约是合理起点；但对虚拟币现货还不够，因为现货需要 `BTC/ETH/SOL/USDT/USD` 等资产级钱包余额、锁仓余额和资产流水。当前代码用 `marginHeld` 模拟现货买入成本，用 BUY position 模拟现货持仓，这能支撑基础展示，但不是完整现货账本。

## 2. 当前代码模块地图

### 2.1 后端核心模块

| 领域 | 当前文件 | 职责 |
| --- | --- | --- |
| 账户实体 | `backend/src/main/java/com/fxplatform/account/entity/TradingAccountEntity.java` | 统一交易账户，记录 balance/equity/usedMargin/freeMargin/leverage |
| 账户服务 | `backend/src/main/java/com/fxplatform/account/service/AccountService.java` | 创建 demo 账户、返回账户摘要 |
| 订单实体 | `backend/src/main/java/com/fxplatform/trading/entity/OrderEntity.java` | 订单主表映射，含 quantity/lots/price/leverage/holdAmount/fee/slippage |
| 持仓实体 | `backend/src/main/java/com/fxplatform/trading/entity/PositionEntity.java` | 持仓主表映射，含 lots/openPrice/currentPrice/floatingPnl/realizedPnl/marginHeld/leverage |
| 交易记录 | `backend/src/main/java/com/fxplatform/trading/entity/TradeEntity.java` | 成交记录 |
| 下单入口 | `backend/src/main/java/com/fxplatform/trading/controller/TradingController.java` | `/api/trading/orders`、positions、close position 等 REST API |
| 下单请求 | `backend/src/main/java/com/fxplatform/trading/dto/request/CreateOrderRequest.java` | 兼容 `lots/requestedPrice` 与 `quantity/price`，支持 `leverage` |
| 订单服务 | `backend/src/main/java/com/fxplatform/trading/service/OrderService.java` | 幂等、风控、pending order 资金冻结、market order 执行 |
| 成交结算 | `backend/src/main/java/com/fxplatform/trading/service/OrderFillService.java` | 更新订单、创建 trade、创建 position、占用保证金、扣 fee、写 ledger |
| 持仓服务 | `backend/src/main/java/com/fxplatform/trading/service/PositionService.java` | 持仓查询、浮盈亏展示、平仓、系统平仓 |
| 风控服务 | `backend/src/main/java/com/fxplatform/risk/service/RiskCheckService.java` | 下单前保证金校验、杠杆解析 |
| 保证金计算 | `backend/src/main/java/com/fxplatform/risk/service/MarginCalculator.java` | 包装 `TradingAlgorithmEngine.requiredMargin()` |
| 盈亏计算 | `backend/src/main/java/com/fxplatform/risk/service/PnLCalculator.java` | 包装 `TradingAlgorithmEngine.unrealizedPnl()` |
| 产品分类 | `backend/src/main/java/com/fxplatform/risk/service/TradingInstrumentClassifier.java` | 根据 symbol assetClass/base/quote 推导 FOREX/SPOT/LINEAR_PERPETUAL/INVERSE_PERPETUAL |
| 算法引擎 | `backend/src/main/java/com/fxplatform/risk/service/TradingAlgorithmEngine.java` | 现货买卖、保证金、浮盈亏、强平价、手续费、ROI |
| 执行接口 | `backend/src/main/java/com/fxplatform/execution/ExecutionAdapter.java` | 执行适配器抽象 |
| demo 执行 | `backend/src/main/java/com/fxplatform/execution/SimulatedExecutionAdapter.java` | demo market order 成交、滑点、手续费、部分成交 |
| pending order 扫描 | `backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java` | 条件启用，扫描 LIMIT/STOP 触发成交 |
| 止盈止损扫描 | `backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java` | 条件启用，扫描 SL/TP，委托 PositionService 平仓 |
| 后台强制平仓 | `backend/src/main/java/com/fxplatform/admin/service/AdminTradingCommandService.java` | 后台取消订单、force close position |
| 流水服务 | `backend/src/main/java/com/fxplatform/ledger/service/LedgerService.java` | 写入 ORDER_HOLD/MARGIN_HOLD/MARGIN_RELEASE/TRADE_PNL 等流水 |

### 2.2 前端核心模块

| 领域 | 当前文件 | 职责 |
| --- | --- | --- |
| 交易表单状态 | `apps/web/src/features/trading/types/order.ts` | TradeMarket/TradeFormState 类型 |
| 市场转交易面板模型 | `apps/web/src/features/trading/components/tradePanelMarket.ts` | 决定 unitSize、quantityMode、leverage |
| 表单计算与校验 | `apps/web/src/features/trading/hooks/useTradeForm.ts` | notional、requiredMargin、余额校验、百分比下单 |
| payload 转换 | `apps/web/src/features/trading/services/orderAdapter.ts` | 前端 form 转后端 OrderPayload |
| trading session | `apps/web/src/features/trading-session/useTradingSession.ts` | 拉账户、订单、持仓、ledger，提交订单，平仓 |
| 余额派生 | `apps/web/src/features/trading-session/tradingSessionModels.ts` | 用 account.freeMargin 和 BUY positions 派生前端 balances |
| 持仓展示 | `apps/web/src/pages/trading/components/positionDisplayModel.ts` | 持仓表格展示、杠杆、强平价、保证金模式、浮盈亏格式化 |

## 3. 数据库结构与交易状态

### 3.1 账户表

`V3__account_tables.sql` 创建 `core.trading_accounts`：

```text
id
user_id
account_type
base_currency
balance
equity
used_margin
free_margin
margin_level
leverage
status
created_at
updated_at
```

当前账户是一个交易账户，不是多资产钱包。`base_currency` 默认 `USD`，因此 `freeMargin` 也默认以账户基准币表达。

### 3.2 市场品种表

`V4__market_tables.sql` 创建 `market.symbols`：

```text
symbol
display_name
provider
provider_symbol
asset_class
base_currency
quote_currency
pip_size
tick_size
lot_size
min_lot
max_lot
leverage
spread_markup
enabled
```

当前产品分类主要依赖 `asset_class`：

```text
FOREX              -> 外汇
CRYPTO / SPOT      -> 现货
LINEAR_PERPETUAL   -> 线性永续
INVERSE_PERPETUAL  -> 反向永续
PERPETUAL/SWAP/FUTURES/CONTRACT -> 合约类
```

注意：`V27__crypto_symbol_provider_seed.sql` 中 `BTCUSDT/ETHUSDT/SOLUSDT/XRPUSDT` 的 `asset_class` 是 `CRYPTO`，`leverage` 是 `20`。但当前后端 `TradingInstrumentClassifier` 会优先把 `CRYPTO` 判定为 `SPOT`。这会造成一个语义不一致：

```text
DB seed:
  asset_class = CRYPTO
  leverage = 20

Backend classifier:
  CRYPTO -> SPOT
  SPOT -> leverage forced to 1

Frontend:
  category crypto + leverage 20 -> quantity mode, looks like margin/contract behavior
```

这块是当前三类产品拆分中最需要修正的点。

### 3.3 订单表

`trading.orders` 当前实体包含：

```text
id
userId
accountId
symbol
side
orderType
status
lots
requestedPrice
executionPrice
clientOrderId
quantity
price
filledQuantity
remainingQuantity
avgFillPrice
fee
slippage
holdAmount
holdCurrency
rejectCode
rejectMessage
stopLoss
takeProfit
idempotencyKey
leverage
createdAt
updatedAt
filledAt
canceledAt
```

订单状态枚举：

```text
RECEIVED
VALIDATING
ACCEPTED
WORKING
PARTIALLY_FILLED
PENDING
FILLED
CANCEL_PENDING
CANCELED
CANCELLED
REJECTED
FAILED
```

订单类型枚举：

```text
MARKET
LIMIT
STOP
```

### 3.4 持仓表

`trading.positions` 当前实体包含：

```text
id
accountId
symbol
side
lots
openPrice
currentPrice
stopLoss
takeProfit
floatingPnl
realizedPnl
marginHeld
status
leverage
openedAt
closedAt
```

持仓状态只有：

```text
OPEN
CLOSED
```

当前没有 `reduceOnly`、`positionSide`、`isolated/cross margin` 字段，也没有持仓合并/净仓字段。

### 3.5 流水表

`ledger.ledger_entries` 当前记录：

```text
account_id
entry_type
amount
balance_after
currency
reference_type
reference_id
description
created_at
```

流水类型：

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

这是账户资金流水，不是完整 double-entry 资产流水。现货如果要做真实钱包，需要额外记录 base/quote asset 的资产流水。

## 4. 当前统一交易生命周期

### 4.1 账户创建

`AccountService.createDemoAccount()` 创建 demo 账户，初始化：

```text
balance = initialBalance
equity = initialBalance
usedMargin = 0
freeMargin = initialBalance
leverage = trading.default-leverage
```

同时写入 `DEMO_DEPOSIT` ledger。

### 4.2 前端下单

前端交易面板根据市场信息生成 `TradeMarket`：

```text
symbol
lastPrice
bestBid
bestAsk
baseAsset
quoteAsset
unitSize
quantityMode
leverage
```

关键规则在 `tradePanelMarket.ts`：

```text
外汇或 fx category:
  unitSize = 100000

其他产品:
  unitSize = 1

crypto 且 leverage 不存在或 <= 1:
  quantityMode = quote-budget

其他:
  quantityMode = quantity
```

前端 `useTradeForm.ts` 计算：

```text
notional = amount * price * unitSize

if quantityMode == quantity:
  requiredMargin = notional / leverage
else:
  requiredMargin = notional
```

余额校验：

```text
BUY:
  requiredMargin <= quoteBalance

SELL + margin/contract:
  requiredMargin <= quoteBalance

SELL + spot:
  amount <= baseBalance
```

`orderAdapter.ts` 生成后端 payload：

```text
accountId
symbol normalized
side BUY/SELL
orderType MARKET/LIMIT/STOP
quantity
price
lots
requestedPrice
stopLoss
takeProfit
clientOrderId
idempotencyKey
leverage
```

### 4.3 后端下单

`OrderService.createOrder()` 流程：

```text
1. OrderCommandFactory.from(principal, request)
2. 根据 user/account/clientOrderId 或 idempotencyKey 查重
3. 验证账户归属
4. 非 MARKET 订单要求 price
5. RiskCheckService.checkOrder(account, request)
6. resolveEffectiveLeverage()
7. 创建 OrderEntity
8. MARKET 订单直接执行
9. LIMIT/STOP 订单进入 PENDING 并冻结 holdAmount
```

pending order 账户变化：

```text
usedMargin = usedMargin + requiredMargin
freeMargin = equity - usedMargin
ledger = ORDER_HOLD
order.holdAmount = requiredMargin
```

pending order 取消：

```text
usedMargin = max(usedMargin - holdAmount, 0)
freeMargin = equity - usedMargin
ledger = ORDER_RELEASE
order.status = CANCELED
```

### 4.4 风控与保证金校验

`RiskCheckService.checkOrder()` 做下单前保证金校验：

```text
1. request.quantity() 必须 > 0
2. 根据 request.symbol 读取 SymbolEntity
3. 拉 QuoteService.freshQuote(symbol)
4. BUY 使用 ask，SELL 使用 bid
5. TradingInstrumentClassifier.profile(symbol)
6. effectiveLeverage(account, symbol, request.leverage)
7. MarginCalculator.requiredMargin(kind, quantity, price, leverage, unitSize)
8. account.freeMargin >= requiredMargin
```

effective leverage 规则：

```text
if profile.kind == SPOT:
  leverage = 1
else:
  leverage = request.leverage 或 account.leverage
  if symbol.leverage 存在:
    leverage = min(request/account leverage, symbol.leverage)
```

当前 `risk.risk_configs` 中的 `max_leverage/max_lots/margin_call_level/stop_out_level` 没有接入 `RiskCheckService` 主链路。

### 4.5 执行与成交

当前 demo 执行器是 `SimulatedExecutionAdapter`：

```text
referencePrice = BUY ? ask : bid
slippage = referencePrice * 0.0001
filledPrice = BUY ? referencePrice + slippage : referencePrice - slippage

if quantity > 1:
  filledQuantity = quantity * 0.5
else:
  filledQuantity = quantity

remainingQuantity = quantity - filledQuantity
fee = filledPrice * filledQuantity * 0.001
```

注意：这里的 fee 没有乘 `unitSize`。对 crypto spot 这种 `unitSize=1` 产品还可以接受；对外汇 lot 或合约产品会偏简化。

真实执行适配器 `BrokerExecutionAdapter/FixExecutionAdapter/LpExecutionAdapter` 当前还属于预留状态。

### 4.6 成交结算与开仓

`OrderFillService.fill()` 负责成交后的统一写入：

```text
1. 更新 order:
   status = FILLED 或 PARTIALLY_FILLED
   executionPrice/avgFillPrice/filledQuantity/remainingQuantity/fee/slippage/filledAt

2. 创建 TradeEntity:
   orderId/accountId/symbol/side/lots/price

3. 创建 PositionEntity:
   accountId/symbol/side/lots/openPrice/currentPrice
   stopLoss/takeProfit
   marginHeld
   leverage

4. 处理账户:
   delta = marginToHold - existingOrderHold
   if delta > 0:
     reserveMarginIfAvailable(delta)
     usedMargin += delta
   if delta < 0:
     usedMargin = max(usedMargin + delta, 0)
   if fee > 0:
     balance -= fee
     equity -= fee
   freeMargin = equity - usedMargin

5. 写流水:
   delta > 0 -> MARGIN_HOLD
   fee > 0 -> TRADE_FEE
```

当前每次成交都会创建一个新 position，不会自动合并同向持仓，也不会用反向订单抵扣已有持仓。

### 4.7 持仓浮盈亏

`PositionService.openPositions()` 对 OPEN 持仓做实时展示：

```text
quote = QuoteService.freshQuote(symbol)
currentPrice = BUY ? bid : ask
markPrice = quote.mid 或 currentPrice
floatingPnl = displayPnl(position, currentPrice)
liquidationPrice = TradingAlgorithmEngine.liquidationPrice(...)
floatingPnlRatio = floatingPnl / marginHeld
```

展示逻辑：

```text
SPOT:
  marginMode = CASH
  leverage = null
  liquidationPrice = null

FOREX / LINEAR_PERPETUAL / INVERSE_PERPETUAL:
  marginMode = CROSS
  leverage = position.leverage 或 account.leverage
  liquidationPrice = 根据算法计算
```

注意：当前持仓查询只计算响应中的浮盈亏，不回写数据库，也不刷新账户 `equity/freeMargin/marginLevel`。因此账户摘要并不是实时 mark-to-market 的净值快照。

### 4.8 平仓与账户变化

用户主动平仓：

```text
TradingController.closePosition()
  -> PositionService.closePosition(userId, accountId, positionId)
  -> closeOwnedPosition()
```

系统平仓：

```text
ProtectiveOrderExecutionService
  -> PositionService.closeSystemPosition(accountId, positionId)
```

后台强制平仓：

```text
AdminTradingCommandService.forceClosePosition()
  -> PositionService.closeSystemPosition(accountId, positionId)
```

平仓价格：

```text
BUY position:
  closePrice = bid

SELL position:
  closePrice = ask
```

平仓账户变化：

```text
realizedPnl = displayPnl(position, closePrice)
marginToRelease = position.marginHeld

position:
  currentPrice = closePrice
  floatingPnl = 0
  realizedPnl = realizedPnl
  marginHeld = 0
  status = CLOSED
  closedAt = now

account:
  balance = balance + realizedPnl
  equity = balance
  usedMargin = max(usedMargin - marginToRelease, 0)
  freeMargin = equity - usedMargin

ledger:
  MARGIN_RELEASE
  TRADE_PNL
```

并发保护：

```text
positionRepository.closeIfOpen(position)
```

这能防止重复平仓重复释放保证金。

### 4.9 强平当前状态

当前系统有：

- `TradingAlgorithmEngine.liquidationPrice()`：计算理论强平价。
- `PositionResponse.liquidationPrice`：向前端展示强平价。
- `AdminTradingCommandService.forceClosePosition()`：后台强制平仓入口。
- `PositionService.closeSystemPosition()`：系统级平仓结算路径。

当前系统没有：

- 自动扫描账户保证金率的 `LiquidationService`。
- 维持保证金 `maintenanceMargin` 的实际计算与持仓字段。
- 根据 `stopOutLevel` 自动触发强平。
- 强平手续费、保险基金、ADL 等永续合约机制。

## 5. 产品分类规则

当前产品分类由 `TradingInstrumentClassifier.profile(SymbolEntity symbol)` 决定。

### 5.1 分类结果

```text
InstrumentKind:
  FOREX
  SPOT
  LINEAR_PERPETUAL
  INVERSE_PERPETUAL
```

`InstrumentProfile` 包含：

```text
kind
unitSize
instrumentType
positionUnit
```

### 5.2 当前规则

```text
assetClass in LINEAR_PERPETUAL, PERPETUAL, SWAP, FUTURES, CONTRACT:
  kind = LINEAR_PERPETUAL
  unitSize = symbol.lotSize 或 1
  instrumentType = SWAP
  positionUnit = CONTRACT

assetClass == INVERSE_PERPETUAL:
  kind = INVERSE_PERPETUAL
  unitSize = symbol.lotSize 或 100
  instrumentType = SWAP
  positionUnit = CONTRACT

assetClass == SPOT or CRYPTO:
  kind = SPOT
  unitSize = symbol.lotSize 或 1
  instrumentType = SPOT
  positionUnit = baseCurrency

其他:
  kind = FOREX
  unitSize = symbol.lotSize 或 100000
  instrumentType = FOREX
  positionUnit = LOT
```

这意味着当前必须显式把永续 symbol 的 `asset_class` 设成 `LINEAR_PERPETUAL` 或 `INVERSE_PERPETUAL`，否则 `CRYPTO` 会按现货走。

## 6. 外汇交易架构

### 6.1 当前定位

外汇是当前代码最完整、最贴近真实保证金账户的产品类型。

当前依赖：

```text
asset_class = FOREX
unitSize = 100000 或 symbol.lot_size
positionUnit = LOT
instrumentType = FOREX
leverage = account/symbol/request resolved leverage
marginMode = CROSS
```

### 6.2 外汇下单链路

```text
用户输入 lots
  -> 前端 unitSize = 100000
  -> notional = lots * price * 100000
  -> requiredMargin = notional / leverage
  -> 后端 RiskCheckService 使用 ask/bid 重新计算
  -> MARKET 执行或 LIMIT/STOP 冻结保证金
  -> 成交后创建 position
```

### 6.3 外汇保证金算法

当前算法：

```text
notional = abs(lots) * unitSize * price
requiredMargin = notional / leverage
```

标准 lot：

```text
unitSize = 100000
```

示例：

```text
EURUSD price = 1.1000
lots = 0.10
leverage = 100

notional = 0.10 * 100000 * 1.1000 = 11000 USD
requiredMargin = 11000 / 100 = 110 USD
```

### 6.4 外汇浮盈亏算法

当前算法：

```text
BUY:
  floatingPnl = (currentBid - openPrice) * lots * 100000

SELL:
  floatingPnl = (openPrice - currentAsk) * lots * 100000
```

代码上 OPEN 持仓展示：

```text
BUY 使用 bid
SELL 使用 ask
```

这是合理的 closeout price 口径。

### 6.5 外汇账户变化

开仓前：

```text
balance = 10000
equity = 10000
usedMargin = 0
freeMargin = 10000
```

开仓成交后：

```text
usedMargin += requiredMargin
balance -= fee
equity -= fee
freeMargin = equity - usedMargin
position.marginHeld = requiredMargin
```

持仓中：

```text
PositionResponse.floatingPnl 动态计算
AccountResponse.equity 当前不会自动包含 floatingPnl
```

平仓后：

```text
balance += realizedPnl
equity = balance
usedMargin -= marginHeld
freeMargin = equity - usedMargin
position.status = CLOSED
ledger = MARGIN_RELEASE + TRADE_PNL
```

### 6.6 外汇当前缺口

1. 账户摘要没有实时 mark-to-market。
2. `marginLevel` 没有在交易链路中持续更新。
3. `risk_configs.max_lots/max_leverage/margin_call_level/stop_out_level` 未接入。
4. fee 模型对 lot 产品偏简化。
5. 没有自动 margin call / stop out。

### 6.7 外汇建议业务层方案

```text
ForexMarginTradingService:
  - validateOrder()
  - calculateInitialMargin()
  - openPosition()
  - markPosition()
  - closePosition()
  - calculateAccountSnapshot()
```

建议账户快照：

```text
openFloatingPnl = sum(open positions floatingPnl)
equity = balance + openFloatingPnl
usedMargin = sum(position.marginHeld)
freeMargin = equity - usedMargin
marginLevel = equity / usedMargin * 100
```

建议风控：

```text
if requestedLots > riskConfig.maxLots:
  reject

if requestedLeverage > min(symbol.leverage, riskConfig.maxLeverage):
  cap or reject

if projectedMarginLevel < marginCallLevel:
  reject new order
```

## 7. 虚拟币现货交易架构

### 7.1 当前定位

当前代码把 `asset_class = CRYPTO` 和 `asset_class = SPOT` 都归类为 `InstrumentKind.SPOT`。

现货当前行为：

```text
leverage = 1
requiredMargin = full notional
marginMode = CASH
position.leverage = null in response
liquidationPrice = null
```

这符合“无杠杆、无强平”的方向。

但当前后端没有资产钱包表，所以现货买入不是：

```text
USDT available 减少
BTC available 增加
```

而是：

```text
usedMargin 增加
freeMargin 减少
创建 BUY position
```

这更像“用保证金账户模拟一笔现货库存”。

### 7.2 前端现货下单

前端在 `tradePanelMarket.ts` 中判断：

```text
category = crypto
leverage 缺失或 <= 1
  -> quantityMode = quote-budget
```

market buy 时：

```text
用户输入 total quote budget
amount = total / (marketPrice * unitSize)
```

现货 BUY 余额校验：

```text
requiredMargin = notional
requiredMargin <= quoteBalance
```

现货 SELL 余额校验：

```text
amount <= baseBalance
```

但 `baseBalance` 当前是从 BUY open positions 派生出来的：

```text
deriveTradingBalances():
  quote balance = account.freeMargin
  base balance += open BUY position lots
```

这不是严格钱包余额，只是 UI 预览层的近似。

### 7.3 现货保证金/资金占用算法

当前算法：

```text
notional = abs(quantity) * unitSize * price
requiredMargin = notional
leverage = 1
```

示例：

```text
BTCUSDT price = 100000
quantity = 0.01 BTC

notional = 0.01 * 1 * 100000 = 1000 USDT
requiredMargin = 1000 USDT
```

### 7.4 现货浮盈亏算法

当前算法复用 `TradingAlgorithmEngine.unrealizedPnl()` 的非 inverse 公式：

```text
BUY:
  floatingPnl = (currentBid - openPrice) * quantity * unitSize

SELL:
  floatingPnl = (openPrice - currentAsk) * quantity * unitSize
```

但对真实现货，SELL 通常应表达为卖出资产并结算 quote，不应长期保留一个 `SELL spot position`。当前代码支持 SELL position，但这更像保证金/合约语义，不是严格现货语义。

### 7.5 现货账户变化：当前实现

买入成交后当前实现：

```text
usedMargin += full notional
freeMargin = equity - usedMargin
position:
  side = BUY
  lots = base quantity
  marginHeld = full notional
  leverage = 1
ledger = MARGIN_HOLD
```

卖出成交后当前实现：

```text
也会创建 SELL position
也会占用 requiredMargin
```

这不符合真实现货卖出逻辑。真实现货卖出应该消耗 base asset 并增加 quote asset。

平仓当前实现：

```text
realizedPnl = (closePrice - openPrice) * quantity
balance += realizedPnl
usedMargin -= marginHeld
freeMargin = equity - usedMargin
position.status = CLOSED
```

真实现货卖出/平仓应更接近：

```text
买入:
  quote.available -= grossQuote + feeQuote
  base.total += netBase

卖出:
  base.available -= baseQty
  quote.total += netQuote
  realizedPnl = netQuote - costBasis
```

### 7.6 现货当前缺口

1. 缺 `wallet_balances`：没有 base/quote 多资产余额。
2. 缺资产流水：没有记录 `USDT -1000`、`BTC +0.01` 这种 double-entry 资产变动。
3. 缺 cost basis：无法精确计算 FIFO/平均成本/税务口径的现货 realized PnL。
4. SELL 现货语义不完整：当前可能创建 SELL position，而不是卖出已有 base asset。
5. `CRYPTO` seed 带 `leverage=20`，但后端按 SPOT，前端可能按杠杆产品展示。
6. `positionDisplayModel.marginModeLabel()` 只识别 `CROSS/ISOLATED`，后端返回 `CASH` 时前端展示可能是 `--`。

### 7.7 现货建议业务层方案

新增钱包聚合：

```text
WalletBalance:
  accountId
  asset
  total
  available
  locked
```

新增现货结算服务：

```text
SpotSettlementService:
  reserveBuyQuoteBudget()
  reserveSellBaseQuantity()
  settleBuyFill()
  settleSellFill()
  releaseUnfilledHold()
  calculateCostBasis()
```

现货 BUY：

```text
下单:
  quote.locked += quoteBudget
  quote.available -= quoteBudget

成交:
  quote.locked -= grossQuote + feeQuote
  quote.available += unfilledQuoteRelease
  base.total += netBase
  base.available += netBase
  assetLedger:
    quote OUT
    base IN
```

现货 SELL：

```text
下单:
  base.locked += baseQty
  base.available -= baseQty

成交:
  base.locked -= filledBase
  quote.total += netQuote
  quote.available += netQuote
  realizedPnl = netQuote - costBasis(filledBase)
  assetLedger:
    base OUT
    quote IN
```

现货不应使用：

```text
usedMargin
marginHeld
liquidationPrice
leverage
```

除非系统刻意把“现货库存”也映射到 position 展示，但资金真实性仍应来自 wallet。

## 8. 虚拟币永续合约架构

### 8.1 当前定位

永续合约算法层已经存在：

```text
InstrumentKind.LINEAR_PERPETUAL
InstrumentKind.INVERSE_PERPETUAL
TradingAlgorithmEngine.requiredMargin()
TradingAlgorithmEngine.unrealizedPnl()
TradingAlgorithmEngine.liquidationPrice()
TradingAlgorithmEngine.linearFee()
TradingAlgorithmEngine.inverseFee()
TradingAlgorithmEngine.netPnl()
TradingAlgorithmEngine.roi()
```

但业务链路需要 symbol 正确分类：

```text
asset_class = LINEAR_PERPETUAL
asset_class = INVERSE_PERPETUAL
asset_class = PERPETUAL/SWAP/FUTURES/CONTRACT
```

如果还是 `asset_class = CRYPTO`，后端会按 SPOT 处理。

### 8.2 线性永续 USDT 本位

适合：

```text
BTCUSDT perpetual
ETHUSDT perpetual
SOLUSDT perpetual
```

当前分类：

```text
kind = LINEAR_PERPETUAL
unitSize = symbol.lotSize 或 1
instrumentType = SWAP
positionUnit = CONTRACT
marginMode = CROSS
```

保证金算法：

```text
notional = abs(quantity) * unitSize * price
requiredMargin = notional / leverage
```

浮盈亏算法：

```text
BUY:
  pnl = (closeoutPrice - entryPrice) * abs(quantity) * unitSize

SELL:
  pnl = (entryPrice - closeoutPrice) * abs(quantity) * unitSize
```

强平价当前简化算法：

```text
exposureUnits = abs(quantity) * unitSize
priceMoveToZero = marginHeld / exposureUnits

BUY:
  liquidationPrice = entryPrice - priceMoveToZero

SELL:
  liquidationPrice = entryPrice + priceMoveToZero
```

示例：

```text
BTCUSDT perpetual
entryPrice = 100000
quantity = 0.1
unitSize = 1
leverage = 10

notional = 0.1 * 1 * 100000 = 10000 USDT
initialMargin = 10000 / 10 = 1000 USDT

BUY close at 101000:
  pnl = (101000 - 100000) * 0.1 = 100 USDT

BUY simplified liquidation:
  priceMoveToZero = 1000 / 0.1 = 10000
  liquidationPrice = 90000
```

注意：这个强平价没有加入维持保证金、手续费缓冲、资金费率、标记价格偏差，因此只能作为展示级近似。

### 8.3 反向永续币本位

适合：

```text
BTCUSD perpetual
ETHUSD perpetual
```

当前分类：

```text
kind = INVERSE_PERPETUAL
unitSize = symbol.lotSize 或 100
instrumentType = SWAP
positionUnit = CONTRACT
```

保证金算法：

```text
requiredMargin = abs(contracts) * contractSize / (price * leverage)
```

盈亏算法：

```text
usdNotional = abs(contracts) * contractSize

BUY:
  pnl = usdNotional * (1 / entryPrice - 1 / closeoutPrice)

SELL:
  pnl = usdNotional * (1 / closeoutPrice - 1 / entryPrice)
```

手续费：

```text
fee = usdNotional / price * feeRate
```

强平价当前简化算法：

```text
entryInverse = 1 / entryPrice
marginPerUsd = marginHeld / usdNotional

BUY:
  liquidationInverse = entryInverse + marginPerUsd
  liquidationPrice = 1 / liquidationInverse

SELL:
  liquidationInverse = entryInverse - marginPerUsd
  liquidationPrice = 1 / liquidationInverse
```

### 8.4 永续账户变化

开仓：

```text
usedMargin += initialMargin
freeMargin = equity - usedMargin
position.marginHeld = initialMargin
position.leverage = effectiveLeverage
ledger = MARGIN_HOLD
```

持仓中：

```text
floatingPnl = 按 mark/closeout price 动态计算
liquidationPrice = 根据 marginHeld 粗略计算
floatingPnlRatio = floatingPnl / marginHeld
```

平仓：

```text
realizedPnl = 当前 closeout price 计算
balance += realizedPnl
usedMargin -= marginHeld
freeMargin = equity - usedMargin
ledger = MARGIN_RELEASE + TRADE_PNL
```

当前没有资金费率结算：

```text
fundingFee 未定时结算
fundingPnl 未落库
netPnl() 虽存在，但业务链路未使用 funding 参数
```

### 8.5 永续当前缺口

1. 缺明确 provider futures/perpetual symbol seed。
2. `CRYPTO` spot 和 perpetual 没拆清楚。
3. 缺 mark price / index price / last price 的正式区分。
4. 缺 maintenance margin。
5. 缺自动 liquidation scan。
6. 缺 funding rate 定时结算。
7. 缺 isolated/cross margin 真实模型。
8. 缺 reduceOnly、positionSide、同向合并、反向减仓。
9. 缺 maker/taker fee、开仓手续费、平仓手续费分别落账。
10. 缺合约单位、结算币、保证金币字段。

### 8.6 永续建议业务层方案

```text
PerpetualTradingService:
  - validateOpenOrder()
  - validateReduceOnlyOrder()
  - calculateInitialMargin()
  - calculateMaintenanceMargin()
  - openOrIncreasePosition()
  - reduceOrClosePosition()
  - markToMarket()
  - settleFunding()
  - liquidatePosition()
```

建议新增：

```text
MarketPriceService:
  lastPrice
  bid/ask
  markPrice
  indexPrice

FundingService:
  currentFundingRate
  nextFundingTime
  settleFunding(accountId, positionId)

LiquidationService:
  scanAccounts()
  scanPositions()
  calculateMarginRatio()
  forceCloseByRisk()
```

自动强平应基于：

```text
accountEquity = balance + openFloatingPnl
maintenanceMargin = sum(position maintenanceMargin)
marginRatio = accountEquity / maintenanceMargin

if marginRatio <= stopOutLevel:
  liquidate
```

或者按交易所常见口径：

```text
marginBalance = walletBalance + unrealizedPnl
maintenanceMargin = positionNotional * maintenanceMarginRate
if marginBalance <= maintenanceMargin + liquidationFeeBuffer:
  liquidate
```

## 9. 推荐的目标分层架构

### 9.1 产品目录层

新增或强化 `ProductCatalog` / `SymbolProfileService`：

```text
SymbolProfile:
  symbol
  productType
  baseAsset
  quoteAsset
  settlementAsset
  marginAsset
  contractSize
  tickSize
  stepSize
  minQty
  minNotional
  maxLeverage
  makerFeeRate
  takerFeeRate
  maintenanceMarginRate
```

建议产品类型：

```text
FX_MARGIN
CRYPTO_SPOT
LINEAR_PERP
INVERSE_PERP
```

不要继续只靠宽泛的 `CRYPTO` 表达现货和永续。

### 9.2 订单应用层

```text
OrderApplicationService:
  createOrder(command)
  cancelOrder(orderId)
  modifyOrder(orderId)
```

职责：

```text
1. 幂等控制
2. 订单状态流转
3. 调用 ProductCatalog
4. 调用 RiskEngine
5. 调用 ExecutionService
6. 把 fill 交给 SettlementService
```

### 9.3 风控层

```text
RiskEngine:
  checkNewOrder()
  checkModifyOrder()
  checkCloseOrder()
  checkAccountRisk()
```

按产品分发：

```text
FX_MARGIN:
  freeMargin >= initialMargin
  leverage <= maxLeverage
  lots <= maxLots

CRYPTO_SPOT:
  buy: quote.available >= quoteBudget
  sell: base.available >= baseQty

LINEAR_PERP/INVERSE_PERP:
  freeMargin >= initialMargin + openFee
  projectedMarginRatio > threshold
  leverage <= maxLeverage
```

### 9.4 执行层

```text
ExecutionService:
  route by productType/provider/accountMode
```

适配器：

```text
DemoExecutionAdapter
ForexBrokerExecutionAdapter
CryptoSpotExecutionAdapter
CryptoPerpExecutionAdapter
FixExecutionAdapter
LpExecutionAdapter
```

执行返回应包含：

```text
filledPrice
filledQuantity
remainingQuantity
fee
feeAsset
liquidityRole
tradeId
executionTime
```

### 9.5 成交结算层

建议把当前 `OrderFillService` 拆成产品感知结算：

```text
FillSettlementService:
  settle(fill, order, account, symbolProfile)
```

分产品：

```text
ForexMarginSettlement:
  create/update margin position
  reserve/release margin
  fee in account currency

SpotSettlement:
  update wallet balances
  update asset ledger
  optionally create inventory position view

PerpSettlement:
  open/increase/reduce/close contract position
  update margin
  apply fee
  record realizedPnl/fundingPnl
```

### 9.6 持仓层

当前 position 只有 OPEN/CLOSED。建议升级：

```text
Position:
  productType
  symbol
  side
  positionSide
  quantity
  averageEntryPrice
  markPrice
  notional
  initialMargin
  maintenanceMargin
  unrealizedPnl
  realizedPnl
  fundingPnl
  marginMode
  leverage
  settlementAsset
  status
```

持仓更新模式：

```text
同向成交:
  加仓，重算 averageEntryPrice

反向成交:
  reduceOnly 或 one-way mode 下先减仓
  超过已有仓位再反向开仓

hedge mode:
  LONG/SHORT 独立仓位
```

### 9.7 账户快照层

不要只读 `TradingAccountEntity.equity`。建议引入动态快照：

```text
AccountSnapshotService:
  balance
  walletBalances
  openFloatingPnl
  equity
  usedMargin
  freeMargin
  marginLevel
  maintenanceMargin
  liquidationRisk
```

保证金类：

```text
equity = balance + sum(unrealizedPnl)
usedMargin = sum(position.initialMargin)
freeMargin = equity - usedMargin
marginLevel = equity / usedMargin * 100
```

现货类：

```text
wallet asset total/available/locked 是真相
equity 可以按 mark price 折算成账户基准币
```

## 10. 推荐算法层接口

建议把当前 `TradingAlgorithmEngine` 拆成产品计算器接口：

```java
public interface ProductCalculator {
  BigDecimal requiredInitialMargin(OrderInput order, SymbolProfile symbol);

  BigDecimal maintenanceMargin(PositionInput position, SymbolProfile symbol, MarketPrice price);

  BigDecimal unrealizedPnl(PositionInput position, MarketPrice price);

  BigDecimal realizedPnl(PositionInput position, CloseInput close);

  BigDecimal liquidationPrice(PositionInput position, AccountSnapshot account, SymbolProfile symbol);

  FeeResult fee(TradeInput trade, SymbolProfile symbol);
}
```

实现：

```text
ForexMarginCalculator
CryptoSpotCalculator
LinearPerpetualCalculator
InversePerpetualCalculator
```

### 10.1 ForexMarginCalculator

```text
notional = abs(lots) * contractSize * price
initialMargin = notional / leverage

BUY pnl = (closeBid - entryAsk) * lots * contractSize
SELL pnl = (entryBid - closeAsk) * lots * contractSize
```

### 10.2 CryptoSpotCalculator

```text
BUY:
  grossBase = quoteBudget / price
  feeBase or feeQuote by fee asset
  netBase = grossBase - feeBase
  averageCost = quoteBudget / netBase

SELL:
  grossQuote = baseQty * price
  feeQuote = grossQuote * feeRate
  netQuote = grossQuote - feeQuote
  realizedPnl = netQuote - costBasis
```

无：

```text
leverage
margin
liquidationPrice
funding
```

### 10.3 LinearPerpetualCalculator

```text
notional = abs(quantity) * contractSize * markPrice
initialMargin = notional / leverage
maintenanceMargin = notional * maintenanceMarginRate

BUY pnl = (markPrice - entryPrice) * quantity * contractSize
SELL pnl = (entryPrice - markPrice) * quantity * contractSize

fee = abs(quantity) * contractSize * tradePrice * feeRate
funding = notional * fundingRate * sideSign
netPnl = grossPnl - openFee - closeFee + funding
```

强平价应加入：

```text
maintenanceMarginRate
feeBuffer
fundingPnl
cross account equity
```

### 10.4 InversePerpetualCalculator

```text
usdNotional = abs(contracts) * contractSize
initialMargin = usdNotional / (entryPrice * leverage)

BUY pnl = usdNotional * (1 / entryPrice - 1 / markPrice)
SELL pnl = usdNotional * (1 / markPrice - 1 / entryPrice)

fee = usdNotional / tradePrice * feeRate
```

## 11. 三类产品对照表

| 项目 | 外汇 | 虚拟币现货 | 虚拟币永续合约 |
| --- | --- | --- | --- |
| 当前 kind | `FOREX` | `SPOT` | `LINEAR_PERPETUAL` / `INVERSE_PERPETUAL` |
| 当前 assetClass | `FOREX` | `CRYPTO` / `SPOT` | 需要显式 `LINEAR_PERPETUAL` / `INVERSE_PERPETUAL` |
| 是否杠杆 | 是 | 否 | 是 |
| 当前 leverage | request/account/symbol 取 min | 强制 1 | request/account/symbol 取 min |
| 下单资金校验 | `freeMargin >= initialMargin` | 当前也是 `freeMargin >= fullNotional` | `freeMargin >= initialMargin` |
| 正确资金校验 | 同当前方向 | buy 看 quote available，sell 看 base available | 同当前方向，但需加 fee/maintenance buffer |
| 开仓后 | 创建 position，占 margin | 当前创建 position，占 full notional | 创建 position，占 initial margin |
| 持仓浮盈亏 | 有 | 当前有，但更像库存估值 | 有 |
| 平仓 | 释放 margin，结算 PnL | 当前释放 full notional，结算 PnL；目标应走钱包卖出 | 释放 margin，结算 PnL |
| 强平 | 应有 stop-out，当前无自动服务 | 无 | 应有 liquidation，当前只有价格展示/后台强平 |
| 资金费率 | 无 | 无 | 应有，当前未接业务链路 |
| 主要缺口 | 实时 equity/marginLevel | 钱包与资产流水 | 自动强平、资金费率、维持保证金 |

## 12. 推荐落地顺序

### 阶段 1：产品分类先修正

目标：不要让 `CRYPTO` 同时承担现货和合约含义。

建议：

```text
新增 productType:
  FX_MARGIN
  CRYPTO_SPOT
  LINEAR_PERP
  INVERSE_PERP

迁移:
  BTCUSDT spot -> CRYPTO_SPOT
  BTCUSDT perpetual -> LINEAR_PERP
```

同时修正前端：

```text
quantityMode 不再只根据 category + leverage 推断
改为根据 productType:
  CRYPTO_SPOT -> quote-budget
  FX_MARGIN/LINEAR_PERP/INVERSE_PERP -> quantity
```

### 阶段 2：现货钱包

新增：

```text
core.wallet_balances
ledger.asset_ledger_entries
SpotSettlementService
```

先覆盖：

```text
spot buy
spot sell
spot cancel/release hold
spot balance display
```

### 阶段 3：账户动态快照

新增：

```text
AccountSnapshotService
```

用于：

```text
account summary
risk check
margin level
free margin
frontend bottom account panel
```

### 阶段 4：永续基础完整化

新增：

```text
maintenanceMarginRate
markPrice/indexPrice
fundingRate
openFee/closeFee
fundingPnl
reduceOnly
positionSide
```

### 阶段 5：自动强平

新增：

```text
LiquidationService
LiquidationEvent
LIQUIDATION_FEE ledger type
FORCED_CLOSE reason
```

触发规则：

```text
if account equity <= maintenance margin + liquidation buffer:
  close risky position
```

## 13. 当前代码实现的关键风险点

1. `CRYPTO` 当前被后端分类为 `SPOT`，但 seed leverage 是 `20`，前端可能显示成杠杆产品。
2. `OrderFillService` 总是新建 position，缺同向合并和反向减仓。
3. 账户 `equity/freeMargin/marginLevel` 不包含实时浮盈亏。
4. 现货没有 wallet，SELL 语义不真实。
5. 永续没有 funding 和 maintenance margin。
6. 强平只有手动/后台入口，没有自动风险扫描。
7. `risk_configs` 还没有接入下单风控。
8. demo fee 对外汇/合约没有乘 `unitSize`，与保证金 notional 口径不一致。
9. `PositionService` 中 fallback symbol 会按 position leverage 猜现货或永续，这只是兜底逻辑，不应作为正式产品识别。
10. 前端 `marginModeLabel()` 不识别 `CASH`，现货持仓展示会降级成 `--`。

## 14. 最小改造建议

如果只做最小闭环，建议按下面顺序：

```text
1. 增加 productType 或严格 assetClass:
   CRYPTO_SPOT / LINEAR_PERPETUAL / INVERSE_PERPETUAL

2. 修改 TradingInstrumentClassifier:
   CRYPTO_SPOT -> SPOT
   CRYPTO 不再默认承担永续或现货歧义

3. 修改前端 TradeMarket:
   quantityMode 由 productType 决定

4. 接入 AccountSnapshotService:
   account summary 动态加入 floatingPnl

5. Spot 增加 wallet_balances:
   buy/sell 不再依赖 marginHeld 模拟真实资产

6. Perp 增加 maintenanceMargin 和 LiquidationService:
   先实现基础 stop-out，再加 funding
```

## 15. 结论

当前代码已经具备交易系统的骨架：

```text
订单服务
风控服务
执行适配器
成交结算
持仓服务
流水服务
前端交易面板
```

其中外汇保证金最接近可用形态；虚拟币现货目前是“保证金账户模拟现货库存”；虚拟币永续合约目前是“算法已具备、业务接线未完整”。下一步最关键不是继续堆功能，而是先把产品类型和资金模型拆清楚：

```text
外汇/永续 -> margin account + positions
现货 -> wallet balances + asset ledger
```

只要这个边界清楚，后续的保证金、浮盈亏、平仓、强平、资金费率和前端展示都能按产品类型稳定扩展。
