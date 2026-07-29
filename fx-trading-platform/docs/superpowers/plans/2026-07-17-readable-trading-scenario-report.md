# Readable Trading Scenario Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate a self-contained Chinese HTML report that translates all 191 Spot and linear-Perpetual scenario results into business-readable trade stories.

**Architecture:** A Node.js generator reads the checked-in matrix plus the 191 runtime `actual.json` files, normalizes each scenario into one reader-facing record, classifies records into nine exclusive categories, and emits the canonical Data Analytics report `artifact.json`. The bundled portable report renderer validates that artifact and creates the final self-contained HTML.

**Tech Stack:** Node.js 24 built-ins, `node:test`, canonical Data Analytics portable report renderer.

## Global Constraints

- Never invent instruments, prices, quantities, fees, PnL, funding, or failures.
- Preserve the one-full-fill scope and explicit partial-fill/DEPTH exclusion.
- Cover exactly 191 unique cases: 36 Spot and 155 Perpetual.
- Keep generated output in `docs/testing/`.
- Do not stage, commit, branch, push, or create a PR.

---

### Task 1: Evidence normalizer and tests

**Files:**
- Create: `scripts/generate-readable-trading-scenario-report.mjs`
- Create: `scripts/generate-readable-trading-scenario-report.test.mjs`

**Interfaces:**
- Produces `loadScenarioEvidence(platformRoot)`, `classifyScenario(scenario)`, `buildCaseRecord(scenario, actual)`, and `buildReportArtifact(evidence, generatedAt)`.
- CLI accepts `--output <artifact.json>` and writes UTF-8 JSON.

- [ ] **Step 1: Write tests that load all evidence and assert exact coverage**

```js
const evidence = loadScenarioEvidence(platformRoot);
assert.equal(evidence.scenarios.length, 191);
assert.equal(evidence.records.filter((row) => row.productType === "CRYPTO_SPOT").length, 36);
assert.equal(evidence.records.filter((row) => row.productType === "LINEAR_PERP").length, 155);
assert.equal(new Set(evidence.records.map((row) => row.caseId)).size, 191);
```

- [ ] **Step 2: Add representative-value assertions**

```js
const spot = evidence.records.find((row) => row.caseId === "SPOT_SELL_PROFIT");
assert.match(spot.tradeProcess, /110\.51105000/);
assert.match(spot.tradeProcess, /119\.48805000/);
assert.match(spot.pnlSummary, /8\.86197280/);

const perp = evidence.records.find((row) => row.caseId === "PERP_CLOSE_PROFIT");
assert.match(perp.tradeProcess, /110\.51105000/);
assert.match(perp.tradeProcess, /119\.48805000/);
assert.match(perp.pnlSummary, /8\.97700000/);
assert.match(perp.pnlSummary, /8\.86200044/);
```

- [ ] **Step 3: Run the test and verify it fails before the generator exists**

Run: `node --test scripts/generate-readable-trading-scenario-report.test.mjs`

Expected: FAIL because the generator exports do not exist.

- [ ] **Step 4: Implement evidence loading, timeline extraction, PnL summaries, classification, Chinese labels, and artifact construction**

Each checkpoint contributes only newly observed trades and ledger events. Totals come from the final snapshot. Rejected scenarios use `expectedError` and final failure evidence; any legal setup trade or race winner remains visible in the final actual financial summary, while the rejected action itself must add no fabricated PnL.

- [ ] **Step 5: Run the generator tests**

Run: `node --test scripts/generate-readable-trading-scenario-report.test.mjs`

Expected: 191 unique cases, exact product/outcome counts, representative prices/PnL present, all assertions PASS.

### Task 2: Build the canonical artifact and HTML

**Files:**
- Create: `docs/testing/spot-perp-readable-test-report.artifact.json`
- Create: `docs/testing/spot-perp-readable-test-report.html`

**Interfaces:**
- Consumes the generator from Task 1.
- Produces a `surface: "report"` artifact with ordered markdown, metric-strip, and table blocks plus bounded snapshot datasets.

- [ ] **Step 1: Generate the artifact**

Run:

```powershell
node scripts/generate-readable-trading-scenario-report.mjs `
  --output docs/testing/spot-perp-readable-test-report.artifact.json
```

Expected: JSON contains 191 case rows split across nine datasets.

- [ ] **Step 2: Package and verify the portable HTML**

Run from the Data Analytics plugin root:

```powershell
npm run report:deliver -- `
  --input C:\workspace\tradingWeb\fx-trading-platform\docs\testing\spot-perp-readable-test-report.artifact.json `
  --output C:\workspace\tradingWeb\fx-trading-platform\docs\testing\spot-perp-readable-test-report.html
```

Expected: validation and packaging pass and the HTML is self-contained. Browser verification may be `structural_only` when no compatible Chromium headless shell is installed; disclose that limitation instead of claiming visual verification passed.

### Task 3: Final evidence and visual QA

**Files:**
- Verify: `docs/testing/spot-perp-readable-test-report.artifact.json`
- Verify: `docs/testing/spot-perp-readable-test-report.html`

- [ ] **Step 1: Re-run source tests and existing runner tests**

Run:

```powershell
node --test scripts/generate-readable-trading-scenario-report.test.mjs
node --test scripts/run-spot-perp-scenario-tests.test.mjs
```

Expected: all tests PASS.

- [ ] **Step 2: Verify artifact counts and generated HTML content**

Assert 191 unique case IDs, nine non-empty categories, representative values, no invented ETH trade, 382 parseable evidence JSON files, and a zero exit code from the portable verifier.

- [ ] **Step 3: Inspect the rendered report at desktop and narrow widths**

Use the portable renderer receipt when verification is `passed`; inspect a rendered screenshot with `view_image` only if the renderer emits a failure screenshot or the browser verifier reports visual defects.

- [ ] **Step 4: Confirm no git integration actions were performed**

Run: `git status --short`

Expected: the new report files and generator are visible as working-tree changes; no staging, commit, branch, push, or PR occurred.
