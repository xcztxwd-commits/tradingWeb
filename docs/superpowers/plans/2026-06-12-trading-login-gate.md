# Trading Login Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交易看盘页允许未登录用户继续看盘，但首屏自动提示登录，关闭后所有下单入口显示“请前往登录”，点击后进入登录页。

**Architecture:** 后端新增公开 `GET /api/auth/session` 作为登录状态探针，避免前端通过下单接口 401 猜测状态。前端 `useTradingSession` 改为读取真实会话，不再自动创建 demo 登录；交易页持有登录提示弹窗状态，`TradePanel` 只负责渲染下单入口和触发登录跳转。联动测试通过真实后端健康检查、前端源码测试、Vite 页面点击和后端 session 请求日志来定位链路问题。

**Tech Stack:** Spring Boot 3.5, Spring Security, Java 21, React 19, React Router 7, Vite, Node test, PowerShell.

---

## 原始需求对齐

- 打开交易看盘页面自动弹出登录提示：`TradingPage` 在 `GET /api/auth/session` 返回未登录后显示 `LoginPromptDialog`。
- 点击弹窗跳转登录页：弹窗主按钮和下单按钮都调用 `navigate('/login?redirect=/trading')`。
- 关闭弹窗后继续看盘：关闭只更新本地 `loginPromptDismissed`，行情、K 线、盘口仍使用公开 market/chart API。
- 下单按钮改为“请前往登录”：`OrderSubmitButton` 根据 `loginRequired` 覆盖按钮文案。
- 前后端都要修改和测试：后端新增 session DTO/controller/security permitAll；前端新增 API、session hook、登录页、弹窗、按钮状态和测试。
- 生命链条健康、可视化、重试和定位：交易页展示会话探针状态、重试按钮和错误文案；联动脚本验证前端点击会打到后端 session 端点。
- UI/UX：登录提示使用交易终端风格、明确产品简介、进入/退出动画、可访问焦点与 reduced-motion 兼容。

## 文件结构

- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/auth/dto/response/SessionStatusResponse.java`
  - 后端 session 探针响应 DTO。
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/auth/controller/AuthController.java`
  - 新增 `GET /api/auth/session`。
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/common/security/SecurityConfig.java`
  - 允许未登录访问 `/api/auth/session`。
- Create: `fx-trading-platform/backend/src/test/java/com/fxplatform/auth/controller/AuthControllerSessionTest.java`
  - 后端单测：未登录/已登录 session 响应。
- Modify: `fx-trading-platform/apps/web/src/services/authApi.ts`
  - 新增 `getSessionStatus(token?)`。
- Modify: `fx-trading-platform/apps/web/src/features/trading-session/useTradingSession.ts`
  - 改为真实会话探针，未登录进入 `login-required`。
- Modify: `fx-trading-platform/apps/web/src/features/trading-session/tradingSession.ts`
  - 新增 session 状态模型，保留本地预览订单工具但不自动 demo 登录。
- Modify: `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx`
  - 新增 `loginRequired` 和 `onLoginRequired`，未登录下单触发登录跳转。
- Modify: `fx-trading-platform/apps/web/src/features/trading/components/OrderFormSide.tsx`
  - 透传登录态到按钮。
- Modify: `fx-trading-platform/apps/web/src/features/trading/components/OrderSubmitButton.tsx`
  - 未登录按钮文案为“请前往登录”。
- Modify: `fx-trading-platform/apps/web/src/features/trading/styles/trade-panel.css`
  - 美化未登录按钮、会话状态、重试反馈。
- Create: `fx-trading-platform/apps/web/src/pages/login/LoginPage.tsx`
  - 登录页，支持 `redirect` 参数，调用现有 login API 并保存 token。
- Create: `fx-trading-platform/apps/web/src/pages/login/LoginPage.module.css`
  - 登录页产品简介、动效和表单样式。
- Create: `fx-trading-platform/apps/web/src/pages/trading/components/LoginPromptDialog.tsx`
  - 看盘页未登录提示弹窗。
- Create: `fx-trading-platform/apps/web/src/pages/trading/components/LoginPromptDialog.module.css`
  - 弹窗动效、终端风格和可访问样式。
- Modify: `fx-trading-platform/apps/web/src/pages/trading/TradingPage.tsx`
  - 接入未登录弹窗、登录跳转和 session 状态。
- Modify: `fx-trading-platform/apps/web/src/pages/trading/TradingPage.module.css`
  - 补充会话可视化样式。
- Modify: `fx-trading-platform/apps/web/src/app/App.tsx`
  - 新增 `/login` 路由。
- Test: `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.test.ts`
- Test: `fx-trading-platform/apps/web/src/features/trading-session/tradingSession.test.ts`
- Test: `fx-trading-platform/apps/web/src/pages/trading/TradingPage.test.ts`
- Test: `fx-trading-platform/apps/web/src/app/App.test.ts`
- Create: `fx-trading-platform/scripts/smoke-trading-login-gate.mjs`
  - 联动测试：后端 session 探针 + Vite 页面点击 + 网络请求验证。
- Modify: `fx-trading-platform/package.json`
  - 新增 `smoke:trading-login-gate`。
- Create: `docs/trading-login-gate-completion-2026-06-12.md`
  - 完成报告、已完成/未完成、下一步优化。

### Task 1: 文档与红灯测试

- [ ] **Step 1: 写后端失败测试**

在 `AuthControllerSessionTest.java` 中断言 `session(null)` 返回未登录，`session(principal)` 返回用户信息。

- [ ] **Step 2: 写前端失败测试**

在现有 source-scan tests 中加入断言：
`/login` 路由存在、`GET /api/auth/session` 被调用、`TradePanel` 支持 `loginRequired`、按钮文案是“请前往登录”、`TradingPage` 渲染 `LoginPromptDialog`。

- [ ] **Step 3: 运行红灯验证**

Run:
`npm.cmd --workspace apps/web run test`
`mvn.cmd -Dtest=AuthControllerSessionTest test`

Expected: 新增测试失败，失败原因是目标代码尚不存在。

- [ ] **Step 4: 对齐需求**

确认失败点覆盖“打开弹窗、关闭可看盘、按钮请前往登录、点击跳登录页、后端 session 探针”。

### Task 2: 后端 session 探针

- [ ] **Step 1: 新增 DTO**

`SessionStatusResponse` 字段：`authenticated`, `userId`, `email`, `role`, `loginPath`。

- [ ] **Step 2: 新增 Controller 方法**

`GET /api/auth/session` 返回 `SessionStatusResponse.guest("/login")` 或已登录用户。

- [ ] **Step 3: 放开 Security**

`SecurityConfig` 中 `/api/auth/session` permitAll。

- [ ] **Step 4: 后端验证**

Run:
`mvn.cmd -Dtest=AuthControllerSessionTest test`

Expected: PASS。

- [ ] **Step 5: 需求对齐**

后端只提供状态探针，不改变登录、下单、行情权限。

### Task 3: 前端未登录会话与登录页

- [ ] **Step 1: 新增 auth API**

`getSessionStatus(token?)` 调用 `apiGet('/api/auth/session', token)`。

- [ ] **Step 2: 改造交易 session hook**

读取本地 token；无 token 或 session 未认证时进入 `login-required`，不再自动注册/登录 demo 用户；探针失败进入 `error` 并保留重试。

- [ ] **Step 3: 新增登录页**

`LoginPage` 提供邮箱/密码输入、登录中状态、错误提示、产品简介，登录成功写入 `fx-platform-demo-token` 并跳转 `redirect`。

- [ ] **Step 4: 前端验证**

Run:
`npm.cmd --workspace apps/web run test`

Expected: PASS。

- [ ] **Step 5: 需求对齐**

确认未登录不再自动 demo 登录，点击登录入口会进入 `/login?redirect=/trading`。

### Task 4: 交易页弹窗、按钮和 UI 美化

- [ ] **Step 1: 新增登录提示弹窗**

`LoginPromptDialog` 包含关闭按钮、主登录按钮、产品简介和健康状态说明。

- [ ] **Step 2: 接入 TradingPage**

未登录且未关闭弹窗时自动显示；关闭后继续看盘；登录按钮和下单按钮统一跳转登录页。

- [ ] **Step 3: 改造 TradePanel 按钮**

`loginRequired` 时下单按钮文字固定为“请前往登录”，点击触发 `onLoginRequired`。

- [ ] **Step 4: UI 验证**

Run:
`npm.cmd --workspace apps/web run test`
`npm.cmd --workspace apps/web run build`

Expected: PASS。

- [ ] **Step 5: 需求对齐**

确认弹窗可关闭、看盘区域保留、下单入口全部引导登录、样式有动效并支持 reduced-motion。

### Task 5: 全链路联动与完成报告

- [ ] **Step 1: 新增 smoke 脚本**

脚本检查：
后端 `/actuator/health`；
后端 `/api/auth/session` 未登录返回 `authenticated:false`；
打开 Vite `/trading`；
等待登录弹窗；
关闭弹窗后确认交易图表/下单面板仍存在；
点击“请前往登录”后确认 URL 进入 `/login?redirect=/trading`；
确认页面加载期间出现 `/api/auth/session` 网络请求。

- [ ] **Step 2: 运行联动测试**

Run:
`npm.cmd run smoke:trading-login-gate`

Expected: PASS。

- [ ] **Step 3: 完成流程测试**

Run:
`mvn.cmd test`
`npm.cmd --workspace apps/web run test`
`npm.cmd --workspace apps/web run build`
`npm.cmd run smoke:trading-login-gate`

Expected: PASS。

- [ ] **Step 4: 生成完成报告**

写入 `docs/trading-login-gate-completion-2026-06-12.md`，列出修改、完成项、未完成项、推荐下一步。

- [ ] **Step 5: 下一步优化方案**

推荐下一步：真实登录态统一 token 命名、交易页 session 指标上报、登录后账户引导、Playwright 截图基线。

## 自审

- 无未定义的文件职责：所有新增和修改路径已列明。
- 无“以后再做”作为当前验收依赖：下一步优化只列为后续建议。
- 当前计划遵守精准修改：不重构 KLineChart 核心库，不改后台 admin，不改数据库 schema。
- 当前计划遵守简洁优先：一个后端 session 端点，一个前端弹窗，一个登录页，一个联动脚本。
