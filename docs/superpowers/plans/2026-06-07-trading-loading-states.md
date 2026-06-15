# Trading Loading States Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a first-pass exchange-grade loading-state system for `/trading` that keeps route loading separate from terminal data readiness.

**Architecture:** Keep `ExchangeLoading` as the `React.lazy` route fallback only. Add focused terminal skeleton components and wire them into the market side panel and trade panel status surface without introducing a global `terminalReady` blocker.

**Tech Stack:** React 19, Vite, CSS Modules, Node test runner, existing `marketDataStore`, existing `TradePanel` session props.

---

### Task 1: Terminal Skeleton Components

**Files:**
- Create: `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.tsx`
- Create: `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.module.css`
- Test: `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.test.ts`

- [x] **Step 1: Write the failing test**

Add a source test that verifies the exported skeleton components and CSS accessibility/motion constraints:

```ts
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const componentPath = join(currentDir, 'TerminalSkeleton.tsx')
const stylesPath = join(currentDir, 'TerminalSkeleton.module.css')

describe('terminal skeleton components', () => {
  it('exports focused skeleton surfaces for terminal regions', () => {
    assert.equal(existsSync(componentPath), true, 'TerminalSkeleton.tsx should exist')
    const source = readFileSync(componentPath, 'utf8')
    assert.match(source, /export function OrderBookSkeleton/)
    assert.match(source, /export function TableSkeleton/)
    assert.match(source, /role="status"/)
    assert.match(source, /aria-live="polite"/)
  })

  it('uses stable dimensions and reduced-motion support', () => {
    assert.equal(existsSync(stylesPath), true, 'TerminalSkeleton.module.css should exist')
    const styles = readFileSync(stylesPath, 'utf8')
    assert.match(styles, /prefers-reduced-motion:\s*reduce/)
    assert.match(styles, /--terminal-skeleton-surface/)
    assert.match(styles, /min-height/)
    assert.match(styles, /@keyframes terminal-skeleton-scan/)
  })
})
```

- [x] **Step 2: Run test to verify it fails**

Run: `npm.cmd run web:test`

Expected: `TerminalSkeleton.tsx should exist` failure.

- [x] **Step 3: Implement minimal skeleton components**

Create `TerminalSkeleton.tsx` with `OrderBookSkeleton` and `TableSkeleton`. Each component should render fixed row counts and expose `role="status"` with concise labels. Use `aria-hidden="true"` for decorative rows.

- [x] **Step 4: Add CSS module**

Create `TerminalSkeleton.module.css` using existing trading CSS variables such as `--trading-surface-2`, `--trading-border`, `--trading-muted`, and `--trading-accent`. Add one transform/opacity shimmer animation and disable it under `prefers-reduced-motion: reduce`.

- [x] **Step 5: Run test to verify it passes**

Run: `npm.cmd run web:test`

Expected: all tests pass.

### Task 2: Market Side Panel First Snapshot Skeleton

**Files:**
- Modify: `fx-trading-platform/apps/web/src/components/market-side-panel/MarketSidePanel.tsx`
- Test: `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBook.test.ts`

- [x] **Step 1: Write the failing test**

Add a source-level assertion that `MarketSidePanel.tsx` imports `OrderBookSkeleton` and computes an initial empty snapshot check:

```ts
const sidePanelSource = readFileSync(join(currentDir, 'MarketSidePanel.tsx'), 'utf8')
assert.match(sidePanelSource, /OrderBookSkeleton/)
assert.match(sidePanelSource, /isInitialMarketSnapshot/)
```

- [x] **Step 2: Run test to verify it fails**

Run: `npm.cmd run web:test`

Expected: failure because `OrderBookSkeleton` is not yet referenced.

- [x] **Step 3: Implement initial snapshot detection**

In `MarketSidePanel.tsx`, add:

```ts
const initialMarketSnapshot =
  snapshot.lastPrice <= 0 &&
  snapshot.asks.length === 0 &&
  snapshot.bids.length === 0 &&
  snapshot.recentTrades.length === 0
```

Use `OrderBookSkeleton` only when the active tab is `orderbook` and the initial snapshot is empty. Do not block `RecentTrades`; it can continue to show its own empty or live state.

- [x] **Step 4: Run test to verify it passes**

Run: `npm.cmd run web:test`

Expected: all tests pass.

### Task 3: Trade Panel Session Loading Semantics

**Files:**
- Modify: `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx`
- Modify: `fx-trading-platform/apps/web/src/features/trading/styles/trade-panel.css`
- Test: `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.test.ts`

- [x] **Step 1: Write the failing test**

Add source assertions in `TradePanel.test.ts`:

```ts
assert.match(source, /trade-panel__session-status/)
assert.match(source, /aria-live="polite"/)
assert.match(styles, /trade-panel__session-status/)
```

- [x] **Step 2: Run test to verify it fails**

Run: `npm.cmd run web:test`

Expected: failure because the status surface does not exist.

- [x] **Step 3: Implement the status surface**

In `TradePanel.tsx`, add a compact session status row near the header:

```tsx
<div className="trade-panel__session-status" aria-live="polite">
  <span>{sessionBadge}</span>
  <small>{accountStatus}</small>
</div>
```

Keep submit blocking logic unchanged. This is a UI clarity improvement, not a behavior change.

- [x] **Step 4: Style the status surface**

Use compact terminal styling: small label, border, `--trading-field-bg`, `--trading-border`, and buy/sell/accent colors through existing parent theme tokens.

- [x] **Step 5: Run test to verify it passes**

Run: `npm.cmd run web:test`

Expected: all tests pass.

### Task 4: Build and Browser QA

**Files:**
- No source files unless QA finds a defect.

- [x] **Step 1: Run full tests**

Run: `npm.cmd run web:test`

Expected: all tests pass.

- [x] **Step 2: Run production build**

Run: `npm.cmd run web:build`

Expected: build succeeds. The known Vite dynamic/static import chunk warning is acceptable if unchanged.

- [x] **Step 3: Browser QA**

Open `http://127.0.0.1:5191/trading` or start the web dev server on a free localhost port. Verify:
- Page title is `FX Trader`.
- Page is not blank.
- Console has no relevant error/warn.
- Desktop view renders the market skeleton briefly or the loaded market panel.
- Mobile width does not introduce horizontal overflow.
