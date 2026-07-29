# P0 用户层交易全景验收测试设计

> 状态：设计方向已批准；书面规格已完成源码复核，待用户审阅
> 目标分支：`codex/usdt-spot-perp-p0`
> 目标交易代码基线：`5f4cb80d226c8842625257f3dae97c61598bbb9d`（本文档提交可位于其后）
> 用户端：`apps/web`
> 后端：`backend`
> 设计日期：2026-07-14

## 1. 目标与验收结论

本设计用于回答一个用户层问题：普通 DEMO 用户能否通过真实 Web 页面，稳定、正确地完成 P0 范围内的 Spot 和 USDT Linear Perpetual 交易生命周期，并在每次状态变化后看到与后端、数据库和账务一致的数据。

本套件不是只验证接口，也不是只验证页面能点击。每个用户动作必须真实经过 Web UI；REST、PostgreSQL、STOMP 和 Admin 测试控制只作为结果 oracle 或测试夹具。

详细矩阵共 60 个顶层用例；包含多个独立子运行的用例必须逐个记录结果，不能用其中一个通过代表全部分支通过。

只有同时满足以下条件，才允许给出“用户层交易稳定并正确”的结论：

1. 本文所有 P0 用例为 `PASS`，没有未解释的 `FAIL`。
2. 环境阻塞必须报告为 `BLOCKED`，不得记为 `PASS`。
3. PostgreSQL/Testcontainers 测试为 0 skipped、0 failures、0 errors。
4. 登录态浏览器测试真实使用 backend、PostgreSQL、Redis、Web 和 Admin，没有拦截或 mock trading API。
5. UI、REST、数据库、Ledger 和账户事件在同一个业务动作上可相互追踪。
6. 任何业务拒绝都保持资金、hold、订单、仓位和流水零 mutation。
7. 没有真实 broker、FIX、LP、交易所私有下单或 LIVE 资金调用。

## 2. 固定范围

### 2.1 P0 正向能力

- Spot：`MARKET`、`LIMIT/GTC`、`STOP_MARKET`、OCO。
- Perpetual：`MARKET`、`LIMIT/GTC`、`STOP_MARKET`。
- Position mode：`ONE_WAY`、`HEDGE`。
- Margin mode：`CROSS`、`ISOLATED`。
- Leverage：1–100x，且受 symbol 最大杠杆限制。
- Quantity unit：`BASE`、`QUOTE/USDT_NOTIONAL`、`CONTRACTS`。
- Perpetual：同向加仓、减仓、ONE_WAY 反手、HEDGE 双向仓。
- Perpetual：部分平仓、单仓全平、账户级 `close-all`、`cancel-all`。
- Perpetual：最多 10 档 TP/SL，支持 MARKET/LIMIT 触发和仓位缩减后的保护量重排。
- ISOLATED：手动增加/减少保证金。
- Funding：正负费率、来源降级、结算、补结算、幂等。
- Liquidation：ISOLATED/CROSS、强平费用、穿仓差额。
- Spot↔Perp USDT 划转、DEMO reset、Admin force cleanup。
- Binance→OKX→LOCAL_SIMULATED 行情来源切换和恢复。

### 2.2 明确不支持但必须验证拒绝的能力

- 新订单部分成交；P0 只允许一次 full fill。
- 普通 `STOP` 和 stop-limit。
- IOC、FOK、GTD、post-only。
- trailing stop、iceberg、TWAP、split order。
- Spot short、Spot reduce-only、Spot position TP/SL。
- Perpetual 普通挂单修改。
- OCO 子腿独立修改。
- HEDGE 单笔穿过对应 slot 后反手。
- Inverse Perpetual、Option、Forex。
- 真实 broker、FIX、LP、保险基金、ADL。

出现这些能力时，正确结果是页面不暴露入口或后端明确拒绝；不得为了“覆盖更多”直接构造项目未承诺的成功结果。

## 3. 执行架构

### 3.1 分层策略

| 层 | 动作来源 | 用途 | 是否允许替代 UI |
|---|---|---|---|
| L1 用户动作 | Web 浏览器点击、输入、确认 | 证明普通用户能完成操作 | 不适用 |
| L2 页面观测 | 当前委托、持仓、历史、成交、资金费、钱包 | 验证用户实际看到的数据 | 否 |
| L3 REST oracle | 用户/管理员只读 API | 获取未被 UI 完整显示的精确值 | 否 |
| L4 数据库 oracle | PostgreSQL 只读 SQL | 核验唯一性、账务和关联关系 | 否 |
| L5 事件 oracle | authenticated STOMP 与浏览器网络日志 | 验证实时刷新和所有权 | 否 |
| L6 测试夹具 | Admin 配置、专用测试数据、经探针证明有效的 authority-bundle fixture | 确定性触发挂单、资金费和强平 | 只能制造外部条件 |
| L7 负向合同探针 | 重放捕获请求并只修改 UI 明确不暴露的非法字段 | 证明后端纵深拒绝 | 不能作为用户正向能力证据 |
| L8 幂等/重放探针 | 在同一认证上下文重发首次 UI 动作捕获的 method、URL、headers 和 body | 验证相同 key 重放或同 key 不同 fingerprint | 不能替代首次 UI 动作 |

任何被声称为普通用户可完成的下单、改单、撤单、部分平仓、全平、close-all、cancel-all、划转和用户 reset，其首次 mutation 若由 API 直接完成，该用例必须记为 `INVALID_TEST`，不能记为 `PASS`。只有当 UI 正确隐藏某个非法组合时，才允许用 L7 补充证明后端也拒绝；只有首次 mutation 已真实经过 UI 后，才允许用 L8 重放其捕获请求。报告分别写入 `contractProbes`、`replayProbes`，不得混入 `userActions`。

### 3.2 四种后端运行配置

所有配置固定：

```text
SPRING_PROFILES_ACTIVE=dev
EXECUTION_MODE=demo
MARKET_TEST_CONTROL_ENABLED=true
MARKET_PROVIDER_INSTRUMENT_SYNC_ENABLED=false
MARKET_REALTIME_ENABLED=false
TRADING_FX_FINANCING_ENABLED=false
```

按用例组只打开需要的 worker：

| Profile | Pending | Protective | Funding | Liquidation | 适用用例 |
|---|---:|---:|---:|---:|---|
| `UI_CORE` | false | false | false | false | 登录、MARKET、立即成交 LIMIT、设置、划转、reset |
| `ORDER_TRIGGER` | true | true | false | false | pending LIMIT、STOP_MARKET、OCO、TP/SL |
| `FUNDING_ONLY` | false | false | true | false | funding 来源、正负结算、补结算、幂等 |
| `LIQUIDATION_ONLY` | false | false | false | true | ISOLATED/CROSS 强平、shortfall |

四个开关的实际环境变量分别是 `TRADING_PENDING_ORDER_EXECUTION_ENABLED`、`TRADING_PROTECTIVE_ORDER_EXECUTION_ENABLED`、`TRADING_FUNDING_ENABLED`、`TRADING_LIQUIDATION_ENABLED`。worker 是启动时条件 Bean；切换配置必须重启 backend，不能在同一进程中动态改开关。测试扫描周期可显式设置：

```text
TRADING_PENDING_ORDER_SCAN_MS=500
TRADING_PROTECTIVE_ORDER_SCAN_MS=500
TRADING_FUNDING_SCAN_MS=500
TRADING_LIQUIDATION_SCAN_INTERVAL_MS=500
```

断言仍必须等待业务状态，不能以固定睡眠代替。`FUNDING_ONLY` 关闭的只是周期性 liquidation scheduler；funding 结算后的风险扫描仍可调用 liquidation，因此 Funding 用例必须使用不会触发强平的安全仓位。

### 3.3 隔离规则

1. 每次 suite 创建随机专用数据库，例如 `fx_p0_user_e2e_${UTC}_${RANDOM}`；执行前给变量赋实际值并写入报告。
2. 每个会改变账户的独立用例使用新注册用户；同一生命周期的步骤必须复用同一用户。
3. 需要两个用户的安全用例创建 `USER_A` 和 `USER_B`。
4. Redis、Docker container name、API/Web/Admin 端口为共享资源，因此用例串行执行。
5. 开始前拒绝复用未知的 18086、5199、5200 服务，避免连接到错误数据库。
6. finally 中删除所有行情 override、恢复 funding/provider 配置、停止进程并删除专用数据库。
7. 不执行 `docker compose down -v`，避免删除用户已有 volume；只清理由本套件创建的数据库和进程。

## 4. Codex 执行合同

### 4.1 开始前门禁

Codex 必须在目标 worktree 中记录：

```powershell
git branch --show-current
git rev-parse HEAD
git merge-base --is-ancestor 5f4cb80d226c8842625257f3dae97c61598bbb9d HEAD
git diff --name-status 5f4cb80d226c8842625257f3dae97c61598bbb9d..HEAD
git status --short
docker version
docker compose -f fx-trading-platform/infra/docker-compose.yml ps
```

预期 branch 与本文头部一致，且目标交易代码基线必须是实际 `HEAD` 的祖先。基线之后只允许本文档等已审阅的非交易差异；若出现交易、行情、钱包、Web/Admin 行为代码变化，必须先重新做源码合同复核。若用户明确指定了更新的 P0 commit，可以继续，但报告必须记录实际 commit 和相对本文基线的差异，并先确认本文仍与实现一致。

先执行基础门禁：

```powershell
docker compose -f fx-trading-platform/infra/docker-compose.yml up -d
cd fx-trading-platform
mvn -f backend/pom.xml test
node --test scripts/smoke-usdt-demo-browser.test.mjs
cmd.exe /d /s /c "npm.cmd --prefix apps/web test"
cmd.exe /d /s /c "npm.cmd --prefix apps/admin test"
cmd.exe /d /s /c "npm.cmd --prefix apps/web run build"
cmd.exe /d /s /c "npm.cmd --prefix apps/admin run build"
cmd.exe /d /s /c "npm.cmd run verify:architecture"
```

显式数据库/并发测试必须运行并解析 Surefire 结果，不能只看退出码：

```powershell
mvn -f backend/pom.xml "-Dtest=PostgresDatabaseIT,V46V47EmptyDatabaseIT,V45ToV47DemoResetIT,Task5PostgresFullFillIT,Task6PostgresSpotIT,Task7PostgresDemoLifecycleIT,Task8PostgresTradingSettingsIT,Task9PostgresPerpetualOrderIT,Task10PostgresProtectionIT,Task11PostgresFundingIT,DemoTradingConcurrencyIT,PerpetualPositionConcurrencyIT,ProtectionOrderConcurrencyIT,FundingLiquidationConcurrencyIT" test
```

若 Docker 不可用，或上述 IT 出现任何 skipped，整个用户层验收结论为 `BLOCKED`。

### 4.2 现有 canonical smoke

在详细用例前先串行执行：

```powershell
cd fx-trading-platform
npm run smoke:usdt-demo-browser
```

该 smoke 是广覆盖基础证据，不替代本文详细用户 UI 用例。现有脚本通过 REST 创建会话并向浏览器注入 user/Admin token，因此它是本节浏览器登录规则的明确例外，不能作为 `AUTH-*` 或 Admin 登录成功的证据。必须检查：

- `artifacts/smoke-usdt-demo-browser/${RUN_ID}/report.json` 的 `status=PASS`。
- 四种 source mode 都有证据，不是空数组。
- Web/Admin desktop/mobile 截图存在。
- Admin 截图前 `.state-block.loading` 和 `.state-block.error` 均不存在；funding 页面已有目标 symbol 行，account 页面已有 `Demo 高风险操作` 和账户数据区。
- 进程日志无未解释错误。
- 专用数据库已删除。

`scripts/smoke-usdt-demo-browser.test.mjs` 只是源码合同测试；`npm run smoke:usdt-demo-browser` 才是运行真实服务和浏览器的全栈 smoke，两者结果不得混用。

### 4.3 浏览器规则

1. 除 4.2 已声明的 canonical smoke 外，注册和登录必须真实走 `/register`、`/login`；不得直接注入 token，安全隔离专用用例除外。
2. Spot 路由使用 `/trade/spot/{symbol}`；Perpetual 使用 `/trade/perpetual/{symbol}-PERP`。
3. 产品切换优先通过顶部交易菜单；为保证单用例起点确定，可以直接打开目标路由。
4. desktop 只使用 `.trade-panel:visible`；mobile 先点击可见 `Trade` 按钮，再使用 `[aria-hidden="false"] .trade-panel`。关闭的 mobile drawer/sheet 仍挂在 DOM，禁止使用未限定可见/open layer 的全局 `.trade-panel`。
5. desktop terminal ready：当前可见 panel、可见 `[data-source]`、无 login submit、余额不再显示 `-`；mobile quote ready 必须先打开 Quote drawer，再在 `[aria-hidden="false"]` 内等待 `[data-source]`。
6. Order type、Buy/Sell 和确认流程都限定在当前 panel；order type 使用 `.trade-panel__order-tabs button` 并等待目标按钮 `aria-selected=true`，Buy/Sell 分别限定在 `.trade-panel__side--buy` 和 `.trade-panel__side--sell`。
7. 所有确认层必须真实确认；不得设置 `fx-trade-confirm-skip=true`，除非用例明确测试跳过确认偏好。
8. terminal 行没有稳定的 order/position id。精确追踪订单时进入 `/orders`，在目标 Actions cell 使用 `[data-order-id="${ORDER_ID}"]`；精确追踪当前仓位动作时进入 `/positions`，使用 `[data-position-id="${POSITION_ID}"]`。平仓后 action cell 会消失，history/ID/状态由 REST/DB 补证。
9. 单仓操作使用 `role=dialog` 且 `aria-label="Position action"`；batch action 使用浏览器原生 `window.confirm`，必须监听并接受原生 dialog，不能按 React dialog 定位。
10. mobile 的 account panel 是页面内联区域，不是 drawer；滚动到该区域并选择 tab。页面导航或响应式布局变化后重新定位，禁止长期持有 stale element handle。

### 4.4 行情控制能力门禁

在基线 `5f4cb80d226c8842625257f3dae97c61598bbb9d` 中，Admin test-control override 只覆盖 legacy quote cache/stream；交易、pending、protection、risk 和 liquidation 使用的权威行情由 `MarketBundleResolver` 直接从 binding provider 解析，`LOCAL_SIMULATED` 也直接调用 `DemoMarketDataGenerator`。因此下列 override 只能验证 UI quote/control endpoint，**不能**用于控制或推断 fill、mark、reference、pending/protection trigger 或 liquidation boundary。调用前仍须通过真实 Admin `/login` 取得 token，只保存在进程内 `ADMIN_TOKEN`：

```http
POST /api/admin/market/test-control/overrides
Authorization: Bearer ${ADMIN_TOKEN}
Content-Type: application/json

{
  "symbol": "BTCUSDT-PERP",
  "bid": 60000,
  "ask": 60010,
  "ttl": "PT5M"
}
```

清理：

```http
DELETE /api/admin/market/test-control/overrides/BTCUSDT-PERP
Authorization: Bearer ${ADMIN_TOKEN}
```

TTL 不超过 5 分钟；finally 必须删除。运行任何包含“控制行情”“移动 mark”“触发 pending/protection/liquidation”或按目标 bid/ask 精确计算 fill 的用例前，先运行 authority-bundle gate：

1. 创建一次性用户，通过真实 UI 建立最小 Spot/Perp 探针状态，并记录 override 前的 UI quote、Trade fill、Position mark 和 source/provider。
2. 将 override bid/ask 设置为与当前 provider 至少相差 20% 的值，等待 UI quote 确认变化。
3. 再通过 UI 提交最小 Spot/Perp MARKET 探针，比较实际 Trade fill、Position mark 与 override 所推导的价格；同时记录 source/provider。
4. 删除 override，关闭探针状态并保存全部 evidence。

只有实际 execution/risk bundle 与 override 同步时，才可把该机制登记为 `authorityBundleFixture=PASS`。当前基线预期该门禁失败；失败后所有依赖可控权威行情的确定性用例必须记为 `BLOCKED: AUTHORITY_BUNDLE_FIXTURE_MISSING`。不依赖目标价的真实 UI lifecycle、validation、cancel、isolation、history 和 source 用例仍可执行。若后续分支新增 bundle-aware fixture，必须先重跑本门禁，不得仅凭接口返回 2xx 解锁。

当前矩阵的明确归类如下，避免执行者自行猜测：

| 归类 | 用例 |
|---|---|
| authority gate 失败后整例/关键子运行 `BLOCKED` | `SPOT-05`–`SPOT-10`、`SPOT-11` 的恢复后单腿触发子运行、`PERP-01`/`PERP-02` 的目标 mark 子运行、`PERP-04`、`PERP-05`、`PERP-10`、`PERP-11`、`PROT-02`–`PROT-04`、`LIQ-01`–`LIQ-04`、`SOURCE-03` 的 trigger/liquidation 子运行、`RES-02`、`RES-03` 的 order-trigger/liquidation 子运行、`UI-01` 的 OCO/目标价子运行 |
| gate 失败后仍执行 | AUTH、CAT、`SPOT-01`–`SPOT-04`、`SPOT-11` 的 validation/stale 子运行、`PERP-01`/`PERP-02` 的开仓/30%/全平核心闭环、`PERP-03`、`PERP-06`–`PERP-09`、`PERP-12`、BATCH、`PROT-01`、`PROT-05`、`PROT-06`、FUND、WALLET、LIFE、`SOURCE-01`、`SOURCE-02`、`SOURCE-04`、`RES-01`、`RES-04`、`UI-02` |

同一顶层 case 若含 `BLOCKED` 子运行，顶层状态也为 `BLOCKED`，但已执行子运行仍逐条保留实际结果。

对不需要目标价的 MARKET 核心闭环，即使 gate 失败也必须执行：使用实际 Order/Trade fill 结算资金和 PnL，并用 Order 保存的 slippage 反推出 execution reference；不得因缺少 fixture 跳过用户的开仓、30% 部分平仓和全平动作。只有“把价格精确推到 T1/T2/trigger/boundary”的检查才被阻断。

Funding 和 liquidation 没有普通用户触发 API。相关夹具必须遵守：

1. 先通过 Admin UI 或 Admin funding-config API 读取并保存原配置。
2. 只修改专用数据库中的目标 symbol、目标测试 account/position 和目标 funding cycle。
3. Funding 优先通过生产 ingestion/scheduler 产生 canonical rate；若必须调整 `opened_at` 或清除竞争 cycle，只能使用明确、可回滚、带 before/after 证据的 SQL。
4. Liquidation 只通过行情、账户风险容量和 scheduler 触发，不直接把 position 改成 CLOSED。
5. finally 恢复 funding priority/rate/interval/freshness、provider bindings 和所有行情 override。

### 4.5 每个动作的六份快照

在 `before`、`submitted`、`filled/triggered`、`repriced`、`partially-closed`、`fully-closed` 中适用的节点记录：

1. UI：订单、持仓、历史、成交、资金费、钱包可见字段和截图。
2. Market：bid、ask、last、mark、index、provider、sourceMode、asOf、expiresAt、stale。
3. Order/Trade：id、clientOrderId、status、origin、side、type、quantity/unit、fill price、fee、feeAsset、liquidityRole、realizedPnl。
4. Position：id、slot、side、quantity、entry、mark、UPL、realizedPnl、margin、leverage、estimated liquidation、version。
5. Account/Wallet：Spot available/locked/total；Perp balance/equity/usedMargin/freeMargin。
6. Ledger/Event：operation type、amount、reference type/id、balanceAfter、STOMP event type/resource id。

### 4.6 等待与失败规则

- UI submit 后先捕获对应请求；无请求即 UI failure。
- `MARKET` 或 marketable `LIMIT` 等待 `FILLED`；非 marketable `LIMIT` 等待 active pending status。
- worker 用例最长等待 30 秒；liquidation 最长等待 120 秒。
- 超时后立即保存 UI、网络、console、REST、DB、worker log，不得继续猜测。
- 预期业务拒绝必须断言具体 error code，并验证六份快照中无不应有变化。
- 任何未预期 5xx、浏览器 console error、unhandled rejection 或无限 loading 都是 `FAIL`。
- 外部 Binance/OKX 不可达是 source 用例的环境证据，不得伪装成 primary source 成功。

## 5. 财务 oracle

### 5.1 固定 Demo 费率

```text
makerFeeRate = 0.0002
takerFeeRate = 0.0005
slippageRate = 0.0001

MARKET BUY fill  = ask × (1 + slippageRate)
MARKET SELL fill = bid × (1 - slippageRate)
```

提交时可成交的 LIMIT 是 taker；等待后成交的 GTC LIMIT 是 maker。STOP_MARKET、MARKET protection 和 liquidation 是 taker。

### 5.2 Spot

```text
effectiveStep      = storageCompatibleStep(symbolQuantityStep)
Spot BUY grossBase = floorToStep(quoteBudget / (fillPrice × (1 + feeRate)), effectiveStep)
Spot BUY quoteSpent = grossBase × fillPrice
Spot BUY quoteFee  = quoteSpent × feeRate
Spot BUY totalSpent = quoteSpent + quoteFee <= quoteBudget
Spot BUY creditedBase = grossBase
Spot averageCost   = cumulativeGrossQuoteCost / currentBase
Spot breakEvenPrice = (outstandingRawCost + recordedFeeCost) / currentBase

Spot SELL grossQuote = soldBase × fillPrice
Spot SELL quoteFee   = grossQuote × feeRate
Spot SELL netQuote   = grossQuote - quoteFee

Spot realizedPnl = grossQuote - soldBase × averageCost - quoteFee
```

Spot BUY 和 SELL 的 feeAsset 都必须是 USDT。BUY 全额入账 base，average cost 保持原始成交成本，USDT fee 通过 feeCost 进入 break-even；SELL fee 已包含在 Spot position 的 realizedPnl 中。当前 P0 的 Spot Trade 不是 realized PnL 真值，必须用 Trade price/fee 重算并与 `spot_positions.realized_pnl` 比较。任意钱包都满足：

```text
total = available + locked
available >= 0
locked >= 0
```

### 5.3 Linear Perpetual

```text
entryNotional       = abs(quantity) × entryPrice
markNotional        = abs(quantity) × markPrice
initialMargin       = entryNotional / leverage
maintenanceMargin   = markNotional × maintenanceMarginRate

longUPL  = (markPrice - entryPrice) × quantity
shortUPL = (entryPrice - markPrice) × quantity
ROI      = UPL / positionMargin × 100%

longRealized  = (closeFillPrice - entryPrice) × closedQuantity
shortRealized = (entryPrice - closeFillPrice) × closedQuantity
```

P0 的仓位和 Trade `realizedPnl` 是价差毛盈亏；手续费独立记账。因此：

```text
position/trade realizedPnl = gross realized PnL
cash delta = realizedPnl - openFee - closeFee + fundingCashflow
```

“预估利润”必须同时报告：

```text
projectedGrossPnl = directionalPriceDifference × quantity
projectedCloseFee = projectedCloseNotional × expectedCloseFeeRate
projectedNetFromNow = projectedGrossPnl - projectedCloseFee
```

预估 close fill 必须由目标时刻的 closing bid/ask、slippage 和订单 liquidity role 推导；MARKET/STOP/市价全平使用 taker fee，等待后成交的 LIMIT 使用 maker fee。开仓手续费已经实际扣除，不得在 `projectedNetFromNow` 中再次扣除；若报告“整笔交易预计净收益”，才再扣 opening fee。

### 5.4 部分平仓

以平仓 30% 为例：

```text
closedQuantity    = originalQuantity × 30%
remainingQuantity = originalQuantity × 70%
releasedMargin    = oldMargin × 30%
remainingMargin   = oldMargin × 70%
```

验收点：

- 剩余仓位 entryPrice 不变。
- 只对 `closedQuantity` 计算 realized PnL 和 close fee。
- 当前 open position 的 realizedPnl 累加该次毛盈亏。
- Trade realizedPnl 只保存本次增量。
- 全平后历史 position realizedPnl 等于所有部分/最终平仓增量之和。
- 当前 position 列表为空；UPL 归零；margin 全部释放。

### 5.5 强平

```text
effectiveIsolatedMargin = marginHeld + fundingPnl
isolatedEquity = effectiveIsolatedMargin + UPL
isolatedThreshold = maintenanceMargin + estimatedCloseTakerFee

crossEquity = perpBalance - isolatedPrincipal + sum(crossUPL)
crossThreshold = sum(crossMaintenance) + sum(estimatedCloseTakerFees)
```

当 equity `<=` threshold 才应强平。liquidation fee 不参与触发阈值，在强平成交后另扣。

逐仓预计强平价：

```text
longEstimatedLiq
  ≈ (entryPrice - effectiveIsolatedMargin / quantity)
   / (1 - maintenanceMarginRate - closeTakerFeeRate)

shortEstimatedLiq
  ≈ (entryPrice + effectiveIsolatedMargin / quantity)
   / (1 + maintenanceMarginRate + closeTakerFeeRate)
```

Cross 预计强平价使用账户级反解；测试以 API 展示值为中心做上下边界探测，并最终以 equity/threshold 公式判定。

强平成交后的名义费用：

```text
liquidationFee = abs(filledQuantity) × abs(executionPrice) × liquidationFeeRate
```

`liquidationFeeRate` 读取当次 symbol/risk 配置并按后端金额精度舍入。实际收取受账户剩余 balance/capacity 限制；`BANKRUPTCY_SHORTFALL` 必须包含未覆盖 core debit 与未收取的 liquidation fee，不能只按价差亏损计算。

### 5.6 精度

- 输入先读取 symbol tick、quantity step、contract size 和 minimum quantity。
- 目标价格按 tick 对齐；BASE 数量按 storage-compatible step 向下对齐。
- `QUOTE/USDT_NOTIONAL` 先使用当次权威 mark 换算，再向下对齐为 BASE；`CONTRACTS` 必须为整数，`baseQuantity = contracts × contractSize × contractMultiplier`。
- UI 按其显示精度比较；REST/DB 使用 decimal 比较。
- 允许误差不超过目标字段最小显示单位的一半；不得使用固定百分比宽松误差掩盖计算错误。
- 30% 数量若不对齐 step，选择一个能被 10 整除且满足 minimum 的原始数量；BTCUSDT-PERP 首选 `0.010` BTC，并先由 symbol metadata 确认可用。

## 6. 统一用例记录格式

每个用例输出一个结构化记录：

```text
id:
status: PASS | FAIL | BLOCKED | INVALID_TEST
commit:
database:
user/account:
profile:
viewport:
startedAt/finishedAt:
preconditions:
userActions:
fixtureActions:
authorityBundleFixture:
contractProbes:
replayProbes:
checkpoints:
financialCalculation:
uiEvidence:
networkEvidence:
apiEvidence:
dbEvidence:
eventEvidence:
consoleErrors:
cleanup:
failureOrBlocker:
```

每个用例使用唯一 artifact 目录：

```text
artifacts/p0-user-trading/${RUN_ID}/${CASE_ID}/
  result.json
  before.png
  submitted.png
  final.png
  network.json
  api-snapshots.json
  db-snapshots.json
  events.json
  console.log
```

## 7. 覆盖索引

| 需求 | 用例 |
|---|---|
| 用户注册、登录、会话 | `AUTH-01`–`AUTH-03` |
| 产品和 Demo 隔离 | `CAT-01`–`CAT-03` |
| Spot MARKET/LIMIT/STOP/OCO | `SPOT-01`–`SPOT-11` |
| 50x/100x、盈亏、强平价、部分/全部平仓 | `PERP-01`、`PERP-02`、`LIQ-01`、`LIQ-02` |
| ONE_WAY/HEDGE | `PERP-05`–`PERP-08` |
| CROSS/ISOLATED、杠杆、数量单位 | `PERP-03`、`PERP-04`、`PERP-09` |
| Perp MARKET/LIMIT/STOP | `PERP-01`、`PERP-10`、`PERP-11` |
| TP/SL | `PROT-01`–`PROT-06` |
| cancel-all/close-all | `BATCH-01`、`BATCH-02` |
| Funding | `FUND-01`–`FUND-04` |
| Liquidation/shortfall | `LIQ-01`–`LIQ-04` |
| 划转、reset、Admin cleanup | `WALLET-01`–`LIFE-03` |
| 行情来源、stale、恢复 | `SOURCE-01`–`SOURCE-04` |
| 幂等、并发、重启 | `RES-01`–`RES-04` |
| 桌面/移动一致性 | `UI-01`、`UI-02` |

## 8. 身份、会话与产品用例

### AUTH-01 注册并初始化唯一 DEMO 账户

**Profile：** `UI_CORE`；desktop 1440×900；新邮箱。

**步骤：**

1. 打开 `/register`，选择邮箱注册，输入本次 run 唯一邮箱和确定合法的 8–128 位密码（例如 `Password123!`）；页面当前不展示密码规则，不能依赖不存在的 help text。
2. 点击注册并等待自动进入登录态页面；刷新浏览器一次，确认会话仍有效。
3. 打开 `/wallet`，再打开 `/trade/spot/BTCUSDT` 和 `/trade/perpetual/BTCUSDT-PERP`。
4. 记录 Spot wallet、Perp account summary 和账户 id。
5. 只读查询数据库中该 user 的 active DEMO account 数量。

**预期：**

- 只有一个 active DEMO account。
- Spot USDT `total=available=50000`、`locked=0`。
- Perp `balance=equity=freeMargin=50000`、`usedMargin=0`。
- position mode 为 `ONE_WAY`；BTCUSDT-PERP 设置为 `CROSS/10x` 和默认数量单位。
- 无订单、成交、open position、funding settlement。
- 刷新后不出现虚构 balance 或 mock position。

### AUTH-02 登出、登录和 redirect

**Profile：** `UI_CORE`；复用 AUTH-01 用户。

**步骤：**

1. 从用户菜单登出，直接打开 `/trade/perpetual/BTCUSDT-PERP`。
2. 尝试提交最小 MARKET BUY，确认出现登录提示且网络中没有 create-order 请求。
3. 点击提示中的登录入口，验证 `/login?redirect=...` 保留原交易路由。
4. 输入正确账号密码并登录，确认返回 BTCUSDT-PERP 页面。
5. 再次登出，用错误密码登录一次，再用正确密码登录。

**预期：**

- 游客可以查看公开行情，但不能发交易写请求。
- 错误密码有明确错误且不建立 session。
- 正确登录恢复同一 account、wallet 和历史，不重复创建 DEMO account。
- redirect 后 symbol、product route 正确。

### AUTH-03 两用户数据与事件隔离

**Profile：** `UI_CORE`；`USER_A`、`USER_B` 两个独立浏览器 context。

**步骤：**

1. 两个 context 分别真实登录，并订阅各自 `/user/queue/trading-events`。
2. USER_A 在 BTCUSDT Spot 完成一次 MARKET BUY；USER_B 保持静止。
3. USER_B 打开订单、成交、钱包和持仓页面。
4. 使用 USER_B token 尝试只读 USER_A account id；尝试订阅旧 `/topic/trading/accounts/{accountIdA}/events`。
5. USER_A 再撤销一个 pending order，观察两边事件。

**预期：**

- USER_B UI、REST 和事件中都看不到 USER_A 数据。
- 越权 REST 和 legacy/mutable account topic 被拒绝。
- USER_A 收到自己的 `TRADE_CREATED/BALANCE_UPDATED/ORDER_CANCELED`；USER_B 不收到。

### CAT-01 十个 P0 产品与路由

**Profile：** `UI_CORE`；登录用户。

**步骤：**

1. 从顶部交易菜单进入 Spot，打开 Markets drawer，逐一选择 `BTCUSDT`、`ETHUSDT`、`BNBUSDT`、`SOLUSDT`、`XRPUSDT`。
2. 从顶部交易菜单进入 USDT Perpetual，逐一选择对应的 `*-PERP`。
3. 每个路由记录 product、symbol、margin label、source、bid/ask；Perp 额外记录 mark/index/funding。
4. 直接访问一个非法 Spot 和非法 Perp symbol 路由，记录回退行为。

**预期：**

- 可交易目录恰好 5 Spot + 5 Linear Perp。
- Spot 显示 `CASH`，Perp 显示 `CROSS` 或 `ISOLATED`，不会混用 `BTCUSDT` 与 `BTCUSDT-PERP`。
- 非法路由不能构造可提交的非法 symbol 订单；页面回退到同 product 的合法默认/最近 symbol。

### CAT-02 十产品最小真实交易 sweep

**Profile：** `UI_CORE`；每个 symbol 使用新用户或每轮完成清理。

**步骤：**

1. 对 5 个 Spot symbol：按 quote budget 完成最小 MARKET BUY，再 MARKET SELL 全部可卖净 base。
2. 对 5 个 Perp symbol：保持 ONE_WAY/CROSS/10x，按 metadata 对齐的最小 BASE quantity MARKET 开仓，再从当前持仓操作整仓平仓。
3. 每个 symbol 核对一笔 opening Trade 和对应 closing Trade/Spot sell Trade。

**预期：**

- 十个产品全部真实可交易，使用自己的 tick、step、contract size 和 provider binding。
- 任意 Perp canonical symbol 在 Order/Trade/Position 中保留 `-PERP`。
- 每轮结束无 open position、active order、locked Spot 资产或异常 used margin。

### CAT-03 DEMO/LIVE 和产品类型 guard

**Profile：** `UI_CORE`；UI 可达性检查 + L7 guard probe。

**步骤：**

1. 确认普通 Web session 没有切换到 LIVE account 的入口，也没有 `INVERSE_PERP`、`OPTION`、`FOREX` 交易入口。
2. 对这些 product 构造路由，确认页面不会形成可提交的非法交易表单。
3. L7 夹具准备一个非 DEMO account 或非 P0 product，记录资金、订单、仓位和 ledger 基线；重放一份已捕获 order request，仅替换 account/product/symbol 非法字段。

**预期：**

- 普通用户 UI 无法选择 LIVE 或非 P0 product；L7 非 DEMO 写入精确返回 `DEMO_ACCOUNT_REQUIRED`。
- 非 P0 product 返回 `PRODUCT_NOT_ALLOWED`；非白名单 symbol 或 product/symbol 不匹配返回 `SYMBOL_NOT_ALLOWED`。
- 所有拒绝均为零 mutation。

## 9. Spot 用例

### SPOT-01 BTCUSDT MARKET 买入、卖出 30%、全部卖出

**Profile：** `UI_CORE`；新用户；权威 provider fresh；首笔买入 budget 建议 1000 USDT。

**步骤：**

1. 采集提交前权威 market evidence 和 `before`：USDT/BTC wallet、Spot position、订单、成交和 ledger；gate 失败时不使用 UI override 价格充当 execution reference。
2. 打开 `/trade/spot/BTCUSDT`，选择 MARKET，在 Buy 表单输入 1000 USDT，确认订单。
3. 等待订单 `FILLED`，采集成交价、gross base、USDT fee、credited base、average cost、break-even 和钱包。
4. 计算可卖 BTC 的 30%，按 quantity step 向下对齐；在 Sell MARKET 输入该数量并确认。
5. 核对部分卖出后的 BTC 剩余、USDT 增量；使用该 Trade 的 price/fee 独立重算，并与 Spot position 累计 realizedPnl 比较。
6. 卖出剩余全部可卖 BTC；若 UI 百分比按钮存在，使用 100%，否则输入精确 available。
7. 打开历史订单、成交、资产和 ledger，采集 `fully-sold` 快照。

**预期：**

- BUY 的 `fill=executionReference+slippage` 且 `slippage=executionReference×0.0001`；gate PASS 时 executionReference 必须等于 fixture ask。feeAsset=USDT，BTC available 增加完整 gross base，USDT 总扣款为 gross quote 加 fee 且不超过原始 quote budget。
- 30% SELL 的 `fill=executionReference-slippage` 且 `slippage=executionReference×0.0001`；gate PASS 时 executionReference 必须等于 fixture bid。feeAsset=USDT；Spot position realizedPnl=`gross proceeds-cost basis-sell fee`。
- 部分卖出不重置剩余平均成本。
- 全卖后 BTC available/locked 为 0 或只剩小于 min quantity 的舍入尘埃；若有尘埃必须精确解释来源。Spot position quantity 归零时 averageCost/UPL 归零，但累计 realizedPnl 和 feeCost 保留。
- 每个 fill 恰好一个 Order、Trade 和对应 ledger，不影响 Perp account summary。

### SPOT-02 输入规则、余额不足与零 mutation

**Profile：** `UI_CORE`；新用户。

**步骤：**

1. 依次尝试空值、0、负数、低于 minimum、未按 step 对齐、超过 50,000 USDT 的 MARKET BUY。
2. 在没有 BTC 时尝试 MARKET SELL。
3. 尝试 Spot `BASE` MARKET BUY 或 `QUOTE` MARKET SELL；若 UI 不提供非法组合，断言没有 mutation request，再使用 L7 修改首次合法 UI 请求的 quantity unit，验证后端拒绝但不计作用户正向操作。
4. 每次失败后重新采集 wallet/order/trade/ledger 计数。

**预期：**

- UI 可前置拦截的输入不发送网络请求。
- 空值、0、负值等 Bean Validation 路径精确返回 `VALIDATION_ERROR`；非法 unit 返回 `INVALID_QUANTITY_UNIT`；余额不足返回 `INSUFFICIENT_BALANCE`。
- USDT/BTC available、locked、total 及所有计数保持不变。

### SPOT-03 marketable LIMIT BUY/SELL

**Profile：** `UI_CORE`；新用户；固定行情。

**步骤：**

1. 设置 LIMIT BUY price 高于或等于 ask，输入对齐的 BASE quantity，确认。
2. 等待立即 `FILLED`，记录 fill price、fee 和 liquidity role。
3. 使用刚获得的 BTC，设置 LIMIT SELL price 低于或等于 bid，确认。
4. 等待立即 `FILLED` 并完成账务核对。

**预期：**

- BUY fill=`min(ask, limitPrice)`；SELL fill=`max(bid, limitPrice)`。
- 两笔都是 taker，feeRate=0.0005。
- 不出现可观察的长期 PENDING，也没有残留 hold。

### SPOT-04 non-marketable LIMIT 的 hold、撤单和释放

**Profile：** `ORDER_TRIGGER`；新用户；固定行情但不跨触发价。

**步骤：**

1. 提交低于 ask 的 LIMIT BUY；采集 submitted 快照。
2. 验证状态 active/PENDING、USDT available 减少、locked 增加、total 不变。
3. 进入 `/orders` 的 Current view，在目标 Actions cell 使用 `[data-order-id="${ORDER_ID}"]` 点击 `Cancel`；该动作直接发请求，不等待确认框。
4. 等待状态 CANCELED，核对 USDT hold 完整释放。
5. 先买入 BTC，再提交高于 bid 的 LIMIT SELL，重复 pending→cancel 流程并核对 BTC hold。

**预期：**

- pending BUY hold 按订单规则覆盖 quantity、limit price 和 worst fee。
- pending SELL locked 等于 base quantity。
- 取消产生取消事件但不产生 Trade、fee 或 realized PnL。
- available+locked=total 在每个节点成立。

### SPOT-05 LIMIT 改单后 maker 成交

**Profile：** `ORDER_TRIGGER`；新用户；需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. 创建 non-marketable LIMIT BUY 并记录 order id/version/原 hold。
2. 打开 `/orders`，选择 Current view，在目标行 Actions cell 点击 `Modify`，从 UI 修改 price 和 quantity，仍保持 non-marketable。
3. 验证 order id 语义、version 递增和 hold 按新参数调整；旧 hold 不残留。
4. 使用已验证的 authority-bundle fixture 将 ask 移到满足 `ask<=limitPrice`，等待 worker 成交。
5. 核对 Trade、wallet、OrderEvent 和 STOMP。

**预期：**

- 只有 Spot active LIMIT/STOP_MARKET 普通订单可按策略修改。
- 等待后成交是 maker，feeRate=0.0002。
- 恰好一次 full fill，`filledQuantity=quantity`、`remainingQuantity=0`。
- 不出现 `PARTIALLY_FILLED`。

### SPOT-06 STOP_MARKET BUY

**Profile：** `ORDER_TRIGGER`；新用户；需要 4.4 authority-bundle gate=PASS；初始 last 低于 trigger。

**步骤：**

1. 选择 STOP_MARKET Buy，输入 BASE quantity 和高于当前 last 的 trigger。
2. 提交后验证 PENDING 和 USDT hold。
3. 行情先移动到 trigger 下一个 tick，确认仍未成交。
4. 将 mid/last 移到 trigger 或更高，等待 `FILLED`。
5. 核对实际 fill 使用触发时 ask 加 slippage，不等于 triggerPrice。

**预期：**

- trigger 使用 `LAST_PRICE`。
- 触发前无 Trade；触发后恰好一笔 taker full fill。
- gross quote 与 BUY USDT fee 从同一 owner hold 扣除，未使用 hold 仅在两次扣款后释放一次。

### SPOT-07 STOP_MARKET SELL

**Profile：** `ORDER_TRIGGER`；先通过 UI 买入 BTC；需要 4.4 authority-bundle gate=PASS；初始 last 高于 trigger。

**步骤：**

1. 创建 STOP_MARKET Sell，trigger 低于当前 last。
2. 验证 base locked，价格保持在 trigger 上方时不成交。
3. 将 last 移到 trigger 或更低，等待成交。
4. 核对 sell fill、USDT fee、realized PnL 和 hold 释放。

**预期：**

- fill 使用触发时 bid 减 slippage。
- feeAsset=USDT；钱包和 Spot position 与实际 sold quantity 一致。

### SPOT-08 SELL OCO 的 LIMIT 腿胜出

**Profile：** `ORDER_TRIGGER`；通过 UI 持有足够 BTC；需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. 在 OCO Sell 输入相同 BASE quantity：limitPrice 高于 last，stopTrigger 低于 last。
2. 提交并验证两条 active leg 共享一个 contingencyGroupId 和一份 BTC hold。
3. 将行情上移至 limitPrice，等待 LIMIT leg `FILLED`。
4. 核对 STOP leg 原子 `CANCELED`、共享 hold 只释放一次。

**预期：**

- winning LIMIT 是等待后 maker。
- 两腿中恰好一笔 Trade；peer 不得短暂成交或产生 fee。
- BTC locked 最终归零，total 守恒。

### SPOT-09 SELL OCO 的 STOP 腿胜出

**Profile：** `ORDER_TRIGGER`；新用户并先买入 BTC；需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. 创建与 SPOT-08 同结构的 SELL OCO。
2. 将行情下移到 stopTrigger，等待 STOP_MARKET leg `FILLED`。
3. 核对 LIMIT leg `CANCELED`、STOP taker fill 和实际滑点。
4. 在 `/orders` 尝试对任一 OCO leg 单独修改。

**预期：**

- OCO peer 原子取消且共享 hold 只结算一次。
- 子腿独立修改不可用或返回 `OCO_ORDER_NOT_MODIFIABLE`。

### SPOT-10 BUY OCO 两种胜出路径

**Profile：** `ORDER_TRIGGER`；两个独立新用户/子运行；需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. BUY OCO 使用 `limitPrice<last<stopTrigger`，两腿 quantity 相同。
2. 子运行 A：下移行情到 limitPrice，验证 LIMIT BUY 胜出、STOP peer 取消。
3. 子运行 B：上移行情到 stopTrigger，验证 STOP BUY 胜出、LIMIT peer 取消。
4. 两个子运行分别核对 USDT shared hold、USDT fee、完整 BTC 入账和最终释放。

**预期：**

- OCO 不双重冻结 USDT；hold 取两腿风险较大的所需金额。
- LIMIT waiting fill 为 maker；STOP fill 为 taker。
- 每组恰好一笔 Trade。

### SPOT-11 OCO 关系错误、幂等和 stale

**Profile：** `ORDER_TRIGGER`；新用户；validation/stale 可独立执行，恢复后指定单腿触发需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. 尝试 SELL OCO 的 limit/last/stop 顺序错误，以及 BUY OCO 的反向错误。
2. 尝试 quantity 超出可用 USDT/BTC 的 OCO。
3. 在确认按钮上执行受控双击并断言浏览器只发送一次 mutation；首次动作成功后，使用 L8 重放捕获的相同 client/idempotency key 请求。
4. 创建合法 pending OCO 后禁用/故障化所有 executable provider，使权威 bundle stale/unavailable。
5. 等待至少两个 worker 周期，再恢复新鲜行情并触发一腿。

**预期：**

- 错误关系返回 `OCO_PRICE_RELATION_INVALID`，超余额返回明确余额错误，均零 mutation。
- 重放返回同一 group/order，不重复 hold 或创建第二组。
- stale 期间不成交、不取消 peer、不改变 wallet；恢复后只成交一腿。

## 10. Perpetual 核心用例

### PERP-01 BTCUSDT-PERP 50x CROSS 做多、平 30%、全部平仓

**Profile：** `UI_CORE`；新用户；desktop；目标 mark 子运行需要 4.4 authority-bundle gate=PASS；核心开仓/30%/全平不依赖该 gate。优先 BASE quantity=`0.010`，先确认满足 step/minimum 且 30% 可对齐。

**步骤：**

1. 打开 `/trade/perpetual/BTCUSDT-PERP`，通过真实设置控件选择 `ONE_WAY`、`CROSS`、`50x`、`BASE`。
2. 记录开仓前 Perp balance/equity/usedMargin/freeMargin、订单/成交计数和行情 bundle。
3. 选择 MARKET Buy，输入 `0.010` BTC，检查确认框中的 symbol、side、margin/leverage、quantity/unit 后确认。
4. 等待 `FILLED` 和一个 open position；记录 order/trade/position/account/ledger/event 六份快照。
5. 独立计算 opening fill、opening fee、entry notional、initial margin、maintenance margin、当前 UPL、ROI 和 estimated liquidation price。
6. UI 只核对当前表真实展示的 symbol、side、quantity、entry、mark/price、50x、CROSS、margin、UPL 和 estimated liquidation price；slot、position id、current realizedPnl=0 由 REST/DB 核对。检查 Demo 强平价免责声明可见。
7. 选一个高于 entry 的目标 mark `T1`，先计算 `projectedGrossPnl`、`projectedCloseFee`、`projectedNetFromNow`，再通过已验证的 authority-bundle fixture 将 mark 推到 T1，等待 UI/API 刷新并比较当前 UPL。
8. 打开该仓位的 Position action，选择 BASE、输入 `0.003` 并确认；从 outgoing request 捕获前端自动生成的唯一 clientOrderId。
9. 等待 closing Trade，采集 `partially-closed` 快照；记录真实 close fill 后重算该次 realized PnL 和 close fee。
10. 核对剩余 quantity=`0.007`、entry 不变、margin 约为原来的 70%、当前仓位 realizedPnl 为本次毛盈亏、Trade realizedPnl 为本次增量。
11. 将 mark 移到低于 entry 的 `T2`，在剩余仓位上计算并核对当前浮亏与目标价预估净收益。
12. 从同一仓位操作选择整仓平仓并确认；等待 current position 消失。
13. 打开历史持仓、历史订单、成交和资产/ledger，采集 `fully-closed` 快照。

**预期：**

- 开仓 BUY fill=`ask×1.0001`，taker fee=`fillNotional×0.0005`。
- opening position 只有一个 `ONE_WAY/BOTH` slot；initial margin=`entryNotional/50`。
- 开仓后 closed-position history 不新增记录；订单和 opening Trade 可见。
- UPL 始终用 mark，不使用 last 或 closing bid/ask。
- 30% 平仓的 SELL fill=`bid×0.9999`；只释放 30% margin，只实现 30% quantity 的 PnL。
- 部分平仓后 quantity、margin、notional、maintenance、UPL 与 70% 剩余仓一致，entry 不变。
- 部分平仓后同一 position 仍在 current 列表，不提前写一条伪 closed position；已平 30% 由 closing Order/Trade、realized 增量和 margin-release ledger 证明。
- 最终历史 position realizedPnl=`partial realized + final realized`，不含 fee。
- 最终全平后 current 中该 id 消失，history 中恰好一条该 position 的 CLOSED 记录；该记录保留 position id、entry/final-close 字段和累计 realizedPnl，原始/分段数量由关联 Orders/Trades 还原。
- Perp balance 变化=`全部 realizedPnl - openingFee - 两次 closeFee`；若期间有 funding，必须单列后再相加，本用例应无 funding。
- usedMargin 最终为 0，freeMargin=equity=balance，当前仓位为空；每次 fill 恰好一笔 Trade。

### PERP-02 BTCUSDT-PERP 100x ISOLATED 做空、平 30%、全部平仓

**Profile：** `UI_CORE`；新用户；目标 mark 子运行需要 4.4 authority-bundle gate=PASS；核心开仓/30%/全平仍执行。BASE quantity 与 PERP-01 相同。

**步骤：**

1. 设置 `ONE_WAY`、`ISOLATED`、`100x`、`BASE`。
2. MARKET Sell 开空并采集 opening 六份快照。
3. 核对 short UPL、isolated margin、maintenance、close fee 和 short estimated liquidation price。
4. 先设目标 mark 低于 entry，计算/核对浮盈和预估净利润。
5. 通过 Position action 用 BUY 平掉 30%，核对部分 realized PnL、fee、margin release 和剩余 70%。
6. 增加一笔 isolated margin，核对 estimated liquidation price 远离当前 mark；记录 margin ledger/event。
7. 将 mark 移到高于 entry，核对剩余空仓浮亏。
8. 整仓平仓并核对历史与账户净变化。

**预期：**

- opening SELL fill=`bid×0.9999`；closing BUY fill=`ask×1.0001`。
- initial margin=`entryNotional/100`，但实际 isolated margin 以持仓权威字段为准。
- short UPL=`(entry-mark)×quantity`。
- 增加 margin 不改变 quantity、entry、realizedPnl；short estimated liquidation price 向更高价格移动。
- 部分和最终 realized PnL 使用实际 BUY fill，手续费独立扣除。
- 30% 平仓阶段只新增 closing Order/Trade，不新增伪 CLOSED position；最终全平后才出现唯一历史 position。
- 全平后 isolated margin 归零并释放，其他账户/仓位不受影响。

### PERP-03 1x/10x/50x/100x 杠杆和持仓中调整

**Profile：** `UI_CORE`；新用户；权威 provider fresh。

**步骤：**

1. 空仓时依次设置 1x、10x、50x、100x，每次等待 settings save 完成并刷新页面确认持久化。
2. 尝试在 UI 输入 0x、101x 和高于 symbol max 的值，断言控件 clamp/阻止且没有非法 mutation；再用 L7 修改一次捕获的合法 settings request，验证后端错误。
3. 设为 50x，MARKET BUY 开一个安全小仓位，记录 quantity、entry、margin、used/free margin。
4. 持仓中提高到 100x，等待 position/account 刷新。
5. 持仓中降低到 10x，再降低到 1x；每次独立计算需要增加的 initial margin。
6. 另用大仓位构造 free margin 不足，再尝试降低杠杆。
7. 清理仓位。

**预期：**

- 有持仓时允许合法杠杆调整；position 与 symbol settings 同步。
- 提高杠杆释放 margin，降低杠杆增加 margin。
- quantity、entry、realizedPnl 不变；HEDGE 用例中两腿应共同使用新 leverage。
- UI 非法值被 clamp/阻止；L7 非法值返回 `LEVERAGE_OUT_OF_RANGE`；资金不足返回 `INSUFFICIENT_MARGIN`，均零 mutation。

### PERP-04 BASE、QUOTE、CONTRACTS 数量换算一致性

**Profile：** `UI_CORE`；三个独立新用户/子运行；需要 4.4 authority-bundle gate=PASS，以固定同一权威 mark 和 leverage。

**步骤：**

1. 从 symbol metadata 读取 contract size、quantity step、minimum。
2. 选择一个同时满足有效 storage-compatible step 和整数 contracts 约束的目标 base quantity `Q`。
3. 子运行 A：设置 `BASE` 并输入 Q。
4. 子运行 B：设置 `QUOTE/USDT_NOTIONAL`，输入与 Q 对应的目标 notional。
5. 子运行 C：设置 `CONTRACTS`，输入与 Q 对应的 contracts 数。
6. 三次都 MARKET BUY，比较 order original quantity/unit、baseQuantity、position quantity、notional 和 margin；随后全平。

**预期：**

- BASE 按有效 storage-compatible step 对齐；QUOTE 使用提交时权威 mark 换算并向下对齐；CONTRACTS 必须为整数且 `baseQuantity=contracts×contractSize×contractMultiplier`。三种输入转换后的 base quantity 在这些规则内一致。
- 原始 quantity 和 unit 被保留，不把 QUOTE 错标为 BASE。
- position、fee、initial margin 使用 canonical base quantity。
- 不可精确换算的输入按明确规则舍入或拒绝，不能静默产生超出用户输入的风险量。

### PERP-05 ONE_WAY 同向加仓与加权 entry

**Profile：** `UI_CORE`；新用户；ONE_WAY/CROSS/10x；需要 4.4 authority-bundle gate=PASS 以固定 P1/P2。

**步骤：**

1. 在行情 P1 MARKET BUY Q1，记录第一笔 position id、entry 和 quantity。
2. 将行情移动到明显不同的 P2，再 MARKET BUY Q2。
3. 等待 position 刷新，核对仍只有一个 BOTH slot 和同一 position id。
4. 计算 `(Q1×fill1 + Q2×fill2)/(Q1+Q2)` 并与新 entry 比较。
5. 核对新 margin、maintenance、UPL、opening fees 和 ledger。
6. 全平并核对总 realized PnL。

**预期：**

- quantity=Q1+Q2；entry 为数量加权平均。
- 同向加仓 realizedPnl 不增加。
- 两笔 opening Trade 各有 fee；不会创建第二个 ONE_WAY open position。

### PERP-06 ONE_WAY 反向减仓与等量全平

**Profile：** `UI_CORE`；新用户。

**步骤：**

1. MARKET BUY Q 开多。
2. MARKET SELL `0.3Q`，`reduceOnly=false`，核对系统先按减仓处理。
3. 核对剩余 `0.7Q`、entry 不变和部分 realized PnL。
4. MARKET SELL 精确 `0.7Q`，核对完全关闭旧仓。
5. 检查 history、Trade realized 增量和 fees。

**预期：**

- 反向量小于或等于仓位时只减仓，不新开 short。
- 等量反向后当前仓位为空；history 累计 realized 正确。

### PERP-07 ONE_WAY 超量反手和 reduce-only 边界

**Profile：** `UI_CORE`；为不同拒绝路径使用可重置子运行。

**步骤：**

1. MARKET BUY Q 开多，随后 `reduceOnly=false` MARKET SELL `1.5Q`。
2. 核对旧 long CLOSED，产生 realized PnL；新 short quantity=`0.5Q`，entry 等于本次反向 fill。
3. 清理后，在空仓上勾选 reduce-only 尝试 BUY。
4. 再开多 Q，勾选 reduce-only SELL `1.1Q`。
5. 首次反手由 UI 完成后，使用 L8 重放捕获的同一 clientOrderId 请求。

**预期：**

- 非 reduce-only 超量反向只平旧仓一次，并以余量新开相反方向。
- 空仓 reduce-only 返回 `REDUCE_ONLY_WOULD_INCREASE`。
- 超过可平量返回 `REDUCE_ONLY_EXCEEDS_POSITION`，不自动截断。
- 重放不重复平仓或再开仓。

### PERP-08 HEDGE LONG/SHORT 独立生命周期

**Profile：** `UI_CORE`；新用户；空账户切换 HEDGE。

**步骤：**

1. 从设置将 position mode 切为 HEDGE。
2. 选择 LONG slot，MARKET BUY QL；选择 SHORT slot，MARKET SELL QS。
3. 核对同 symbol 同时存在 LONG 和 SHORT 两个 position，entry/UPL/realized 独立。
4. 尝试切回 ONE_WAY，验证被阻止。
5. 对 LONG 使用 SELL + LONG 平 30%；确认 SHORT 完全不变。
6. 对 SHORT 使用 BUY + SHORT 全平；确认 LONG 仍存在。
7. 尝试对 LONG 超量平仓，验证拒绝且不能反手进入 SHORT。
8. 全平 LONG，确认可切回 ONE_WAY。

**预期：**

- HEDGE 不做 LONG/SHORT 净额合并。
- 两腿共享 symbol leverage/margin mode，但 margin、UPL、realized 和 protection 关联独立。
- 有 open position 或 active Perp order 时返回 `POSITION_MODE_SWITCH_BLOCKED`。
- HEDGE 超量关闭对应 slot 整单拒绝。

### PERP-09 CROSS/ISOLATED 切换与手动逐仓保证金

**Profile：** `UI_CORE`；新用户。

**步骤：**

1. 空仓时 CROSS→ISOLATED→CROSS，逐次刷新确认持久化和 version 递增。
2. 设为 ISOLATED 并开多，尝试切回 CROSS，验证阻止。
3. 在 Position action 选择 Adjust margin，ADD 一个合法金额。
4. 核对 account balance 和同一 mark 下 equity 不变、`usedMargin += amount`、`freeMargin -= amount`、`position.marginHeld += amount`；UPL/entry/quantity 不变，estimated liquidation 变远。ledger 表示 margin hold，不是现金扣款。
5. REDUCE 一个安全金额并核对逆向变化。
6. REDUCE 到会立即不安全的金额。
7. 清理后开 CROSS position，断言 UI 的 Adjust margin disabled 且不发 mutation；再用 L7 修改捕获请求验证 `INVALID_MARGIN_MODE`。
8. 首次合法 margin adjustment 由 UI 完成后，使用 L8 将捕获请求的 expectedVersion 改为过期版本，验证冲突。

**预期：**

- active order/open position 时 margin mode 切换返回 `MARGIN_MODE_SWITCH_BLOCKED`。
- 不安全减少返回 `MARGIN_REDUCTION_UNSAFE`；CROSS 调整返回 `INVALID_MARGIN_MODE`。
- stale version 返回 `POSITION_VERSION_CONFLICT`。
- ADD/REDUCE 只在 used/free margin 间重分类，不改变 balance；安全 REDUCE 与 ADD 严格逆向。拒绝均不改变 balance、margin、liq price 或 ledger。

### PERP-10 LIMIT 立即成交、挂起、触发、撤单和不可修改

**Profile：** `ORDER_TRIGGER`；新用户；trigger 子运行需要 4.4 authority-bundle gate=PASS；立即成交/pending/cancel 仍执行。

**步骤：**

1. 提交 marketable LIMIT BUY，验证立即 taker full fill；全平清理。
2. 提交 non-marketable LIMIT BUY，验证 PENDING 和 Perp order hold。
3. 从 `/orders` 尝试修改 price/quantity。
4. 取消订单，验证 hold 完整释放且无 Trade。
5. 再创建相同 pending LIMIT，将行情移动到 marketable，等待 worker 成交。
6. 核对 waiting LIMIT 的 maker fee、position 和 event。

**预期：**

- Perp 普通挂单修改返回 `ORDER_NOT_MODIFIABLE` 或 UI 禁用，原订单不变。
- cancel 无 fee/Trade/position；trigger fill 恰好一次且为 maker。
- opening hold 在 fill/cancel 后归零。

### PERP-11 STOP_MARKET 关闭多仓和空仓

**Profile：** `ORDER_TRIGGER`；两个独立子运行；需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. 子运行 A：开 LONG，提交 reduce-only STOP_MARKET SELL，trigger 低于当前 mark。
2. 从提交请求、Order 和数据库确认 triggerPriceType 为 `MARK_PRICE`；fixture 必须更新完整权威 Perp bundle，不得伪造可独立于 bid/ask/index/last 的 mark。
3. 将权威 Perp bundle 的 mark 移到 trigger，等待全平。
4. 子运行 B：开 SHORT，提交 reduce-only STOP_MARKET BUY，trigger 高于当前 mark并触发。
5. 比较 trigger、mark 和实际 MARKET fill。

**预期：**

- Perp STOP_MARKET 按权威 mark 触发，实际 fill 使用触发时 bid/ask 加滑点。
- 两个方向都只减对应仓，不反向开仓。
- closing Trade realized PnL、fee、history 和 margin release 正确。

### PERP-12 非法字段、保证金不足和 full-fill 合同

**Profile：** `UI_CORE`；新用户。

**步骤：**

1. 先确认 UI 不显示普通 `STOP`、stop-limit、Perp OCO 或不适用于当前 mode 的 positionSide；断言没有非法 mutation，再用 L7 请求探针验证后端纵深拒绝。
2. 通过 UI 尝试低于 minimum、未按 step、过大 notional、超过 free margin 的开仓；UI 前置阻止的分支断言无请求，需要后端证据时使用 L7。
3. UI 在 HEDGE 只暴露 LONG/SHORT、ONE_WAY 不暴露 positionSide；用 L7 在 ONE_WAY 传 LONG/SHORT slot、在 HEDGE 传 BOTH slot。
4. 若测试接口允许构造 partial execution result，仅在后端合同测试中执行，不通过用户 UI 伪造交易所回报。

**预期：**

- 普通 STOP 精确返回 `INVALID_PERPETUAL_ORDER_TYPE`；多余/非法字段返回 `INVALID_PERPETUAL_ORDER_FIELDS`；slot 不匹配返回 `INVALID_POSITION_SIDE`；保证金不足返回 `INSUFFICIENT_MARGIN`；Bean Validation 路径返回 `VALIDATION_ERROR`。
- P0 订单只允许一次 full fill；任何 partial fill 结果返回 `PARTIAL_FILL_NOT_SUPPORTED` 并回滚。
- 所有失败零资金、订单、成交和仓位 mutation。

## 11. 批量动作

### BATCH-01 cancel-all 混合活动订单

**Profile：** `ORDER_TRIGGER`；新用户。

**步骤：**

1. 通过 UI 创建两个 Spot pending LIMIT、一个 Spot OCO、两个 Perp pending LIMIT 和至少一个未触发 protection。
2. 记录每笔 active order、group、hold 和 version。
3. 在终端当前委托 tab 点击账户级“全部撤单”，监听并接受原生 `window.confirm`。
4. 捕获 batch POST response，从 `items[]` 核对 orderId/status/error；UI 只等待列表刷新或失败 alert，不要求渲染逐项结果。

**预期：**

- 返回逐项结果，不以一个模糊 success 代替各 order 状态。
- 普通订单 CANCELED；OCO 两腿作为完整 group 取消；可取消 protection 按合同处理。
- 所有 Spot/Perp holds 精确释放一次，无 Trade 或 realized PnL。
- 重复点击 cancel-all 为无副作用成功或明确空结果。

### BATCH-02 close-all 多仓及部分失败

**Profile：** `UI_CORE`；新用户；构造至少三个 Perp position，包含不同 symbol 和 margin mode。

**步骤：**

1. 通过 UI 在 BTC、ETH、SOL Perp 开仓，记录 position ids 和账户基线。
2. 点击当前持仓 tab 的账户级“全部平仓”，监听并接受原生 `window.confirm`。
3. 正常子运行从 batch POST response 的 `items[]` 验证全部关闭和逐项 orderId/status；UI 只核对列表刷新。
4. 部分失败子运行重新构造仓位，使一个 symbol 缺少新鲜 executable bundle，其他 symbol 保持新鲜。
5. 再次点击 close-all 并接受原生 dialog，从 response 记录逐项结果；恢复行情后重试失败项。

**预期：**

- 每个 position 独立事务和派生 idempotency key。
- 一个 symbol stale/失败不回滚其他已成功仓位。
- 成功项有标准 BATCH_CLOSE Order、Trade、fee、PnL、ledger；失败项保持 open 并给出 errorCode。
- 恢复后只关闭剩余仓位，不重复关闭或重复计费成功项。

## 12. TP/SL 与保护单

### PROT-01 开仓附带多档 TP/SL 的激活

**Profile：** `ORDER_TRIGGER`；新用户；BTCUSDT-PERP LONG；固定行情且初始不触发。

**步骤：**

1. 在 MARKET BUY 表单的保护编辑器添加两档 TAKE_PROFIT 和两档 STOP_LOSS。
2. 每档填写不同、方向合法的 trigger、protected quantity；至少一档 MARKET、一档 LIMIT execution。
3. 确认开仓，捕获 create-order payload 中的 `attachedProtections[]`。
4. 等待主订单 FILLED，再查看当前委托和仓位操作中的保护单。
5. 查询每档 parentOrderId、parentPositionId、position slot、status、version 和 protected quantity。

**预期：**

- 主单成交前保护单不能错误绑定到虚构 position；成交后全部绑定真实 position id 并 active。
- triggerPriceType=`MARK_PRICE`、reduceOnly=true、origin=`PROTECTIVE`。
- TAKE_PROFIT 总保护量不超过 position quantity；STOP_LOSS 独立计算同样的预算。
- 主单失败时所有 attached protection 一起失败/回滚，不留孤儿单。

### PROT-02 LONG 的 MARKET TAKE_PROFIT 与 STOP_LOSS

**Profile：** `ORDER_TRIGGER`；两个独立子运行；需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. 子运行 A 开 LONG，在 Position action 新建 MARKET TAKE_PROFIT，trigger 高于 entry，protected quantity 小于或等于仓位。
2. mark 保持 trigger 下方时确认不成交；移动到 trigger，等待保护单触发和平仓。
3. 子运行 B 开 LONG，新建 MARKET STOP_LOSS，trigger 低于 entry；向下移动 mark 触发。
4. 分别核对 protection order、派生 closing fill、仓位、history、realized PnL、fee、event。

**预期：**

- LONG TP 条件为 `mark>=trigger`；LONG SL 为 `mark<=trigger`。
- 实际成交价按 MARKET SELL 的 bid/slippage，不使用 trigger。
- protected quantity 只减仓，不会把 LONG 反手成 SHORT。
- 触发产生 `PROTECTION_TRIGGERED` 和 position update/closed 事件。

### PROT-03 SHORT 的 MARKET TAKE_PROFIT 与 STOP_LOSS

**Profile：** `ORDER_TRIGGER`；两个独立子运行；需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. 子运行 A 开 SHORT，创建 MARKET TAKE_PROFIT，trigger 低于 entry，向下移动 mark 触发。
2. 子运行 B 开 SHORT，创建 MARKET STOP_LOSS，trigger 高于 entry，向上移动 mark 触发。
3. 核对 BUY closing fill、realized PnL、fee、position 和历史。

**预期：**

- SHORT TP 条件为 `mark<=trigger`；SHORT SL 为 `mark>=trigger`。
- 实际成交按 MARKET BUY ask/slippage。
- LONG/SHORT 方向校验不可互换。

### PROT-04 LIMIT protection 的立即成交与二阶段 maker 成交

**Profile：** `ORDER_TRIGGER`；两个独立子运行；需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. 子运行 A：LONG 上创建 LIMIT TAKE_PROFIT，使触发时 limit 已 marketable；移动 mark 触发。
2. 核对保护单转为 LIMIT 后立即 taker full fill。
3. 子运行 B：创建 LIMIT protection，使 trigger 命中时 limit 尚不可成交。
4. 移动 mark 命中 trigger，验证订单变为 active GTC LIMIT，但仓位尚未减少。
5. 再移动 bid/ask 到 limit 可成交，等待 maker full fill。
6. 在触发后、fill 前取消该 resting protection，另做一个子运行验证可取消和 hold/quantity 状态。

**预期：**

- trigger 和 fill 是两个不同状态，不能在不可成交时伪造 FILLED。
- 立即 marketable protection LIMIT 为 taker；后续触价为 maker。
- 取消 resting protection 不改变 position 或 realized PnL。

### PROT-05 十档上限、同类型预算和部分平仓 resize

**Profile：** `ORDER_TRIGGER`；新用户；开一个数量足以按 step 切分的 LONG。

**步骤：**

1. 按创建时间顺序添加 5 个 TP 和 5 个 SL；每类 protected quantity 总和分别等于或小于 position quantity。
2. 记录每档 id、createdAt、type、quantity、status 和 version。
3. 添加到第 10 档后断言 Add 控件 disabled 且没有第 11 档 mutation；再用 L7 扩展一次捕获 payload 到 11 档，验证后端上限。
4. 尝试使 TP 总量超仓，再单独使 SL 总量超仓。
5. 从 Position action 手动平掉 30%，等待 protection resize 完成。
6. 比较 before/after，按 type 分组确认 oldest-first 保留、newest-first 缩减或 EXPIRED。
7. 再平掉一部分并重复检查，最后整仓平仓。

**预期：**

- 第 11 档返回 `PROTECTION_LIMIT_EXCEEDED`。
- 同类型超量返回 `PROTECTION_QUANTITY_EXCEEDED`；TP 和 SL 预算互不相加。
- resize 总量不超过剩余仓位，顺序完全确定；每个变化写 OrderEvent/`PROTECTION_RESIZED`。
- 仓位归零后所有 active protection CANCELED/EXPIRED，无后续反向成交。

### PROT-06 方向、修改、取消和版本冲突

**Profile：** `ORDER_TRIGGER`；新用户。

**步骤：**

1. 对 LONG 输入低于 entry 的 TP、高于 entry 的 SL；对 SHORT 输入相反错误方向。
2. 创建一条合法 protection，在 `/orders` 通过 UI 修改 trigger、quantity、execution type 和 LIMIT price。
3. 使用另一个 tab 先更新一次，再从旧 tab 提交 stale version。
4. 从 UI 取消 protection，再重复取消。
5. 整仓平仓后尝试对已过期 protection 修改。

**预期：**

- 方向错误被 UI 或后端 `PROTECTION_DIRECTION_INVALID` 拒绝且零 mutation。
- 合法修改 version 递增并保留 parent position/slot。
- stale update 返回 `PROTECTION_VERSION_CONFLICT`，不覆盖较新值。
- 重复取消和 closed-position 修改不会产生第二次状态变化或反向仓位。

## 13. Funding 用例

Funding 不是普通用户主动动作。用户只通过 Web 开仓并观察结算；Admin funding 配置、due cycle 和时间调整属于 L6 测试夹具，必须记录在 `fixtureActions`。

### FUND-01 正费率下 CROSS LONG 支付、SHORT 收款

**Profile：** `FUNDING_ONLY`；两个独立用户；固定 mark；固定正费率，例如 `+0.0001`。

**步骤：**

1. USER_LONG 通过 UI 开 CROSS LONG；USER_SHORT 通过 UI 开 CROSS SHORT。
2. 记录两个 account 和 position 的 funding 前快照。
3. 夹具创建一个在持仓 openedAt 之后到期的 canonical funding cycle，mark 和 rate 固定；确保两仓在 fundingTime 前已持有。
4. 等待 scheduler 各创建一条 settlement。
5. 用户在终端 Funding tab 核对实际可见的 symbol、rate、amount、asset、positionSide、source 和 time。
6. mode、settlement mark、position fundingPnl 由 REST/DB 核对；同时核对 account balance/equity/free margin、ledger 和 event。

**预期：**

- `fundingNotional=abs(q)×settlementMark`，`fundingAmount=fundingNotional×rate`。
- 正费率 LONG cashflow 为 `-fundingAmount`，SHORT 为 `+fundingAmount`。
- CROSS cashflow 进入 Perp balance；每个 position+fundingTime 恰好一条 settlement 和一条 ledger。
- UI 与 REST/DB 的实际 source 一致。

### FUND-02 负费率下 ISOLATED LONG 收款、SHORT 支付

**Profile：** `FUNDING_ONLY`；两个独立用户；固定负费率。

**步骤：**

1. 两个用户分别通过 UI 开 ISOLATED LONG/SHORT，记录 isolated margin 和 Perp balance。
2. 夹具创建 due negative funding cycle并等待结算。
3. 用户在 Funding tab 核对可见列和余额；margin mode、settlement mark、fundingPnl、isolatedMarginAfter 由 REST/DB 核对。
4. 核对 `marginHeld`、Perp balance、used/free margin 均不变；只更新 position fundingPnl 和派生展示的 isolatedMarginAfter。

**预期：**

- 负费率 LONG 收款、SHORT 支付。
- ISOLATED cashflow 进入对应 position 的 `fundingPnl`；`marginHeld` 不变，也不挪用其他 slot。
- `isolatedMarginAfter=max(marginHeld+fundingPnlAfter,0)`；正常结算的 `ledgerEntryId=null` 且不写 ledger，只有 shortfall 路径另有账户账务。Funding tab 只断言已实现的列，精确字段以 REST/DB 为准。

### FUND-03 Binance→OKX→FIXED 来源和 stale 判断

**Profile：** `FUNDING_ONLY`；新用户；外部网络可用性分别控制。

**步骤：**

1. 配置优先级 `BINANCE,OKX,FIXED`，在 Binance 新鲜可用时等待 rate ingestion，记录 provider/source/asOf/raw hash。
2. 使 Binance 不可用或 stale、OKX 新鲜，等待下一 canonical cycle，记录实际 source。
3. 使两个外部来源都不可用/stale，配置确定性的 FIXED rate/interval，等待下一 cycle。
4. 每个阶段通过 UI 保持一个合格仓位并观察 settlement。
5. 检查相同 symbol+fundingTime 只有一个 canonical rate。

**预期：**

- 只使用第一个 active 且 fresh 的来源。
- 配置 priority 使用枚举 `BINANCE/OKX/FIXED`；settlement/provider code 精确保存为 `binance-usdm`、`okx-swap`、`fixed`。外部来源 `sourceMode=PUBLIC_EXTERNAL`，fixed 为 `LOCAL_SIMULATED`；fallback 不能误标成 Binance。
- stale 边界按配置 freshness threshold 判断；raw payload hash/asOf 证据完整。
- 外部网络不可达时，PUBLIC-primary 子目标可为 `BLOCKED`，但 FIXED fallback 可以单独验收。

### FUND-04 幂等、重启补结算和结算/平仓竞争

**Profile：** `FUNDING_ONLY`；新用户。

**步骤：**

1. 开仓并准备 due cycle；等待首次 settlement，记录 settlement id、cashflow 和 balanceAfter。
2. 让 scheduler 多扫描至少三个周期但保持同 fundingTime，确认无第二次变化。
3. 准备下一 due cycle，在到期前停止 backend；跨过 fundingTime 后重启同一 DB。
4. 等待 catch-up settlement，并确认旧 cycle 未按新 rate 重算。
5. 用两个浏览器 tab 在 due 瞬间发起用户全平，记录 funding 与 close 的最终串行化结果。

**预期：**

- 唯一键为 positionId+fundingTime；重试返回已有结果或 no-op。
- 重启补结算一次且仅一次，保留原 cycle rate/source/mark。
- funding/close 竞争不出现负 balance、重复 settlement、丢失 realized PnL 或孤儿 position；报告实际获胜顺序。

## 14. Liquidation 用例

Liquidation 同样不是用户主动动作。用户通过 UI 开仓、挂单并观察；经 4.4 门禁证明有效的 authority-bundle fixture 和专用 shortfall fixture 才能作为 L6 条件。当前基线门禁失败时，以下需要确定性移动 mark 的用例必须报告 `BLOCKED`。

### LIQ-01 ISOLATED LONG 的预计强平价与实际边界

**Profile：** `LIQUIDATION_ONLY`；新用户；100x ISOLATED LONG；需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. 通过 UI 开安全 LONG，记录 entry、quantity、isolated margin、MMR、close fee rate 和 UI/API estimated liquidation price。
2. 独立按第 5.5 节计算 estimated liq；与 UI/API 比较。
3. 创建一个增加风险的 pending order，随后将 mark 移到展示 liq 上方一个 tick；等待至少两个 scan interval。
4. 验证未强平，并核对 `isolatedEquity>threshold`。
5. 将 mark 移到边界；观察 scanner 取消相关增险订单以消除后续成交/竞争风险，再用同一新鲜 mark 重新计算。
6. 明确验证单纯释放 order hold 不改变当前 isolated liquidation predicate；只有 mark、position 或 funding 状态同时变化时才可能恢复安全。继续下移到危险点。
7. 等待 position CLOSED，检查 LIQUIDATION system order、Trade、taker fee、liquidation fee、PnL、ledger 和通知。

**预期：**

- 展示价与同一风险模型的实际触发边界一致，误差不超过一个价格 tick/显示舍入。
- `equity>threshold` 不强平；`equity<=threshold` 才强平。
- 取消增险单后必须用新鲜 mark 重算；当前风险核不因单纯释放 hold 而恢复安全。
- 强平整仓 full fill，不产生用户反向仓。

### LIQ-02 ISOLATED SHORT 的预计强平价与实际边界

**Profile：** `LIQUIDATION_ONLY`；新用户；100x ISOLATED SHORT；需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. 按 LIQ-01 采集 short 风险字段并独立计算 short estimated liq。
2. mark 设置在展示 liq 下方一个 tick，确认安全。
3. 向上移动至危险边界，等待强平。
4. 核对 closing BUY fill、fees、realized PnL、history、ledger 和 notification。

**预期：**

- short estimated liq 方向和实际 trigger 正确；不能套用 long 公式。
- 只消耗该 isolated slot，不隐式挪用其他 isolated position 或 Cross pool。

### LIQ-03 CROSS 多 symbol 账户级强平

**Profile：** `LIQUIDATION_ONLY`；新用户；需要 4.4 authority-bundle gate=PASS；至少 BTC、SOL、XRP 三个 CROSS position，并有不同 symbol 活动委托/保护 LIMIT。

**步骤：**

1. 通过 UI 建立多仓和 active orders，记录所有 position/order/hold。
2. 控制多个 mark 使 `crossEquity` 逐渐接近但仍大于 cross threshold，确认不强平。
3. 再移动到 `crossEquity<=threshold`。
4. 观察系统取消该 Perp account 全部增险活动委托并释放 holds，再重新计算；断言 hold 释放本身不改变 cross equity/threshold predicate。
5. 若仍危险，等待所有 Cross positions 处理完成。
6. 用户查看 current/history、orders/trades/ledger；强平 notification 用认证 STOMP/L5 断言。Admin 打开 `/accounts/${ACCOUNT_ID}` 并重新加载，等待 `.state-block.loading/.error` 消失，只在持仓区按 symbol 核对可见状态；每仓的 origin、Order/Trade IDs 和唯一性由 REST/DB 核对。

**预期：**

- Cross 使用账户级 equity，不按单仓 displayed liq 独立误触发。
- 仍危险时一次处理所有 Cross position；每仓一个独立 LIQUIDATION Order 和 Trade。
- 所有 active Cross opening/protective LIMIT 正确取消。
- 全部成功后账户恢复 ACTIVE；任何单仓失败进入 `LIQUIDATION_PENDING` 且禁止新增加风险订单。

### LIQ-04 穿仓、余额下限和重试唯一性

**Profile：** `LIQUIDATION_ONLY`；需要 4.4 authority-bundle gate=PASS；专用数据库；记录所有 fixture SQL；不得影响其他用户。

**步骤：**

1. 通过 UI 建立高杠杆 Cross 多仓；夹具只在必要时调整该测试账户的可用风险容量，使极端跳价后 loss+fees 超出可用资金。
2. 将 mark 极端不利跳变，等待强平结算。
3. 按第 5.5 节用实际 execution price、filled quantity 和配置 rate 计算名义 liquidation fee；记录实际可扣 core debit、trade fee、liquidation fee、两类未覆盖额和最终 balance。
4. 让 liquidation/settlement worker 重试至少三次并重启一次 backend。
5. 核对其他测试用户资金和仓位完全不变。

**预期：**

- Perp balance 最低为 0，永不为负。
- 未覆盖差额写一条账户级 `BANKRUPTCY_SHORTFALL` ledger/audit；金额等于未覆盖 core debit 与未收取 liquidation fee 之和，并符合后端舍入。
- 每个 position/order charge pair 只结算一次，重试不重复 shortfall。
- 不创建真实 insurance fund 或 ADL，也不影响其他用户。

## 15. 钱包与账户生命周期

### WALLET-01 Spot↔Perp 双向 USDT 划转守恒

**Profile：** `UI_CORE`；新用户；无 active order/position。

**步骤：**

1. 打开 `/wallet`，记录 Spot USDT、Perp balance/equity/free margin 和 combined USDT。
2. 打开 `Transfer Demo USDT` dialog，选择 `SPOT_TO_PERP`，输入 1000 USDT 并确认。
3. 等待 dialog 关闭和页面 notice，记录两侧余额和 Wallet ledger；`/wallet` 本身没有 transfer history。
4. 再选择 `PERP_TO_SPOT`，转回 400 USDT。
5. 在终端 Transfers tab 核对方向/金额/时间，在 Wallet 核对余额/notice/ledger；transferId、requestId 和 paired ledger 由 REST/DB 比较。

**预期：**

- 第一笔 Spot available -1000、Perp balance/free/equity +1000；第二笔反向 400。
- combined Spot available+Perp balance 在无 hold/position 时保持 100,000。
- 无 transfer fee；每个 transferId 有两侧方向相反、金额相等的 ledger，且同事务提交。
- Transfers tab 不重复、不丢失方向；transferId/requestId 在 REST/DB 中唯一可追踪。

### WALLET-02 locked/used margin 限制、幂等和冲突

**Profile：** `ORDER_TRIGGER`；新用户。

**步骤：**

1. 创建 Spot pending LIMIT BUY 锁定部分 USDT；从 Wallet 尝试转出大于 Spot available、但小于 total 的金额。
2. 取消订单后重复合法转账。
3. 开 Perp position并创建 Perp pending opening order，尝试转出会侵占 used margin/order hold 或使风险不足的金额。
4. 平仓/撤单后重复合法转账。
5. 对一次合法 transfer 在 UI confirm 上做受控双击，断言只发送一次 mutation；首次成功后用 L8 重放捕获的同一 requestId/payload。
6. 使用 L8 复用同一 requestId 但修改 amount/direction，提交 fingerprint 冲突子运行。

**预期：**

- 只能转出 available/free 且风险安全的 USDT；精确错误为 `TRANSFER_AMOUNT_UNAVAILABLE`。
- hold/margin 不会被转账绕过；失败没有单边 ledger。
- 同 requestId 同 fingerprint 只产生一次余额变化和一组 paired ledger。
- 同 requestId 不同 fingerprint 返回 `TRANSFER_REQUEST_CONFLICT`，零新增变化。

### LIFE-01 active state 阻止用户 DEMO reset

**Profile：** `ORDER_TRIGGER`；每种阻止条件独立子运行。

**步骤：**

1. 子运行 A 创建普通 pending order；从 `/wallet` 打开 Reset Demo account 并确认。
2. 子运行 B 创建 OCO；尝试 reset。
3. 子运行 C 创建 Perp position 和 protection；尝试 reset。
4. 每次记录 reset 前后资金、订单、仓位、历史、settings 和 ledger。

**预期：**

- 每种 active state 返回 `DEMO_RESET_BLOCKED`。
- reset 不会暗中 cancel、close 或清除历史；所有资金和状态零 mutation。
- UI 显示明确错误，不伪装 success notice。

### LIFE-02 清洁状态 reset 与历史保留

**Profile：** `UI_CORE`；先完成一些 Spot/Perp 交易、划转并清理全部 active 状态。

**步骤：**

1. 记录 reset 前历史 order/trade/transfer/ledger ids 和 count。
2. 修改 position mode 和 symbol settings，再确保无 active order/open position。
3. 从 `/wallet` 提交用户 reset；等待完成事件和页面刷新。
4. 检查 Spot 所有 wallet、Perp account、position mode、五个 Perp settings 和历史页面。
5. 首次 reset 真实经 UI 完成后，使用 L8 重放捕获的同一 reset requestId/payload。

**预期：**

- Spot USDT 恢复 50,000；非 USDT Spot asset/current cost position 清零。
- Perp balance/equity/free=50,000、used=0；position mode=ONE_WAY；symbol settings 回到 CROSS/10x/default unit。
- 旧 Order、Trade、Funding、Transfer、Ledger 历史保留。
- 新增唯一 `DEMO_RESET` ledger/audit；相同 requestId 重试不重复写。

### LIFE-03 Admin force cleanup 与 Admin reset 分离

**Profile：** `ORDER_TRIGGER`；用户有 pending orders、protections 和多个 Perp positions；Admin 使用真实 Admin UI。

**步骤：**

1. 用户浏览器保持登录，并记录 current state。
2. Admin 真实登录后打开 `/accounts`，在 `账户 ID` 筛选框输入目标 id 并点击该 id 链接；等待 `Demo 交易账户详情`，且 `.state-block.loading/.error` 均不存在。
3. 断言 blocker 文本可见、`重置 Demo 账户` disabled 且点击不会发 reset 请求；这证明 UI guard，不把 disabled 控件冒充后端拒绝证据。
4. 点击 `强制清理`，从 `[data-testid="high-risk-request-id"]` 记录 requestId；输入非空 Reason，点击 `Continue to confirmation`，在确认阶段受控双击 `Confirm Force cleanup` 并断言只发一个 POST。
5. 捕获 cleanup POST response，以 `items[]` 核对每个 position/order 的 id、status、errorCode/message。dialog 只断言 `Operation accepted` 和 `[data-testid="high-risk-audit-id"]`；actor、reason、requestId、auditId 用只读 audit REST/DB 核对。
6. 用户观察订单撤销、仓位关闭、资金和 history 实时刷新；Admin 使用 L8 以相同 payload/requestId 重放一次，断言返回既有结果且没有新增 Order、Trade、Ledger 或 audit。
7. 点击 `Close`，等待“当前未发现清理阻塞项。”且 `重置 Demo 账户` enabled；点击 reset，填写独立 Reason，依次点击 `Continue to confirmation`、`Confirm Reset demo account`，等待新的 `Operation accepted` 和不同 auditId。
8. 用户验证 50,000/50,000 初始状态和历史保留；最后用普通 user token 调用 `/api/admin/**` 负向探针，验证权限拒绝且零 mutation。

**预期：**

- force cleanup 与 reset 是两个独立动作和 audit，不得一个按钮隐式完成两者。
- cleanup 使用 authority market price，产生标准 ADMIN_FORCE_CLOSE Order/Trade/Fee/PnL/Ledger。
- Admin UI 当前只展示 requestId/auditId，不展示 batch `items[]` 或完整 audit details；这些字段由捕获 response 和只读 REST/DB 证明，不能伪称 UI 可见。
- pending 防双击；L8 相同 requestId 不重复 cleanup。
- `/api/admin/**` 全程要求 Admin 权限，普通用户调用被拒绝。

## 16. 行情来源与 stale 行为

### SOURCE-01 Binance PUBLIC primary

**Profile：** P0 workers 按被测订单需要开启；外部网络允许；provider binding 恢复默认。

本节四个 source 用例都不得保留 Admin test-control override；虽然当前基线的 override 不进入 execution bundle，它会污染 UI quote/source evidence，导致页面与权威 provider 证据不一致。

**步骤：**

1. 验证 Binance Spot 和 Binance USD-M binding enabled 且 priority 最高。
2. 打开 BTCUSDT 和 BTCUSDT-PERP，记录整套 bundle 的 provider/source/asOf/expiry。
3. 分别通过 UI 完成 Spot MARKET buy/sell、Perp MARKET open/close。
4. 核对 Order/Trade 使用的 provider/source 与成交前权威 bundle 相同。

**预期：**

- 实际 source 为 `PUBLIC_EXTERNAL`，provider 为对应 Binance adapter。
- Spot quote/orderbook/trades/candles 与 Perp bid/ask/last/mark/index 各自不跨 venue 混合。
- 若外部网络、限流或 venue 不可用，本用例为 `BLOCKED`；不得把 fallback 结果标为 Binance primary PASS。

### SOURCE-02 Binance 失败后 OKX failover

**Profile：** 与 SOURCE-01 相同；fixture 禁用/故障化 Binance binding，保留 OKX。

**步骤：**

1. 记录禁用前 provider 配置并在 finally 恢复。
2. 打开 Spot/Perp 页面，等待 source 切到 OKX 且 bundle fresh。
3. 通过 UI执行 Spot MARKET、pending LIMIT cancel、Perp MARKET、STOP_MARKET close。
4. 核对每笔 Trade provider/source 和 UI badge。

**预期：**

- higher-priority Binance 有不可用证据，实际执行来自 OKX。
- failover 是整 bundle 切换，不能将 Binance bid/ask 与 OKX mark 混合。
- 交易、fee、PnL 仍遵守同一 Demo full-fill 合同。

### SOURCE-03 LOCAL_SIMULATED 离线完整交易

**Profile：** 外部 provider 禁用或网络隔离，只保留 local-spot/local-perp；按交易、触发、funding、liquidation 分成四个独立子运行，分别使用对应 worker profile。trigger/liquidation 子运行需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. 确认可见 badge 为 `LOCAL` 或实际 local provider code，元素 `data-source="LOCAL_SIMULATED"`，REST sourceMode 同为 `LOCAL_SIMULATED`，不是伪装 Binance/OKX。
2. 通过 UI 完成 Spot MARKET/LIMIT trigger/STOP/OCO。
3. 完成 Perp MARKET/LIMIT/STOP、partial close、TP/SL。
4. 完成一笔 funding 和一次 isolated liquidation 的用户观测闭环。

**预期：**

- 无外部网络时仍能完成 P0 Demo 交易、触发、funding 和 liquidation。
- local bundle 的 bid/ask/last/index/mark 来自同一 generation；所有 Trade 保存 `LOCAL_SIMULATED` 和 local provider。

### SOURCE-04 source recovery、跳价和用户提示

**Profile：** 从 OKX 或 LOCAL_SIMULATED 开始，再恢复更高优先级来源。

**步骤：**

1. 在 fallback source 下打开 Web 并创建一个安全仓位和 pending order。
2. 恢复 Binance，允许新 source 价格发生明显跳变。
3. 观察 `[data-testid="market-source-change-notice"]`、source badge、quote/reference 和图表。
4. 核对 pending/position 风险只在完整新鲜 bundle 到达后重新计算。
5. 截取 desktop 和 mobile 的可见提示。

**预期：**

- `[data-testid="market-source-change-notice"][role="status"]` 显示可读的 source transition 文案/data attributes；`MARKET_SOURCE_CHANGED` 事件名只在 L5 stream evidence 中断言，不要求页面渲染枚举字面量。
- 价格可以跳变，但不得生成虚构中间成交或跨源 bundle。
- stale/不完整恢复快照不能触发 pending、protection、funding 或 liquidation。

## 17. 幂等、并发与恢复

### RES-01 用户双击与请求重放

**Profile：** 按动作选择；每种动作独立子运行。

**步骤：**

1. 对 MARKET order confirm、pending cancel、partial close、full close、transfer、reset 分别执行快速双击。
2. 每个首次 mutation 都必须由真实 UI 完成；随后使用 L8 对捕获的同一 clientOrderId/requestId 请求做一次网络重试，参数完全相同。
3. 再使用 L8 以同 key、不同 fingerprint 做冲突请求；两类结果写入 `replayProbes`，不重复记入 `userActions`。

**预期：**

- 相同 fingerprint 只产生一个业务结果、一次 fee/ledger/position mutation。
- 不同 fingerprint 明确冲突，不复用旧结果完成新动作。
- UI pending 状态阻止可避免的重复点击；即使前端防护失效，后端仍保持幂等。

### RES-02 fill/cancel、close/protection、close/liquidation 竞争

**Profile：** `ORDER_TRIGGER` 或 `LIQUIDATION_ONLY`；两个浏览器 tab；专用用户；需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. pending LIMIT 到达触发价的同时，在另一 tab 点击取消。
2. protection 到达 trigger 的同时，手动部分/全部平仓。
3. mark 到达 liquidation boundary 的同时，用户点击全平。
4. 每种竞争重复多轮，记录数据库提交顺序和最终状态。

**预期：**

- 允许不同合法胜出顺序，但每轮最终只能是一个可解释状态。
- 订单最多一笔 Trade；hold 释放一次；position quantity 不负、不反向；fee/realized/ledger 不重复。
- loser 返回已取消、不可取消、position not found 或幂等结果，不能返回假 success。

### RES-03 backend 重启后的 pending、状态和补处理

**Profile：** 分别运行 `ORDER_TRIGGER`、`FUNDING_ONLY`、`LIQUIDATION_ONLY` 子运行；order-trigger/liquidation 子运行需要 4.4 authority-bundle gate=PASS，funding 子运行不依赖。

**步骤：**

1. 在 ORDER_TRIGGER 子运行建立 pending LIMIT/OCO/protection；在 FUNDING_ONLY 子运行建立 open position 和一条即将到期 funding cycle；在 LIQUIDATION_ONLY 子运行建立风险仓位。
2. 记录完整状态后正常停止 backend，不停止 PostgreSQL/Redis/Web。
3. 跨过触发/结算时间后，以同一 DB 和相同 profile 重启 backend。
4. 刷新 Web，观察恢复、catch-up 和最终状态。

**预期：**

- 持久化订单、仓位、wallet/account 和历史不丢失。
- due funding 补结算一次；pending/protection 在新鲜行情恢复后处理。
- 不使用重启前 stale snapshot；无重复 Trade、fee、settlement 或 notification。

### RES-04 API/行情超时和恢复

**Profile：** `UI_CORE`；fixture 临时停止 backend 或让所有 provider unavailable。

**步骤：**

1. terminal ready 后中断 backend，尝试提交订单并切换 account tab。
2. 恢复 backend，使用页面 Retry 或重新加载。
3. 让行情 stale 但 API 存活，尝试 MARKET 和 pending trigger。
4. 恢复新鲜行情再执行正常订单。

**预期：**

- 网络失败有明确错误/重试状态，不能显示虚假 success 或本地生成可交易价格。
- 不确定提交必须通过 clientOrderId 查询最终结果，不能盲目重下。
- stale 返回 `MARKET_DATA_STALE/UNAVAILABLE`，零交易 mutation；恢复后正常处理一次。

## 18. 桌面、移动与数据可见性

### UI-01 desktop/mobile 关键交易一致性

**Profile：** `ORDER_TRIGGER`；同一用户分别使用 1440×900 和 390×844，但避免同时修改同一订单；OCO/目标价子运行需要 4.4 authority-bundle gate=PASS。

**步骤：**

1. desktop 完成 Spot MARKET、LIMIT pending/cancel、OCO 和 Perp 50x open/30% close/full close。
2. mobile 完成同样的最小闭环，并额外打开 symbol drawer、order sheet；account panel 是页面内联区域，滚动到该区域并选择 tab，再打开 Position action。
3. 两种 viewport 分别访问 current/history orders、positions、trades、funding、transfers、assets。
4. 每个关键状态截图并检查 viewport 溢出、遮挡、不可滚动或确认按钮不可达。

**预期：**

- mobile 不使用硬编码 Perpetual/Cross/100x/价格，和 desktop 消费相同真实 state。
- 用户可在 390×844 完成下单、取消、部分平仓和全平，不需要改 DOM 或直接调 API。
- 同一数据在两个 viewport 的数值和状态一致；只允许布局差异。

### UI-02 历史、筛选、实时刷新和可访问定位

**Profile：** `UI_CORE`/`ORDER_TRIGGER`；已产生丰富交易历史的用户。

**步骤：**

1. 在 terminal bottom tabs、`/orders`、`/positions`、`/wallet` 之间比较当前/历史记录。
2. 按页面真实能力使用 filters：`/orders` 为 symbol/status/from/to，`/positions` 为 symbol，Wallet ledger 为 type/wallet/currency/date，terminal 为 current-symbol checkbox；确认不会混入其他用户或错误 product。
3. 在页面保持打开时从另一 tab 完成成交/平仓，测量事件到 UI 可见更新时间；随后验证 polling fallback。
4. 用 role/label/keyboard 完成真实 dialog（确认、Position action、Transfer/Reset）的主要路径；mobile drawer/sheet 用当前 open-layer locator 单独测试。若 drawer 缺少 dialog semantics、accessible label 或 Escape 支持，记录为可访问性产品缺口/`FAIL`，不能把它当成已有能力。
5. 全程收集 console error、unhandled rejection、失败资源和重复网络请求。

**预期：**

- current 和 history 不重复/漏记录；Perp position history 累计 realized 正确。当前 Trade grid 不展示 realizedPnl，本次增量由 REST/DB 核对；Spot realized 以 Spot position 为准。
- 事件触发后在设计的 100–300ms 合并窗口后刷新；轮询不会造成请求风暴或旧响应覆盖新状态。
- 键盘焦点和 dialog 关闭可用；关键操作可用稳定 role/aria/data-id 定位。
- 无未解释 console/network runtime errors。

## 19. 推荐执行顺序

严格按以下顺序执行，任一基础门禁失败先停止并报告：

1. Git/Docker/JDK/Node/Maven preflight。
2. backend unit、Web/Admin test/build、contract、architecture。
3. 显式 PostgreSQL/并发 IT，并确认 skipped=0。
4. canonical `smoke:usdt-demo-browser`。
5. 运行 4.4 authority-bundle gate；失败时记录固定 blocker，并继续执行不依赖目标价控制的用例。
6. `UI_CORE`：AUTH、CAT、SPOT immediate、PERP core、WALLET、LIFE clean。
7. `ORDER_TRIGGER`：Spot pending/STOP/OCO、Perp LIMIT/STOP、PROT、BATCH cancel。
8. `FUNDING_ONLY`：FUND-01–04。
9. `LIQUIDATION_ONLY`：LIQ-01–04。
10. SOURCE-01–04。
11. RES-01–04。
12. UI-01–02。
13. 汇总报告和全局清理。

## 20. 全局不变量清单

每个 case 结束时都检查适用项：

- [ ] `wallet.total = available + locked`，且三者不为负。
- [ ] Spot 交易未改写 Perp balance/summary，除非有显式 transfer。
- [ ] Perp balance 不为负；equity/used/free 与持仓和 holds 可对账。
- [ ] 每次 canonical full fill 恰好一个 Order 对一个 Trade。
- [ ] 没有新 `PARTIALLY_FILLED` 记录。
- [ ] Order/Trade/Position/Ledger reference ids 可相互追踪。
- [ ] opening/closing fee 的 asset、rate、liquidity role 正确。
- [ ] Perp position realizedPnl 是累计毛盈亏；Perp Trade realizedPnl 是本次增量；Spot realized 使用 Spot position 口径并包含 sell fee。
- [ ] Perp current UPL 使用 mark；Perp realized 使用实际 fill。
- [ ] partial close 保留 entry，按比例释放 margin，不反向开仓。
- [ ] full close 后 open position 不存在、UPL=0、margin 释放。
- [ ] rejection 没有资金、hold、订单、成交、仓位或 ledger mutation。
- [ ] clientOrderId/requestId 重放不重复扣款或写记录。
- [ ] Trade 的 sourceMode/providerCode 与成交前权威 bundle 一致；Trade 不持久化 asOf/expiresAt/stale，这三项只在 UI 与 market REST/network evidence 之间比较。
- [ ] private events 只到达拥有该 account 的认证用户。
- [ ] Web/Admin 无未解释 console error、5xx 或永久 loading。

## 21. 结果汇总格式

最终报告至少给出：

| 字段 | 内容 |
|---|---|
| Branch/commit | 实际执行基线 |
| Environment | OS、JDK、Maven、Node、Docker、浏览器 |
| Database | 专用 DB 名和清理结果 |
| Gate results | 每条命令、tests/failures/errors/skipped、耗时 |
| Authority fixture gate | override 前后 UI quote、实际 Trade fill、Position mark、source/provider 和结论 |
| Case summary | PASS/FAIL/BLOCKED/INVALID_TEST 数量 |
| Financial mismatches | 公式、预期、实际、差值、scale |
| UI/runtime errors | console、network、截图 |
| Source evidence | Binance、OKX、LOCAL、recovery |
| Cleanup | overrides/config/进程/DB 恢复情况 |
| Verdict | PASS、FAIL 或 BLOCKED，附原因 |

判定优先级：

```text
存在任何有效 FAIL        => FAIL
无 FAIL 但有环境 BLOCKED => BLOCKED
所有必测项 PASS          => PASS
```

不得用“绝大多数通过”覆盖资金错误、越权、重复成交、负余额、错误强平或清理失败。

## 22. 当前已知证据状态

截至目标 commit，单元、静态合同和构建已有历史通过记录，但这不是本设计的执行结果。仓库已有的 canonical smoke artifacts 尚无一次成功的登录态全栈 PASS；已知运行在 Docker Compose 前置阶段失败，截图和 sourceEvidence 为空。

源码复核还确认：当前 Admin market override 不进入 `MarketBundleResolver`，所以确定性成交价、pending/protection trigger 和 liquidation boundary 缺少可用的 authority-bundle fixture。这是当前分支的 P0 测试能力阻断；相关用例即使 UI quote 已变化也必须记为 `BLOCKED`。此外，mobile drawer/sheet 当前缺少完整 dialog semantics、accessible label/Escape 行为，`UI-02` 应据实产出产品缺口，而不是绕过检查。

因此在本文用例真实执行、authority fixture 缺口被修复或明确接受阻断、并产出新证据前，P0 用户层交易稳定性结论仍为 `BLOCKED/未验证`，不能写成已通过。

## 23. 后续实施边界

本文是可直接由 Codex 按步骤执行的验收规格，不包含自动化 runner 的代码实现。若后续批准自动化，应优先扩展现有 `scripts/smoke-usdt-demo-browser.mjs` 的真实 UI 覆盖和金额级断言，避免新建第二套不一致的交易测试框架；自动化实施必须另行编写实施计划并评审。
