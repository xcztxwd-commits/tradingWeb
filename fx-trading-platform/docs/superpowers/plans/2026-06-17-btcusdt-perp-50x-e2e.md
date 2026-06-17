# BTCUSDT Perp 50x E2E Test Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify a dev/demo LINEAR_PERP BTCUSDT long position opened at a fixed quote, repriced upward, reduced by 30%, then fully closed with account, wallet, ledger, order, event, position, history, PnL, and liquidation fields analyzed.

**Architecture:** Add one repo-native smoke script that controls only local dev/test state, uses admin/test-control or a local DB fixture to pin BTCUSDT quotes, drives user-facing APIs for exact accounting assertions, and uses the real web UI for user-click open and final close evidence. Keep production logic unchanged; restore local BTCUSDT metadata after the smoke when it was changed as a fixture.

**Tech Stack:** Node.js `node:test`, local Spring Boot dev profile, PostgreSQL/Redis from `infra/docker-compose.yml`, Vite web app, Chrome/Edge CDP automation.

---

### Task 1: Add Smoke Contract Test

**Files:**
- Create: `scripts/smoke-btcusdt-perp-50x.test.mjs`
- Modify: `package.json`

- [ ] **Step 1: Write the failing test**

Create `scripts/smoke-btcusdt-perp-50x.test.mjs` that asserts:
- `package.json` exposes `smoke:btcusdt-perp-50x`.
- `scripts/smoke-btcusdt-perp-50x.mjs` exists.
- The script contains fixed quote phases `100000`, `102000`, and `101000`.
- The script calls `/api/auth/register`, `/api/accounts`, `/api/accounts/{id}/summary`, `/api/accounts/{id}/wallet-balances`, `/api/accounts/{id}/asset-ledger`, `/api/market/symbols`, `/api/market/quotes/BTCUSDT`, `/api/chart/candles`, `/api/trading/orders`, `/api/trading/orders/{id}/events`, `/api/trading/positions`, `/api/trading/positions/history`, and `/api/ledger`.
- The script validates `LINEAR_PERP`, `leverage: 50`, `quantity: '0.01'`, `SELL MARKET` partial close at `0.003`, remaining quantity near `0.007`, and final close through the UI close button or API fallback.

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /d /s /c "cd fx-trading-platform && node --test scripts/smoke-btcusdt-perp-50x.test.mjs"`

Expected: FAIL because the script and npm command do not exist yet.

### Task 2: Add BTCUSDT Perp Smoke Script

**Files:**
- Create: `scripts/smoke-btcusdt-perp-50x.mjs`
- Modify: `package.json`

- [ ] **Step 1: Implement minimal smoke script**

The script must:
- Require `API_BASE_URL`, defaulting to `http://127.0.0.1:18086`.
- Require `WEB_BASE_URL`, defaulting to `http://127.0.0.1:5199`.
- Register and log in a unique test user.
- Query or create a demo account.
- If BTCUSDT is not `LINEAR_PERP`, update the local PostgreSQL row as a test fixture and store the original row for restore.
- Login bootstrap admin and push fixed quotes through `/api/admin/market/test-control/overrides`.
- Capture snapshots after baseline, open, price-up, partial-close, reprice-down, and full-close.
- Open through the web UI buy market form when possible.
- Reduce 30% through `SELL MARKET` because current close API has no quantity or percentage parameter.
- Fully close through the web UI close button when possible, otherwise call `POST /api/trading/positions/{id}/close?accountId=...`.
- Print a JSON report with phase rows and bug records.

- [ ] **Step 2: Run source contract test**

Run: `cmd.exe /d /s /c "cd fx-trading-platform && node --test scripts/smoke-btcusdt-perp-50x.test.mjs"`

Expected: PASS.

### Task 3: Execute Backend and Frontend Chain

**Files:**
- Read-only runtime outputs under `.codex-runlogs/` or `test-results/`.

- [ ] **Step 1: Start infra and backend**

Run:
```powershell
docker compose -f fx-trading-platform/infra/docker-compose.yml up -d
```

Run backend with dev/demo/test-control:
```powershell
cd fx-trading-platform/backend
mvn spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--server.port=18086 --market.test-control.enabled=true --market.demo-quotes.enabled=true --market.test-data.enabled=true --market.provider-instrument-sync.enabled=false"
```

- [ ] **Step 2: Execute the smoke**

Run:
```powershell
cmd.exe /d /s /c "cd fx-trading-platform && npm.cmd run smoke:btcusdt-perp-50x"
```

Expected: PASS or a JSON bug report with exact failing phase.

### Task 4: Report Findings

**Files:**
- No mandatory code changes unless a bug is confirmed and fixed.

- [ ] **Step 1: Produce final report**

Include:
- Commands and results.
- Baseline/open/price-up/partial-close/reprice-down/full-close analysis table.
- Account, wallet, ledger, order, event, position, history, and trade-access status.
- Bug list in the user-requested format for every confirmed gap.
