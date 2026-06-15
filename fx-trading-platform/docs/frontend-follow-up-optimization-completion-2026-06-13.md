# 前端后续优化完成报告

日期：2026-06-13

## 已完成

- `OrderBookSkeleton` 已移入 `components/market-side-panel`，`MarketSidePanel` 不再依赖 `pages/trading`。
- `TerminalSkeleton` 页面组件已收敛为账户表格 skeleton，盘口 skeleton 由 market-side-panel 自有。
- `ChartDrawingToolbar` 已改为 lazy split，并添加固定宽度 rail fallback，移动端隐藏 fallback。
- 已新增 `web:bundle-budget`，对 `TradingPage`、`MobileTradingTerminal`、`IndicatorSettingsModal`、`ChartDrawingToolbar` JS chunk 做存在性和大小校验。
- 已扩展 `verify:architecture`，把 market-side-panel 禁止反向依赖 `pages/trading` 固化为精确架构规则。

## 修改文件

- `apps/web/src/components/market-side-panel/MarketSidePanel.tsx`
- `apps/web/src/components/market-side-panel/OrderBook.test.ts`
- `apps/web/src/components/market-side-panel/OrderBookSkeleton.tsx`
- `apps/web/src/components/market-side-panel/OrderBookSkeleton.module.css`
- `apps/web/src/pages/trading/components/TerminalSkeleton.tsx`
- `apps/web/src/pages/trading/components/TerminalSkeleton.module.css`
- `apps/web/src/pages/trading/components/TerminalSkeleton.test.ts`
- `apps/web/src/pages/trading/components/ChartWorkspace.tsx`
- `apps/web/src/pages/trading/components/ChartWorkspace.module.css`
- `apps/web/src/pages/trading/components/ChartWorkspace.test.ts`
- `scripts/check-web-bundle-budget.mjs`
- `scripts/verify-architecture.mjs`
- `package.json`

## 命令验证

- `npm.cmd --prefix fx-trading-platform run web:test`：PASS，221 tests / 221 pass。
- `npm.cmd --prefix fx-trading-platform run verify:architecture`：PASS，输出 `Architecture verification passed.`。
- `npm.cmd --prefix fx-trading-platform run web:bundle-budget`：PASS，输出 `Bundle budget passed.`。
- `pnpm.cmd type-check`：PASS，`tsc --noEmit` exit 0。

## Bundle 预算结果

- `TradingPage JS`：364417 / 380000 bytes，`TradingPage-CR4phptJ.js`。
- `MobileTradingTerminal JS`：4356 / 20000 bytes，`MobileTradingTerminal-DPKTAi0D.js`。
- `IndicatorSettingsModal JS`：9233 / 20000 bytes，`IndicatorSettingsModal-DjAc2Ral.js`。
- `ChartDrawingToolbar JS`：7806 / 80000 bytes，`ChartDrawingToolbar-CbfzgkJ_.js`。

## Browser 验证

桌面 `/trading`：

- URL：`http://127.0.0.1:5173/trading`
- Title：`FX Trader`
- Viewport：1365 x 900
- Chart area：1074 x 758
- 指标设置弹窗：可打开，弹窗尺寸 688 x 710
- DOM snapshot：31834 chars，页面非空
- Framework overlay：未出现
- Console errors/warnings：0

移动 `/trading`：

- URL：`http://127.0.0.1:5173/trading`
- Title：`FX Trader`
- Viewport：390 x 844
- Mobile terminal：390 x 1061，可见
- Desktop workspace visible：false
- Loading fallback stuck：false
- Bottom action visible：true
- DOM snapshot：18521 chars，页面非空
- Framework overlay：未出现
- Console errors/warnings：0

## 下一步优化方向

1. 继续执行全栈计划中未纳入本轮的 `features/market` 数据 store/adapter 所有权迁移，把兼容 re-export 逐步收口到 feature 层。
2. 分阶段拆分 `TradingPage.tsx` 和 `TradePanel.tsx`，先增加行数和行为 guardrail，再抽离展示组件与提交 hook，避免一次性大改交易行为。
3. 后端侧优先处理 `AuditDetailsBuilder` 统一采用和高风险交易/资金路径注释质量规则，继续保持“只改注释不改逻辑”与架构测试先行。
4. `AdminFeatureCatalogService` 拆分应按 catalog group 小步执行，每组保持当前 API 和现有测试不变。
5. 根目录 KLineCharts 大文件治理先保留为低优先级 guardrail，不与交易平台业务前端优化混在同一轮改动中。

## 说明

- 本轮没有新增运行时依赖。
- 本轮没有改动登录鉴权、游客 watch-only、下单语义、盘口数据语义或 KLineCharts 渲染算法。
- 本轮没有修改已应用的 Flyway migration。
