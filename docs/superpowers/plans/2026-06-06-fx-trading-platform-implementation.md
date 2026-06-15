# FX Trading Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/trading` the trusted primary trading terminal by fixing account-scoped authorization, preserving real backend order failures, consolidating the old trade entry, sharing market stream connections, and wiring the current frontend tests into verification.

**Architecture:** Keep existing API paths and service boundaries. Add ownership checks in backend services, make frontend session state explicit, keep `TradePanel` as a form component, and replace per-topic STOMP clients with one shared client per token/session. Work in three small phases so every phase is buildable and testable.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5, Mockito, React 19, TypeScript, Vite, Node `node:test`, STOMP over WebSocket.

---

## Scope Check

The spec touches backend authorization, frontend trading state, route consolidation, market streaming, and test scripts. These are coupled around one product goal: making `/trading` the trusted trading terminal. Keep the work in one plan, but implement it in independently verifiable tasks.

## File Structure

Backend files:

- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/controller/TradingController.java`: pass the authenticated principal into positions and close-position calls.
- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PositionService.java`: validate account ownership before reading or closing positions.
- `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/controller/LedgerController.java`: pass the authenticated principal into ledger reads.
- `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/service/LedgerService.java`: validate account ownership before returning ledger entries.
- `fx-trading-platform/backend/src/test/java/com/fxplatform/trading/service/PositionServiceTest.java`: add ownership-denial tests.
- `fx-trading-platform/backend/src/test/java/com/fxplatform/ledger/service/LedgerServiceTest.java`: create focused ledger ownership tests.

Frontend files:

- `fx-trading-platform/apps/web/src/services/apiClient.ts`: expose a typed API error with `status`, `code`, `message`, and optional `requestId`.
- `fx-trading-platform/apps/web/src/services/apiClient.test.ts`: create tests for API error parsing.
- `fx-trading-platform/apps/web/src/features/trading-session/tradingSession.ts`: do not convert backend order failures into local preview success.
- `fx-trading-platform/apps/web/src/features/trading-session/tradingSession.test.ts`: add source/state helpers for offline preview behavior.
- `fx-trading-platform/apps/web/src/features/trading-session/useTradingSession.ts`: expose session mode and last order error.
- `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx`: remove normal-path mock login and display explicit backend/offline states.
- `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.test.ts`: add source-level tests for no mock login and explicit preview copy.
- `fx-trading-platform/apps/web/src/app/App.tsx`: make `/trade` redirect to `/trading`.
- `fx-trading-platform/apps/web/src/app/App.test.ts`: add a route consolidation source test.
- `fx-trading-platform/apps/web/src/services/marketStream.ts`: implement shared STOMP client and topic subscription reuse.
- `fx-trading-platform/apps/web/src/services/marketStream.test.ts`: add source tests for shared client behavior.
- `fx-trading-platform/apps/web/src/pages/trading/components/KLineChartPanel.tsx`: add current-bar update hook from quote data after REST candle load.
- `fx-trading-platform/apps/web/src/pages/trading/components/KLineChartPanel.test.ts`: add a source test for non-empty `subscribeBar`/realtime update wiring.
- `fx-trading-platform/apps/web/package.json`: add `test` script using Node's test runner.
- `fx-trading-platform/package.json`: add `web:test` script that delegates to the web workspace.

## Task 1: Backend Account Ownership for Positions

**Files:**

- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/controller/TradingController.java`
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PositionService.java`
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java`
- Modify: `fx-trading-platform/backend/src/test/java/com/fxplatform/trading/service/PositionServiceTest.java`
- Modify: `fx-trading-platform/backend/src/test/java/com/fxplatform/trading/service/ProtectiveOrderExecutionServiceTest.java`

- [ ] **Step 1: Add failing tests for position ownership**

Append these tests to `PositionServiceTest` before the helper methods:

```java
  @Test
  void openPositionsRejectsAccountThatDoesNotBelongToUser() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.openPositions(userId, accountId))
        .isInstanceOf(com.fxplatform.common.exception.BusinessException.class)
        .hasMessageContaining("Account not found");

    verify(positionRepository, never()).findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN);
  }

  @Test
  void closePositionRejectsAccountThatDoesNotBelongToUser() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.closePosition(userId, accountId, positionId))
        .isInstanceOf(com.fxplatform.common.exception.BusinessException.class)
        .hasMessageContaining("Account not found");

    verify(positionRepository, never()).findById(positionId);
    verify(accountRepository, never()).save(any());
  }
```

Also add these imports at the top:

```java
import static org.mockito.ArgumentMatchers.any;
import com.fxplatform.common.exception.BusinessException;
```

Use `BusinessException.class` in the assertions after adding the import.

- [ ] **Step 2: Run the focused test and confirm it fails**

Run:

```powershell
$env:JAVA_HOME='C:\soft\fx-platform-tools\jdk-21'
$env:Path="$env:JAVA_HOME\bin;C:\soft\fx-platform-tools\apache-maven-3.9.9\bin;$env:Path"
mvn -Dtest=PositionServiceTest test
```

Expected: compilation fails because `openPositions(UUID, UUID)` and `closePosition(UUID, UUID, UUID)` do not exist yet.

- [ ] **Step 3: Update `PositionService` method signatures and ownership check**

In `PositionService`, change:

```java
  public List<PositionResponse> openPositions(UUID accountId) {
    return positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN)
```

to:

```java
  public List<PositionResponse> openPositions(UUID userId, UUID accountId) {
    requireOwnedAccount(userId, accountId);
    return positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN)
```

Change:

```java
  public PositionResponse closePosition(UUID accountId, UUID positionId) {
    PositionEntity position = positionRepository.findById(positionId)
```

to:

```java
  public PositionResponse closePosition(UUID userId, UUID accountId, UUID positionId) {
    TradingAccountEntity account = requireOwnedAccount(userId, accountId);
    PositionEntity position = positionRepository.findById(positionId)
```

Then remove the later duplicate account lookup:

```java
    TradingAccountEntity account = accountRepository.findById(accountId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
```

Add this private helper near the bottom of `PositionService`:

```java
  private TradingAccountEntity requireOwnedAccount(UUID userId, UUID accountId) {
    return accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
  }
```

- [ ] **Step 4: Update `TradingController` and protective order caller**

In `TradingController`, change positions endpoint from:

```java
  public ApiResponse<List<PositionResponse>> positions(@RequestParam UUID accountId) {
    return ApiResponse.success(positionService.openPositions(accountId));
  }
```

to:

```java
  public ApiResponse<List<PositionResponse>> positions(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam UUID accountId
  ) {
    return ApiResponse.success(positionService.openPositions(principal.id(), accountId));
  }
```

Change close endpoint from:

```java
  public ApiResponse<PositionResponse> closePosition(
      @RequestParam UUID accountId,
      @PathVariable UUID positionId
  ) {
    return ApiResponse.success(positionService.closePosition(accountId, positionId));
  }
```

to:

```java
  public ApiResponse<PositionResponse> closePosition(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam UUID accountId,
      @PathVariable UUID positionId
  ) {
    return ApiResponse.success(positionService.closePosition(principal.id(), accountId, positionId));
  }
```

In `ProtectiveOrderExecutionService`, replace:

```java
        positionService.closePosition(position.getAccountId(), position.getId());
```

with a new service method call added in `PositionService`:

```java
        positionService.closeSystemPosition(position.getAccountId(), position.getId());
```

Add this method to `PositionService` to preserve scheduled protective-order execution without a user principal:

```java
  @Transactional
  public PositionResponse closeSystemPosition(UUID accountId, UUID positionId) {
    TradingAccountEntity account = accountRepository.findById(accountId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    return closeOwnedPosition(account, positionId);
  }
```

Extract the body of the existing close logic after account ownership into:

```java
  private PositionResponse closeOwnedPosition(TradingAccountEntity account, UUID positionId) {
    UUID accountId = account.getId();
    PositionEntity position = positionRepository.findById(positionId)
        .orElseThrow(() -> new BusinessException("POSITION_NOT_FOUND", "Position not found"));
    if (!position.getAccountId().equals(accountId)) {
      throw new BusinessException("POSITION_ACCOUNT_MISMATCH", "Position does not belong to account");
    }
    QuoteResponse quote = quoteService.freshQuote(position.getSymbol());
    BigDecimal closePrice = position.getSide() == OrderSide.BUY ? quote.bid() : quote.ask();
    BigDecimal realizedPnl = pnlCalculator.floatingPnl(position.getSide(), position.getLots(), position.getOpenPrice(), closePrice);
    BigDecimal marginToRelease = position.getMarginHeld() == null ? BigDecimal.ZERO : position.getMarginHeld();

    position.setCurrentPrice(closePrice);
    position.setFloatingPnl(BigDecimal.ZERO);
    position.setRealizedPnl(realizedPnl);
    position.setMarginHeld(BigDecimal.ZERO);
    position.setStatus(PositionStatus.CLOSED);
    position.setClosedAt(Instant.now());
    positionRepository.save(position);

    BigDecimal usedMargin = account.getUsedMargin().subtract(marginToRelease).max(BigDecimal.ZERO);
    account.setBalance(account.getBalance().add(realizedPnl));
    account.setEquity(account.getBalance());
    account.setUsedMargin(usedMargin);
    account.setFreeMargin(account.getEquity().subtract(usedMargin));
    accountRepository.save(account);
    ledgerService.recordMarginRelease(account, marginToRelease, position.getId(), "Position margin released");
    ledgerService.recordTradePnl(account, realizedPnl, position.getId(), "Position closed");

    return toResponse(position);
  }
```

Then make user close call this helper:

```java
  @Transactional
  public PositionResponse closePosition(UUID userId, UUID accountId, UUID positionId) {
    TradingAccountEntity account = requireOwnedAccount(userId, accountId);
    return closeOwnedPosition(account, positionId);
  }
```

- [ ] **Step 5: Update existing position tests for new signatures**

In `PositionServiceTest`, update successful calls:

```java
    UUID userId = UUID.randomUUID();
```

Set the account helper to include user id:

```java
    TradingAccountEntity account = account(userId, accountId);
```

Stub ownership:

```java
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
```

Call:

```java
    List<PositionResponse> responses = service.openPositions(userId, accountId);
```

and:

```java
    PositionResponse response = service.closePosition(userId, accountId, positionId);
```

Change helper:

```java
  private static TradingAccountEntity account(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
```

- [ ] **Step 6: Update protective-order tests for system close**

In `ProtectiveOrderExecutionServiceTest`, replace each verification:

```java
    verify(positionService).closePosition(accountId, positionId);
```

with:

```java
    verify(positionService).closeSystemPosition(accountId, positionId);
```

Replace each negative verification:

```java
    verify(positionService, never()).closePosition(accountId, positionId);
```

with:

```java
    verify(positionService, never()).closeSystemPosition(accountId, positionId);
```

- [ ] **Step 7: Run focused and related backend tests**

Run:

```powershell
$env:JAVA_HOME='C:\soft\fx-platform-tools\jdk-21'
$env:Path="$env:JAVA_HOME\bin;C:\soft\fx-platform-tools\apache-maven-3.9.9\bin;$env:Path"
mvn -Dtest=PositionServiceTest,ProtectiveOrderExecutionServiceTest test
```

Expected: tests pass.

- [ ] **Step 8: Commit Task 1**

Run only after checking no unrelated files are staged:

```powershell
git status --short
git add fx-trading-platform/backend/src/main/java/com/fxplatform/trading/controller/TradingController.java `
  fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PositionService.java `
  fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java `
  fx-trading-platform/backend/src/test/java/com/fxplatform/trading/service/PositionServiceTest.java `
  fx-trading-platform/backend/src/test/java/com/fxplatform/trading/service/ProtectiveOrderExecutionServiceTest.java
git commit -m "fix: enforce account ownership for positions"
```

Expected: commit succeeds after Git identity is configured.

## Task 2: Backend Account Ownership for Ledger

**Files:**

- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/controller/LedgerController.java`
- Modify: `fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/service/LedgerService.java`
- Create: `fx-trading-platform/backend/src/test/java/com/fxplatform/ledger/service/LedgerServiceTest.java`

- [ ] **Step 1: Write failing ledger ownership tests**

Create `LedgerServiceTest.java`:

```java
package com.fxplatform.ledger.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

  @Mock
  private LedgerEntryRepository ledgerEntryRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Test
  void entriesRejectsAccountThatDoesNotBelongToUser() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    assertThatThrownBy(() -> service.entries(userId, accountId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Account not found");

    verify(ledgerEntryRepository, never()).findByAccountIdOrderByCreatedAtDesc(accountId);
  }

  @Test
  void entriesReadsLedgerForOwnedAccount() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    service.entries(userId, accountId);

    verify(ledgerEntryRepository).findByAccountIdOrderByCreatedAtDesc(accountId);
  }
}
```

- [ ] **Step 2: Run focused test and confirm it fails**

Run:

```powershell
$env:JAVA_HOME='C:\soft\fx-platform-tools\jdk-21'
$env:Path="$env:JAVA_HOME\bin;C:\soft\fx-platform-tools\apache-maven-3.9.9\bin;$env:Path"
mvn -Dtest=LedgerServiceTest test
```

Expected: compilation fails because `LedgerService` constructor and `entries(UUID, UUID)` do not exist yet.

- [ ] **Step 3: Inject account repository into `LedgerService`**

Update `LedgerService` fields:

```java
  private final LedgerEntryRepository ledgerEntryRepository;
  private final TradingAccountRepository accountRepository;
```

Add import:

```java
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
```

Replace:

```java
  public List<LedgerEntryEntity> entries(UUID accountId) {
    return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
  }
```

with:

```java
  public List<LedgerEntryEntity> entries(UUID userId, UUID accountId) {
    accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
  }
```

- [ ] **Step 4: Update `LedgerController`**

Add import:

```java
import com.fxplatform.common.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
```

Replace:

```java
  public ApiResponse<List<LedgerEntryEntity>> entries(@RequestParam UUID accountId) {
    return ApiResponse.success(ledgerService.entries(accountId));
  }
```

with:

```java
  public ApiResponse<List<LedgerEntryEntity>> entries(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam UUID accountId
  ) {
    return ApiResponse.success(ledgerService.entries(principal.id(), accountId));
  }
```

- [ ] **Step 5: Run backend tests**

Run:

```powershell
$env:JAVA_HOME='C:\soft\fx-platform-tools\jdk-21'
$env:Path="$env:JAVA_HOME\bin;C:\soft\fx-platform-tools\apache-maven-3.9.9\bin;$env:Path"
mvn -Dtest=LedgerServiceTest test
mvn test
```

Expected: `LedgerServiceTest` and full backend test suite pass.

- [ ] **Step 6: Commit Task 2**

```powershell
git status --short
git add fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/controller/LedgerController.java `
  fx-trading-platform/backend/src/main/java/com/fxplatform/ledger/service/LedgerService.java `
  fx-trading-platform/backend/src/test/java/com/fxplatform/ledger/service/LedgerServiceTest.java
git commit -m "fix: enforce account ownership for ledger"
```

## Task 3: Frontend API Error Model and No Silent Order Preview

**Files:**

- Modify: `fx-trading-platform/apps/web/src/services/apiClient.ts`
- Create: `fx-trading-platform/apps/web/src/services/apiClient.test.ts`
- Modify: `fx-trading-platform/apps/web/src/features/trading-session/tradingSession.ts`
- Modify: `fx-trading-platform/apps/web/src/features/trading-session/useTradingSession.ts`
- Modify: `fx-trading-platform/apps/web/src/features/trading-session/tradingSession.test.ts`

- [ ] **Step 1: Write API client error tests**

Create `apiClient.test.ts`:

```ts
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { ApiClientError, parseApiErrorPayload } from './apiClient.ts'

describe('api client errors', () => {
  it('keeps backend error code message status and request id', () => {
    const error = new ApiClientError({
      status: 403,
      code: 'ACCOUNT_NOT_FOUND',
      message: 'Account not found',
      requestId: 'req-123'
    })

    assert.equal(error.status, 403)
    assert.equal(error.code, 'ACCOUNT_NOT_FOUND')
    assert.equal(error.message, 'Account not found')
    assert.equal(error.requestId, 'req-123')
  })

  it('parses wrapped backend failure payloads', () => {
    assert.deepEqual(
      parseApiErrorPayload(
        {
          success: false,
          code: 'INSUFFICIENT_MARGIN',
          message: 'Free margin is not enough',
          data: null,
          requestId: 'req-9'
        },
        400
      ),
      {
        status: 400,
        code: 'INSUFFICIENT_MARGIN',
        message: 'Free margin is not enough',
        requestId: 'req-9'
      }
    )
  })
})
```

- [ ] **Step 2: Run API client test and confirm it fails**

From `fx-trading-platform/apps/web`, run:

```powershell
node --test src/services/apiClient.test.ts
```

Expected: fails because `ApiClientError` and `parseApiErrorPayload` do not exist.

- [ ] **Step 3: Implement typed API errors**

Replace `apiClient.ts` with:

```ts
type ApiResponse<T> = {
  success: boolean
  code: string
  message: string
  data: T
  requestId?: string
}

export type ApiClientErrorInit = {
  status: number
  code: string
  message: string
  requestId?: string
}

export class ApiClientError extends Error {
  readonly status: number
  readonly code: string
  readonly requestId?: string

  constructor(init: ApiClientErrorInit) {
    super(init.message)
    this.name = 'ApiClientError'
    this.status = init.status
    this.code = init.code
    this.requestId = init.requestId
  }
}

const API_BASE =
  (import.meta as ImportMeta & { env?: { VITE_API_BASE_URL?: string } }).env?.VITE_API_BASE_URL ?? ''

export async function apiGet<T>(path: string, token?: string): Promise<T> {
  return request<T>(path, { method: 'GET' }, token)
}

export async function apiPost<T>(path: string, body: unknown, token?: string): Promise<T> {
  return request<T>(
    path,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    },
    token
  )
}

export function parseApiErrorPayload(payload: unknown, status: number, requestId?: string): ApiClientErrorInit {
  if (isApiFailure(payload)) {
    return {
      status,
      code: payload.code || `HTTP_${status}`,
      message: payload.message || `Request failed: ${status}`,
      requestId: requestId ?? (typeof payload.requestId === 'string' ? payload.requestId : undefined)
    }
  }
  return {
    status,
    code: `HTTP_${status}`,
    message: `Request failed: ${status}`
  }
}

async function request<T>(path: string, init: RequestInit, token?: string): Promise<T> {
  const headers = new Headers(init.headers)
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers
  })
  const payload = (await response.json().catch(() => null)) as ApiResponse<T> | null
  if (!response.ok || !payload?.success) {
    const requestId = response.headers.get('X-Request-Id') ?? undefined
    throw new ApiClientError(parseApiErrorPayload(payload, response.status, requestId))
  }
  return payload.data
}

function isApiFailure(value: unknown): value is { success: false; code?: string; message?: string; requestId?: unknown } {
  return typeof value === 'object' && value !== null && 'success' in value && (value as { success?: unknown }).success === false
}
```

- [ ] **Step 4: Write no-silent-preview session tests**

Add to `tradingSession.test.ts`:

```ts
import { shouldCreateLocalPreview } from './tradingSession.ts'

describe('trading session submit mode', () => {
  it('allows local preview only without a backend token', () => {
    assert.equal(shouldCreateLocalPreview(undefined), true)
    assert.equal(shouldCreateLocalPreview(''), true)
    assert.equal(shouldCreateLocalPreview('token-1'), false)
  })
})
```

- [ ] **Step 5: Run session test and confirm it fails**

From `fx-trading-platform/apps/web`, run:

```powershell
node --test src/features/trading-session/tradingSession.test.ts
```

Expected: fails because `shouldCreateLocalPreview` is not exported.

- [ ] **Step 6: Update `tradingSession.ts` order submission**

Add:

```ts
export function shouldCreateLocalPreview(token?: string | null) {
  return !token
}
```

Replace:

```ts
export async function submitTradingOrder(payload: OrderPayload, token?: string) {
  try {
    if (!token) throw new Error('Demo account is not ready')
    return await createOrder(payload, token)
  } catch {
    return createLocalPreviewOrder(payload)
  }
}
```

with:

```ts
export async function submitTradingOrder(payload: OrderPayload, token?: string | null) {
  if (shouldCreateLocalPreview(token)) {
    return createLocalPreviewOrder(payload)
  }
  return createOrder(payload, token)
}
```

- [ ] **Step 7: Update `useTradingSession.ts` to expose order errors**

Add state:

```ts
  const [lastOrderError, setLastOrderError] = useState<unknown>(null)
```

In `submitOrder`, replace the current implementation with:

```ts
  const submitOrder = useCallback(
    async (payload: OrderPayload) => {
      setLastOrderError(null)
      try {
        const response = accountId
          ? await submitTradingOrder({ ...payload, accountId }, token)
          : createLocalPreviewOrder(payload)

        setOrders((current) => [response, ...current.filter((order) => order.id !== response.id)])

        if (token && accountId) {
          await refreshAccountData(token, accountId).catch(() => undefined)
        }

        return response
      } catch (error) {
        setLastOrderError(error)
        throw error
      }
    },
    [accountId, refreshAccountData, token]
  )
```

Return `lastOrderError` and a session mode:

```ts
    sessionMode: sessionReady ? 'ready' : token ? 'loading' : 'offline-preview',
    lastOrderError,
```

- [ ] **Step 8: Run focused frontend tests**

```powershell
cd fx-trading-platform\apps\web
node --test src/services/apiClient.test.ts
node --test src/features/trading-session/tradingSession.test.ts
```

Expected: both pass.

- [ ] **Step 9: Commit Task 3**

```powershell
git status --short
git add fx-trading-platform/apps/web/src/services/apiClient.ts `
  fx-trading-platform/apps/web/src/services/apiClient.test.ts `
  fx-trading-platform/apps/web/src/features/trading-session/tradingSession.ts `
  fx-trading-platform/apps/web/src/features/trading-session/useTradingSession.ts `
  fx-trading-platform/apps/web/src/features/trading-session/tradingSession.test.ts
git commit -m "fix: preserve backend order failures"
```

## Task 4: Terminal Route Consolidation and Explicit Trade Panel State

**Files:**

- Modify: `fx-trading-platform/apps/web/src/app/App.tsx`
- Modify: `fx-trading-platform/apps/web/src/app/App.test.ts`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/TradingPage.tsx`
- Modify: `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx`
- Modify: `fx-trading-platform/apps/web/src/features/trading/components/TradePanel.test.ts`

- [ ] **Step 1: Write route consolidation source test**

Add to `App.test.ts`:

```ts
  it('redirects the legacy /trade route to the primary terminal', () => {
    assert.match(source, /<Route path="\/trade" element=\{<Navigate to="\/trading" replace \/>\} \/>/)
    assert.doesNotMatch(source, /<Route path="\/trade" element=\{<TradePage \/>/)
  })
```

- [ ] **Step 2: Run route test and confirm it fails**

```powershell
cd fx-trading-platform\apps\web
node --test src/app/App.test.ts
```

Expected: fails because `/trade` still renders `TradePage`.

- [ ] **Step 3: Update `App.tsx`**

Remove:

```ts
import { TradePage } from '../pages/trade/TradePage'
```

Change routes:

```tsx
            <Route path="/" element={<Navigate to="/trade" replace />} />
            <Route path="/trade" element={<TradePage />} />
```

to:

```tsx
            <Route path="/" element={<Navigate to="/trading" replace />} />
            <Route path="/trade" element={<Navigate to="/trading" replace />} />
```

- [ ] **Step 4: Write trade panel state source tests**

Add to `TradePanel.test.ts`:

```ts
  it('does not expose mock login as a normal trading path', () => {
    assert.doesNotMatch(tradePanelSource, /模拟登录/)
    assert.doesNotMatch(tradePanelSource, /setIsLoggedIn/)
  })

  it('shows explicit backend and offline preview states', () => {
    assert.match(tradePanelSource, /sessionMode/)
    assert.match(tradePanelSource, /offline-preview/)
    assert.match(tradePanelSource, /本地预览/)
  })
```

- [ ] **Step 5: Run trade panel test and confirm it fails**

```powershell
cd fx-trading-platform\apps\web
node --test src/features/trading/components/TradePanel.test.ts
```

Expected: fails because current component still contains mock login path and does not accept `sessionMode`.

- [ ] **Step 6: Update `TradePanel` props and state logic**

In `TradePanel.tsx`, change props:

```ts
  sessionReady?: boolean
  sessionMode?: 'loading' | 'ready' | 'offline-preview' | 'error'
  orderError?: unknown
```

Remove:

```ts
  const [isLoggedIn, setIsLoggedIn] = useState(false)
```

Replace:

```ts
  const backendReady = Boolean(accountId && sessionReady && onSubmitOrder)
  const canTrade = backendReady || isLoggedIn
```

with:

```ts
  const backendReady = Boolean(accountId && sessionReady && onSubmitOrder)
  const previewReady = sessionMode === 'offline-preview'
  const canTrade = backendReady || previewReady
```

Replace the login toggle button with:

```tsx
          <span className="trade-panel__session-badge">
            {backendReady ? '后端已连接' : previewReady ? '本地预览' : '连接中'}
          </span>
```

Change the not-ready notice in `handleSubmit`:

```ts
      setNotice('请先登录或注册，第一阶段可使用模拟登录')
```

to:

```ts
      setNotice('后端会话尚未就绪，暂不能提交真实订单')
```

Change mock success notice:

```ts
      setNotice(`Mock 下单成功：${mockResponse.orderId}`)
```

to:

```ts
      setNotice(`本地预览订单：${mockResponse.orderId}`)
```

- [ ] **Step 7: Pass session state from `TradingPage`**

In `TradingPage.tsx`, destructure:

```ts
    sessionMode,
    lastOrderError,
```

Pass to both `TradePanel` usages:

```tsx
                sessionMode={sessionMode}
                orderError={lastOrderError}
```

and:

```tsx
          sessionMode={sessionMode}
          orderError={lastOrderError}
```

- [ ] **Step 8: Run focused frontend tests and build**

```powershell
cd fx-trading-platform
npm.cmd run web:build
cd apps\web
node --test src/app/App.test.ts
node --test src/features/trading/components/TradePanel.test.ts
```

Expected: tests and web build pass.

- [ ] **Step 9: Commit Task 4**

```powershell
git status --short
git add fx-trading-platform/apps/web/src/app/App.tsx `
  fx-trading-platform/apps/web/src/app/App.test.ts `
  fx-trading-platform/apps/web/src/pages/trading/TradingPage.tsx `
  fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx `
  fx-trading-platform/apps/web/src/features/trading/components/TradePanel.test.ts
git commit -m "feat: consolidate trading terminal route"
```

## Task 5: Shared Market Stream Client

**Files:**

- Modify: `fx-trading-platform/apps/web/src/services/marketStream.ts`
- Create: `fx-trading-platform/apps/web/src/services/marketStream.test.ts`

- [ ] **Step 1: Write source tests for shared STOMP session**

Create `marketStream.test.ts`:

```ts
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'marketStream.ts'), 'utf8')

describe('market stream shared client', () => {
  it('keeps one active session and subscriptions by topic', () => {
    assert.match(source, /activeMarketStreamSession/)
    assert.match(source, /subscriptions = new Map<string/)
    assert.match(source, /ensureMarketStreamSession/)
  })

  it('does not create a new STOMP Client inside every subscribe call', () => {
    const clientCreations = source.match(/new Client/g) ?? []
    assert.equal(clientCreations.length, 1)
  })
})
```

- [ ] **Step 2: Run test and confirm it fails**

```powershell
cd fx-trading-platform\apps\web
node --test src/services/marketStream.test.ts
```

Expected: fails because current implementation creates a client per topic.

- [ ] **Step 3: Replace `marketStream.ts` with shared session implementation**

Use this implementation:

```ts
import type { Quote } from '../types/trading'
import type { Client as StompClient, StompSubscription } from '@stomp/stompjs'

type MessageHandler<T> = (message: T) => void
type TopicHandler = (message: unknown) => void

type TopicSubscription = {
  handlers: Set<TopicHandler>
  stompSubscription?: StompSubscription
}

type MarketStreamSession = {
  token: string | null
  client?: StompClient
  connected: boolean
  disposed: boolean
  subscriptions: Map<string, TopicSubscription>
}

let activeMarketStreamSession: MarketStreamSession | undefined

export function subscribeQuote(symbol: string, token: string | null, onQuote: (quote: Quote) => void) {
  return subscribeMarketTopic(`/topic/market/quotes/${symbol}`, token, onQuote)
}

export function subscribeOrderBook<T>(symbol: string, token: string | null, onOrderBook: (orderBook: T) => void) {
  return subscribeMarketTopic(`/topic/market/order-book/${symbol}`, token, onOrderBook)
}

export function subscribeRecentTrades<T>(symbol: string, token: string | null, onTrades: (trades: T) => void) {
  return subscribeMarketTopic(`/topic/market/trades/${symbol}`, token, onTrades)
}

function subscribeMarketTopic<T>(topic: string, token: string | null, onMessage: MessageHandler<T>) {
  const session = ensureMarketStreamSession(token)
  const handler: TopicHandler = (message) => onMessage(message as T)
  const subscription = session.subscriptions.get(topic) ?? createTopicSubscription(session, topic)
  subscription.handlers.add(handler)
  subscribeStompTopicWhenReady(session, topic, subscription)

  return () => {
    subscription.handlers.delete(handler)
    if (subscription.handlers.size > 0) return
    subscription.stompSubscription?.unsubscribe()
    session.subscriptions.delete(topic)
    stopSessionWhenIdle(session)
  }
}

function ensureMarketStreamSession(token: string | null): MarketStreamSession {
  if (activeMarketStreamSession && activeMarketStreamSession.token === token && !activeMarketStreamSession.disposed) {
    return activeMarketStreamSession
  }
  activeMarketStreamSession?.client?.deactivate()
  const session: MarketStreamSession = {
    token,
    connected: false,
    disposed: false,
    subscriptions: new Map<string, TopicSubscription>()
  }
  activeMarketStreamSession = session
  startClient(session)
  return session
}

function createTopicSubscription(session: MarketStreamSession, topic: string) {
  const subscription: TopicSubscription = { handlers: new Set<TopicHandler>() }
  session.subscriptions.set(topic, subscription)
  return subscription
}

function startClient(session: MarketStreamSession) {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  void import('@stomp/stompjs').then(({ Client }) => {
    if (session.disposed) return
    const client = new Client({
      brokerURL: `${protocol}://${window.location.host}/ws`,
      connectHeaders: session.token ? { Authorization: `Bearer ${session.token}` } : {},
      reconnectDelay: 5000,
      onConnect: () => {
        session.connected = true
        session.subscriptions.forEach((subscription, topic) => subscribeStompTopicWhenReady(session, topic, subscription))
      },
      onDisconnect: () => {
        session.connected = false
        session.subscriptions.forEach((subscription) => {
          subscription.stompSubscription = undefined
        })
      }
    })
    session.client = client
    client.activate()
  })
}

function subscribeStompTopicWhenReady(session: MarketStreamSession, topic: string, subscription: TopicSubscription) {
  if (!session.connected || subscription.stompSubscription || !session.client) return
  subscription.stompSubscription = session.client.subscribe(topic, (message) => {
    const payload = JSON.parse(message.body) as unknown
    subscription.handlers.forEach((handler) => handler(payload))
  })
}

function stopSessionWhenIdle(session: MarketStreamSession) {
  if (session.subscriptions.size > 0 || activeMarketStreamSession !== session) return
  session.disposed = true
  void session.client?.deactivate()
  activeMarketStreamSession = undefined
}
```

- [ ] **Step 4: Run market stream test and build**

```powershell
cd fx-trading-platform\apps\web
node --test src/services/marketStream.test.ts
cd ..\..
npm.cmd run web:build
```

Expected: market stream test and build pass.

- [ ] **Step 5: Commit Task 5**

```powershell
git status --short
git add fx-trading-platform/apps/web/src/services/marketStream.ts `
  fx-trading-platform/apps/web/src/services/marketStream.test.ts
git commit -m "refactor: share market stream connection"
```

## Task 6: K-Line Current Bar Update

**Files:**

- Modify: `fx-trading-platform/apps/web/src/pages/trading/components/KLineChartPanel.tsx`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/components/KLineChartPanel.test.ts`

- [ ] **Step 1: Add source test for live bar wiring**

Add to `KLineChartPanel.test.ts`:

```ts
  it('subscribes to quote updates to keep the current chart bar moving', () => {
    assert.match(source, /subscribeQuote/)
    assert.match(source, /applyRealtimeQuoteToChart/)
    assert.match(source, /realtimeBarCallbackRef/)
    assert.doesNotMatch(source, /subscribeBar:\s*\(\)\s*=>\s*\{\}/)
    assert.doesNotMatch(source, /updateData\(/)
  })
```

- [ ] **Step 2: Run test and confirm it fails**

```powershell
cd fx-trading-platform\apps\web
node --test src/pages/trading/components/KLineChartPanel.test.ts
```

Expected: fails because `subscribeBar` is empty and quote updates are not wired.

- [ ] **Step 3: Wire quote updates in `KLineChartPanel.tsx`**

Update imports:

```ts
import { subscribeQuote } from '../../../services/marketStream'
import type { TradingCandle, TradingPeriod } from '../tradingModels'
```

Add refs inside the component next to `chartRef`:

```ts
  const realtimeBarCallbackRef = useRef<((data: TradingCandle) => void) | null>(null)
  const latestRealtimeBarRef = useRef<TradingCandle | null>(null)
```

Update the candle loader's success and failure paths:

```ts
            const lastCandle = candles.at(-1) ?? null
            latestRealtimeBarRef.current = lastCandle
            setLastClose(lastCandle?.close ?? null)
            callback(candles)
```

```ts
            latestRealtimeBarRef.current = null
            setLastClose(null)
            callback([])
```

Replace the data loader's empty `subscribeBar` and `unsubscribeBar` implementation:

```ts
      subscribeBar: ({ callback }) => {
        realtimeBarCallbackRef.current = callback
      },
      unsubscribeBar: () => {
        realtimeBarCallbackRef.current = null
      }
```

Add effect inside component after the candle-loading effect:

```ts
  useEffect(() => {
    return subscribeQuote(symbol, null, (quote) => {
      const callback = realtimeBarCallbackRef.current
      const price = Number(quote.mid)
      const nextBar = applyRealtimeQuoteToChart(latestRealtimeBarRef.current, period, quote.timestamp, price)
      if (!callback || !nextBar) return
      latestRealtimeBarRef.current = nextBar
      callback(nextBar)
      setLastClose(nextBar.close)
    })
  }, [period, symbol])
```

Add helper near the bottom:

```ts
const periodMilliseconds: Record<TradingPeriod, number> = {
  time: 1_000,
  '1s': 1_000,
  '1m': 60_000,
  '3m': 3 * 60_000,
  '5m': 5 * 60_000,
  '15m': 15 * 60_000,
  '30m': 30 * 60_000,
  '1h': 60 * 60_000,
  '2h': 2 * 60 * 60_000,
  '4h': 4 * 60 * 60_000,
  '6h': 6 * 60 * 60_000,
  '12h': 12 * 60 * 60_000,
  '1d': 24 * 60 * 60_000,
  '2d': 2 * 24 * 60 * 60_000,
  '3d': 3 * 24 * 60 * 60_000,
  '5d': 5 * 24 * 60 * 60_000,
  '1w': 7 * 24 * 60 * 60_000,
  '1M': 30 * 24 * 60 * 60_000,
  '3M': 90 * 24 * 60 * 60_000
}

function applyRealtimeQuoteToChart(previous: TradingCandle | null, period: TradingPeriod, timestamp: number, price: number) {
  if (!Number.isFinite(price) || price <= 0) return null
  const periodMs = periodMilliseconds[period]
  const bucketTimestamp = Math.floor(timestamp / periodMs) * periodMs
  if (previous && previous.timestamp === bucketTimestamp) {
    return {
      ...previous,
      high: Math.max(previous.high, price),
      low: Math.min(previous.low, price),
      close: price
    }
  }

  const open = previous?.close ?? price
  return {
    timestamp: bucketTimestamp,
    open,
    high: Math.max(open, price),
    low: Math.min(open, price),
    close: price,
    volume: 0
  }
}
```

This uses the KLineCharts loader callback already exposed in `apps/web/src/vite-env.d.ts`; it does not call any chart method outside the local typings.

- [ ] **Step 4: Run test and build**

```powershell
cd fx-trading-platform\apps\web
node --test src/pages/trading/components/KLineChartPanel.test.ts
cd ..\..
npm.cmd run web:build
```

Expected: test and build pass.

- [ ] **Step 5: Commit Task 6**

```powershell
git status --short
git add fx-trading-platform/apps/web/src/pages/trading/components/KLineChartPanel.tsx `
  fx-trading-platform/apps/web/src/pages/trading/components/KLineChartPanel.test.ts
git commit -m "feat: update chart current bar from quotes"
```

## Task 7: Frontend Test Script and Full Verification

**Files:**

- Modify: `fx-trading-platform/apps/web/package.json`
- Modify: `fx-trading-platform/package.json`

- [ ] **Step 1: Add failing script expectation by running current command**

Run:

```powershell
cd fx-trading-platform
npm.cmd run web:test
```

Expected: fails because `web:test` is not defined.

- [ ] **Step 2: Add web test scripts**

In `fx-trading-platform/apps/web/package.json`, add:

```json
"test": "node --test \"src/**/*.test.ts\"",
```

Place it after `dev`:

```json
  "scripts": {
    "prebuild": "node ../../scripts/ensure-klinecharts-dist.mjs",
    "dev": "vite --host 0.0.0.0 --port 5173",
    "test": "node --test \"src/**/*.test.ts\"",
    "build": "tsc -b && vite build",
    "preview": "vite preview --host 0.0.0.0 --port 4173"
  },
```

In `fx-trading-platform/package.json`, add:

```json
"web:test": "npm --workspace apps/web run test",
```

Place it after `web:dev`:

```json
  "scripts": {
    "verify:architecture": "node scripts/verify-architecture.mjs",
    "smoke:backend": "node scripts/smoke-backend.mjs",
    "smoke:admin": "node scripts/smoke-admin.mjs",
    "web:dev": "npm --workspace apps/web run dev",
    "web:test": "npm --workspace apps/web run test",
    "web:build": "npm --workspace apps/web run build",
    "admin:dev": "npm --workspace apps/admin run dev",
    "admin:build": "npm --workspace apps/admin run build"
  },
```

- [ ] **Step 3: Run frontend tests**

```powershell
cd fx-trading-platform
npm.cmd run web:test
```

Expected: all frontend `node:test` tests pass.

- [ ] **Step 4: Run full static/build verification**

```powershell
cd fx-trading-platform
npm.cmd run verify:architecture
npm.cmd run web:build
npm.cmd run admin:build
cd backend
$env:JAVA_HOME='C:\soft\fx-platform-tools\jdk-21'
$env:Path="$env:JAVA_HOME\bin;C:\soft\fx-platform-tools\apache-maven-3.9.9\bin;$env:Path"
mvn test
```

Expected:

- Architecture verification passed.
- Web build passes.
- Admin build passes.
- Backend tests pass.

- [ ] **Step 5: Run smoke if backend dependencies are running**

If PostgreSQL/Redis/backend are running, run:

```powershell
cd fx-trading-platform
npm.cmd run smoke:backend
npm.cmd run smoke:admin
```

Expected: both smoke scripts pass. If backend dependencies are not running, record the blocker and do not claim smoke coverage.

- [ ] **Step 6: Commit Task 7**

```powershell
git status --short
git add fx-trading-platform/apps/web/package.json fx-trading-platform/package.json
git commit -m "test: add web test script"
```

## Final Review

- [ ] Run `git status --short` and confirm only intentional changes remain.
- [ ] Run the full verification set from Task 7 Step 4.
- [ ] If smoke scripts were skipped, document the exact missing service/dependency.
- [ ] Open `/trading` in a browser and verify the terminal route loads.
- [ ] Confirm `/trade` redirects or is clearly not the main entry.
- [ ] Confirm order failures show failure text instead of local success.
- [ ] Confirm no implementation touched root `src/` KLineCharts library files.
