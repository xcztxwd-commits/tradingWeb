# BTCUSDT Perp Liquidation Chain Test Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development for code changes and superpowers:verification-before-completion before reporting completion.

**Goal:** Verify the complete backend plus frontend user chain for a BTCUSDT `LINEAR_PERP` 50x long as price moves from entry to mild loss, near liquidation, and below liquidation.

**Architecture:** Use backend tests for deterministic risk math and explicit `LiquidationService.scanAccount(accountId)` because scan is not exposed through a user API. Use a dev runtime with `execution.mode=demo`, `trading.liquidation.enabled=false`, and `market.test-control.enabled=true` for browser-driven register/login/order/position display proof.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, Flyway, PostgreSQL/Redis, Vite web app, Playwright/browser automation.

---

### Phase 1: Current Contract Discovery

- [ ] Confirm `BTCUSDT` is available as `LINEAR_PERP` in the current runtime data.
- [ ] If the default seed has `BTCUSDT` as `CRYPTO_SPOT`, record the gap and use a test fixture or admin/runtime setup to create the required `LINEAR_PERP` symbol without changing unrelated production behavior.
- [ ] Confirm scheduler default: `trading.liquidation.enabled=false`.
- [ ] Confirm dev execution mode: `execution.mode=demo`.

### Phase 2: Backend Deterministic Test

- [ ] Create a backend test scenario with a test user/account balance of `10000`, `BTCUSDT` as `LINEAR_PERP`, leverage `50`, quantity `0.01`, and deterministic quotes.
- [ ] Entry quote: mark `100000`.
- [ ] Mild loss quote: mark `99000`.
- [ ] Near-liquidation quote: estimated liquidation price plus a safe buffer.
- [ ] Below-liquidation quote: estimated liquidation price minus a small buffer.
- [ ] Assert `floatingPnl < 0`, `equity/freeMargin` decrease, `usedMargin` remains stable before close, `maintenanceMargin` is populated, and `liquidationPrice < entryPrice`.
- [ ] Assert no automatic close occurs while scheduler is disabled.
- [ ] Call `LiquidationService.scanAccount(accountId)` explicitly and assert liquidation happens only after the below-liquidation quote.
- [ ] Assert closed/history state, negative realized PnL, released used margin, and ledger rows including liquidation fee or the project's current equivalent forced-close entries.

### Phase 3: Frontend User Chain

- [ ] Start local PostgreSQL/Redis with `fx-trading-platform/infra/docker-compose.yml`.
- [ ] Start backend on a temporary port with `dev` profile, `EXECUTION_MODE=demo`, `TRADING_LIQUIDATION_ENABLED=false`, and `MARKET_TEST_CONTROL_ENABLED=true`.
- [ ] Start `apps/web` on a temporary port pointed at the backend.
- [ ] In the browser, register and log in a test user.
- [ ] Create/select the demo account and confirm initial balance `10000`.
- [ ] Set quote to entry mark `100000`.
- [ ] On `/trading?symbol=BTCUSDT`, place `BUY MARKET`, quantity `0.01`, leverage `50`.
- [ ] Confirm UI/API position fields: entry, mark/current price, quantity, leverage, liquidation price, floating PnL, maintenance margin, used/free margin.
- [ ] Set quote to `99000` and confirm the UI/API show negative floating PnL and reduced equity/free margin.
- [ ] Set quote near liquidation and confirm the position remains open with risk warning or dangerous margin level when supported.
- [ ] Set quote below liquidation; confirm scheduler still does not close automatically.

### Phase 4: Verification Report

- [ ] Run targeted backend tests.
- [ ] Run relevant frontend tests/build when frontend code is touched or E2E depends on it.
- [ ] Report exact commands, exit codes, scenario table, pass/fail/blocked status, and any implementation boundary that prevents full proof.
