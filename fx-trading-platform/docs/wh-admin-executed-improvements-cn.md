# WH 后台本轮已改进文档

日期：2026-06-10

## 1. 执行原则

本轮改进严格沿用当前项目的大框架：后端继续使用 Spring Boot 3、现有 `admin/finance/market/trading/content` 分层、MyBatis-Plus Mapper、PostgreSQL/Flyway；前端继续使用 `apps/admin` 的 React/Vite 管理后台和 `FeatureCrudPage` 通用页面。

没有另起一套后台系统，没有替换 Java 后端框架，没有把业务能力绕到前端假数据中实现。新增逻辑集中在现有 controller、service、DTO 和前端 `adminApi` 映射层，公共分页/筛选/排序能力抽到可复用工具中。

## 2. 通用列表能力

已新增后端通用查询协议：

- `AdminFeaturePageQuery`：统一解析 `page`、`size`、`sortField`、`sortDirection`、`filter.xxx`。
- `AdminFeatureQuerySupport`：统一封装 MyBatis-Plus `QueryWrapper` 的分页、模糊查询、等值查询、布尔查询、UUID 查询、排序白名单。
- 所有排序字段都走白名单映射，不允许前端直接透传任意 SQL 列名。
- 使用 Hutool `StrUtil`、`MapUtil` 做空值、前缀、字符串归一化。

前端 `FeatureCrudPage` 已改为服务端分页、筛选、排序：

- 搜索按钮：提交当前筛选条件到后端。
- 重置按钮：清空筛选并回到第一页。
- 表头排序：把列 key 传给后端白名单排序。
- 分页按钮：按后端返回的 `page/size/total/totalPages` 渲染。
- 每页数量：变更后重新请求后端。

## 3. 权限模块

已把截图中的权限管理行操作接入真实 API：

- 用户管理、角色管理、部门管理、菜单管理、岗位管理页面使用真实 `/api/admin/rbac/*` 接口。
- 角色编辑、删除、菜单权限、数据权限已从前端行按钮映射到真实后端。
- 菜单、部门、岗位的新增、编辑、删除均走真实 MyBatis-Plus 持久化。
- 数据权限保存为角色数据范围，菜单权限保存为角色菜单和按钮权限。

## 4. 产品模块

产品列表已从静态目录切换到真实交易品种 API：

- 列表读取 `/api/admin/market/symbols`。
- 新增产品：`POST /api/admin/market/symbols`。
- 编辑产品：`PUT /api/admin/market/symbols/{symbolId}`。
- 删除产品：`DELETE /api/admin/market/symbols/{symbolId}`，采用“下架”语义，把 `enabled=false`，避免破坏历史订单、成交和行情数据。
- 风控按钮：如果填写目标价格，创建显式价格调整记录；否则走产品启停状态接口。
- 产品分类、涨跌设置继续复用现有 `market.symbol_categories`、`market.price_adjustments` 业务表和接口。

## 5. 财务模块

已补齐截图财务页面的真实列表和操作链路：

- 资金明细：`/api/admin/finance/ledger` 支持服务端分页、筛选、排序。
- 充值订单、提现订单：`/api/admin/finance/fund-orders` 支持分页筛选，审核动作接入真实资金服务。
- 收款方式：列表、新增、编辑、删除全部走 `/api/admin/finance/payment-methods`。
- 删除收款方式会真实删除配置并写审计日志。

## 6. 用户与订单模块

已补齐截图中用户、银行卡/钱包、挂单/持仓/历史相关的真实数据来源：

- 用户列表继续复用真实 `/api/admin/users` 分页接口。
- 用户银行卡/钱包使用 `/api/admin/members/{userId}/payment-accounts` 和 `/api/admin/members/payment-accounts`。
- 订单历史使用 `/api/admin/trading/orders`，支持 `UID/产品/方向/状态/类型` 等筛选映射。
- 持仓和成交查询继续保持现有交易域 API，不把交易历史复制到通用假表。
- 撤销订单按钮接入 `/api/admin/trading/orders/{orderId}/cancel`，并带管理员原因和幂等键。

## 7. 内容与日志模块

公告、新闻、通知不再只停留在目录配置：

- 公告列表：`GET /api/admin/content/articles?articleType=ANNOUNCEMENT`。
- 新闻列表：`GET /api/admin/content/articles?articleType=NEWS`。
- 站内通知：`GET /api/admin/content/messages`。
- 公告/新闻/通知均支持新增、编辑、删除。
- 请求日志、验证码日志继续走真实审计接口，并支持前端截图表格的搜索、分页、排序。

## 8. 前端关键修改

主要修改集中在：

- `apps/admin/src/services/adminApi.ts`
  - 补齐 WH 截图页面到真实 Java API 的映射。
  - 抽出 `featurePageQueryString`、`featureQuery`、各业务 payload mapper。
  - 将产品、收款方式、订单、公告、新闻、通知等行按钮映射到真实写接口。
- `apps/admin/src/pages/FeatureCrudPage.tsx`
  - 接入后端分页、筛选、排序。
  - 支持产品风控和订单撤销的专用弹窗字段。
- `apps/admin/src/pages/adminPageUtils.tsx`
  - 支持依赖变化后重新加载页面数据。
- `apps/admin/src/types.ts`
  - 给通用功能页补充分页元数据。

## 9. 后端关键修改

主要新增/修改集中在：

- `AdminFeaturePageQuery`、`AdminFeatureQuerySupport`
- `AdminRbacController`、`AdminRbacService`
- `AdminMarketController`、`AdminMarketCommandService`、`AdminMarketQueryService`
- `AdminFinanceController`、`AdminFinanceCommandService`、`AdminFinanceQueryService`
- `AdminPaymentMethodQueryService`
- `AdminFundOrderController`、`AdminFundOrderService`
- `AdminTradingController`、`AdminTradingQueryService`
- `AdminContentController`、`AdminContentCommandService`、`AdminContentQueryService`
- `AdminConfigQueryService`

后端新增逻辑保持当前项目风格：Controller 接收参数和返回 `ApiResponse`，Service 处理业务、事务、审计，Repository/Mapper 做 MyBatis-Plus 数据操作。

## 10. 已验证结果

本轮最新验证命令和结果：

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| 后端定向模块测试 | `mvn.cmd "-Dtest=AdminPaymentMethodQueryServiceTest,AdminContentCommandServiceTest,AdminMarketCommandServiceTest,AdminFinanceCommandServiceTest" test` | 15 tests，0 failures |
| 后端全量测试 | `mvn.cmd test` | 100 tests，0 failures |
| 前端单元测试 | `npm.cmd --workspace apps/admin run test` | 23 tests，0 failures |
| 前端生产构建 | `npm.cmd --workspace apps/admin run build` | TypeScript + Vite build 通过 |
| 后端健康检查 | `GET http://localhost:8080/actuator/health` | `UP` |
| 全链路 admin smoke | `node scripts/smoke-admin.mjs` | 15 个步骤全部 PASS |
| 本轮新增 API 定向联调 | 一次性 Node API 脚本 | 7 个步骤全部 PASS |

定向联调覆盖：

- `http://localhost:5174/` 首页可访问。
- `http://localhost:5174/api/admin/dashboard/summary` 可通过前端 dev server 代理到后端。
- 产品新增、编辑、筛选、排序、下架。
- 收款方式新增、编辑、筛选、删除。
- 公告新增、编辑、筛选、删除。
- 站内通知新增、编辑、筛选、删除。
- 资金明细和订单历史分页筛选。

## 11. 当前结论

本轮已经把 WH 截图后台中此前缺少真实写接口或服务端查询能力的关键页面，补齐到当前项目的前端页面、Java Controller、Service、MyBatis-Plus 查询和真实数据库链路中。

仍建议继续做的内容不再是“能否使用”的阻塞项，而是后台体验、审计治理、导入导出文件生产、像素级还原和更细的数据权限执行链优化。
