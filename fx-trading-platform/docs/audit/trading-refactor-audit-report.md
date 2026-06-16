# Trading Refactor Audit Report

Audit date: 2026-06-16

Workspace: `C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform`

Scope source of truth:

- `C:\Users\User\Downloads\codex_step1_to_step10_detailed_task_doc.md`
- `C:\Users\User\Downloads\okx_binance_spot_perp_algorithms_selfcheck.md`
- `C:\Users\User\Downloads\forex_leveraged_trading_algorithm_selfcheck.md`

The original audit did not change production code. The 2026-06-16 FX financing basis follow-up updates `ForexFinancingService`, focused tests, and the Step 10 audit documentation.

## 1. Executive Summary

Overall status: PARTIAL

Overall score: 87/100

Launch readiness: NOT_READY

The Step 1-10 audit tests, the formula-level full regression audit, and the web test suite pass. The refactor is materially implemented across product type, wallet settlement, net positions, perpetual margin, funding, liquidation, and FX conversion/financing.

The duplicate Flyway migration version reported in this audit has been resolved by moving the funding and FX settlement migration to `V39` while keeping product type integrity on `V38`:

- `backend/src/main/resources/db/migration/V39__funding_and_fx_financing_settlements.sql`
- `backend/src/main/resources/db/migration/V38__symbol_product_type_integrity.sql`

There are still remaining P1/P2 gaps around Step 2 perpetual snapshot mark-price use and Step 7 frontend maintenance margin amount display. Step 10 FX financing now converts non-account-currency financing amounts before settlement and ledger write.

| Step | Name | Score | Status | Risk | Key Finding |
|---|---|---:|---|---|---|
| 1 | productType | 92 | PASS | Medium | Explicit `product_type` exists through DB/entity/API/frontend, with product type integrity kept on `V38`. |
| 2 | AccountSnapshotService | 88 | PARTIAL | Medium | Account summary and risk use dynamic snapshot; perpetual snapshot valuation is not clearly mark-price first. |
| 3 | Fee Notional | 86 | PARTIAL | Medium | FX/linear/inverse notional fees and ledger work; spot fee accounting is intentionally wallet/asset-ledger based. |
| 4 | Wallet Schema | 94 | PASS | Low | Wallet tables, service, API, demo initialization, and asset ledger are present. |
| 5 | Spot Wallet Settlement | 94 | PASS | Low | Spot BUY/SELL use wallets and avoid margin positions; frontend still has fallback/mock balance behavior. |
| 6 | PositionEngine | 90 | PASS | Medium | Netting, reduce, close, reverse, and inverse harmonic average are implemented; historical duplicate positions are not repaired. |
| 7 | Perp Maintenance Margin | 84 | PARTIAL | Medium | Linear/inverse formulas and backend response fields exist; frontend does not expose maintenance margin amount. |
| 8 | FundingService | 88 | PARTIAL | High | Funding direction/account/position/ledger/idempotency exist; settlement schema now uses migration `V39`. |
| 9 | LiquidationService | 86 | PASS | Medium | Scan loops, mark recomputation, forced close, sorting, fee, and scheduler gate exist; `shouldLiquidate(snapshot)` is narrower than scan logic. |
| 10 | FX Financing & Conversion | 92 | PARTIAL | Medium | Conversion-aware financing basis, ledger, triple-swap, and idempotency exist; OANDA-style home conversion factors remain out of scope. |

## 2. Environment

- OS/shell: Windows PowerShell
- Backend command directory: `C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend`
- Frontend command directory: `C:\Users\User\Desktop\workspace\tradingView-KlineChart`
- Java/Maven project: `fx-platform-backend`
- Frontend test runner: Node `node --test`
- Docker/Testcontainers: unavailable; `PostgresDatabaseIT` was skipped.

## 3. Baseline Test Result

Command:

```powershell
mvn "-Dtest=!com.fxplatform.audit.*,!*AuditTest,!FullTradingRegressionAuditTest" test
```

Result:

```text
BUILD SUCCESS
Tests run: 390, Failures: 0, Errors: 0, Skipped: 3
```

Environment note: `PostgresDatabaseIT` skipped three tests because Testcontainers could not find a valid Docker environment.

## 4. Step-by-step Findings

### Step 1 - productType

- Target: Use explicit `FX_MARGIN`, `CRYPTO_SPOT`, `LINEAR_PERP`, and `INVERSE_PERP` across DB, backend, API, and frontend.
- Files inspected: `V37__symbol_product_type.sql`, `V38__symbol_product_type_integrity.sql`, `ProductType.java`, `SymbolEntity.java`, `SymbolProductTypes.java`, `TradingInstrumentClassifier.java`, `SymbolResponse.java`, `tradePanelMarket.ts`.
- DB checks: `product_type` is added, backfilled, set `NOT NULL`, given a default, and constrained to the four product types.
- Backend checks: classifier uses `SymbolProductTypes.readOrLegacy`; `productType` is preferred and `assetClass=CRYPTO` with leverage remains spot.
- Frontend checks: `TradeMarket.productType` exists; quantity mode maps `CRYPTO_SPOT -> quote-budget`, `INVERSE_PERP -> contracts`, and `FX_MARGIN/LINEAR_PERP -> quantity`.
- Tests added/present: `Step01ProductTypeAuditTest`; frontend `tradePanelMarket.test.ts`.
- Passed: explicit mappings, fallback mappings, and leveraged crypto spot behavior.
- Failed: no product redline found.
- Score: 92
- Status: PASS
- Risk: Medium
- Recommendation: Fix the duplicate `V38` migration version before treating product-type DB integrity as deployable.

### Step 2 - AccountSnapshotService

- Target: Account summary and pre-trade risk must use dynamic snapshot values with open floating PnL.
- Files inspected: `AccountSnapshot.java`, `AccountSnapshotService.java`, `AccountResponse.java`, `AccountService.java`, `RiskCheckService.java`, `PnLCalculator.java`.
- DB checks: no new table required for this step.
- Backend checks: snapshot returns `openFloatingPnl`, `equity`, `usedMargin`, `freeMargin`, `marginLevel`, `maintenanceMargin`, `positionValue`, and warning metadata. `RiskCheckService` uses `accountSnapshotService.snapshot(account).freeMargin()`.
- Frontend checks: account API response includes snapshot fields consumed by session flows.
- Tests added/present: `Step02AccountSnapshotAuditTest`, `AccountSnapshotServiceTest`, `AccountSummarySnapshotIntegrationTest`.
- Passed: EURUSD floating PnL math, `usedMargin=0` stability, summary wiring, risk snapshot free margin, no observed balance mutation.
- Failed: perpetual snapshot valuation uses bid/ask closeout from `QuoteResponse`; it does not clearly use Step 7 mark price/mid in `AccountSnapshotService`.
- Score: 88
- Status: PARTIAL
- Risk: Medium
- Recommendation: Align `AccountSnapshotService` perpetual valuation with the mark-price path used in `LiquidationService`.

### Step 3 - Fee Notional

- Target: Fees use true notional for spot, FX, linear perp, and inverse perp.
- Files inspected: `TradingAlgorithmEngine.java`, `SimulatedExecutionAdapter.java`, `OrderFillService.java`, `LedgerService.java`, `TradeEntity.java`.
- DB checks: order fee fields exist; trade-level generic `fee`, `feeAsset`, and `notional` fields are not persisted.
- Backend checks: FX fee uses `lots * unitSize * price * rate`; linear uses quote notional; inverse uses `usdNotional / price * rate`; non-spot fill charges account balance and writes `TRADE_FEE`.
- Frontend checks: not directly involved.
- Tests added/present: `Step03FeeNotionalAuditTest`, `SimulatedExecutionAdapterFeeTest`.
- Passed: fixed expected values including EURUSD 0.1 lot fee = 11 and inverse fee = 0.0002 BTC.
- Failed: spot fee is settled via asset ledger in Step 5 rather than cash `TRADE_FEE`, and trade rows do not carry fill-level fee audit fields.
- Score: 86
- Status: PARTIAL
- Risk: Medium
- Recommendation: Add trade-level fee/notional audit fields if per-fill reconciliation is required; keep spot fee accounting in wallet/asset ledger.

### Step 4 - Wallet Schema

- Target: Add wallet balances, asset ledger, service, demo initialization, and wallet API.
- Files inspected: `V33__wallet_balances_and_asset_ledger.sql`, `WalletBalanceEntity.java`, `AssetLedgerEntryEntity.java`, `WalletService.java`, `AccountController.java`, `AccountService.java`.
- DB checks: `core.wallet_balances` and `ledger.asset_ledger_entries` exist with uniqueness, non-negative, and total/available/locked consistency constraints.
- Backend checks: `getOrCreateBalance`, credit, debit, lock, release, and debit locked are implemented; insufficient available/locked balance throws and leaves state unchanged.
- Frontend checks: wallet balances are loaded through trading session and account flows.
- Tests added/present: `Step04WalletSchemaAuditTest`, `WalletServiceTest`, `AccountControllerWalletTest`, `AccountWalletInitializationTest`.
- Passed: schema, repository, service, API, demo wallet, and asset ledger behavior.
- Failed: no hard failure. Precision is `NUMERIC(24,8)`, below the recommended `36,18`, but the document states this as a recommendation.
- Score: 94
- Status: PASS
- Risk: Low
- Recommendation: Consider widening precision before production crypto ledger usage.

### Step 5 - Spot Wallet Settlement

- Target: CRYPTO_SPOT BUY/SELL settles wallet assets instead of margin positions.
- Files inspected: `SpotSettlementService.java`, `RiskCheckService.java`, `OrderFillService.java`, `OrderService.java`, `WalletService.java`, `tradingSessionModels.ts`.
- DB checks: depends on Step 4 asset ledger schema.
- Backend checks: spot fill path branches to `SpotSettlementService` and returns before position creation. BUY debits quote and credits base less base fee. SELL debits base and credits quote less quote fee. Pending spot orders lock/release wallet assets.
- Frontend checks: trading balances prefer `walletBalances` over free margin and legacy position-derived inventory.
- Tests added/present: `Step05SpotWalletSettlementAuditTest`, `SpotSettlementServiceTest`, frontend trading session tests.
- Passed: `BUY 0.1 BTC @ 50000 -> USDT -5000, BTC +0.0999`; `SELL 0.1 BTC @ 55000 -> BTC -0.1, USDT +5494.5`; spot SELL does not create a SELL position.
- Failed: no core redline found. UI still has fallback/mock balance behavior when live wallet data is empty.
- Score: 94
- Status: PASS
- Risk: Low
- Recommendation: Keep fallback display clearly separate from live wallet state in UI.

### Step 6 - PositionEngine

- Target: Net-position engine supports add, reduce, close, and reverse for FX and perps.
- Files inspected: `PositionEngine.java`, `OrderFillService.java`, `PositionRepository.java`, `PnLCalculator.java`, `LedgerService.java`.
- DB checks: no unique open-net-position constraint found.
- Backend checks: `OrderFillService` routes FOREX, LINEAR_PERPETUAL, and INVERSE_PERPETUAL fills into `PositionEngine`; inverse add uses harmonic average; reduce/reverse writes margin and PnL ledger entries.
- Frontend checks: position response/display uses net positions.
- Tests added/present: `Step06PositionEngineAuditTest`, `PositionEngineTest`, `OrderPositionConcurrencyTest`.
- Passed: same-side add, weighted average, reduce, exact close, reverse, inverse harmonic average, margin release, and PnL ledger paths.
- Failed: historical duplicate open positions are not repaired; no DB uniqueness prevents old duplicate net positions.
- Score: 90
- Status: PASS
- Risk: Medium
- Recommendation: Add a data repair or uniqueness strategy for `(account_id, symbol, status=OPEN)` net positions.

### Step 7 - Perp Maintenance Margin

- Target: Perps support mark price, contract size, initial margin, maintenance margin, and response/display fields.
- Files inspected: `V34__perpetual_margin_fields.sql`, `PerpMarginCalculator.java`, `PositionEngine.java`, `PositionService.java`, `PositionResponse.java`, `components/tables/types.ts`, `positionDisplayModel.ts`.
- DB checks: symbol and position perpetual fields exist.
- Backend checks: linear and inverse formulas are implemented; backend `PositionResponse` includes `markPrice`, `maintenanceMargin`, `maintenanceMarginRate`, `liquidationPrice`, and margin asset fields are persisted on positions.
- Frontend checks: frontend types/display include `maintenanceMarginRate`, but not the `maintenanceMargin` amount from the backend response.
- Tests added/present: `Step07PerpMaintenanceMarginAuditTest`, `PerpMarginCalculatorTest`, frontend bottom account panel tests.
- Passed: linear IM 5000/MM 250; inverse IM 0.02 BTC/MM 0.001 BTC; display PnL path uses mark price for perps.
- Failed: frontend does not expose maintenance margin amount, and migration backfills some fields from `asset_class`, not the newer `product_type`.
- Score: 84
- Status: PARTIAL
- Risk: Medium
- Recommendation: Add `maintenanceMargin` to frontend position types/display and align migration backfill with `product_type`.

### Step 8 - FundingService

- Target: Perpetual funding settlement with direction, account/position updates, ledger, scheduler gate, and idempotency.
- Files inspected: `V35__perpetual_funding_rates.sql`, `V39__funding_and_fx_financing_settlements.sql`, `FundingService.java`, `FundingSettlementRepository.java`, `FundingSettlementScheduler.java`, `LedgerService.java`.
- DB checks: `trading.funding_rates`, `positions.funding_pnl`, and `trading.funding_settlements` exist; `(position_id, funding_time)` is unique.
- Backend checks: positive funding means long pays and short receives; linear and inverse formulas use mark/position value; closed positions are skipped; settlement insert is idempotent.
- Frontend checks: no required frontend settlement UI in the Step 8 scope.
- Tests added/present: `Step08FundingServiceAuditTest`, `FundingServiceTest`.
- Passed: direction, linear/inverse value, balance, `fundingPnl`, `FUNDING_FEE` ledger, scheduler disabled by default, idempotency hook.
- Failed: inverse short positive-funding case is not explicitly covered by the audit test class.
- Score: 88
- Status: PARTIAL
- Risk: High
- Recommendation: Add explicit inverse-short audit coverage.

### Step 9 - LiquidationService

- Target: Manual scan can liquidate FX/perp accounts through system close path with ledger and duplicate protection.
- Files inspected: `LiquidationService.java`, `PositionService.java`, `LedgerService.java`, `LiquidationScanScheduler.java`, `RiskConfigRepository.java`.
- DB checks: no new required table; uses existing positions, account, risk config, and ledger tables.
- Backend checks: `scanAccount`, `scanAllAccounts`, `shouldLiquidate`, and `liquidatePosition` exist. `scanAccount` loops, recomputes perp mark/notional/MM/floating PnL from fresh quote, sorts by risk contribution, calls `PositionService.closeSystemPosition`, and can write liquidation fee.
- Frontend checks: no required frontend force-close UI in the Step 9 scope.
- Tests added/present: `Step09LiquidationServiceAuditTest`, `LiquidationServiceTest`, `LiquidationFeeLedgerServiceTest`.
- Passed: FX/perp liquidation rules, system close path, `MARGIN_RELEASE`, `TRADE_PNL`, `FORCED_CLOSE`, duplicate close protection, scheduler disabled by default.
- Failed: public `shouldLiquidate(AccountSnapshot)` is narrower than `scanAccount` and does not include liquidation fee buffer or fresh mark recomputation; inverse liquidation fee currency handling needs further reconciliation.
- Score: 86
- Status: PASS
- Risk: Medium
- Recommendation: Either document `shouldLiquidate(snapshot)` as a shallow helper or make it share the full scan risk model.

### Step 10 - FX Financing & Conversion

- Target: FX PnL converts quote currency to account currency and financing settles with ledger, scheduler gate, triple-swap, and idempotency.
- Files inspected: `V36__fx_conversion_and_financing.sql`, `V39__funding_and_fx_financing_settlements.sql`, `ForexConversionService.java`, `PnLCalculator.java`, `ForexFinancingService.java`, `ForexFinancingSettlementRepository.java`, `ForexFinancingScheduler.java`, `LedgerService.java`.
- DB checks: `fx_conversion_rates`, `fx_financing_rates`, `positions.financing_accrued`, and `fx_financing_settlements` exist; `(position_id, settlement_date)` is unique.
- Backend checks: conversion supports same/direct/reverse/missing-rate error; USDJPY PnL converts JPY to USD; FX financing resolves `QUOTE_POSITION_VALUE`, `BASE_UNITS`, and account-currency ledger basis; financing updates account, position, settlement, and `FINANCING` ledger in `account.baseCurrency`; closed positions are skipped; Wednesday and USDCAD Thursday triple-swap logic exists.
- Frontend checks: no required frontend settlement UI in the Step 10 scope.
- Tests added/present: `Step10FxFinancingConversionAuditTest`, `ForexConversionServiceTest`, `ForexFinancingServiceTest`, `PnLCalculatorTest`.
- Passed: USDJPY `50000 JPY ~= 332.21 USD`; USDJPY financing converts JPY basis to USD ledger amount; EURUSD/USD financing stays in USD; long financing charge `-10`; short credit `+5`; missing conversion throws before account/ledger mutation.
- Failed: OANDA-style gain/loss home conversion factors are TODO.
- Score: 92
- Status: PARTIAL
- Risk: Medium
- Recommendation: Keep `docs/audit/fx-financing-basis.md` as the current basis contract; add OANDA-style home conversion factors only if that broker-specific model becomes in scope.

## 5. Full Regression Result

Command:

```powershell
mvn "-Dtest=FullTradingRegressionAuditTest" test
```

Result:

```text
BUILD SUCCESS
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

Status: PASS for the implemented `FullTradingRegressionAuditTest`.

Audit limitation: the current class is formula/service-level. It verifies key expected values for spot, FX fee, linear/inverse IM/MM, funding direction, and USDJPY conversion, but it does not execute one complete DB/API/frontend/ledger scenario for every product type.

## 6. Remaining Issues

### P0

- None after assigning funding and FX settlement schema to `V39__funding_and_fx_financing_settlements.sql`.

### P1

- Step 2 perpetual account snapshot does not clearly use mark price first, even though liquidation recomputation does.
- Step 7 frontend position model does not expose/display backend `maintenanceMargin` amount.

### P2

- Step 3 trade rows lack generic per-fill `fee`, `feeAsset`, and `notional` audit fields.
- Step 6 historical duplicate open net positions are not repaired or prevented by a DB uniqueness strategy.
- Step 8 audit coverage should add explicit inverse-short funding settlement.
- Step 9 `shouldLiquidate(AccountSnapshot)` is a shallow helper compared with the full `scanAccount` path.
- Step 10 OANDA-style `gainQuoteHome` / `lossQuoteHome` conversion factors and conversion markup ledger are not implemented.

## 7. Files Added or Modified

Files edited in this audit turn:

- `docs/audit/trading-refactor-audit-report.md`
- `docs/audit/trading-refactor-audit-summary.json`

Audit test suite present and executed:

- `Step01ProductTypeAuditTest`
- `Step02AccountSnapshotAuditTest`
- `Step03FeeNotionalAuditTest`
- `Step04WalletSchemaAuditTest`
- `Step05SpotWalletSettlementAuditTest`
- `Step06PositionEngineAuditTest`
- `Step07PerpMaintenanceMarginAuditTest`
- `Step08FundingServiceAuditTest`
- `Step09LiquidationServiceAuditTest`
- `Step10FxFinancingConversionAuditTest`
- `FullTradingRegressionAuditTest`

## 8. Raw Test Output Summary

Baseline backend:

```text
mvn "-Dtest=!com.fxplatform.audit.*,!*AuditTest,!FullTradingRegressionAuditTest" test
BUILD SUCCESS
Tests run: 390, Failures: 0, Errors: 0, Skipped: 3
```

Step audit backend:

```text
mvn "-Dtest=Step01ProductTypeAuditTest,Step02AccountSnapshotAuditTest,Step03FeeNotionalAuditTest,Step04WalletSchemaAuditTest,Step05SpotWalletSettlementAuditTest,Step06PositionEngineAuditTest,Step07PerpMaintenanceMarginAuditTest,Step08FundingServiceAuditTest,Step09LiquidationServiceAuditTest,Step10FxFinancingConversionAuditTest" test
BUILD SUCCESS
Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
```

Full regression audit:

```text
mvn "-Dtest=FullTradingRegressionAuditTest" test
BUILD SUCCESS
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

Frontend:

```text
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
tests 370
pass 370
fail 0
cancelled 0
skipped 0
todo 0
```

## Final Acceptance Status

Not complete for launch.

All requested audit test commands are green, and the duplicate migration version has been resolved. The remaining launch decision depends on whether the team accepts the Step 2 and Step 7 residual risks listed above.
