# Trading Path Lab Phase 3: Admin Local Oracle and UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `/trading/lab` Admin page with an independent fixed-point browser Oracle, deterministic scenario generator, IndexedDB drafts, timeline editor, KLineCharts visualization, authenticated fetch-SSE controls, and raw JSON report download/print.

**Architecture:** Keep all trading-lab frontend code in a route-level lazy feature. Use pure TypeScript modules for decimal arithmetic, Oracle calculation, scenario validation, and random generation. Use React only for presentation and interaction; high-frequency ticks update the chart through refs rather than rebuilding large React state trees.

**Tech Stack:** React 19, TypeScript 5.8 strict, React Router 7, Vite 7, KLineCharts from the outer workspace, native IndexedDB/Web Crypto/Streams APIs, Node test runner.

## Global Constraints

- Preserve the current Admin visual language and existing user changes.
- Do not create a branch, Commit, Push, or PR.
- Route is desktop-only for widths `>=1280px`; show a clear disabled state below that width.
- Browser may call only main backend `/api/admin/**`; never validation-backend or Supervisor.
- Browser Oracle must not import generated backend implementation code or the Java test Oracle.
- Money calculations use string + `BigInt`; JS `number` is forbidden in Oracle money paths.
- Do not implement automatic local-vs-actual tolerance comparison or Pass/Fail.
- Negative mode shows a warning and captures expected error metadata, but does not judge the result in the product UI.
- Do not load full large reports into React state or render them into the main page.
- Use existing Admin token refresh behavior, but add single-flight refresh and `AbortSignal` before opening long-lived streams.

---

## File Map

### Feature entry and styles

- Create `apps/admin/src/features/tradingLab/TradingLabPage.tsx`
- Create `apps/admin/src/features/tradingLab/TradingLabPage.css`
- Create `apps/admin/src/features/tradingLab/index.ts`
- Modify `apps/admin/src/app/AdminApp.tsx`
- Modify `apps/admin/src/app/adminMenu.ts`
- Modify `apps/admin/src/app/AdminLayout.tsx` only if authority-based menu filtering is not isolated elsewhere.
- Modify `apps/admin/package.json`
- Modify `apps/admin/vite.config.ts` only for explicit chunking if Vite does not split the lazy route adequately.
- Modify `scripts/ensure-klinecharts-dist.mjs` only if it cannot support both Web and Admin callers.

### API and runtime

- Create `apps/admin/src/features/tradingLab/api/tradingLabApi.ts`
- Create `apps/admin/src/features/tradingLab/api/tradingLabStream.ts`
- Create `apps/admin/src/features/tradingLab/api/rawDownload.ts`
- Modify `apps/admin/src/services/apiClient.ts`
- Modify `apps/admin/src/services/adminToken.ts`

### Domain, Oracle, generator, and storage

- Create `apps/admin/src/features/tradingLab/model/types.ts`
- Create `apps/admin/src/features/tradingLab/model/defaults.ts`
- Create `apps/admin/src/features/tradingLab/model/validation.ts`
- Create `apps/admin/src/features/tradingLab/model/normalization.ts`
- Create `apps/admin/src/features/tradingLab/oracle/decimal.ts`
- Create `apps/admin/src/features/tradingLab/oracle/spotOracle.ts`
- Create `apps/admin/src/features/tradingLab/oracle/perpetualOracle.ts`
- Create `apps/admin/src/features/tradingLab/oracle/riskOracle.ts`
- Create `apps/admin/src/features/tradingLab/oracle/runOracle.ts`
- Create `apps/admin/src/features/tradingLab/generator/prng.ts`
- Create `apps/admin/src/features/tradingLab/generator/randomScenario.ts`
- Create `apps/admin/src/features/tradingLab/storage/tradingLabDraftStore.ts`

### Components

- Create focused files under `apps/admin/src/features/tradingLab/components/`:
  - `LabHeader.tsx`
  - `EnvironmentStatus.tsx`
  - `ScenarioSettings.tsx`
  - `RandomScenarioDialog.tsx`
  - `TimelineEditor.tsx`
  - `TimelineActionEditor.tsx`
  - `LocalExpectedPanel.tsx`
  - `ActualStatePanel.tsx`
  - `TradingLabChart.tsx`
  - `RunProgress.tsx`
  - `ReportPanel.tsx`
  - `NegativeModeBanner.tsx`

### Tests

- Create colocated `*.test.ts` for pure TypeScript modules.
- Create source/runtime `*.test.mjs` for React wiring where DOM dependencies are unavailable.
- Extend `apps/admin/src/app/AdminApp.test.mjs`
- Extend `apps/admin/src/app/AdminTradingOperationsNavigation.test.mjs`

---

### Task 1: Add streaming-safe API primitives and authority-aware route access

**Interfaces:**

```ts
export function apiRaw(
  path: string,
  init?: RequestInit & { signal?: AbortSignal }
): Promise<Response>

export function hasAdminAuthority(authority: string): boolean
```

- [ ] **Step 1: Write failing API client tests**

Cover:

- two concurrent 401 responses cause one refresh request.
- `AbortSignal` reaches fetch.
- `apiRaw` preserves response body stream and does not call `response.text()`.
- a failed refresh clears tokens once.
- authority checks distinguish `TRADING_LAB_VIEW`, `TRADING_LAB_EXECUTE`, and `SUPER_ADMIN`.

- [ ] **Step 2: Run and confirm RED**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin test"
```

Expected: new tests fail because raw fetch, signal, and refresh single-flight do not exist.

- [ ] **Step 3: Implement refresh single-flight**

Use one module-level promise:

```ts
let refreshInFlight: Promise<string> | null = null

function refreshAccessTokenOnce() {
  refreshInFlight ??= refreshAccessToken().finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}
```

Keep existing JSON envelope behavior unchanged for `apiGet/apiPost`.

- [ ] **Step 4: Implement `apiRaw`**

Add Bearer auth, one refresh/replay attempt, and return the raw `Response`. Never parse the body.

- [ ] **Step 5: Add lazy route and menu authority**

In `AdminApp.tsx`:

```tsx
const TradingLabPage = lazy(() =>
  import('../features/tradingLab').then((module) => ({ default: module.TradingLabPage }))
)
```

Render it inside `Suspense`. Add `/trading/lab`. Hide the menu item without `TRADING_LAB_VIEW`, but keep backend authorization authoritative.

- [ ] **Step 6: Run Admin tests and build**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run build"
```

Expected: pass; Vite emits a separate trading-lab route chunk.

### Task 2: Implement exact browser decimal arithmetic

**Interfaces:**

```ts
export type Decimal = Readonly<{ coefficient: bigint; scale: number }>

export function decimal(value: string | bigint): Decimal
export function add(left: Decimal, right: Decimal): Decimal
export function subtract(left: Decimal, right: Decimal): Decimal
export function multiply(left: Decimal, right: Decimal): Decimal
export function divide(
  left: Decimal,
  right: Decimal,
  scale: number,
  rounding: 'DOWN' | 'UP' | 'HALF_UP'
): Decimal
export function quantize(value: Decimal, scale: number, rounding: RoundingMode): Decimal
export function floorToStep(value: Decimal, step: Decimal): Decimal
export function compare(left: Decimal, right: Decimal): -1 | 0 | 1
export function toDecimalString(value: Decimal): string
```

- [ ] **Step 1: Write failing decimal tests**

Exact cases:

```ts
assert.equal(toDecimalString(decimal('0.00000001')), '0.00000001')
assert.equal(toDecimalString(add(decimal('0.1'), decimal('0.2'))), '0.3')
assert.equal(toDecimalString(multiply(decimal('60000.12'), decimal('0.005'))), '300.0006')
assert.equal(toDecimalString(divide(decimal('1'), decimal('3'), 8, 'DOWN')), '0.33333333')
assert.equal(toDecimalString(floorToStep(decimal('1.234567'), decimal('0.001'))), '1.234')
```

Add a source contract assertion that `decimal.ts` contains no `parseFloat`, `Number(`, `Math.round`, or floating exponential notation.

- [ ] **Step 2: Run and confirm RED**

```powershell
node --test fx-trading-platform/apps/admin/src/features/tradingLab/oracle/decimal.test.ts
```

Expected: module not found.

- [ ] **Step 3: Implement normalized coefficient/scale arithmetic**

Rules:

- no negative scale.
- remove trailing coefficient zeros while retaining zero scale semantics.
- division by zero throws `DecimalError`.
- all rounding is explicit.
- JSON uses strings only.

- [ ] **Step 4: Run decimal tests**

```powershell
node --test fx-trading-platform/apps/admin/src/features/tradingLab/oracle/decimal.test.ts
```

Expected: all pass.

### Task 3: Define scenario model, normalization, hash, and validation

**Interfaces:**

Core types must include:

```ts
export type TradingLabScenario = {
  id: string
  name: string
  description: string
  negativeMode: boolean
  seed: string
  modelVersion: string
  configSnapshot: TradingLabConfigSnapshot
  configSnapshotHash: string
  initialBalances: InitialBalances
  defaults: ScenarioDefaults
  symbols: ScenarioSymbol[]
  timeline: TimelineAction[]
}

export type TimelineTrigger =
  | { type: 'VIRTUAL_TIME'; atSecond: number }
  | { type: 'PRICE'; priceType: PriceType; operator: 'GTE' | 'LTE'; value: string }
  | { type: 'AFTER_ACTION'; actionId: string; delaySeconds: number }
  | { type: 'GROUP'; operator: 'ALL' | 'ANY'; items: TimelineTrigger[] }
```

- [ ] **Step 1: Write failing normalization and validation tests**

Cover:

- stable key order and stable timeline order.
- equal semantic scenarios produce equal SHA-256 hash.
- invalid precision/min quantity/min notional.
- illegal Reduce Only quantity.
- invalid leverage/margin range.
- invalid normal-mode action blocks run.
- negative-mode action requires expected code/type.
- saved/running scenario cannot mutate.

- [ ] **Step 2: Run and confirm RED**

```powershell
node --test fx-trading-platform/apps/admin/src/features/tradingLab/model/*.test.ts
```

Expected: module failures.

- [ ] **Step 3: Implement canonical normalization and Web Crypto hash**

Canonical JSON:

- recursively sort object keys.
- preserve array order.
- keep all decimals as normalized strings.
- exclude transient UI state.

`hashScenarioConfig` returns lowercase 64-character SHA-256 hex.

- [ ] **Step 4: Implement Chinese validation messages**

Return:

```ts
export type ScenarioValidationIssue = {
  path: string
  code: string
  message: string
  severity: 'ERROR' | 'WARNING'
}
```

No component should duplicate business validation.

- [ ] **Step 5: Run model tests**

Expected: all pass.

### Task 4: Implement Spot and Perpetual browser Oracles

**Interfaces:**

```ts
export function calculateSpotAction(
  state: LocalCalculationState,
  action: TimelineAction,
  tick: MarketTick,
  config: TradingLabConfigSnapshot
): LocalCalculationState

export function calculatePerpetualAction(
  state: LocalCalculationState,
  action: TimelineAction,
  tick: MarketTick,
  config: TradingLabConfigSnapshot
): LocalCalculationState

export function calculateScenario(scenario: TradingLabScenario): LocalCalculationResult
```

- [ ] **Step 1: Write Spot RED tests**

Cover:

- repeated buys.
- partial sell.
- buy again.
- final sell.
- moving average remains unchanged after partial sell.
- cumulative net cost and break-even.
- all fees paid in USDT.
- insufficient USDT and oversell validation.

Use fixed string assertions for every wallet, cost, fee, and realized PnL.

- [ ] **Step 2: Run and confirm RED**

```powershell
node --test fx-trading-platform/apps/admin/src/features/tradingLab/oracle/spotOracle.test.ts
```

Expected: module failure.

- [ ] **Step 3: Implement Spot Oracle**

Keep separate fields:

```ts
averageCost
grossQuoteCost
feeCostUsdt
netInvestedUsdt
breakEvenPrice
realizedGrossPnl
realizedNetPnl
```

- [ ] **Step 4: Write Perpetual/Risk RED tests**

Cover:

- long/short.
- repeated add.
- repeated partial close.
- add/close/add/close.
- one-way reversal.
- hedge LONG/SHORT slots.
- cross/isolated.
- margin add/remove.
- leverage change.
- funding sign.
- multi-symbol shared USDT.
- isolated and cross liquidation.
- “other marks unchanged” liquidation estimate.

- [ ] **Step 5: Implement Perpetual and risk Oracles**

Keep gross PnL, trading fee, funding fee, liquidation fee, and net PnL separate. Return assumptions as human-readable strings in the result.

- [ ] **Step 6: Add local snapshot tests**

Every action result includes immutable:

```ts
{
  actionId,
  virtualTime,
  orders,
  trades,
  spotPositions,
  perpetualPositions,
  wallets,
  accountSummary,
  ledgerProjection,
  risk,
  warnings
}
```

- [ ] **Step 7: Run all Oracle tests**

```powershell
node --test fx-trading-platform/apps/admin/src/features/tradingLab/oracle/*.test.ts
```

Expected: all pass.

### Task 5: Implement deterministic market paths and random scenarios

**Interfaces:**

```ts
export function createPrng(seed: string): () => number
export function generateRandomScenario(input: RandomScenarioInput): TradingLabScenario
export function generateMarketTicks(path: MarketPathDefinition): MarketTick[]
```

The PRNG may return `number` only as an unsigned random fraction; generated money values must be converted through integer ranges and Decimal strings before use.

- [ ] **Step 1: Write failing reproducibility tests**

Assert:

- same seed and input produce deep-equal canonical JSON.
- different seed changes at least one action and one path point.
- 5 minutes produce exactly 300 ticks.
- monotonic mode never reverses direction.
- realistic mode varies but lands exactly on the target.
- advanced mode emits complete bid/ask/last/mark/index each second.
- generated legal scenarios pass validation.
- generated negative actions include expected error metadata.

- [ ] **Step 2: Run and confirm RED**

```powershell
node --test fx-trading-platform/apps/admin/src/features/tradingLab/generator/*.test.ts
```

Expected: module failures.

- [ ] **Step 3: Implement a documented 32-bit PRNG**

Use a fixed algorithm such as Mulberry32 with a stable UTF-8 seed hash. Keep the implementation and test vectors in `prng.ts`.

- [ ] **Step 4: Implement paths**

For each segment:

- compute integer-step interpolation.
- add deterministic bounded noise only in realistic mode.
- clamp bid `<` ask.
- force final Tick exactly to configured targets.

- [ ] **Step 5: Implement random scenario generation**

Select only supported action templates. In normal mode, maintain enough balance/margin while constructing actions. In negative mode, inject one explicit illegal property and expected error.

- [ ] **Step 6: Run generator tests**

Expected: all pass.

### Task 6: Implement IndexedDB drafts and JSON import/export

**Interfaces:**

```ts
export interface TradingLabDraftStore {
  list(): Promise<TradingLabDraftSummary[]>
  get(id: string): Promise<TradingLabScenario | null>
  put(scenario: TradingLabScenario): Promise<void>
  delete(id: string): Promise<void>
}
```

- [ ] **Step 1: Write failing storage tests**

Use a small fake IndexedDB adapter or injectable request factory. Cover put/get/list/delete, schema upgrade, corrupt record rejection, and debounced autosave.

- [ ] **Step 2: Run and confirm RED**

Expected: module failure.

- [ ] **Step 3: Implement native IndexedDB store**

Database name: `fx-platform-trading-lab`. Object store: `drafts`, key path `id`, index `updatedAt`.

- [ ] **Step 4: Implement JSON import/export**

- import validates schema and normalizes decimals before replacing editor state.
- export uses canonical JSON and a Blob download.
- neither operation sends a draft to the backend.

- [ ] **Step 5: Run storage tests**

Expected: all pass.

### Task 7: Build the timeline editor and local expected-result workspace

**Interfaces:**

`TimelineEditor` props:

```ts
type TimelineEditorProps = {
  actions: TimelineAction[]
  locked: boolean
  issues: ScenarioValidationIssue[]
  onChange(actions: TimelineAction[]): void
}
```

- [ ] **Step 1: Write component contract tests**

Assert source/runtime behavior for:

- add/delete/duplicate/edit.
- HTML drag/drop ordering and up/down buttons.
- same-second explicit sequence.
- locked state disables every mutation.
- negative mode warning and expected error fields.
- all English price labels include Chinese text in parentheses.

- [ ] **Step 2: Implement focused components**

Do not put all editors in `TradingLabPage.tsx`. `TimelineActionEditor` switches on action type and emits one normalized `TimelineAction`.

- [ ] **Step 3: Implement local calculation scheduling**

Run the pure Oracle after debounced scenario changes. For large scenarios, compute in a Web Worker created from a dedicated module; keep the main thread responsive.

- [ ] **Step 4: Add desktop-width guard**

At `<1280px`, show:

```text
交易路径实验室首版仅支持宽度不低于 1280px 的桌面端。
```

Do not mount the editor or chart below the threshold.

- [ ] **Step 5: Run tests and build**

Expected: Admin tests and build pass.

### Task 8: Add KLineCharts Tick/K-line view and event markers

**Interfaces:**

`TradingLabChart` receives bounded visible data:

```ts
type TradingLabChartProps = {
  symbol: string
  ticks: MarketTick[]
  localTicks: MarketTick[]
  markers: TradingLabChartMarker[]
  period: '1s' | '1m' | '5m' | '15m' | '1h'
  mode: 'TICK' | 'KLINE'
  visiblePrices: Set<PriceType>
  virtualTime: string
}
```

- [ ] **Step 1: Add explicit KLineCharts dependency/build preparation**

Add Admin `prebuild` using the existing `ensure-klinecharts-dist.mjs` and an explicit local dependency resolution that works from `apps/admin`.

- [ ] **Step 2: Write chart utility RED tests**

Cover:

- 60 one-second ticks aggregate into one 1-minute candle.
- bid/ask/last/mark/index series toggles.
- marker mapping for open/add/reduce/TP/SL/funding/liquidation.
- local and actual series remain distinct.
- virtual time maps to the correct candle.

- [ ] **Step 3: Implement chart wrapper**

Initialize/dispose exactly once per mounted symbol panel. Use refs for latest Tick, visible range, and chart instance. Do not store the full report in the chart.

- [ ] **Step 4: Add bounded data window**

Keep all authoritative ticks in report/backend storage. The page retains a configurable visible window, default 10,000 ticks, and requests older chunks on demand.

- [ ] **Step 5: Run chart tests and build**

Expected: tests pass; chart lives in the lazy route chunk.

### Task 9: Implement authenticated fetch-SSE and run controls

**Interfaces:**

```ts
export function streamTradingLabRun(
  runId: string,
  handlers: TradingLabStreamHandlers,
  options: { signal: AbortSignal; lastEventId?: string }
): Promise<void>
```

- [ ] **Step 1: Write stream parser RED tests**

Cover fragmented UTF-8 chunks, multiline `data:`, event IDs, heartbeat comments, reconnect from last ID, malformed event isolation, and AbortController cancellation.

- [ ] **Step 2: Implement fetch-stream parser**

Use `apiRaw`, `ReadableStreamDefaultReader`, and `TextDecoder` streaming mode. Do not use native `EventSource`.

- [ ] **Step 3: Implement run state handling**

The page:

- creates a run only after validation passes.
- freezes editor state.
- subscribes to SSE.
- updates state/progress/current tick/current step.
- sends pause/resume/cancel REST calls.
- reconnects after transient stream failure.
- reloads durable run state after page refresh.

- [ ] **Step 4: Enforce authorities**

- VIEW: page/read only.
- EXECUTE: create, pause, resume, cancel, negative mode.
- SUPER_ADMIN: environment actions and large print confirmation.

Disable unavailable controls in the UI but rely on backend denial.

- [ ] **Step 5: Run stream and page tests**

Expected: all pass.

### Task 10: Implement actual-state, report download, and raw JSON print

**Interfaces:**

```ts
export async function downloadReport(reportId: string): Promise<void>
export async function printRawReport(reportId: string): Promise<void>
```

- [ ] **Step 1: Write failing raw-stream tests**

Assert:

- download uses `apiRaw`.
- response is piped to Blob/file without JSON envelope parsing.
- print queries size first.
- >50 MB requires SUPER_ADMIN confirmation API.
- main page never calls `response.text()` for a report.

- [ ] **Step 2: Implement report panel**

Show metadata, sections, size, retention, permanent flag, errors, cleanup result, and paged/chunked preview.

Provide:

- download.
- delete with confirmation.
- mark/unmark permanent retention.
- print raw JSON.

Disable deletion while the associated run is active and refresh metadata after every retention action.

- [ ] **Step 3: Implement download**

Use `response.blob()` only for bounded browser download; when File System Access API is available, stream directly to a writable file. Do not put content in React state.

- [ ] **Step 4: Implement raw JSON print**

Open a same-origin print window. Stream UTF-8 text into a `<pre>` in bounded writes. After end-of-stream:

```js
printWindow.focus()
printWindow.print()
```

For >50 MB, obtain a one-time backend confirmation token and include it in the print request. Never truncate.

- [ ] **Step 5: Run tests and build**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run build"
```

Expected: pass.

### Task 11: Run Phase 3 integration gates

- [ ] **Step 1: Run all Admin tests**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin test"
```

Expected: zero failures.

- [ ] **Step 2: Run TypeScript and build**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run build"
```

Expected: TypeScript strict compile and Vite build pass.

- [ ] **Step 3: Run Web compatibility**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web run build"
```

Expected: pass.

- [ ] **Step 4: Run architecture**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

Expected: pass, including the rule that Admin trading-lab code references only `/api/admin/**`.
