# FX Admin Backend And Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a complete backend management system on the existing Spring Boot backend and add a usable admin frontend that covers the wh-admin analysis document while preserving all current trading frontend API contracts.

**Architecture:** Keep user-facing APIs stable and add admin-only capabilities under `/api/admin/**`. Implement each vertical slice with DTOs, services, repositories, Flyway migrations, audit logging, tests, and a matching React admin surface. Backend admin writes must use domain services and audit logs instead of direct state mutation.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Security, Spring Data JPA, Bean Validation, Flyway, Lombok, JUnit 5, Mockito, React 19, TypeScript, Vite, lucide-react, CSS Modules/plain CSS, Node `node:test`.

---

## Global Execution Rules

- Every new or materially modified Java controller, service, implementation class, public method, critical internal step, DTO field, and entity field must include Chinese JavaDoc or a concise Chinese comment.
- Existing untouched code should not be mass-commented.
- Every backend function slice must include focused unit or controller tests.
- Every backend link slice must include a service-to-repository or controller-to-service flow test.
- Every combined business slice must include cross-module assertions such as audit plus ledger, order plus position, or product plus market compatibility.
- Every frontend admin feature must include a frontend test and a front-to-back contract assertion.
- After each task, run the task-specific tests before continuing.
- Use `C:\soft\fx-platform-tools\jdk-21` and `C:\soft\fx-platform-tools\apache-maven-3.9.9\bin\mvn.cmd` for backend tests.
- Use `npm.cmd test` and `npm.cmd run build` in `fx-trading-platform/apps/web` for frontend tests and build.
- Do not change existing user-facing API paths or response semantics unless a compatibility test proves the contract is preserved.
- Do not implement hidden profit/loss manipulation, hidden user impersonation, or direct un-audited balance changes.

## Baseline Commands

Backend:

```powershell
$env:JAVA_HOME='C:\soft\fx-platform-tools\jdk-21'
$env:Path="$env:JAVA_HOME\bin;C:\soft\fx-platform-tools\apache-maven-3.9.9\bin;$env:Path"
mvn.cmd -q test
```

Frontend:

```powershell
npm.cmd test
npm.cmd run build
```

## Task 1: Admin Foundation Contracts

**Files:**

- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/AdminPageResponse.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminReasonRequest.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/response/AdminAuditLogResponse.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminAuditQueryService.java`
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/controller/AdminController.java`
- Modify: `fx-trading-platform/backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java`
- Test: `fx-trading-platform/backend/src/test/java/com/fxplatform/admin/service/AdminAuditQueryServiceTest.java`

- [ ] **Step 1: Write failing architecture and service tests**

Add architecture assertions that admin API must not directly return entity collections and that admin code contains DTO/page response types. Add `AdminAuditQueryServiceTest` verifying audit logs map to DTOs and page metadata.

- [ ] **Step 2: Run focused backend tests and confirm failure**

Run:

```powershell
$env:JAVA_HOME='C:\soft\fx-platform-tools\jdk-21'
$env:Path="$env:JAVA_HOME\bin;C:\soft\fx-platform-tools\apache-maven-3.9.9\bin;$env:Path"
mvn.cmd -q -Dtest=ArchitectureRulesTest,AdminAuditQueryServiceTest test
```

Expected: fails because new DTO/service files do not exist.

- [ ] **Step 3: Implement admin pagination and audit DTOs**

Create the DTO records with JavaDoc for the record and every parameter. Implement `AdminAuditQueryService` as the read-only audit query facade.

- [ ] **Step 4: Replace entity-returning audit endpoint**

Update admin audit endpoint to return `AdminPageResponse<AdminAuditLogResponse>`.

- [ ] **Step 5: Run function tests**

Run:

```powershell
mvn.cmd -q -Dtest=AdminAuditQueryServiceTest test
```

- [ ] **Step 6: Run architecture tests**

Run:

```powershell
mvn.cmd -q -Dtest=ArchitectureRulesTest test
```

## Task 2: Admin Read-Only Management APIs

**Files:**

- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/response/AdminDashboardSummaryResponse.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/response/AdminAccountResponse.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/response/AdminOrderResponse.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/response/AdminPositionResponse.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/response/AdminLedgerEntryResponse.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/response/AdminSymbolResponse.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminDashboardService.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminAccountQueryService.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminTradingQueryService.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFinanceQueryService.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminMarketQueryService.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/controller/AdminDashboardController.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/controller/AdminAccountController.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/controller/AdminTradingController.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/controller/AdminFinanceController.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/controller/AdminMarketController.java`
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/controller/AdminController.java`
- Test: `fx-trading-platform/backend/src/test/java/com/fxplatform/admin/service/AdminDashboardServiceTest.java`
- Test: `fx-trading-platform/backend/src/test/java/com/fxplatform/admin/service/AdminQueryServicesTest.java`

- [ ] **Step 1: Write failing read-only service tests**

Test dashboard counts, symbol DTO mapping, order/position/ledger DTO mapping, and no password hash exposure.

- [ ] **Step 2: Run focused tests and confirm failure**

Run:

```powershell
mvn.cmd -q -Dtest=AdminDashboardServiceTest,AdminQueryServicesTest test
```

- [ ] **Step 3: Implement read-only admin services and controllers**

Each service must be read-only and return DTOs. Each controller must be annotated with `@PreAuthorize("hasRole('ADMIN')")` or rely on class-level admin security.

- [ ] **Step 4: Run function and link tests**

Run:

```powershell
mvn.cmd -q -Dtest=AdminDashboardServiceTest,AdminQueryServicesTest,ArchitectureRulesTest test
```

## Task 3: Admin User Actions And Session Controls

**Files:**

- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminUserStatusRequest.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminKycReviewRequest.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminUserNoteRequest.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminRiskLevelRequest.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/response/AdminUserNoteResponse.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/entity/AdminUserNoteEntity.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/repository/AdminUserNoteRepository.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminUserService.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/controller/AdminUserController.java`
- Create: `fx-trading-platform/backend/src/main/resources/db/migration/V13__admin_user_management.sql`
- Test: `fx-trading-platform/backend/src/test/java/com/fxplatform/admin/service/AdminUserServiceTest.java`

- [ ] **Step 1: Write failing user action tests**

Test status update, KYC review, risk-level update, note creation, force logout audit event, and missing reason validation.

- [ ] **Step 2: Run focused tests and confirm failure**

Run:

```powershell
mvn.cmd -q -Dtest=AdminUserServiceTest test
```

- [ ] **Step 3: Add user note migration, entity, repository, service, controller**

All fields and methods must have Chinese comments. User status, KYC, risk, and note actions must write audit logs.

- [ ] **Step 4: Run function and combined audit tests**

Run:

```powershell
mvn.cmd -q -Dtest=AdminUserServiceTest,ArchitectureRulesTest test
```

## Task 4: Admin Market Product Management

**Files:**

- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/market/entity/SymbolCategoryEntity.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/market/entity/SymbolAdminEventEntity.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/market/entity/PriceAdjustmentEntity.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/market/repository/SymbolCategoryRepository.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/market/repository/SymbolAdminEventRepository.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/market/repository/PriceAdjustmentRepository.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminSymbolRequest.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminSymbolStatusRequest.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminPriceAdjustmentRequest.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminMarketCommandService.java`
- Create: `fx-trading-platform/backend/src/main/resources/db/migration/V14__admin_market_management.sql`
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/controller/AdminMarketController.java`
- Test: `fx-trading-platform/backend/src/test/java/com/fxplatform/admin/service/AdminMarketCommandServiceTest.java`

- [ ] **Step 1: Write failing market command tests**

Test symbol enable/disable, symbol parameter update, event record creation, audit creation, and production-safe price adjustment validation.

- [ ] **Step 2: Run focused tests and confirm failure**

Run:

```powershell
mvn.cmd -q -Dtest=AdminMarketCommandServiceTest test
```

- [ ] **Step 3: Implement market management tables and command service**

Use existing `market.symbols` as the source of truth. `price_adjustments` must be explicit, audited, and disabled from hidden manipulation semantics.

- [ ] **Step 4: Run function, link, and compatibility tests**

Run:

```powershell
mvn.cmd -q -Dtest=AdminMarketCommandServiceTest,ArchitectureRulesTest test
```

## Task 5: Admin Trading Actions

**Files:**

- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminCancelOrderRequest.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminForceClosePositionRequest.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminTradingCommandService.java`
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/controller/AdminTradingController.java`
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/repository/OrderRepository.java`
- Test: `fx-trading-platform/backend/src/test/java/com/fxplatform/admin/service/AdminTradingCommandServiceTest.java`

- [ ] **Step 1: Write failing trading command tests**

Test admin cancel order, idempotent repeated cancel, force-close request validation, audit logging, and domain-state protection.

- [ ] **Step 2: Run focused tests and confirm failure**

Run:

```powershell
mvn.cmd -q -Dtest=AdminTradingCommandServiceTest test
```

- [ ] **Step 3: Implement command service using existing trading boundaries**

Do not directly bypass ledger/margin logic. If a full domain close path is unavailable, return a clear business error rather than silently mutating money.

- [ ] **Step 4: Run function and combined trading tests**

Run:

```powershell
mvn.cmd -q -Dtest=AdminTradingCommandServiceTest,OrderServiceTest,PositionServiceTest test
```

## Task 6: Admin Finance, Deposits, Withdrawals, Adjustments

**Files:**

- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/entity/DepositRequestEntity.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/entity/WithdrawalRequestEntity.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/entity/PaymentMethodEntity.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/entity/BalanceAdjustmentEntity.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/repository/DepositRequestRepository.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/repository/WithdrawalRequestRepository.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/repository/PaymentMethodRepository.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/repository/BalanceAdjustmentRepository.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminFinanceReviewRequest.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminBalanceAdjustmentRequest.java`
- Create: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java`
- Create: `fx-trading-platform/backend/src/main/resources/db/migration/V15__admin_finance_management.sql`
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/controller/AdminFinanceController.java`
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/enums/LedgerEntryType.java`
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/service/LedgerService.java`
- Test: `fx-trading-platform/backend/src/test/java/com/fxplatform/admin/service/AdminFinanceCommandServiceTest.java`

- [ ] **Step 1: Write failing finance tests**

Test deposit approval creates ledger, withdrawal approval checks free margin/balance, balance adjustment creates ledger and audit, and rejection does not move money.

- [ ] **Step 2: Run focused tests and confirm failure**

Run:

```powershell
mvn.cmd -q -Dtest=AdminFinanceCommandServiceTest test
```

- [ ] **Step 3: Implement finance workflow**

All money changes must go through `LedgerService`; direct balance edits without a ledger entry are not allowed.

- [ ] **Step 4: Run function, link, and combined finance tests**

Run:

```powershell
mvn.cmd -q -Dtest=AdminFinanceCommandServiceTest,LedgerServiceTest,ArchitectureRulesTest test
```

## Task 7: Messages, Announcements, News, Dictionaries, Settings, Logs

**Files:**

- Create core entities/repositories for messages, message receipts, announcements, news, dictionaries, dictionary items, system settings, login logs, and API logs.
- Create admin DTOs, controllers, and services under `com.fxplatform.admin`.
- Create migration: `fx-trading-platform/backend/src/main/resources/db/migration/V16__admin_content_system_logs.sql`
- Test: `fx-trading-platform/backend/src/test/java/com/fxplatform/admin/service/AdminContentSystemServiceTest.java`

- [ ] **Step 1: Write failing content/system tests**

Test message send, announcement publish/unpublish, news publish/unpublish, dictionary item CRUD, system setting update with audit, and log DTO mapping.

- [ ] **Step 2: Run focused tests and confirm failure**

Run:

```powershell
mvn.cmd -q -Dtest=AdminContentSystemServiceTest test
```

- [ ] **Step 3: Implement content and system services**

Use status fields instead of hard deletion. Sensitive setting values must be masked in responses.

- [ ] **Step 4: Run function and combination tests**

Run:

```powershell
mvn.cmd -q -Dtest=AdminContentSystemServiceTest,ArchitectureRulesTest test
```

## Task 8: Admin Frontend Foundation

**Files:**

- Create: `fx-trading-platform/apps/web/src/admin/adminApi.ts`
- Create: `fx-trading-platform/apps/web/src/admin/AdminShell.tsx`
- Create: `fx-trading-platform/apps/web/src/admin/AdminShell.module.css`
- Create: `fx-trading-platform/apps/web/src/admin/adminTypes.ts`
- Modify: `fx-trading-platform/apps/web/src/app/App.tsx`
- Test: `fx-trading-platform/apps/web/src/admin/AdminShell.test.ts`
- Test: `fx-trading-platform/apps/web/src/admin/adminApi.test.ts`

- [ ] **Step 1: Write failing frontend foundation tests**

Test `/admin` route, admin shell navigation, API paths under `/api/admin/**`, accessibility labels, and no marketing hero layout.

- [ ] **Step 2: Run focused frontend tests and confirm failure**

Run:

```powershell
npm.cmd test -- src/admin/*.test.ts
```

- [ ] **Step 3: Implement admin shell**

Use ui-ux-pro-max rules: dense operational layout, accessible navigation, 44px touch targets where interactive, tabular numbers, restrained professional palette, no nested cards.

- [ ] **Step 4: Run frontend function tests**

Run:

```powershell
npm.cmd test -- src/admin/*.test.ts
```

## Task 9: Admin Frontend Pages And Contract Tests

**Files:**

- Create admin pages for Dashboard, Users, Market, Trading, Finance, Messages, Content, System, Audit.
- Create shared table, toolbar, status badge, metric strip, and drawer/detail components if needed.
- Test: page-level source and API contract tests.

- [ ] **Step 1: Write failing page tests**

Each page test must assert visible page title, table columns, primary filters, read/write action buttons, and matching API function calls.

- [ ] **Step 2: Run focused frontend tests and confirm failure**

Run:

```powershell
npm.cmd test -- src/admin/**/*.test.ts
```

- [ ] **Step 3: Implement pages**

Keep the UI utilitarian and scanner-friendly. Use icon buttons with labels/tooltips. Use modals/drawers for write operations and require reason fields on high-risk actions.

- [ ] **Step 4: Run frontend and contract tests**

Run:

```powershell
npm.cmd test -- src/admin/**/*.test.ts
```

## Task 10: Full Verification

**Files:**

- No source files unless verification finds a defect.

- [ ] **Step 1: Run backend full tests**

Run:

```powershell
$env:JAVA_HOME='C:\soft\fx-platform-tools\jdk-21'
$env:Path="$env:JAVA_HOME\bin;C:\soft\fx-platform-tools\apache-maven-3.9.9\bin;$env:Path"
mvn.cmd -q test
```

- [ ] **Step 2: Run frontend tests**

Run:

```powershell
npm.cmd test
```

- [ ] **Step 3: Run frontend build**

Run:

```powershell
npm.cmd run build
```

- [ ] **Step 4: Run front-back contract scan**

Verify every admin API path used by `src/admin/adminApi.ts` has a matching Spring controller mapping, and every current user-facing frontend API remains documented and untouched.

- [ ] **Step 5: Browser smoke test**

Start the web dev server and verify `/trading` and `/admin` render without blank screens. Use Browser/Playwright if available; otherwise use build and source-level checks with a clear note.

## Self-Review

- The plan covers backend admin foundation, read-only APIs, user actions, market commands, trading commands, finance, content/system/logs, frontend shell, frontend pages, and full verification.
- The plan includes testing gates for each function, link, combination, and frontend-backend contract.
- The plan explicitly includes the user's comment requirement for controllers, services, methods, implementation classes, key steps, and fields.
- The plan does not ask the user to choose execution mode; the user already instructed continuous execution with the agent's best judgment.
