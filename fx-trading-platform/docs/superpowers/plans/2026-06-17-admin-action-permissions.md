# Admin Action Permissions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the admin backend from menu-level access to API/action-level permission enforcement, add high-risk operation confirmation, and verify the full admin permission chain.

**Architecture:** Reuse the existing `admin.menus.permission_key` and `admin.role_menu_permissions.buttons` storage. Add a small permission catalog/mapping service, resolve authorities for authenticated admin users, enforce permissions in static admin APIs and the dynamic `/api/admin/features/{pageKey}/actions` path, then expose permissions to the admin UI for button filtering and client-side confirmation.

**Tech Stack:** Spring Boot Security method security, MyBatis-Plus repositories, JUnit 5/Mockito/AssertJ, Vite admin app with Node test files.

**Repository Rule:** Do not stage or commit automatically. The current worktree has unrelated changes; only touch files directly required by this plan.

---

### Task 1: Permission Catalog And Resolver

**Files:**
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminPermissionCatalog.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminAuthorityService.java`
- Modify: `backend/src/main/java/com/fxplatform/common/security/UserPrincipal.java`
- Modify: `backend/src/main/java/com/fxplatform/common/security/JwtAuthenticationFilter.java`
- Test: `backend/src/test/java/com/fxplatform/admin/service/AdminAuthorityServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/common/security/JwtAuthenticationFilterTest.java`

- [ ] **Step 1: Write failing resolver tests**

Add tests proving a user with role-menu permissions receives both page permission and button/action permissions, and a plain admin keeps `ROLE_ADMIN`.

Run from `fx-trading-platform/backend`: `cmd.exe /d /s /c "mvn.cmd -Dtest=AdminAuthorityServiceTest test"`

Expected: FAIL because `AdminAuthorityService` does not exist.

- [ ] **Step 2: Write minimal resolver implementation**

Implement `AdminAuthorityService.authoritiesFor(UserEntity user)` so it returns `ROLE_<role>`, plus enabled menu `permissionKey`, plus parsed enabled button strings for admin role bindings.

- [ ] **Step 3: Wire JWT authentication**

Change `JwtAuthenticationFilter` to build `UserPrincipal` with the resolved authority strings instead of only `ROLE_ADMIN`.

- [ ] **Step 4: Verify resolver green**

Run from `fx-trading-platform/backend`: `cmd.exe /d /s /c "mvn.cmd -Dtest=AdminAuthorityServiceTest,JwtAuthenticationFilterTest test"`

Expected: PASS.

### Task 2: Backend Action/API Enforcement

**Files:**
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminFeatureOperationService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/controller/AdminFinanceController.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/controller/AdminFundOrderController.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/controller/AdminTradingController.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/controller/AdminMarketController.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/controller/AdminMarketDataProviderController.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/controller/AdminUserController.java`
- Test: `backend/src/test/java/com/fxplatform/admin/service/AdminFeatureOperationServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/admin/controller/AdminControllerActionPermissionTest.java`

- [ ] **Step 1: Write failing dynamic action permission test**

Add a test for `AdminFeatureOperationService.performAction` where `order-history/cancel` requires `trading:order:cancel` and throws `AccessDeniedException` when missing.

Run from `fx-trading-platform/backend`: `cmd.exe /d /s /c "mvn.cmd -Dtest=AdminFeatureOperationServiceTest test"`

Expected: FAIL because `performAction` has no permission check.

- [ ] **Step 2: Implement action mapping**

Map at least these dynamic actions:

```text
products:create -> market:symbol:create
products:edit -> market:symbol:update
products:risk -> market:symbol:update
price-schedules:create -> finance:adjustment:create
price-schedules:cancel -> finance:adjustment:create
recharge-orders:review approved -> finance:fund-order:approve
recharge-orders:review rejected -> finance:fund-order:reject
withdrawal-orders:review approved -> finance:fund-order:approve
withdrawal-orders:review rejected -> finance:fund-order:reject
order-history:cancel -> trading:order:cancel
order-history:close-position -> trading:position:force-close
members:edit disabling -> user:disable
members:kick-offline -> user:force-logout
```

- [ ] **Step 3: Add static API `@PreAuthorize` checks**

Protect direct endpoints with authorities such as:

```java
@PreAuthorize("hasAuthority('finance:adjustment:create')")
@PreAuthorize("hasAuthority('trading:position:force-close')")
@PreAuthorize("hasAuthority('market:symbol:update')")
@PreAuthorize("hasAuthority('user:disable')")
```

Keep class-level `hasRole('ADMIN')` where it is still useful as a coarse gate.

- [ ] **Step 4: Verify enforcement green**

Run from `fx-trading-platform/backend`: `cmd.exe /d /s /c "mvn.cmd -Dtest=AdminFeatureOperationServiceTest test"`

Expected: PASS.

### Task 3: High-Risk Confirmation And Audit Coverage

**Files:**
- Modify: high-risk request DTOs under `backend/src/main/java/com/fxplatform/admin/dto/request`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminMarketDataProviderService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/controller/AdminMarketDataProviderController.java`
- Test: command service and provider tests.

- [ ] **Step 1: Write failing confirmation tests**

Add tests that high-risk DTOs reject missing or incorrect `confirmationText`, and provider enable/disable writes an audit record with actor user id.

Run from `fx-trading-platform/backend`: `cmd.exe /d /s /c "mvn.cmd -Dtest=AdminMarketDataProviderServiceTest,AdminFundOrderServiceTest,AdminTradingCommandServiceTest,AdminUserServiceTest,AdminMarketCommandServiceTest test"`

Expected: FAIL until confirmation fields and provider audit are implemented.

- [ ] **Step 2: Implement minimal DTO confirmation**

Add optional `confirmationText` fields only to high-risk requests and validate in service/controller boundary where the target/action is known. Do not add a broad framework.

- [ ] **Step 3: Add provider actor/audit path**

Pass `@AuthenticationPrincipal UserPrincipal` into provider create/update/sync/display operations that mutate state, and have `AdminMarketDataProviderService` record high-risk provider updates.

- [ ] **Step 4: Verify high-risk green**

Run the same targeted Maven command.

Expected: PASS.

### Task 4: Admin UI Permission Filtering And Confirmation

**Files:**
- Modify: `apps/admin/src/types.ts`
- Modify: `apps/admin/src/pages/FeatureCrudPage.tsx`
- Modify: `apps/admin/src/services/adminApi.ts`
- Test: `apps/admin/src/pages/FeatureCrudPage.test.mjs` or existing admin Node tests.

- [ ] **Step 1: Write failing UI source tests**

Add tests that feature action type includes `permission`, toolbar/row actions are filtered by `allowedPermissions`, and `confirm` actions open a confirmation dialog instead of executing immediately.

Run: `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run test"`

Expected: FAIL before UI changes.

- [ ] **Step 2: Implement minimal UI filtering**

Extend `AdminFeatureAction` with optional `permission` and `riskConfirmationText`. Filter actions against permissions returned by backend or token/session state.

- [ ] **Step 3: Implement actual confirm dialog**

For `action.type === 'confirm'`, open a dialog, require exact confirmation text for high-risk actions, and include `confirmationText` in the action payload/request.

- [ ] **Step 4: Verify UI green**

Run: `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run test"`

Expected: PASS.

### Task 5: Full Chain Verification

**Files:**
- Modify existing smoke scripts only if required by changed contracts.

- [ ] **Step 1: Backend targeted tests**

Run from `fx-trading-platform/backend`: `cmd.exe /d /s /c "mvn.cmd -Dtest=AdminAuthorityServiceTest,JwtAuthenticationFilterTest,AdminFeatureOperationServiceTest,AdminMarketDataProviderServiceTest,AdminFundOrderServiceTest,AdminTradingCommandServiceTest,AdminUserServiceTest,AdminMarketCommandServiceTest test"`

Expected: PASS.

- [ ] **Step 2: Backend broader admin/security test pass**

Run from `fx-trading-platform/backend`: `cmd.exe /d /s /c "mvn.cmd -Dtest=*Admin*,*Security* test"`

Expected: PASS or report exact unrelated pre-existing failures.

- [ ] **Step 3: Admin frontend tests**

Run: `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run test"`

Expected: PASS.

- [ ] **Step 4: Architecture/smoke chain**

Run repo-native smoke/architecture commands if available:

```cmd
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:admin"
```

Expected: PASS or report exact environment blockers with evidence.
