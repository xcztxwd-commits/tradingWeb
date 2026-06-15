# Fullstack Follow-up Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the remaining frontend/backend architecture debt found after the 2026-06-12 fullstack audit without changing trading behavior.

**Architecture:** Treat this as a cleanup-and-boundary project, not a feature project. Start with small, testable cleanup, then move market data ownership into `features/market`, then reduce backend admin/audit coupling, then split dense UI files. Every task must preserve the already-fixed trading bugs: selected-symbol order submission, `OPEN` guarded close, pending-order claim, admin fund idempotency, order duplicate replay, and guest watch-only login flow.

**Tech Stack:** React 19, Vite, TypeScript, Node `node:test`, Spring Boot 3, Java 21, MyBatis-Plus, Flyway, Hutool JSON, Maven, PowerShell on Windows.

---

## Current State Snapshot

Use this snapshot to avoid rediscovering the same facts.

- Already fixed:
  - Old `apps/web/src/pages/trade/TradePage.tsx` and `apps/web/src/components/order-panel/OrderPanel.tsx` are gone.
  - `/trade` now redirects to `/trading`.
  - `parseSymbolAssets` and `formatDecimal` are centralized in `apps/web/src/features/trading/utils`.
  - Order symbol fallback bug is covered by `tradePanelMarket.test.ts`.
  - `offline-preview` is removed from trading UI state.
  - `FxBaseMapper.findAll(...)` takes a sort whitelist instead of raw `orderColumn`.
  - Position close uses `PositionRepository.closeIfOpen(...)`.
  - Pending order execution uses `OrderRepository.claimPending(...)`.
  - Order idempotency catches `DataIntegrityViolationException` and rereads existing orders.
  - Admin fund idempotency has `V25__admin_fund_operation_idempotency.sql` plus `insertIfAbsent(...)`.
  - `AdminFeatureOperationService` uses `List<AdminFeatureActionHandler>`.

- Still present:
  - `apps/web/src/styles.css` still contains dead `.order-panel` and related old order form styles.
  - `apps/web/src/components/market-side-panel/MarketSidePanel.tsx` imports `OrderBookSkeleton` from `pages/trading/components/TerminalSkeleton`.
  - `apps/web/src/features/market/tradingMarketAdapters.ts` imports market side-panel types from `components/market-side-panel/types`.
  - `TradePanel.tsx` imports `useMarketDataSnapshot` from `components/market-side-panel/marketDataStore`.
  - `AdminFeatureCatalogService.java` is still a 725-line static catalog builder.
  - Audit details are only partially unified; several admin services still hand-build JSON details.
  - `TradingPage.tsx` is still roughly 490 lines; `TradePanel.tsx` is still roughly 315 lines.
  - Root KLineCharts files are large: `src/Store.ts`, `src/Chart.ts`, `src/common/EventHandler.ts`.
  - Template comments like `执行 xxx 业务流程` still exist in backend production code.

## Non-goals

- Do not rework authentication, login prompt behavior, trading order semantics, database schema already created by applied Flyway migrations, or KLineCharts rendering algorithms.
- Do not mechanically add Chinese comments to every getter, DTO, mapper, or component.
- Do not delete pre-existing unrelated code unless a test in this plan proves it is retired or unreachable.
- Do not rewrite large files in one pass. Split only along boundaries proven by tests.

## Execution Rules

- Execute tasks in order unless using subagents on disjoint files.
- Use a fresh branch such as `codex/fullstack-followup-optimization`.
- Before each task, run `git status --short` and avoid reverting user changes.
- Each task must begin with a failing test or failing architecture rule.
- Each task must end with the task-specific verification command passing.
- Commit after each task if the user wants commits.
- Use new Flyway versioned migrations for schema changes. Do not edit old `V*.sql` migrations that may already be applied.

## External References

- React `lazy` returns a component that must render inside `Suspense` while loading; use that pattern when keeping mobile/desktop route chunks split: https://react.dev/reference/react/lazy
- Spring transaction boundaries should stay on public service methods that are invoked through Spring-managed beans: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
- Flyway versioned migrations are applied once and tracked in schema history; roll changes forward with new migrations: https://documentation.red-gate.com/fd/versioned-migrations-273973333.html

---

## Task 1: Remove Retired Frontend Order Panel CSS

**Purpose:** Finish the old `/trade` and `OrderPanel` cleanup by removing dead global CSS.

**Files:**
- Modify: `apps/web/src/app/App.test.ts`
- Modify: `apps/web/src/styles.css`

- [ ] **Step 1: Add a failing test that guards retired CSS**

Append this test to `apps/web/src/app/App.test.ts`. Keep the existing imports and add `stylesPath` near `sourcePath`.

```ts
const stylesPath = join(currentDir, '..', 'styles.css')
```

```ts
  it('removes retired order panel global styles', () => {
    const styles = readFileSync(stylesPath, 'utf8')

    assert.doesNotMatch(styles, /\.order-panel\b/)
    assert.doesNotMatch(styles, /\.order-actions\b/)
  })
```

- [ ] **Step 2: Run the focused frontend test and verify it fails**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\app\App.test.ts"
```

Expected: FAIL on `removes retired order panel global styles` because `.order-panel` and `.order-actions` are still in `styles.css`.

- [ ] **Step 3: Remove only retired selectors from `styles.css`**

Delete these selector blocks from `apps/web/src/styles.css`:

```css
.order-panel {
  padding-bottom: 14px;
}

.order-panel label {
  display: grid;
  gap: 6px;
  padding: 12px 14px 0;
  color: #50606d;
  font-size: 13px;
}

.order-panel input,
.order-panel select {
  min-height: 38px;
  border: 1px solid #d6dee7;
  border-radius: 6px;
  padding: 0 10px;
  background: #ffffff;
}

.order-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 16px 14px 0;
}

.order-actions button {
  min-height: 40px;
  border: 0;
  border-radius: 8px;
  color: #ffffff;
  font-weight: 700;
}
```

Before deleting `.two-inputs`, run:

```powershell
rg -n "two-inputs" fx-trading-platform\apps\web\src
```

If the only hit is `styles.css`, delete the `.two-inputs` block too. If there are JSX usages, leave it.

- [ ] **Step 4: Run the focused frontend test and verify it passes**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\app\App.test.ts"
```

Expected: PASS.

- [ ] **Step 5: Run grep verification**

Run:

```powershell
rg -n "\.order-panel|\.order-actions|components\\order-panel|pages\\trade" fx-trading-platform\apps\web\src
```

Expected: no production source hits. Test file hits for retired checks are acceptable.

- [ ] **Step 6: Commit**

```powershell
git add fx-trading-platform/apps/web/src/app/App.test.ts fx-trading-platform/apps/web/src/styles.css
git commit -m "chore(web): remove retired order panel styles"
```

---

## Task 2: Move Skeleton Surfaces Out of `pages/trading`

**Purpose:** Remove the concrete reverse dependency from shared `components/market-side-panel` to page-owned `pages/trading/components`.

**Files:**
- Create: `apps/web/src/components/loading/TerminalSkeleton.tsx`
- Create: `apps/web/src/components/loading/TerminalSkeleton.module.css`
- Create: `apps/web/src/components/loading/TerminalSkeleton.test.ts`
- Modify: `apps/web/src/pages/trading/components/TerminalSkeleton.tsx`
- Modify: `apps/web/src/pages/trading/components/TerminalSkeleton.test.ts`
- Modify: `apps/web/src/components/market-side-panel/MarketSidePanel.tsx`
- Modify: `apps/web/src/components/market-side-panel/OrderBook.test.ts`

- [ ] **Step 1: Add a failing boundary test**

Append to `apps/web/src/components/market-side-panel/OrderBook.test.ts`:

```ts
  it('does not import loading skeletons from page-owned trading modules', () => {
    const sidePanelSource = readFileSync(join(currentDir, 'MarketSidePanel.tsx'), 'utf8')

    assert.doesNotMatch(sidePanelSource, /pages\/trading/)
    assert.doesNotMatch(sidePanelSource, /\.\.\/\.\.\/pages\/trading/)
    assert.match(sidePanelSource, /components\/loading\/TerminalSkeleton/)
  })
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\components\market-side-panel\OrderBook.test.ts"
```

Expected: FAIL because `MarketSidePanel.tsx` imports `../../pages/trading/components/TerminalSkeleton`.

- [ ] **Step 3: Create shared skeleton component**

Move the current contents of `apps/web/src/pages/trading/components/TerminalSkeleton.tsx` to `apps/web/src/components/loading/TerminalSkeleton.tsx`.

Use this import in the new file:

```ts
import styles from './TerminalSkeleton.module.css'
```

Move `apps/web/src/pages/trading/components/TerminalSkeleton.module.css` to `apps/web/src/components/loading/TerminalSkeleton.module.css` unchanged.

- [ ] **Step 4: Keep the old page import path as a compatibility re-export**

Replace `apps/web/src/pages/trading/components/TerminalSkeleton.tsx` with:

```ts
export { OrderBookSkeleton, TableSkeleton } from '../../../components/loading/TerminalSkeleton'
```

Replace `apps/web/src/pages/trading/components/TerminalSkeleton.module.css` with a deleted file if no code imports it. Verify first:

```powershell
rg -n "TerminalSkeleton\.module\.css|pages/trading/components/TerminalSkeleton\.module\.css" fx-trading-platform\apps\web\src
```

Expected before deletion: no imports except the old `TerminalSkeleton.tsx`.

- [ ] **Step 5: Update market side-panel import**

Change `apps/web/src/components/market-side-panel/MarketSidePanel.tsx`:

```ts
import { OrderBookSkeleton } from '../loading/TerminalSkeleton'
```

The path is `../loading/TerminalSkeleton` because both folders live under `src/components`.

- [ ] **Step 6: Add shared skeleton test**

Create `apps/web/src/components/loading/TerminalSkeleton.test.ts`:

```ts
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const componentPath = join(currentDir, 'TerminalSkeleton.tsx')
const stylesPath = join(currentDir, 'TerminalSkeleton.module.css')

describe('shared terminal skeleton components', () => {
  it('exports order book and table skeleton surfaces from shared components', () => {
    assert.equal(existsSync(componentPath), true)

    const source = readFileSync(componentPath, 'utf8')
    assert.match(source, /export function OrderBookSkeleton/)
    assert.match(source, /export function TableSkeleton/)
    assert.match(source, /role="status"/)
    assert.match(source, /aria-live="polite"/)
  })

  it('keeps skeleton styling outside page-owned trading modules', () => {
    assert.equal(existsSync(stylesPath), true)

    const styles = readFileSync(stylesPath, 'utf8')
    assert.match(styles, /prefers-reduced-motion:\s*reduce/)
    assert.match(styles, /--terminal-skeleton-surface/)
    assert.match(styles, /min-height/)
    assert.match(styles, /@keyframes terminal-skeleton-scan/)
  })
})
```

- [ ] **Step 7: Update page skeleton test**

Change `apps/web/src/pages/trading/components/TerminalSkeleton.test.ts` so it asserts the page file is only a re-export:

```ts
  it('keeps page skeleton imports as compatibility re-exports', () => {
    const source = readFileSync(componentPath, 'utf8')

    assert.match(source, /export \{ OrderBookSkeleton, TableSkeleton \}/)
    assert.match(source, /components\/loading\/TerminalSkeleton/)
  })
```

Remove assertions that expect `role="status"` in the page file, because the implementation now lives in `components/loading`.

- [ ] **Step 8: Run tests**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\components\loading\TerminalSkeleton.test.ts src\pages\trading\components\TerminalSkeleton.test.ts src\components\market-side-panel\OrderBook.test.ts"
```

Expected: PASS.

- [ ] **Step 9: Commit**

```powershell
git add fx-trading-platform/apps/web/src/components/loading fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.tsx fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.test.ts fx-trading-platform/apps/web/src/components/market-side-panel/MarketSidePanel.tsx fx-trading-platform/apps/web/src/components/market-side-panel/OrderBook.test.ts
git commit -m "refactor(web): move terminal skeletons to shared loading components"
```

---

## Task 3: Move Market Data Types Into `features/market`

**Purpose:** Stop `features/market` from depending on UI component folders for domain types.

**Files:**
- Create: `apps/web/src/features/market/marketDataTypes.ts`
- Modify: `apps/web/src/features/market/tradingMarketAdapters.ts`
- Modify: `apps/web/src/components/market-side-panel/types.ts`
- Modify: `apps/web/src/features/market/tradingMarketApi.test.ts`
- Modify: `apps/web/src/components/market-side-panel/quoteMarketDataAdapter.test.ts`

- [ ] **Step 1: Add a failing architecture assertion**

Append to `apps/web/src/features/market/tradingMarketApi.test.ts`:

```ts
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
```

If those imports already exist, reuse them.

Add:

```ts
const currentDir = dirname(fileURLToPath(import.meta.url))

describe('market feature boundaries', () => {
  it('does not import market data types from component folders', () => {
    const source = readFileSync(join(currentDir, 'tradingMarketAdapters.ts'), 'utf8')

    assert.doesNotMatch(source, /components\/market-side-panel\/types/)
    assert.match(source, /from '\.\/marketDataTypes'/)
  })
})
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\features\market\tradingMarketApi.test.ts"
```

Expected: FAIL because `tradingMarketAdapters.ts` imports from `../../components/market-side-panel/types`.

- [ ] **Step 3: Create `marketDataTypes.ts`**

Move the type definitions from `apps/web/src/components/market-side-panel/types.ts` into `apps/web/src/features/market/marketDataTypes.ts`.

The new file must export at least these names:

```ts
export type OrderBookSide = 'bid' | 'ask'

export type OrderBookLevel = {
  price: number
  amount: number
  side: OrderBookSide
}

export type TradeItem = {
  id: string
  price: number
  amount: number
  side: 'buy' | 'sell'
  time: number
}

export type MarketDataSnapshot = {
  bids: Array<{ price: number; amount: number }>
  asks: Array<{ price: number; amount: number }>
  lastPrice: number
  lastPriceDirection: 'up' | 'down' | 'flat'
  recentTrades: TradeItem[]
  updatedAt: number
}
```

If the existing `types.ts` has additional exported names, move those too.

- [ ] **Step 4: Turn old `types.ts` into a compatibility re-export**

Replace `apps/web/src/components/market-side-panel/types.ts` with:

```ts
export type {
  MarketDataSnapshot,
  OrderBookLevel,
  OrderBookSide,
  TradeItem
} from '../../features/market/marketDataTypes'
```

Add any extra exported names that existed in the old file.

- [ ] **Step 5: Update market adapters import**

Change `apps/web/src/features/market/tradingMarketAdapters.ts`:

```ts
import type { MarketDataSnapshot, TradeItem } from './marketDataTypes'
```

- [ ] **Step 6: Run tests**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\features\market\tradingMarketApi.test.ts src\components\market-side-panel\quoteMarketDataAdapter.test.ts"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add fx-trading-platform/apps/web/src/features/market/marketDataTypes.ts fx-trading-platform/apps/web/src/features/market/tradingMarketAdapters.ts fx-trading-platform/apps/web/src/components/market-side-panel/types.ts fx-trading-platform/apps/web/src/features/market/tradingMarketApi.test.ts fx-trading-platform/apps/web/src/components/market-side-panel/quoteMarketDataAdapter.test.ts
git commit -m "refactor(web): move market data types into market feature"
```

---

## Task 4: Move Market Data Store And Adapter Ownership Into `features/market`

**Purpose:** Make `features/market` own quote/order-book/trade data orchestration while `components/market-side-panel` stays UI-only.

**Files:**
- Create: `apps/web/src/features/market/marketDataStore.ts`
- Create: `apps/web/src/features/market/quoteMarketDataSnapshot.ts`
- Create: `apps/web/src/features/market/quoteMarketDataAdapter.ts`
- Modify: `apps/web/src/components/market-side-panel/marketDataStore.ts`
- Modify: `apps/web/src/components/market-side-panel/quoteMarketDataSnapshot.ts`
- Modify: `apps/web/src/components/market-side-panel/quoteMarketDataAdapter.ts`
- Modify: `apps/web/src/features/trading/components/TradePanel.tsx`
- Modify: `apps/web/src/pages/trading/components/RightTradingPanel.tsx`
- Modify: related tests under `apps/web/src/components/market-side-panel`

- [ ] **Step 1: Add boundary test**

Append to `apps/web/src/features/market/tradingMarketApi.test.ts`:

```ts
  it('keeps market store and adapters in the market feature', () => {
    const tradePanel = readFileSync(join(currentDir, '..', 'trading', 'components', 'TradePanel.tsx'), 'utf8')

    assert.doesNotMatch(tradePanel, /components\/market-side-panel\/marketDataStore/)
    assert.match(tradePanel, /features\/market\/marketDataStore/)
  })
```

If relative `join` is awkward from `features/market`, use:

```ts
const tradePanel = readFileSync(join(currentDir, '..', 'trading', 'components', 'TradePanel.tsx'), 'utf8')
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\features\market\tradingMarketApi.test.ts"
```

Expected: FAIL because `TradePanel.tsx` still imports `../../../components/market-side-panel/marketDataStore`.

- [ ] **Step 3: Move store and adapter files**

Move these implementations unchanged:

```text
apps/web/src/components/market-side-panel/marketDataStore.ts -> apps/web/src/features/market/marketDataStore.ts
apps/web/src/components/market-side-panel/quoteMarketDataSnapshot.ts -> apps/web/src/features/market/quoteMarketDataSnapshot.ts
apps/web/src/components/market-side-panel/quoteMarketDataAdapter.ts -> apps/web/src/features/market/quoteMarketDataAdapter.ts
```

Fix imports inside the moved files:

```ts
import { subscribeOrderBook, subscribeQuote, subscribeRecentTrades } from '../../services/marketStream'
import { fetchMarketOrderBook, fetchMarketQuote, fetchMarketRecentTrades } from './tradingMarketApi'
import { mapOrderBookToMarketData, mapQuoteToTradingQuote, mapRecentTradesToMarketData } from './tradingMarketAdapters'
import type { BackendOrderBook, BackendQuote, BackendRecentTrade } from './tradingMarketAdapters'
import type { TradingQuote } from './tradingModels'
```

For `quoteMarketDataSnapshot.ts`, import types from:

```ts
import type { MarketDataSnapshot } from './marketDataTypes'
```

- [ ] **Step 4: Keep component-folder compatibility re-exports**

Replace old component-folder files with re-exports:

```ts
export { marketDataStore, useMarketDataSnapshot } from '../../features/market/marketDataStore'
export type { MarketDataStoreState } from '../../features/market/marketDataStore'
```

```ts
export { createFallbackMarketDataSnapshot, createQuoteMarketDataSnapshot } from '../../features/market/quoteMarketDataSnapshot'
```

```ts
export { startQuoteMarketDataAdapter } from '../../features/market/quoteMarketDataAdapter'
```

If `MarketDataStoreState` is not exported by the moved store, export it from `features/market/marketDataStore.ts`.

- [ ] **Step 5: Update direct imports**

Change `TradePanel.tsx` to:

```ts
import { useMarketDataSnapshot } from '../../market/marketDataStore'
```

Because `TradePanel.tsx` is in `features/trading/components`, `../../market/marketDataStore` points to `features/market`.

Change `RightTradingPanel.tsx` and any other page/container imports that start or release the quote adapter to import from `../../../features/market/quoteMarketDataAdapter` or the correct relative feature path.

- [ ] **Step 6: Run targeted tests**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\features\market\tradingMarketApi.test.ts src\components\market-side-panel\marketDataStore.test.ts src\components\market-side-panel\quoteMarketDataAdapter.test.ts src\features\trading\components\TradePanel.test.ts"
```

Expected: PASS.

- [ ] **Step 7: Grep boundary**

Run:

```powershell
rg -n "features/market.*components/market-side-panel|components/market-side-panel/marketDataStore|components/market-side-panel/quoteMarketDataAdapter" fx-trading-platform\apps\web\src
```

Expected: no production imports into old component-owned store/adapter paths. Compatibility re-export files may match.

- [ ] **Step 8: Commit**

```powershell
git add fx-trading-platform/apps/web/src/features/market fx-trading-platform/apps/web/src/components/market-side-panel fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx fx-trading-platform/apps/web/src/pages/trading/components/RightTradingPanel.tsx
git commit -m "refactor(web): move market data orchestration into market feature"
```

---

## Task 5: Finish Audit Details Builder Adoption

**Purpose:** Make audit `details` construction consistent in admin write paths while leaving non-audit JSON columns alone.

**Files:**
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminFeatureOperationService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminUserService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminFundOrderService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminRiskCommandService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminMemberService.java`
- Modify: `backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java`

- [ ] **Step 1: Strengthen architecture test**

In `ArchitectureRulesTest.adminAuditDetailsUseSharedJsonBuilder`, expand the scanned files:

```java
String featureOperation = Files.readString(Path.of(
    "src/main/java/com/fxplatform/admin/service/AdminFeatureOperationService.java"));
String userService = Files.readString(Path.of(
    "src/main/java/com/fxplatform/admin/service/AdminUserService.java"));
String fundOrderService = Files.readString(Path.of(
    "src/main/java/com/fxplatform/admin/service/AdminFundOrderService.java"));
String riskCommand = Files.readString(Path.of(
    "src/main/java/com/fxplatform/admin/service/AdminRiskCommandService.java"));
String memberService = Files.readString(Path.of(
    "src/main/java/com/fxplatform/admin/service/AdminMemberService.java"));
String combined = financeCommand
    + tradingCommand
    + featureOperation
    + userService
    + fundOrderService
    + riskCommand
    + memberService;
```

Then assert:

```java
assertThat(combined).doesNotContain("private String escape");
assertThat(combined).doesNotContain("return \"{\"");
assertThat(combined).doesNotContain("JSONUtil.toJsonStr(MapUtil.builder()");
assertThat(combined).contains("AuditDetailsBuilder.create()");
```

- [ ] **Step 2: Run backend architecture test and verify it fails**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd -Dtest=ArchitectureRulesTest#adminAuditDetailsUseSharedJsonBuilder test"
```

Expected: FAIL because several services still use `JSONUtil.toJsonStr(MapUtil.builder())` or a direct `LinkedHashMap` for audit details.

- [ ] **Step 3: Convert `AdminFeatureOperationService` audit details**

Import:

```java
import com.fxplatform.audit.service.AuditDetailsBuilder;
```

Replace:

```java
LinkedHashMap<String, Object> auditDetails = new LinkedHashMap<>();
auditDetails.put("pageKey", pageKey);
auditDetails.put("action", action.key());
auditDetails.put("rowId", request.rowId());
auditDetails.put("reason", request.reason());
```

and the `JSONUtil.toJsonStr(auditDetails)` argument with:

```java
String auditDetails = AuditDetailsBuilder.create()
    .put("pageKey", pageKey)
    .put("action", action.key())
    .put("rowId", request.rowId())
    .put("reason", request.reason())
    .toJson();
```

Then pass `auditDetails` into `auditLogService.record(...)`.

Keep `JSONUtil.toJsonStr(enrichPayload(...))` for `AdminFeatureRecordEntity.data`; that is not audit details.

- [ ] **Step 4: Convert `details(...)` helper methods**

For each of these files, update only audit details helper methods to use `AuditDetailsBuilder`:

```text
AdminUserService.java
AdminFundOrderService.java
AdminRiskCommandService.java
AdminMemberService.java
```

Use this pattern:

```java
private String details(String reason, String before, String after, String note) {
  return AuditDetailsBuilder.create()
      .put("reason", reason)
      .put("before", before)
      .put("after", after)
      .put("note", note)
      .toJson();
}
```

Adapt key names to match the current payload fields in each file. Do not rename existing JSON keys unless a test explicitly covers the new names.

- [ ] **Step 5: Run backend focused tests**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd -Dtest=ArchitectureRulesTest,AdminFeatureOperationServiceTest,AdminFundOrderServiceTest,AdminUserServiceTest,AdminRiskCommandServiceTest,AdminMemberServiceTest test"
```

If one of the named service test classes does not exist, remove that class from `-Dtest` and run the existing matching test classes discovered by:

```powershell
Get-ChildItem -Recurse -File fx-trading-platform\backend\src\test\java -Filter '*ServiceTest.java' | Select-String -Pattern 'AdminUserService|AdminRiskCommandService|AdminMemberService|AdminFundOrderService'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service fx-trading-platform/backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java
git commit -m "refactor(backend): standardize admin audit details builder usage"
```

---

## Task 6: Split `AdminFeatureCatalogService` By Catalog Group

**Purpose:** Reduce the 725-line catalog class into group-owned static builders while keeping the API unchanged.

**Files:**
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminFeatureCatalogDsl.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminPermissionFeaturePages.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminProductFeaturePages.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminFinanceFeaturePages.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminMemberFeaturePages.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminContentFeaturePages.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminSettingsFeaturePages.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminFeatureCatalogService.java`
- Modify: `backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java`
- Modify: `backend/src/test/java/com/fxplatform/admin/service/AdminFeatureCatalogServiceTest.java` if present.

- [ ] **Step 1: Add size and behavior guard**

Add to `ArchitectureRulesTest`:

```java
@Test
void adminFeatureCatalogIsSplitByPageGroup() throws Exception {
  Path catalogPath = Path.of("src/main/java/com/fxplatform/admin/service/AdminFeatureCatalogService.java");
  String catalog = Files.readString(catalogPath);
  long lines = Files.lines(catalogPath).count();

  assertThat(lines).isLessThanOrEqualTo(250);
  assertThat(catalog).contains("AdminPermissionFeaturePages.pages()");
  assertThat(catalog).contains("AdminProductFeaturePages.pages()");
  assertThat(catalog).contains("AdminFinanceFeaturePages.pages()");
  assertThat(catalog).contains("AdminMemberFeaturePages.pages()");
  assertThat(catalog).contains("AdminContentFeaturePages.pages()");
  assertThat(catalog).contains("AdminSettingsFeaturePages.pages()");
}
```

- [ ] **Step 2: Run architecture test and verify it fails**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd -Dtest=ArchitectureRulesTest#adminFeatureCatalogIsSplitByPageGroup test"
```

Expected: FAIL because the class is still about 725 lines and group builders do not exist.

- [ ] **Step 3: Create `AdminFeatureCatalogDsl`**

Move the helper methods from the bottom of `AdminFeatureCatalogService` into package-private static methods in `AdminFeatureCatalogDsl`.

Required methods:

```java
final class AdminFeatureCatalogDsl {

  private AdminFeatureCatalogDsl() {
  }

  static AdminFeaturePageResponse page(
      String key,
      String title,
      String group,
      List<AdminFeatureFieldResponse> fields,
      List<AdminFeatureColumnResponse> columns,
      List<AdminFeatureActionResponse> toolbarActions,
      List<AdminFeatureActionResponse> rowActions,
      List<Map<String, Object>> rows
  ) {
    return new AdminFeaturePageResponse(key, title, group, fields, columns, toolbarActions, rowActions, rows);
  }

  static List<AdminFeatureFieldResponse> fields(AdminFeatureFieldResponse... fields) {
    return List.of(fields);
  }

  static List<AdminFeatureColumnResponse> columns(AdminFeatureColumnResponse... columns) {
    return List.of(columns);
  }

  static List<AdminFeatureActionResponse> actions(AdminFeatureActionResponse... actions) {
    return List.of(actions);
  }

  @SafeVarargs
  static List<Map<String, Object>> rows(Map<String, Object>... rows) {
    return List.of(rows);
  }

  static AdminFeatureFieldResponse field(String key, String label, String component) {
    return new AdminFeatureFieldResponse(key, label, component, List.of());
  }

  static AdminFeatureFieldResponse select(String key, String label, String... options) {
    List<AdminFeatureOptionResponse> items = Stream.of(options)
        .map(option -> new AdminFeatureOptionResponse(option, option))
        .toList();
    return new AdminFeatureFieldResponse(key, label, "select", items);
  }

  static AdminFeatureColumnResponse col(String key, String label) {
    return new AdminFeatureColumnResponse(key, label, false);
  }

  static AdminFeatureColumnResponse sortableCol(String key, String label) {
    return new AdminFeatureColumnResponse(key, label, true);
  }

  static AdminFeatureActionResponse action(String key, String label, String type) {
    return new AdminFeatureActionResponse(key, label, type);
  }

  static Map<String, Object> row(Object... entries) {
    if (entries.length % 2 != 0) {
      throw new IllegalArgumentException("row entries must be key/value pairs");
    }
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) {
      row.put(String.valueOf(entries[i]), entries[i + 1]);
    }
    return Collections.unmodifiableMap(row);
  }
}
```

Add the necessary imports from the old file.

- [ ] **Step 4: Extract page groups**

Create each group class with this shape:

```java
final class AdminPermissionFeaturePages {

  private AdminPermissionFeaturePages() {
  }

  static List<AdminFeaturePageResponse> pages() {
    return List.of(
        AdminFeatureCatalogDsl.page(
            "system-users",
            "用户管理",
            "权限",
            AdminFeatureCatalogDsl.fields(
                AdminFeatureCatalogDsl.field("department", "搜索部门", "input")
            ),
            AdminFeatureCatalogDsl.columns(
                AdminFeatureCatalogDsl.col("avatar", "头像")
            ),
            AdminFeatureCatalogDsl.actions(
                AdminFeatureCatalogDsl.action("create", "新增", "modal")
            ),
            AdminFeatureCatalogDsl.actions(
                AdminFeatureCatalogDsl.action("edit", "编辑", "modal")
            ),
            AdminFeatureCatalogDsl.rows()
        )
    );
  }
}
```

Then move the full existing page definitions into the matching classes:

```text
AdminPermissionFeaturePages: system-users, system-roles, system-departments, system-menus, system-posts
AdminProductFeaturePages: products, product-categories, price-schedules
AdminFinanceFeaturePages: finance-ledger, recharge-orders, withdrawal-orders, payment-methods
AdminMemberFeaturePages: members, member-payment-accounts
AdminContentFeaturePages: notices, news, member-notices, verification-codes, request-logs
AdminSettingsFeaturePages: settings-site, settings-upload, settings-sms, settings-email, settings-footer
```

Keep existing keys, labels, actions, and rows exactly as they are. This task moves code; it does not redesign catalog content.

- [ ] **Step 5: Shrink `AdminFeatureCatalogService.buildPages()`**

Replace the current long method with:

```java
private static Map<String, AdminFeaturePageResponse> buildPages() {
  LinkedHashMap<String, AdminFeaturePageResponse> pages = new LinkedHashMap<>();

  Stream.of(
          AdminPermissionFeaturePages.pages(),
          AdminProductFeaturePages.pages(),
          AdminFinanceFeaturePages.pages(),
          AdminMemberFeaturePages.pages(),
          AdminContentFeaturePages.pages(),
          AdminSettingsFeaturePages.pages())
      .flatMap(List::stream)
      .forEach(page -> pages.put(page.key(), page));

  return Collections.unmodifiableMap(pages);
}
```

- [ ] **Step 6: Run backend tests**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd -Dtest=ArchitectureRulesTest,AdminFeatureOperationServiceTest test"
```

Expected: PASS.

Also run:

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service fx-trading-platform/backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java
git commit -m "refactor(backend): split admin feature catalog by group"
```

---

## Task 7: Split `TradingPage` Into Orchestration And Presentational Views

**Purpose:** Reduce `TradingPage.tsx` by moving desktop/mobile layout and settings dialog into focused components without changing behavior.

**Files:**
- Create: `apps/web/src/pages/trading/components/TradingDesktopView.tsx`
- Create: `apps/web/src/pages/trading/components/TradingMobileView.tsx`
- Create: `apps/web/src/pages/trading/components/TradingSettingsDialog.tsx`
- Create: `apps/web/src/pages/trading/tradingPageViewModels.ts`
- Modify: `apps/web/src/pages/trading/TradingPage.tsx`
- Modify: `apps/web/src/pages/trading/TradingPage.test.ts`

- [ ] **Step 1: Add source-structure tests**

Append to `TradingPage.test.ts`:

```ts
  it('keeps TradingPage as an orchestration shell with extracted views', () => {
    assert.match(source, /<TradingDesktopView/)
    assert.match(source, /<TradingMobileView/)
    assert.match(source, /<TradingSettingsDialog/)
    assert.doesNotMatch(source, /<TradingWorkspace[\s\S]*<ChartWorkspace[\s\S]*<TradePanel[\s\S]*<BottomAccountPanel/)
  })
```

- [ ] **Step 2: Run focused test and verify it fails**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\pages\trading\TradingPage.test.ts"
```

Expected: FAIL because `TradingPage.tsx` still contains inline desktop/mobile layout.

- [ ] **Step 3: Create shared view model types**

Create `apps/web/src/pages/trading/tradingPageViewModels.ts`:

```ts
import type { ReactNode } from 'react'

import type { ChartSettings, ChartType, DrawingMagnetMode, DrawingTool, IndicatorSettings } from './chartSettings'
import type { TradingMarket, TradingPeriod, TradingQuote } from '../../features/market/tradingModels'
import type { TradingAccount, OrderResponse, PositionResponse, LedgerEntry } from '../../components/tables/types'

export type ChartThemeMode = 'dark' | 'light'

export type TradingChartCallbacks = {
  onChartTypeChange: (chartType: ChartType) => void
  onDrawingMagnetModeChange: (magnetMode: DrawingMagnetMode) => void
  onDrawingToolChange: (activeTool: DrawingTool) => void
  onIndicatorSettingsChange: (indicatorSettings: IndicatorSettings) => void
  onIndicatorToggle: (indicator: string) => void
  onPeriodChange: (interval: TradingPeriod) => void
}

export type TradingAccountPanelData = {
  account: TradingAccount | null
  ledgerEntries: LedgerEntry[]
  loading: boolean
  orders: OrderResponse[]
  positions: PositionResponse[]
  sessionReady: boolean
  onClosePosition: (positionId: string) => Promise<void> | void
}

export type TradingTerminalViewProps = {
  accountPanel: TradingAccountPanelData
  accountId?: string
  balances: Record<string, number>
  chartCallbacks: TradingChartCallbacks
  chartSettings: ChartSettings
  chartThemeMode: ChartThemeMode
  indicators: string[]
  lastOrderError?: unknown
  loginRequired: boolean
  market: TradingMarket
  markets: TradingMarket[]
  onLoginRequired: () => void
  onOpenMarkets: () => void
  onOpenQuote: () => void
  onOpenSettings: () => void
  onOpenTrade: () => void
  onRetrySession: () => Promise<void> | void
  onSelectSymbol: (symbol: string) => void
  quote: TradingQuote
  quotes: Record<string, TradingQuote>
  sessionError?: string | null
  sessionReady: boolean
  sessionStatusLabel: string
  sessionStatusText: string
  sessionTelemetryState: string
  showSessionRetry: boolean
  submitOrder: (payload: unknown) => Promise<unknown>
  symbol: string
  terminalLoading: boolean
  token: string | null
  tradePanelSessionMode: 'loading' | 'ready' | 'login-required' | 'error'
}

export type TradingSettingsDialogProps = {
  open: boolean
  onClose: () => void
}

export type MobileSuspenseProps = {
  fallback: ReactNode
}
```

If local types differ, adjust imports to the actual exported names while keeping this file as the single shared prop contract.

- [ ] **Step 4: Extract settings dialog**

Create `apps/web/src/pages/trading/components/TradingSettingsDialog.tsx`:

```tsx
import { X } from 'lucide-react'

import { ThemeSwitcher } from '../../../design-system/theme/ThemeSwitcher'
import styles from '../TradingPage.module.css'
import type { TradingSettingsDialogProps } from '../tradingPageViewModels'

export function TradingSettingsDialog({ open, onClose }: TradingSettingsDialogProps) {
  if (!open) return null

  return (
    <div
      className={styles.settingsLayer}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <section className={styles.settingsDialog} role="dialog" aria-modal="true" aria-label="交易设置">
        <header className={styles.settingsHeader}>
          <h2>交易设置</h2>
          <button type="button" aria-label="关闭交易设置" onClick={onClose}>
            <X size={20} aria-hidden="true" />
          </button>
        </header>
        <div className={styles.settingsRow}>
          <ThemeSwitcher />
        </div>
      </section>
    </div>
  )
}
```

- [ ] **Step 5: Extract desktop view**

Create `TradingDesktopView.tsx` by moving the non-mobile JSX branch from `TradingPage.tsx`. It must import:

```tsx
import { Settings } from 'lucide-react'
import { TradingWorkspace } from '../../../components/layout/TradingWorkspace'
import { TradePanel } from '../../../features/trading/components/TradePanel'
import { BottomAccountPanel } from './BottomAccountPanel'
import { ChartWorkspace } from './ChartWorkspace'
import { MarketSidebar } from './MarketSidebar'
import { RightTradingPanel } from './RightTradingPanel'
import { SymbolHeader } from './SymbolHeader'
import styles from '../TradingPage.module.css'
import type { TradingTerminalViewProps } from '../tradingPageViewModels'
```

Export:

```tsx
export function TradingDesktopView(props: TradingTerminalViewProps) {
  const {
    accountPanel,
    accountId,
    balances,
    chartCallbacks,
    chartSettings,
    chartThemeMode,
    indicators,
    lastOrderError,
    loginRequired,
    market,
    markets,
    onLoginRequired,
    onOpenMarkets,
    onOpenQuote,
    onOpenSettings,
    onRetrySession,
    onSelectSymbol,
    quote,
    quotes,
    sessionError,
    sessionReady,
    sessionStatusLabel,
    sessionStatusText,
    sessionTelemetryState,
    showSessionRetry,
    submitOrder,
    symbol,
    terminalLoading,
    token,
    tradePanelSessionMode
  } = props

  return (
    <div className={`${styles.layout} ${styles.desktopTerminal}`}>
      {/* Move the exact desktop JSX from TradingPage.tsx here. */}
    </div>
  )
}
```

When filling the JSX, keep the same child components and prop values that currently exist in `TradingPage.tsx`. The only behavior change allowed is the component boundary.

- [ ] **Step 6: Extract mobile view**

Create `TradingMobileView.tsx` by moving the mobile branch from `TradingPage.tsx`. It must import `Suspense`, lazy `MobileTradingTerminal`, `ChartWorkspace`, `BottomAccountPanel`, and `MobileTerminalFallback`.

Keep lazy loading:

```tsx
const MobileTradingTerminal = lazy(() =>
  import('../mobile/MobileTradingTerminal').then((module) => ({ default: module.MobileTradingTerminal }))
)
```

Export:

```tsx
export function TradingMobileView(props: TradingTerminalViewProps) {
  return (
    <div className={styles.mobileTerminal}>
      {/* Move the exact mobile JSX from TradingPage.tsx here. */}
    </div>
  )
}
```

Move `MobileTerminalFallback` into this file as a private function.

- [ ] **Step 7: Update `TradingPage.tsx`**

`TradingPage.tsx` should keep:

- data loading and state
- callbacks
- drawers and login prompt
- view selection

It should render:

```tsx
{!isMobileTerminal ? (
  <TradingDesktopView {...viewProps} />
) : null}

{isMobileTerminal ? (
  <TradingMobileView {...viewProps} />
) : null}

<TradingSettingsDialog open={settingsOpen} onClose={() => setSettingsOpen(false)} />
```

Build `viewProps` with `useMemo` only if it does not make dependencies unreadable. A plain object is acceptable.

- [ ] **Step 8: Run focused tests**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\pages\trading\TradingPage.test.ts src\pages\trading\components\ChartWorkspace.test.ts src\pages\trading\components\BottomAccountPanel.test.ts"
```

Expected: PASS.

- [ ] **Step 9: Verify line count**

Run:

```powershell
powershell -NoProfile -Command "(Get-Content -LiteralPath 'fx-trading-platform\apps\web\src\pages\trading\TradingPage.tsx').Count"
```

Expected: `TradingPage.tsx` is under 330 lines.

- [ ] **Step 10: Commit**

```powershell
git add fx-trading-platform/apps/web/src/pages/trading
git commit -m "refactor(web): split trading page views from orchestration"
```

---

## Task 8: Split `TradePanel` State And Subviews

**Purpose:** Keep `TradePanel.tsx` focused on composition by extracting session status, leverage controls, and submit handling helpers.

**Files:**
- Create: `apps/web/src/features/trading/components/TradePanelSessionStatus.tsx`
- Create: `apps/web/src/features/trading/components/TradePanelLeverageControls.tsx`
- Create: `apps/web/src/features/trading/hooks/useTradePanelSubmit.ts`
- Modify: `apps/web/src/features/trading/components/TradePanel.tsx`
- Modify: `apps/web/src/features/trading/components/TradePanel.test.ts`

- [ ] **Step 1: Add structure tests**

Append to `TradePanel.test.ts`:

```ts
  it('delegates session status and leverage surfaces to focused components', () => {
    assert.match(tradePanelSource, /<TradePanelSessionStatus/)
    assert.match(tradePanelSource, /<TradePanelLeverageControls/)
    assert.match(tradePanelSource, /useTradePanelSubmit/)
    assert.doesNotMatch(tradePanelSource, /function LeverageCell/)
  })
```

- [ ] **Step 2: Run focused test and verify it fails**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\features\trading\components\TradePanel.test.ts"
```

Expected: FAIL because subcomponents do not exist and `LeverageCell` is still local.

- [ ] **Step 3: Extract session status**

Create `TradePanelSessionStatus.tsx`:

```tsx
type Props = {
  accountStatus: string
  onRetrySession?: () => Promise<void> | void
  sessionBadge: string
  sessionErrorText: string
  sessionHasError: boolean
}

export function TradePanelSessionStatus({
  accountStatus,
  onRetrySession,
  sessionBadge,
  sessionErrorText,
  sessionHasError
}: Props) {
  return (
    <>
      <div className="trade-panel__session-status" aria-live="polite">
        <span>{sessionBadge}</span>
        <small>{accountStatus}</small>
      </div>

      {sessionHasError ? (
        <div className="trade-panel__session-error" role="alert">
          <span>{sessionErrorText}</span>
          {onRetrySession ? (
            <button type="button" onClick={() => void onRetrySession()}>
              重试连接
            </button>
          ) : null}
        </div>
      ) : null}
    </>
  )
}
```

If the project keeps existing mojibake literals in this file, preserve existing strings from `TradePanel.tsx` instead of introducing mixed encodings.

- [ ] **Step 4: Extract leverage controls**

Create `TradePanelLeverageControls.tsx`:

```tsx
import { ChevronDown, X } from 'lucide-react'

import type { TradeSide } from '../types/order'

type Props = {
  leverage: number
  leverageOpen: boolean
  leverageOptions: number[]
  onClose: () => void
  onOpen: () => void
  onUpdateLeverage: (value: number) => void
}

export function TradePanelLeverageControls({
  leverage,
  leverageOpen,
  leverageOptions,
  onClose,
  onOpen,
  onUpdateLeverage
}: Props) {
  return (
    <>
      {leverageOpen ? (
        <div className="trade-panel__leverage-popover" role="dialog" aria-label="调整杠杆">
          <div className="trade-panel__leverage-popover-head">
            <strong>调整杠杆</strong>
            <button type="button" aria-label="关闭杠杆弹窗" onClick={onClose}>
              <X size={16} aria-hidden="true" />
            </button>
          </div>
          <label className="trade-panel__field">
            <span className="trade-panel__field-label">杠杆倍数</span>
            <span className="trade-panel__control">
              <input
                aria-label="杠杆倍数"
                inputMode="numeric"
                value={`${leverage}.00`}
                onChange={(event) => onUpdateLeverage(Number(event.target.value))}
              />
              <span className="trade-panel__unit">x</span>
            </span>
          </label>
          <div className="trade-panel__leverage-options" aria-label="杠杆快捷选项">
            {leverageOptions.map((option) => (
              <button
                key={option}
                type="button"
                className={option === leverage ? 'trade-panel__leverage-option--active' : ''}
                onClick={() => onUpdateLeverage(option)}
              >
                {option}x
              </button>
            ))}
          </div>
          <p className="trade-panel__leverage-hint">当前最高支持 10x 杠杆，请注意风险</p>
          <button type="button" className="trade-panel__leverage-confirm" onClick={onClose}>
            会话就绪后更改
          </button>
        </div>
      ) : null}

      <div className="trade-panel__leverage-row" aria-label="逐仓和杠杆设置">
        <LeverageCell side="buy" leverage={leverage} onOpen={onOpen} />
        <LeverageCell side="sell" leverage={leverage} onOpen={onOpen} />
      </div>
    </>
  )
}

function LeverageCell({ side, leverage, onOpen }: { side: TradeSide; leverage: number; onOpen: () => void }) {
  return (
    <div className={`trade-panel__leverage-cell trade-panel__leverage-cell--${side}`}>
      <button type="button" onClick={onOpen}>
        逐仓
        <ChevronDown size={13} aria-hidden="true" />
      </button>
      <button type="button" onClick={onOpen}>
        {leverage}x
        <ChevronDown size={13} aria-hidden="true" />
      </button>
    </div>
  )
}
```

If preserving current encoded strings is required, copy the exact strings from `TradePanel.tsx`.

- [ ] **Step 5: Extract submit hook**

Create `useTradePanelSubmit.ts`:

```ts
import { useCallback, useState } from 'react'

import { toOrderPayload } from '../services/orderAdapter'
import type { OrderValidationResult, TradeFormState, TradeMarket, TradeSide } from '../types/order'
import { ApiClientError } from '../../../services/apiClient'
import type { OrderPayload } from '../../../types/trading'
import type { OrderResponse } from '../../../components/tables/types'

type Options = {
  accountId?: string
  backendReady: boolean
  canTrade: boolean
  loginRequired: boolean
  market: TradeMarket
  onLoginRequired?: () => void
  onSubmitOrder?: (payload: OrderPayload) => Promise<OrderResponse | void>
  sessionHasError: boolean
  setNotice: (message: string) => void
}

export function useTradePanelSubmit({
  accountId,
  backendReady,
  canTrade,
  loginRequired,
  market,
  onLoginRequired,
  onSubmitOrder,
  sessionHasError,
  setNotice
}: Options) {
  const [submittingSide, setSubmittingSide] = useState<TradeSide | null>(null)
  const [attempted, setAttempted] = useState<Record<TradeSide, boolean>>({ buy: false, sell: false })

  const handleSubmit = useCallback(
    async (form: TradeFormState, validation: OrderValidationResult, reset: () => void) => {
      setAttempted((current) => ({ ...current, [form.side]: true }))

      if (loginRequired) {
        setNotice('请先登录后再下单')
        onLoginRequired?.()
        return
      }

      if (!canTrade) {
        setNotice(sessionHasError ? '后端会话异常，请先重试连接' : '后端会话尚未就绪，暂不能提交真实订单')
        return
      }

      if (!validation.canSubmit) {
        const firstError = validation.errors[0]
        setNotice(firstError ? validation.fieldErrors[firstError] ?? '请检查订单参数' : '请检查订单参数')
        return
      }

      setSubmittingSide(form.side)
      try {
        if (!backendReady || !accountId || !onSubmitOrder) {
          setNotice('后端会话尚未就绪，不能提交真实订单')
          return
        }

        const payload = toOrderPayload(accountId, form, market)
        const response = await onSubmitOrder(payload)
        reset()
        setAttempted((current) => ({ ...current, [form.side]: false }))
        setNotice(`后端下单成功：${response?.status ?? '已提交'}`)
      } catch (error) {
        setNotice(`后端下单失败：${formatOrderError(error)}`)
      } finally {
        setSubmittingSide(null)
      }
    },
    [accountId, backendReady, canTrade, loginRequired, market, onLoginRequired, onSubmitOrder, sessionHasError, setNotice]
  )

  return { attempted, handleSubmit, submittingSide }
}

function formatOrderError(error: unknown) {
  if (error instanceof ApiClientError) {
    const details = [`错误码：${error.code}`, `HTTP 状态：${error.status}`]
    if (error.requestId) details.push(`Request ID：${error.requestId}`)
    return `${error.message}（${details.join('，')}）`
  }

  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error) return error
  return '请稍后重试'
}
```

If current source encoding must remain byte-consistent, copy the exact existing Chinese strings from `TradePanel.tsx`.

- [ ] **Step 6: Wire `TradePanel.tsx`**

Remove local `formatOrderError`, local `LeverageCell`, local `submittingSide`, and local `attempted` state.

Import:

```ts
import { TradePanelLeverageControls } from './TradePanelLeverageControls'
import { TradePanelSessionStatus } from './TradePanelSessionStatus'
import { useTradePanelSubmit } from '../hooks/useTradePanelSubmit'
```

Use:

```ts
const { attempted, handleSubmit, submittingSide } = useTradePanelSubmit({
  accountId,
  backendReady,
  canTrade,
  loginRequired,
  market,
  onLoginRequired,
  onSubmitOrder,
  sessionHasError,
  setNotice
})
```

Replace inline session JSX with `TradePanelSessionStatus`.

Replace inline leverage popover and row with `TradePanelLeverageControls`.

- [ ] **Step 7: Run focused tests**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\features\trading\components\TradePanel.test.ts src\features\trading\services\orderAdapter.test.ts src\features\trading\components\tradePanelMarket.test.ts"
```

Expected: PASS.

- [ ] **Step 8: Verify line count**

Run:

```powershell
powershell -NoProfile -Command "(Get-Content -LiteralPath 'fx-trading-platform\apps\web\src\features\trading\components\TradePanel.tsx').Count"
```

Expected: `TradePanel.tsx` is under 230 lines.

- [ ] **Step 9: Commit**

```powershell
git add fx-trading-platform/apps/web/src/features/trading
git commit -m "refactor(web): split trade panel session and leverage surfaces"
```

---

## Task 9: Add Large File Guardrails Before Touching KLineCharts Core

**Purpose:** Make future root KLineCharts refactors measurable before splitting `Store.ts`, `Chart.ts`, or `EventHandler.ts`.

**Files:**
- Create: `scripts/audit-large-files.mjs`
- Modify: `package.json`

- [ ] **Step 1: Create large-file audit script**

Create `scripts/audit-large-files.mjs`:

```js
import { readFileSync } from 'node:fs'

const limits = [
  ['src/Store.ts', 1900],
  ['src/Chart.ts', 1450],
  ['src/common/EventHandler.ts', 1000],
  ['fx-trading-platform/apps/web/src/pages/trading/TradingPage.tsx', 330],
  ['fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx', 230],
  ['fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFeatureCatalogService.java', 250]
]

const failures = []

for (const [path, limit] of limits) {
  const lines = readFileSync(path, 'utf8').split(/\r?\n/).length
  if (lines > limit) {
    failures.push(`${path} has ${lines} lines; limit is ${limit}`)
  }
}

if (failures.length > 0) {
  console.error('Large file audit failed:')
  for (const failure of failures) console.error(`- ${failure}`)
  process.exit(1)
}

console.log('Large file audit passed.')
```

- [ ] **Step 2: Add package script**

In root `package.json`, add:

```json
"audit:large-files": "node scripts/audit-large-files.mjs"
```

Keep JSON comma placement valid.

- [ ] **Step 3: Run and interpret**

Run:

```powershell
cmd.exe /d /s /c "pnpm.cmd audit:large-files"
```

Expected after Tasks 6-8: PASS for `TradingPage`, `TradePanel`, and `AdminFeatureCatalogService`; root KLineCharts files should pass current relaxed limits. This task intentionally does not force a risky root KLineCharts split.

- [ ] **Step 4: Commit**

```powershell
git add scripts/audit-large-files.mjs package.json
git commit -m "chore: add large file audit guardrails"
```

---

## Task 10: Replace Low-value Backend Template Comments In High-risk Paths

**Purpose:** Improve comment quality where comments protect business rules, not mechanically everywhere.

**Files:**
- Modify: `backend/src/main/java/com/fxplatform/trading/service/PositionService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/repository/PositionRepository.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/repository/OrderRepository.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java`
- Modify: `backend/src/main/java/com/fxplatform/finance/repository/AdminFundOperationRepository.java`
- Modify: `backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java`

- [ ] **Step 1: Add high-risk comment quality test**

Add to `ArchitectureRulesTest`:

```java
@Test
void highRiskTradingAndFinancePathsDoNotUseTemplateBusinessComments() throws Exception {
  String combined = Files.readString(Path.of("src/main/java/com/fxplatform/trading/service/PositionService.java"))
      + Files.readString(Path.of("src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java"))
      + Files.readString(Path.of("src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java"))
      + Files.readString(Path.of("src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java"));

  assertThat(combined).doesNotContain("执行 closePosition 业务流程");
  assertThat(combined).doesNotContain("执行 tryExecute 业务流程");
  assertThat(combined).doesNotContain("执行 shouldClose 业务流程");
  assertThat(combined).doesNotContain("执行 applyBalanceChange 业务流程");
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd -Dtest=ArchitectureRulesTest#highRiskTradingAndFinancePathsDoNotUseTemplateBusinessComments test"
```

Expected: FAIL if these exact template comments remain.

- [ ] **Step 3: Replace comments with business constraints**

Use these comment replacements. Keep code unchanged.

In `PositionService.closePosition(...)`:

```java
/**
 * 用户主动平仓入口。真正的重复结算保护在 closeOwnedPosition 和 closeIfOpen 中完成，
 * 这里先验证账户归属，避免跨账户平仓。
 */
```

In `PositionService.closeOwnedPosition(...)`, add before `closeIfOpen`:

```java
// 先用 OPEN 条件更新抢占平仓权，再写账户和流水，避免并发请求重复释放保证金。
```

In `PendingOrderExecutionService.tryExecute(...)`:

```java
/**
 * 挂单触价后先抢占 PENDING 状态，只有抢占成功的实例可以继续成交。
 */
```

In `ProtectiveOrderExecutionService.shouldClose(...)`:

```java
/**
 * 止盈止损只扫描 OPEN 持仓；并发重复触发交给 PositionService 的 OPEN 条件更新兜底。
 */
```

In `AdminFinanceCommandService.applyBalanceChange(...)`:

```java
/**
 * 后台资金写操作以 accountId + operationType + idempotencyKey 作为命令幂等边界。
 * 非空幂等键必须先抢占资金操作记录，抢占成功后才允许改账户余额和写流水。
 */
```

In `AdminFundOperationRepository.insertIfAbsent(...)`:

```java
/**
 * 用数据库唯一索引抢占非空幂等键；返回 0 表示已有同一命令结果，调用方应回读。
 */
```

- [ ] **Step 4: Run backend tests**

Run:

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd -Dtest=ArchitectureRulesTest,PositionServiceTest,PendingOrderExecutionServiceTest,AdminFinanceCommandServiceTest test"
```

If `AdminFinanceCommandServiceTest` does not exist, run the existing admin finance tests found by:

```powershell
Get-ChildItem -Recurse -File fx-trading-platform\backend\src\test\java -Filter '*Finance*Test.java'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add fx-trading-platform/backend/src/main/java/com/fxplatform/trading fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java fx-trading-platform/backend/src/main/java/com/fxplatform/finance/repository/AdminFundOperationRepository.java fx-trading-platform/backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java
git commit -m "docs(backend): replace template comments in high-risk flows"
```

---

## Final Verification

Run these commands after all tasks:

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd test"
cmd.exe /d /s /c "pnpm.cmd type-check"
cmd.exe /d /s /c "pnpm.cmd audit:large-files"
```

Expected:

- architecture verification exits 0
- web tests exit 0
- web build exits 0
- backend Maven tests exit 0
- root type check exits 0
- large-file audit exits 0

## Execution Handoff

Recommended execution mode: subagent-driven development.

Suggested split:

- Agent 1: Tasks 1-4, frontend boundaries and market ownership.
- Agent 2: Tasks 5-6 and 10, backend audit/catalog/comment quality.
- Agent 3: Tasks 7-9, trading page/panel split and large-file guardrails.

After each agent returns, review the diff and run that task's focused tests before starting final verification.

## Self-review

- Spec coverage: Covers all remaining issues from the latest audit: dead frontend CSS, market-side-panel reverse dependency, feature/component boundary, partial audit JSON unification, large admin catalog, TradingPage/TradePanel density, root KLineCharts guardrails, and comment quality.
- Placeholder scan: No `TBD`, `TODO`, or open-ended "add tests" steps remain. Each task names files, test commands, and expected outcomes.
- Type consistency: New frontend types are centralized in `tradingPageViewModels.ts` and `marketDataTypes.ts`; backend helper names match existing service names and repository methods.
