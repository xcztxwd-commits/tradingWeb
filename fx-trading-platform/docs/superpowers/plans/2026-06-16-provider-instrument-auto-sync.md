# Provider Instrument Auto Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a backend scheduled job that keeps `market.provider_instruments` populated from enabled market data providers without auto-publishing user-facing trading symbols.

**Architecture:** Reuse the existing admin provider sync path as the single source of truth. Add a small scheduler that runs after startup and periodically calls a batch sync method for enabled providers; failures are recorded on provider health and do not block application startup.

**Tech Stack:** Spring Boot 3, Java 21, Spring `@Scheduled`, JUnit 5, Mockito.

---

### Task 1: Batch Sync Service Behavior

**Files:**
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminMarketDataProviderService.java`
- Test: `backend/src/test/java/com/fxplatform/admin/service/AdminMarketDataProviderServiceTest.java`

- [x] **Step 1: Write the failing test**

Add a test that calls `syncEnabledProviderInstruments()`, verifies enabled providers are synced, disabled providers are skipped, and one failing provider does not stop the next enabled provider.

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AdminMarketDataProviderServiceTest#syncEnabledProviderInstrumentsSkipsDisabledProvidersAndContinuesAfterFailure test`

Expected: FAIL because `syncEnabledProviderInstruments()` does not exist.

- [x] **Step 3: Write minimal implementation**

Add `syncEnabledProviderInstruments()` and a private helper that reuses the current upsert logic. On success set provider health to `UP`; on failure set it to `DOWN` and continue.

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=AdminMarketDataProviderServiceTest test`

Expected: PASS.

### Task 2: Scheduled Job

**Files:**
- Create: `backend/src/main/java/com/fxplatform/admin/service/ProviderInstrumentSyncScheduler.java`
- Create: `backend/src/test/java/com/fxplatform/admin/service/ProviderInstrumentSyncSchedulerTest.java`
- Modify: `backend/src/main/resources/application.yml`

- [x] **Step 1: Write the failing scheduler test**

Add tests proving the scheduler calls `AdminMarketDataProviderService.syncEnabledProviderInstruments()` and swallows runtime failures.

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=ProviderInstrumentSyncSchedulerTest test`

Expected: FAIL because `ProviderInstrumentSyncScheduler` does not exist.

- [x] **Step 3: Write minimal implementation**

Create `ProviderInstrumentSyncScheduler` with:
- `@Service`
- `@ConditionalOnProperty(prefix = "market.provider-instrument-sync", name = "enabled", havingValue = "true", matchIfMissing = true)`
- `@Scheduled(initialDelayString = "${market.provider-instrument-sync.initial-delay-ms:60000}", fixedDelayString = "${market.provider-instrument-sync.fixed-delay-ms:21600000}")`

- [x] **Step 4: Add configuration defaults**

Add `market.provider-instrument-sync.enabled`, `initial-delay-ms`, and `fixed-delay-ms` defaults to `application.yml`.

- [x] **Step 5: Run backend verification**

Run:
- `mvn -q -Dtest=AdminMarketDataProviderServiceTest,ProviderInstrumentSyncSchedulerTest test`
- `mvn -q test`

Expected: PASS, or report any unrelated environmental skips separately.
