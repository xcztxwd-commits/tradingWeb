# 2026-06-13 全栈跟进优化完成说明

## 范围

本次收口覆盖以下两个计划文件中的优化项：

- `docs/superpowers/plans/2026-06-13-fullstack-followup-optimization.zh-CN.md`
- `docs/superpowers/plans/2026-06-13-frontend-follow-up-optimization-zh.md`

执行原则：保持代码可读、可扩展、低耦合；每个阶段都用聚焦测试或全量验证收口。

## 已完成改动

### 前端边界与页面拆分

- 将 market data snapshot、quote adapter、market store 移到 `features/market`，保留旧目录兼容 re-export，避免 trading page 反向拥有市场数据。
- 删除废弃 `.order-panel`、`.order-actions`、`.two-inputs` 等全局样式，并补前端结构测试防止回归。
- 将 shared loading skeleton 移到 `apps/web/src/components/loading/TerminalSkeleton.tsx`，页面目录只保留兼容 re-export。
- 将 `TradingPage.tsx` 拆成编排壳：
  - `components/TradingDesktopView.tsx`
  - `components/TradingMobileView.tsx`
  - `components/TradingSettingsDialog.tsx`
  - `useTradingChartSettings.ts`
  - `tradingPageMarketSelection.ts`
  - `tradingPageSessionStatus.ts`
  - `useMobileTerminalViewport.ts`
- 将 `TradePanel.tsx` 拆成组合壳：
  - `TradePanelSessionStatus.tsx`
  - `TradePanelLeverageControls.tsx`
  - `TradePanelAccountStrip.tsx`
  - `useTradePanelSubmit.ts`
- 移除 `orderError/lastOrderError` 的无效父级透传，订单提交错误只使用当前捕获的错误。
- 修正 `tradePanelMarket` 不再从 BTC mock market 借价；非 BTC 且 snapshot 为空时不会产生错误价格。
- 增加杠杆输入 `Number.isFinite` 防护，避免非有限值进入状态更新。
- 补齐 `SettingsPage.tsx`，使 `/settings` 不再是缺失 lazy route。

### 后端审计与目录拆分

- 引入并推广 `AuditDetailsBuilder` 到高风险 admin command/service：
  - feature operation、user、fund order、risk、member、market、config、content 等路径。
- 将 `AdminFeatureCatalogService` 拆成页面组目录模块：
  - permission/product/finance/member/order/content/settings 等 feature pages。
- 修复 `FundReviewFeatureActionHandler`：已批准资金审核如果缺少完整 payload，不再静默成功，改为抛出业务异常。
- 清理交易和资金高风险路径模板化注释，并补架构测试防止错位注释再次出现。

### 质量护栏

- 新增 `scripts/audit-large-files.mjs` 和 `npm run audit:large-files`。
- 当前预算结果：
  - `TradingPage.tsx`: 279/330
  - `TradePanel.tsx`: 227/230
  - `AdminFeatureCatalogService.java`: 79/250
  - 根 chart library 关键文件均在预算内。

## 验证结果

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| 前端全量测试 | `npm --prefix fx-trading-platform run web:test` | 251/251 pass |
| 前端构建与 bundle budget | `npm --prefix fx-trading-platform run web:bundle-budget` | pass，`TradingPage` 371672/380000 bytes |
| 后端全量测试 | `cd fx-trading-platform/backend && mvn.cmd test` | 139/139 pass |
| 架构检查 | `npm --prefix fx-trading-platform run verify:architecture` | pass |
| 大文件预算 | `cd fx-trading-platform && npm.cmd run audit:large-files` | pass |
| 根类型检查 | `pnpm.cmd type-check` | pass |

## 浏览器 QA

- 服务：`http://127.0.0.1:5188/trading`，HTTP 200。
- Browser plugin：页面加载和控制台阶段可用，但在桌面交互验证中连续两次 native pipe 崩溃，因此降级到临时 Playwright。
- 临时 Playwright 不写入项目依赖，安装目录：`%TEMP%/codex-playwright-qa`。
- 桌面 1280x720：
  - 页面标题 `FX Trader`
  - 非空渲染
  - 无 Vite/React/framework overlay
  - 设置弹窗可打开
  - 杠杆弹窗可打开
  - console warn/error 为空
  - 截图：`C:\Users\User\AppData\Local\Temp\fx-trading-final-playwright-desktop.png`
- 移动 390x844：
  - 页面标题 `FX Trader`
  - `section[aria-label="移动端交易终端"]` 可见
  - `Trade` CTA 可见
  - `scrollWidth === width === 390`
  - console warn/error 为空
  - 截图：`C:\Users\User\AppData\Local\Temp\fx-trading-final-playwright-mobile.png`

## 下一步优化方向

1. 将 `BottomAccountPanel.tsx` 的 table/grid 渲染继续拆成独立 view components，避免该文件后续成为新的大文件热点。
2. 为移动端订单 sheet 增加一次真实点击 smoke：`Trade` -> 登录提示或订单面板打开，根据登录态分别断言。
3. 将临时 Playwright smoke 固化为仓库脚本，例如 `web:smoke:trading`，避免依赖手动临时脚本。
4. 后端可继续把 admin command 的审计动作抽成更薄的 command helper，但仅在出现重复扩张时再做，避免过早抽象。
