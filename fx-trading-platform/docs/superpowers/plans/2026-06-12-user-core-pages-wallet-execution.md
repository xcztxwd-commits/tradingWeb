# 用户核心页面与资金申请执行拆分

## 目标

把主导航里剩余的 `/dashboard`、`/markets`、`/wallet` 从占位页改成真实业务页面，并补齐 wallet 所需的用户侧资金申请 API。范围只覆盖 demo 资金账本、充值/提现申请与后台审核状态，不接真实支付、链上提现、三方支付通道或 KYC 规则。

## 已执行切片

### 1. `/markets` 真实行情页

- 新增 `apps/web/src/pages/markets/MarketsPage.tsx`。
- 使用 `fetchMarketSymbols` 读取后端 symbol 列表。
- 使用 `fetchMarketQuote` 补充报价、spread、source、change。
- 支持搜索、分类筛选、loading、error、empty。
- 点击市场进入 `/trading?symbol=...`。
- `TradingPage` 增加 query symbol 读取，保证从 markets 跳转后真实选中对应品种。

### 2. `/dashboard` 账户总览页

- 新增 `apps/web/src/pages/dashboard/DashboardPage.tsx`。
- 复用 `useTradingSession` 聚合真实 account/orders/positions/ledger。
- 展示净值、余额、可用保证金、已用保证金、今日成交、最近订单、最近流水、当前持仓。
- 覆盖 login-required、loading、error、empty。

### 3. `/wallet` 资金页

- 新增 `apps/web/src/pages/wallet/WalletPage.tsx`。
- 展示真实 account balance/free margin/currency。
- 展示真实 ledger entries。
- 新增充值/提现申请表单，提交到用户侧真实后端 API。
- 展示资金申请列表及 PENDING/APPROVED/REJECTED 审核状态。

### 4. 用户侧资金申请后端

- 新增 `GET /api/finance/fund-orders?accountId=...`。
- 新增 `POST /api/finance/fund-orders`。
- 新增 `FundOrderService`，用户只能查询/创建自己账户下的资金申请。
- 复用既有 `finance.fund_orders` 与 admin 审核/落账链路，不复制后台财务逻辑。

## 验收命令

- `C:\soft\Apache-Maven\apache-maven-3.9.11\bin\mvn.cmd -q test`
- `npm --prefix fx-trading-platform run web:test`
- `npm --prefix fx-trading-platform run web:build`

## 后续真实业务缺口

- wallet 还没有真实支付方式选择、支付凭证上传、提现账户绑定和 KYC/风控限制。
- orders 页面已有撤单和事件查看，但改单 UI 还只是后端/API 能力，用户端还需要表单入口。
- dashboard 目前是账户聚合页，后续可补 equity curve、风险率曲线、日内 PnL 和通知。
- markets 目前按 symbol 拉 quote，后续应由后端提供批量 ticker/market overview，减少多请求。
