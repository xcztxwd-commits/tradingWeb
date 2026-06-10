# Admin UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Professionalize the standalone admin UI in `fx-trading-platform/apps/admin` without changing backend contracts.

**Architecture:** Keep existing React routes and API services intact. Add a small source-level regression test for required UI structure, then update `AdminLayout.tsx`, `FeatureCrudPage.tsx`, `adminPageUtils.tsx`, `LoginPage.tsx`, `DashboardPage.tsx`, and `styles.css` so all current admin pages inherit one coherent backend design system.

**Tech Stack:** React 19, React Router 7, Vite 7, TypeScript, Node test runner, `lucide-react`, plain CSS.

---

### Task 1: Lock UI Structure Expectations

**Files:**
- Create: `fx-trading-platform/apps/admin/src/app/AdminUiRedesign.test.mjs`

- [ ] **Step 1: Write the failing source-level test**

Create a Node test that reads the admin layout, CRUD page, login page, and CSS. Assert the redesigned shell includes a skip link, compact brand copy, professional topbar buttons, feature page header, semantic loading copy, design tokens, motion reduction, row hover, modal animation, and stable button press effects.

- [ ] **Step 2: Run test to verify it fails**

Run: `npm.cmd --workspace apps/admin run test`

Expected before implementation: FAIL because the new UI structure and CSS tokens do not exist yet.

### Task 2: Update Admin Shell And Shared Page Components

**Files:**
- Modify: `fx-trading-platform/apps/admin/src/app/AdminLayout.tsx`
- Modify: `fx-trading-platform/apps/admin/src/pages/adminPageUtils.tsx`
- Modify: `fx-trading-platform/apps/admin/src/pages/DashboardPage.tsx`

- [ ] **Step 1: Add professional shell affordances**

Add a skip link, richer brand text, professional class names for topbar icon buttons, and better sidebar collapse labeling while preserving existing route tabs, menu groups, logout logic, and fullscreen behavior.

- [ ] **Step 2: Improve shared page states**

Keep `PageHeader`, `StateBlock`, `DataTable`, and `AdminPageTable` APIs compatible. Add semantic class variants for loading/error/empty states and a concise page description style.

- [ ] **Step 3: Refresh dashboard metric card structure**

Keep existing dashboard API usage. Add metric icon containers and a compact status strip so CSS can style it as an operations dashboard.

### Task 3: Update CRUD And Login Surfaces

**Files:**
- Modify: `fx-trading-platform/apps/admin/src/pages/FeatureCrudPage.tsx`
- Modify: `fx-trading-platform/apps/admin/src/pages/LoginPage.tsx`

- [ ] **Step 1: Add feature page header and density metadata**

Render `data.group`, `data.title`, row totals, visible columns, and page size above the filter grid. Keep all filters, pagination, sorting, preferences, and actions unchanged.

- [ ] **Step 2: Improve action feedback markup**

Add loading-friendly, icon-friendly classes to existing buttons. Keep the actual handlers and backend action calls unchanged.

- [ ] **Step 3: Redesign login markup without changing auth flow**

Keep `login(email, password)`, ADMIN role check, token storage, redirect, and error text. Add professional visual wrappers and keep the submit button disabled during loading.

### Task 4: Replace Admin Styles With A Token-Driven Design System

**Files:**
- Modify: `fx-trading-platform/apps/admin/src/styles.css`

- [ ] **Step 1: Define admin design tokens**

Add semantic CSS variables for background, sidebar, surface, border, text, muted text, primary, primary hover, danger, warning, success, shadow, radius, and motion duration.

- [ ] **Step 2: Style shell, tabs, content, and shared states**

Style `.admin-shell`, `.admin-sidebar`, `.admin-topbar`, `.route-tabs`, `.admin-content`, `.page-header`, `.state-block`, `.admin-table`, and dashboard metric cards using the token system.

- [ ] **Step 3: Style CRUD tables, forms, buttons, modal, pagination, settings**

Style `.feature-page`, `.feature-page-header`, `.feature-filters`, `.primary-button`, `.danger-button`, `.ghost-button`, `.circle-button`, `.feature-table`, `.modal-mask`, `.feature-modal`, `.settings-modal`, and `.feature-pagination` with professional backend density and visible focus states.

- [ ] **Step 4: Style login and responsive states**

Style `.login-screen`, `.login-panel`, mobile breakpoints, and `prefers-reduced-motion`.

### Task 5: Verify

**Files:**
- No additional source changes expected unless verification exposes issues.

- [ ] **Step 1: Run admin tests**

Run: `npm.cmd --workspace apps/admin run test`

Expected: PASS.

- [ ] **Step 2: Run admin build**

Run: `npm.cmd --workspace apps/admin run build`

Expected: PASS.

- [ ] **Step 3: Render in browser**

Open `http://localhost:5174/`. Verify the login or protected admin screen is meaningful, not blank, has no framework overlay, and console has no relevant app errors.

- [ ] **Step 4: Interaction checks**

Exercise at least one shell or CRUD interaction: sidebar collapse, route tab close, table settings modal, or login loading state. Verify desktop and mobile-sized viewports.
