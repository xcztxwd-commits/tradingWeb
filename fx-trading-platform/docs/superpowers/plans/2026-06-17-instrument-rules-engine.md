# Instrument Rules Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a unified instrument rules source that powers backend order validation and frontend trade-panel validation.

**Architecture:** Add `InstrumentRulesEngine` as the single backend read model for symbol availability, provider capabilities, product profile, quantity/price/notional limits, leverage, and basic risk metadata. Keep `RiskCheckService` as the final order gate by delegating rule validation to the engine before existing balance and margin checks. Expose rules through market APIs and let the frontend consume the same fields for experience-level validation.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, JUnit 5, Mockito, React, TypeScript, Node test runner.

---

### Task 1: Backend Rules Engine

**Files:**
- Create: `backend/src/main/java/com/fxplatform/risk/model/InstrumentRules.java`
- Create: `backend/src/main/java/com/fxplatform/risk/service/InstrumentRulesEngine.java`
- Test: `backend/src/test/java/com/fxplatform/risk/service/InstrumentRulesEngineTest.java`

- [ ] Write failing tests for rules sourced from `market.symbols`, provider metadata, and provider capabilities.
- [ ] Implement a focused engine that returns nullable unsupported fields instead of inventing fake data.
- [ ] Keep reusable helpers for decimal parsing and positive fallback local to the engine until a second consumer appears.

### Task 2: Backend Order Rule Validation

**Files:**
- Modify: `backend/src/main/java/com/fxplatform/risk/service/RiskCheckService.java`
- Test: `backend/src/test/java/com/fxplatform/risk/service/RiskCheckServiceTest.java`

- [ ] Write failing tests for disabled/non-tradable symbols, invalid tick, invalid step, below min notional, and leverage above max.
- [ ] Call `InstrumentRulesEngine.validateOrderRules()` before existing quote and balance checks.
- [ ] Preserve current spot wallet and margin behavior after the new rule gate passes.

### Task 3: Market Rules API

**Files:**
- Create: `backend/src/main/java/com/fxplatform/market/dto/InstrumentRulesResponse.java`
- Modify: `backend/src/main/java/com/fxplatform/market/controller/MarketController.java`
- Test: `backend/src/test/java/com/fxplatform/market/controller/MarketControllerRulesTest.java`

- [ ] Add `GET /api/market/symbols/{symbol}/rules`.
- [ ] Add `GET /api/market/symbol-rules?symbols=BTCUSDT,ETHUSDT`.
- [ ] Return `exists=false` for unknown symbols in read APIs, while order validation still rejects unknown symbols.

### Task 4: Frontend Rules Consumption

**Files:**
- Modify: `apps/web/src/features/market/tradingMarketAdapters.ts`
- Modify: `apps/web/src/features/market/tradingMarketApi.ts`
- Modify: `apps/web/src/features/market/tradingModels.ts`
- Modify: `apps/web/src/features/trading/types/order.ts`
- Modify: `apps/web/src/features/trading/components/TradePanel.tsx`
- Modify: `apps/web/src/features/trading/components/tradePanelMarket.ts`
- Modify: `apps/web/src/features/trading/hooks/useTradeForm.ts`
- Modify: `apps/web/src/pages/trading/TradingPage.tsx`
- Test: existing focused frontend tests in `apps/web/src/features/market` and `apps/web/src/features/trading`.

- [ ] Write failing tests for rules API paths and rules-aware min-notional validation.
- [ ] Map backend rules into `TradingMarket.rules`.
- [ ] Pass selected market rules into `TradePanel`.
- [ ] Use rules for `minNotional`, `minAmount`, and market tradability without replacing backend final validation.

### Task 5: Delivery Notes and Verification

**Files:**
- Create: `docs/instrument-rules-engine-implementation-2026-06-17.md`

- [ ] Document implemented and intentionally unimplemented scope.
- [ ] Run backend targeted tests.
- [ ] Run frontend targeted tests.
- [ ] Report exact verification output and any residual gaps.
