# Wallet Type Asset Conversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add product-scoped wallet balances and a demo USDT-to-USD conversion service without changing the full trading order margin source in this slice.

**Architecture:** Keep `TradingAccountEntity` as the account container and extend `core.wallet_balances` with `wallet_type`. Existing wallet APIs remain compatible by defaulting legacy calls to `SPOT`; new code can explicitly address `FX_MARGIN`, `SPOT`, `USDT_PERP`, and `COIN_PERP`. Add `AssetConversionService` to move funds between wallet types with a demo fixed 1:1 rate and audited asset ledger entries.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, Flyway SQL migrations, JUnit 5, Mockito.

---

### Task 1: Wallet Type Isolation

**Files:**
- Create: `backend/src/main/java/com/fxplatform/wallet/enums/WalletType.java`
- Modify: `backend/src/main/java/com/fxplatform/wallet/entity/WalletBalanceEntity.java`
- Modify: `backend/src/main/java/com/fxplatform/wallet/entity/AssetLedgerEntryEntity.java`
- Modify: `backend/src/main/java/com/fxplatform/wallet/repository/WalletBalanceRepository.java`
- Modify: `backend/src/main/java/com/fxplatform/wallet/repository/AssetLedgerEntryRepository.java`
- Modify: `backend/src/main/java/com/fxplatform/wallet/service/WalletService.java`
- Test: `backend/src/test/java/com/fxplatform/wallet/service/WalletServiceTest.java`

- [x] Add a failing test proving `SPOT:USDT` and `USDT_PERP:USDT` are separate balances.
- [x] Run the targeted wallet test and verify it fails because wallet type APIs do not exist.
- [x] Add `WalletType`, entity fields, repository queries, and `WalletService` overloads.
- [x] Run the targeted wallet test and verify it passes.

### Task 2: Demo Asset Conversion

**Files:**
- Create: `backend/src/main/java/com/fxplatform/wallet/service/AssetConversionService.java`
- Test: `backend/src/test/java/com/fxplatform/wallet/service/AssetConversionServiceTest.java`

- [x] Add a failing test proving `USDT_PERP:USDT -> FX_MARGIN:USD` debits USDT, credits USD, and writes `CONVERT_OUT` / `CONVERT_IN` ledger rows.
- [x] Run the targeted conversion test and verify it fails because the service does not exist.
- [x] Implement minimal 1:1 conversion using `WalletService`.
- [x] Run the targeted conversion test and verify it passes.

### Task 3: Account API Surface And Migration

**Files:**
- Modify: `backend/src/main/java/com/fxplatform/account/dto/WalletBalanceResponse.java`
- Modify: `backend/src/main/java/com/fxplatform/account/dto/AssetLedgerEntryResponse.java`
- Create: `backend/src/main/java/com/fxplatform/account/dto/AssetConversionRequest.java`
- Create: `backend/src/main/java/com/fxplatform/account/dto/AssetConversionResponse.java`
- Modify: `backend/src/main/java/com/fxplatform/account/service/AccountService.java`
- Modify: `backend/src/main/java/com/fxplatform/account/controller/AccountController.java`
- Modify: `backend/src/test/java/com/fxplatform/account/controller/AccountControllerWalletTest.java`
- Create: `backend/src/test/java/com/fxplatform/account/service/AccountAssetConversionTest.java`
- Modify: `backend/src/test/java/com/fxplatform/account/service/AccountWalletInitializationTest.java`
- Create: `backend/src/main/resources/db/migration/V40__wallet_type_asset_conversion.sql`

- [x] Add wallet type to wallet and asset ledger responses.
- [x] Add optional `walletType` filter to asset ledger.
- [x] Add account-owned demo conversion endpoint.
- [x] Initialize new demo account base currency into `FX_MARGIN`.
- [x] Add migration for `wallet_type`, unique `(account_id, wallet_type, asset)`, and ledger wallet type.
- [x] Run targeted account and wallet tests.

### Verification

- [x] Run:
  `cd C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend; mvn -Dtest=WalletServiceTest,AssetConversionServiceTest,AccountControllerWalletTest,AccountWalletInitializationTest test`
- [x] Check:
  `git status --short -- fx-trading-platform/backend/src/main/java/com/fxplatform/wallet fx-trading-platform/backend/src/main/java/com/fxplatform/account fx-trading-platform/backend/src/main/resources/db/migration/V40__wallet_type_asset_conversion.sql fx-trading-platform/backend/src/test/java/com/fxplatform/wallet fx-trading-platform/backend/src/test/java/com/fxplatform/account`
