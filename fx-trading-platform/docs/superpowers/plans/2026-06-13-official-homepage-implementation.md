# Official Homepage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real official homepage at `/` matching the accepted ui-ux-pro-max prototype.

**Architecture:** Keep homepage implementation isolated under `pages/home`. The page is presentational and uses local static view data so it does not couple homepage rendering to trading session, backend, or chart services.

**Tech Stack:** React 19, CSS modules, React Router `Link`, lucide-react, Node `node:test`.

---

### Task 1: Home Page Component

**Files:**
- Create: `fx-trading-platform/apps/web/src/pages/home/HomePage.tsx`
- Create: `fx-trading-platform/apps/web/src/pages/home/HomePage.module.css`
- Create: `fx-trading-platform/apps/web/src/pages/home/HomePage.test.ts`

- [ ] **Step 1: Write failing homepage source test**

Create `HomePage.test.ts` that reads `HomePage.tsx` and `HomePage.module.css`, then asserts the page includes links to `/trading`, `/markets`, `/dashboard`, `/orders`, `/positions`, `/wallet`, `/security`, `/settings`, uses a feature list without `/admin`, and defines mobile responsive CSS.

- [ ] **Step 2: Run the failing test**

Run: `npm --workspace apps/web run test -- src/pages/home/HomePage.test.ts`

Expected: FAIL because the page files do not exist yet.

- [ ] **Step 3: Implement homepage**

Build a semantic homepage with hero, market ticker strip, terminal preview, and 8 user feature entries. Use `Link` for real app routes. Do not introduce new data fetching or runtime dependencies.

- [ ] **Step 4: Verify homepage test**

Run: `npm --workspace apps/web run test -- src/pages/home/HomePage.test.ts`

Expected: PASS.

### Task 2: Home Route Integration

**Files:**
- Modify: `fx-trading-platform/apps/web/src/app/App.tsx`
- Modify: `fx-trading-platform/apps/web/src/app/App.test.ts`

- [ ] **Step 1: Add route assertions**

Assert `HomePage` is lazy-loaded and `/` renders `<HomePage />`, while `/trade` remains a legacy redirect to `/trading`.

- [ ] **Step 2: Run failing app test**

Run: `npm --workspace apps/web run test -- src/app/App.test.ts`

Expected: FAIL until `App.tsx` imports and routes `HomePage`.

- [ ] **Step 3: Integrate route**

Update `App.tsx` to lazy-load `HomePage` and render it at `/`.

- [ ] **Step 4: Verify app test**

Run: `npm --workspace apps/web run test -- src/app/App.test.ts`

Expected: PASS.
