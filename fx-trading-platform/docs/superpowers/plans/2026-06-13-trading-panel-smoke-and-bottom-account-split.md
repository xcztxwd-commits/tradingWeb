# Trading Panel Smoke And Bottom Account Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the trading terminal maintainable by splitting the bottom account table views and turning the mobile Trade click path into a reusable smoke command.

**Architecture:** Keep `TradingPage` as the orchestration shell. Keep `BottomAccountPanel` responsible for tab state and filtering only, with table/grid rendering moved into focused sibling view components. Extend the existing CDP smoke pattern with one mobile viewport branch and expose it through `web:smoke:trading`.

**Tech Stack:** React, TypeScript, Node `node:test`, Vite, Chrome DevTools Protocol smoke scripts.

---

### Task 1: Lock The Desired Boundaries With Failing Tests

**Files:**
- Modify: `fx-trading-platform/apps/web/src/pages/trading/components/BottomAccountPanel.test.ts`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/TradingPage.test.ts`

- [ ] **Step 1: Add BottomAccountPanel decomposition assertions**

Assert that `BottomAccountPanel.tsx` imports `OrdersGrid`, `PositionsGrid`, `AssetView`, and `StrategiesGrid` from sibling files, and no longer defines those view functions inline.

- [ ] **Step 2: Add trading smoke script assertions**

Assert that `fx-trading-platform/package.json` exposes `web:smoke:trading`, and that `scripts/smoke-trading-login-gate.mjs` contains a mobile Trade click branch that verifies either a login dialog or an opened order sheet.

- [ ] **Step 3: Run the targeted web tests and confirm they fail for missing implementation**

Run: `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test -- --test-name-pattern=\"bottom account panel|TradingPage\""`

Expected: fail on missing imports/script/mobile smoke assertions.

### Task 2: Split BottomAccountPanel View Components

**Files:**
- Modify: `fx-trading-platform/apps/web/src/pages/trading/components/BottomAccountPanel.tsx`
- Create: `fx-trading-platform/apps/web/src/pages/trading/components/BottomAccountOrdersGrid.tsx`
- Create: `fx-trading-platform/apps/web/src/pages/trading/components/BottomAccountPositionsGrid.tsx`
- Create: `fx-trading-platform/apps/web/src/pages/trading/components/BottomAccountAssetView.tsx`
- Create: `fx-trading-platform/apps/web/src/pages/trading/components/BottomAccountStrategiesGrid.tsx`
- Create: `fx-trading-platform/apps/web/src/pages/trading/components/bottomAccountFormatters.ts`

- [ ] **Step 1: Move row renderers into focused components**

Keep the JSX identical and reuse `BottomAccountPanel.module.css`, so the UI and style surface do not change.

- [ ] **Step 2: Keep BottomAccountPanel as state orchestration**

Leave only tab state, current-symbol filtering, loading skeleton, and view selection in `BottomAccountPanel.tsx`.

- [ ] **Step 3: Run the targeted bottom account tests**

Run: `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test -- --test-name-pattern=\"bottom account panel\""`

Expected: pass.

### Task 3: Solidify Mobile Trading Smoke

**Files:**
- Modify: `fx-trading-platform/scripts/smoke-trading-login-gate.mjs`
- Modify: `fx-trading-platform/package.json`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/TradingPage.test.ts`

- [ ] **Step 1: Add `web:smoke:trading`**

Map it to `node scripts/smoke-trading-login-gate.mjs` so the command name is stable and product-focused.

- [ ] **Step 2: Add mobile viewport smoke branch**

Open `/trading` at `390x844`, click the visible `Trade` action, then assert:
- guest/login-required state: login dialog opens and no order sheet remains open;
- authenticated state: order sheet opens and contains the compact `TradePanel`.

- [ ] **Step 3: Run targeted TradingPage tests**

Run: `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test -- --test-name-pattern=\"TradingPage\""`

Expected: pass.

### Task 4: Backend Admin Command Scope Check

**Files:**
- Read only: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/*CommandService.java`
- Read only: `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/*FeatureActionHandler.java`

- [ ] **Step 1: Check for new duplicate audit action expansion**

If no current edit touches admin command code and no immediate duplicate expansion is required for this task, do not change backend admin command helpers.

- [ ] **Step 2: Report the deferral explicitly**

State that the helper extraction remains a later step triggered by real repeated expansion, not a preemptive abstraction.

### Task 5: Full Verification

**Files:**
- No edits.

- [ ] **Step 1: Run frontend tests**

Run: `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"`

- [ ] **Step 2: Run frontend build**

Run: `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"`

- [ ] **Step 3: Run trading smoke**

Run: `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:smoke:trading"`

- [ ] **Step 4: Run backend health or test check if smoke reports backend dependency issues**

Run: `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:backend"` or backend Maven tests, depending on the observed failure.
