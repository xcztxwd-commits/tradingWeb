# Frontend Follow-up Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the remaining frontend audit follow-up by removing the last page-layer dependency from the reusable market side panel, splitting one more heavy chart tool chunk, and enforcing repeatable verification and bundle budgets.

**Architecture:** Keep business behavior unchanged. Move shared loading UI to the component that owns it, lazy-load chart-only tooling behind stable Suspense fallbacks, and add a small Node-based bundle budget script so future changes cannot silently grow the trading terminal bundle.

**Tech Stack:** React 19, Vite 7, TypeScript 5.8, Node `node:test`, PowerShell on Windows, existing Browser plugin for final visual QA.

---

## Context For Codex

This plan continues from `fx-trading-platform/docs/frontend-code-audit-optimization-2026-06-12.md`.

The previous optimization pass already completed the major audit items:

- `createPanelMarket` keeps the current symbol on empty quote snapshots.
- `offline-preview` was removed from production session modes.
- old `/trade` implementation files were deleted while `/trade` still redirects to `/trading`.
- `tradingMarketApi`, `tradingMarketAdapters`, `tradingModels`, and `mockTradingData` were moved into `features/market`.
- `parseSymbolAssets` and `formatDecimal` were consolidated.
- untouched limit prices now follow same-symbol market updates.
- quote subscriptions are limited to selected, favorite, and first-screen markets.
- `MobileTradingTerminal` and `IndicatorSettingsModal` are already lazy-loaded.

Remaining audit follow-up:

1. `components/market-side-panel/MarketSidePanel.tsx` still imports `OrderBookSkeleton` from `pages/trading/components/TerminalSkeleton`.
2. `ChartDrawingToolbar` is still statically imported by `ChartWorkspace`.
3. There is no local bundle budget script that enforces the expected chunk split.
4. Final QA should include build, tests, type-check, architecture check, and browser verification.

Reference documentation used for this plan:

- React `lazy`: https://react.dev/reference/react/lazy
- React code splitting with `Suspense`: https://legacy.reactjs.org/docs/code-splitting.html
- Vite dynamic imports and preload behavior: https://vite.dev/guide/features
- web.dev code splitting rationale: https://web.dev/learn/performance/code-split-javascript

Do not broaden scope into root `src/Chart.ts`, `src/Store.ts`, or `src/common/EventHandler.ts`. The audit explicitly says the chart library large-class cleanup is lower priority and should not be mixed into this business frontend optimization.

---

## Execution Rules

- Work from `C:\Users\User\Desktop\workspace\tradingView-KlineChart`.
- Do not reset or discard unrelated user changes.
- Before editing, run `git status --short` and keep the diff limited to files listed in this plan.
- Use TDD for code changes: write or update the targeted test first, run it to see it fail, then implement the minimal code.
- Keep commits small. Commit after each task if the user asks for commits; otherwise leave changes unstaged and report verification.
- Use PowerShell-safe commands. Prefer `cmd.exe /d /s /c "npm.cmd ..."` for npm scripts.
- Do not add new runtime dependencies.
- Do not mechanically rewrite existing mojibake text in unrelated files. Only touch strings needed by the changed component or test.

---

## Target File Map

### Create

- `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.tsx`
  - Owns the order book loading skeleton used by the reusable market side panel.

- `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.module.css`
  - Owns only order book skeleton styling.

- `fx-trading-platform/scripts/check-web-bundle-budget.mjs`
  - Checks production build assets against explicit chunk-size budgets.

### Modify

- `fx-trading-platform/apps/web/src/components/market-side-panel/MarketSidePanel.tsx`
  - Import `OrderBookSkeleton` from local market-side-panel module.

- `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBook.test.ts`
  - Add assertions that `MarketSidePanel` no longer depends on `pages/trading`.

- `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.tsx`
  - Keep `TableSkeleton` only.

- `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.module.css`
  - Keep table skeleton styles only.

- `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.test.ts`
  - Update expectations to match `TableSkeleton` ownership.

- `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.tsx`
  - Lazy-load `ChartDrawingToolbar` and add a stable rail-sized fallback.

- `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.module.css`
  - Add `.drawingToolbarLoading`.

- `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.test.ts`
  - Assert the drawing toolbar is lazy-loaded with a fallback.

- `fx-trading-platform/package.json`
  - Add a `web:bundle-budget` script.

- `fx-trading-platform/scripts/verify-architecture.mjs`
  - Add one content check preventing `MarketSidePanel` from importing `../../pages/trading`.

### Final Report

- `fx-trading-platform/docs/frontend-follow-up-optimization-completion-2026-06-13.md`
  - Add a short completion report after all tasks pass.

---

## Success Criteria

All of these must be true before reporting completion:

- `MarketSidePanel.tsx` has no import from `../../pages/trading`.
- `quoteMarketDataAdapter.ts`, `quoteMarketDataSnapshot.ts`, and `MarketSidePanel.tsx` do not import from `pages/trading`.
- `ChartWorkspace.tsx` lazy-loads `ChartDrawingToolbar`.
- Production build emits separate chunks for:
  - `TradingPage`
  - `MobileTradingTerminal`
  - `IndicatorSettingsModal`
  - `ChartDrawingToolbar`
- The following commands pass:
  - `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"`
  - `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"`
  - `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"`
  - `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:bundle-budget"`
  - `cmd.exe /d /s /c "pnpm.cmd type-check"`
- Browser QA verifies `/trading` on desktop and mobile with no console errors.

---

## Task 0: Baseline Snapshot

**Files:**
- Read-only: repository state and current verification outputs.

- [ ] **Step 0.1: Inspect current worktree**

Run:

```powershell
git status --short
```

Expected:

- There may be existing untracked project files in `fx-trading-platform`.
- Do not revert unrelated files.
- Record whether any files from the target file map are already modified.

- [ ] **Step 0.2: Run current focused tests**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\components\market-side-panel\OrderBook.test.ts src\components\market-side-panel\quoteMarketDataAdapter.test.ts src\pages\trading\components\TerminalSkeleton.test.ts src\pages\trading\components\ChartWorkspace.test.ts"
```

Expected:

- PASS before changes.
- This establishes the current behavior before writing failing assertions.

- [ ] **Step 0.3: Build once and record chunk names**

Run:

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
Get-ChildItem -LiteralPath "fx-trading-platform\apps\web\dist\assets" -File | Where-Object { $_.Name -match "TradingPage|MobileTradingTerminal|IndicatorSettingsModal|ChartDrawingToolbar" } | Select-Object Name,Length
```

Expected before Task 2:

- `TradingPage-*.js` exists.
- `MobileTradingTerminal-*.js` exists.
- `IndicatorSettingsModal-*.js` exists.
- `ChartDrawingToolbar-*.js` does not exist yet.

---

## Task 1: Move OrderBookSkeleton Into Market Side Panel

**Files:**
- Create: `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.tsx`
- Create: `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.module.css`
- Modify: `fx-trading-platform/apps/web/src/components/market-side-panel/MarketSidePanel.tsx`
- Modify: `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBook.test.ts`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.tsx`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.module.css`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.test.ts`

### Task 1 Goal

Remove the reusable market side panel's dependency on the `pages/trading` directory. This finishes the audit's decoupling direction without moving unrelated table skeleton code.

- [ ] **Step 1.1: Update the failing dependency test first**

Edit `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBook.test.ts`.

Change the first test to this:

```ts
  it('shows a market-owned skeleton while the first market snapshot is empty', () => {
    assert.match(sidePanelSource, /import \{ OrderBookSkeleton \} from '\.\/OrderBookSkeleton'/)
    assert.match(sidePanelSource, /OrderBookSkeleton/)
    assert.match(sidePanelSource, /isInitialMarketSnapshot/)
    assert.doesNotMatch(sidePanelSource, /\.\.\/\.\.\/pages\/trading/)
  })
```

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\components\market-side-panel\OrderBook.test.ts"
```

Expected:

- FAIL because `MarketSidePanel.tsx` still imports `OrderBookSkeleton` from `../../pages/trading/components/TerminalSkeleton`.

- [ ] **Step 1.2: Create the market-side-panel skeleton component**

Create `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.tsx`:

```tsx
import styles from './OrderBookSkeleton.module.css'

type OrderBookSkeletonProps = {
  rows?: number
}

export function OrderBookSkeleton({ rows = 14 }: OrderBookSkeletonProps) {
  return (
    <div className={styles.orderBookSkeleton} role="status" aria-live="polite" aria-label="正在同步盘口">
      <div className={styles.headerRow} aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
      <div className={styles.rows} aria-hidden="true">
        {Array.from({ length: rows }, (_, index) => (
          <span key={index} className={index % 2 === 0 ? styles.askRow : styles.bidRow} />
        ))}
      </div>
    </div>
  )
}
```

Create `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.module.css`:

```css
.orderBookSkeleton {
  --market-skeleton-surface: var(--trading-surface-2);
  --market-skeleton-line: var(--trading-surface-3);
  --market-skeleton-highlight: var(--trading-border-strong);
  min-width: 0;
  min-height: 180px;
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  background: var(--market-skeleton-surface);
  color: var(--trading-muted);
  overflow: hidden;
}

.headerRow {
  min-height: 28px;
  display: grid;
  grid-template-columns: minmax(92px, 1fr) minmax(86px, 0.9fr) minmax(86px, 0.9fr);
  gap: 8px;
}

.rows {
  display: grid;
  gap: 6px;
}

.headerRow span,
.rows span {
  position: relative;
  overflow: hidden;
  border-radius: 4px;
  background: var(--market-skeleton-line);
}

.headerRow span {
  height: 12px;
}

.rows span {
  height: 20px;
}

.askRow {
  box-shadow: inset 3px 0 0 var(--trading-sell);
}

.bidRow {
  box-shadow: inset 3px 0 0 var(--trading-buy);
}

.headerRow span::after,
.rows span::after {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, var(--market-skeleton-highlight), transparent);
  opacity: 0.34;
  transform: translateX(-100%);
  animation: market-skeleton-scan 1400ms ease-in-out infinite;
  content: "";
}

@keyframes market-skeleton-scan {
  0% {
    transform: translateX(-100%);
  }

  100% {
    transform: translateX(100%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .headerRow span::after,
  .rows span::after {
    animation: none;
    transform: none;
    opacity: 0.18;
  }
}

@media (max-width: 760px) {
  .orderBookSkeleton {
    min-height: 150px;
    padding: 8px 6px;
  }

  .headerRow {
    grid-template-columns: minmax(64px, 1fr) minmax(54px, 0.9fr);
  }

  .headerRow span:nth-child(3) {
    display: none;
  }
}
```

- [ ] **Step 1.3: Update MarketSidePanel import**

In `fx-trading-platform/apps/web/src/components/market-side-panel/MarketSidePanel.tsx`, replace:

```ts
import { OrderBookSkeleton } from '../../pages/trading/components/TerminalSkeleton'
```

with:

```ts
import { OrderBookSkeleton } from './OrderBookSkeleton'
```

- [ ] **Step 1.4: Reduce TerminalSkeleton to table-only ownership**

Replace `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.tsx` with:

```tsx
import styles from './TerminalSkeleton.module.css'

type SkeletonRowsProps = {
  rows?: number
}

export function TableSkeleton({ rows = 4 }: SkeletonRowsProps) {
  return (
    <div className={styles.tableSkeleton} role="status" aria-live="polite" aria-label="正在同步账户表格">
      <div className={styles.tableHeader} aria-hidden="true">
        <span />
        <span />
        <span />
        <span />
      </div>
      <div className={styles.tableRows} aria-hidden="true">
        {Array.from({ length: rows }, (_, index) => (
          <span key={index} />
        ))}
      </div>
    </div>
  )
}
```

Replace `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.module.css` with:

```css
.tableSkeleton {
  --terminal-skeleton-surface: var(--trading-surface-2);
  --terminal-skeleton-line: var(--trading-surface-3);
  --terminal-skeleton-highlight: var(--trading-border-strong);
  min-width: 0;
  min-height: 180px;
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  background: var(--terminal-skeleton-surface);
  color: var(--trading-muted);
  overflow: hidden;
}

.tableHeader {
  min-height: 28px;
  display: grid;
  grid-template-columns: repeat(4, minmax(54px, 1fr));
  gap: 8px;
}

.tableRows {
  display: grid;
  gap: 6px;
}

.tableHeader span,
.tableRows span {
  position: relative;
  overflow: hidden;
  border-radius: 4px;
  background: var(--terminal-skeleton-line);
}

.tableHeader span {
  height: 12px;
}

.tableRows span {
  height: 28px;
}

.tableHeader span::after,
.tableRows span::after {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, var(--terminal-skeleton-highlight), transparent);
  opacity: 0.34;
  transform: translateX(-100%);
  animation: terminal-skeleton-scan 1400ms ease-in-out infinite;
  content: "";
}

@keyframes terminal-skeleton-scan {
  0% {
    transform: translateX(-100%);
  }

  100% {
    transform: translateX(100%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .tableHeader span::after,
  .tableRows span::after {
    animation: none;
    transform: none;
    opacity: 0.18;
  }
}

@media (max-width: 760px) {
  .tableSkeleton {
    min-height: 150px;
    padding: 8px 6px;
  }
}
```

- [ ] **Step 1.5: Update TerminalSkeleton test**

In `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.test.ts`, change the first test to:

```ts
  it('exports the table skeleton used by account regions', () => {
    assert.equal(existsSync(componentPath), true, 'TerminalSkeleton.tsx should exist')

    const source = readFileSync(componentPath, 'utf8')
    assert.match(source, /export function TableSkeleton/)
    assert.doesNotMatch(source, /export function OrderBookSkeleton/)
    assert.match(source, /role="status"/)
    assert.match(source, /aria-live="polite"/)
  })
```

Change the second test to:

```ts
  it('uses stable dimensions and reduced-motion support', () => {
    assert.equal(existsSync(stylesPath), true, 'TerminalSkeleton.module.css should exist')

    const styles = readFileSync(stylesPath, 'utf8')
    assert.match(styles, /prefers-reduced-motion:\s*reduce/)
    assert.match(styles, /--terminal-skeleton-surface/)
    assert.match(styles, /min-height/)
    assert.match(styles, /@keyframes terminal-skeleton-scan/)
    assert.doesNotMatch(styles, /\.orderBookSkeleton/)
  })
```

- [ ] **Step 1.6: Run focused tests**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\components\market-side-panel\OrderBook.test.ts src\components\market-side-panel\quoteMarketDataAdapter.test.ts src\pages\trading\components\TerminalSkeleton.test.ts"
```

Expected:

- PASS.

- [ ] **Step 1.7: Search for page-layer dependency residue**

Run:

```powershell
rg -n -F "../../pages/trading" fx-trading-platform/apps/web/src/components/market-side-panel
```

Expected:

- No matches.

- [ ] **Step 1.8: Commit checkpoint**

If committing is requested, run:

```powershell
git add fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.tsx fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.module.css fx-trading-platform/apps/web/src/components/market-side-panel/MarketSidePanel.tsx fx-trading-platform/apps/web/src/components/market-side-panel/OrderBook.test.ts fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.tsx fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.module.css fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.test.ts
git commit -m "refactor(web): move order book skeleton into market side panel"
```

---

## Task 2: Lazy Split ChartDrawingToolbar

**Files:**
- Modify: `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.tsx`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.module.css`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.test.ts`

### Task 2 Goal

Move drawing toolbar code out of the initial `TradingPage` chunk while keeping chart layout stable on first paint.

- [ ] **Step 2.1: Write the failing lazy-load test**

Add this test to `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.test.ts`:

```ts
  it('lazy loads the drawing toolbar with a stable rail fallback', () => {
    assert.match(workspaceSource, /const ChartDrawingToolbar = lazy\(/)
    assert.match(workspaceSource, /import\('\.\/ChartDrawingToolbar'\)/)
    assert.match(workspaceSource, /function DrawingToolbarFallback\(\)/)
    assert.match(workspaceSource, /className=\{styles\.drawingToolbarLoading\}/)
    assert.match(workspaceStyles, /\.drawingToolbarLoading\s*{/)
  })
```

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\pages\trading\components\ChartWorkspace.test.ts"
```

Expected:

- FAIL because `ChartDrawingToolbar` is still statically imported and there is no fallback.

- [ ] **Step 2.2: Convert the static toolbar import to lazy import**

In `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.tsx`, remove:

```ts
import { ChartDrawingToolbar } from './ChartDrawingToolbar'
```

Add this after the existing `IndicatorSettingsModal` lazy declaration:

```ts
const ChartDrawingToolbar = lazy(() =>
  import('./ChartDrawingToolbar').then((module) => ({ default: module.ChartDrawingToolbar }))
)
```

- [ ] **Step 2.3: Wrap toolbar render in Suspense**

Replace the direct `ChartDrawingToolbar` render with:

```tsx
        <Suspense fallback={<DrawingToolbarFallback />}>
          <ChartDrawingToolbar
            drawingsHidden={drawingsHidden}
            indicatorsHidden={indicatorsHidden}
            settings={settings.drawingToolSettings}
            onDrawingToolChange={handleDrawingToolChange}
            onDrawingMagnetModeChange={onDrawingMagnetModeChange}
            onClearDrawings={handleClearDrawings}
            onToggleAllHidden={handleToggleAllHidden}
            onToggleDrawingsHidden={() => setDrawingsHidden((hidden) => !hidden)}
            onToggleIndicatorsHidden={() => setIndicatorsHidden((hidden) => !hidden)}
          />
        </Suspense>
```

Add this function near the bottom of the same file, before `canUseNativeFullscreen`:

```tsx
function DrawingToolbarFallback() {
  return (
    <div className={styles.drawingToolbarLoading} role="status" aria-label="正在加载绘图工具">
      <span aria-hidden="true" />
      <span aria-hidden="true" />
      <span aria-hidden="true" />
      <span aria-hidden="true" />
    </div>
  )
}
```

- [ ] **Step 2.4: Add stable fallback styling**

Add this to `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.module.css`:

```css
.drawingToolbarLoading {
  width: 46px;
  min-width: 46px;
  min-height: 320px;
  display: grid;
  align-content: start;
  justify-items: center;
  gap: 8px;
  padding: 8px 6px;
  border-right: 1px solid var(--trading-border);
  background: var(--trading-surface-2);
}

.drawingToolbarLoading span {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--trading-surface-3);
}

@media (max-width: 768px) {
  .drawingToolbarLoading {
    display: none;
  }
}
```

If `ChartWorkspace.module.css` already has an `@media (max-width: 768px)` block, place the mobile rule inside that existing block instead of creating a duplicate block.

- [ ] **Step 2.5: Run focused tests**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\pages\trading\components\ChartWorkspace.test.ts src\pages\trading\components\ChartDrawingToolbar.test.ts src\pages\trading\components\KLineChartPanel.test.ts"
```

Expected:

- PASS.

- [ ] **Step 2.6: Build and verify the new chunk exists**

Run:

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
Get-ChildItem -LiteralPath "fx-trading-platform\apps\web\dist\assets" -File | Where-Object { $_.Name -match "TradingPage|ChartDrawingToolbar|IndicatorSettingsModal|MobileTradingTerminal" } | Select-Object Name,Length
```

Expected:

- `ChartDrawingToolbar-*.js` exists.
- `TradingPage-*.js` still exists.
- `MobileTradingTerminal-*.js` still exists.
- `IndicatorSettingsModal-*.js` still exists.

- [ ] **Step 2.7: Commit checkpoint**

If committing is requested, run:

```powershell
git add fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.tsx fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.module.css fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.test.ts
git commit -m "perf(web): lazy load chart drawing toolbar"
```

---

## Task 3: Add Bundle Budget Verification

**Files:**
- Create: `fx-trading-platform/scripts/check-web-bundle-budget.mjs`
- Modify: `fx-trading-platform/package.json`

### Task 3 Goal

Make bundle expectations executable. The build should fail if the trading terminal regresses by merging lazy chunks back into `TradingPage` or if key lazy chunks disappear.

- [ ] **Step 3.1: Run the missing budget script to establish failure**

Run:

```powershell
cmd.exe /d /s /c "node fx-trading-platform\scripts\check-web-bundle-budget.mjs"
```

Expected:

- FAIL because the script does not exist yet.

- [ ] **Step 3.2: Add the budget script**

Create `fx-trading-platform/scripts/check-web-bundle-budget.mjs`:

```js
import { existsSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')
const assetsDir = join(projectRoot, 'apps/web/dist/assets')

const budgets = [
  {
    label: 'TradingPage JS',
    pattern: /^TradingPage-.*\.js$/,
    maxBytes: 380_000
  },
  {
    label: 'MobileTradingTerminal JS',
    pattern: /^MobileTradingTerminal-.*\.js$/,
    maxBytes: 20_000
  },
  {
    label: 'IndicatorSettingsModal JS',
    pattern: /^IndicatorSettingsModal-.*\.js$/,
    maxBytes: 20_000
  },
  {
    label: 'ChartDrawingToolbar JS',
    pattern: /^ChartDrawingToolbar-.*\.js$/,
    maxBytes: 80_000
  }
]

if (!existsSync(assetsDir)) {
  fail(`Missing build assets directory: ${assetsDir}. Run web:build first.`)
}

const files = readdirSync(assetsDir)
const failures = []
const report = []

for (const budget of budgets) {
  const matches = files.filter((file) => budget.pattern.test(file))
  if (matches.length === 0) {
    failures.push(`Missing required chunk: ${budget.label}`)
    continue
  }

  for (const file of matches) {
    const size = statSync(join(assetsDir, file)).size
    report.push({ label: budget.label, file, size, maxBytes: budget.maxBytes })
    if (size > budget.maxBytes) {
      failures.push(`${budget.label} is ${size} bytes, expected <= ${budget.maxBytes} bytes (${file})`)
    }
  }
}

for (const item of report) {
  console.log(`${item.label}: ${item.size}/${item.maxBytes} bytes (${item.file})`)
}

if (failures.length > 0) {
  fail(`Bundle budget failed:\n${failures.map((failure) => `- ${failure}`).join('\n')}`)
}

console.log('Bundle budget passed.')

function fail(message) {
  console.error(message)
  process.exit(1)
}
```

- [ ] **Step 3.3: Add npm script**

In `fx-trading-platform/package.json`, add:

```json
"web:bundle-budget": "npm run web:build && node scripts/check-web-bundle-budget.mjs"
```

The `scripts` section should include this near the existing web scripts:

```json
{
  "verify:architecture": "node scripts/verify-architecture.mjs",
  "smoke:backend": "node scripts/smoke-backend.mjs",
  "smoke:admin": "node scripts/smoke-admin.mjs",
  "smoke:real-trading-loop": "node scripts/smoke-real-trading-loop.mjs",
  "smoke:user-core-pages": "node scripts/smoke-user-core-pages.mjs",
  "smoke:trading-login-gate": "node scripts/smoke-trading-login-gate.mjs",
  "web:dev": "npm --workspace apps/web run dev",
  "web:test": "npm --workspace apps/web run test",
  "web:build": "npm --workspace apps/web run build",
  "web:bundle-budget": "npm run web:build && node scripts/check-web-bundle-budget.mjs",
  "admin:dev": "npm --workspace apps/admin run dev",
  "admin:build": "npm --workspace apps/admin run build"
}
```

If the local `package.json` has extra scripts, preserve them and add only `web:bundle-budget`.

- [ ] **Step 3.4: Run budget verification**

Run:

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:bundle-budget"
```

Expected:

- PASS.
- Output includes `Bundle budget passed.`
- Output includes all four chunk labels.

- [ ] **Step 3.5: Commit checkpoint**

If committing is requested, run:

```powershell
git add fx-trading-platform/package.json fx-trading-platform/scripts/check-web-bundle-budget.mjs
git commit -m "test(web): enforce trading bundle budget"
```

---

## Task 4: Extend Architecture Verification For The Remaining Boundary

**Files:**
- Modify: `fx-trading-platform/scripts/verify-architecture.mjs`

### Task 4 Goal

Make the page-layer boundary executable so `MarketSidePanel` cannot re-import `pages/trading` after this cleanup.

- [ ] **Step 4.1: Add content checks**

In `fx-trading-platform/scripts/verify-architecture.mjs`, add these entries to `contentChecks`:

```js
  ['apps/web/src/components/market-side-panel/MarketSidePanel.tsx', './OrderBookSkeleton'],
  ['apps/web/src/components/market-side-panel/OrderBookSkeleton.tsx', 'export function OrderBookSkeleton']
```

Add this new array after `forbiddenContentChecks`:

```js
const forbiddenFrontendImports = [
  ['apps/web/src/components/market-side-panel/MarketSidePanel.tsx', '../../pages/trading'],
  ['apps/web/src/components/market-side-panel/quoteMarketDataAdapter.ts', '../../pages/trading'],
  ['apps/web/src/components/market-side-panel/quoteMarketDataSnapshot.ts', '../../pages/trading']
]
```

Add this loop after the existing `forbiddenContentChecks` loop:

```js
for (const [file, forbidden] of forbiddenFrontendImports) {
  const path = join(root, file)
  if (!existsSync(path)) {
    failures.push(`Missing frontend import target: ${file}`)
    continue
  }
  const content = readFileSync(path, 'utf8')
  if (content.includes(forbidden)) {
    failures.push(`Forbidden frontend import "${forbidden}" found in ${file}`)
  }
}
```

- [ ] **Step 4.2: Run architecture verification**

Run:

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

Expected:

- PASS.

- [ ] **Step 4.3: Commit checkpoint**

If committing is requested, run:

```powershell
git add fx-trading-platform/scripts/verify-architecture.mjs
git commit -m "test(web): guard market side panel boundaries"
```

---

## Task 5: Browser QA And Completion Report

**Files:**
- Create: `fx-trading-platform/docs/frontend-follow-up-optimization-completion-2026-06-13.md`

### Task 5 Goal

Verify the optimized page in a real browser and document exact evidence.

- [ ] **Step 5.1: Start the web server**

Run:

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:dev"
```

If port `5173` is busy, use the Vite-assigned port shown in stdout. Keep the server running during browser QA.

Verify HTTP:

```powershell
try { (Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:5173/trading' -TimeoutSec 5).StatusCode } catch { $_.Exception.Message }
```

Expected:

- `200`

- [ ] **Step 5.2: Browser QA desktop**

Use the Browser plugin, not a separate browser tool, when available.

Open:

```text
http://127.0.0.1:5173/trading
```

Desktop viewport checks:

- The Markets rail is visible.
- The symbol header is visible.
- `KLineCharts 图表区` is visible and non-empty.
- `BTC-USDT 交易面板` is visible and non-empty.
- Click the chart toolbar `设置` button.
- `指标设置` dialog appears.
- Close the dialog.
- The drawing toolbar area renders or its fallback is replaced.
- Console error log is empty.

Record:

- viewport size
- visible chart area dimensions
- visible trade panel dimensions
- console error count

- [ ] **Step 5.3: Browser QA mobile**

Set viewport to `390 x 844`.

Open:

```text
http://127.0.0.1:5173/trading
```

Mobile checks:

- `移动端交易终端` is visible.
- Desktop `交易工作台` is not visible.
- `正在加载移动端交易终端` fallback is not stuck after load.
- The bottom mobile action bar is visible.
- Console error log is empty.

Record:

- viewport size
- mobile terminal dimensions
- whether fallback remains visible
- console error count

- [ ] **Step 5.4: Run full final verification**

Run:

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:bundle-budget"
cmd.exe /d /s /c "pnpm.cmd type-check"
```

Expected:

- All commands PASS.
- `web:test` reports all tests passing.
- `verify:architecture` prints `Architecture verification passed.`
- `web:bundle-budget` prints `Bundle budget passed.`
- `type-check` exits with code `0`.

- [ ] **Step 5.5: Write completion report**

Create `fx-trading-platform/docs/frontend-follow-up-optimization-completion-2026-06-13.md`:

```markdown
# Frontend Follow-up Optimization Completion

Date: 2026-06-13

## Completed

- Moved `OrderBookSkeleton` into `components/market-side-panel`.
- Removed the last `MarketSidePanel` import from `pages/trading`.
- Lazy-loaded `ChartDrawingToolbar` with a stable rail fallback.
- Added bundle budget verification for trading terminal chunks.
- Extended architecture verification for market side panel boundaries.

## Verification

- `npm.cmd --prefix fx-trading-platform run web:test`: PASS, [paste exact test count]
- `npm.cmd --prefix fx-trading-platform run verify:architecture`: PASS
- `npm.cmd --prefix fx-trading-platform run web:bundle-budget`: PASS
- `pnpm.cmd type-check`: PASS

## Browser QA

Desktop `/trading`:

- Viewport: [paste exact viewport]
- Chart area: [paste width x height]
- Trade panel: [paste width x height]
- Indicator settings dialog opened: yes
- Console errors: 0

Mobile `/trading`:

- Viewport: [paste exact viewport]
- Mobile terminal: [paste width x height]
- Desktop workspace visible: no
- Loading fallback stuck: no
- Console errors: 0

## Notes

- Root chart-library large-class refactor was intentionally not included.
- No new runtime dependencies were added.
```

Replace bracketed report values with the exact command/browser outputs gathered in this task. Do not leave bracketed values in the final document.

- [ ] **Step 5.6: Commit checkpoint**

If committing is requested, run:

```powershell
git add fx-trading-platform/docs/frontend-follow-up-optimization-completion-2026-06-13.md
git commit -m "docs(web): record frontend follow-up verification"
```

---

## Final Review Checklist

Run this before reporting done:

- [ ] `rg -n -F "../../pages/trading" fx-trading-platform/apps/web/src/components/market-side-panel` returns no matches.
- [ ] `rg -n "const ChartDrawingToolbar = lazy|import\('\./ChartDrawingToolbar'\)" fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.tsx` returns both lazy-load signals.
- [ ] `Get-ChildItem fx-trading-platform/apps/web/dist/assets | Where-Object { $_.Name -match "ChartDrawingToolbar" }` returns one JS chunk after `web:build`.
- [ ] `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"` passes.
- [ ] `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"` passes.
- [ ] `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:bundle-budget"` passes.
- [ ] `cmd.exe /d /s /c "pnpm.cmd type-check"` passes.
- [ ] Browser desktop QA has no console errors.
- [ ] Browser mobile QA has no console errors.

---

## Expected Final Summary For User

When the implementation is complete, report in Chinese:

```markdown
已完成后续前端优化：

- `MarketSidePanel` 已脱离 `pages/trading`，`OrderBookSkeleton` 移到 market-side-panel 内部。
- `ChartDrawingToolbar` 已 lazy split，构建产物出现独立 `ChartDrawingToolbar-*.js` chunk。
- 新增 `web:bundle-budget`，防止 TradingPage 和关键 lazy chunk 回退。
- 架构校验已覆盖 market-side-panel 反向依赖。

验证：
- web:test: PASS，N/N
- verify:architecture: PASS
- web:bundle-budget: PASS
- pnpm type-check: PASS
- Browser 桌面/移动 `/trading`: PASS，console errors 0
```

Use exact test counts and chunk sizes from the executed commands.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-13-frontend-follow-up-optimization.md`.

Two execution options:

1. **Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** - execute tasks in this session using `superpowers:executing-plans`, with checkpoints after each task.

Choose one execution mode before editing code.
