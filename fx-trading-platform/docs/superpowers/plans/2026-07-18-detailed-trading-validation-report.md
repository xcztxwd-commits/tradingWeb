# Detailed Trading Validation Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the existing Chinese report so all 191 scenarios show full operation steps, actual data, algorithm/expected data, and explicit equality checks.

**Architecture:** Extend the existing Node.js generator to load each `expected.json`, normalize actual and expected checkpoints into readable detailed records, and compute deep component-level comparisons. Keep the canonical report artifact and portable HTML renderer as the only delivery path.

**Tech Stack:** Node.js built-ins, `node:test`, canonical Data Analytics report artifact and portable renderer.

## Global Constraints

- Read only matrix, actual, expected, and the verified Markdown report as numerical evidence.
- Preserve exactly 191 unique cases: 36 Spot, 155 Perpetual, 28 expected rejections.
- Never invent instruments, fills, fees, PnL, failures, or unsupported partial-fill/depth behavior.
- Keep all report outputs in `docs/testing/`.
- Do not stage, commit, branch, push, or create a PR.

---

### Task 1: Expected evidence and equality model

**Files:**
- Modify: `scripts/generate-readable-trading-scenario-report.test.mjs`
- Modify: `scripts/generate-readable-trading-scenario-report.mjs`

**Interfaces:**
- `loadScenarioEvidence(platformRoot)` additionally returns `expectedByCaseId`.
- `buildCaseRecord(scenario, actual, expected)` emits readable detail and comparison fields.

- [x] **Step 1: Write failing tests** asserting 191 expected artifacts load, all checkpoint components compare equal, and representative cases expose `calculationTrace`.
- [x] **Step 2: Run** `node --test scripts/generate-readable-trading-scenario-report.test.mjs` and confirm failures are caused by missing expected/detail fields.
- [x] **Step 3: Implement** expected loading plus deep component comparison with `node:util.isDeepStrictEqual`.
- [x] **Step 4: Re-run** the generator tests and confirm the new tests pass without breaking existing semantic tests.

### Task 2: Detailed plain-language scenario records

**Files:**
- Modify: `scripts/generate-readable-trading-scenario-report.test.mjs`
- Modify: `scripts/generate-readable-trading-scenario-report.mjs`

**Interfaces:**
- Each record adds `scenarioAndInitialData`, `detailedSteps`, `actualFinalData`, `algorithmAndExpectedData`, and `comparisonResult`.

- [x] **Step 1: Write failing tests** for Spot profit, perpetual close profit, reduceOnly rejection, OCO, one-way reversal, funding liquidation, and provider switch.
- [x] **Step 2: Verify RED** with the focused Node test command.
- [x] **Step 3: Implement** complete snapshot formatters for orders, trades, positions, wallets, account, ledger, protections, events, and failure.
- [x] **Step 4: Implement** per-action market/request/actual/expected/equality narratives and preserve raw `calculationTrace` values.
- [x] **Step 5: Verify GREEN** for all generator tests.

### Task 3: Canonical report table upgrade

**Files:**
- Modify: `scripts/generate-readable-trading-scenario-report.test.mjs`
- Modify: `scripts/generate-readable-trading-scenario-report.mjs`
- Regenerate: `docs/testing/spot-perp-readable-test-report.artifact.json`
- Regenerate: `docs/testing/spot-perp-readable-test-report.html`

**Interfaces:**
- Nine category tables use the six detailed audit columns defined in the design.

- [x] **Step 1: Write failing artifact tests** for the six-column schema, 191 detailed rows, 191 equality passes, and visible methodology/caveat sections.
- [x] **Step 2: Verify RED**.
- [x] **Step 3: Update** artifact blocks, datasets, table columns, subtitles, sources, and summary metrics while preserving the existing report sections and coverage chart.
- [x] **Step 4: Verify GREEN** and regenerate the artifact.
- [x] **Step 5: Run the portable renderer** and capture its validation/package/browser-verification receipt.

### Task 4: Final validation

**Files:**
- Verify: all 382 evidence JSON files.
- Verify: generated artifact and embedded HTML payload.

**Interfaces:**
- Final handoff links the self-contained HTML and supporting artifact/generator.

- [x] **Step 1: Run** `node --check scripts/generate-readable-trading-scenario-report.mjs`.
- [x] **Step 2: Run** both Node test suites and require zero failures.
- [x] **Step 3: Recompute** 191/36/155/28 counts, uniqueness, 191 expected-vs-actual equalities, and representative values directly from evidence.
- [x] **Step 4: Decode** the embedded HTML payload and require exact dataset equality with the canonical artifact.
- [x] **Step 5: Scan** for missing fields, fake ETH, unsafe external executable assets, and renderer failure screenshots.
- [x] **Step 6: Report** browser QA as `structural_only` if Chromium remains unavailable.
