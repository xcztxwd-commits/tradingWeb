# User Shell Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the user-facing app shell so the official home page and all user trading functions are visible, while the user app no longer exposes the backend/admin page.

**Architecture:** Move navigation definitions out of `App.tsx` into a focused `navigation.ts` module. Introduce an `AppShell` component responsible only for desktop top navigation, mobile bottom navigation, auth/terminal shell state, and language/account actions. Keep route declarations in `App.tsx`.

**Tech Stack:** React 19, React Router 7, TypeScript, lucide-react, Node `node:test`, Vite.

---

### Task 1: Navigation Model And App Shell

**Files:**
- Create: `fx-trading-platform/apps/web/src/app/navigation.ts`
- Create: `fx-trading-platform/apps/web/src/app/AppShell.tsx`
- Modify: `fx-trading-platform/apps/web/src/app/App.tsx`
- Modify: `fx-trading-platform/apps/web/src/app/App.test.ts`
- Modify: `fx-trading-platform/apps/web/src/styles.css`
- Modify: `fx-trading-platform/apps/web/src/i18n/locales/en-US.ts`
- Modify: `fx-trading-platform/apps/web/src/i18n/locales/zh-CN.ts`
- Modify: `fx-trading-platform/apps/web/src/i18n/locales/ja-JP.ts`

- [ ] **Step 1: Write failing shell/navigation tests**

Update `App.test.ts` so it expects `/` to render `HomePage`, expects no `AdminPage` import or `/admin` user page route, expects `primaryNavItems` to contain `/`, `/dashboard`, `/trading`, `/markets`, `/orders`, `/positions`, `/wallet`, `/security`, `/settings`, and expects `mobileNavItems` to contain exactly `/`, `/trading`, `/markets`, `/orders`, `/account`.

- [ ] **Step 2: Run the failing test**

Run: `npm --workspace apps/web run test -- src/app/App.test.ts`

Expected: FAIL because current code redirects `/` to `/trading`, imports `AdminPage`, exposes `/admin`, and keeps navigation inline.

- [ ] **Step 3: Implement navigation module and shell**

Create `navigation.ts` with typed navigation arrays and no admin entry. Create `AppShell.tsx` with top nav and mobile bottom nav. Update `App.tsx` to compose `AppShell` around routes and redirect `/admin` to `/`.

- [ ] **Step 4: Update global shell CSS**

Replace sidebar-first shell CSS with a top-nav shell: `--app-top-nav-height: 64px`, `app-topbar`, `app-topbar__nav`, `app-topbar__actions`, and mobile `mobile-tabs` with 5 equal columns, 44px+ touch targets, safe-area padding, and no horizontal page scroll.

- [ ] **Step 5: Verify shell/navigation tests pass**

Run: `npm --workspace apps/web run test -- src/app/App.test.ts`

Expected: PASS for route, navigation, mobile cap, and admin removal assertions.

### Task 2: Terminal Shell Compatibility

**Files:**
- Modify: `fx-trading-platform/apps/web/src/styles.css`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/TradingPage.module.css`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/mobile/MobileTradingTerminal.module.css`
- Modify: `fx-trading-platform/apps/web/src/app/App.test.ts`

- [ ] **Step 1: Write failing layout assertions**

Update `App.test.ts` so terminal route no longer hides the global top navigation, mobile terminal leaves room for the bottom 5-item navigation, and auth pages still hide app chrome.

- [ ] **Step 2: Run the failing test**

Run: `npm --workspace apps/web run test -- src/app/App.test.ts`

Expected: FAIL until CSS is updated.

- [ ] **Step 3: Update CSS minimally**

Set terminal app shell to keep `app-topbar` visible on desktop and `mobile-tabs` visible on mobile. Offset mobile terminal action bar above `--mobile-tabs-height`.

- [ ] **Step 4: Verify tests**

Run: `npm --workspace apps/web run test -- src/app/App.test.ts`

Expected: PASS.
