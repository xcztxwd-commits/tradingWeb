# WH Admin Gap Full Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/wh-admin-implementation-gap-plan-cn.md` 第 46-52 行补齐 WH 后台剩余 P0/P1/P2 功能，并在每个模块后完成后端测试、API 测试和前后端联调测试。

**Architecture:** 后端继续使用 Spring Boot 3 + MyBatis-Plus + PostgreSQL + Flyway，新增业务表用专用 Entity/Mapper/Service/Controller，通用截图后台页面继续通过 `FeatureCrudPage` 渲染，但数据源必须来自真实业务 API 或真实落库记录。前端继续使用 `apps/admin` 的 React/Vite 管理后台，不新增第二套后台系统。

**Tech Stack:** Java 21, Spring Boot 3.5.7, MyBatis-Plus, PostgreSQL, Flyway, Hutool, Lombok, Knife4j, React 19, TypeScript, Vite.

---

### Task 1: Runtime Baseline And Feature API Migration

**Files:**
- Modify: `backend/src/main/resources/db/migration/V17__admin_feature_records.sql`
- Test: `scripts/smoke-admin.mjs`

- [ ] Verify current DB applies `V17__admin_feature_records.sql`.
- [ ] Restart backend so `AdminFeatureController` is live.
- [ ] Add smoke checks for `GET /api/admin/features`, `GET /api/admin/features/system-roles`, and `POST /api/admin/features/system-roles/actions`.
- [ ] Run `mvn.cmd test`, `node scripts/smoke-admin.mjs`, `npm.cmd --workspace apps/admin run test`, and browser page check for `/system/roles`.

### Task 2: P0 RBAC, Menu Permission, Button Permission, Data Scope

**Files:**
- Create: `backend/src/main/resources/db/migration/V18__admin_rbac_and_gap_modules.sql`
- Create: `backend/src/main/java/com/fxplatform/admin/entity/AdminRoleEntity.java`
- Create: `backend/src/main/java/com/fxplatform/admin/entity/AdminMenuEntity.java`
- Create: `backend/src/main/java/com/fxplatform/admin/entity/AdminRoleMenuPermissionEntity.java`
- Create: `backend/src/main/java/com/fxplatform/admin/entity/AdminUserRoleEntity.java`
- Create: `backend/src/main/java/com/fxplatform/admin/entity/AdminDepartmentEntity.java`
- Create: `backend/src/main/java/com/fxplatform/admin/entity/AdminPostEntity.java`
- Create: `backend/src/main/java/com/fxplatform/admin/entity/AdminRoleDataScopeEntity.java`
- Create corresponding repositories under `backend/src/main/java/com/fxplatform/admin/repository/`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminRbacService.java`
- Create: `backend/src/main/java/com/fxplatform/admin/controller/AdminRbacController.java`
- Test: `backend/src/test/java/com/fxplatform/admin/service/AdminRbacServiceTest.java`

- [ ] RED: service test creates role, menu, role-menu button permissions, user-role binding, and data scope.
- [ ] GREEN: implement migration, entities, repositories, service, controller.
- [ ] Add QueryService data-scope entry helper and use it in admin user/account/trading query services where actor context is available.
- [ ] Test after module: targeted service test, `mvn.cmd test`, API smoke for `/api/admin/rbac/*`, frontend route `/system/roles`.

### Task 3: P0 Finance Review Orders

**Files:**
- Create: `backend/src/main/java/com/fxplatform/finance/entity/FundOrderEntity.java`
- Create: `backend/src/main/java/com/fxplatform/finance/repository/FundOrderRepository.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminFundOrderService.java`
- Create: `backend/src/main/java/com/fxplatform/admin/controller/AdminFundOrderController.java`
- Test: `backend/src/test/java/com/fxplatform/admin/service/AdminFundOrderServiceTest.java`

- [ ] RED: service test creates pending recharge/withdrawal order, approves it, and rejects another order.
- [ ] GREEN: implement order status flow with audit and ledger integration through existing finance command service.
- [ ] Wire `FeatureCrudPage` page keys `recharge-orders` and `withdrawal-orders` to real fund orders.
- [ ] Test after module: targeted test, `mvn.cmd test`, API smoke, frontend `/finance/recharge-orders` and `/finance/withdrawal-orders`.

### Task 4: P1 Member Detail, KYC, Bank Card, Wallet

**Files:**
- Create: `backend/src/main/java/com/fxplatform/auth/entity/UserProfileEntity.java`
- Create: `backend/src/main/java/com/fxplatform/auth/entity/KycApplicationEntity.java`
- Create: `backend/src/main/java/com/fxplatform/finance/entity/MemberPaymentAccountEntity.java`
- Create corresponding repositories.
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminMemberService.java`
- Create: `backend/src/main/java/com/fxplatform/admin/controller/AdminMemberController.java`
- Test: `backend/src/test/java/com/fxplatform/admin/service/AdminMemberServiceTest.java`

- [ ] RED: service test returns member detail, submits/reviews KYC, creates bank card and wallet account.
- [ ] GREEN: implement tables, entities, service and controller.
- [ ] Wire `members` and `member-payment-accounts` feature pages to real data.
- [ ] Test after module: targeted test, `mvn.cmd test`, API smoke, frontend `/members/list` and `/members/payment-accounts`.

### Task 5: P1 Product Category, Price Schedule, Risk CRUD

**Files:**
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminMarketQueryService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminMarketCommandService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/controller/AdminMarketController.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminRiskCommandService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/controller/AdminRiskController.java`
- Test: `backend/src/test/java/com/fxplatform/admin/service/AdminMarketManagementServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/admin/service/AdminRiskCommandServiceTest.java`

- [ ] RED: tests require category CRUD, price adjustment list/cancel, and risk config create/update/delete.
- [ ] GREEN: implement APIs on existing `market.symbol_categories`, `market.price_adjustments`, and `risk.risk_configs`.
- [ ] Wire feature pages `product-categories`, `price-schedules`, and risk page to real CRUD.
- [ ] Test after module: targeted tests, `mvn.cmd test`, API smoke, frontend product/risk pages.

### Task 6: P1 Request Logs And Verification Code Logs

**Files:**
- Create: `backend/src/main/java/com/fxplatform/audit/entity/RequestLogEntity.java`
- Create: `backend/src/main/java/com/fxplatform/audit/entity/VerificationCodeLogEntity.java`
- Create corresponding repositories.
- Create: `backend/src/main/java/com/fxplatform/audit/service/RequestLogService.java`
- Create: `backend/src/main/java/com/fxplatform/audit/service/VerificationCodeLogService.java`
- Create: `backend/src/main/java/com/fxplatform/common/web/RequestLogFilter.java`
- Create: `backend/src/main/java/com/fxplatform/admin/controller/AdminLogController.java`
- Test: `backend/src/test/java/com/fxplatform/audit/service/AdminLogServiceTest.java`

- [ ] RED: service test records request log and verification code log and queries both.
- [ ] GREEN: implement audit tables, services, filter and controller.
- [ ] Wire feature pages `request-logs` and `verification-codes` to real data.
- [ ] Test after module: targeted test, `mvn.cmd test`, API smoke, frontend log pages.

### Task 7: P2 Table Column Settings, Import, Export, Batch Operations

**Files:**
- Create: `backend/src/main/java/com/fxplatform/admin/entity/AdminTableColumnPreferenceEntity.java`
- Create: `backend/src/main/java/com/fxplatform/admin/entity/AdminExportTaskEntity.java`
- Create: `backend/src/main/java/com/fxplatform/admin/entity/AdminImportTaskEntity.java`
- Create: `backend/src/main/java/com/fxplatform/admin/entity/AdminBatchOperationEntity.java`
- Create corresponding repositories.
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminTableToolService.java`
- Create: `backend/src/main/java/com/fxplatform/admin/controller/AdminTableToolController.java`
- Modify: `apps/admin/src/pages/FeatureCrudPage.tsx`
- Test: `backend/src/test/java/com/fxplatform/admin/service/AdminTableToolServiceTest.java`

- [ ] RED: service test saves column preference, creates export/import task, and creates batch operation.
- [ ] GREEN: implement backend APIs and front-end calls from table settings/import/export/batch toolbar.
- [ ] Test after module: targeted test, `mvn.cmd test`, API smoke, frontend `/system/roles` table settings/import/export flow.

### Task 8: Final Full Verification And Docs

**Files:**
- Modify: `scripts/smoke-admin.mjs`
- Create: `docs/wh-admin-gap-full-completion-report-cn.md`
- Create: `docs/wh-admin-gap-full-test-results-cn.md`

- [ ] Run architecture verification.
- [ ] Run full backend tests.
- [ ] Restart backend and confirm Flyway reaches latest migration.
- [ ] Run all API smoke tests.
- [ ] Run admin frontend tests and build.
- [ ] Run browser联调 for RBAC, fund orders, members, product/risk, logs, table tools.
- [ ] Write completion and optimization documents with verified evidence.
