# Spot + Linear Perpetual Scenario Matrix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or execute each task inline with RED → GREEN verification. This repository is intentionally kept on the current working tree for this request; do not create branches, commits, pushes, or pull requests.

**Goal:** Build a deterministic, executable CRYPTO_SPOT + LINEAR_PERP DEMO scenario matrix whose checked-in CSV/JSON rows map to runnable JUnit cases, calculate expected results with an independent test-scope BigDecimal Oracle before production trading calls, compare complete persisted outcomes, and generate a truthful report through one PowerShell runner.

**Architecture:** Add one test-only scenario domain under `com.fxplatform.trading.scenario`. A programmatic catalog is the single source of case definitions; an artifact writer renders the catalog to CSV/JSON, parameterized JUnit tests consume the same definitions, independent Spot/Perp Oracles calculate expected results, and a Spring/PostgreSQL executor calls existing production services and reads actual persisted state. The runner creates a uniquely named Compose PostgreSQL database, enables otherwise opt-in scenario integration tests, runs all gates, writes the report, and drops only the database it created.

**Tech Stack:** Java 21, JUnit Jupiter parameterized tests, Spring Boot 3.5.7, MyBatis-Plus, JdbcTemplate, PostgreSQL 16, Jackson already supplied by Spring Boot, PowerShell 7/Windows PowerShell compatibility.

## Global Constraints

- Cover only `CRYPTO_SPOT`, `LINEAR_PERP`, `DEMO`, applicable `CASH/CROSS/ISOLATED`, `ONE_WAY/HEDGE`, `MARKET/LIMIT/STOP_MARKET`, Spot OCO, `BASE/QUOTE/CONTRACTS`, `reduceOnly`, protection, funding, liquidation, batch close/cancel, idempotency, concurrency, stale data, and rollback.
- Exclude `FX_MARGIN`, `INVERSE_PERP`, live trading, real broker/FIX/LP/private exchange APIs, insurance fund, ADL, real matching, and newly generated partial fills.
- New normal-path fills are full-fill-only. `PARTIALLY_FILLED` is compatibility/rejection coverage only.
- Spot cannot short, use leverage, set `reduceOnly`, use Perp position sides, or attach Perp protection semantics.
- Public stop semantics use `STOP_MARKET`; legacy `STOP` is a compatibility rejection case.
- Schedulers remain disabled for scenario tests; pending order, protection, funding, and liquidation work is invoked explicitly.
- Expected results must be calculated before fixture creation calls any production trading service.
- Oracle code cannot call production `PnLCalculator`, `MarginCalculator`, `PositionEngine`, `SpotSettlementService`, `FullFillCoordinator`, or equivalent production calculators.
- All money math uses `BigDecimal`, explicit scale, and explicit `RoundingMode`.
- Each `caseId` appears in exactly one matrix row and one JUnit parameterized invocation display name.
- Failed requests must produce zero unintended wallet/account/position/trade/ledger mutation.
- Preserve the dirty working tree. Do not edit the user's modified P0 documents, `docs/architecture.md`, `scripts/smoke-visual-qa.mjs`, `.run-logs/`, or unrelated untracked files.
- Do not create commits, branches, pushes, or pull requests.
- When product semantics conflict, use current official Binance/OKX public documentation, choose the rule matching this project's product model and stricter safety behavior, and record the source in the report.

## File Structure

### Required artifacts

- `docs/testing/spot-perp-scenario-matrix.json`: generated full scenario catalog.
- `docs/testing/spot-perp-scenario-matrix.csv`: deterministic flattened rendering of the same catalog.
- `docs/testing/spot-perp-test-report.md`: baseline, semantic evidence, command results, case totals, defects, blockers, and coverage.
- `scripts/run-spot-perp-scenario-tests.ps1`: unique database lifecycle, matrix generation, tests, report, and cleanup.

### Test-only scenario model

- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioDefinition.java`: immutable 28-field matrix row plus typed execution metadata.
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioCatalog.java`: single source of all Spot/Perp cases.
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioAction.java`: typed actions such as BUY, SELL, ADD, PARTIAL_CLOSE, SETTLE_FUNDING, TRIGGER_PROTECTION.
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioPriceStep.java`: deterministic bid/ask/last/mark/index/source/asOf/expiresAt.
- `backend/src/test/java/com/fxplatform/trading/scenario/ExpectedScenarioResult.java`: expected order/trade/position/wallet/account/ledger/protection/event/error snapshots.
- `backend/src/test/java/com/fxplatform/trading/scenario/ActualScenarioResult.java`: actual snapshots in the same shape.
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioMatrixArtifacts.java`: JSON/CSV serialization and checked-in artifact comparison.
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioAssertions.java`: caseId-aware recursive comparison with decimal normalization.

### Independent Oracle

- `backend/src/test/java/com/fxplatform/trading/scenario/oracle/ScenarioDecimalMath.java`: scale and rounding helpers only.
- `backend/src/test/java/com/fxplatform/trading/scenario/oracle/SpotScenarioOracle.java`: fee, fill, weighted cost, realized PnL, wallet/hold/OCO calculations.
- `backend/src/test/java/com/fxplatform/trading/scenario/oracle/PerpetualScenarioOracle.java`: quantity conversion, entry average, realized/unrealized PnL, margin, leverage, funding, liquidation, protection budgets.

### PostgreSQL execution

- `backend/src/test/resources/application-scenario-it.yml`: scheduler-off scenario profile using `SCENARIO_DATABASE_URL`, `SCENARIO_DATABASE_USERNAME`, and `SCENARIO_DATABASE_PASSWORD`.
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioDatabaseGuard.java`: fail fast unless the database name starts with `fx_scenario_`.
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioFixture.java`: isolated user/account/wallet/settings creation and cleanup.
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioExecutor.java`: deterministic market stubbing and production service/API actions.
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioResultReader.java`: reads Order, Trade, Position, Wallet, Account, Ledger, Protection, and Event rows.
- `backend/src/test/java/com/fxplatform/trading/scenario/SpotScenarioMatrixIT.java`: Spot parameterized execution.
- `backend/src/test/java/com/fxplatform/trading/scenario/PerpetualScenarioMatrixIT.java`: Perp parameterized execution.
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioResilienceIT.java`: concurrency, idempotency, stale lock, rollback, and source-switch cases that need coordinated threads or failure injection.

---

### Task 1: Lock the catalog contract and artifact format

**Files:**
- Create the scenario model, catalog, artifact writer, and `ScenarioCatalogTest`.
- Create the two matrix artifacts through the tested writer.

**Interfaces:**
- `ScenarioCatalog.all(): List<ScenarioDefinition>`
- `ScenarioCatalog.spot(): Stream<ScenarioDefinition>`
- `ScenarioCatalog.perpetual(): Stream<ScenarioDefinition>`
- `ScenarioMatrixArtifacts.write(Path docsTesting, List<ScenarioDefinition> scenarios)`

- [ ] Write `ScenarioCatalogTest` first. It must fail because the catalog and artifacts do not exist.
- [ ] Assert all 28 listed required columns, unique nonblank `caseId`, valid `testClass/testMethod`, deterministic ordering, no excluded product, no new `STOP`, and no `PARTIALLY_FILLED` normal path.
- [ ] Assert coverage includes all 15 named price paths, four Spot action paths, 64 Perp three-leg/action/direction chains, all required order/margin/position/quantity dimensions, and positive/negative cases for every listed rule.
- [ ] Implement the minimum typed records and catalog builders.
- [ ] Generate checked-in JSON and CSV with stable UTF-8/LF output and RFC-4180 quoting.
- [ ] Re-run `mvn "-Dtest=ScenarioCatalogTest" test`; expected result is PASS with zero skipped tests.

### Task 2: Implement and unit-test independent Oracles

**Files:**
- Create the three Oracle files and `SpotScenarioOracleTest`, `PerpetualScenarioOracleTest`.

**Interfaces:**
- `SpotScenarioOracle.calculate(ScenarioDefinition): ExpectedScenarioResult`
- `PerpetualScenarioOracle.calculate(ScenarioDefinition): ExpectedScenarioResult`

- [ ] Write failing Spot formula tests for MARKET BUY/SELL, fee asset, weighted average cost, repeated partial sell, full reset of the cost cycle, LIMIT/STOP holds, and one shared OCO hold.
- [ ] Run the Spot Oracle tests and confirm failures are caused by missing Oracle behavior.
- [ ] Implement only the tested Spot formulas with explicit scales and rounding.
- [ ] Write failing Perp formula tests for long/short PnL, add, partial/full close, one-way reversal, hedge slots, initial/maintenance margin, leverage changes, isolated margin changes, reduce-only boundaries, funding signs, liquidation shortfall, and protection resize/expiry.
- [ ] Run the Perp Oracle tests and confirm failures are caused by missing Oracle behavior.
- [ ] Implement only the tested Perp formulas.
- [ ] Add a static contract test that rejects imports or references from Oracle package to production calculator/engine/settlement classes.
- [ ] Run all Oracle tests; expected result is PASS with zero skipped tests.

### Task 3: Build the Compose PostgreSQL scenario harness and core chains

**Files:**
- Create the scenario profile, guard, fixture, executor, result reader, assertions, and the two parameterized IT classes.

**Interfaces:**
- `ScenarioFixture.create(ScenarioDefinition): ScenarioContext`
- `ScenarioExecutor.execute(ScenarioContext, ScenarioDefinition): ActualScenarioResult`
- `ScenarioResultReader.read(ScenarioContext): ActualScenarioResult`
- `ScenarioAssertions.assertScenarioEquals(String caseId, ExpectedScenarioResult, ActualScenarioResult)`

- [ ] Write a failing database guard test proving the suite refuses `fx_platform` and accepts only `fx_scenario_*`.
- [ ] Write the first Spot integration case with the exact method order:

```java
ExpectedScenarioResult expected = oracle.calculate(scenario);
ScenarioContext context = fixture.create(scenario);
ActualScenarioResult actual = executor.execute(context, scenario);
ScenarioAssertions.assertScenarioEquals(scenario.caseId(), expected, actual);
```

- [ ] Run it against a unique Compose database and confirm RED on missing harness behavior.
- [ ] Implement the fixture by reusing current DEMO account, wallet, order, trade, position, ledger, and symbol services/repositories; do not duplicate production settlement.
- [ ] Implement deterministic `MarketBundleResolver` stubbing for bid/ask/last/mark/index/source/time.
- [ ] Make all Spot core price/action paths pass.
- [ ] Add the 64 required Perp core chains and make them pass for LONG and SHORT.
- [ ] Read and compare every applicable field after every action, not only final state.
- [ ] Run both matrix IT classes; expected result is every parameterized invocation PASS and zero skipped.

### Task 4: Add order-type, validation, idempotency, stale, concurrency, and rollback cases

**Files:**
- Extend catalog/executor/oracles and create `ScenarioResilienceIT`.

- [ ] Add failing tests for Spot LIMIT immediate/wait/modify/cancel/trigger, STOP_MARKET exact/cross/gap, OCO leg wins/group cancel/dual-trigger race, insufficient balance, oversell, precision, minNotional, balance race, exact replay, and fingerprint conflict.
- [ ] Add failing tests for Perp LIMIT/STOP, quantity units, reduceOnly below/equal/above, one-way/hedge, cross/isolated, leverage boundaries, leverage/margin adjustment, and exact replay/conflict.
- [ ] Add coordinated races for fill/cancel, close/protection, close/liquidation, batch close, and same-position double close.
- [ ] Add stale bundle, incomplete bundle, provider switch with gap, lock-wait expiry, and injected trade/ledger failure rollback.
- [ ] For each production defect, preserve the failing case, identify the first wrong persisted state, make the smallest production fix, and rerun the case, domain suite, and full matrix.
- [ ] Update matrix `executionStatus` only from actual test results.

### Task 5: Add protection, funding, liquidation, and batch lifecycle cases

**Files:**
- Extend catalog/executor/oracles; reuse production protection/funding/liquidation/batch services.

- [ ] Add attached and independent TP/SL, MARKET/LIMIT execution, partial trigger, position-driven resize/expiry, 10-order limit, and 11th rejection.
- [ ] Add positive, negative, and zero funding for long/short; add/close before settlement; idempotent settlement; funding-driven cross/isolated liquidation.
- [ ] Add safe, exact-boundary, beyond-boundary, gap, and bankruptcy-shortfall liquidation; multi-position recovery and cascading liquidation.
- [ ] Add cancel-all, close-all partial failure, admin force-close, and permission preservation.
- [ ] Compare fee, PnL, balance/equity/used/free/maintenance, ledger uniqueness, protection state, system order origin, and event ordering.
- [ ] Run all scenario tests and required existing domain tests with zero failures and zero scenario skips.

### Task 6: Implement the one-command runner and truthful report

**Files:**
- Create `scripts/run-spot-perp-scenario-tests.ps1`.
- Create/update `docs/testing/spot-perp-test-report.md`.

**Runner flow:**

1. Verify workspace paths and preserve the current dirty tree snapshot.
2. `docker compose -f infra/docker-compose.yml up -d`.
3. Generate a database name `fx_scenario_<UTC timestamp>_<8 hex>` and validate the prefix.
4. Create only that database through `fx-platform-postgres`.
5. Set scenario datasource environment variables and scheduler-off profile.
6. Generate/verify matrix artifacts.
7. Run Oracle/catalog tests.
8. Run Spot/Perp/Resilience scenario tests.
9. Run the requested focused suite.
10. Run full backend tests with the scenario database available.
11. If API/shared types changed, run Web/Admin builds and architecture verification.
12. Parse Surefire XML; missing scenario classes, zero tests, skipped scenario cases, failures, or errors fail the run.
13. Atomically write the Markdown report with totals, fixed defects, unresolved issues, commands/results, semantic sources, and coverage.
14. In `finally`, terminate owned helpers and drop only the exact database created in step 3.

- [ ] Write a runner contract test or dry-run mode first; confirm it fails before the script exists.
- [ ] Implement strict path/database ownership checks and `try/finally` cleanup.
- [ ] Run:

```powershell
powershell -ExecutionPolicy Bypass -File fx-trading-platform/scripts/run-spot-perp-scenario-tests.ps1
```

- [ ] Expected final result: CSV/JSON generated, every `caseId` has a JUnit result, all scenario cases pass, backend suite passes, required builds/architecture pass, cleanup succeeds, and the report contains exact command evidence.

## Approved Semantic Defaults

- Spot MARKET BUY quantity is quote budget; Spot MARKET SELL quantity is base quantity. This matches OKX `tgtCcy` defaults and the existing project contract.
- Spot uses `CASH`, `positionSide=BOTH`, `leverage=1`, and `reduceOnly=false`.
- One-way Perp nets/reverses in the BOTH slot; Hedge maintains independent LONG/SHORT slots.
- Oversized `reduceOnly` is rejected in full rather than automatically trimmed, matching the stricter OKX rule and existing project design.
- Perp STOP_MARKET and TP/SL trigger from mark price; actual fill uses executable bid/ask plus configured slippage.
- Positive funding means long pays short; negative funding means short pays long; zero means no transfer.
- Liquidation and funding use mark price and must never leave the user account balance below zero; unpaid loss is recorded as `BANKRUPTCY_SHORTFALL`.

## Baseline Recorded Before Changes

- Targeted command: `mvn "-Dtest=*Scenario*,*Spot*,*Perpetual*,*Protection*,*Funding*,*Liquidation*" test`
- Result: `363` tests, `0` failures, `0` errors, `43` skipped, build success.
- The skipped tests are PostgreSQL/Testcontainers classes. Docker CLI and Compose are healthy, but Testcontainers 1.21.3 receives HTTP 400 from the current Docker Desktop 4.81 named-pipe API. Setting the Docker endpoint and API version did not resolve it.
- Scenario integration tests therefore use a uniquely named database inside the already running Compose PostgreSQL instance; they do not rely on Testcontainers.
