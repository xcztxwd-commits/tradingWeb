# Mobile Responsive Verification Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify the implemented UI matches the accepted prototype on desktop and mobile without regressing build/test health.

**Architecture:** Use existing app scripts and browser rendering. Keep screenshots under transient test result paths unless the user asks to keep them.

**Tech Stack:** Vite, Chrome/Browser plugin, Node `node:test`, TypeScript build.

---

### Task 1: Automated Verification

**Files:**
- Verify only: `fx-trading-platform/apps/web`

- [ ] **Step 1: Run focused tests**

Run: `npm --workspace apps/web run test -- src/app/App.test.ts src/pages/home/HomePage.test.ts`

Expected: PASS.

- [ ] **Step 2: Run full web tests**

Run: `npm run web:test`

Expected: PASS or report exact pre-existing failures if unrelated.

- [ ] **Step 3: Run production build**

Run: `npm run web:build`

Expected: TypeScript and Vite build succeed.

### Task 2: Browser Visual QA

**Files:**
- Verify rendered routes: `/`, `/trading`, `/account`

- [ ] **Step 1: Start dev server**

Run: `npm run web:dev -- --port <available-port>`.

- [ ] **Step 2: Inspect desktop routes**

Open `/` and `/trading` in the browser at a desktop viewport. Verify official homepage first viewport, top nav items, terminal nav visibility, and no admin entry.

- [ ] **Step 3: Inspect mobile routes**

Open `/` and `/trading` at 390px width. Verify bottom nav has exactly 首页/终端/行情/订单/我的, no horizontal overflow, and terminal quick action bar does not cover the global bottom nav.

- [ ] **Step 4: Compare to prototype screenshots**

Use the accepted prototype screenshots under `docs/prototypes/screenshots` as reference and record any material deviations.
