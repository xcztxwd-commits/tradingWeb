# FX 后台管理系统后端设计

日期：2026-06-08

## 1. 背景

本设计基于当前仓库的 Java/Spring Boot 后台：

`fx-trading-platform/backend`

已有后端模块：

- `auth`：用户、登录、JWT、角色、状态。
- `account`：交易账户、余额、权益、保证金、杠杆。
- `market`：交易品种、报价、行情状态。
- `chart`：K 线数据。
- `trading`：订单、成交、持仓、订单事件。
- `ledger`：资金流水。
- `risk`：下单前风控、保证金、盈亏计算。
- `execution`：执行适配器。
- `audit`：后台关键操作审计。
- `admin`：已有最小后台 API 和管理员启动逻辑。

参考来源：

- `docs/wh-admin-fx-backoffice-analysis.md`
- 线上 wh-admin 后台静态包分析结果
- 当前 Spring Boot 代码结构、实体、迁移和安全配置

目标不是复制 wh-admin 的 PHP/Vue/MineAdmin 接口形态，而是在现有 Spring Boot 架构中实现同等业务管理能力。

## 2. 目标

后台管理系统后端应支持以下能力：

- 管理员登录后可查看和管理用户、账户、交易品种、订单、持仓、资金、消息、公告、新闻、系统配置和审计日志。
- 保持用户端交易 API 稳定，不破坏现有 `/api/trading`、`/api/market`、`/api/accounts`、`/api/ledger`。
- 后台 API 全部收敛到 `/api/admin/**`。
- 所有后台写操作必须有管理员身份、权限校验、操作原因、审计日志。
- 所有后台列表支持分页、筛选、排序，便于前端做表格、搜索、导出。
- 所有返回值使用 DTO，不直接返回 JPA Entity。
- 数据库变更只通过 Flyway migration。
- 高风险操作要可追踪、可解释、可回滚或可补偿。

## 3. 非目标

- 不直接兼容 wh-admin 的 `system/*` 路径。
- 不把 wh-admin 的前端组件、MineAdmin CRUD 配置迁进 Java 后端。
- 不在普通用户端暴露后台管理接口。
- 不做隐蔽操控用户盈亏、暗中改价、暗中替用户登录交易等能力。
- 不绕过当前 `OrderService`、`PositionService`、`LedgerService` 直接修改核心交易状态。
- 不引入独立后台服务或微服务拆分。

## 4. 推荐架构

采用方案 B：按业务域拆后台模块。

后台包结构建议：

```text
com.fxplatform.admin
  controller
    AdminDashboardController
    AdminUserController
    AdminAccountController
    AdminMarketController
    AdminTradingController
    AdminFinanceController
    AdminMessageController
    AdminContentController
    AdminSystemController
    AdminAuditController
  service
    AdminDashboardService
    AdminUserService
    AdminAccountService
    AdminMarketService
    AdminTradingService
    AdminFinanceService
    AdminMessageService
    AdminContentService
    AdminSystemService
    AdminAuditQueryService
  dto
    request
    response
```

原则：

- `controller` 只处理 HTTP、参数校验、当前管理员身份。
- `service` 承载后台业务流程和事务。
- 领域写操作优先调用已有领域服务，例如交易走 `OrderService`/`PositionService`，资金走 `LedgerService`。
- 查询型后台聚合可以由 admin service 调用 repository，但输出必须映射成 DTO。
- 每个写操作必须调用 `AuditLogService.record(...)`。

## 5. 安全和权限模型

现有 `SecurityConfig` 已限制：

```text
/api/admin/** -> hasRole("ADMIN")
```

第一阶段沿用粗粒度 `ADMIN` 角色。

第二阶段增加细粒度权限：

- `ADMIN_USER_READ`
- `ADMIN_USER_WRITE`
- `ADMIN_MARKET_READ`
- `ADMIN_MARKET_WRITE`
- `ADMIN_TRADING_READ`
- `ADMIN_TRADING_ACTION`
- `ADMIN_FINANCE_READ`
- `ADMIN_FINANCE_REVIEW`
- `ADMIN_MESSAGE_WRITE`
- `ADMIN_SYSTEM_WRITE`
- `ADMIN_AUDIT_READ`

可选表结构：

- `auth.admin_roles`
- `auth.admin_permissions`
- `auth.admin_role_permissions`
- `auth.admin_user_roles`

权限落地方式：

- 简单阶段：继续用 `@PreAuthorize("hasRole('ADMIN')")`。
- 完整阶段：使用 `@PreAuthorize("hasAuthority('ADMIN_FINANCE_REVIEW')")`。

## 6. 通用 API 规范

所有后台接口路径：

```text
/api/admin/**
```

响应继续使用当前项目的：

```java
ApiResponse<T>
```

后台列表统一响应结构：

```text
AdminPageResponse<T>
  items
  page
  size
  total
  totalPages
```

通用查询参数：

```text
page
size
sort
direction
keyword
createdFrom
createdTo
status
```

后台写请求统一带：

```text
reason
```

高风险写请求额外带：

```text
reviewNote
idempotencyKey
```

## 7. 仪表盘模块

对应 wh-admin 的首页、仪表盘、统计卡片。

接口：

```text
GET /api/admin/dashboard/summary
GET /api/admin/dashboard/trading-volume
GET /api/admin/dashboard/user-growth
GET /api/admin/dashboard/risk-alerts
GET /api/admin/dashboard/recent-operations
```

数据来源：

- `auth.users`
- `core.trading_accounts`
- `trading.orders`
- `trading.positions`
- `ledger.ledger_entries`
- `audit.audit_logs`

输出：

- 用户总数、新增用户数、活跃用户数。
- 账户余额总览、权益总览、保证金占用。
- 今日订单数、成交数、撤单数。
- 当前持仓数量、浮动盈亏合计。
- 最近后台操作日志。
- 风控告警数量。

## 8. 用户管理模块

对应 wh-admin：

- `system/member`
- `system/memberBank`
- 实名审核
- 备注
- 状态
- 修改密码
- 会员通知
- 踢下线

推荐接口：

```text
GET   /api/admin/users
GET   /api/admin/users/{userId}
GET   /api/admin/users/{userId}/accounts
GET   /api/admin/users/{userId}/ledger
GET   /api/admin/users/{userId}/orders
GET   /api/admin/users/{userId}/positions
PATCH /api/admin/users/{userId}/status
POST  /api/admin/users/{userId}/kyc-review
POST  /api/admin/users/{userId}/note
POST  /api/admin/users/{userId}/reset-password
POST  /api/admin/users/{userId}/force-logout
```

现有复用：

- `auth.users`
- `UserEntity`
- `UserStatus`
- `UserRole`
- `AdminUserResponse`
- `AuditLogService`

新增表建议：

```text
core.user_profiles
  user_id
  display_name
  country
  real_name
  id_document_type
  id_document_number_masked
  kyc_reviewed_by
  kyc_reviewed_at
  created_at
  updated_at

core.user_notes
  id
  user_id
  admin_user_id
  note
  created_at

core.user_payment_methods
  id
  user_id
  type
  holder_name
  bank_name
  account_number_masked
  wallet_address_masked
  status
  created_at
  updated_at
```

设计约束：

- 不返回 `passwordHash`。
- 证件号、银行卡号、钱包地址默认脱敏。
- 重置密码只允许生成一次性重置状态或临时密码，不在响应中泄露敏感值。
- “后台代登录”不实现成管理员伪装用户交易；如确需排查，设计为只读“用户视图快照”或生成受审计的支持会话。

## 9. 产品和行情管理模块

对应 wh-admin：

- `system/productCate`
- `system/product`
- `system/proKline`
- 产品启停
- 杠杆、手续费、点差、精度
- K 线控制记录
- 风控价格

推荐接口：

```text
GET   /api/admin/market/categories
POST  /api/admin/market/categories
PATCH /api/admin/market/categories/{categoryId}

GET   /api/admin/market/symbols
GET   /api/admin/market/symbols/{symbolId}
POST  /api/admin/market/symbols
PATCH /api/admin/market/symbols/{symbolId}
PATCH /api/admin/market/symbols/{symbolId}/status

GET   /api/admin/market/symbols/{symbolId}/events
POST  /api/admin/market/symbols/{symbolId}/price-adjustments
GET   /api/admin/market/price-adjustments
GET   /api/admin/market/status
```

现有复用：

- `market.symbols`
- `SymbolEntity`
- `SymbolService`
- `QuoteService`
- `ChartService`

扩展 `market.symbols` 字段建议：

```text
category_id
price_precision
quantity_precision
commission_rate
min_spread
max_spread
sort_order
maintenance_mode
```

新增表：

```text
market.symbol_categories
  id
  name
  code
  sort_order
  enabled
  created_at
  updated_at

market.symbol_admin_events
  id
  symbol_id
  admin_user_id
  event_type
  before_value
  after_value
  reason
  created_at

market.price_adjustments
  id
  symbol
  mode
  adjustment_type
  target_price
  starts_at
  ends_at
  status
  admin_user_id
  reason
  created_at
```

高风险约束：

- wh-admin 的 `set_kline` 和 `set_fk` 只能映射为“模拟盘行情场景”或“后台价格修正记录”。
- 真实交易环境不得用该能力隐蔽操控用户订单结果。
- 所有价格修正必须有作用范围、时间窗口、原因、操作者和审计日志。

## 10. 订单和持仓管理模块

对应 wh-admin：

- `system/gd`
- `system/position`
- 成交
- 撤单
- 平仓
- 订单导出
- 持仓查询

推荐接口：

```text
GET  /api/admin/trading/orders
GET  /api/admin/trading/orders/{orderId}
GET  /api/admin/trading/orders/{orderId}/events
POST /api/admin/trading/orders/{orderId}/cancel

GET  /api/admin/trading/trades
GET  /api/admin/trading/positions
GET  /api/admin/trading/positions/{positionId}
POST /api/admin/trading/positions/{positionId}/force-close

GET  /api/admin/trading/export/orders
GET  /api/admin/trading/export/positions
```

现有复用：

- `trading.orders`
- `trading.positions`
- `trading.trades`
- `trading.order_events`
- `OrderService`
- `PositionService`
- `OrderEventService`
- `ExecutionAdapter`

新增服务：

```text
AdminTradingService
```

职责：

- 后台订单、持仓、成交聚合查询。
- 后台撤单前检查订单状态。
- 后台强平前检查持仓状态、账户、最新报价、保证金释放。
- 通过现有领域服务执行状态变更。
- 写入订单事件和审计日志。

约束：

- 不允许 Controller 直接改 `OrderEntity.status` 或 `PositionEntity.status`。
- 后台强平必须记录 `reason`。
- 后台撤单、强平需要幂等，重复请求不能重复释放资金或重复记账。

## 11. 财务管理模块

对应 wh-admin：

- `system/memberRecharge`
- `system/memberWithdrawal`
- `system/balanceLog`
- `system/pays`
- 充值审核
- 提现审核
- 资金流水
- 支付方式

推荐接口：

```text
GET  /api/admin/finance/deposits
GET  /api/admin/finance/deposits/{depositId}
POST /api/admin/finance/deposits/{depositId}/review

GET  /api/admin/finance/withdrawals
GET  /api/admin/finance/withdrawals/{withdrawalId}
POST /api/admin/finance/withdrawals/{withdrawalId}/review

GET  /api/admin/finance/ledger
POST /api/admin/finance/adjustments

GET   /api/admin/finance/payment-methods
POST  /api/admin/finance/payment-methods
PATCH /api/admin/finance/payment-methods/{paymentMethodId}
PATCH /api/admin/finance/payment-methods/{paymentMethodId}/status
```

现有复用：

- `ledger.ledger_entries`
- `LedgerService`
- `TradingAccountEntity`
- `TradingAccountRepository`

新增表：

```text
ledger.deposit_requests
  id
  user_id
  account_id
  amount
  currency
  payment_method_id
  external_reference
  status
  submitted_at
  reviewed_by
  reviewed_at
  review_note
  created_at
  updated_at

ledger.withdrawal_requests
  id
  user_id
  account_id
  amount
  currency
  destination_payment_method_id
  status
  submitted_at
  reviewed_by
  reviewed_at
  review_note
  created_at
  updated_at

ledger.payment_methods
  id
  name
  type
  currency
  config
  enabled
  created_at
  updated_at

ledger.balance_adjustments
  id
  account_id
  admin_user_id
  amount
  currency
  direction
  reason
  ledger_entry_id
  created_at
```

资金约束：

- 充值审核通过、提现审核通过、后台余额调整都必须通过 `LedgerService` 记账。
- 不允许直接更新账户余额但不写流水。
- 后台余额调整必须有原因、审计日志和独立流水类型。
- 提现审核通过需要检查账户可用余额。

## 12. 消息、公告和内容模块

对应 wh-admin：

- `system/queueMessage`
- `system/memberMsg`
- `system/memberNotice`
- `system/notice`
- `system/news`

推荐接口：

```text
GET  /api/admin/messages
POST /api/admin/messages
GET  /api/admin/messages/{messageId}
POST /api/admin/messages/{messageId}/recipients

GET   /api/admin/announcements
POST  /api/admin/announcements
PATCH /api/admin/announcements/{announcementId}
PATCH /api/admin/announcements/{announcementId}/status

GET   /api/admin/news
POST  /api/admin/news
PATCH /api/admin/news/{newsId}
PATCH /api/admin/news/{newsId}/status
```

面向用户端后续可增加：

```text
GET  /api/messages/inbox
POST /api/messages/{messageId}/read
GET  /api/announcements
GET  /api/news
```

新增表：

```text
core.messages
  id
  sender_user_id
  title
  body
  message_type
  target_type
  status
  created_at

core.message_receipts
  id
  message_id
  recipient_user_id
  read_at
  deleted_at
  created_at

core.announcements
  id
  title
  body
  visibility
  status
  published_at
  created_by
  created_at
  updated_at

core.news_articles
  id
  title
  summary
  body
  status
  published_at
  created_by
  created_at
  updated_at
```

## 13. 系统设置、字典和日志模块

对应 wh-admin：

- `system/user`
- `system/role`
- `system/menu`
- `system/dict`
- `setting/config`
- 操作日志、登录日志、接口日志、队列日志

推荐接口：

```text
GET   /api/admin/system/settings
PATCH /api/admin/system/settings/{key}

GET   /api/admin/system/dictionaries
POST  /api/admin/system/dictionaries
PATCH /api/admin/system/dictionaries/{dictId}

GET   /api/admin/system/dictionaries/{dictId}/items
POST  /api/admin/system/dictionaries/{dictId}/items
PATCH /api/admin/system/dictionary-items/{itemId}

GET   /api/admin/audit-logs
GET   /api/admin/audit-logs/{auditLogId}
GET   /api/admin/system/login-logs
GET   /api/admin/system/api-logs
```

新增表：

```text
core.system_settings
  key
  value
  value_type
  group_code
  description
  updated_by
  updated_at

core.dictionaries
  id
  code
  name
  enabled
  created_at
  updated_at

core.dictionary_items
  id
  dictionary_id
  label
  value
  sort_order
  enabled
  created_at
  updated_at
```

说明：

- 当前已有 `audit.audit_logs`，后台操作日志优先复用。
- 登录日志和 API 日志可第二阶段补充，不阻塞核心交易后台能力。

## 14. wh-admin 功能映射

| wh-admin 功能 | 当前后端落点 | 设计处理 |
| --- | --- | --- |
| 产品分类 | `market` | 新增 `symbol_categories` |
| 产品管理 | `market.symbols` | 扩展 `AdminMarketService` |
| K 线控制 | `market/chart` | 仅作为模拟行情场景或价格修正审计 |
| 用户管理 | `auth.users` | 扩展用户详情、KYC、备注、状态 |
| 用户银行卡 | `core.user_payment_methods` | 脱敏存储和后台查询 |
| 充值 | `ledger.deposit_requests` | 审核后走 `LedgerService` |
| 提现 | `ledger.withdrawal_requests` | 审核后走 `LedgerService` |
| 订单管理 | `trading.orders` | 后台查询、撤单、事件时间线 |
| 持仓管理 | `trading.positions` | 后台查询、强平 |
| 余额日志 | `ledger.ledger_entries` | 后台分页查询 |
| 消息中心 | `core.messages` | 站内信和收件状态 |
| 公告 | `core.announcements` | 发布、下线、查询 |
| 新闻 | `core.news_articles` | 发布、下线、查询 |
| 支付配置 | `ledger.payment_methods` | 支付方式配置 |
| 后台用户 | `auth.users` | 初期复用 `ADMIN` 角色 |
| 角色权限 | `auth.admin_*` | 第二阶段细粒度权限 |
| 字典 | `core.dictionaries` | 统一状态选项 |
| 请求日志 | `audit.audit_logs` + API log | 审计优先，API 日志后补 |
| 系统设置 | `core.system_settings` | 配置分组和值 |

### 14.1 当前前端调用后端全量清单

当前前端在 `fx-trading-platform/apps/web/src` 中的真实后端调用集中在 `services/*`、`pages/trading/tradingMarketApi.ts`、`features/trading-session/*` 和 WebSocket 行情订阅。后台管理后端设计必须保护这些调用的兼容性。

| 前端来源 | 前端功能 | 后端接口或主题 | 鉴权 | 当前用途 | 后台管理影响 |
| --- | --- | --- | --- | --- | --- |
| `services/authApi.ts` | 注册 demo 用户 | `POST /api/auth/register` | 否 | demo session 首次创建用户 | 后台用户列表要能看到注册来源、角色、状态、创建时间 |
| `services/authApi.ts` | 登录 demo 用户 | `POST /api/auth/login` | 否 | 获取 `accessToken` | 后台应能查看登录状态和失败记录，后续补登录日志 |
| `backend AuthController` | 当前用户信息 | `GET /api/auth/me` | 是 | 后端已有，当前前端未调用 | 后台用户详情可复用用户身份 DTO，但不能暴露敏感字段 |
| `backend AuthController` | 刷新 token | `POST /api/auth/refresh` | 否 | 后端保留，当前返回未实现 | 管理后台上线前应决定是否实现 refresh token |
| `backend AuthController` | 退出登录 | `POST /api/auth/logout` | 是 | 后端已有空实现，当前前端未调用 | 后台强制退出和用户端退出应共享 session/token 黑名单设计 |
| `services/accountApi.ts` | 用户账户列表 | `GET /api/accounts` | 是 | demo session 查找账户 | 后台账户管理需要全局分页查询，不改变用户端只查自己的账户 |
| `services/accountApi.ts` | 账户摘要 | `GET /api/accounts/{accountId}/summary` | 是 | 账户余额、权益、保证金展示 | 后台账户详情要包含同字段和更多风险字段 |
| `services/accountApi.ts` | 创建 demo 账户 | `POST /api/accounts/demo` | 是 | demo session 无账户时创建 | 后台需能筛选 demo/live 账户，管理账户状态和杠杆 |
| `services/marketApi.ts` | 品种列表 | `GET /api/market/symbols` | 否 | legacy `TradePage` 和交易页品种源 | 后台产品管理必须控制 `enabled`、展示名、杠杆、最小/最大手数 |
| `pages/trading/tradingMarketApi.ts` | 交易页品种列表 | `GET /api/market/symbols` | 否 | `/trading` 左侧市场列表 | 后台禁用品种后，前端会过滤 `enabled=false` |
| `services/marketApi.ts` | 单品种报价 | `GET /api/market/quotes/{symbol}` | 否 | legacy `TradePage` 首屏报价 | 后台行情状态页需要能诊断报价源、时间戳、点差 |
| `pages/trading/useTradingQuotes.ts` | 多品种报价首屏 | `GET /api/market/quotes/{symbol}` | 否 | `/trading` 多品种初始报价 | 后台产品配置影响前端显示点差、价格、source |
| `services/marketApi.ts` | K 线 | `GET /api/chart/candles?symbol=&timeframe=&from=&to=` | 否 | legacy `TradePage` K 线 | 后台 K 线管理要保证 timeframe、时间范围、价格精度稳定 |
| `pages/trading/chartCandleData.ts` | K 线 | `GET /api/chart/candles?symbol=&timeframe=&from=&to=` | 否 | `/trading` 图表数据源 | 后台 K 线修正或补数不能破坏图表尺度 |
| `pages/trading/tradingMarketApi.ts` | 盘口快照 | `GET /api/market/order-book/{symbol}` | 否 | 右侧订单表首屏 | 后台行情监控要展示盘口状态；产品禁用时应停止或返回明确状态 |
| `pages/trading/tradingMarketApi.ts` | 最新成交 | `GET /api/market/trades/{symbol}?limit=40` | 否 | 右侧最新成交首屏 | 后台行情监控要能查成交模拟/数据源状态 |
| `services/marketStream.ts` | 报价推送 | `WS /ws` + `/topic/market/quotes/{symbol}` | 可带 token | 行情增量刷新、图表实时 bar | 后台行情配置变更后要驱动或影响推送 |
| `services/marketStream.ts` | 盘口推送 | `WS /ws` + `/topic/market/order-book/{symbol}` | 可带 token | 右侧订单表增量刷新 | 后台行情监控要能看到推送健康度 |
| `services/marketStream.ts` | 最新成交推送 | `WS /ws` + `/topic/market/trades/{symbol}` | 可带 token | 右侧成交增量刷新 | 后台行情监控要能看到推送健康度 |
| `services/tradingApi.ts` | 创建订单 | `POST /api/trading/orders` | 是 | 买入/卖出、Market/Limit/Stop、SL/TP | 后台订单管理必须能查询、撤单、审计下单链路 |
| `services/tradingApi.ts` | 当前用户订单 | `GET /api/trading/orders` | 是 | 底部当前/历史委托 | 后台订单列表需要全局分页、按用户/账户/品种/状态筛选 |
| `services/tradingApi.ts` | 当前账户持仓 | `GET /api/trading/positions?accountId=` | 是 | 底部当前仓位、平仓按钮 | 后台持仓列表需要全局分页、风险筛选和强平能力 |
| `services/tradingApi.ts` | 用户平仓 | `POST /api/trading/positions/{positionId}/close?accountId=` | 是 | 底部持仓 Close | 后台强平要走独立 admin endpoint，不能复用用户端身份语义 |
| `services/ledgerApi.ts` | 资金流水 | `GET /api/ledger?accountId=` | 是 | 底部资产 tab | 后台资金流水要支持全局查询、导出、关联充值/提现/订单 |
| `features/trading/services/orderApi.ts` | 本地预览下单 | 无真实后端 | 否 | 无 token 时 mock preview | 后端无需实现；文档和实现要避免把 mock 当成生产接口 |

### 14.2 当前前端页面和控件到后端契约

| 前端路由或组件 | 当前状态 | 后端依赖 | 必须保持的契约 | 后台管理要补的能力 |
| --- | --- | --- | --- | --- |
| `/` | 重定向到 `/trading` | 无 | 不需要后端 | 无 |
| `/trade` | 重定向到 `/trading` | legacy `TradePage` 文件仍存在 | 不再作为主入口 | 不为 legacy 页面单独扩接口 |
| `/trading` | 主交易终端 | 账户、行情、K 线、订单、持仓、资金、WebSocket | 所有交易端接口保持向后兼容 | 后台应能管理这些数据的来源、状态和异常 |
| `/dashboard` | Placeholder | 无 | 当前无真实调用 | 后续用户概览可接 `/api/accounts`、`/api/trading`、`/api/ledger` 聚合；后台另接 `/api/admin/dashboard/*` |
| `/markets` | Placeholder | 无 | 当前无真实调用 | 后续用户行情页复用 `/api/market/*`；后台管理接 `/api/admin/market/*` |
| `/wallet` | Placeholder | 无 | 当前无真实调用 | 用户端后续充值/提现入口接用户资金 API；后台接 `/api/admin/finance/*` |
| `/settings` | Placeholder | 无 | 当前无真实调用 | 用户偏好后续接用户设置 API；后台系统设置接 `/api/admin/system/settings` |
| `MarketSidebar` | 可用 | `GET /api/market/symbols`、报价 REST/WS | `symbol/displayName/assetClass/baseCurrency/quoteCurrency/enabled` 稳定 | 后台产品分类、排序、启停、展示名、杠杆、手数 |
| `SymbolHeader` | 可用 | 报价 map | `bid/ask/mid/spread/source/timestamp` 稳定 | 后台显示报价源、延迟、最近更新时间 |
| `ChartWorkspace` | 可用 | K 线 REST、报价 WS | candle timestamp/open/high/low/close/volume 稳定 | 后台 K 线补数、数据源状态、异常价格修正 |
| `RightTradingPanel` | 可用 | 报价、盘口、成交 REST/WS | 盘口 `bids/asks` 和成交 `price/amount/side/timestamp` 稳定 | 后台行情监控、盘口推送健康度、模拟成交开关 |
| `TradePanel` | 可用 | `POST /api/trading/orders` | `accountId/symbol/side/orderType/lots/quantity/price/requestedPrice/SL/TP/clientOrderId/idempotencyKey` | 后台配置品种参数、账户杠杆、风控限制、订单审计 |
| `TradePanel` 杠杆弹窗 | 前端本地状态 | 未提交后端 | 当前只影响 UI 文案，不影响订单 | 后台应决定账户杠杆、品种最大杠杆、用户风险等级；前端后续应从后端读 |
| `TradePanel` 策略/工具 | 显示“后续版本开放” | 无 | 当前无真实调用 | 后续可新增策略模块，不属于本次后台核心 |
| `BottomAccountPanel` 当前/历史委托 | 可用 | `GET /api/trading/orders` | `OrderResponse` 字段稳定 | 后台订单全局列表、详情、事件时间线、导出 |
| `BottomAccountPanel` 当前/历史仓位 | 可用 | `GET /api/trading/positions`、用户平仓 | `PositionResponse` 字段稳定 | 后台持仓列表、强平、风险筛选、导出 |
| `BottomAccountPanel` 资产 | 可用 | `GET /api/accounts/{id}/summary`、`GET /api/ledger` | `AccountResponse` 和 `LedgerEntry` 稳定 | 后台账户详情、余额流水、充值提现审核 |
| `BottomAccountPanel` 策略 | 本地数据结构 | 无 | 当前无真实调用 | 后续单独设计策略后端；本次只预留 |
| `BottomAccountPanel` 全部撤单 | 禁用 | 无 | 当前无真实调用 | 后续用户端可新增批量撤单；后台先设计 admin 批量撤单 |
| `BottomAccountPanel` 导出 | 禁用 | 无 | 当前无真实调用 | 后台优先实现订单/持仓/资金导出 |
| `MobileDrawer/MobileOrderSheet` | 可用 | 复用桌面调用 | 移动端不得调用不同接口 | 后台无单独适配 |
| 图表设置、主题、盘口设置 | 本地 localStorage | 无 | 当前不依赖后端 | 后续用户偏好可接用户 settings，不阻塞后台 |

### 14.3 前端已依赖 DTO 契约

前端 TypeScript 类型和 Java DTO 必须保持兼容。后台管理扩展可以增加字段，但不能删除或改变这些字段语义。

`SymbolResponse` / `SymbolItem`：

```text
symbol
displayName
assetClass
baseCurrency
quoteCurrency
minLot
maxLot
leverage
enabled
```

后台管理扩展字段：

```text
categoryId
pricePrecision
quantityPrecision
pipSize
tickSize
lotSize
spreadMarkup
commissionRate
sortOrder
maintenanceMode
updatedAt
```

`QuoteResponse` / `Quote`：

```text
type
symbol
bid
ask
mid
spread
source
timestamp
```

后台管理扩展字段：

```text
provider
providerSymbol
latencyMs
stale
lastError
```

`AccountResponse` / `AccountSummary`：

```text
id
accountType
baseCurrency
balance
equity
usedMargin
freeMargin
marginLevel
leverage
status
```

后台管理扩展字段：

```text
userId
email
phone
riskLevel
kycStatus
openPositions
pendingOrders
createdAt
updatedAt
```

`CreateOrderRequest` / `OrderPayload`：

```text
accountId
symbol
side
orderType
quantity
price
clientOrderId
lots
requestedPrice
stopLoss
takeProfit
idempotencyKey
```

后台管理必须支持查询和审计这些字段，但后台撤单、强平、补单不能直接复用用户端 `CreateOrderRequest`。

`OrderResponse`：

```text
id
accountId
symbol
side
orderType
status
lots
quantity
price
executionPrice
filledQuantity
remainingQuantity
avgFillPrice
holdAmount
holdCurrency
rejectCode
rejectMessage
createdAt
updatedAt
filledAt
canceledAt
```

前端表格当前只展示其中一部分。后台订单详情必须展示完整字段和事件时间线。

`PositionResponse`：

```text
id
symbol
side
lots
openPrice
currentPrice
floatingPnl
realizedPnl
marginHeld
status
```

后台持仓详情应额外包含：

```text
accountId
userId
openedAt
closedAt
stopLoss
takeProfit
marginLevelAtOpen
closeReason
```

`LedgerEntry`：

```text
id
accountId
entryType
amount
balanceAfter
currency
referenceType
referenceId
description
createdAt
```

当前 Java `LedgerController` 直接返回 `LedgerEntryEntity`。后台扩展时应增加 `LedgerEntryResponse`，再逐步让用户端和后台端都使用 DTO。

### 14.4 后台管理前端需要新增的后端调用面

如果后续要做后台管理前端，后台前端应只调用 `/api/admin/**`，不调用用户端接口模拟后台能力。

| 后台页面 | 后端调用 | 前端控件 | 写操作保护 |
| --- | --- | --- | --- |
| 仪表盘 | `GET /api/admin/dashboard/summary` | 统计卡片、趋势图、风险告警 | 只读 |
| 用户列表 | `GET /api/admin/users` | 搜索、状态筛选、KYC 筛选、分页表格 | 只读 |
| 用户详情 | `GET /api/admin/users/{id}` | 基础信息、账户、订单、持仓、资金 tab | 只读 |
| 用户状态 | `PATCH /api/admin/users/{id}/status` | 状态 select/switch | 必填 `reason`，写审计 |
| KYC 审核 | `POST /api/admin/users/{id}/kyc-review` | 通过/驳回、备注 | 必填 `reason/reviewNote`，写审计 |
| 用户备注 | `POST /api/admin/users/{id}/note` | 备注输入框 | 写审计 |
| 产品分类 | `GET/POST/PATCH /api/admin/market/categories` | 树/列表、排序、启停 | 写审计 |
| 产品列表 | `GET /api/admin/market/symbols` | 搜索、分类、状态、资产类型 | 只读 |
| 产品新增编辑 | `POST/PATCH /api/admin/market/symbols` | 表单、精度、杠杆、点差、手续费 | 必填 `reason`，写审计 |
| 产品启停 | `PATCH /api/admin/market/symbols/{id}/status` | switch | 必填 `reason`，写审计 |
| 行情修正 | `POST /api/admin/market/symbols/{id}/price-adjustments` | 模拟行情/修正窗口 | 高风险权限，默认生产禁用 |
| 订单列表 | `GET /api/admin/trading/orders` | 搜索、状态、用户、账户、品种、时间 | 只读 |
| 订单详情 | `GET /api/admin/trading/orders/{id}` | 字段详情、事件时间线、资金影响 | 只读 |
| 后台撤单 | `POST /api/admin/trading/orders/{id}/cancel` | 撤单按钮 | 必填 `reason/idempotencyKey`，领域服务执行 |
| 持仓列表 | `GET /api/admin/trading/positions` | 当前/历史、风险筛选、盈亏筛选 | 只读 |
| 后台强平 | `POST /api/admin/trading/positions/{id}/force-close` | 强平按钮 | 必填 `reason/idempotencyKey`，领域服务执行 |
| 充值列表 | `GET /api/admin/finance/deposits` | 状态、用户、金额、时间 | 只读 |
| 充值审核 | `POST /api/admin/finance/deposits/{id}/review` | 通过/驳回 | 必填 `reviewNote`，走 `LedgerService` |
| 提现列表 | `GET /api/admin/finance/withdrawals` | 状态、用户、金额、时间 | 只读 |
| 提现审核 | `POST /api/admin/finance/withdrawals/{id}/review` | 通过/驳回 | 检查可用余额，走 `LedgerService` |
| 余额调整 | `POST /api/admin/finance/adjustments` | 加款/扣款工单 | 双重权限，必填 `reason`，走 `LedgerService` |
| 支付方式 | `GET/POST/PATCH /api/admin/finance/payment-methods` | 表格、配置表单、启停 | 敏感配置脱敏，写审计 |
| 站内信 | `GET/POST /api/admin/messages` | 收件人、标题、正文、发送类型 | 写审计 |
| 公告 | `GET/POST/PATCH /api/admin/announcements` | 富文本、发布/下线 | 写审计 |
| 新闻 | `GET/POST/PATCH /api/admin/news` | 富文本、发布/下线 | 写审计 |
| 字典 | `GET/POST/PATCH /api/admin/system/dictionaries` | 字典和字典项 CRUD | 写审计 |
| 系统设置 | `GET/PATCH /api/admin/system/settings` | 配置分组、类型化输入 | 写审计，敏感值脱敏 |
| 审计日志 | `GET /api/admin/audit-logs` | 搜索、目标、动作、操作者、时间 | 只读，不允许删除 |
| 登录/API 日志 | `GET /api/admin/system/login-logs`、`GET /api/admin/system/api-logs` | 搜索、状态、IP、时间 | 只读 |

### 14.5 wh-admin 全功能覆盖矩阵

| wh-admin 页面或能力 | 发现的接口或组件 | 本项目后端设计 | 覆盖级别 |
| --- | --- | --- | --- |
| 首页/欢迎页 | dashboard welcome components | `GET /api/admin/dashboard/summary` | 必做 |
| 仪表盘统计 | dashboard statistics components | dashboard trading/user/finance/risk 聚合接口 | 必做 |
| 个人信息 | `views/userCenter` | `GET /api/auth/me`、后续 `PATCH /api/auth/me` | 第二阶段 |
| 登录验证码 | `system/captcha` | 当前不需要；如后台登录需要验证码，新增 captcha service | 第二阶段 |
| 消息中心收件箱 | `system/queueMessage/receiveList` | `GET /api/messages/inbox` 用户端，`GET /api/admin/messages` 后台 | 必做 |
| 消息中心已发送 | `system/queueMessage/sendList` | `GET /api/admin/messages?box=sent` | 必做 |
| 发私信 | `system/queueMessage/sendPrivateMessage` | `POST /api/admin/messages` | 必做 |
| 删除消息 | `system/queueMessage/deletes` | 用户端软删除 receipt；后台不物理删除 | 必做 |
| 标记已读 | `system/queueMessage/updateReadStatus` | `POST /api/messages/{id}/read` | 必做 |
| 产品分类 | `system/productCate` | `market.symbol_categories` + admin category API | 必做 |
| 产品列表 | `system/product/index` | `market.symbols` + admin symbols API | 必做 |
| 新增/编辑产品 | `system/product/save/update` | `POST/PATCH /api/admin/market/symbols` | 必做 |
| 删除/回收产品 | `system/product/delete/recycle/recovery/realDelete` | 不做真实删除，改为启停和归档 | 等价替代 |
| 产品状态 | `system/product/changeStatus` | `PATCH /api/admin/market/symbols/{id}/status` | 必做 |
| 数值自增自减 | `system/product/numberOperation` | 不提供通用任意数值接口，改为字段级编辑 | 等价替代 |
| K 线控制 | `system/product/set_kline`、`system/proKline` | 模拟盘 price adjustment，生产默认禁用 | 受限实现 |
| 风控价格 | `system/product/set_fk` | 报价异常修正/风控限制，不隐蔽操控 | 受限实现 |
| 用户管理 | `system/member/index` | `GET /api/admin/users` | 必做 |
| 新增/编辑用户 | `system/member/save/update` | 后台创建支持用户可选；普通用户注册仍走 `/api/auth/register` | 部分实现 |
| 删除用户 | `system/member/delete` | 不物理删除，改为禁用/冻结 | 等价替代 |
| 修改密码 | `system/member/edit_pwd` | `POST /api/admin/users/{id}/reset-password` | 必做 |
| 实名审核 | `system/member/edit_realname` | `POST /api/admin/users/{id}/kyc-review` | 必做 |
| 修改余额 | `system/member/edit_money` | `POST /api/admin/finance/adjustments`，必须写 ledger | 必做 |
| 一键控盈利 | `system/member/yl` | 不实现；改为风险等级/交易限制 | 替代 |
| 一键控输 | `system/member/ks` | 不实现；改为风险等级/交易限制 | 替代 |
| 一键控正常 | `system/member/zc` | `PATCH /api/admin/users/{id}/risk-level` | 替代 |
| 踢下线 | `system/member/xiaxian` | `POST /api/admin/users/{id}/force-logout` | 必做 |
| 后台代登录 | `system/member/ht_login` | 不代用户交易；改只读支持视图 | 替代 |
| 用户备注 | `system/member/bz` | `POST /api/admin/users/{id}/note` | 必做 |
| 用户银行卡/钱包 | `system/memberBank` | `core.user_payment_methods` | 必做 |
| 充值订单 | `system/memberRecharge` | `ledger.deposit_requests` | 必做 |
| 充值审核 | `shenhe/edit_statuss` | `POST /api/admin/finance/deposits/{id}/review` | 必做 |
| 提现订单 | `system/memberWithdrawal` | `ledger.withdrawal_requests` | 必做 |
| 提现审核 | `edit_statuss` | `POST /api/admin/finance/withdrawals/{id}/review` | 必做 |
| 订单管理/挂单 | `system/gd/index` | `GET /api/admin/trading/orders` | 必做 |
| 订单成交 | `system/gd/cj` | 正常成交走 execution；后台人工成交仅限测试/模拟并审计 | 受限实现 |
| 平仓 | `system/gd/pc` | `POST /api/admin/trading/positions/{id}/force-close` | 必做 |
| 撤单 | `system/gd/cd` | `POST /api/admin/trading/orders/{id}/cancel` | 必做 |
| 订单导出 | `system/gd/export` | `GET /api/admin/trading/export/orders` | 必做 |
| 持仓列表 | `system/position` | `GET /api/admin/trading/positions` | 必做 |
| 余额日志 | `system/balanceLog` | `GET /api/admin/finance/ledger` | 必做 |
| 验证码记录 | `system/sendCode` | `core.verification_codes` 或登录/通知日志 | 第二阶段 |
| 会员消息 | `system/memberMsg` | `core.messages` | 必做 |
| 会员通知 | `system/memberNotice` | `core.announcements` 或 targeted notice | 必做 |
| 会员持仓控制 | `system/memberPosition` | 风险限制、强平、禁止开仓，不做隐蔽控制 | 受限实现 |
| 支付配置 | `system/pays` | `ledger.payment_methods` | 必做 |
| 公告列表 | `system/notice` | `core.announcements` | 必做 |
| 新闻列表 | `system/news` | `core.news_articles` | 必做 |
| 后台用户 | `system/user` | `auth.users` + admin role/permission 表 | 必做 |
| 后台用户清缓存 | `system/user/clearCache` | 如使用 Redis session/cache，再设计 cache evict | 第二阶段 |
| 初始化用户密码 | `system/user/initUserPassword` | `POST /api/admin/users/{id}/reset-password` | 必做 |
| 修改个人资料 | `system/user/updateInfo` | `PATCH /api/auth/me` 或 admin profile API | 第二阶段 |
| 修改个人密码 | `system/user/modifyPassword` | `POST /api/auth/change-password` | 第二阶段 |
| 角色管理 | `system/role` | `auth.admin_roles` | 必做 |
| 菜单权限 | `system/menu` | 后台前端菜单配置或权限驱动菜单 | 第二阶段 |
| 数据权限 | role data permission | 暂按全局 admin，后续按部门/范围 | 第二阶段 |
| 部门管理 | `system/dept` | 非交易核心，可选 `auth.admin_departments` | 后置 |
| 岗位管理 | `system/post` | 非交易核心，可选 `auth.admin_posts` | 后置 |
| 字典 | `system/dict`、`dataList` | `core.dictionaries`、`core.dictionary_items` | 必做 |
| 系统配置 | `setting/config` | `core.system_settings` | 必做 |
| 定时任务 | `setting/crontab` | Spring Scheduler job registry + job logs | 后置 |
| 数据源管理 | `setting/datasource` | 不开放线上 DSN 管理；仅配置文件/运维管理 | 不做 |
| 代码生成 | `setting/code` | 不属于产品运行能力 | 不做 |
| 缓存监控 | cache monitor | Redis/actuator 只读监控 | 第二阶段 |
| 在线用户 | online users | token/session registry + force logout | 第二阶段 |
| 服务器监控 | server monitor | Actuator health/info/metrics | 第二阶段 |
| 操作日志 | `system/logs/operLog` | `audit.audit_logs` | 必做 |
| 登录日志 | `system/logs/loginLog` | `audit.login_logs` 或 auth event log | 第二阶段 |
| API 日志 | `system/logs/apiLog` | request log filter + async persistence | 第二阶段 |
| 队列日志 | `system/logs/queueLog` | 当前无队列则不做；引入队列后补 | 后置 |
| 接口文档 | `/mineDoc`、APP ID/APP SECRET、签名算法说明 | Springdoc OpenAPI `/swagger-ui`、`/v3/api-docs`；开放平台签名不纳入交易后台第一阶段 | 已有基础 |

### 14.6 前端适配优先级

后台后端实现应按前端真实依赖排序，避免先做看起来完整但交易页用不到的后台能力。

第一优先级：保护现有交易页。

- `/api/market/symbols`
- `/api/market/quotes/{symbol}`
- `/api/chart/candles`
- `/api/market/order-book/{symbol}`
- `/api/market/trades/{symbol}`
- `/ws` 三类 market topic
- `/api/auth/login`
- `/api/auth/register`
- `/api/accounts`
- `/api/accounts/{accountId}/summary`
- `/api/accounts/demo`
- `/api/trading/orders`
- `/api/trading/positions`
- `/api/ledger`

第二优先级：后台只读管理面。

- admin users/accounts/symbols/orders/positions/trades/ledger/audit/dashboard
- 分页、筛选、排序、DTO 脱敏

第三优先级：后台核心写操作。

- 用户状态/KYC/备注
- 产品启停/参数
- 撤单/强平
- 充值/提现审核
- 余额调整工单

第四优先级：内容和系统能力。

- 消息、公告、新闻
- 字典、系统设置、角色权限
- 登录日志、API 日志、监控

## 15. 高风险功能替代设计

wh-admin 中存在一些后台交易系统常见但风险很高的功能：

- 一键控盈利
- 一键控输
- 风控价格
- 设置 K 线涨跌
- 后台代登录
- 直接修改余额

本系统不设计隐蔽操控用户交易结果的能力。

替代方案：

- 一键控盈利/控输：改为 `risk_level`、交易限制、只减仓、禁止开仓、人工复核标记。
- 风控价格：改为模拟盘行情场景、报价异常修正、价格源切换记录。
- 设置 K 线涨跌：只用于 demo 数据或测试环境，生产环境默认禁用。
- 后台代登录：改为只读用户视图快照，不允许代用户下单、平仓、改密码。
- 修改余额：改为资金调整工单，必须写 ledger、审计、原因和管理员。

## 16. 事务和审计

所有后台写操作统一模式：

```text
1. 校验管理员权限
2. 校验请求参数
3. 加载目标对象
4. 校验当前状态是否允许操作
5. 调用领域服务执行变更
6. 写领域事件或资金流水
7. 写审计日志
8. 返回 DTO
```

审计字段：

```text
actor_user_id
action
target_type
target_id
request_id
details
created_at
```

`details` 建议包含：

```text
reason
before
after
request
result
```

## 17. 分阶段实施计划

第一阶段：后台基础查询面

- 拆分现有 `AdminController`。
- 增加分页响应、查询 DTO、用户/账户/订单/持仓/资金/品种只读后台接口。
- 补充测试，确保不直接暴露 Entity。

第二阶段：核心后台写操作

- 用户状态、KYC 审核、备注。
- 品种启停、品种参数编辑。
- 后台撤单、强平。
- 审计日志覆盖所有写操作。

第三阶段：财务工单

- 充值申请、提现申请、审核。
- 支付方式配置。
- 后台资金调整工单。
- 资金操作全链路通过 `LedgerService`。

第四阶段：消息和内容

- 站内信、系统通知。
- 公告、新闻。
- 用户端只读消息接口。

第五阶段：系统管理

- 字典、系统设置。
- 细粒度 RBAC。
- 登录日志、API 日志。
- 导出任务和异步任务日志。

## 18. 测试策略

架构测试：

- `AdminController` 不直接返回 `UserEntity`、`OrderEntity`、`PositionEntity`、`TradingAccountEntity`。
- `/api/admin/**` 必须要求 `ADMIN`。
- 后台资金变更必须经过 `LedgerService`。
- 后台订单、持仓写操作不能绕过领域服务。

服务测试：

- 用户状态更新写审计。
- KYC 审核状态流转正确。
- 品种启停影响 `/api/market/symbols`。
- 撤单幂等。
- 强平只释放一次保证金。
- 充值审核通过生成 ledger。
- 提现审核通过扣减余额并生成 ledger。

控制器测试：

- 非管理员访问 `/api/admin/**` 返回拒绝。
- 分页、筛选、排序参数生效。
- 写操作缺少 `reason` 返回校验错误。
- 响应不包含敏感字段。
- 前端当前使用的所有用户端接口保持兼容：
  - `POST /api/auth/register`
  - `POST /api/auth/login`
  - `GET /api/accounts`
  - `GET /api/accounts/{accountId}/summary`
  - `POST /api/accounts/demo`
  - `GET /api/market/symbols`
  - `GET /api/market/quotes/{symbol}`
  - `GET /api/chart/candles`
  - `GET /api/market/order-book/{symbol}`
  - `GET /api/market/trades/{symbol}`
  - `POST /api/trading/orders`
  - `GET /api/trading/orders`
  - `GET /api/trading/positions`
  - `POST /api/trading/positions/{positionId}/close`
  - `GET /api/ledger`
- WebSocket `/ws` 能继续订阅：
  - `/topic/market/quotes/{symbol}`
  - `/topic/market/order-book/{symbol}`
  - `/topic/market/trades/{symbol}`

迁移测试：

- Flyway clean/migrate 后应用可启动。
- `spring.jpa.hibernate.ddl-auto=validate` 通过。

## 19. 成功标准

- 后台管理后端功能覆盖 wh-admin 文档中的主要业务域。
- 现有用户交易端接口不破坏。
- 所有后台接口位于 `/api/admin/**`。
- 后台核心写操作全部有审计日志。
- 用户、资金、订单、持仓敏感操作不直接改表。
- 当前前端真实调用的 REST 和 WebSocket 接口全部在文档中有对应后端契约。
- wh-admin 分析文档中的页面和功能全部在覆盖矩阵中有处理结论：必做、受限实现、等价替代、后置或不做。
- 后台管理前端未来需要的 `/api/admin/**` 调用面有页面、控件、接口和写操作保护说明。
- Maven test 通过。
- 架构测试覆盖后台 DTO、权限、审计和领域边界。

## 20. 自审结果

- 未保留未完成标记。
- 设计没有要求复制 wh-admin 的 `system/*` 路径。
- 设计保持当前 Spring Boot 分层、Flyway、JWT、`ApiResponse` 风格。
- 高风险功能已转成可审计、可授权、可解释的合规后台能力。
- 实施范围可以按阶段拆分，不需要一次性完成所有模块。
- 已对照当前前端真实后端调用补充 REST、WebSocket、页面控件和 DTO 契约。
- 已对 wh-admin 全部主要页面补充覆盖级别，避免遗漏功能。
