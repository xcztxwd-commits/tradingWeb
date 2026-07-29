# Spot + Linear Perpetual DEMO Scenario Test Report
## Summary

- Status: **PASSED**
- Generated (UTC): 2026-07-17T20:40:38.7501216Z
- Total cases: 191
- Passed: 191
- Failed: 0
- Skipped: 0
- Not reported: 0
- JUnit invocations: 191
- JUnit suite failures/errors: 0/0

## Product coverage

- Spot (`CRYPTO_SPOT`): total=36, passed=36, failed=0, skipped=0, notReported=0
- Linear Perpetual (`LINEAR_PERP`): total=155, passed=155, failed=0, skipped=0, notReported=0

## Scope

- This DEMO matrix verifies one full fill per order. Real partial fills and DEPTH matching are explicitly out of scope and are not connected to a production entry point.
- `PARTIALLY_FILLED` is retained only as compatibility input and is rejected with `PARTIAL_FILL_NOT_SUPPORTED` before committed mutation.

## Fixed issues

- No fixed-issue metadata was supplied to this runner invocation.

## Unresolved issues

- None reported by this execution.

## Command results

- **start Compose PostgreSQL**
  - Command: `docker compose -f "C:\workspace\tradingWeb\fx-trading-platform\infra\docker-compose.yml" up -d postgres`
  - Result: PASS - Completed successfully
  - Started/finished (UTC): 2026-07-17T20:35:58.5461836Z / 2026-07-17T20:35:59.2644443Z
- **resolve Compose PostgreSQL container**
  - Command: `docker compose -f "C:\workspace\tradingWeb\fx-trading-platform\infra\docker-compose.yml" ps -q postgres`
  - Result: PASS - Completed successfully
  - Started/finished (UTC): 2026-07-17T20:35:59.2788358Z / 2026-07-17T20:35:59.7218415Z
- **wait for PostgreSQL readiness**
  - Command: `docker exec bde8e4ffd970a65c6670d869962dc2a1c7d29a3ee387ae713cc0ce4d614211c0 pg_isready -U postgres -d postgres`
  - Result: PASS - Completed successfully
  - Started/finished (UTC): 2026-07-17T20:35:59.7223475Z / 2026-07-17T20:35:59.9940687Z
- **create owned scenario database**
  - Command: `docker exec bde8e4ffd970a65c6670d869962dc2a1c7d29a3ee387ae713cc0ce4d614211c0 psql -U postgres -d postgres -c "CREATE DATABASE fx_scenario_it_20260717203558_4576_1c5f2468"`
  - Result: PASS - Completed successfully
  - Started/finished (UTC): 2026-07-17T20:35:59.9950679Z / 2026-07-17T20:36:01.0804040Z
- **matrix artifact update**
  - Command: `mvn -Dscenario.writeArtifacts=true -Dtest=ScenarioCatalogTest test`
  - Result: PASS - BUILD SUCCESS
  - Started/finished (UTC): 2026-07-17T20:36:01.0925383Z / 2026-07-17T20:36:12.7082384Z
- **catalog, database guard and independent Oracle tests**
  - Command: `mvn -Dtest=ScenarioCatalogTest,ScenarioDatabaseGuardTest,*ScenarioOracleTest,OracleIsolationContractTest test`
  - Result: PASS - BUILD SUCCESS
  - Started/finished (UTC): 2026-07-17T20:36:12.9583359Z / 2026-07-17T20:36:26.1011798Z
- **clear stale scenario expected/actual evidence**
  - Command: `clear "C:\workspace\tradingWeb\fx-trading-platform\backend\target\scenario-artifacts"`
  - Result: PASS - Completed successfully
  - Started/finished (UTC): 2026-07-17T20:36:26.3240658Z / 2026-07-17T20:36:27.5871590Z
- **Spot, Perpetual and resilience scenario matrix**
  - Command: `mvn -Dspring.profiles.active=scenario-it -Dscenario.it.enabled=true -Dtest=SpotScenarioMatrixIT,PerpetualScenarioMatrixIT,ScenarioResilienceIT test`
  - Result: PASS - BUILD SUCCESS
  - Started/finished (UTC): 2026-07-17T20:36:27.5871590Z / 2026-07-17T20:40:05.8096296Z
- **verify per-case expected/actual evidence**
  - Command: `verify 191 scenario expected/actual artifact pairs`
  - Result: PASS - Completed successfully
  - Started/finished (UTC): 2026-07-17T20:40:08.0346841Z / 2026-07-17T20:40:09.0203895Z
- **focused Spot, Perpetual, protection, funding and liquidation suite**
  - Command: `mvn -Dtest=*Scenario*,*Spot*,*Perpetual*,*Protection*,*Funding*,*Liquidation* test`
  - Result: PASS - BUILD SUCCESS
  - Started/finished (UTC): 2026-07-17T20:40:09.0203895Z / 2026-07-17T20:40:37.5037794Z
- **drop owned scenario database**
  - Command: `docker exec bde8e4ffd970a65c6670d869962dc2a1c7d29a3ee387ae713cc0ce4d614211c0 psql -U postgres -d postgres -c "DROP DATABASE fx_scenario_it_20260717203558_4576_1c5f2468 WITH (FORCE)"`
  - Result: PASS - Completed successfully
  - Started/finished (UTC): 2026-07-17T20:40:37.5037794Z / 2026-07-17T20:40:38.3775164Z

## Key files

- `docs/testing/spot-perp-scenario-matrix.csv`
- `docs/testing/spot-perp-scenario-matrix.json`
- `docs/testing/spot-perp-test-report.md`
- `scripts/run-spot-perp-scenario-tests.ps1`
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioCatalog.java`
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioExecutor.java`
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioResultReader.java`
- `backend/src/test/java/com/fxplatform/trading/scenario/SpotScenarioMatrixIT.java`
- `backend/src/test/java/com/fxplatform/trading/scenario/PerpetualScenarioMatrixIT.java`
- `backend/src/test/java/com/fxplatform/trading/scenario/ScenarioResilienceIT.java`
- `backend/src/test/java/com/fxplatform/trading/scenario/oracle/SpotScenarioOracle.java`
- `backend/src/test/java/com/fxplatform/trading/scenario/oracle/PerpetualScenarioOracle.java`
- `backend/target/scenario-artifacts/<caseId>/expected.json`
- `backend/target/scenario-artifacts/<caseId>/actual.json`

## Matrix coverage

- Cases: 191
- Price paths (15): CROSS_THRESHOLD, DOWN_DOWN_DOWN, DOWN_DOWN_UP, DOWN_UP_DOWN, DOWN_UP_UP, FLAT, GAP_THROUGH_THRESHOLD, OSCILLATE_AROUND_THRESHOLD, PROVIDER_SWITCH_WITH_GAP, STALE_MARKET, TOUCH_EXACTLY, UP_DOWN_DOWN, UP_DOWN_UP, UP_UP_DOWN, UP_UP_UP
- Action types (23): ADD, ADJUST_MARGIN, ADMIN_FORCE_CLOSE, BUY, CANCEL, CANCEL_ALL, CHANGE_LEVERAGE, CLOSE_ALL, CREATE_OCO, FULL_CLOSE, LIQUIDATE, MODIFY, PARTIAL_CLOSE, PARTIAL_SELL, PLACE_ORDER, RACE, REPLAY, REVALUE, REVERSE, SELL, SET_PROTECTION, SETTLE_FUNDING, TRIGGER
- Position modes: HEDGE, ONE_WAY
- Margin modes: CASH, CROSS, ISOLATED
- Order types: LIMIT, MARKET, STOP, STOP_MARKET
- Quantity units: BASE, CONTRACTS, QUOTE
- Test classes: com.fxplatform.trading.scenario.PerpetualScenarioMatrixIT, com.fxplatform.trading.scenario.ScenarioResilienceIT, com.fxplatform.trading.scenario.SpotScenarioMatrixIT
- Checked-in executionStatus values: NOT_RUN

## Database lifecycle

- Owned database: `fx_scenario_it_20260717203558_4576_1c5f2468`
- Creation: CREATED_AND_CONFIRMED
- Cleanup: DROPPED_AND_CONFIRMED

## Scenario failure details

- None reported.

## Exchange semantics

- Binance Spot REST/OCO: https://github.com/binance/binance-spot-api-docs/blob/master/rest-api.md
- Binance Spot commission FAQ: https://developers.binance.com/en/docs/products/spot/faqs/commission_faq
- Binance USD-M Futures API: https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api
- OKX API v5: https://www.okx.com/docs-v5/en/
- OKX funding FAQ: https://www.okx.com/en-us/help/funding-fees-for-perpetual-contracts-faq
- OKX liquidation FAQ: https://www.okx.com/en-gb/help/liquidation-faq
