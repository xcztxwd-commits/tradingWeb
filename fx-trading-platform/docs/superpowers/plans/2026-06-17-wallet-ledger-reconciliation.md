# Wallet Ledger Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make wallet, account, ledger, snapshots, and spot cost accounting auditable before trading launch.

**Architecture:** Keep the current modular Spring Boot backend and MyBatis-Plus repository style. Add explicit idempotency keys to wallet/cash ledger rows, add read-only reconciliation reports, persist daily snapshots, and introduce `trading.spot_positions` as the weighted-average cost source for spot assets.

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, Flyway SQL migrations, JUnit 5, AssertJ, Mockito.

---

### Task 1: Ledger Idempotency Foundation

**Files:**
- Modify: `backend/src/main/resources/db/migration/V43__wallet_ledger_idempotency_snapshots_spot_positions.sql`
- Modify: `backend/src/main/java/com/fxplatform/wallet/entity/AssetLedgerEntryEntity.java`
- Modify: `backend/src/main/java/com/fxplatform/ledger/entity/LedgerEntryEntity.java`
- Modify: `backend/src/main/java/com/fxplatform/wallet/repository/AssetLedgerEntryRepository.java`
- Modify: `backend/src/main/java/com/fxplatform/ledger/repository/LedgerEntryRepository.java`
- Modify: `backend/src/main/java/com/fxplatform/wallet/service/WalletService.java`
- Modify: `backend/src/main/java/com/fxplatform/ledger/service/LedgerService.java`
- Test: `backend/src/test/java/com/fxplatform/wallet/service/WalletServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/ledger/service/LedgerServiceTest.java`

- [ ] **Step 1: Write failing wallet idempotency test**

Add a test showing that calling `creditAvailableWithEntryType` twice with the same `accountId/walletType/asset/referenceType/referenceId/entryType` only mutates the wallet once and only writes one asset ledger row.

- [ ] **Step 2: Run wallet test and verify RED**

Run: `cd fx-trading-platform/backend && mvn -Dtest=WalletServiceTest#creditAvailableIsIdempotentForSameBusinessOperation test`

Expected: FAIL because the current service applies both credits.

- [ ] **Step 3: Implement wallet operation key**

Add `operationType` to `AssetLedgerEntryEntity`; persist it as the same value as `entryType`; add repository lookup by `(accountId, walletType, asset, referenceType, referenceId, operationType)`; make `WalletService.persist(...)` skip mutation if that key already exists.

- [ ] **Step 4: Write and pass cash ledger idempotency test**

Add `LedgerServiceTest` coverage for `recordTradeFee` or `recordFundingFee` with a stable operation key and verify duplicate calls return the existing entry without inserting twice.

### Task 2: Business Reference Alignment

**Files:**
- Modify: `backend/src/main/java/com/fxplatform/trading/service/OrderFillService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/SpotSettlementService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/FundingService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/ForexFinancingService.java`
- Test: `backend/src/test/java/com/fxplatform/trading/service/SpotSettlementServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/trading/service/FundingServiceTest.java`

- [ ] **Step 1: Write failing spot settlement test**

Show that duplicate settlement for the same trade/fill key does not double debit quote/base or double credit the received asset.

- [ ] **Step 2: Use operation instance IDs**

Pass `TradeEntity.id` to spot settlement and fee ledger calls. Use funding settlement IDs for funding ledger entries and FX financing settlement IDs for financing ledger entries.

### Task 3: Reconciliation Report

**Files:**
- Create: `backend/src/main/java/com/fxplatform/wallet/dto/WalletReconciliationIssue.java`
- Create: `backend/src/main/java/com/fxplatform/wallet/dto/WalletReconciliationReport.java`
- Create: `backend/src/main/java/com/fxplatform/wallet/service/WalletReconciliationService.java`
- Create: `backend/src/main/java/com/fxplatform/wallet/service/WalletReconciliationJob.java`
- Modify: `backend/src/main/java/com/fxplatform/wallet/repository/WalletBalanceRepository.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/repository/OrderRepository.java`
- Test: `backend/src/test/java/com/fxplatform/wallet/service/WalletReconciliationServiceTest.java`

- [ ] **Step 1: Write failing reconciliation tests**

Cover `total != available + locked`, asset ledger total delta mismatch, pending spot order hold mismatch, and account `usedMargin` mismatch against open positions plus pending margin orders.

- [ ] **Step 2: Implement read-only reconciliation**

Return issues with `code`, `severity`, `accountId`, `asset`, and `message`. The scheduled job should run only when `trading.wallet-reconciliation-enabled=true`.

### Task 4: Daily Snapshots

**Files:**
- Modify: `backend/src/main/resources/db/migration/V43__wallet_ledger_idempotency_snapshots_spot_positions.sql`
- Create: `backend/src/main/java/com/fxplatform/account/entity/AccountDailySnapshotEntity.java`
- Create: `backend/src/main/java/com/fxplatform/wallet/entity/WalletDailySnapshotEntity.java`
- Create: `backend/src/main/java/com/fxplatform/account/repository/AccountDailySnapshotRepository.java`
- Create: `backend/src/main/java/com/fxplatform/wallet/repository/WalletDailySnapshotRepository.java`
- Create: `backend/src/main/java/com/fxplatform/account/service/DailySnapshotService.java`
- Test: `backend/src/test/java/com/fxplatform/account/service/DailySnapshotServiceTest.java`

- [ ] **Step 1: Write failing snapshot service test**

Given one account and one wallet balance, `capture(LocalDate)` inserts one account daily snapshot and one wallet daily snapshot with the requested `snapshotDate`.

- [ ] **Step 2: Implement snapshot entities and service**

Use upsert-style repository inserts keyed by `(account_id, snapshot_date)` and `(account_id, wallet_type, asset, snapshot_date)`.

### Task 5: Spot Position Cost Basis

**Files:**
- Modify: `backend/src/main/resources/db/migration/V43__wallet_ledger_idempotency_snapshots_spot_positions.sql`
- Create: `backend/src/main/java/com/fxplatform/trading/entity/SpotPositionEntity.java`
- Create: `backend/src/main/java/com/fxplatform/trading/repository/SpotPositionRepository.java`
- Create: `backend/src/main/java/com/fxplatform/trading/service/SpotPositionService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/SpotSettlementService.java`
- Test: `backend/src/test/java/com/fxplatform/trading/service/SpotPositionServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/trading/service/SpotSettlementServiceTest.java`

- [ ] **Step 1: Write failing weighted-average buy test**

Buying BTC twice updates `quantity`, `averageCost`, `feeCost`, and `costAsset` using weighted average.

- [ ] **Step 2: Write failing sell PnL test**

Selling part of the BTC position reduces `quantity` and increases `realizedPnl` by `proceeds - allocatedCost - fee`.

- [ ] **Step 3: Implement minimal weighted-average spot cost service**

Keep one position row per `(accountId, walletType, asset, costAsset)`. Use quote asset as `costAsset` for `BTCUSDT`.

### Task 6: Verification

**Files:**
- No production files.

- [ ] **Step 1: Run targeted backend tests**

Run: `cd fx-trading-platform/backend && mvn -Dtest=WalletServiceTest,LedgerServiceTest,WalletReconciliationServiceTest,DailySnapshotServiceTest,SpotPositionServiceTest,SpotSettlementServiceTest,FundingServiceTest test`

- [ ] **Step 2: Run architecture/static check if backend tests pass**

Run: `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"`

- [ ] **Step 3: Report exact pass/fail evidence**

Do not claim completion unless the commands above pass or any blocked coverage is explicitly reported.
