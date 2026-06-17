# ETHUSDT 20x Position Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify a demo-execution LINEAR_PERP long position can be increased three times, keep one net position with correct weighted entry, split realized/floating PnL on partial close, and preserve realized PnL after full close.

**Architecture:** Use the existing Spring Boot trading stack and dev profile. Add a focused backend regression test for the engine/execution behavior that blocks this scenario, plus a runtime smoke script that registers an isolated user, fixes quote through test-control, snapshots account/wallet/ledger/orders/positions at every stage, and emits a table report. Use the web app for the final user-click smoke instead of mutating production-like configuration.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, Flyway, PostgreSQL/Redis dev services, Node smoke scripts, Vite web app, Codex Browser.

---

### Task 1: Baseline Existing Trading Tests

**Files:**
- Read: `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PositionEngine.java`
- Read: `fx-trading-platform/backend/src/main/java/com/fxplatform/execution/SimulatedExecutionAdapter.java`
- Read: `fx-trading-platform/backend/src/test/java/com/fxplatform/trading/service/PositionEngineTest.java`
- Read: `fx-trading-platform/backend/src/test/java/com/fxplatform/execution/SimulatedExecutionAdapterFeeTest.java`

- [ ] **Step 1: Run the focused baseline**

```powershell
cd fx-trading-platform/backend
mvn "-Dtest=PositionEngineTest,SimulatedExecutionAdapterFeeTest" test
```

Expected: existing focused tests pass, or failures are recorded before new changes.

### Task 2: Add Backend Regression Coverage For The Requested Chain

**Files:**
- Modify: `fx-trading-platform/backend/src/test/java/com/fxplatform/trading/service/PositionEngineTest.java`
- Test: `fx-trading-platform/backend/src/test/java/com/fxplatform/trading/service/PositionEngineTest.java`

- [ ] **Step 1: Write failing regression test**

Add a test that applies fills equivalent to:

```text
BUY 1 at 3000, leverage 20
BUY 1 at 3200, leverage 20
BUY 2 at 2800, leverage 20
SELL 2 at 3300
SELL 2 at 3100
```

Assertions:

```text
After 2nd buy: one open BUY position, lots 2, openPrice 3100
After 3rd buy: one open BUY position, lots 4, openPrice 2950, marginHeld/notional at 20x
After partial close: one open BUY position, lots 2, realizedPnl positive, marginHeld reduced
After full close: status CLOSED, realizedPnl positive, marginHeld/initialMargin/notional zero
```

- [ ] **Step 2: Run test red**

```powershell
cd fx-trading-platform/backend
mvn "-Dtest=PositionEngineTest#linearPerpetualTwentyTimesLongIncreasePartialCloseAndFullCloseKeepsWeightedAverageAndPnl" test
```

Expected: fails only if current implementation violates the requested behavior. If it passes, keep it as regression coverage.

- [ ] **Step 3: Minimal production fix only if needed**

Touch only the class that caused the failing assertion. Do not refactor adjacent trading code.

- [ ] **Step 4: Run focused green**

```powershell
cd fx-trading-platform/backend
mvn "-Dtest=PositionEngineTest,SimulatedExecutionAdapterFeeTest" test
```

Expected: new regression and existing execution adapter fee tests pass.

### Task 3: Add Runtime API Smoke For Fixed Quote Chain

**Files:**
- Create: `fx-trading-platform/scripts/smoke-ethusdt-20x-position-chain.mjs`
- Test: `fx-trading-platform/scripts/smoke-ethusdt-20x-position-chain.mjs`

- [ ] **Step 1: Implement smoke script**

The script must:

```text
1. Verify /actuator/health.
2. Login admin bootstrap user for test-control.
3. Register/login unique user.
4. Fetch accountId and baseline account summary, wallet balances, asset ledger, orders, positions, history.
5. Find ETHUSDT LINEAR_PERP; if absent, create/select a local test LINEAR_PERP USDT symbol fixture.
6. Set test-control quote before every order.
7. Submit BUY/BUY/BUY/SELL/SELL MARKET orders with leverage 20.
8. Re-fetch all required endpoints after each stage.
9. Query order events for every order.
10. Emit a JSON report and a compact analysis table with stage, action, position, PnL, margin, wallet, ledger, orders/trades/history state.
```

- [ ] **Step 2: Run red or blocked**

```powershell
cmd.exe /d /s /c "node scripts/smoke-ethusdt-20x-position-chain.mjs"
```

Expected: pass if runtime chain already works; fail with a specific assertion if quantity, weighted entry, margin, PnL, history, or traceability is wrong. If backend is not running, record blocked environment evidence and start dev services.

### Task 4: Browser User-Click Smoke

**Files:**
- Use: `fx-trading-platform/apps/web`
- Use: running backend on `http://localhost:8080`
- Use: running web app on `http://localhost:5173`

- [ ] **Step 1: Start backend**

```powershell
cd fx-trading-platform/backend
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Required environment:

```text
EXECUTION_MODE=demo
MARKET_TEST_CONTROL_ENABLED=true
MARKET_DEMO_QUOTES_ENABLED=true
```

- [ ] **Step 2: Start web**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web run dev -- --host 127.0.0.1 --port 5173"
```

- [ ] **Step 3: Use Browser to submit real UI orders**

Actions:

```text
1. Login/register unique test user in the web UI or inject that user's token after API registration.
2. Navigate to /trading?symbol=<selected LINEAR_PERP symbol>.
3. Set leverage 20, order type MARKET, quantity 1, click BUY.
4. Set fixed quote 3200, quantity 1, click BUY.
5. Set fixed quote 2800, quantity 2, click BUY.
6. Set fixed quote 3300, quantity 2, click SELL.
7. Set fixed quote 3100, close remaining quantity through SELL or Close UI.
8. Verify visible positions/orders update and cross-check via API snapshots.
```

Expected: the browser-clicked chain matches the API smoke assertions.

### Task 5: Final Verification

**Files:**
- Report: `fx-trading-platform/runtime/logs/ethusdt-20x-position-chain-report.json`

- [ ] **Step 1: Run backend focused tests**

```powershell
cd fx-trading-platform/backend
mvn "-Dtest=PositionEngineTest,SimulatedExecutionAdapterFeeTest" test
```

- [ ] **Step 2: Run user web build**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web run build"
```

- [ ] **Step 3: Run runtime smoke**

```powershell
cmd.exe /d /s /c "node scripts/smoke-ethusdt-20x-position-chain.mjs"
```

Expected: all executed checks pass, or the final report clearly separates code failures from environment blockers.
