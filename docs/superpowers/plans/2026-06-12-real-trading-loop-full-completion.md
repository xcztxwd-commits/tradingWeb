# Real Trading Loop Full Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the phase 1 real demo trading loop and phase 2 user core pages with backend APIs, frontend workflows, automated tests, and browser click verification.

**Architecture:** Keep trading settlement rules in backend services, keep frontend pages as thin orchestration layers, and share table state, page state, and API error rendering across user pages. Prove phase 1 with a repeatable smoke script that uses real HTTP APIs and database checks when a backend is running.

**Tech Stack:** Spring Boot 3.5, MyBatis-Plus, PostgreSQL, React, Vite, Node test runner, STOMP market stream.

---

## File Structure

- Backend trading:
  - Modify `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/dto/response/PositionResponse.java` to expose TP/SL and timestamps.
  - Modify `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/dto/request/UpdatePositionProtectionRequest.java` to carry editable TP/SL.
  - Modify `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PositionService.java` to list current/history positions and update TP/SL.
  - Modify `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/controller/TradingController.java` to expose position history and TP/SL update routes.
  - Modify `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/repository/PositionRepository.java` to query all account positions by status.
  - Test `fx-trading-platform/backend/src/test/java/com/fxplatform/trading/service/PositionServiceTest.java`.

- Backend finance:
  - Modify `fx-trading-platform/backend/src/main/java/com/fxplatform/finance/service/FundOrderService.java` to create `PENDING_REVIEW`.
  - Modify `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFundOrderService.java` to review both `PENDING_REVIEW` and existing `PENDING`.
  - Test `fx-trading-platform/backend/src/test/java/com/fxplatform/finance/service/FundOrderServiceTest.java`.

- Smoke verification:
  - Create `fx-trading-platform/scripts/smoke-real-trading-loop.mjs` to register/login, create orders, assert HTTP state, and optionally assert DB rows via `DATABASE_URL`.
  - Modify `fx-trading-platform/package.json` to add `smoke:real-trading-loop`.

- Frontend shared UI:
  - Create `fx-trading-platform/apps/web/src/components/user-page/PageState.tsx` for loading/error/empty/login-required/API-error panels.
  - Create `fx-trading-platform/apps/web/src/components/user-page/DataTable.tsx` for table shell, local pagination, sorting, refresh, and empty state.
  - Create `fx-trading-platform/apps/web/src/components/user-page/userPageModels.ts` for filtering, sorting, pagination, and summary helpers.
  - Modify `fx-trading-platform/apps/web/src/styles.css` for tabs, pagination, status chips, and table controls.
  - Test `fx-trading-platform/apps/web/src/components/user-page/userPageModels.test.ts`.

- Frontend APIs and pages:
  - Modify `fx-trading-platform/apps/web/src/types/trading.ts` and `fx-trading-platform/apps/web/src/components/tables/types.ts`.
  - Modify `fx-trading-platform/apps/web/src/services/tradingApi.ts` to add position history and TP/SL update.
  - Modify `fx-trading-platform/apps/web/src/features/trading-session/tradingSession.ts` and `useTradingSession.ts` to load history positions and expose protection updates.
  - Rewrite focused parts of `DashboardPage.tsx`, `MarketsPage.tsx`, `OrdersPage.tsx`, `PositionsPage.tsx`, and `WalletPage.tsx`.
  - Test `fx-trading-platform/apps/web/src/pages/userPages.test.ts` plus API tests.

## Tasks

### Task 1: Backend Position Protection And History

- [ ] Write failing service tests for position history, TP/SL response fields, and TP/SL update ownership checks.
- [ ] Run `mvn -q -Dtest=PositionServiceTest test` and verify the new tests fail for missing behavior.
- [ ] Add `UpdatePositionProtectionRequest`, extend `PositionResponse`, repository queries, service methods, and controller routes.
- [ ] Re-run `mvn -q -Dtest=PositionServiceTest test`.

### Task 2: Fund Order Review State

- [ ] Change fund order tests to expect `PENDING_REVIEW`.
- [ ] Run `mvn -q -Dtest=FundOrderServiceTest test` and verify failure.
- [ ] Update user/admin fund order services so new orders use `PENDING_REVIEW`, admin review accepts both pending states, and no unrelated finance flow changes.
- [ ] Re-run targeted finance tests.

### Task 3: Real Trading Loop Smoke Script

- [ ] Add a smoke script that uses live HTTP APIs: auth register/login, account, market order, limit pending order, cancel, TP/SL order, user close, and admin force close if admin credentials are configured.
- [ ] Include optional DB assertions for `trading.orders`, `trading.trades`, `trading.positions`, and `ledger.ledger_entries` when `DATABASE_URL` is present.
- [ ] Add `npm run smoke:real-trading-loop`.
- [ ] Run the script against a live backend when available; otherwise keep it as a runnable acceptance harness and report the missing runtime dependency.

### Task 4: Shared User Page Components

- [ ] Write failing Node tests for pagination, sorting, status view filtering, and API error formatting.
- [ ] Implement `PageState`, `DataTable`, and pure helpers without coupling to any page.
- [ ] Re-run `npm --prefix fx-trading-platform run web:test`.

### Task 5: Orders And Positions Pages

- [ ] Add tests proving orders page has four views, cancel, modify, filters, pagination, and event timeline.
- [ ] Add tests proving positions page has current/history views, close, edit TP/SL, filters, pagination, and real API calls.
- [ ] Implement pages using shared components and existing session/API services.
- [ ] Re-run web tests.

### Task 6: Wallet, Dashboard, And Markets Pages

- [ ] Add tests proving wallet has balance/free/frozen, currency input, `PENDING_REVIEW`, fund requests, ledger, filters, and pagination.
- [ ] Add tests proving dashboard has equity, balance, free margin, used margin, floating PnL, today PnL, open position count, pending order count, recent ledger, and recent orders.
- [ ] Add tests proving markets has categories, search, favorites, STOMP quote subscription, realtime price, spread, 24h high/low, source status, and trading navigation.
- [ ] Implement pages with shared components and existing market APIs/STOMP.
- [ ] Re-run web tests and build.

### Task 7: Verification

- [ ] Run `C:\soft\Apache-Maven\apache-maven-3.9.11\bin\mvn.cmd -q test` from `fx-trading-platform/backend`.
- [ ] Run `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"`.
- [ ] Run `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"`.
- [ ] Start backend and web dev server if needed.
- [ ] Use the in-app Browser to click through login, trading order controls, `/orders`, `/positions`, `/wallet`, `/dashboard`, and `/markets`, checking DOM state and console health.
