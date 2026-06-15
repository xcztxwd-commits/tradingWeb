# 后台管理系统全链路测试结果

日期：2026-06-08  
目标：验证当前 `fx-trading-platform` 后台管理系统是否完整覆盖 `docs/wh-admin-fx-backoffice-analysis.md` 中的功能，并按前端、后端、前后端联调逐步测试。

## 1. 测试结论

当前实现已经完成一套可运行的后台管理基础闭环：

- 后端 admin 读写 API、普通用户交易 API、Flyway 临时库启动、JWT 管理员登录、审计写入均通过。
- `apps/web` 的 `/admin` 后台工作台桌面和移动端通过浏览器 QA。
- `apps/admin` 独立后台入口完成运行时 API 修复后通过登录、读取和截图 QA。
- 本轮测试未访问和修改线上 `https://wh-admin.lonbmau.vip/#/message` 数据；对照基准来自本地已整理的 wh-admin 分析文档。

但对照 wh-admin 全量功能，当前系统仍不是 100% 完整复刻。现有实现覆盖了用户、账户、订单、持仓、流水、品种、支付方式、消息、公告、字典、设置、审计的基础管理能力；缺少完整产品 CRUD、充值/提现申请审核流、用户银行卡/钱包 CRUD、RBAC、系统监控、操作/登录/API/队列日志、代码生成、数据源、定时任务等后台模块。

## 2. 测试环境

- 工作目录：`C:\Users\User\Desktop\workspace\tradingView-KlineChart`
- 应用目录：`fx-trading-platform`
- 后端端口：`18080`
- `apps/web` 测试端口：`5182`
- `apps/admin` 测试端口：`5186`
- 临时数据库：`fx_platform_codex_admin_e2e`
- 数据库：本机 PostgreSQL 18.4
- 管理员账号：`admin-e2e@example.com`
- 管理员密码：`Admin123456!`
- 普通用户和业务数据：测试脚本动态生成

测试结束后已停止本轮启动的 `18080/5182/5186` helper 进程。

## 3. 已执行命令和结果

| 层级 | 命令或动作 | 结果 |
| --- | --- | --- |
| 后端单元测试 | `mvn.cmd -q test` | 通过 |
| 前端单元测试 | `npm.cmd --workspace apps/web run test` | 169 个测试通过 |
| `apps/web` 构建 | `npm.cmd --workspace apps/web run build` | 通过 |
| `apps/admin` 构建 | `npm.cmd --workspace apps/admin run build` | 通过 |
| 后端打包 | `mvn.cmd -q -DskipTests package` | 通过 |
| 临时库迁移 | 后端启动时 Flyway 执行 V1-V16 | 通过 |
| 健康检查 | `GET /actuator/health` | `UP` |
| HTTP 联调 | `npm.cmd run smoke:admin` | 14 个核心步骤通过 |
| 浏览器 QA | Chrome CDP headless | 3 个页面状态通过，无 console/network 错误 |

## 4. 本轮发现并修复的问题

| 问题 | 原因 | 修复 |
| --- | --- | --- |
| 前端审计日志路径不匹配 | 前端使用 `/api/admin/audit/logs`，后端实际是 `/api/admin/audit-logs` | 修复 `apps/web/src/services/adminApi.ts`，补充 API 契约测试 |
| `PUT` 后台配置接口浏览器预检失败风险 | CORS methods 未包含 `PUT` | 修复 `SecurityConfig`，补充架构测试断言 |
| `apps/admin` 运行时请求旧 API | 独立后台仍请求 `/api/admin/orders`、`/api/admin/ledger` 等旧路径，并假设返回数组 | 改为新分页接口 `/api/admin/trading/orders`、`/api/admin/finance/ledger` 等，并按 `items` 解包 |
| `apps/admin` 状态更新旧契约 | 旧代码用 query string 修改状态 | 改为 `PATCH /api/admin/users/{id}/status` + `{status, reason}` |
| `smoke:admin` token 状态传递错误 | 步骤返回值只保留 userId，后续请求没有 Authorization | 脚本内部保存 token，输出不打印 token |
| `smoke:admin` 限价单价格缺失 | quoteMid 存在 `context`，下单读取了步骤返回值，导致 `requestedPrice` 被省略 | 下单改用 `context.quoteMid` 并转字符串 |

## 5. HTTP 联调覆盖明细

`scripts/smoke-admin.mjs` 已覆盖以下链路：

1. `GET /actuator/health`
2. 管理员 `POST /api/auth/login`
3. 普通用户 `POST /api/auth/register`
4. 普通用户 `GET /api/auth/me`
5. `GET /api/accounts`
6. `GET /api/accounts/{accountId}/summary`
7. `GET /api/market/symbols`
8. `GET /api/market/quotes/{symbol}`
9. `GET /api/chart/candles`
10. `GET /api/market/order-book/{symbol}`
11. `GET /api/market/trades/{symbol}`
12. `GET /api/market/status`
13. `POST /api/trading/orders`
14. `GET /api/trading/orders`
15. `GET /api/trading/positions`
16. `GET /api/ledger`
17. `GET /api/admin/dashboard/summary`
18. `GET /api/admin/users`
19. `GET /api/admin/accounts`
20. `GET /api/admin/trading/orders`
21. `GET /api/admin/trading/positions`
22. `GET /api/admin/finance/ledger`
23. `GET /api/admin/market/symbols`
24. `GET /api/admin/finance/payment-methods`
25. `GET /api/admin/content/messages`
26. `GET /api/admin/content/articles`
27. `GET /api/admin/config/dictionaries`
28. `GET /api/admin/config/settings`
29. `GET /api/admin/audit-logs`
30. `PATCH /api/admin/users/{userId}/status`
31. `POST /api/admin/users/{userId}/kyc-review`
32. `PATCH /api/admin/users/{userId}/risk-level`
33. `POST /api/admin/users/{userId}/notes`
34. `POST /api/admin/users/{userId}/force-logout`
35. `PATCH /api/admin/market/symbols/{symbolId}/status`
36. `POST /api/admin/market/symbols/{symbolId}/price-adjustments`
37. `POST /api/admin/trading/orders/{orderId}/cancel`
38. `POST /api/admin/finance/payment-methods`
39. `PATCH /api/admin/finance/payment-methods/{paymentMethodId}`
40. `POST /api/admin/finance/accounts/{accountId}/deposit`
41. `POST /api/admin/finance/accounts/{accountId}/withdraw`
42. `POST /api/admin/finance/accounts/{accountId}/adjustments`
43. `POST /api/admin/content/messages`
44. `POST /api/admin/content/articles`
45. `PUT /api/admin/config/dictionaries`
46. `PUT /api/admin/config/settings`
47. `OPTIONS /api/admin/config/settings`
48. `GET /api/admin/audit-logs`

最终 HTTP 联调结果：14 个分组步骤全部 PASS，审计日志包含用户、订单、财务、支付方式、内容、字典、设置等写操作。

## 6. 浏览器 QA 结果

浏览器测试使用本地 Chrome DevTools Protocol，未引入新依赖。

| 页面 | 地址 | 结果 | 截图 |
| --- | --- | --- | --- |
| `apps/web` 后台桌面 | `http://127.0.0.1:5182/admin` | 通过；7 个表单、27 个按钮、26 次 admin API 请求，无错误 | `fx-trading-platform/apps/web/qa-artifacts/admin-e2e-desktop.png` |
| `apps/web` 后台移动端 | `http://127.0.0.1:5182/admin` | 通过；移动布局可用，无错误 | `fx-trading-platform/apps/web/qa-artifacts/admin-e2e-mobile.png` |
| `apps/admin` 独立后台 | `http://127.0.0.1:5186/` | 通过；登录后 3 张表、16 个按钮、8 次 admin API 请求，无错误 | `fx-trading-platform/apps/web/qa-artifacts/standalone-admin-e2e-desktop.png` |

浏览器 QA 报告文件：`fx-trading-platform/runtime/logs/admin-browser-qa.json`。

## 7. 对照 wh-admin 的功能完成度

### 7.1 已完成或基本完成

| wh-admin 功能域 | 当前实现 | 测试状态 |
| --- | --- | --- |
| 首页/仪表盘 | `GET /api/admin/dashboard/summary`，前端统计卡片 | 已联调 |
| 用户管理基础列表 | `GET /api/admin/users` | 已联调 |
| 用户状态/KYC/风险/备注/踢下线审计 | `status`、`kyc-review`、`risk-level`、`notes`、`force-logout` | 已联调 |
| 账户列表 | `GET /api/admin/accounts` | 已联调 |
| 订单列表和后台撤单 | `GET /api/admin/trading/orders`、`cancel` | 已联调 |
| 持仓列表 | `GET /api/admin/trading/positions` | 已联调 |
| 资金流水 | `GET /api/admin/finance/ledger` | 已联调 |
| 后台人工入金/出金/余额调整 | finance account commands | 已联调 |
| 支付方式配置基础增改查 | payment methods | 已联调 |
| 产品/品种基础列表和启停 | `GET /api/admin/market/symbols`、`status` | 已联调 |
| 显式价格调整记录 | `price-adjustments`，拒绝隐藏控价模式 | 已联调 |
| 消息创建/列表 | `content/messages` | 已联调 |
| 公告/新闻创建/列表 | `content/articles` | 已联调 |
| 字典项 upsert/list | `config/dictionaries` | 已联调 |
| 系统设置 upsert/list | `config/settings` | 已联调 |
| 后台审计日志 | `GET /api/admin/audit-logs` | 已联调 |
| 普通用户交易主链路 | 注册、账户、行情、K 线、盘口、成交、挂单、订单、持仓、流水 | 已联调 |

### 7.2 部分完成

| wh-admin 功能域 | 当前差距 |
| --- | --- |
| 产品管理 | 当前只有品种列表、启停、显式价格调整；缺少产品新增、编辑、删除、分类、排序、杠杆/点差等完整字段维护。 |
| 产品分类 | wh-admin 有 `productCate`；当前无分类 CRUD 和前端入口。 |
| K 线控制记录 | 当前只实现审计化、显式价格调整；未实现隐藏控涨跌或 K 线控制，且不建议照搬。 |
| 用户管理 | 已有状态/KYC/风险/备注/踢下线；缺少用户资料全字段、头像、客服 URL、密码重置、批量操作、搜索筛选、导出。 |
| 订单管理 | 已有列表和撤单；缺少订单详情、成交明细、后台强制成交/补单、复杂筛选、导出。 |
| 持仓管理 | 已有列表和强平接口；本轮没有构造真实持仓做前后端强平联调，缺少详情、导出和完整筛选。 |
| 财务 | 已有人工资金操作和流水；缺少充值申请、提现申请、审核、驳回、凭证、用户端申请入口。 |
| 支付方式 | 已有增改查；缺少删除、更多渠道字段、状态筛选、前端用户充值页消费。 |
| 消息/公告/新闻 | 已有后台创建和列表；缺少富文本上传、更新、删除、发布撤回、目标用户选择器、用户端收件箱读/删。 |
| 字典/设置 | 已有基础 upsert；缺少 wh-admin 的字典类型/字典数据分离、缓存刷新、完整系统配置分组。 |
| 前端页面 | `apps/web /admin` 是一个密集工作台，非 wh-admin 那种每个模块独立完整 CRUD 子页面。 |
| `apps/admin` 独立后台 | 已能登录和展示关键摘要，但功能少于 `apps/web /admin`，目前更像运维概览页。 |

### 7.3 未完成

| wh-admin 功能域 | 未完成内容 |
| --- | --- |
| 用户银行卡/钱包 | 后台钱包/银行卡 CRUD、币种/钱包类型字典、脱敏显示、用户端维护入口。 |
| 充值订单审核 | 充值申请、审核、驳回、到账、凭证、审核备注。 |
| 提现订单审核 | 提现申请、审核、驳回、打款、审核备注、用户提现备注。 |
| 验证码发送记录 | 短信/邮箱验证码发送日志查询。 |
| 会员通知和会员持仓控制 | 会员通知配置、单控持仓控制等高风险后台项未实现。 |
| 后台用户管理 | 后台操作员 CRUD、重置密码、设置首页、部门和岗位绑定。 |
| 角色/RBAC | 角色、菜单权限、按钮权限、数据权限、超级管理员保护。 |
| 菜单管理 | 动态菜单、按钮生成、外链、隐藏、重定向、组件路径。 |
| 部门/岗位 | 部门树、岗位、部门领导列表。 |
| 定时任务 | crontab、立即执行、任务日志。 |
| 数据源 | 多数据源配置和 DSN 管理。 |
| 代码生成 | 装载表、预览、同步、生成代码。 |
| 监控 | 缓存监控、在线用户、服务指标、Redis key 管理。 |
| 日志中心 | 操作日志、登录日志、API 日志、请求日志、队列日志的完整查询和详情。 |
| 导入导出 | wh-admin 多模块常见导出/批量操作当前未实现。 |

### 7.4 不建议照搬的功能

以下 wh-admin 能力属于高风险交易后台能力，不应按原语义直接实现到当前系统：

- 一键控盈利、一键控输、一键控正常。
- 隐藏 K 线/价格控制。
- 绕过订单撮合和风控的后台强制成交。
- 无审计余额改动。
- 删除审计日志或不可追踪的后台操作。
- 后台直接模拟用户登录且无二次验证和审计。

当前实现采用更安全的替代方向：显式价格调整记录、后台资金操作审计、订单撤单审计、用户状态和风控变更审计。

## 8. 后续执行流程方案

### 阶段 1：产品和行情后台完整化

1. 后端新增产品分类 CRUD、产品 CRUD、品种详情、启停、排序、杠杆、点差、最小/最大手数。
2. 前端拆分产品分类页、产品列表页、产品编辑弹窗、产品状态操作。
3. 测试：controller/service 单元测试、API 契约测试、产品新增到交易页行情列表的联调测试。

### 阶段 2：财务申请和审核流

1. 后端新增充值申请、提现申请、审核、驳回、完成、凭证字段、审核备注。
2. 前端新增充值订单、提现订单、审核弹窗、流水关联详情。
3. 用户端新增受限申请入口，后台只做审核。
4. 测试：用户提交申请 -> 后台审核 -> 账户余额/流水变化 -> 审计日志完整。

### 阶段 3：用户资料、银行卡和钱包

1. 后端新增用户资料扩展、银行卡/钱包表、脱敏 DTO、状态流转。
2. 前端新增用户详情页、钱包/银行卡页、备注时间线。
3. 测试：用户创建钱包 -> 后台查看脱敏数据 -> 后台禁用/备注 -> 审计。

### 阶段 4：交易后台增强

1. 补订单详情、成交明细、事件时间线、筛选、导出。
2. 补持仓详情、强平联调数据、强平前置校验和二次确认字段。
3. 不实现隐藏控输赢；如确需模拟场景，只做显式、可审计、可撤销的模拟账户功能。
4. 测试：挂单、撤单、成交、持仓、强平、流水、审计完整链路。

### 阶段 5：内容中心和用户收件箱

1. 后端补消息更新、删除、发布/撤回、用户收件箱、已读/删除状态。
2. 前端补后台内容列表详情和用户端消息中心。
3. 测试：后台发消息 -> 指定用户收到 -> 已读 -> 后台审计。

### 阶段 6：RBAC 和系统管理

1. 后端新增后台用户、角色、菜单、按钮权限、部门、岗位、数据权限。
2. 前端新增权限模块页面，按权限动态控制菜单和按钮。
3. 测试：不同角色登录看到不同菜单，越权 API 返回 403，超级管理员不可禁用。

### 阶段 7：日志、监控和运维工具

1. 新增操作日志、登录日志、API 日志、队列日志、在线用户、缓存监控、定时任务。
2. 前端按 wh-admin 模块拆页。
3. 测试：每个后台写操作、登录、失败请求都能被对应日志页查询。

## 9. 当前可复测入口

1. 后端测试：`cd fx-trading-platform/backend; mvn.cmd -q test`
2. 前端测试：`cd fx-trading-platform; npm.cmd --workspace apps/web run test`
3. 前端构建：`npm.cmd --workspace apps/web run build`
4. 独立后台构建：`npm.cmd --workspace apps/admin run build`
5. 联调脚本：启动后端后执行 `API_BASE_URL=http://127.0.0.1:18080 ADMIN_SMOKE_EMAIL=admin-e2e@example.com ADMIN_SMOKE_PASSWORD=Admin123456! npm.cmd run smoke:admin`

测试方案文档：`docs/superpowers/plans/2026-06-08-admin-full-stack-test-plan.md`。
