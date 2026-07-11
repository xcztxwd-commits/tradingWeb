# USDT 现货与 USDT 永续 Demo：最终设计与实施基准

> 最后更新：2026-07-11
>
> 状态：需求已确认，等待实施计划
>
> 适用仓库：fx-trading-platform
>
> 权威性：本文件是本轮实现、测试和验收的唯一产品/技术基准
>
> 原则：最大复用现有模块化单体，以最小必要改动形成可持久化、可审计、可离线演示的完整交易闭环

## 1. 目标

把现有项目完善为接近 Binance、OKX 常见页面结构和交易语义的模拟交易平台：

- 支持五个主流 USDT 现货交易对。
- 支持五个主流 USDT 本位永续交易对。
- 支持桌面 Web、移动 Web 和 Admin 运营闭环。
- 外部公共行情优先，外部不可用时自动切换后端本地模拟。
- 所有订单、成交、余额、持仓、资金费、保护单、强平和划转持久化。
- 所有资金变化能追溯到 Order、Trade、OrderEvent、Ledger 或 FundingSettlement。
- 不连接任何真实交易私有 API，不处理真实用户资金。
- 不建设真实撮合引擎；成交模型始终是单次全量成交。

## 2. 非目标

本轮明确不实现：

- 真实订单簿撮合、真实流动性和主动部分成交。
- IOC、FOK、GTD、Post-only、Trailing Stop。
- 真实充值提现、链上钱包、托管、节点。
- 保险基金、ADL、真实风险准备金。
- 分层风险限额、多币种组合保证金、Portfolio Margin。
- 币本位合约、交割合约、期权、C2C、VIP、理财。
- 微服务、消息队列、事件溯源、分布式事务和金融级清算。
- BNB/OKB 抵扣手续费、VIP 费率和返佣体系。
- 邮件、短信通知。

历史代码中的 FX_MARGIN、INVERSE_PERP 等实现可以保留，但用户端入口必须隐藏，后端交易白名单只允许本文件列出的产品。

## 3. 已确认的产品范围

### 3.1 现货

| 内部 symbol | Binance symbol | OKX instrument |
|---|---|---|
| BTCUSDT | BTCUSDT | BTC-USDT |
| ETHUSDT | ETHUSDT | ETH-USDT |
| BNBUSDT | BNBUSDT | BNB-USDT |
| SOLUSDT | SOLUSDT | SOL-USDT |
| XRPUSDT | XRPUSDT | XRP-USDT |

现货订单：

- MARKET。
- LIMIT，固定 GTC。
- STOP_MARKET。
- OCO：一个限价止盈腿 + 一个 STOP_MARKET 止损腿。

现货成交：

- 不允许卖空。
- MARKET、触发后的 STOP_MARKET 一次全量成交。
- LIMIT 市场可成交时立即全量成交，否则保持 PENDING。
- 不主动产生 PARTIALLY_FILLED，仅保留历史兼容。

### 3.2 USDT 本位永续

| 内部 symbol | Binance USD-M symbol | OKX SWAP instrument |
|---|---|---|
| BTCUSDT-PERP | BTCUSDT | BTC-USDT-SWAP |
| ETHUSDT-PERP | ETHUSDT | ETH-USDT-SWAP |
| BNBUSDT-PERP | BNBUSDT | BNB-USDT-SWAP |
| SOLUSDT-PERP | SOLUSDT | SOL-USDT-SWAP |
| XRPUSDT-PERP | XRPUSDT | XRP-USDT-SWAP |

永续订单：

- MARKET。
- LIMIT，固定 GTC。
- STOP_MARKET。
- reduce-only 市价/限价平仓。
- 手动部分平仓和整仓平仓。
- 最多 10 个持仓保护条件单；每档 TP/SL 可选 MARKET 或 LIMIT 执行。
- 一键撤销全部活动委托。
- 一键平掉全部永续仓位，逐仓返回成功/失败，不做整批回滚。

### 3.3 Demo 初始资金

- 每个普通用户只能有一个 active DEMO 账户。
- 注册时自动创建；重复创建返回已有账户。
- Spot wallet：50,000 USDT。
- USDT Perp account：50,000 USDT。
- 总演示资金：100,000 USDT。
- 永续默认杠杆：10×。
- 五个永续交易对最大杠杆：100×。
- 维持保证金率初始值：0.5%。
- 强平费率初始值：0.5%。
- Admin 可修改交易对风控参数。

## 4. 当前代码复用边界

### 4.1 直接复用

| 能力 | 当前类/组件 |
|---|---|
| 用户认证 | AuthController、AuthService、JWT/session/revocation |
| 订单入口 | TradingController、OrderService |
| 风控/规则 | RiskCheckService、InstrumentRulesEngine |
| Demo 市价成交 | SimulatedExecutionAdapter |
| 成交落库 | OrderFillService |
| 挂单扫描 | PendingOrderExecutionService |
| Spot 结算 | SpotSettlementService |
| Spot 成本持仓 | SpotPositionService |
| 钱包和资产流水 | WalletService、ledger.asset_ledger_entries |
| 永续净仓 | PositionEngine |
| 保证金 | PerpMarginCalculator |
| PnL/ROI | TradingAlgorithmEngine、PnLCalculator |
| 持仓查询/平仓 | PositionService |
| 保护触发 | ProtectiveOrderExecutionService |
| 强平 | LiquidationService |
| 资金费 | FundingService、FundingSettlementScheduler |
| 行情路由 | ProviderResolver、MarketDataRouter、QuoteService |
| 本地行情 | DemoMarketDataGenerator、MarketTestDataService |
| 实时推送 | RealtimeQuoteSink、MarketWsPublisher、TradingWsPublisher |
| K 线 | KLineChartPanel、ChartWorkspace、ChartService |
| 用户终端 | TradingPage、TradingDesktopView、TradePanel、BottomAccountPanel |
| 用户资产/订单/持仓 | WalletPage、OrdersPage、PositionsPage |
| Admin | AdminLayout、AccountsPage、OrdersPage、PositionsPage、TradesPage、provider pages |

### 4.2 必须统一的旁路

当前 MARKET、挂单触发、手动平仓、TP/SL、强平和 Admin force-close 不是同一成交链。实施后所有实际成交必须进入：

    Command
      → DemoExecutionGuard
      → authoritative market snapshot
      → account/wallet/position locks
      → FullFillCoordinator
      → OrderFillService
      → SpotSettlementService 或 PositionEngine
      → Trade + OrderEvent + Ledger
      → STOMP account event

禁止任何服务直接修改 Position/Account/Ledger 来伪造成交结果。

## 5. Binance/OKX 算法与语义对照

本项目不复制两家的全部风控细节，只实现两家共同、可解释、适合 Demo 的语义。

| 主题 | Binance 常见语义 | OKX 常见语义 | 本项目选择 |
|---|---|---|---|
| Spot MARKET | 最优 bid/ask；支持 quantity/quote quantity | 最优 bid/ask；支持 base/quote target currency | 买入输入 USDT budget，卖出输入 base quantity |
| Spot LIMIT | limit 或更优；GTC 等 TIF | limit 或更优；GTC 等 TIF | 只实现 GTC，触价即全量成交 |
| Spot OCO | above/below 两腿，成交一腿取消另一腿 | algo/OCO 条件单 | LIMIT TP + STOP_MARKET SL，共享一份 hold |
| Spot fee asset | 每次 fill 返回 commissionAsset；可有平台币抵扣 | 默认从所得资产扣费，可切 quote | 买入扣 base，卖出扣 USDT；记录 feeAsset |
| Position mode | One-way / Hedge | One-way / Hedge | 账户级 ONE_WAY/HEDGE |
| Margin mode | Cross / Isolated | Cross / Isolated | 交易对级 CROSS/ISOLATED |
| Leverage | 交易对初始杠杆，可有持仓时修改 | 交易对/持仓杠杆，可修改 | 交易对统一杠杆；Hedge 多空共享 |
| Mark price | index + premium/funding basis，用于 UPL/强平 | index/mark，用于风险 | 外部直接使用 venue mark；本地由 index + 受限 premium |
| Funding | 正费率多付空收，负费率反向 | 同 | 后台来源优先级，实际结算 |
| TP/SL | 独立条件单、reduceOnly/closePosition | attached/split TP/SL | 最多 10 个保护单，reduce-only，MARKET/LIMIT |
| Liquidation | 撤单、强平单、保险基金、极端 ADL | 撤单、减仓/强平、保险基金、极端 ADL | 先撤单重算；仍危险则按已确认规则整仓强平；不做保险/ADL |
| Bankruptcy | 保险基金防止用户负余额 | Security Fund/ADL | 用户余额最低为 0，差额记 BANKRUPTCY_SHORTFALL |

官方对照：

- Binance Spot：https://www.binance.com/en/academy/articles/your-guide-to-binance-spot-trading
- Binance USD-M order：https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/trade#new-order
- Binance Perpetual/Mark/Insurance：https://academy.binance.com/kk-KZ/articles/what-are-perpetual-futures-contracts
- Binance OCO：https://academy.binance.com/ur-PK/articles/how-to-place-an-oco-order-with-the-binance-api
- OKX API：https://www.okx.com/docs-v5/en/
- OKX Trading Settings：https://www.okx.com/en-us/help/trading-settings-faq
- OKX Liquidation：https://www.okx.com/en-gb/help/liquidation-faq
- OKX Demo：https://www.okx.com/en-us/help/how-to-conduct-contract-simulation-trading-transactions

## 6. 系统不变量

### 6.1 Demo 隔离

只有同时满足以下条件才允许交易写入：

- execution.mode=demo。
- account.account_type=DEMO。
- productType 属于 CRYPTO_SPOT 或 LINEAR_PERP。
- symbol 在十个产品白名单内且 tradable=true。

MARKET、pending worker、STOP、OCO、TP/SL、funding、liquidation、Admin force-close 和 batch close 都必须调用同一个 DemoExecutionGuard。

### 6.2 成交

- 一笔订单至多产生一次 full fill。
- filledQuantity 必须等于 quantity；否则整笔回滚。
- 每个 fill 恰好生成一条 trading.trades。
- Order、Trade、wallet/account、position、ledger 在同一事务中提交。
- 系统平仓也必须创建 Order 和 Trade。
- 相同 clientOrderId/requestId 重放返回已有结果，不重复扣费。

### 6.3 资金

- Spot 真值：core.wallet_balances，wallet_type=SPOT。
- Perp 真值：core.trading_accounts 的 balance/equity/used_margin/free_margin。
- 不创建 USDT_PERP wallet 镜像，避免双真值。
- available/locked/total 始终守恒。
- Perp balance 不得小于 0；超额穿仓记 BANKRUPTCY_SHORTFALL。
- 现货和永续之间划转使用同一个 transferId 写两侧流水。

### 6.4 仓位

- ONE_WAY：每个 account+symbol 只有 BOTH 槽。
- HEDGE：每个 account+symbol 最多 LONG、SHORT 两个槽。
- ONE_WAY/HEDGE 是账户级设置。
- 切换 position mode 时整个永续账户必须无活动委托、无未平仓。
- margin mode 和 leverage 是 account+symbol 设置。
- 切换 CROSS/ISOLATED 时该 symbol 必须无活动委托、无未平仓。
- Hedge 的 LONG/SHORT 共用该 symbol 的 margin mode 和 leverage。

### 6.5 行情

- Spot 价格 bundle：quote、order book、market trades、candles。
- Perp 价格 bundle：bid/ask/last、mark、index、asOf、source。
- 同一 bundle 禁止跨 venue 混用。
- 优先级：Binance → OKX → LOCAL_SIMULATED。
- 任一必需字段缺失或 stale 时整包切换到下一来源。
- 本地模拟模式允许完整交易、触发、资金费和强平。
- 数据源恢复可直接跳价；页面必须推送 source changed 提示。

## 7. 行情设计

### 7.1 Provider

保留现有 binance Spot provider，新增或完善：

- binance-usdm。
- okx Spot。
- okx-swap。
- local-spot。
- local-perp。

ProviderResolver 从“返回首个 binding”改为“按 priority 返回可用候选”。MarketDataRouter 对 empty、exception、stale 继续尝试下一候选。

### 7.2 Freshness

所有 executable snapshot 必须包含：

- platformSymbol。
- providerCode。
- providerSymbol。
- sourceMode：PUBLIC_EXTERNAL 或 LOCAL_SIMULATED。
- bid、ask、last。
- Perp 的 mark、index。
- asOf。
- expiresAt。

成交前在取得 account/wallet/position 锁后重新校验 snapshot 未过期；锁等待导致过期时不使用旧价格。

### 7.3 本地 Perp mark

    indexPrice = local spot reference
    premium = clamp(simulatedPremium, -premiumLimit, premiumLimit)
    markPrice = indexPrice × (1 + premium)

last、bid/ask、index、mark 来自同一个 local generation。禁止 mark 回退为任意旧 spot mid。

## 8. 订单合同

### 8.1 核心枚举

- ProductType：CRYPTO_SPOT、LINEAR_PERP。
- OrderType：MARKET、LIMIT、STOP_MARKET。
- TimeInForce：GTC。
- PositionMode：ONE_WAY、HEDGE。
- PositionSide：BOTH、LONG、SHORT。
- MarginMode：CASH、CROSS、ISOLATED。
- OrderOrigin：USER、PROTECTIVE、LIQUIDATION、ADMIN_FORCE_CLOSE、BATCH_CLOSE、OCO。
- TriggerPriceType：LAST_PRICE、MARK_PRICE。
- TriggerExecutionType：MARKET、LIMIT。
- ProtectionType：TAKE_PROFIT、STOP_LOSS。

### 8.2 创建订单最小字段

    accountId
    symbol
    side
    orderType
    quantity
    quantityUnit
    price
    triggerPrice
    triggerPriceType
    leverage
    marginMode
    positionSide
    reduceOnly
    clientOrderId
    attachedProtections[]

### 8.3 数量单位

用户可在三种单位之间切换：

- BASE：BTC、ETH 等基础币数量。
- QUOTE：USDT 名义价值。
- CONTRACTS：张数。

后端根据 symbol.contract_size、contract_multiplier 和 authority price 统一转成 baseQuantity。所有规则校验、仓位和 Trade 使用 baseQuantity；原始输入单位和值作为订单快照保留。

现货特殊规则：

- MARKET BUY：输入 QUOTE USDT budget。
- MARKET SELL：输入 BASE quantity。
- LIMIT/STOP/OCO：输入 BASE quantity。

### 8.4 状态

最小状态：

- PENDING_ACTIVATION：附加保护单等待主订单成交。
- PENDING：等待价格条件。
- WORKING：worker 已领取，短暂内部态。
- FILLED。
- CANCELED。
- REJECTED。
- EXPIRED：保护单因仓位消失/缩减而失效。

PARTIALLY_FILLED 只用于读取旧记录，任何新成交不得写入。

## 9. 手续费、滑点与占资

### 9.1 配置

    makerFeeRate = 0.0002
    takerFeeRate = 0.0005
    slippageRate = 0.0001

- MARKET、STOP_MARKET、保护 MARKET、liquidation：taker。
- 提交时立即 marketable 的 LIMIT：taker。
- 等待后触发的 GTC LIMIT：maker。
- 保护 LIMIT 触发后若立即 marketable 为 taker，否则后续 fill 为 maker。
- liquidation 另收 symbol.liquidation_fee_rate。

### 9.2 成交价

    MARKET BUY  = ask × (1 + slippageRate)
    MARKET SELL = bid × (1 - slippageRate)

    LIMIT BUY marketable  when ask <= limitPrice
    LIMIT SELL marketable when bid >= limitPrice
    LIMIT BUY fill  = min(ask, limitPrice)
    LIMIT SELL fill = max(bid, limitPrice)

STOP_MARKET 触发后按 MARKET 价格和滑点成交，不能用 triggerPrice 作为成交价。

### 9.3 Spot fee asset

- BUY：fee 从获得的 base asset 扣除。
- SELL：fee 从获得的 USDT 扣除。
- Order 和 Trade 都保存 fee、feeAsset、liquidityRole。

### 9.4 Hold

    worstBuyPrice = ask × (1 + slippageRate)
    worstSellPrice = bid × (1 - slippageRate)
    worstFeeRate = max(makerFeeRate, takerFeeRate)

    spotBuyHold = quantity × worstBuyPrice × (1 + worstFeeRate)
    spotSellHold = baseQuantity
    perpOpenHold = initialMargin(worstPrice, leverage)
                 + fullOrderNotional × worstFeeRate
                 + adverseCloseLoss

- Spot OCO 两腿共享一份 hold，不能双重冻结。
- 纯 reduce-only Perp 不预占 initial margin。
- 取消、拒绝、OCO 对腿取消时完整释放未使用 hold。

## 10. Spot 结算

### 10.1 市价买

1. quote budget 换算 gross base。
2. 从 SPOT/USDT 扣 quote。
3. fee 以 base 扣除。
4. 把 net base 加入 SPOT/base。
5. 更新 spot_positions 平均成本。
6. 写两侧 asset ledger、Order、Trade、Event。

### 10.2 市价卖

1. 扣 SPOT/base。
2. 计算 gross USDT。
3. 从 gross USDT 扣 fee。
4. net USDT 加入 SPOT/USDT。
5. 按平均成本计算 realized PnL。
6. 更新 spot_positions 和流水。

### 10.3 LIMIT

- 提交即 marketable：直接走 full fill。
- 否则冻结资金，PENDING。
- price event 或 1 秒 worker 检查。
- 每个订单独立事务，单笔失败不回滚整批。

### 10.4 STOP_MARKET

- BUY：LAST_PRICE >= triggerPrice。
- SELL：LAST_PRICE <= triggerPrice。
- 触发后 MARKET full fill。

### 10.5 OCO

- 一组包含 LIMIT leg 与 STOP_MARKET leg。
- BUY/SELL 均支持。
- 两腿使用相同 quantity、side、groupId。
- SELL：limitPrice 必须高于当前 last，stopTrigger 必须低于当前 last。
- BUY：limitPrice 必须低于当前 last，stopTrigger 必须高于当前 last。
- 任一腿 FILLED，另一腿原子 CANCELED。
- 任一腿被用户取消时整组取消。
- group 共享 hold；释放只发生一次。

## 11. 永续持仓算法

### 11.1 Linear Perp

所有五个产品是 USDT Linear Perp：

    notional = abs(baseQuantity) × markPrice
    initialMargin = entryNotional ÷ leverage
    maintenanceMargin = markNotional × maintenanceMarginRate

    longUPL = (markPrice - entryPrice) × quantity
    shortUPL = (entryPrice - markPrice) × quantity
    ROI = UPL ÷ positionMargin × 100%

UPL 和强平使用 markPrice；成交与 realized PnL 使用实际 fillPrice。

### 11.2 ONE_WAY

- 只有 BOTH 槽。
- 空仓 BUY 开多；空仓 SELL 开空。
- 同向 fill 按数量加权平均 entryPrice。
- 反向 fill 先减仓。
- 反向 quantity 等于仓位：完全平仓。
- 反向 quantity 大于仓位且 reduceOnly=false：旧仓平掉，剩余量以本次 fillPrice 反手。
- reduceOnly 超出可平量：整单拒绝，不自动缩量。

### 11.3 HEDGE

- BUY + LONG：增加多仓。
- SELL + LONG：减少多仓。
- SELL + SHORT：增加空仓。
- BUY + SHORT：减少空仓。
- 平仓 quantity 大于对应腿：整单拒绝。
- LONG/SHORT 互不净额，但共享该 symbol 的 leverage 和 margin mode。

### 11.4 部分平仓

- 用户可以输入 base、USDT notional 或 contracts。
- 后端换算 baseQuantity 后校验不超过仓位。
- 只对平掉的数量计算 realized PnL、fee 和释放 margin。
- 剩余仓位保持原 entryPrice。
- 保护订单按第 13 节重新分配。

### 11.5 杠杆修改

- 默认 10×，范围 1–100×，同时受 symbol.max_leverage 约束。
- 有持仓时允许修改。
- 降低杠杆需要增加 initial margin；可用余额不足则拒绝。
- 提高杠杆释放多余 initial margin。
- 不改变 quantity、entryPrice、realized PnL。
- Hedge LONG/SHORT 同时使用新杠杆。

## 12. Cross 与 Isolated

### 12.1 Cross

    crossEquity = perpBalance
                + sum(cross UPL)
                + realized cashflows not yet reflected

    crossMaintenance = sum(cross maintenanceMargin)
    crossAvailable = crossEquity
                   - sum(cross initialMargin)
                   - active opening order holds

- 所有 Cross 仓共享 Perp account balance。
- 一笔亏损、手续费或负资金费影响整个 Cross risk pool。
- Cross 的预计强平价是模拟估算，实际触发以账户级 crossEquity 为准。

### 12.2 Isolated

    isolatedEquity = isolatedMargin + UPL + isolatedFundingPnl
    isolatedMaintenance = maintenanceMargin + estimatedCloseTakerFee

- 每个 position slot 有独立 isolatedMargin。
- 亏损、资金费和强平只消耗该 slot。
- 其他 Isolated 仓和 Cross balance 不得被隐式挪用。

### 12.3 手动调整逐仓保证金

- ADD：从 Perp available balance 转入 isolatedMargin。
- REDUCE：从 isolatedMargin 返回 available balance。
- REDUCE 前模拟调整后的 equity/MM；若会立即触发强平则整笔拒绝。
- 暂不支持自动追加保证金。
- 调整后立即重算 estimatedLiquidationPrice，并写 Ledger/Event。

### 12.4 简化预计强平价

逐仓可展示：

    long estimatedLiq
      ≈ (entryPrice - isolatedMargin / quantity)
       / (1 - maintenanceMarginRate - closeTakerFeeRate)

    short estimatedLiq
      ≈ (entryPrice + isolatedMargin / quantity)
       / (1 + maintenanceMarginRate + closeTakerFeeRate)

Cross 使用账户级风险方程反解估算值。展示和实际 trigger 必须由同一个 PerpetualRiskService 计算。

UI 必须提示：

> 预计强平价使用简化 Demo 模型，不代表 Binance、OKX 或其他交易所的完整风控，不可用于真实资金交易。

## 13. 永续保护单

### 13.1 表达方式

保护单复用 trading.orders，不新建第二套成交表。新增关联字段：

- parentOrderId。
- parentPositionId。
- protectionType。
- triggerPrice。
- triggerPriceType=MARK_PRICE。
- triggerExecutionType=MARKET/LIMIT。
- contingencyGroupId。
- protectedQuantity。
- reduceOnly=true。
- orderOrigin=PROTECTIVE。

开仓订单附加的保护单先为 PENDING_ACTIVATION；主订单 FILLED 后绑定真实 position slot 并激活。

### 13.2 数量和档数

- 每个仓位最多 10 个 active 保护单，TP/SL 可任意组合。
- 同一 protectionType 的 protectedQuantity 总和不得超过当前 position quantity。
- 每档 quantity 必须满足 symbol step/min rules。
- TP/SL 平仓数量大于当前可平量时不得反向开仓。

### 13.3 触发

LONG：

- TAKE_PROFIT：mark >= trigger。
- STOP_LOSS：mark <= trigger。

SHORT：

- TAKE_PROFIT：mark <= trigger。
- STOP_LOSS：mark >= trigger。

MARKET protection：

- 触发后创建/转换为 reduce-only MARKET。
- 按 bid/ask + slippage full fill。

LIMIT protection：

- 触发后成为 reduce-only LIMIT。
- 若当时 marketable，立即 taker full fill。
- 否则 PENDING GTC；后续触价 maker full fill。

### 13.4 仓位变化后的保护单

任一手动平仓、保护成交、强平或 Admin close 使仓位减少后：

1. 按 protectionType 分别计算 active total。
2. 先创建的保护单优先保留。
3. 最新创建的保护单先缩减。
4. quantity 缩减为 0 的订单标记 EXPIRED。
5. 所有调整写 OrderEvent。
6. 仓位归零时取消/过期全部保护单。

这套顺序是本项目的确定性规则；Binance/OKX 没有公开一个共同的自动缩减排序。

## 14. 资金费

### 14.1 配置

每个 Perp symbol 保存有序来源优先级，默认：

    BINANCE → OKX → FIXED

FIXED 默认：

- rate=0.0001，即 0.01%。
- interval=8 小时。

Admin 可以修改：

- 来源优先级。
- fixed rate。
- fixed interval。
- provider freshness threshold。

### 14.2 外部来源

- BINANCE 使用 USD-M premium index/funding endpoint。
- OKX 使用 SWAP funding-rate endpoint。
- 外部 rate 必须包含 fundingTime、nextFundingTime、asOf、providerCode。
- 选择外部来源时，结算时间跟随 provider nextFundingTime。
- FIXED 只在前序外部来源不可用/stale 时作为 fallback，使用 8 小时边界。
- 每次 settlement 保存实际 selectedSource，不把 fallback 伪装成 primary。

FundingRateIngestionService 负责：

- 按来源优先级轮询当前 rate/nextFundingTime。
- 在应用启动和 provider 恢复时，从最后一个已持久化 fundingTime 之后拉取历史 rate。
- 把选中的实际来源写入 trading.funding_rates。
- source 切换后只接受严格晚于最后已持久化 fundingTime 的新周期，避免短时间重复结算。
- 对相同 symbol+fundingTime 只保留一条 canonical rate。
- 外部历史仍不可用时继续尝试下一来源；最终 FIXED 按 8 小时边界生成缺失周期。

### 14.3 结算公式

    fundingNotional = abs(positionQuantity) × settlementMarkPrice
    fundingAmount = fundingNotional × fundingRate

- 正费率：LONG 支付，SHORT 收款。
- 负费率：SHORT 支付，LONG 收款。
- 系统作为模拟对手方；不要求真实多空总额相等。
- Cross 支付/收款进入 Perp account balance。
- Isolated 支付/收款进入 isolatedMargin。
- 负资金费可以触发 Cross/Isolated 强平。
- position.fundingPnl 累加用户实际现金流。

### 14.4 幂等与补结算

- 唯一键：positionId + fundingTime。
- scheduler 启动时查询未结算 funding cycle。
- 服务停机跨过结算点时必须补结算。
- 已结算周期永不按新费率追溯重算。
- 同周期重试返回已有 settlement，零二次资金变化。
- Settlement 保存 rate、source、markPrice、positionSide、marginMode、amount、balanceAfter。

### 14.5 查询

用户和 Admin 都能查询：

- symbol。
- position side。
- margin mode。
- funding rate。
- actual source。
- funding time。
- amount。
- balance/margin after。

## 15. 模拟强平

### 15.1 触发

Isolated：

    isolatedEquity
      <= maintenanceMargin + estimatedCloseTakerFee

Cross：

    crossEquity
      <= totalCrossMaintenance + estimatedCloseTakerFees

liquidationFee 不计入触发阈值；实际强平成交后另扣。

### 15.2 Isolated 流程

1. 取消与目标 symbol/positionSide 相关的增加风险订单。
2. 释放订单 hold。
3. 重新加载新鲜 mark 并重算。
4. 若恢复安全，停止。
5. 若仍危险，整仓创建 LIQUIDATION reduce-only MARKET。
6. full fill，扣 taker fee 和 liquidation fee。
7. 关闭 position，写 Order/Trade/Event/Ledger/notification。

### 15.3 Cross 流程

1. 取消该 Perp account 的全部活动委托，包括保护 LIMIT。
2. 释放所有 opening holds。
3. 重新加载所有 Perp bundle 并重算 cross risk。
4. 若恢复安全，停止。
5. 若仍危险，一次处理全部 Cross positions。
6. 每个 position 生成独立 LIQUIDATION MARKET system order。
7. 单仓失败不伪装成功；账户事件返回逐仓结果。
8. 任一 Cross 仓未能关闭时账户进入 LIQUIDATION_PENDING，禁止增加风险的新订单，并由 worker 在新鲜行情恢复后继续重试。

用户要求 Cross 触发后一次性平掉全部 Cross 仓，不做交易所的逐级减仓。

### 15.4 穿仓

- Perp balance/isolatedMargin 最低为 0。
- fill loss + taker fee + liquidation fee 超出可用资金时：
  - 实际可扣部分正常入账。
  - 差额写 BANKRUPTCY_SHORTFALL Ledger/Audit。
  - 不创建真实保险基金。
  - 不执行 ADL。
  - 不影响其他用户。

## 16. 手动与批量平仓

### 16.1 单仓

- 用户输入 quantity，可部分或全部。
- 后端重新读取当前 position。
- 超过可平量整单拒绝。
- 创建 reduce-only order。
- 市价或限价成交沿用统一 full-fill。

### 16.2 Close all

- 用户端和 Admin 都可一键平仓。
- 每个 position 使用派生的 idempotency key。
- 每仓独立事务。
- 某个 symbol stale/失败不回滚其他成功仓。
- 返回 positionId、orderId、status、errorCode。

### 16.3 Admin force cleanup

独立操作，不与 reset 合并：

1. 撤销目标账户全部活动委托。
2. 按当前 authority market price 平掉全部永续仓位。
3. 生成标准 Order/Trade/Event/Fee/PnL/Ledger。
4. 记录 admin actor、reason、requestId。
5. 完成后管理员才能另行 reset。

## 17. Spot/Perp 内部划转

### 17.1 规则

- 只允许 USDT。
- SPOT → PERP 或 PERP → SPOT。
- 1:1，即时，无手续费。
- 只能使用 available balance。
- 不得动用 Spot locked。
- 不得转出 Perp usedMargin、order holds 或会导致风险不足的余额。
- 相同 requestId 幂等。

### 17.2 账务

一个 transferId：

- Spot asset ledger 写 TRANSFER_OUT/TRANSFER_IN。
- Perp cash ledger 写相反方向。
- 两侧在同一事务提交。
- 查询时合并成一条 transfer history。

## 18. Demo 初始化、迁移与重置

### 18.1 一次性迁移

用户已明确授权：

- 清空所有现有 DEMO account 关联的订单、成交、持仓、保护单、funding settlement、wallet、ledger 和 snapshot 数据。
- 删除旧 DEMO accounts。
- 保留 auth users、Admin、market provider、symbol/config/content 数据。
- 为每个普通 active user 新建一个 DEMO account。
- 初始化 Spot 50,000 USDT、Perp 50,000 USDT。

LIVE account 数据不得删除。

### 18.2 日常 reset

用户和 Admin reset 均必须：

- account_type=DEMO。
- 无活动订单/OCO/protection。
- 无未平永续仓位。

否则返回 DEMO_RESET_BLOCKED。

reset 成功：

- 清零非 USDT Spot assets 和 spot cost position 当前数量。
- Spot USDT 恢复 50,000。
- Perp balance/equity/freeMargin 恢复 50,000。
- usedMargin=0。
- position mode 恢复 ONE_WAY。
- symbol settings 恢复 CROSS、10× 和默认 quantity unit。
- 历史 Order、Trade、Funding、Transfer、Ledger 保留。
- 写 DEMO_RESET ledger/audit。
- 相同 requestId 重试不重复写。

## 19. WebSocket 与通知

### 19.1 Topic

市场 topic 允许匿名：

- /topic/market/quotes/{symbol}
- /topic/market/order-book/{symbol}
- /topic/market/trades/{symbol}
- /topic/market/perp-reference/{symbol}

账户 topic 必须认证并校验 account ownership：

- /user/queue/trading-events，优先改为 user destination。

禁止继续允许匿名订阅 /topic/trading/accounts/{accountId}/events。

### 19.2 事件

- ORDER_ACCEPTED/PENDING/FILLED/CANCELED/REJECTED/EXPIRED。
- TRADE_CREATED。
- BALANCE_UPDATED。
- POSITION_UPDATED/CLOSED。
- PROTECTION_TRIGGERED/RESIZED/CANCELED。
- FUNDING_SETTLED。
- MARGIN_ADJUSTED。
- LIQUIDATION。
- TRANSFER_COMPLETED。
- DEMO_RESET。
- MARKET_SOURCE_CHANGED。

Web 对 100–300ms 内事件做合并刷新；账户全量轮询降为 10–30 秒兜底，并提供 single-flight。

### 19.3 通知

只做站内实时通知、toast 和历史列表；不做邮件/SMS。

## 20. API 设计

### 20.1 Trading settings

- GET /api/accounts/{accountId}/trading-settings
- PATCH /api/accounts/{accountId}/position-mode
- PATCH /api/accounts/{accountId}/symbols/{symbol}/settings
- POST /api/trading/positions/{positionId}/margin

symbol settings 包含：

- leverage。
- marginMode。
- quantityUnit。

### 20.2 Orders

保留并扩展：

- POST /api/trading/orders
- GET /api/trading/orders
- PATCH /api/trading/orders/{id}
- POST /api/trading/orders/{id}/cancel
- GET /api/trading/orders/{id}/events

新增：

- POST /api/trading/orders/cancel-all
- POST /api/trading/oco
- POST /api/trading/positions/{id}/protections
- PATCH /api/trading/protections/{orderId}
- DELETE /api/trading/protections/{orderId}

### 20.3 Trades/positions

- GET /api/trading/trades?accountId=
- GET /api/trading/positions?accountId=
- GET /api/trading/positions/history?accountId=
- POST /api/trading/positions/{id}/close
- POST /api/trading/positions/close-all

### 20.4 Funding

- GET /api/trading/funding/settlements?accountId=
- Admin GET/PUT /api/admin/market/symbols/{id}/funding-config

### 20.5 Account

- POST /api/accounts/{id}/transfers
- GET /api/accounts/{id}/transfers
- POST /api/accounts/{id}/demo-reset

Admin：

- GET /api/admin/accounts/{id}/wallet-balances
- GET /api/admin/accounts/{id}/asset-ledger
- GET /api/admin/accounts/{id}/funding-settlements
- POST /api/admin/accounts/{id}/force-cleanup
- POST /api/admin/accounts/{id}/demo-reset

### 20.6 Market

扩展现有响应：

- sourceMode。
- providerCode。
- asOf。
- expiresAt。
- stale。

Perp：

- GET /api/market/perpetuals/{symbol}/reference

## 21. 数据库最小变化

使用两份聚焦迁移，不创建 close batch、exposure event、insurance/ADL 表。

### 21.1 V46：产品、账户、订单和仓位

core.trading_accounts：

- position_mode。
- demo_generation。
- reset_at。
- 每用户一个 active DEMO 的部分唯一索引。

trading.account_symbol_settings（新表）：

- account_id。
- symbol。
- leverage。
- margin_mode。
- quantity_unit。
- version。
- unique(account_id,symbol)。

trading.orders：

- product_type。
- position_mode。
- position_side。
- margin_mode。
- quantity_unit。
- original_quantity。
- base_quantity。
- time_in_force。
- reduce_only。
- order_origin。
- system_reason。
- trigger_price。
- trigger_price_type。
- trigger_execution_type。
- protection_type。
- parent_order_id。
- parent_position_id。
- contingency_group_id。
- hold_owner_order_id。
- liquidity_role。
- fee_asset。
- version。

trading.trades：

- product_type。
- position_side。
- margin_mode。
- fee。
- fee_asset。
- liquidity_role。
- system_reason。
- source_mode。
- provider_code。

trading.positions：

- product_type。
- position_mode。
- position_side。
- margin_mode。
- version。

索引：

- ONE_WAY open BOTH slot。
- HEDGE open LONG/SHORT slots。
- active order/group/parent position。

### 21.2 V47：Funding、source 和 Demo 重建

market.symbols：

- fixed_funding_rate。
- fixed_funding_interval_minutes。
- funding_source_priority。
- funding_stale_seconds。

trading.funding_rates：

- provider_code/source_mode。
- as_of。
- interval_minutes。
- raw_payload_hash。

trading.funding_settlements：

- position_side。
- margin_mode。
- mark_price。
- source。
- balance_after。
- isolated_margin_after。
- shortfall。

Demo data：

- 按第 18 节清理并重建。
- seed 五个 Spot、五个 Perp 和 provider bindings。

现有 ledger 通过 operation_type/reference_type/reference_id 表达 transfer、reset、bankruptcy，不新建 transfer table。

## 22. Web 设计

### 22.1 路由

- /trade/spot/:symbol?
- /trade/perpetual/:symbol?
- /trading 保留兼容重定向。

两个路由复用 TradingPage，不复制终端。

### 22.2 桌面

保留现有：

- ticker/watchlist。
- KLineChartPanel。
- OrderBook/RecentTrades。
- TradePanel。
- BottomAccountPanel。
- resize/drag layout。

新增/接通：

- Spot/Perp 产品头。
- market source badge。
- Perp mark/index/funding/countdown。
- account position mode。
- symbol margin mode/leverage/unit。
- open/close、long/short、reduce-only。
- multi-level protections。
- partial close、margin adjustment、close-all。
- current orders/trades/funding/transfer history。

### 22.3 移动

删除 MobileTradingTerminal 中固定的 Perpetual/Cross/100x/7.34。移动端必须消费与桌面相同的：

- selected product/symbol。
- account and symbol settings。
- TradePanel form state。
- balances/positions/orders。
- validation and payload adapter。

### 22.4 数据可信度

ready 状态禁止：

- useMockBalances 补真实资产。
- bottomAccountPanelData 补虚构订单/仓位。
- quote 失败时前端生成可交易价格。

loading 使用 skeleton；empty 使用明确空状态；LOCAL_SIMULATED 数据由后端返回并标识。

## 23. Admin 设计

主导航必须包含：

- Accounts。
- Trading Orders。
- Positions。
- Trades。
- Funding Settlements。
- Market Status。
- Risk。
- Audit Logs。

Accounts detail：

- Spot/Perp balance。
- locked/used/free margin。
- orders/trades/positions。
- funding/transfer/ledger。
- force cleanup。
- reset。

高风险操作：

- 必须二次确认。
- 必须输入 reason。
- pending 防双击。
- 显示 requestId/auditId。
- Admin reset 不能绕过 active state；必须先 force cleanup。

资金费配置：

- 每 symbol priority editor。
- fixed rate/interval。
- provider freshness。
- 当前 selected source 和 next funding time。

## 24. 错误码

至少包括：

- EXECUTION_DISABLED。
- DEMO_ACCOUNT_REQUIRED。
- PRODUCT_NOT_ALLOWED。
- SYMBOL_NOT_TRADABLE。
- MARKET_DATA_UNAVAILABLE。
- MARKET_DATA_STALE。
- MARKET_BUNDLE_INCOMPLETE。
- INSUFFICIENT_BALANCE。
- INSUFFICIENT_MARGIN。
- ORDER_NOT_CANCELABLE。
- ORDER_NOT_MODIFIABLE。
- PARTIAL_FILL_NOT_SUPPORTED。
- POSITION_MODE_SWITCH_BLOCKED。
- MARGIN_MODE_SWITCH_BLOCKED。
- LEVERAGE_OUT_OF_RANGE。
- REDUCE_ONLY_EXCEEDS_POSITION。
- PROTECTION_LIMIT_EXCEEDED。
- PROTECTION_QUANTITY_EXCEEDED。
- POSITION_NOT_FOUND。
- MARGIN_REDUCTION_UNSAFE。
- FUNDING_RATE_UNAVAILABLE。
- DEMO_RESET_BLOCKED。
- TRANSFER_AMOUNT_UNAVAILABLE。
- ACCOUNT_TOPIC_FORBIDDEN。

失败语义：

- 业务拒绝必须零资金/仓位 mutation。
- worker transient failure 保持 PENDING 并记录 event。
- stale 行情不触发成交、资金费或强平。
- 一键操作返回逐项结果。

## 25. 测试设计

### 25.1 Backend unit

- Spot buy/sell fee asset、平均成本、realized PnL。
- MARKET/LIMIT/STOP price rules。
- OCO shared hold、one-cancel-other。
- quantity unit conversion。
- ONE_WAY increase/reduce/reverse。
- HEDGE LONG/SHORT。
- Cross/Isolated margin。
- leverage adjustment。
- partial close。
- protection direction、MARKET/LIMIT、resize order。
- funding sign/source/fallback/catch-up。
- liquidation trigger、fees、shortfall。
- transfer/reset。

### 25.2 PostgreSQL integration

- V1→V47 空库迁移。
- 已有 V45 数据升级并清理 DEMO。
- one active DEMO constraint。
- open position slot constraints。
- clientOrderId/funding cycle idempotency。
- account/wallet/position row locks。
- concurrent fill/cancel/reset/funding/liquidation。
- ledger and balance invariants。

### 25.3 Contract

- OpenAPI export/generate/check。
- Web/Admin generated types。
- canonical BTCUSDT-PERP 不被去掉连字符。

### 25.4 Browser E2E

真实 backend/database，不 mock trading API：

1. Spot MARKET buy/sell。
2. Spot LIMIT pending → trigger/cancel。
3. Spot STOP_MARKET。
4. Spot OCO。
5. Perp ONE_WAY/HEDGE。
6. CROSS/ISOLATED。
7. leverage/unit switch。
8. partial close、10-level protections。
9. funding settlement。
10. margin adjust。
11. liquidation。
12. transfer。
13. close-all、Admin force cleanup、reset。

视口：

- Web desktop 1440×900。
- Web mobile 390×844。
- Admin desktop 1440×900。
- Admin mobile 390×844。

### 25.5 行情模式

必须分别验收：

- PUBLIC：Binance 主源。
- PUBLIC failover：强制 Binance 失败后 OKX。
- LOCAL_SIMULATED：禁止外部网络仍完整交易。
- source recovery：允许价格跳变并推送 source changed。
- Perp bundle 不跨源混合。

## 26. 实施阶段

### Phase 0：工具链和基线

- 使用 C:\workspace\.tools 的 JDK 21、Maven 3.9.9。
- 使用 Codex runtime Node 24 驱动现有 npm CLI。
- 启动 Docker Desktop。
- 记录当前测试基线，不把既有失败归因于新代码。

### Phase 1：迁移、合同和 Demo guard

- V46/V47。
- enums/entities/DTO/OpenAPI。
- DemoExecutionGuard。
- authoritative locks 和 idempotency。

### Phase 2：行情 bundle

- Binance/OKX Spot/Perp adapters。
- candidate fallback。
- local bundle。
- source metadata/STOMP。

### Phase 3：统一 full-fill 与 Spot

- FullFillCoordinator。
- MARKET/LIMIT/STOP。
- OCO。
- wallet/spot position/fees/trades。

### Phase 4：Perp position/margin

- account/symbol settings。
- ONE_WAY/HEDGE。
- CROSS/ISOLATED。
- leverage/unit/partial close/margin adjust。

### Phase 5：Protection、Funding、Liquidation

- multi-level protection。
- funding sources/settlement/catch-up。
- unified system close。
- liquidation/shortfall。

### Phase 6：Transfer、Reset、Batch/Admin

- internal transfer。
- reset gate。
- cancel-all/close-all。
- Admin force cleanup/config/history。

### Phase 7：Web/Mobile/Admin

- product routes和 controls。
- remove truth mock。
- realtime refresh。
- responsive parity。

### Phase 8：验证

- backend tests。
- web/admin tests/build。
- architecture/contract。
- public/offline browser E2E。
- screenshots。

## 27. Definition of Done

只有全部满足才可宣称完成：

1. 十个产品可见且其他产品不可交易。
2. 每用户一个 Demo；Spot/Perp 各 50,000 USDT。
3. PUBLIC Binance→OKX→LOCAL 自动切换可验证。
4. Spot MARKET/LIMIT/STOP/OCO 闭环。
5. Perp MARKET/LIMIT/STOP，ONE_WAY/HEDGE，CROSS/ISOLATED 闭环。
6. 1–100× 杠杆、三种数量单位、手动逐仓保证金调整。
7. 手动部分/全部平仓、cancel-all、close-all。
8. 每仓最多 10 个 TP/SL，MARKET/LIMIT 触发和自动 resize 正确。
9. Funding 按 Binance→OKX→FIXED 实际结算、补结算且幂等。
10. Isolated/Cross 强平产生 Order/Trade/Fee/PnL/Ledger/notification。
11. 穿仓余额不为负且有 BANKRUPTCY_SHORTFALL。
12. Spot↔Perp USDT 划转守恒。
13. reset 拒绝 active state；Admin force cleanup 与 reset 分离。
14. 所有账户 WebSocket 订阅有所有权校验。
15. 桌面/移动 Web 与 Admin 四类视口真实操作通过。
16. Java/Maven/Node/Docker 环境下全量约定命令通过。
17. 没有真实 private exchange/broker/FIX/LP 调用。
18. 没有保险基金、ADL、真实撮合、微服务或消息队列。
