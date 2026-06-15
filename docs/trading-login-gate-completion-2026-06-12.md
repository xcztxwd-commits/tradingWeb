# 交易看盘未登录门控完成报告

## 目标

打开交易看盘页后，未登录用户自动看到登录提示弹窗；点击弹窗按钮进入登录页；点击关闭后继续看盘；所有下单按钮显示“请前往登录”，点击后进入登录页。前后端需要有清晰的状态探针、可重试、可定位、可验证链路。

## 已完成

- 后端新增公开登录状态探针：`GET /api/auth/session`
  - 未登录返回 `authenticated:false` 和 `loginPath:"/login"`。
  - 已登录返回用户 `id/email/role`。
  - `SecurityConfig` 已放开该端点，其它交易/账户接口权限不变。
- 前端交易页改为真实 session 探针
  - 不再自动注册/登录 demo 用户。
  - 无 token 也会调用 `/api/auth/session`，便于验证前后端链路。
  - 未登录进入 `login-required`，行情、K 线、盘口继续可用。
- 交易看盘页新增登录提示弹窗
  - 自动弹出。
  - 支持右上角 `X` 关闭。
  - 关闭后继续看盘。
  - 主按钮跳转 `/login?redirect=/trading`。
- 下单入口改造
  - 未登录时买入/卖出按钮统一显示“请前往登录”。
  - 点击下单按钮不再提交订单，直接跳转登录页。
- 新增登录页
  - `/login` 路由。
  - 登录成功写入 token 并回到 `redirect`。
  - 页面包含产品简介、交易终端视觉风格、动效、焦点状态和移动端适配。
- 生命链条可视化和重试
  - 交易页新增 session 状态条：公开看盘模式、账户链路已连接、会话链路异常。
  - 异常状态提供重试入口。
  - smoke 脚本输出后端健康、guest session、session 请求次数和最终跳转路径。
- 已继续执行下一步优化
  - 新增 `readStoredAuthToken/writeStoredAuthToken/clearStoredAuthToken`。
  - 新登录链路和旧 web admin 页已切到 `AuthToken` 命名。
  - 保留旧 `DemoToken` 导出和 `fx-platform-demo-token` key，避免破坏已有本地 token。

## 修改文件

- `docs/superpowers/plans/2026-06-12-trading-login-gate.md`
- `docs/trading-login-gate-completion-2026-06-12.md`
- `fx-trading-platform/backend/src/main/java/com/fxplatform/auth/controller/AuthController.java`
- `fx-trading-platform/backend/src/main/java/com/fxplatform/auth/dto/response/SessionStatusResponse.java`
- `fx-trading-platform/backend/src/main/java/com/fxplatform/common/security/SecurityConfig.java`
- `fx-trading-platform/backend/src/test/java/com/fxplatform/auth/controller/AuthControllerSessionTest.java`
- `fx-trading-platform/apps/web/src/app/App.tsx`
- `fx-trading-platform/apps/web/src/app/App.test.ts`
- `fx-trading-platform/apps/web/src/features/trading-session/tradingSession.ts`
- `fx-trading-platform/apps/web/src/features/trading-session/tradingSession.test.ts`
- `fx-trading-platform/apps/web/src/features/trading-session/tradingSessionStorage.ts`
- `fx-trading-platform/apps/web/src/features/trading-session/useTradingSession.ts`
- `fx-trading-platform/apps/web/src/features/trading/components/OrderFormSide.tsx`
- `fx-trading-platform/apps/web/src/features/trading/components/OrderSubmitButton.tsx`
- `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx`
- `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.test.ts`
- `fx-trading-platform/apps/web/src/features/trading/styles/trade-panel.css`
- `fx-trading-platform/apps/web/src/pages/admin/AdminPage.tsx`
- `fx-trading-platform/apps/web/src/pages/login/LoginPage.tsx`
- `fx-trading-platform/apps/web/src/pages/login/LoginPage.module.css`
- `fx-trading-platform/apps/web/src/pages/trading/TradingPage.tsx`
- `fx-trading-platform/apps/web/src/pages/trading/TradingPage.module.css`
- `fx-trading-platform/apps/web/src/pages/trading/TradingPage.test.ts`
- `fx-trading-platform/apps/web/src/pages/trading/components/LoginPromptDialog.tsx`
- `fx-trading-platform/apps/web/src/pages/trading/components/LoginPromptDialog.module.css`
- `fx-trading-platform/apps/web/src/services/authApi.ts`
- `fx-trading-platform/package.json`
- `fx-trading-platform/scripts/smoke-trading-login-gate.mjs`

## 验证结果

- 后端目标红灯：`AuthControllerSessionTest` 初次失败于缺少 `session(...)` 方法。
- 前端目标红灯：新增断言初次失败于缺少 `/login` 路由、session 探针、登录弹窗和“请前往登录”按钮文案。
- 后端目标测试：`mvn -Dtest=AuthControllerSessionTest test`，2/2 PASS。
- 后端全量测试：`mvn test`，102/102 PASS。
- 前端全量测试：`npm.cmd --workspace apps/web run test`，174/174 PASS。
- 前端构建：`npm.cmd --workspace apps/web run build`，PASS。
- 联动点击测试：`npm.cmd run smoke:trading-login-gate`，PASS。
  - `backendHealth: "UP"`
  - `guestSessionAuthenticated: false`
  - `sessionProbeRequests: 2`
  - `finalPath: "/login?redirect=/trading"`

## 未完成或保留项

- 未把本地 token key 从 `fx-platform-demo-token` 迁移成新 key；为避免破坏用户已有登录态，本次只做兼容命名。
- Vite build 仍提示 `tradingMarketApi.ts` 同时动态和静态导入，属于既有 chunk 分割警告，不影响本次功能。
- Codex Browser 控制工具本轮未暴露；已用 Chrome DevTools Protocol smoke 脚本替代真实点击验证。

## 推荐下一步

1. 增加 token key 一次性迁移：从 `fx-platform-demo-token` 平滑迁移到 `fx-platform-auth-token`。
2. 给登录弹窗和登录页补桌面/移动截图基线，降低后续 UI 回归成本。
3. 后端给 `/api/auth/session` 增加请求日志字段或指标，区分 guest、valid token、invalid token。
4. 登录页增加注册入口或管理员/交易员角色提示，避免用户只看到登录表单却不知道账号来源。
