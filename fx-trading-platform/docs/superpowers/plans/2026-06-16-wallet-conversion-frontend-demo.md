# Wallet Conversion Frontend Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the backend demo asset conversion on the web wallet surface so an authenticated user can convert `USDT_PERP:USDT` into `FX_MARGIN:USD` and refresh wallet/ledger data.

**Architecture:** Keep the current `useTradingSession` data flow as the source of account, wallet, and ledger state. Add typed account API contracts for conversion, render wallet rows by `walletType + asset`, and add a narrow conversion form on `WalletPage` that calls the backend and then refreshes session data.

**Tech Stack:** React, TypeScript, existing `apiClient`, Node test runner, Vite build.

---

### Task 1: Frontend Account API Contract

**Files:**
- Modify: `apps/web/src/types/trading.ts`
- Modify: `apps/web/src/services/accountApi.ts`
- Test: `apps/web/src/services/accountApi.test.ts`

- [x] Run the current frontend API/page contract tests and verify they fail on missing `walletType` and `convertAsset`.
- [x] Add `WalletType`, conversion payload/response types, and `walletType` fields to wallet and asset ledger models.
- [x] Add `walletType?: string` to `AssetLedgerFilters`.
- [x] Add `convertAsset(accountId, payload, token)` using `POST /api/accounts/{accountId}/asset-conversions`.

### Task 2: Wallet Page Demo Conversion

**Files:**
- Modify: `apps/web/src/pages/wallet/WalletPage.tsx`
- Test: `apps/web/src/pages/userPages.test.ts`

- [x] Render wallet assets by `walletKey(balance.walletType, balance.asset)` instead of asset alone.
- [x] Add a small `AssetConversionPanel` to submit `USDT_PERP:USDT -> FX_MARGIN:USD`.
- [x] On successful conversion, show a notice and call `refreshAccountData` to reload balances and ledger.
- [x] Expose `walletType` in ledger filtering and ledger table rows.
- [x] Keep existing deposit/withdrawal flow unchanged.

### Task 3: Verification

**Files:**
- Verify: `apps/web`
- Verify: `backend`

- [x] Run `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web test"`.
- [x] Run `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web run build"`.
- [ ] Run `cd fx-trading-platform/backend; mvn test` (not run; this slice only changes frontend files).
- [x] Run `git diff --check`.
