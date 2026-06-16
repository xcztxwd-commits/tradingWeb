# 交易算法手册对标分析

本文档以以下两份算法手册为标准，对当前 `fx-trading-platform` 交易相关代码做最小但完整的全面分析：

- `C:/Users/User/Downloads/forex_leveraged_trading_algorithm_selfcheck.md`
- `C:/Users/User/Downloads/okx_binance_spot_perp_algorithms_selfcheck.md`

分析范围：

- 外汇杠杆交易
- 虚拟币现货交易
- 虚拟币永续合约交易

本文只分析当前代码现状和最小补齐路径，不修改交易代码。

## 1. 总体结论

当前项目已经具备交易系统骨架：

```text
账户
  -> 下单
  -> 风控保证金校验
  -> demo 执行
  -> 成交
  -> 创建持仓
  -> 持仓浮盈亏展示
  -> 平仓
  -> 账户余额/保证金变化
  -> ledger 流水
```

但按两份算法手册的标准看，当前系统仍是一个“统一保证金账户 + 简化产品分类 + 简化成交结算”的实现。它适合 demo 和早期模拟盘，不足以完整覆盖外汇、现货、永续三类产品的真实算法自检。

最关键的结论：

| 产品 | 当前完成度 | 核心问题 |
| --- | --- | --- |
| 外汇杠杆 | 中等 | bid/ask、lot、保证金、PnL 基础公式基本有；但缺账户币转换、pip/pipLocation、融资费/swap、动态 marginRate、账户实时 NAV、真实 closeout |
| 虚拟币现货 | 偏低 | 算法样例存在；业务链路没有 wallet balances，而是用 `marginHeld + BUY position` 模拟现货库存 |
| 虚拟币永续 | 偏低到中等 | 线性/反向公式已有；但没有 exchange futures 规则、mark price、维持保证金、资金费、风险档位、自动强平、reduce-only/净仓 |

最小化整改原则：

```text
先拆清产品类型，再补资金模型，再补风险引擎。
```

最小闭环顺序：

```text
1. 明确产品类型：FX_MARGIN / CRYPTO_SPOT / LINEAR_PERP / INVERSE_PERP
2. 外汇补账户实时 NAV / marginLevel / 手续费和平仓净 PnL
3. 现货补 wallet_balances 和 asset ledger
4. 永续补 mark price / maintenance margin / funding / liquidation
5. 订单补 tick/step/min/max/minNotional 校验和 reduceOnly
```

## 2. 当前代码证据地图

### 2.1 交易主链路

| 模块 | 文件 | 当前职责 |
| --- | --- | --- |
| 账户 | `backend/src/main/java/com/fxplatform/account/entity/TradingAccountEntity.java` | `balance/equity/usedMargin/freeMargin/marginLevel/leverage` |
| 下单 DTO | `backend/src/main/java/com/fxplatform/trading/dto/request/CreateOrderRequest.java` | `accountId/symbol/side/orderType/quantity/price/leverage` |
| 订单服务 | `backend/src/main/java/com/fxplatform/trading/service/OrderService.java` | 幂等、风控、pending order 保证金冻结、market order 执行 |
| 风控 | `backend/src/main/java/com/fxplatform/risk/service/RiskCheckService.java` | 下单前计算 `requiredMargin`，比较 `freeMargin` |
| 成交结算 | `backend/src/main/java/com/fxplatform/trading/service/OrderFillService.java` | 更新订单、创建成交、创建持仓、占用保证金、扣 fee |
| 持仓 | `backend/src/main/java/com/fxplatform/trading/service/PositionService.java` | 浮盈亏展示、平仓、系统平仓 |
| 算法 | `backend/src/main/java/com/fxplatform/risk/service/TradingAlgorithmEngine.java` | 现货样例、保证金、PnL、强平价、fee、ROI |
| 产品分类 | `backend/src/main/java/com/fxplatform/risk/service/TradingInstrumentClassifier.java` | 根据 `assetClass` 归类为 `FOREX/SPOT/LINEAR_PERPETUAL/INVERSE_PERPETUAL` |
| demo 执行 | `backend/src/main/java/com/fxplatform/execution/SimulatedExecutionAdapter.java` | bid/ask 成交、滑点、手续费、部分成交 |
| 挂单扫描 | `backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java` | `LIMIT/STOP` 触发后复用 fill |
| 止盈止损扫描 | `backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java` | SL/TP 触发后复用 `PositionService.closeSystemPosition()` |

### 2.2 前端交易链路

| 模块 | 文件 | 当前职责 |
| --- | --- | --- |
| TradeMarket | `apps/web/src/features/trading/types/order.ts` | `unitSize/quantityMode/leverage` |
| 市场转交易面板 | `apps/web/src/features/trading/components/tradePanelMarket.ts` | 外汇 `unitSize=100000`；crypto 低杠杆走 quote-budget |
| 表单校验 | `apps/web/src/features/trading/hooks/useTradeForm.ts` | notional、requiredMargin、quote/base balance 校验 |
| payload | `apps/web/src/features/trading/services/orderAdapter.ts` | 表单转后端 `OrderPayload` |
| session | `apps/web/src/features/trading-session/useTradingSession.ts` | 账户、订单、持仓、ledger 刷新 |
| 前端余额派生 | `apps/web/src/features/trading-session/tradingSessionModels.ts` | 用 `account.freeMargin` 和 BUY positions 派生 balances |
| 持仓展示 | `apps/web/src/pages/trading/components/positionDisplayModel.ts` | mark price、liquidation price、margin mode、PnL 格式化 |

### 2.3 已有测试覆盖

当前已有测试证明部分公式和手册样例一致：

| 测试 | 已覆盖 |
| --- | --- |
| `TradingAlgorithmEngineTest` | 现货买卖样例、外汇多空 PnL、线性永续、反向永续、简化强平价 |
| `RiskCheckServiceTest` | 外汇 lot 保证金、spot full notional、inverse perpetual margin、杠杆覆盖与上限 |
| `OrderFillServiceTest` | `CRYPTO` spot fill 使用 full notional margin |
| `PositionServiceTest` | SWAP 展示 `CROSS/leverage/liquidationPrice`，SPOT 展示 `CASH/null leverage/null liquidationPrice` |

这些测试说明“公式引擎已有雏形”，但不能证明“业务结算已完整符合手册”。

## 3. 手册标准提炼

### 3.1 外汇手册最低标准

外汇手册要求系统至少支持：

```text
1. bid/ask 成交：
   多头开仓 ask，平仓 bid
   空头开仓 bid，平仓 ask

2. units 与 lot：
   units = lots * lotSize
   lotSize 不能永久写死，必须来自产品参数

3. pip/pipLocation：
   pipSize = 10 ^ pipLocation
   JPY 对不能按 0.0001 写死

4. PnL：
   多头 = units * (closeBid - entryAsk)
   空头 = abs(units) * (entryBid - closeAsk)
   先以 quote currency 产生，再转换为 account currency

5. 保证金：
   notional_ACC = units * price * quoteToAccountRate
   initialMargin = notional_ACC * marginRate
   marginRate = 1 / leverage

6. 账户权益：
   NAV = balance + unrealizedPL
   marginAvailable = NAV - marginUsed
   marginLevel = NAV / marginUsed * 100%

7. closeout：
   不能只有单仓简化强平价
   需要账户级 margin call / stop-out

8. 成本：
   spread cost
   commission
   financing / swap / rollover
   conversion fee
   rebate

9. 持仓：
   支持同向加仓均价、反向减仓、反手、netting 或 hedging
```

### 3.2 现货手册最低标准

OKX/Binance 现货手册要求：

```text
1. 产品规则动态获取：
   tickSize/tickSz
   stepSize/lotSz
   minQty/minSz
   maxQty/maxLmtSz/maxMktSz
   minNotional/maxNotional
   symbol status

2. 下单校验：
   price % tick == 0
   quantity % step == 0
   quantity >= minQty
   notional >= minNotional
   balance 足够

3. 钱包模型：
   base asset available/locked/total
   quote asset available/locked/total

4. 买入：
   gross_base = quoteBudget / price
   fee_base = gross_base * feeRate
   net_base = gross_base - fee_base
   quote 余额减少，base 余额增加

5. 卖出：
   gross_quote = baseQty * price
   fee_quote = gross_quote * feeRate
   net_quote = gross_quote - fee_quote
   base 余额减少，quote 余额增加

6. 成本：
   avg_cost
   cost_basis
   realized_pnl
   estimated_exit_fee

7. 费用：
   maker/taker
   账户实际费率
   BNB/OKB 抵扣
   OKX API 费率正负号
   返佣/负费率
```

### 3.3 永续合约手册最低标准

OKX/Binance 永续手册要求：

```text
1. 产品规则动态获取：
   futures exchangeInfo / instruments
   contractSize / ctVal / ctMult
   tick/step/min/max
   leverage bracket / position tier
   liquidationFee

2. 价格：
   下单用 bid/ask/last
   风控和 UPL 通常用 mark price
   index price 与 mark price 不能混同

3. 线性合约：
   notional = abs(baseQty) * price
   IM = notional / leverage
   UPL long = qty * (mark - entry)
   UPL short = qty * (entry - mark)

4. 反向合约：
   usdNotional = contracts * contractSize
   IM_coin = usdNotional / (price * leverage)
   PnL 用倒数公式

5. 维持保证金：
   Binance: MM = notional * maintMarginRatio - cum
   OKX: MM = size * ctVal * ctMult * mark * mmr

6. 资金费：
   funding_rate > 0: long pays, short receives
   funding_cashflow = -side * positionValue * fundingRate

7. 持仓：
   one-way / hedge mode
   reduce-only
   同向加仓均价
   反向减仓
   反手

8. 强平：
   isolated/cross 不同
   account/position margin balance + UPL <= MM + liquidation fee

9. 成交：
   必须逐 fill 计算
   一个订单可多笔成交、maker/taker 混合、不同 fee asset
```

## 4. 外汇杠杆交易对标

### 4.1 当前已符合的部分

| 手册标准 | 当前代码 | 结论 |
| --- | --- | --- |
| 多头开仓用 ask、空头开仓用 bid | `RiskCheckService.checkOrder()`、`SimulatedExecutionAdapter.execute()` | 符合基础要求 |
| 多头平仓用 bid、空头平仓用 ask | `PositionService.closeOwnedPosition()` | 符合基础要求 |
| lotSize 支持产品参数 | `TradingInstrumentClassifier.unitSize(symbol, 100000)` | 部分符合 |
| 外汇保证金 = notional / leverage | `TradingAlgorithmEngine.requiredMargin()` | 符合简单模型 |
| 多空浮盈亏公式 | `TradingAlgorithmEngine.unrealizedPnl()` / `PnLCalculator` | 符合 quote=account 的简单模型 |
| pending order 预占保证金 | `OrderService.reserveOrderHold()` | 符合基础要求 |
| SL/TP bid/ask 触发 | `ProtectiveOrderExecutionService` | 符合基础要求 |

### 4.2 当前不符合或缺失的部分

| 手册标准 | 当前项目现状 | 影响 |
| --- | --- | --- |
| `units = lots * lotSize` 全链路统一 | 业务层 `quantity/lots` 混用；算法引擎既可用 units，也可通过 `unitSize` 放大 | 容易在前后端、测试和业务结算中产生口径混乱 |
| `pipLocation/pipSize/displayPrecision/tradeUnitsPrecision` | `SymbolEntity` 只有 `pipSize/tickSize/lotSize/minLot/maxLot`，没有 `pipLocation/tradeUnitsPrecision/unitStep` | JPY 对、最小单位、pip value 自检不完整 |
| PnL quote currency 转 account currency | 当前默认 quote 与 account currency 近似同币；没有 conversion engine | 例如 `GBPJPY` 的 JPY PnL 无法正确转 USD |
| 动态 `marginRate` | 当前用 `leverage` 推导；没有 `marginRate` 字段、分层 margin | 无法对标 OANDA/IG/IBKR 的保证金率和 tier |
| 账户实时 NAV | `PositionService` 响应计算 floatingPnl，但账户 `equity` 不实时包含 UPL | `freeMargin/marginLevel` 不是真实风险口径 |
| `marginLevel` | 字段存在，但交易链路未持续更新 | 无法做 margin call / stop-out |
| OANDA closeout percent | 没有 `marginCloseoutNAV/marginCloseoutPercent` | 无法按手册 closeout 自检 |
| 开/平仓佣金 | `SimulatedExecutionAdapter` 只在成交时生成一次 fee；平仓不扣 close fee | net PnL 不完整 |
| spread cost 归因 | bid/ask 体现点差，但没有单独归因字段 | 无法做成本分析/对账 |
| financing/swap/rollover | 无 | 外汇隔夜持仓成本缺失 |
| 同向加仓/反向减仓/反手 | `OrderFillService` 每次 fill 都创建新 position | 不符合 netting/hedging 持仓模型 |
| 风控配置接入 | `risk.risk_configs` 存在，但 `RiskCheckService` 未使用 | `maxLots/maxLeverage/stopOutLevel` 不生效 |

### 4.3 外汇算法当前评分

```text
价格方向 bid/ask:       通过
基础保证金公式:         通过
基础 PnL 公式:          通过
lotSize 参数化:         部分通过
pip / 精度:             不通过
账户币转换:             不通过
实时 NAV / marginLevel: 不通过
融资费 / swap:          不通过
净仓/减仓/反手:         不通过
强平/closeout:          不通过
```

### 4.4 外汇最小补齐方案

最小不是重写系统，而是补齐当前模型缺口：

```text
P0:
  1. 新增 AccountSnapshotService
     - totalUPL
     - NAV/equity
     - marginUsed
     - marginAvailable
     - marginLevel

  2. RiskCheckService 接入 RiskConfigEntity
     - maxLots
     - maxLeverage
     - marginCallLevel
     - stopOutLevel

  3. 外汇数量口径统一
     - API 内部统一为 units
     - UI 可继续显示 lots
     - Order/Position 存 lots 时必须同时能推导 units

P1:
  4. 增加 ForexInstrument 参数
     - pipLocation
     - tradeUnitsPrecision
     - unitStep
     - marginRate
     - pricingModel

  5. 增加 CurrencyConversionService
     - quote -> account
     - gain/loss conversion factor

P2:
  6. 增加 FinancingService
     - long/short annual rate
     - daysCharged
     - Wednesday triple swap

  7. 增加 PositionNettingService
     - 同向加仓均价
     - 反向减仓
     - 反手
```

## 5. 虚拟币现货对标

### 5.1 当前已符合的部分

| 手册标准 | 当前代码 | 结论 |
| --- | --- | --- |
| 现货无杠杆 | `RiskCheckService.effectiveLeverage()` 对 `SPOT` 强制 1 | 符合 |
| 现货 full notional 占用 | `TradingAlgorithmEngine.requiredMargin(SPOT)` | 作为模拟资金占用符合 |
| 现货买卖公式样例 | `TradingAlgorithmEngine.spotBuyWithQuoteBudget()`、`spotSell()` | 算法样例符合手册 |
| Binance spot exchangeInfo 获取 | `BinanceSpotMarketDataProvider.fetchTradingRules()` | 有接口读取能力 |
| Binance spot tick/step/minNotional 元数据 | `toTradingRuleMetadata()` 写入 raw metadata | 部分符合 |
| 前端 quote-budget market buy | `tradePanelMarket.ts` / `useTradeForm.ts` | 部分符合 |

### 5.2 当前不符合或缺失的部分

| 手册标准 | 当前项目现状 | 影响 |
| --- | --- | --- |
| 现货钱包余额 | 没有 `wallet_balances`；只有 `TradingAccountEntity.freeMargin` | 无法表达 BTC/ETH/USDT 多资产余额 |
| base/quote locked | 没有 asset-level locked | 无法正确处理挂买/挂卖占用 |
| 买入后 base 增加、quote 减少 | 当前是 `usedMargin += fullNotional` 并创建 BUY position | 不是现货账本 |
| 卖出消耗 base，增加 quote | 当前 SELL 也可能创建 position 并占用 margin | 不符合现货卖出 |
| 手续费资产 | demo fee 是 quote 口径；业务未区分 base/quote/BNB/OKB | 不能对标 Binance/OKX 手续费 |
| maker/taker 实际费率 | 无账户实际 fee API；无 maker/taker fill 归因 | 费用自检不完整 |
| tick/step/minNotional 校验 | provider metadata 有，但 `RiskCheckService` 不校验 price tick、quantity step、minNotional | 下单规则不符合交易所 |
| min/max/market lot | `SymbolEntity.minLot/maxLot` 有字段，但风险校验未使用 | 无法拒绝非法数量 |
| cost basis / avg cost | 无 | realized PnL 不可靠 |
| partial fill 成本 | 没有 fill-level spot inventory accounting | 多 fill 对账不完整 |
| 费率正负号/返佣 | 无 | OKX maker rebate 无法支持 |

### 5.3 现货分类风险

当前 `TradingInstrumentClassifier` 规则：

```text
assetClass == SPOT or CRYPTO -> InstrumentKind.SPOT
```

但 `BinanceSpotMarketDataProvider` 和 `V27__crypto_symbol_provider_seed.sql` 给 crypto symbol 设置：

```text
asset_class = CRYPTO
leverage = 20
```

结果：

```text
后端:
  CRYPTO -> SPOT -> leverage 强制 1

前端:
  crypto + leverage 20 -> quantityMode = quantity
  看起来像杠杆/合约产品
```

这是当前现货和永续最核心的产品语义冲突。

### 5.4 现货算法当前评分

```text
spot 公式样例:             通过
现货强制 1x:              通过
交易所规则获取:            部分通过
下单 tick/step/min 校验:   不通过
钱包余额:                  不通过
base/quote locked:         不通过
手续费资产:                不通过
成本均价/realized PnL:     不通过
现货卖出语义:              不通过
```

### 5.5 现货最小补齐方案

最低限度需要新增钱包，不建议继续用 `marginHeld` 模拟现货。

```text
P0:
  1. 拆产品类型:
     - CRYPTO_SPOT
     - LINEAR_PERP
     - INVERSE_PERP

  2. 新增 wallet_balances:
     - accountId
     - asset
     - total
     - available
     - locked

  3. 新增 SpotSettlementService:
     - buy: quote.available/locked -> base.available
     - sell: base.available/locked -> quote.available

  4. RiskCheckService 增加 spot order rule:
     - BUY 校验 quote available
     - SELL 校验 base available

P1:
  5. 将 Binance exchangeInfo metadata 落到可查询规则:
     - tickSize
     - stepSize
     - minQty
     - maxQty
     - minNotional
     - maxNotional

  6. Order validation:
     - price % tickSize == 0
     - qty % stepSize == 0
     - notional >= minNotional

P2:
  7. 成本模型:
     - avg_cost
     - cost_basis
     - realized_pnl

  8. FeeEngine:
     - maker/taker
     - fee asset
     - BNB/OKB discount
     - rebate
```

## 6. 虚拟币永续合约对标

### 6.1 当前已符合的部分

| 手册标准 | 当前代码 | 结论 |
| --- | --- | --- |
| 线性永续初始保证金 | `TradingAlgorithmEngine.requiredMargin(LINEAR_PERPETUAL)` | 简单模型符合 |
| 线性永续 PnL | `TradingAlgorithmEngine.unrealizedPnl()` | 简单模型符合 |
| 线性手续费公式 | `TradingAlgorithmEngine.linearFee()` | 算法存在 |
| 反向永续保证金 | `requiredMargin(INVERSE_PERPETUAL)` | 简单模型符合 |
| 反向永续倒数 PnL | `inversePnl()` | 符合手册基本公式 |
| 反向手续费 | `inverseFee()` | 算法存在 |
| netPnl/ROI | `netPnl()`、`roi()` | 算法存在 |
| 持仓响应展示 markPrice/liquidationPrice | `PositionResponse` / `PositionService` | 展示字段存在 |

### 6.2 当前不符合或缺失的部分

| 手册标准 | 当前项目现状 | 影响 |
| --- | --- | --- |
| futures/perp 产品源 | 当前有 Binance spot provider；没有 futures/perp exchangeInfo provider | 无法动态获取合约规则 |
| productType 明确区分 | 依赖 `assetClass` 字符串；`CRYPTO` 被当 SPOT | 永续 symbol 容易被误判 |
| mark price | `PositionService` 用 `quote.mid()` 作为 markPrice | 不等于 Binance/OKX 官方 mark price |
| leverage bracket / position tier | 无 | 无法计算 max leverage、MMR、cum |
| 维持保证金 MM | 无业务计算；`PositionResponse.maintenanceMarginRate` 始终无真实来源 | 无法做强平风险 |
| open loss | 无 | Binance futures 下单成本不完整 |
| funding rate | 前端 market dashboard 有 funding 数据展示能力，但交易结算无 FundingService | 永续 PnL 不完整 |
| funding cashflow | `TradingAlgorithmEngine.netPnl()` 可传 funding，但业务链路不用 | 无资金费流水 |
| isolated/cross | 后端统一返回 `CROSS`，没有真实 isolated | 保证金模式不完整 |
| reduce-only | 订单 DTO 无 `reduceOnly` | 减仓/平仓控制不完整 |
| one-way/hedge mode | 无 | 无法对标交易所持仓模式 |
| 同向加仓/反向减仓/反手 | 每次成交都创建新 position | 不符合合约仓位算法 |
| liquidation fee / buffer | 无 | 强平价和强平结算不真实 |
| 自动 liquidation | 无 `LiquidationService` | 只能展示简化强平价或后台 force close |
| fill-level maker/taker fee | 无 | 多 fill 对账不完整 |

### 6.3 当前强平价与手册差异

当前线性强平价：

```text
priceMoveToZero = marginHeld / (abs(quantity) * unitSize)

BUY:
  liquidationPrice = entryPrice - priceMoveToZero

SELL:
  liquidationPrice = entryPrice + priceMoveToZero
```

这只是“保证金亏完”的近似。

手册要求强平至少考虑：

```text
maintenanceMargin
liquidationFee
markPrice
cross/isolated margin balance
open order margin
funding
fees
risk tier
```

所以当前 `liquidationPrice` 只能作为 demo 展示，不应作为真实风控触发依据。

### 6.4 永续算法当前评分

```text
线性 IM:                 通过简单模型
线性 PnL:                通过简单模型
反向 IM:                 通过简单模型
反向 PnL:                通过简单模型
mark price:              不通过
leverage bracket:        不通过
maintenance margin:      不通过
funding:                 不通过
reduce-only/netting:     不通过
自动 liquidation:        不通过
交易所合约规则动态获取:  不通过
```

### 6.5 永续最小补齐方案

```text
P0:
  1. 明确合约产品:
     - LINEAR_PERP
     - INVERSE_PERP

  2. 新增 PerpInstrumentRules:
     - contractSize / ctVal / ctMult
     - tickSize
     - stepSize
     - minQty
     - minNotional
     - maxQty
     - maxLeverage
     - marginAsset
     - settlementAsset

  3. 新增 MarkPriceService:
     - Binance fapi/dapi premiumIndex
     - OKX public mark-price
     - fallback 才允许用 mid

P1:
  4. 新增 MaintenanceMarginCalculator:
     - Binance: notional * maintMarginRatio - cum
     - OKX: size * ctVal * ctMult * mark * mmr

  5. 新增 LiquidationRiskEngine:
     - cross
     - isolated
     - liquidation buffer

P2:
  6. 新增 FundingService:
     - fundingRate
     - fundingTime
     - funding cashflow
     - ledger entry

  7. 新增 PositionNettingService:
     - 加仓均价
     - 减仓 realized PnL
     - 反手
     - reduceOnly
```

## 7. 横向对标矩阵

| 标准项 | 外汇 | 现货 | 永续 | 当前项目状态 |
| --- | --- | --- | --- | --- |
| BigDecimal/Decimal | 需要 | 需要 | 需要 | Java 使用 `BigDecimal`，通过 |
| bid/ask 成交 | 必须 | 买卖盘需要 | 需要 | 基础通过 |
| mark price | 可选/风控可用 mid | 不需要 | 必须 | 永续不通过 |
| tick/step 校验 | 需要 unitStep | 必须 | 必须 | 字段/metadata 部分存在，风控未接 |
| min/max 数量 | 必须 | 必须 | 必须 | 字段存在，风控未接 |
| minNotional | 可选 | 必须 | 必须 | provider metadata 有，风控未接 |
| 账户币转换 | 必须 | 资产估值需要 | 多资产需要 | 不通过 |
| 钱包余额 | 不一定 | 必须 | margin wallet 需要 | 现货不通过 |
| 初始保证金 | 必须 | 无 | 必须 | 外汇/永续简单模型通过 |
| 维持保证金 | closeout 需要 | 无 | 必须 | 不通过 |
| 资金费/融资费 | swap/financing | 无 | funding | 不通过 |
| maker/taker fee | 可选 | 必须 | 必须 | 不通过 |
| fee asset | 可选 | 必须 | 必须 | 不通过 |
| 加仓均价 | 必须 | 成本均价 | 必须 | 不通过 |
| 减仓/反手 | 必须 | 卖出成本 | 必须 | 不通过 |
| 自动强平 | 必须 | 无 | 必须 | 不通过 |
| ledger 完整性 | 必须 | 必须 | 必须 | 部分通过 |

## 8. 最小完整整改路线

### 8.1 不建议立即做的大重构

当前不建议一上来重写全部交易系统。原因：

```text
1. 当前代码已有可用的订单/持仓/ledger 骨架。
2. 已有测试覆盖部分公式。
3. 最大问题是产品边界和资金模型，不是所有文件都坏了。
4. 最小化应该先补错位的领域模型，再把算法接进去。
```

### 8.2 第一阶段：产品类型最小拆分

目标：修掉 `CRYPTO` 同时像 spot 和 perp 的语义冲突。

最小改动：

```text
SymbolEntity.assetClass 或新增 productType:
  FX_MARGIN
  CRYPTO_SPOT
  LINEAR_PERP
  INVERSE_PERP
```

分类器：

```text
FX_MARGIN -> FOREX
CRYPTO_SPOT -> SPOT
LINEAR_PERP -> LINEAR_PERPETUAL
INVERSE_PERP -> INVERSE_PERPETUAL
```

前端：

```text
quantityMode 不再根据 category + leverage 推断
改为根据 productType:
  CRYPTO_SPOT -> quote-budget
  FX_MARGIN/LINEAR_PERP/INVERSE_PERP -> quantity
```

### 8.3 第二阶段：订单规则校验最小接入

把已存在的 symbol 字段和 Binance metadata 接入 `RiskCheckService`：

```text
price tick 校验
quantity step 校验
minQty/minLot 校验
maxQty/maxLot 校验
minNotional 校验
symbol tradable/enabled 校验
```

这一步对三类产品都有效。

### 8.4 第三阶段：账户快照

新增 `AccountSnapshotService`：

```text
for margin products:
  totalUPL = sum(open position UPL by closeout/mark price)
  equity/NAV = balance + totalUPL
  usedMargin = sum(position.marginHeld)
  freeMargin = equity - usedMargin
  marginLevel = equity / usedMargin * 100

for spot:
  wallet assets are truth
  account equity is valuation result
```

这一步让外汇和永续的风险指标先真实起来。

### 8.5 第四阶段：现货钱包

新增：

```text
wallet_balances
asset_ledger_entries
SpotSettlementService
```

替换现货当前逻辑：

```text
不要用 usedMargin/marginHeld 表达现货买入成本
不要用 SELL position 表达现货卖出
```

### 8.6 第五阶段：永续风险

新增：

```text
MarkPriceService
MaintenanceMarginCalculator
FundingService
LiquidationRiskEngine
```

先支持单资产 cross：

```text
crossMarginBalance = walletBalance + sum(UPL) + realizedPnl + funding - fees - openOrderMargin
liquidationCheck = crossMarginBalance <= sum(MM) + liquidationFees
```

再支持 isolated。

### 8.7 第六阶段：持仓净额和 reduce-only

新增：

```text
PositionNettingService
Order.reduceOnly
Order.positionSide
Position.averageEntryPrice
Position.quantity signed or side-separated
```

处理：

```text
同向加仓
反向减仓
刚好平仓
反手
hedge mode
one-way mode
```

## 9. 当前代码最小保留策略

可以保留：

```text
OrderService
OrderFillService 作为编排入口
PositionService.closeSystemPosition 作为统一系统平仓入口
LedgerService
TradingAlgorithmEngine 的基础公式
TradingInstrumentClassifier 但要改产品类型输入
```

需要最小拆出的职责：

```text
OrderFillService:
  目前混合了成交、持仓、保证金、fee、ledger。
  建议只保留编排，结算交给产品 SettlementService。

RiskCheckService:
  目前只算 requiredMargin。
  建议接 ProductRules + AccountSnapshot + WalletBalance。

PositionService:
  当前可以继续负责查询/平仓入口。
  但加仓/减仓/反手应该交给 PositionNettingService。
```

## 10. 结论

按两份手册标准，当前项目不是“完全没算法”，而是：

```text
算法公式有雏形；
业务结算未完全接入；
产品边界不清；
风险账户不是实时快照；
现货缺钱包；
永续缺交易所风险规则。
```

最小完整方向不是大面积重构，而是按产品边界补齐：

```text
外汇:
  bid/ask 和基础公式保留
  补 NAV/marginLevel、currency conversion、financing、netting、closeout

现货:
  不再用 margin 模拟钱包
  补 wallet + asset ledger + spot settlement + fee asset

永续:
  保留线性/反向公式
  补 mark price、maintenance margin、funding、liquidation、reduce-only/netting
```

只要先完成产品类型拆分和账户/钱包资金模型拆分，后续算法才能稳定落到业务层。否则继续在统一 `freeMargin/usedMargin/position.marginHeld` 上叠功能，会让外汇、现货、永续三套规则互相污染。
