# 交易与钱包模块算法对照分析

生成日期：2026-06-16

分析对象：

- 当前项目：`C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform`
- 虚拟币资料：`C:\Users\User\Downloads\okx_binance_spot_perp_algorithms_selfcheck.md`
- 外汇资料：`C:\Users\User\Downloads\forex_leveraged_trading_algorithm_selfcheck.md`

本文只做架构与代码方案分析，没有改动业务代码。

## 1. 总体判断

当前项目已经从早期“统一保证金账户 + 简化 position”推进到较完整的模拟交易骨架：

```text
市场品种 / Quote
  -> 前端 TradePanel
  -> TradingController
  -> OrderService
  -> RiskCheckService
  -> ExecutionAdapter
  -> OrderFillService
  -> SpotSettlementService 或 PositionEngine
  -> AccountSnapshotService / WalletService / LedgerService
  -> FundingService / ForexFinancingService / LiquidationService
```

现在已经落地的关键能力：

- `ProductType` 显式区分 `FX_MARGIN`、`CRYPTO_SPOT`、`LINEAR_PERP`、`INVERSE_PERP`。
- 现货不再只靠 `marginHeld` 模拟，已有 `core.wallet_balances` 和 `ledger.asset_ledger_entries`。
- 现货成交由 `SpotSettlementService` 更新 base/quote 资产余额。
- 外汇、线性永续、反向永续由 `PositionEngine` 做同向加仓均价、反向减仓和反手。
- `AccountSnapshotService` 会按 open position 重算 `equity/freeMargin/marginLevel/maintenanceMargin`。
- 永续已有 `FundingService`、`funding_rates`、`funding_settlements`。
- 外汇已有 `ForexConversionService`、`ForexFinancingService`、`fx_conversion_rates`、`fx_financing_rates`。
- 强平已有 `LiquidationService` 和可选 scheduler。

但按两份算法资料的标准，它仍不是完整的交易所级/经纪商级算法系统。主要缺口集中在：

- 下单前缺少统一 `InstrumentRulesEngine`：`tickSize/stepSize/minNotional/maxQty/tradable/leverage bracket` 没有进入后端风控主链路。
- 成交级模型过薄：`ExecutionResult` 只有单笔均价、数量、fee、feeAsset，没有 `fillId/tradeId/makerTaker/actualFeeRate/bidAtFill/askAtFill`。
- 费用体系简化：demo fee 固定 `0.0010`，缺少 maker/taker、VIP、BNB/OKB 抵扣、OKX 正负费率、促销费率。
- 现货钱包已存在，但缺少成本账本：没有 `avg_cost/cost_basis/realized_pnl` 的资产级口径。
- 永续有维持保证金和资金费，但缺少真实 futures/perp 规则源、mark price provider、risk tier、reduce-only、positionSide、open loss。
- 外汇有换汇和 rollover，但缺少 broker profile：`pipLocation/unitStep/tradeUnitsPrecision/marginRate/commission/spread attribution/closeout percent` 尚未系统化。
- scheduler 默认关闭，真实运行时如果不打开环境变量，资金费、外汇融资、强平扫描不会自动发生。

最小正确方向不是重写，而是把“产品规则、成交明细、资产/保证金会计、风险快照”补成明确边界。

## 2. 两份算法资料的核心要求

### 2.1 OKX/Binance 现货与永续

虚拟币资料强调 9 个核心模块：

```text
InstrumentRulesEngine
FeeEngine
SpotPositionEngine
ContractSpecEngine
PerpPositionEngine
MarginEngine
FundingEngine
LiquidationRiskEngine
ReconciliationEngine
```

最重要的原则：

- 所有 tick、step、min/max、minNotional、contractSize、leverage bracket、funding、mark price、actual fee rate 都必须来自动态接口或动态配置，不应写死。
- 下单前校验顺序必须覆盖：交易状态、price tick、quantity step、min/max、notional、最大持仓/风险档位、手续费/资金费/强平费。
- 现货应更新 base/quote 钱包余额，并维护成本。
- 永续应区分线性和反向，mark price 用于 UPL、保证金、强平风险。
- 成交 fill 是唯一可信口径，一个订单可能多次成交、maker/taker 混合、fee asset 不同。

### 2.2 外汇杠杆交易

外汇资料强调外汇不是统一撮合交易所，系统应是：

```text
固定公式 + 动态产品参数 + 动态账户参数 + 动态费率参数 + 动态风控档位
```

核心要求：

- 外汇最终应统一到 `units = lots * lotSize` 计算，不应假设所有产品都是 100000。
- 必须使用 bid/ask：多头开仓 ask，平仓 bid；空头开仓 bid，平仓 ask。
- PnL 先在 quote currency 产生，再转换到账户货币。
- 账户快照应实时计算 `NAV/equity/marginUsed/marginAvailable/marginLevel`。
- 外汇融资不是永续 funding，而是 overnight financing / swap / rollover。
- 强平应是账户级 closeout 循环，不是单仓孤立公式。
- 订单、成交、仓位应表达 `intent/reduceOnly/fill/commission/spreadCost/realizedPL`。

## 3. 当前后端架构

### 3.1 订单应用层

`OrderService` 是订单应用服务，负责：

- 幂等查重：`clientOrderId` / `idempotencyKey`。
- 账户归属校验。
- 调用 `RiskCheckService.checkOrder()`。
- 市价单调用 `ExecutionAdapter.execute()` 后交给 `OrderFillService.fill()`。
- 非市价单进入 `PENDING`，并预占保证金或锁定 spot 钱包资产。
- pending order 修改和取消时释放或增减 hold。

当前设计优点：

- 订单编排边界清楚，controller 没有承载业务流程。
- spot pending order 已经走 wallet lock，而不是账户 `usedMargin`。
- 市价单与挂单最终复用 fill 路径，避免结算分叉。

当前缺口：

- `RiskCheckService` 只返回一个 hold 数值，没有返回完整 `RiskDecision`，所以无法表达规则命中、fee buffer、open loss、max position、risk tier 等细节。
- `OrderEntity` 没有 `reduceOnly`、`positionSide`、`timeInForce`、`postOnly`、`closeOnly`、`intent`。
- pending order hold 没有扣除预估手续费，也没有对 reduce-only close order 计算可平数量。

### 3.2 风控与产品分类

`TradingInstrumentClassifier` 当前按 `ProductType` 分类：

```text
FX_MARGIN    -> InstrumentKind.FOREX
CRYPTO_SPOT  -> InstrumentKind.SPOT
LINEAR_PERP  -> InstrumentKind.LINEAR_PERPETUAL
INVERSE_PERP -> InstrumentKind.INVERSE_PERPETUAL
```

这是正确方向，因为它解决了 `CRYPTO` 同时表示现货和永续的语义污染。

`RiskCheckService` 当前逻辑：

```text
1. quantity > 0
2. 读取 SymbolEntity
3. 读取 freshQuote
4. BUY 用 ask，SELL 用 bid
5. SPOT 走 wallet 可用余额校验
6. 非 SPOT 走 requiredMargin = notional / leverage 或反向公式
7. 用 AccountSnapshotService 的 freeMargin 判断是否足够
```

已符合的点：

- bid/ask 方向基本符合外汇资料。
- 现货 BUY 看 quote wallet，SELL 看 base wallet。
- 外汇和永续会用动态 account snapshot 的 free margin。
- spot 强制 1x leverage。

不符合的点：

- 没有校验 `symbol.tradable/enabled`。
- 没有校验 price tick、quantity step、min/max、minNotional。
- 没有接入 `risk.risk_configs` 的 `maxLeverage/maxLots/marginCallLevel/stopOutLevel` 到下单前校验。
- 对外汇没有统一 `units` 内部口径，接口仍以 `quantity/lots` 混合表达。
- 对永续没有 leverage bracket / position tier，只有 symbol 最大 leverage。

### 3.3 执行层

当前 `ExecutionAdapter` 非常薄：

```java
ExecutionResult execute(CreateOrderRequest request)
```

`SimulatedExecutionAdapter`：

- 用 `QuoteService.freshQuote()`。
- BUY 参考 ask，SELL 参考 bid。
- 固定滑点 `0.0001`。
- `quantity > 1` 时模拟 50% 部分成交。
- 固定 fee rate `0.0010`。
- 反向合约 fee asset 返回 margin/settlement asset。

这个适合 demo，但离两份资料要求的 fill-level 会计还差一层：

```text
fillId
tradeId
liquidityRole = MAKER / TAKER
actualFeeRate
feeAsset
feeAmount
bidAtFill / askAtFill / markAtFill
realizedPnlIfClosing
providerExecutionId
```

### 3.4 成交结算与持仓引擎

`OrderFillService` 根据产品类型分流：

```text
SPOT
  -> SpotSettlementService

FOREX / LINEAR_PERP / INVERSE_PERP
  -> PositionEngine.applyFill()
```

`PositionEngine` 已实现：

- 无持仓：开仓。
- 同方向：加仓并重算均价。
- 反方向且小于等于旧仓：减仓并计算 realized PnL。
- 反方向且大于旧仓：先平旧仓，再用余量反手开新仓。
- 反向永续使用倒数/调和均价。
- 永续持仓写入 `notional/initialMargin/maintenanceMargin/markPrice/settlementAsset/marginAsset`。

这已经覆盖了两份资料中“加仓、减仓、反手”的主要方向。

剩余缺口：

- 当前是一种净仓模式，没有 `hedge mode`。
- 没有 `reduceOnly`，所以无法表达“只减仓，不能反手”。
- 外汇 close commission、open commission allocated、spread cost attribution 没有进入 realized net PnL。
- 永续 close fee、funding、liquidation fee 与单次平仓 PnL 的净额归因还没有形成完整 `netPnl` 账本。
- `TradeEntity` 仍偏订单成交摘要，不是完整 `fills` 表。

### 3.5 现货钱包

数据库已有：

```text
core.wallet_balances:
  account_id
  asset
  total
  available
  locked

ledger.asset_ledger_entries:
  account_id
  asset
  amount
  balance_after
  entry_type
  reference_type
  reference_id
```

`WalletService` 支持：

- `creditAvailable`
- `debitAvailable`
- `lockAvailable`
- `releaseLocked`
- `debitLocked`
- asset ledger 写入

`SpotSettlementService`：

- BUY：扣 quote，入 base，再按 base 扣 fee。
- SELL：扣 base，入 quote，再按 quote 扣 fee。
- pending order 如果已有 locked hold，会优先从 locked 扣并释放剩余。

已符合：

- 现货不再使用保证金账户模拟。
- base/quote 钱包方向正确。
- pending buy/sell 可以锁 quote/base。

不足：

- `AssetLedgerEntryType` 枚举只有通用类型，但业务传入了 `SPOT_BUY_QUOTE_OUT` 等字符串；当前数据库能存，枚举解析方法却不能识别这些业务 code，后续如果读取时调用 `fromCode` 会有风险。
- 没有成本账本：缺少 `cost_basis`、`avg_cost`、`realized_spot_pnl`。
- 现货 fee mode 固定为 BUY 扣 base、SELL 扣 quote，未支持 BNB/OKB 抵扣、返佣、负费率。
- 没有余额对账入口：无法自动验证 wallet = 初始 + 入出金 + 买卖净变动 - 手续费 + 返佣。

### 3.6 账户快照

`AccountSnapshotService`：

- 读取 open positions。
- 对每个持仓用 quote 计算 UPL。
- 永续用 mark price 优先，其次 mid，其次 closeout price。
- 汇总 open floating PnL。
- 使用 open positions 的 `marginHeld/initialMargin` 作为 used margin。
- 计算 `equity = balance + openFloatingPnl`、`freeMargin = equity - usedMargin`、`marginLevel = equity / usedMargin * 100`。
- 如果持仓保证金与账户落库 usedMargin 不一致，会返回 warning。

这符合外汇资料中实时 NAV / marginAvailable 的方向。

不足：

- spot wallet valuation 没有折算到账户总 equity。
- pending order margin / pending commission buffer 只在落库 usedMargin 或 wallet locked 中存在，快照层没有统一扣除 open order exposure。
- 外汇 closeout 需要的 `marginCloseoutNAV/marginCloseoutMarginUsed/marginCloseoutPercent` 尚未建模。

### 3.7 Funding、Financing、Liquidation

永续资金费：

- `funding_rates` 保存 funding rate、funding time、next funding time、mark price。
- `FundingService` 使用公式 `-side * positionValue * fundingRate`。
- funding settlement 有唯一键 `(position_id, funding_time)` 防重复。
- `FundingSettlementScheduler` 可按配置开启。

外汇融资：

- `fx_conversion_rates` 支持正向和反向换汇。
- `fx_financing_rates` 保存 long/short annual rate、day count、effective date。
- `ForexFinancingService` 支持 Wednesday triple swap，`USDCAD` 周四三倍。
- settlement 表按 `(position_id, settlement_date)` 防重复。

强平：

- `LiquidationService` 支持账户扫描。
- 外汇按 `marginLevel <= stopOutLevel`。
- 永续按 `perpEquity <= maintenanceMargin + liquidationFee`。
- 候选仓位按风险贡献排序。
- 执行时复用 `PositionService.closeSystemPosition()`，并可记录 liquidation fee。

不足：

- 三个 scheduler 在 `application.yml` 中默认关闭，真实环境需要显式开启。
- funding/financing rate 当前需要已有表数据，缺少 Binance/OKX/OANDA provider 自动同步闭环。
- 永续 mark price 仍可能 fallback 到 quote mid，不等于交易所官方 mark price。
- 强平是全仓近似，不支持逐仓 margin balance、added/reduced margin、open order margin。

## 4. 当前前端架构

### 4.1 交易页

核心文件：

- `apps/web/src/features/trading/components/tradePanelMarket.ts`
- `apps/web/src/features/trading/hooks/useTradeForm.ts`
- `apps/web/src/features/trading/services/orderAdapter.ts`
- `apps/web/src/features/trading-session/useTradingSession.ts`

当前前端已经使用 `productType`：

```text
CRYPTO_SPOT  -> quote-budget
LINEAR_PERP  -> quantity
INVERSE_PERP -> contracts
FX_MARGIN    -> quantity + unitSize 100000
```

表单校验：

- price > 0
- amount > 0
- minAmount
- minNotional 默认 5
- BUY 看 quote balance
- SELL 对 margin product 看 quote balance，对 spot 看 base balance
- TP/SL/trigger 非空校验

不足：

- 没有基于后端动态 rules 的 tick/step 修正。
- `minNotional` 是前端默认值，不是每个 symbol 的交易所规则。
- leverage 控件没有根据 product type 隐藏 spot 杠杆，也没有根据 symbol/risk tier 动态上限。
- `timeInForce/postOnly/fok/ioc` UI 类型存在，但 `OrderPayload` 没传后端。

### 4.2 交易 session 与钱包页

`useTradingSession` 会并行拉取：

```text
account summary
orders
positions
positionHistory
ledgerEntries
walletBalances
```

`deriveTradingBalances()` 已优先使用 `walletBalances` 生成交易可用余额，这是正确方向。

钱包页当前问题：

- `WalletPage` 已经从 session 拿到 `walletBalances`，但 `getAssetRows()` 仍主要基于 `account` 与资金流水派生资产行，没有直接使用 wallet balances 的 `total/available/locked`。
- 资产流水页面展示的是资金 ledger，不是 `asset_ledger_entries`。
- 充值/提现仍是 fund order 维度，不等同真实链上入出金或多资产充提。

## 5. 产品维度对照矩阵

| 维度 | 外汇 FX_MARGIN | 现货 CRYPTO_SPOT | 永续 LINEAR/INVERSE_PERP | 当前项目状态 |
| --- | --- | --- | --- | --- |
| 显式产品类型 | 需要 | 需要 | 需要 | 已有 `ProductType` |
| bid/ask 成交 | 必须 | 需要盘口 | 需要盘口 | 基础已具备 |
| tick/step/min/max | 必须 | 必须 | 必须 | 字段部分存在，后端风控未接入 |
| 钱包余额 | 非核心 | 必须 | margin wallet 需要 | spot wallet 已有，perp wallet 不完整 |
| 初始保证金 | 必须 | 不适用 | 必须 | 外汇/永续已具备简化模型 |
| 维持保证金 | closeout 需要 | 不适用 | 必须 | 永续已有 rate 字段和计算，外汇 closeout 不完整 |
| 资金费/融资费 | rollover/swap | 无 | funding | 服务和表已存在，provider 同步不足 |
| 成交级 fill | 必须 | 必须 | 必须 | 当前过薄 |
| maker/taker fee | broker 视情况 | 必须 | 必须 | 未完整支持 |
| 成本均价 | 持仓均价 | spot cost basis | position entry | 外汇/永续持仓均价已有，spot 成本账本缺失 |
| 减仓/反手 | 必须 | 卖出成本 | 必须 | margin product 已有，spot 成本缺失 |
| 自动强平 | 必须 | 不适用 | 必须 | 服务已有，真实触发依赖配置和数据 |
| 对账 | 必须 | 必须 | 必须 | 未形成 ReconciliationEngine |

## 6. 关键风险点

1. **交易规则未硬校验**

   后端下单风控没有统一校验 `tickSize/stepSize/minLot/maxLot/minNotional/tradable`。这会让前端或外部 API 能提交交易所规则不接受的订单。

2. **成交级数据不足**

   `ExecutionResult` 无法表达多 fill、maker/taker 混合、实际费率、fee asset 多样性。后续接真实 Binance/OKX/FIX 时会卡在会计归因。

3. **现货成本账本缺失**

   钱包余额是资产数量真相，但没有 `cost basis` 就无法计算现货卖出 realized PnL，也无法做自检资料要求的成本均价。

4. **永续规则仍是内部简化**

   维持保证金率、清算费率、contract size 字段存在，但需要交易所规则源和风险档位。否则只能算 demo 风险，不是交易所级自检。

5. **外汇 broker 参数不完整**

   当前外汇用 `lotSize` 和换汇服务补了一部分，但缺少 `pipLocation/unitStep/tradeUnitsPrecision/marginRate/commissionModel/swapMode/closeoutProfile`。

6. **scheduler 默认关闭**

   funding、fx financing、liquidation 都是可选 scheduler。如果测试或部署没有打开环境变量，会出现“服务存在但不会自动发生”的误判。

7. **前端钱包展示滞后于后端钱包模型**

   后端已有 `wallet_balances`，前端 session 也拉取了，但钱包页资产表没有真正以它为主数据源。

## 7. 推荐代码方案

### P0：补统一交易规则引擎

新增或扩展：

```text
InstrumentRulesEngine
  - loadRules(symbol)
  - validateOrder(order, accountSnapshot, walletSnapshot)
  - normalizePrice(price)
  - normalizeQuantity(quantity)
```

最小落地点：

- 先复用 `market.symbols` 现有字段：`tickSize/minLot/maxLot/tradable/productType/leverage`。
- 增加缺失字段或 metadata 映射：`stepSize/minNotional/maxNotional/marketMinQty/marketMaxQty`.
- `RiskCheckService.checkOrder()` 返回 `RiskDecision`：

```java
record RiskDecision(
    BigDecimal holdAmount,
    String holdAsset,
    int effectiveLeverage,
    BigDecimal estimatedFee,
    String feeAsset,
    List<String> ruleCodes
) {}
```

验收：

- 非 tradable symbol 拒单。
- price 不满足 tick 拒单。
- quantity 不满足 step/min/max 拒单。
- spot BUY/SELL 仍正确使用 wallet。
- margin/perp 仍正确使用 account snapshot freeMargin。

### P1：升级成交模型

新增：

```text
trading.fills
  id
  order_id
  provider_trade_id
  symbol
  side
  quantity
  price
  liquidity_role
  fee_rate
  fee_amount
  fee_asset
  bid_at_fill
  ask_at_fill
  mark_at_fill
  realized_pnl
  executed_at
```

调整：

- `ExecutionAdapter.execute()` 返回 `ExecutionReport`，内部包含 `List<FillResult>`。
- `OrderFillService` 遍历 fills，而不是只按一个均价结算。
- `TradeEntity` 可保留为聚合视图，但真实会计以 `FillEntity` 为准。

验收：

- 一个订单两笔 partial fill，手续费和持仓均价按两笔计算。
- maker/taker 不同费率能得到不同 fee。
- spot 买入 fee asset 为 base，卖出 fee asset 为 quote。

### P2：补现货成本账本

新增：

```text
spot_asset_positions 或 wallet_cost_lots
  account_id
  asset
  quantity
  avg_cost_quote
  cost_basis_quote
  realized_pnl_quote
  quote_asset
```

服务：

```text
SpotCostBasisService
  - applyBuyFill()
  - applySellFill()
  - calculateAverageCost()
  - calculateRealizedPnl()
```

策略：

- 第一阶段使用加权平均成本。
- 后续如需要可扩展 FIFO/LIFO。

验收：

- BTCUSDT buy 后 `wallet BTC` 增加，`USDT` 减少，`avg_cost` 正确。
- sell 后 `BTC` 减少，`USDT` 增加，`realized_pnl` 正确。
- `asset_ledger_entries` 与 cost ledger 能对账。

### P3：补永续交易所规则与风险

新增：

```text
PerpInstrumentRule
  symbol
  exchange
  contractSize
  contractMultiplier
  tickSize
  stepSize
  minQty
  maxQty
  minNotional
  maxLeverage
  maintenanceMarginRate
  maintenanceAmountCum
  liquidationFeeRate
  fundingIntervalHours
```

服务：

```text
MarkPriceService
LeverageBracketService
MaintenanceMarginCalculator
PerpRiskEngine
```

订单字段：

```text
reduceOnly
positionSide
marginMode
timeInForce
postOnly
```

验收：

- `LINEAR_PERP` 使用 official mark price 计算 UPL/MM。
- `INVERSE_PERP` 使用倒数公式，fee 用币本位。
- funding settlement 按 `funding_time` 幂等。
- reduce-only 不允许增加仓位或反手。
- liquidation 使用 `equity <= sum(MM) + liquidationFee`。

### P4：补外汇 broker profile

新增或扩展：

```text
ForexInstrumentProfile
  symbol
  lotSize
  pipLocation
  pipSize
  unitStep
  tradeUnitsPrecision
  minimumTradeSize
  maximumOrderUnits
  marginRate
  commissionModel
  financingModel
  closeoutModel
```

服务：

```text
ForexRulesEngine
ForexCommissionService
ForexSpreadAttributionService
ForexCloseoutService
```

验收：

- `EURUSD` 和 `USDJPY` pip value 都正确。
- 非账户货币 quote PnL 能转换到账户币。
- Wednesday triple swap 与 `USDCAD` Thursday triple swap 保留。
- closeout percent 能在账户快照中呈现。

### P5：前端按后端规则驱动

前端调整：

- `SymbolResponse` 暴露 `productType/tickSize/stepSize/minNotional/maxNotional/ruleWarnings`。
- `TradeMarket` 增加 rules 字段。
- `useTradeForm` 用 rules 做本地预检和输入修正。
- spot 产品隐藏或禁用 leverage 控件。
- `WalletPage` 资产表主数据源改为 `walletBalances`，流水增加 asset ledger tab。
- 订单确认弹窗显示 estimated fee、hold asset、required margin、min/max rule。

验收：

- 前端无法提交明显违反 tick/step/minNotional 的订单。
- spot wallet 的 `total/available/locked` 与交易页可用余额一致。
- wallet 页能看到 `SPOT_BUY_QUOTE_OUT/SPOT_BUY_BASE_IN/SPOT_FEE_BASE` 等资产流水。

## 8. 推荐实施顺序

```text
1. InstrumentRulesEngine
   验证：后端单测覆盖 tick/step/min/max/tradable/minNotional。

2. ExecutionReport + fills 表
   验证：部分成交、多 fee asset、maker/taker 单测。

3. SpotCostBasisService
   验证：现货买入、卖出、部分卖出、成本均价、realized PnL。

4. Perp rules + mark price + reduceOnly
   验证：linear/inverse IM、MM、funding、liquidation、reduce-only。

5. Forex broker profile + closeout
   验证：EURUSD/USDJPY pip、换汇、financing、closeout percent。

6. 前端 rules-aware trade panel + wallet asset ledger
   验证：web:test、web:build、smoke:user-core-pages、smoke:trading-login-gate。
```

这个顺序的理由：

- 先补规则引擎能同时提升外汇、现货、永续。
- 再补 fills，避免现货成本、永续 funding、外汇 commission 都继续基于订单均价粗算。
- 之后按产品补齐，不互相污染。
- 前端最后消费后端规则，避免先写死一套 UI 规则又被后端推翻。

## 9. 最小验收清单

### 后端单元/集成

```text
RiskCheckService
  - rejects non-tradable symbol
  - rejects invalid tick
  - rejects invalid step
  - rejects minNotional
  - rejects max leverage

SpotSettlementService
  - spot buy quote out/base in/base fee
  - spot sell base out/quote in/quote fee
  - pending lock release
  - cost basis after partial sell

PositionEngine
  - same-side increase weighted average
  - inverse harmonic average
  - reduce-only exact close
  - reverse position

FundingService
  - positive funding long pays
  - negative funding short pays
  - duplicate settlement skipped

ForexFinancingService
  - Wednesday triple swap
  - USDCAD Thursday triple swap
  - quote-to-account conversion

LiquidationService
  - FX stop-out
  - perp maintenance margin + liquidation fee
```

### 前端

```text
TradePanel
  - CRYPTO_SPOT quote-budget and no leverage semantics
  - LINEAR_PERP quantity mode
  - INVERSE_PERP contracts mode
  - tick/step/minNotional local validation

WalletPage
  - wallet balances are the primary asset table source
  - asset ledger entries can be filtered by asset/type/reference
```

### Repo-native 验证命令

Windows 上优先使用：

```powershell
cd C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
mvn test

cd C:\Users\User\Desktop\workspace\tradingView-KlineChart
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

若涉及真实页面流，再跑：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:user-core-pages"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:trading-login-gate"
```

## 10. 结论

当前项目已经具备交易系统的主体骨架，并且比旧架构文档描述更完整：现货钱包、产品类型、动态账户快照、永续 funding、外汇 financing、强平扫描都已经出现。

下一步不应做大面积重写。最有效的代码方案是：

```text
产品规则引擎
  -> fill 级成交账本
  -> 现货成本账本
  -> 永续 mark/risk tier/reduce-only
  -> 外汇 broker profile/closeout
  -> 前端规则驱动表单和钱包展示
```

这样可以把两份算法资料的核心要求落到项目现有边界内，避免外汇、现货、永续继续共用一套过粗的 `quantity/freeMargin/position` 语义。

## 11. 代码级架构索引

本节按当前工作区实际 Java 代码整理。注意：当前仓库有大量未提交和未跟踪后端文件，本文按这些文件的当前内容分析，不把它们当作已合并基线。

### 11.1 入口与订单编排

```text
TradingController
  -> OrderService.createOrder()
  -> OrderCommandFactory.from()
  -> OrderEntityFactory.createReceived()
  -> RiskCheckService.checkOrder()
  -> ExecutionAdapter.execute()
  -> OrderFillService.fill()
```

关键类：

- `backend/src/main/java/com/fxplatform/trading/service/OrderService.java`
  - 负责幂等查重、账户归属校验、市价/挂单分流、订单 hold、取消和修改。
  - 市价单立即调用 `ExecutionAdapter`，成交后进入 `OrderFillService`。
  - 非市价单进入 `PENDING`，根据产品类型预占账户 margin 或锁定 spot wallet。
- `backend/src/main/java/com/fxplatform/trading/service/OrderCommandFactory.java`
  - 把 API 请求规范化为内部 command。
  - 当前 symbol normalization 已集中在 factory 一侧，避免 controller 承担业务判断。
- `backend/src/main/java/com/fxplatform/trading/entity/OrderEntity.java`
  - 已有 `quantity/price/filledQuantity/remainingQuantity/avgFillPrice/fee/slippage/holdAmount/holdCurrency/leverage`。
  - 尚缺 `reduceOnly/positionSide/timeInForce/postOnly/marginMode/intent`，所以还不能表达交易所级下单意图。

订单层当前边界是清楚的：它负责“这张订单能否进入系统、是否需要 hold、是否立刻执行”，但不直接做钱包结算或持仓算法。

### 11.2 风控与产品分类

```text
RiskCheckService
  -> SymbolRepository.findBySymbol()
  -> QuoteService.freshQuote()
  -> TradingInstrumentClassifier.profile()
  -> SPOT: WalletService.getBalance()
  -> FOREX/PERP: MarginCalculator.requiredMargin()
  -> AccountSnapshotService.snapshot()
```

关键类：

- `backend/src/main/java/com/fxplatform/market/model/ProductType.java`
  - 当前枚举：`FX_MARGIN`、`CRYPTO_SPOT`、`LINEAR_PERP`、`INVERSE_PERP`。
- `backend/src/main/java/com/fxplatform/risk/model/InstrumentKind.java`
  - 当前内部分类：`FOREX`、`SPOT`、`LINEAR_PERPETUAL`、`INVERSE_PERPETUAL`。
- `backend/src/main/java/com/fxplatform/risk/service/TradingInstrumentClassifier.java`
  - `FX_MARGIN` 映射为 lot 模式，默认 `100000`。
  - `CRYPTO_SPOT` 映射为 base unit，默认 `1`。
  - `LINEAR_PERP` / `INVERSE_PERP` 映射为 contract 模式，读取 `contractSize/contractMultiplier/maintenanceMarginRate/settlementAsset/marginAsset`。
- `backend/src/main/java/com/fxplatform/risk/service/RiskCheckService.java`
  - 现货 BUY 检查 quote wallet available，SELL 检查 base wallet available。
  - 外汇/永续检查 `requiredMargin <= AccountSnapshotService.snapshot().freeMargin()`。
  - spot 强制 `effectiveLeverage = 1`。

当前风控仍是“保证金/余额检查”，不是完整的“交易规则检查”。`SymbolEntity` 已有 `tickSize/minLot/maxLot/tradable/enabled/leverage` 等字段，但 `RiskCheckService` 尚未统一校验这些字段，也没有解析 Binance adapter 写入 metadata 中的 `stepSize/minNotional`。

### 11.3 执行与成交模型

```text
ExecutionAdapter
  -> SimulatedExecutionAdapter
  -> ExecutionResult
  -> OrderFillService.fill()
```

关键类：

- `backend/src/main/java/com/fxplatform/execution/ExecutionResult.java`
  - 当前字段是单笔摘要：`filledPrice/filledAt/filledQuantity/remainingQuantity/fee/feeAsset/slippage/rejectCode/rejectMessage`。
  - 可以表达部分成交数量，但不能表达多笔 fill、maker/taker 混合、provider trade id、bid/ask/mark 快照。
- `backend/src/main/java/com/fxplatform/execution/SimulatedExecutionAdapter.java`
  - 使用 `QuoteService.freshQuote()`。
  - BUY 用 ask，SELL 用 bid。
  - 模拟固定滑点和固定 fee rate。

和两份算法文档对照，执行层是当前最需要升级的公共瓶颈。因为现货成本、永续真实手续费、外汇佣金/点差归因都应该从 fill 级事实出发，而不是只从订单均价出发。

### 11.4 现货钱包与资产流水

```text
OrderFillService
  -> productType = CRYPTO_SPOT
  -> SpotSettlementService.settleBuyFill() / settleSellFill()
  -> WalletService.debit/credit/lock/release
  -> ledger.asset_ledger_entries
```

关键类与表：

- `backend/src/main/java/com/fxplatform/wallet/service/WalletService.java`
  - 支持 `creditAvailable`、`debitAvailable`、`lockAvailable`、`releaseLocked`、`debitLocked`。
  - 每次变动写 `AssetLedgerEntryEntity`。
- `backend/src/main/java/com/fxplatform/trading/service/SpotSettlementService.java`
  - BUY：扣 quote，入 base，再从 base 扣 fee。
  - SELL：扣 base，入 quote，再从 quote 扣 fee。
  - pending order 可从 locked 资产扣除，并释放剩余 locked。
- `backend/src/main/resources/db/migration/V33__wallet_balances_and_asset_ledger.sql`
  - 新增 `core.wallet_balances`。
  - 新增 `ledger.asset_ledger_entries`。

现货路径已经从“保证金账户模拟”走向真实多资产钱包，这是已完成度最高的方向之一。缺口是成本账本：目前能证明 BTC/USDT 数量变化，但不能证明 BTC 的 `avg_cost/cost_basis/realized_spot_pnl`。

### 11.5 外汇/永续持仓引擎

```text
OrderFillService
  -> productType = FX_MARGIN / LINEAR_PERP / INVERSE_PERP
  -> PositionEngine.applyFill()
  -> PositionRepository.findOpenNetPosition()
  -> increasePosition() / reducePosition() / reversePosition()
  -> LedgerService.recordMarginHold/release/tradePnl
```

关键类：

- `backend/src/main/java/com/fxplatform/trading/service/PositionEngine.java`
  - 新仓：创建 open position，计算 margin。
  - 同方向加仓：按数量加权均价；反向合约用 USD notional 调和均价。
  - 反方向减仓：按比例释放 margin，记录 realized PnL。
  - 反方向超过旧仓：先关闭旧仓，再用剩余数量反手开新仓。
- `backend/src/main/java/com/fxplatform/risk/service/PnLCalculator.java`
  - 外汇默认按 quote currency PnL，再通过 `ForexConversionService` 转账户币。
  - 永续走 `TradingAlgorithmEngine.unrealizedPnl()`。
- `backend/src/main/java/com/fxplatform/risk/service/PerpMarginCalculator.java`
  - 线性永续：`notional = qty * contractSize * multiplier * markPrice`。
  - 反向永续：`usdNotional = contracts * contractSize * multiplier`，保证金按币本位除以 mark price。

当前持仓模型是 netting 模式，不是 hedge mode。它适合先把外汇、线性永续、反向永续的核心公式跑通，但还不能表达 Binance Hedge Mode 的 `positionSide=LONG/SHORT`。

### 11.6 账户快照与动态权益

```text
AccountSnapshotService.snapshot()
  -> open positions
  -> QuoteService.freshQuote()
  -> closeout price / mark price
  -> PnLCalculator
  -> usedMargin / maintenanceMargin / freeMargin / marginLevel
```

关键类：

- `backend/src/main/java/com/fxplatform/account/service/AccountSnapshotService.java`
  - 外汇 closeout：多头用 bid，空头用 ask。
  - 永续估值优先 `quote.markPrice()`，其次 `mid()`，最后 closeout price。
  - `equity = balance + openFloatingPnl`。
  - `usedMargin` 优先由 open position 的 `marginHeld/initialMargin` 汇总得到。
  - 如果落库 `account.usedMargin` 与 open positions 汇总不一致，返回 warning。

这已经符合外汇文档的动态 `NAV/equity/marginUsed/marginAvailable/marginLevel` 方向。缺口是账户级 closeout 细分字段、pending order exposure、spot wallet 估值没有统一进账户总 equity。

### 11.7 永续 funding、外汇 financing、强平

```text
FundingService
  -> funding_rates
  -> funding_settlements
  -> account balance/equity/freeMargin
  -> position.fundingPnl
  -> LedgerService.recordFundingFee()

ForexFinancingService
  -> fx_financing_rates
  -> fx_conversion_rates
  -> fx_financing_settlements
  -> account balance/equity/freeMargin
  -> position.financingAccrued
  -> LedgerService.recordFinancing()

LiquidationService
  -> AccountSnapshotService.snapshot()
  -> classify positions
  -> recompute perp risk
  -> PositionService.closeSystemPosition()
  -> liquidation fee ledger
```

关键类与表：

- `FundingService` 已支持正 funding rate 下 long pay / short receive，并对 `(position_id, funding_time)` 做幂等。
- `ForexFinancingService` 已支持 long/short 年化费率、day count、周三三倍、`USDCAD` 周四三倍、quote-to-account conversion。
- `LiquidationService` 已支持 FX stop-out 与 perp `equity <= maintenanceMargin + liquidationFee`。
- `V35__perpetual_funding_rates.sql`、`V36__fx_conversion_and_financing.sql`、`V39__funding_and_fx_financing_settlements.sql` 已提供核心表。

运行时注意：`application.yml` 里 `trading.funding.enabled`、`trading.fx-financing.enabled`、`trading.liquidation.enabled` 默认都是 `false`。也就是说服务存在不等于自动后台结算已经开启。

## 12. 三类交易的当前实际流程

### 12.1 外汇 `FX_MARGIN`

```text
CreateOrderRequest(EURUSD, BUY, quantity=lots)
  -> OrderService
  -> RiskCheckService
     - 读取 SymbolEntity(productType=FX_MARGIN)
     - BUY 用 ask 作为风险价格
     - MarginCalculator.requiredMargin(FOREX, lots, price, leverage, lotSize)
     - AccountSnapshotService.freeMargin 校验
  -> SimulatedExecutionAdapter
     - BUY 用 ask 成交，SELL 用 bid 成交
  -> OrderFillService
  -> PositionEngine
     - 无仓开仓 / 同向加仓 / 反向减仓 / 反手
     - realized PnL 通过 PnLCalculator
     - margin hold/release 写 ledger
  -> AccountSnapshotService
     - 多头用 bid 平仓估值
     - 空头用 ask 平仓估值
     - quote PnL 转 account currency
  -> ForexFinancingService
     - 按年化 long/short rate 做 daily rollover
  -> LiquidationService
     - marginLevel <= stopOutLevel 时扫描平仓
```

完成情况：

- 已完成：bid/ask 方向、lot size 默认、动态账户快照、净仓加减反手、quote-to-account conversion、daily financing、FX stop-out。
- 部分完成：`lotSize` 字段可配置，但没有完整 `ForexInstrumentProfile`；融资有三倍日，但未处理节假日表；closeout 是 margin level 近似，不是 OANDA 风格 `marginCloseoutPercent` 完整模型。
- 未完成：`units = lots * lotSize` 没有成为所有接口/账本统一口径；无 pipLocation/tradeUnitsPrecision/unitStep 校验；无 commission/spread attribution；无 hedge mode。

### 12.2 虚拟币现货 `CRYPTO_SPOT`

```text
CreateOrderRequest(BTCUSDT, BUY, quantity=baseQty)
  -> OrderService
  -> RiskCheckService
     - 读取 SymbolEntity(productType=CRYPTO_SPOT)
     - BUY 校验 USDT wallet available
     - SELL 校验 BTC wallet available
     - effectiveLeverage = 1
  -> MARKET:
     - ExecutionAdapter 产出 ExecutionResult
     - OrderFillService -> SpotSettlementService
  -> LIMIT/STOP:
     - OrderService 标记 PENDING
     - WalletService.lockAvailable(quote/base)
     - 成交时 SpotSettlementService 从 locked 扣除并释放剩余
  -> WalletService
     - wallet_balances total/available/locked
     - asset_ledger_entries
```

完成情况：

- 已完成：base/quote 资产分离、spot 不占用 margin、BUY/SELL 钱包可用余额校验、pending wallet lock、成交资产流水。
- 部分完成：`TradingAlgorithmEngine` 有 spot buy/sell 成本公式，但主交易链路还没有持久化 spot cost basis。
- 未完成：无 `SpotPositionEngine`/成本账本；无交易所规则 tick/step/minNotional 校验；无 maker/taker/VIP/BNB/OKB/负费率；无 ReconciliationEngine。

### 12.3 虚拟币永续 `LINEAR_PERP` / `INVERSE_PERP`

```text
CreateOrderRequest(BTCUSDT or BTCUSD)
  -> OrderService
  -> RiskCheckService
     - TradingInstrumentClassifier 区分 linear / inverse
     - MarginCalculator.requiredMargin()
     - AccountSnapshotService.freeMargin 校验
  -> ExecutionAdapter
  -> OrderFillService
  -> PositionEngine
     - linear: 加权均价、quote notional、quote PnL
     - inverse: USD notional、倒数 PnL、调和均价、coin-settled margin
  -> PerpMarginCalculator
     - initialMargin
     - maintenanceMargin
     - settlementAsset / marginAsset
  -> FundingService
     - funding cashflow 入账
  -> LiquidationService
     - 维护保证金 + liquidation fee 触发
```

完成情况：

- 已完成：线性/反向产品类型、contract size/multiplier、initial margin、maintenance margin、mark price 字段、funding settlement、反向合约调和均价和币本位 PnL。
- 部分完成：mark price 可从 quote 读取，但缺官方 Binance/OKX mark price provider；`maintenanceMarginRate` 是单值，不是档位；强平是全仓近似。
- 未完成：无 leverage bracket / position tier；无 reduce-only；无 positionSide/hedge mode；无 open order loss；无逐仓；无多资产/组合保证金；无 fill 级 maker/taker fee。

## 13. 已完成与未完成对照矩阵

| 模块 | 算法文档要求 | 当前代码证据 | 状态 | 缺口 |
| --- | --- | --- | --- | --- |
| 产品分类 | 外汇、现货、线性永续、反向永续必须分开 | `ProductType` + `TradingInstrumentClassifier` | 已完成主体 | 仍有 fallback/legacy 分支，需收敛为显式规则 |
| 交易规则 | tick、step、min/max、minNotional、tradable | `SymbolEntity` 有部分字段，Binance provider metadata 有 rules | 部分完成 | `RiskCheckService` 未硬校验 |
| 现货钱包 | base/quote wallet、available/locked | `WalletService` + `SpotSettlementService` + `V33` | 已完成主体 | 缺成本账本和对账 |
| 现货成本 | avg cost、cost basis、realized PnL | `TradingAlgorithmEngine` 有公式 | 部分完成 | 主链路未持久化 |
| 成交明细 | 逐 fill、maker/taker、fee asset、actual fee | `ExecutionResult` 单摘要，`TradeEntity` 简单 | 未完成 | 需 `ExecutionReport` + `trading.fills` |
| 外汇 PnL | quote PnL 转账户币 | `PnLCalculator` + `ForexConversionService` | 已完成主体 | 转换价/markup 和缺失汇率策略仍简化 |
| 外汇融资 | rollover、swap、三倍日 | `ForexFinancingService` + settlement 表 | 已完成主体 | 节假日、broker profile、不同地区 markup 未完成 |
| 外汇 closeout | 账户级 closeout / OANDA percent | `LiquidationService` 用 `marginLevel <= stopOutLevel` | 部分完成 | 未建模 `marginCloseoutNAV` 等字段 |
| 永续保证金 | linear/inverse IM/MM | `PerpMarginCalculator` + position 字段 | 已完成主体 | 无 tier 和逐仓 |
| 永续 funding | funding rate、方向、幂等结算 | `FundingService` + settlement 表 | 已完成主体 | 无官方 funding provider 自动同步 |
| 永续强平 | mark price、MM、清算费 | `LiquidationService` 重算 perp risk | 部分完成 | mark provider、risk tier、open order loss 未完成 |
| FeeEngine | maker/taker、VIP、抵扣、返佣 | demo fixed fee | 未完成 | 需账户费率和 fill fee 模型 |
| Reconciliation | fill/bill/wallet/account 对账 | ledger 分散存在 | 未完成 | 需统一 reconciliation report |
| Scheduler | funding/financing/liquidation 自动跑 | scheduler 类已存在 | 部分完成 | 默认关闭，需部署配置与监控 |

## 14. 推荐的最小代码框架方向

不要重写交易模块。建议沿当前边界做小步扩展：

```text
OrderService
  -> RiskCheckService
      -> InstrumentRulesEngine
      -> FeeEngine.estimate()
      -> RiskDecision
  -> ExecutionAdapter
      -> ExecutionReport(List<FillResult>)
  -> OrderFillService
      -> FillLedgerService.persist()
      -> product dispatcher
          -> SpotSettlementService + SpotCostBasisService
          -> PositionEngine + PerpRiskEngine
          -> ForexPositionAccountingService
  -> AccountSnapshotService
  -> ReconciliationService
```

第一阶段只需要三个小模型：

```java
record RiskDecision(
    BigDecimal holdAmount,
    String holdAsset,
    int effectiveLeverage,
    BigDecimal estimatedFee,
    String estimatedFeeAsset,
    List<String> ruleCodes
) {}

record FillResult(
    BigDecimal quantity,
    BigDecimal price,
    String liquidityRole,
    BigDecimal feeRate,
    BigDecimal feeAmount,
    String feeAsset,
    BigDecimal bidAtFill,
    BigDecimal askAtFill,
    BigDecimal markAtFill
) {}

record InstrumentRules(
    String symbol,
    ProductType productType,
    BigDecimal tickSize,
    BigDecimal stepSize,
    BigDecimal minQty,
    BigDecimal maxQty,
    BigDecimal minNotional,
    Integer maxLeverage,
    boolean tradable
) {}
```

这三个模型能直接解决当前最大的问题：规则校验没有结构化返回、成交事实不是 fill 级、前端无法消费统一规则。其它更复杂的 broker profile、risk tier、reconciliation 都可以在这三个模型稳定后继续叠加。
