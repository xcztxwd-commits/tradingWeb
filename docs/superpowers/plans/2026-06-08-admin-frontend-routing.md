# Admin Frontend Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert `fx-trading-platform/apps/admin` from a single static dashboard into an independent routed admin app while preserving the Java `/api/admin/**` backend architecture.

**Architecture:** Add `BrowserRouter`, protected routes, and an `AdminLayout` with `NavLink` navigation. Split data loading from one global snapshot into page-level admin API calls. Keep missing backend areas explicit through placeholder pages instead of changing Java backend structure.

**Tech Stack:** React 19, Vite, TypeScript, `react-router-dom`, existing Spring Boot admin APIs.

---

### Task 1: Guard Rails And Tests

**Files:**
- Modify: `fx-trading-platform/apps/admin/package.json`
- Create: `fx-trading-platform/apps/admin/src/app/AdminApp.test.mjs`
- Create: `fx-trading-platform/apps/admin/src/services/adminApi.test.mjs`

- [ ] Add an admin test script using Node's built-in test runner.
- [ ] Add source-level tests that require `BrowserRouter`, protected routes, layout nav links, explicit placeholder routes for missing backend APIs, and page-level admin API functions.
- [ ] Run `npm.cmd --workspace apps/admin run test`; expected first result is failure because the implementation is not present yet.

### Task 2: Routing Shell

**Files:**
- Modify: `fx-trading-platform/apps/admin/src/main.tsx`
- Modify: `fx-trading-platform/apps/admin/src/app/AdminApp.tsx`
- Create: `fx-trading-platform/apps/admin/src/app/AdminLayout.tsx`
- Create: `fx-trading-platform/apps/admin/src/app/RequireAdmin.tsx`
- Create: `fx-trading-platform/apps/admin/src/services/adminToken.ts`

- [ ] Wrap `AdminApp` in `BrowserRouter`.
- [ ] Define routes for `/login`, `/`, `/dashboard`, users, accounts, trading, finance, market, risk, content, config, and audit pages.
- [ ] Gate admin pages through `RequireAdmin`.
- [ ] Use `NavLink` in `AdminLayout` for active menu state.

### Task 3: Page-Level APIs And Pages

**Files:**
- Modify: `fx-trading-platform/apps/admin/src/services/adminApi.ts`
- Modify: `fx-trading-platform/apps/admin/src/services/apiClient.ts`
- Modify: `fx-trading-platform/apps/admin/src/types.ts`
- Create: `fx-trading-platform/apps/admin/src/pages/*.tsx`

- [ ] Replace global snapshot loading with page-level query functions.
- [ ] Create pages for dashboard, users, accounts, orders, positions, ledger, payment methods, symbols, market status, content, config, and audit logs.
- [ ] Keep `TradesPage` and `RiskPage` as explicit backend-gap pages.
- [ ] Keep Java API paths and request shapes unchanged.

### Task 4: Styling And Verification

**Files:**
- Modify: `fx-trading-platform/apps/admin/src/styles.css`
- Modify: `fx-trading-platform/apps/admin/package.json`
- Modify: `fx-trading-platform/package-lock.json`

- [ ] Replace fixed `nav button:first-child` styling with `.admin-nav-link.active`.
- [ ] Add route page loading, empty, error, table, and placeholder styles.
- [ ] Run `npm.cmd --workspace apps/admin run test`.
- [ ] Run `npm.cmd --workspace apps/admin run build`.
- [ ] Open local admin app and verify menu clicks update URL and active state.
