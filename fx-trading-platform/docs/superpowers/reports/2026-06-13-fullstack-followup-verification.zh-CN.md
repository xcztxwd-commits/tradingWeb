# 2026-06-13 全栈优化专项验证报告

## 结论

本轮对前端、后端和页面按钮到后端接口的链路进行了独立验证。当前修改后的业务代码未发现阻断性 bug；前端构建、单测、体积预算、性能 smoke、后端全量/定向测试、架构规则、真实交易业务 smoke 和浏览器链路联调均通过。

唯一发现的问题是 `scripts/smoke-trading-login-gate.mjs` 的旧流程断言已落后于当前产品行为：脚本仍等待交易页初始自动弹出登录提示，而当前设计是游客可先看盘，点击下单按钮才弹出登录提示。已修正该 smoke 脚本并重新通过。

## 前端单独验证

- `npm --prefix fx-trading-platform run web:test`
  - 结果：251 个测试全部通过，0 失败。
- `npm --prefix fx-trading-platform run web:bundle-budget`
  - 结果：通过。
  - `TradingPage` JS：371672 / 380000 bytes。
  - `MobileTradingTerminal` JS：4356 / 20000 bytes。
  - `IndicatorSettingsModal` JS：9233 / 20000 bytes。
  - `ChartDrawingToolbar` JS：7806 / 80000 bytes。
- `cd fx-trading-platform && npm run audit:large-files`
  - 结果：通过。
  - `TradingPage.tsx`：279 / 330 行。
  - `TradePanel.tsx`：227 / 230 行。

## 前端性能 Smoke

使用本机 Chrome 对 `http://127.0.0.1:5188/trading` 做桌面和移动端 smoke。Browser 插件本轮没有暴露可用页面控制工具，因此使用 Playwright + 系统 Chrome 作为 fallback。

- 桌面视口 `1440x900`
  - `domContentLoadedMs`: 157
  - `loadMs`: 158
  - API 响应：30 个 `/api/*` 响应均为 200。
  - 控制台 error/warning：0。
  - 失败请求：0。
  - 横向溢出：0。
- 移动视口 `390x844`
  - `domContentLoadedMs`: 139
  - `loadMs`: 141
  - API 响应：30 个 `/api/*` 响应均为 200。
  - 控制台 error/warning：0。
  - 失败请求：0。
  - 横向溢出：0。

## 后端单独验证

- `cd fx-trading-platform/backend && mvn test`
  - 结果：139 个测试全部通过，0 失败。
- `npm --prefix fx-trading-platform run verify:architecture`
  - 结果：通过。
- 定向测试：
  - `AdminFeatureOperationServiceTest`
  - `AdminMarketCommandServiceTest`
  - `AdminConfigCommandServiceTest`
  - `AdminContentCommandServiceTest`
  - `OrderServiceTest`
  - `PositionServiceTest`
  - `PendingOrderExecutionServiceTest`
  - `ProtectiveOrderExecutionServiceTest`
  - `ArchitectureRulesTest`
  - 结果：70 个测试全部通过，0 失败。
- `cd fx-trading-platform && npm run smoke:real-trading-loop`
  - 结果：通过。
  - 覆盖：后端健康检查、注册、账户与初始账本、行情 quote、市场单成交、限价挂单自动成交、TP/SL 自动平仓、用户撤单。

## 链路联调

- `cd fx-trading-platform && npm run smoke:user-core-pages`
  - 结果：通过。
  - 覆盖：真实用户注册和数据种子、受保护页面登录态、loading/error 状态、dashboard/markets/orders/positions/wallet 页面按钮与后端 API 调用。
  - 关键证据：
    - dashboard：16 个 API 请求，16 个响应，0 失败。
    - markets：18 个 API 请求，18 个响应，0 失败。
    - orders：33 个 API 请求，33 个响应，0 失败。
    - positions：35 个 API 请求，35 个响应，0 失败。
    - wallet：19 个 API 请求，19 个响应，0 失败。
- `cd fx-trading-platform && npm run smoke:trading-login-gate`
  - 初始结果：失败，原因是 smoke 脚本旧断言。
  - 修复后结果：通过。
  - 关键证据：
    - `guestSessionStatus`: `guest`
    - `invalidSessionStatus`: `invalid_token`
    - `validSessionStatus`: `valid_token`
    - `sessionProbeRequests`: 7
    - `finalPath`: `/login?redirect=/trading`
- 真实交易页按钮到后端下单链路
  - 页面：`/trading?symbol=EURUSD`
  - 操作：写入真实 auth token，填写买入数量 `5`，点击 `买入 EUR`，在确认弹窗点击 `确认下单`。
  - 后端请求：POST `/api/trading/orders` 返回 200。
  - 创建订单：`1c7a8fea-999c-4ca6-85ce-66a9b2f17f9a`，`EURUSD`，`BUY`，`PENDING`，`lots=5`。
  - 后端订单列表核对：找到同一订单。
  - 控制台 error/warning：0。
  - 失败请求：0。

## 本轮修复

- 修复 `scripts/smoke-trading-login-gate.mjs`：
  - 从“初始自动弹出登录提示”改为“游客初始可看盘，点击 `登录后下单` 后弹出登录提示”。
  - 保留关闭弹窗后继续看盘、再次点击交易按钮重开弹窗、点击 `前往登录` 跳转 `/login?redirect=/trading`、无效 token 与有效 token 状态验证。

## 下一步优化方向

1. 将本轮临时 Playwright UI 下单链路沉淀为仓库脚本，加入固定 smoke 命令，避免以后只靠人工临时验证。
2. 给交易页按钮链路增加稳定的 `data-testid`，减少中文文案变化对 smoke 脚本的影响。
3. 将性能 smoke 的阈值固化，例如移动端横向溢出必须为 0、控制台 error 为 0、核心 API 失败响应为 0。
4. 如果后续引入真实支付、真实行情或 WebSocket 长连，应增加环境矩阵 smoke，区分 demo 数据、真实数据和降级路径。
