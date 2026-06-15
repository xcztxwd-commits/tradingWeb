# 后台管理系统全链路测试方案

日期：2026-06-08  
范围：`fx-trading-platform` 后端、`apps/web` 前端后台页、`apps/admin` 独立后台入口、前后端 API 链路、与 `docs/wh-admin-fx-backoffice-analysis.md` 的功能覆盖对照。

## 1. 测试目标

1. 验证现有后台管理后端接口是否能支撑前端后台页面调用。
2. 验证普通用户链路和后台管理链路能在同一个临时数据库中形成闭环。
3. 验证前端 API 契约、前端构建、后端单元测试和真实 HTTP 联调均可通过。
4. 对照 wh-admin 分析文档，标记已完成、部分完成、未完成和不建议照搬的高风险能力。
5. 测试失败时按“复现 -> 定位 -> 最小修复 -> 回归”的流程处理。

## 2. 数据策略

使用本地 PostgreSQL 临时库：

- 数据库：`fx_platform_codex_admin_e2e`
- 管理员：通过后端启动参数启用 `ADMIN_BOOTSTRAP_ENABLED=true`
- 管理员账号：`admin-e2e@example.com`
- 管理员密码：`Admin123456!`
- 普通用户：脚本动态生成 `admin-smoke-user+<runId>@example.com`
- 行情、品种、K 线：使用 Flyway seed 数据
- 写操作：只写入临时库，不访问线上 `https://wh-admin.lonbmau.vip/#/message`

## 3. 测试矩阵

| 层级 | 检查项 | 命令或动作 | 通过标准 |
| --- | --- | --- | --- |
| 后端单元测试 | service、DTO、架构规则 | `mvn.cmd test` | 全部测试通过 |
| 后端契约测试 | CORS、admin 路由、DTO 路径 | `ArchitectureRulesTest` 和 HTTP smoke | admin `PUT` 接口预检通过，接口路径与前端一致 |
| 前端单元测试 | `apps/web` 服务契约、组件逻辑 | `npm.cmd --workspace apps/web run test` | 所有 node test 通过 |
| 前端构建 | `apps/web` 生产构建 | `npm.cmd --workspace apps/web run build` | TypeScript 和 Vite build 成功 |
| 独立后台构建 | `apps/admin` 生产构建 | `npm.cmd --workspace apps/admin run build` | TypeScript 和 Vite build 成功；若 API 契约过期，记录为功能缺口 |
| 后端启动 | 临时 PostgreSQL + Flyway | `mvn.cmd spring-boot:run` | `/actuator/health` 为 `UP` |
| 普通用户链路 | 注册、登录、账户、行情、下单、订单、持仓、流水 | `npm.cmd run smoke:admin` | 每一步 HTTP 成功，数据可回查 |
| 后台读取链路 | dashboard、users、accounts、orders、positions、ledger、symbols、payment methods、content、config、audit logs | `npm.cmd run smoke:admin` | 所有分页结构正确，新增数据可被后台查到 |
| 后台写入链路 | 用户状态、KYC、风险、备注、强制退出、品种状态、价格调整、撤单、入金、出金、余额调整、支付方式、消息、公告、字典、设置 | `npm.cmd run smoke:admin` | 写入成功且审计日志包含对应动作 |
| 浏览器 QA | `/admin` 页面桌面和移动端 | Browser/Playwright 截图与控制台检查 | 页面可打开，无明显布局破裂，无关键 console error |
| wh-admin 覆盖 | 本地实现 vs `wh-admin-fx-backoffice-analysis.md` | 人工矩阵检查 + 代码/接口证据 | 输出已完成/部分完成/未完成列表 |

## 4. 逐步执行流程

### 4.1 静态与契约准备

1. 读取 `docs/wh-admin-fx-backoffice-analysis.md` 和 `docs/superpowers/specs/2026-06-08-admin-backend-design.md`。
2. 检查前端 `adminApi.ts` 是否覆盖后端 `/api/admin/**` 实际路径。
3. 检查后端 `SecurityConfig` 是否允许 `GET/POST/PUT/PATCH/DELETE/OPTIONS`。
4. 修复发现的路径或 CORS 契约错误后，先跑最小测试，再继续全量测试。

### 4.2 后端测试

1. 执行 `mvn.cmd test`。
2. 若失败，读取 surefire 报告和失败堆栈。
3. 按失败模块定位最小代码点。
4. 修复后先执行目标测试，再执行全量测试。

### 4.3 前端测试

1. 执行 `npm.cmd --workspace apps/web run test`。
2. 执行 `npm.cmd --workspace apps/web run build`。
3. 执行 `npm.cmd --workspace apps/admin run build`。
4. 若独立后台入口使用旧 API，记录失败点并按现有新后端 API 做最小兼容修复，或在结果中标记为未完成入口。

### 4.4 后端临时库启动

1. 重建 PostgreSQL 临时库 `fx_platform_codex_admin_e2e`。
2. 使用 JDK 21、Maven 3.9.9、临时库连接和 admin bootstrap 参数启动后端到 `18080`。
3. 轮询 `/actuator/health`，直到 `UP` 或超时。
4. 若启动失败，读取 `runtime/logs/codex-e2e-18080.err.log` 和 `.out.log`。

### 4.5 HTTP 联调

执行 `API_BASE_URL=http://127.0.0.1:18080 ADMIN_SMOKE_EMAIL=admin-e2e@example.com ADMIN_SMOKE_PASSWORD=Admin123456! npm.cmd run smoke:admin`。

脚本逐步验证：

1. `GET /actuator/health`
2. `POST /api/auth/login`
3. `POST /api/auth/register`
4. `GET /api/auth/me`
5. `GET /api/accounts`
6. `POST /api/accounts/demo`
7. `GET /api/accounts/{accountId}/summary`
8. `GET /api/market/symbols`
9. `GET /api/market/quotes/{symbol}`
10. `GET /api/chart/candles`
11. `GET /api/market/order-book/{symbol}`
12. `GET /api/market/trades/{symbol}`
13. `GET /api/market/status`
14. `POST /api/trading/orders`
15. `GET /api/trading/orders`
16. `GET /api/trading/positions`
17. `GET /api/ledger`
18. `GET /api/admin/dashboard/summary`
19. `GET /api/admin/users`
20. `GET /api/admin/accounts`
21. `GET /api/admin/trading/orders`
22. `GET /api/admin/trading/positions`
23. `GET /api/admin/finance/ledger`
24. `GET /api/admin/market/symbols`
25. `GET /api/admin/finance/payment-methods`
26. `GET /api/admin/content/messages`
27. `GET /api/admin/content/articles`
28. `GET /api/admin/config/dictionaries`
29. `GET /api/admin/config/settings`
30. `GET /api/admin/audit-logs`
31. `PATCH /api/admin/users/{userId}/status`
32. `POST /api/admin/users/{userId}/kyc-review`
33. `PATCH /api/admin/users/{userId}/risk-level`
34. `POST /api/admin/users/{userId}/notes`
35. `POST /api/admin/users/{userId}/force-logout`
36. `PATCH /api/admin/market/symbols/{symbolId}/status`
37. `POST /api/admin/market/symbols/{symbolId}/price-adjustments`
38. `POST /api/admin/trading/orders/{orderId}/cancel`
39. `POST /api/admin/finance/payment-methods`
40. `PATCH /api/admin/finance/payment-methods/{paymentMethodId}`
41. `POST /api/admin/finance/accounts/{accountId}/deposit`
42. `POST /api/admin/finance/accounts/{accountId}/withdraw`
43. `POST /api/admin/finance/accounts/{accountId}/adjustments`
44. `POST /api/admin/content/messages`
45. `POST /api/admin/content/articles`
46. `PUT /api/admin/config/dictionaries`
47. `PUT /api/admin/config/settings`
48. `OPTIONS /api/admin/config/settings`
49. `GET /api/admin/audit-logs`

## 5. wh-admin 覆盖判定规则

- 已完成：本地前后端均有对应页面或控件，且 HTTP 联调可通过。
- 部分完成：后端有基础能力，但缺少完整前端控件、详情页、审核流、导入导出、批量操作或细粒度筛选。
- 未完成：无后端接口或无前端入口。
- 不建议照搬：涉及隐藏控价、操控盈亏、无审计越权、删除审计、绕过风控等高风险能力，只允许做显式、审计化、权限化替代设计。

## 6. 失败处理流程

1. 记录失败命令、失败步骤、错误响应和日志位置。
2. 区分环境问题、测试脚本过期、前端契约错误、后端业务错误、数据库迁移错误。
3. 只做和失败直接相关的最小修复。
4. 修复后先跑失败测试，再跑所属层级全量测试。
5. 将修复原因、回归结果和剩余缺口写入最终测试结果文档。
