# 前端代码审计与优化方向

审计日期：2026-06-12  
审计范围：根目录 `src` KLineCharts 图表库、`fx-trading-platform/apps/web/src` 交易前端。  
审计方式：静态阅读、全局搜索、Superpowers 子 agent 前端审计、本地测试与构建验证。

## 结论

前端整体已经有明确拆分：根目录图表库按 `component/pane/view/widget/extension/common` 拆分，交易前端按 `app/pages/components/features/services/stores/design-system` 拆分。`/trading` 新终端已有较完整的交易会话、行情、下单、持仓、资金流水闭环，测试覆盖也比较多。

主要问题不是“完全没拆”，而是部分可复用业务代码仍放在 `pages/trading`，旧 `/trade` 页面和新 `/trading` 页面并存，交易表单有少量真实业务 bug，部分通用工具重复。中文注释没有全量覆盖；复杂交易规则更多依赖命名和测试表达意图。

## 已验证结果

- `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"`：通过。
- `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"`：210 个测试通过。
- `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"`：生产构建通过，`TradingPage` JS 约 383.15 kB，CSS 约 101.39 kB。
- `cmd.exe /d /s /c "pnpm.cmd type-check"`：根目录 TypeScript 类型检查通过。

## 重点发现

### 1. 无行情快照时下单品种可能回退为 `BTCUSDT`

严重级别：高  
影响：用户选择非 BTC 品种但盘口快照为空时，交易面板会用全局 mock market 初始化表单，最终 payload 的 `symbol` 可能来自 `BTC-USDT`。

证据：

- `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx:317`：`createPanelMarket(symbol, snapshot)` 根据当前 symbol 创建表单市场。
- `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx:325`：当 `lastPrice/bestBid/bestAsk` 都无效时直接 `return mockMarket`。
- `fx-trading-platform/apps/web/src/features/trading/hooks/useMockBalances.ts:3`：`mockMarket.symbol` 固定为 `BTC-USDT`。
- `fx-trading-platform/apps/web/src/features/trading/hooks/useTradeForm.ts:130`：表单初始化写入 `market.symbol`。
- `fx-trading-platform/apps/web/src/features/trading/services/orderAdapter.ts:12`：下单 payload 从 `form.symbol` 生成后端 `symbol`。

建议：保留 mock 价格可以，但 fallback market 必须使用当前 `symbol/baseAsset/quoteAsset`，只回退价格，不回退品种。

### 2. `offline-preview` 是保留分支，实际状态机不可达

严重级别：中  
影响：代码和测试语义容易分裂，后续维护者可能误以为有离线预览交易流。

证据：

- `fx-trading-platform/apps/web/src/features/trading-session/useTradingSession.ts:22`：`TradingSessionMode` 包含 `offline-preview`。
- `fx-trading-platform/apps/web/src/features/trading-session/useTradingSession.ts:31`：`getTradingSessionMode` 只返回 `error/login-required/ready/loading`。
- `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx:62`：仍有 `offline-preview` UI 分支。
- `fx-trading-platform/apps/web/src/pages/trading/TradingPage.tsx:466`：状态文案仍处理 `offline-preview`。

建议：二选一。若产品要求 guest watch-only，就删除或注释清楚 `offline-preview` 保留分支；若要离线预览，则在状态机中明确进入条件，且不得与真实下单混用。

### 3. `pages/trading` 承担了可复用市场模块职责

严重级别：中  
影响：`components/market-side-panel` 反向依赖 `pages/trading` 的 API、adapter、types，复用边界不够干净。

证据：

- `fx-trading-platform/apps/web/src/components/market-side-panel/quoteMarketDataAdapter.ts:2` 直接 import `../../pages/trading/tradingMarketApi`。
- `fx-trading-platform/apps/web/src/components/market-side-panel/quoteMarketDataAdapter.ts:3` 直接 import `../../pages/trading/tradingMarketAdapters`。
- `fx-trading-platform/apps/web/src/pages/trading/TradingPage.tsx:21` 再组合 `features/trading/components/TradePanel`。

建议：把 `tradingMarketApi/tradingMarketAdapters/tradingModels` 中与页面无关的部分迁到 `features/market` 或 `services/market`，`pages/trading` 只负责组装页面。

### 4. 旧 `/trade` 页面和新 `/trading` 页面并存

严重级别：中  
影响：旧 `TradePage/OrderPanel/KLineChartWrapper` 仍保留一套下单和图表逻辑，虽然路由已重定向，后续容易误改旧代码。

证据：

- `fx-trading-platform/apps/web/src/app/App.tsx:51`：`/trade` 重定向到 `/trading`。
- `fx-trading-platform/apps/web/src/pages/trade/TradePage.tsx:31`：旧交易页仍存在。
- `fx-trading-platform/apps/web/src/components/order-panel/OrderPanel.tsx:13`：旧下单面板仍存在。

建议：如果确认 `/trade` 不再恢复，删除旧页面和旧组件；如果保留作为轻量模式，则重命名为 `LegacyTradePage` 并加文档说明。

### 5. 交易工具函数重复

严重级别：中  
影响：同一业务概念有不同实现，未来品种解析和精度格式容易不一致。

证据：

- `fx-trading-platform/apps/web/src/features/trading/hooks/useTradeForm.ts:306` 和 `fx-trading-platform/apps/web/src/features/trading-session/tradingSessionModels.ts:52` 都实现 `parseSymbolAssets`。
- `fx-trading-platform/apps/web/src/features/trading/hooks/useTradeForm.ts:313` 和 `fx-trading-platform/apps/web/src/features/trading/services/orderAdapter.ts:45` 都实现 `formatDecimal`。

建议：只抽纯函数，不做大重构。新增 `features/trading/utils/symbols.ts` 与 `features/trading/utils/format.ts` 即可。

### 6. 限价默认价不会随同一 symbol 行情更新

严重级别：中  
影响：用户停留在同一品种时，表单初始价只在 symbol 改变时重置，盘口变化不会同步到未触碰的限价输入。

证据：

- `fx-trading-platform/apps/web/src/features/trading/hooks/useTradeForm.ts:66`：重置 effect 依赖 `market.symbol` 和 `side`。
- `fx-trading-platform/apps/web/src/features/trading/hooks/useTradeForm.ts:127`：初始价格来自 `bestAsk/bestBid`。
- `fx-trading-platform/apps/web/src/pages/trading/tradingModels.ts:196`：已有 `getNextPriceValue` 这类“用户正在编辑时不覆盖”的模型能力，但未接入表单。

建议：记录价格字段是否被用户触碰或聚焦，仅在未触碰时随行情更新默认价。

### 7. 行情订阅面偏宽

严重级别：中  
影响：当前 mock 品种少，问题不明显；真实市场列表增多后会产生 N 个首屏 quote 请求和 N 个 STOMP 订阅。

证据：

- `fx-trading-platform/apps/web/src/pages/trading/TradingPage.tsx:68`：对 `visibleMarkets` 调用 `useTradingQuoteMap`。
- `fx-trading-platform/apps/web/src/pages/trading/useTradingQuotes.ts:31`：对每个 market fetch quote。
- `fx-trading-platform/apps/web/src/pages/trading/useTradingQuotes.ts:34`：对每个 market subscribe quote。

建议：只实时订阅当前选中品种、收藏品种和首屏可见品种；其余使用分页/懒加载或低频轮询。

### 8. 根目录图表库核心类偏大

严重级别：低到中  
影响：基础库模块边界清楚，但 `Chart.ts`、`Store.ts`、`EventHandler.ts` 行数偏大，扩展阅读成本高。

证据：

- 本地统计：`src/Store.ts` 约 1623 行，`src/Chart.ts` 约 1240 行，`src/common/EventHandler.ts` 约 761 行。
- `src/common/EventHandler.ts:113` 自带 TODO：布尔 flag 过多，后续可能需要枚举状态。

建议：根目录图表库属于基础设施，不建议在业务优化中顺手重构。只有在新增图表交互时，按“新增行为附近最小拆分”的方式收敛状态。

## 解耦合与复用评价

- 合格：业务前端有 `features/trading-session` 管理真实 session，`services/*Api.ts` 管理后端请求，`features/trading` 管理下单表单。
- 不完全：市场数据 adapter 放在 `pages/trading`，通用盘口组件反向依赖页面目录。
- 冗余：旧 `/trade` 页面与旧 `OrderPanel` 已被路由替代但仍存在。
- 建议优先级：先修 symbol fallback bug，再清理旧页面，再抽市场 API/adapter，最后处理图表库大类。

## 命名与中文注释评价

- 目录和组件命名整体符合 React/Vite 项目习惯：`pages` 页面、`components` 通用组件、`features` 业务能力、`services` API。
- 中文注释不充分。全局统计显示 `fx-trading-platform/apps/web/src` 与根目录 `src` 的 337 个 TS/TSX 文件中，有 236 个没有中文字符。
- 不建议给所有文件机械补注释。建议只在这些位置加短中文注释：真实下单/离线预览分界、session 状态机、symbol fallback、行情订阅策略、资金/保证金展示换算。

## 前端优化路线

1. 修复 `createPanelMarket` fallback symbol 错误，并补一个单元测试：非 BTC symbol 且无盘口时，下单 payload 仍为当前 symbol。
2. 明确 `offline-preview` 产品语义：删除不可达分支，或让状态机显式支持。
3. 删除或隔离旧 `/trade` 代码，避免双交易入口。
4. 抽出 `features/market`：迁移 `tradingMarketApi/tradingMarketAdapters/tradingModels` 的可复用部分。
5. 合并 `parseSymbolAssets/formatDecimal` 等纯工具。
6. 给限价输入接入“未编辑时跟随盘口，编辑中不覆盖”的策略。
7. 对行情订阅做可见范围控制，减少真实品种数扩大后的 WebSocket 压力。
8. 生产构建上关注 `TradingPage` chunk，后续可把指标设置弹窗、绘图工具、移动端终端做进一步 lazy split。
