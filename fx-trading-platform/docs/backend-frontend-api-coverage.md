# 后端接口与前端接入覆盖评估

评估日期：2026-06-08

2026-06-16 校准：

- 主前端 `apps/web` 的 `authApi.ts` 只保留 `POST /api/auth/register`、`POST /api/auth/login`、`GET /api/auth/session`；`identity-check` 和 `verification-code` service 封装已删除，当前后端也没有对应 public auth endpoint。
- `/api/admin/**` 和 `/api/admin/market/data-providers/**`、`/api/admin/market/symbols/{symbolId}/provider-bindings/**` 属于独立后台 `apps/admin` 与后端 provider 治理链路，主前端不消费是预期行为，不应按“接口没用”判 bug。
- 本文是 2026-06-08 的覆盖快照；`GET /api/admin/trading/trades` 和 `/api/admin/risk/configs` 后续已补齐，下面相关行已按当前代码校准。

## 结论

当前 Java 后端业务 REST 接口共 51 个，另有 1 个 STOMP WebSocket 入口 `/ws` 和 3 类行情推送 topic。

独立后台 `apps/admin` 已经接上了现有 Java 后台的主要查询接口：登录、首页统计、用户、账户、订单、持仓、资金流水、支付方式、品种、行情状态、消息、公告、字典、系统设置、审计日志，以及用户状态更新。通过前端端口 `5174` 的 `/api` 代理做了 26 个安全 GET 联调检查，全部返回 `200 + success=true`。

但独立后台还不是完整运营后台：后台写操作仍有一部分没有迁移到 `apps/admin` 页面里。`成交记录` 和 `风控配置` 当前已有 Java 后端接口；其中成交记录已由独立后台读取，风控配置当前以只读页面接入。

## 评估口径

- 已接入独立后台：`apps/admin` 当前路由页面或服务实际调用。
- 已接入交易前台：`apps/web` 当前 `/trading` 页面实际调用。
- 已接入旧 Web 管理页：`apps/web` 的 `/admin` 页面实际调用。该页面仍存在，但不是这次路由化后的独立后台主线。
- 仅服务封装：前端 service 有函数，但当前页面没有调用。
- 未接入：前端没有当前页面调用，也没有有效服务调用。
- 写操作没有批量实测，避免改动本地数据；覆盖判断来自代码引用。GET 接口已做前端代理只读验证。

## 关键文件

- 后端 Controller：`backend/src/main/java/com/fxplatform/**/controller/*Controller.java`
- 安全与代理相关：`backend/src/main/java/com/fxplatform/common/security/SecurityConfig.java`
- WebSocket：`backend/src/main/java/com/fxplatform/common/websocket/MarketWebSocketConfig.java`
- 独立后台服务：`apps/admin/src/services/adminApi.ts`、`apps/admin/src/services/authApi.ts`
- 独立后台路由：`apps/admin/src/app/AdminApp.tsx`
- 交易前台服务：`apps/web/src/services/*.ts`、`apps/web/src/pages/trading/tradingMarketApi.ts`
- 旧 Web 管理页：`apps/web/src/pages/admin/AdminPage.tsx`、`apps/web/src/services/adminApi.ts`

## 独立后台 apps/admin 接入现状

| 前端页面 | 当前状态 | 已接后端接口 | 缺口 |
| --- | --- | --- | --- |
| `/login` | 已接入 | `POST /api/auth/login` | 未调用 `POST /api/auth/logout`，退出只清本地 token |
| `/dashboard` | 已接入 | `GET /api/admin/dashboard/summary` | 无 |
| `/users` | 部分接入 | `GET /api/admin/users`、`PATCH /api/admin/users/{userId}/status` | KYC、风险等级、备注、强制退出未迁移 |
| `/accounts` | 已接入只读 | `GET /api/admin/accounts` | 入金、出金、余额调整在财务接口里，未迁移到独立后台表单 |
| `/trading/orders` | 已接入只读 | `GET /api/admin/trading/orders` | 后台取消订单未迁移 |
| `/trading/positions` | 已接入只读 | `GET /api/admin/trading/positions` | 后台强制平仓未迁移 |
| `/trading/trades` | 已接入只读 | `GET /api/admin/trading/trades?page=&size=` | 无 |
| `/finance/ledger` | 已接入只读 | `GET /api/admin/finance/ledger` | 财务写操作未迁移 |
| `/finance/payment-methods` | 已接入只读 | `GET /api/admin/finance/payment-methods` | 创建/更新支付方式未迁移 |
| `/market/symbols` | 已接入只读 | `GET /api/admin/market/symbols` | 品种启停、价格调整未迁移 |
| `/market/status` | 已接入 | `GET /api/admin/market/status` | 无 |
| `/risk` | 已接入只读 | `GET /api/admin/risk/configs` | 创建/更新/删除接口已在后端，独立后台页面当前只读 |
| `/content/messages` | 已接入只读 | `GET /api/admin/content/messages` | 创建站内消息未迁移 |
| `/content/articles` | 已接入只读 | `GET /api/admin/content/articles` | 创建公告/新闻未迁移 |
| `/config/dictionaries` | 已接入只读 | `GET /api/admin/config/dictionaries` | 创建/更新字典未迁移 |
| `/config/settings` | 已接入只读 | `GET /api/admin/config/settings` | 更新系统设置未迁移 |
| `/audit-logs` | 已接入只读 | `GET /api/admin/audit-logs` | 无 |

## 后端 REST 接口覆盖表

### 认证接口 `/api/auth`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/register` | 已实现 | 已接入交易前台 | `apps/web` demo session 登录失败时自动注册 |
| POST | `/api/auth/login` | 已实现 | 已接入独立后台、交易前台 | 独立后台登录页和交易前台 demo session 都使用 |
| POST | `/api/auth/refresh` | 保留但未实现 | 未接入 | 后端返回 `NOT_IMPLEMENTED` |
| POST | `/api/auth/logout` | 已实现空成功 | 未接入 | 独立后台退出当前只清本地 token |
| GET | `/api/auth/me` | 已实现 | 未接入 | 只读验证通过，但没有页面使用 |

### 用户账户接口 `/api/accounts`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `/api/accounts` | 已实现 | 已接入交易前台 | `/trading` demo session 初始化账户 |
| GET | `/api/accounts/{accountId}/summary` | 已实现 | 已接入交易前台 | `/trading` 刷新账户摘要 |
| POST | `/api/accounts/demo` | 已实现 | 已接入交易前台 | demo 用户无账户时自动创建 |

### 行情与图表接口 `/api/market`、`/api/chart`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `/api/market/symbols` | 已实现 | 已接入交易前台 | `/trading` 交易品种列表 |
| GET | `/api/market/quotes/{symbol}` | 已实现 | 已接入交易前台 | 首屏报价和右侧行情面板使用 |
| GET | `/api/market/order-book/{symbol}` | 已实现 | 已接入交易前台 | 右侧订单簿使用 |
| GET | `/api/market/trades/{symbol}?limit=` | 已实现 | 已接入交易前台 | 右侧最新成交使用 |
| GET | `/api/market/status` | 已实现 | 未接入 | 前端当前用的是后台版 `GET /api/admin/market/status` |
| GET | `/api/chart/candles?symbol=&timeframe=&from=&to=` | 已实现 | 已接入交易前台 | K 线图加载历史 candles |

### 交易接口 `/api/trading`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| POST | `/api/trading/orders` | 已实现 | 已接入交易前台 | `/trading` 下单面板使用 |
| GET | `/api/trading/orders` | 已实现 | 已接入交易前台 | 底部订单列表刷新 |
| GET | `/api/trading/positions?accountId=` | 已实现 | 已接入交易前台 | 底部持仓列表刷新 |
| POST | `/api/trading/positions/{positionId}/close?accountId=` | 已实现 | 已接入交易前台 | 持仓平仓操作 |

### 资金流水接口 `/api/ledger`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `/api/ledger?accountId=` | 已实现 | 已接入交易前台 | 底部资金流水刷新 |

### 后台首页与审计 `/api/admin`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `/api/admin/dashboard/summary` | 已实现 | 已接入独立后台、旧 Web 管理页 | 后台首页统计 |
| GET | `/api/admin/audit-logs?page=&size=` | 已实现 | 已接入独立后台、旧 Web 管理页 | 审计日志列表 |

### 后台用户管理 `/api/admin/users`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `/api/admin/users?page=&size=` | 已实现 | 已接入独立后台、旧 Web 管理页 | 用户列表 |
| PATCH | `/api/admin/users/{userId}/status` | 已实现 | 已接入独立后台、旧 Web 管理页 | 独立后台有启停按钮 |
| POST | `/api/admin/users/{userId}/kyc-review` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |
| PATCH | `/api/admin/users/{userId}/risk-level` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |
| POST | `/api/admin/users/{userId}/notes` | 已实现 | 仅服务封装 | `apps/web/src/services/adminApi.ts` 有 `addAdminUserNote`，页面未调用 |
| POST | `/api/admin/users/{userId}/force-logout` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |

### 后台账户管理 `/api/admin/accounts`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `/api/admin/accounts?page=&size=` | 已实现 | 已接入独立后台、旧 Web 管理页 | 后台账户列表 |

### 后台交易管理 `/api/admin/trading`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `/api/admin/trading/orders?page=&size=` | 已实现 | 已接入独立后台、旧 Web 管理页 | 后台订单列表 |
| GET | `/api/admin/trading/positions?page=&size=` | 已实现 | 已接入独立后台、旧 Web 管理页 | 后台持仓列表 |
| POST | `/api/admin/trading/orders/{orderId}/cancel` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |
| POST | `/api/admin/trading/positions/{positionId}/force-close` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |
| GET | `/api/admin/trading/trades?page=&size=` | 已实现 | 已接入独立后台 | 后台成交记录列表 |

### 后台财务管理 `/api/admin/finance`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `/api/admin/finance/ledger?page=&size=` | 已实现 | 已接入独立后台、旧 Web 管理页 | 后台资金流水 |
| GET | `/api/admin/finance/payment-methods?page=&size=` | 已实现 | 已接入独立后台、旧 Web 管理页 | 支付方式列表 |
| POST | `/api/admin/finance/payment-methods` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |
| PATCH | `/api/admin/finance/payment-methods/{paymentMethodId}` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |
| POST | `/api/admin/finance/accounts/{accountId}/deposit` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |
| POST | `/api/admin/finance/accounts/{accountId}/withdraw` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |
| POST | `/api/admin/finance/accounts/{accountId}/adjustments` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |

### 后台行情与品种管理 `/api/admin/market`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `/api/admin/market/symbols?page=&size=` | 已实现 | 已接入独立后台、旧 Web 管理页 | 后台品种列表 |
| GET | `/api/admin/market/status` | 已实现 | 已接入独立后台 | 后台行情源状态 |
| PATCH | `/api/admin/market/symbols/{symbolId}/status` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |
| POST | `/api/admin/market/symbols/{symbolId}/price-adjustments` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |

### 后台内容管理 `/api/admin/content`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `/api/admin/content/messages?page=&size=` | 已实现 | 已接入独立后台、旧 Web 管理页 | 站内消息列表 |
| POST | `/api/admin/content/messages` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |
| GET | `/api/admin/content/articles?page=&size=` | 已实现 | 已接入独立后台、旧 Web 管理页 | 公告/新闻列表 |
| POST | `/api/admin/content/articles` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |

### 后台配置管理 `/api/admin/config`

| 方法 | 接口 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `/api/admin/config/dictionaries?page=&size=` | 已实现 | 已接入独立后台、旧 Web 管理页 | 字典列表 |
| PUT | `/api/admin/config/dictionaries` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |
| GET | `/api/admin/config/settings?page=&size=` | 已实现 | 已接入独立后台、旧 Web 管理页 | 系统设置列表 |
| PUT | `/api/admin/config/settings` | 已实现 | 已接入旧 Web 管理页 | 独立后台未迁移 |

## WebSocket 覆盖表

| 类型 | 地址 | 后端状态 | 前端接入状态 | 备注 |
| --- | --- | --- | --- | --- |
| STOMP endpoint | `/ws` | 已实现 | 已接入交易前台 | `apps/web/src/services/marketStream.ts` 建立连接 |
| Topic | `/topic/market/quotes/{symbol}` | 已发布 | 已订阅 | 交易页报价实时刷新 |
| Topic | `/topic/market/order-book/{symbol}` | 已发布 | 已订阅 | 右侧订单簿实时刷新 |
| Topic | `/topic/market/trades/{symbol}` | 已发布 | 已订阅 | 右侧最新成交实时刷新 |
| Inbound `/app/**` | 无 `@MessageMapping` | 未实现 | 未接入 | 当前 WebSocket 只做服务端推送 |

## 当前独立后台的主要缺口

1. `GET /api/admin/trading/trades?page=&size=` 已补齐，`成交记录` 不再是后端缺口。
2. `GET/POST/PUT/DELETE /api/admin/risk/configs` 已在后端；独立后台当前只读消费 `GET /api/admin/risk/configs`。
3. 独立后台只迁移了一个写操作：`PATCH /api/admin/users/{userId}/status`。
4. 以下后端写操作还未迁移到独立后台页面：
   - KYC 审核、风险等级、用户备注、强制退出。
   - 订单取消、强制平仓。
   - 支付方式创建/更新。
   - 人工入金、出金、余额调整。
   - 品种启停、价格调整。
   - 站内消息、公告/新闻创建。
   - 字典和系统设置更新。
5. `POST /api/auth/logout` 没有前端调用，退出登录目前只是本地 token 清理。
6. `POST /api/auth/refresh` 后端本身未实现，前端也未接入。
7. `GET /api/auth/me` 和 `GET /api/market/status` 后端可用，但当前页面没有使用。

## 建议的下一步顺序

1. 继续把已存在的后台写操作迁移到 `apps/admin` 页面，尤其是风控配置创建/更新/删除、后台取消订单和强制平仓。
2. 把旧 `apps/web /admin` 中已经接好的后台写操作，按模块迁移到 `apps/admin` 独立后台。
3. 为独立后台补 `logout` 调用；如果后续做长会话，再实现并接入 `refresh`。
4. 迁移完成后删除或降级旧 `apps/web /admin`，避免两个后台入口并存导致权限和 token 来源混乱。

## 本次只读验证记录

通过 `http://localhost:5174/api/...` 前端代理验证：

- `POST /api/auth/login` 返回 `200 + success=true`。
- 26 个安全 GET 检查全部返回 `200 + success=true`：
  - 行情/图表：symbols、quote、order-book、trades、market status、candles。
  - 认证/用户侧：me、accounts、account summary、orders、positions、ledger。
  - 后台：dashboard、users、accounts、orders、positions、finance ledger、payment methods、symbols、admin market status、messages、articles、dictionaries、settings、audit logs。

写接口未批量触发，避免对本地数据库产生运营数据变更。
