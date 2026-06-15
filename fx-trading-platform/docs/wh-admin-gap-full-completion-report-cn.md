# WH 后台缺口补全完成报告

日期：2026-06-10

## 完成范围

本次按 `docs/wh-admin-implementation-gap-plan-cn.md` 中 P0/P1/P2 缺口进行补全，后端统一使用 Spring Boot 3 + PostgreSQL + Flyway + MyBatis-Plus；新增代码使用 Lombok 简化实体/服务构造，必要逻辑使用 Hutool 工具类处理 JSON、集合、字符串、时间和审计明细。

## 已完成模块

1. RBAC、菜单、按钮权限、数据权限
   - 新增 `admin.roles`、`admin.menus`、`admin.role_menu_permissions`、`admin.user_roles`、`admin.departments`、`admin.posts`、`admin.role_data_scopes`。
   - 新增 `/api/admin/rbac/*` 角色、菜单、部门、岗位、用户角色、按钮权限、数据范围接口。
   - 前端 `/system/roles`、`/system/menus`、`/system/departments`、`/system/posts` 已切到真实 RBAC API。

2. 充值/提现审核订单
   - 新增 `finance.fund_orders`。
   - 新增 `/api/admin/finance/fund-orders` 查询、创建、审核接口。
   - 审核通过时接入现有资金入金/出金服务，拒绝时记录审核原因。
   - 前端 `/finance/recharge-orders`、`/finance/withdrawal-orders` 已切到真实资金订单 API。

3. 会员详情、KYC、银行卡/钱包
   - 新增 `auth.user_profiles`、`auth.kyc_applications`、`finance.member_payment_accounts`。
   - 新增 `/api/admin/members/*` 会员资料、KYC、银行卡/钱包接口。
   - 前端 `/members/list` 使用真实用户分页接口，`/members/payment-accounts` 使用真实支付账户接口。

4. 产品分类、涨跌设置、风控 CRUD
   - 产品分类和涨跌设置使用已有 `market.symbol_categories`、`market.price_adjustments`。
   - 风控配置新增创建、更新、删除接口，使用 `risk.risk_configs`。
   - 前端 `/products/categories`、`/products/price-schedules`、`/risk` 已确认调用真实 API。

5. 请求日志、验证码日志
   - 新增 `audit.request_logs`、`audit.verification_code_logs`。
   - 新增请求日志 Filter，记录 `/api/**` 请求耗时、状态码、客户端信息。
   - 新增 `/api/admin/logs/request-logs`、`/api/admin/logs/verification-codes`。
   - 前端 `/logs/request-logs`、`/logs/verification-codes` 已切到真实 API。

6. 表格列设置、导入、导出、批量操作
   - 新增 `admin.table_column_preferences`、`admin.export_tasks`、`admin.import_tasks`、`admin.batch_operations`。
   - 新增 `/api/admin/table-tools/*`。
   - `FeatureCrudPage` 已接入表格偏好自动读取/保存，导入、导出、删除批量操作创建真实任务。

## 关键迁移

- `V18__admin_rbac.sql`
- `V19__finance_fund_orders.sql`
- `V20__member_profile_kyc_payment_accounts.sql`
- `V21__admin_request_and_verification_logs.sql`
- `V22__admin_table_tools.sql`

## 主要改动路径

- `backend/src/main/java/com/fxplatform/admin/**`
- `backend/src/main/java/com/fxplatform/audit/**`
- `backend/src/main/java/com/fxplatform/auth/**`
- `backend/src/main/java/com/fxplatform/finance/**`
- `backend/src/main/java/com/fxplatform/market/**`
- `backend/src/main/java/com/fxplatform/risk/**`
- `backend/src/main/resources/db/migration/**`
- `apps/admin/src/services/adminApi.ts`
- `apps/admin/src/pages/FeatureCrudPage.tsx`
- `scripts/smoke-admin.mjs`

## 当前结论

本次后台缺口已补到真实数据库表、真实 MyBatis-Plus Mapper、真实后端 API、真实前端调用链，并已通过后端单测、API smoke、前端测试、前端构建和 Playwright 浏览器联调。
