# Contract Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add backend OpenAPI export, generated TypeScript API types, shared error-code contracts, CI gates, and smoke verification for the web and admin apps.

**Architecture:** Keep the existing lightweight `apiClient` wrappers in `apps/web` and `apps/admin`, but move shared API DTO and error-code contracts into `packages/shared-types`. Generate OpenAPI types from the backend `/v3/api-docs` document and make both frontend apps depend on those generated types without introducing a generated runtime client.

**Tech Stack:** Java 21, Spring Boot 3.5, Knife4j/OpenAPI, Node 24, npm workspaces, TypeScript, Vite, `openapi-typescript`, GitHub Actions.

---

## File Structure

- Create `scripts/export-openapi.mjs`: fetches backend `/v3/api-docs`, validates the OpenAPI shape, and writes `artifacts/openapi/backend-openapi.json`.
- Create `scripts/generate-openapi-types.mjs`: runs `openapi-typescript` against the exported document, writes or checks `packages/shared-types/src/generated/openapi.ts`.
- Create `scripts/contract-governance.test.mjs`: static contract tests for package scripts, generated type exports, frontend usage, and CI workflow content.
- Create `packages/shared-types/src/generated/openapi.ts`: generated TypeScript OpenAPI declarations.
- Create `packages/shared-types/src/apiTypes.ts`: small type aliases derived from generated OpenAPI paths/components.
- Create `packages/shared-types/src/errorCodes.ts`: canonical error-code list and frontend friendly message helper.
- Modify `packages/shared-types/src/index.ts`: re-export generated types, API aliases, and error-code helpers.
- Modify `apps/web/package.json` and `apps/admin/package.json`: depend on `@fx-platform/shared-types`.
- Modify `apps/web/src/services/authApi.ts` and `apps/admin/src/types.ts`: consume generated/shared types.
- Modify `apps/web/src/services/apiClient.ts` and `apps/admin/src/services/apiClient.ts`: use shared error-code friendly messages.
- Create `backend/src/main/java/com/fxplatform/common/exception/ErrorCode.java`: canonical backend error-code constants.
- Modify auth/security backend code where existing codes directly map to the new standard list.
- Create `backend/src/test/java/com/fxplatform/common/exception/ErrorCodeContractTest.java`: locks the standard error-code set.
- Modify `.github/workflows/fx-contract.yml`: runs backend OpenAPI export, type generation check, TypeScript checks, builds, and smoke scripts.

## Task 1: RED Tests For Contract Governance

**Files:**
- Create: `scripts/contract-governance.test.mjs`
- Create: `backend/src/test/java/com/fxplatform/common/exception/ErrorCodeContractTest.java`

- [ ] **Step 1: Write failing Node contract test**

```js
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

const text = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
const json = (path) => JSON.parse(text(path))

describe('contract governance workspace wiring', () => {
  it('defines contract export, generation, check, and CI scripts', () => {
    const pkg = json('package.json')
    assert.equal(pkg.scripts['contract:export'], 'node scripts/export-openapi.mjs')
    assert.equal(pkg.scripts['contract:generate'], 'node scripts/generate-openapi-types.mjs')
    assert.equal(pkg.scripts['contract:check'], 'node scripts/generate-openapi-types.mjs --check')
    assert.match(pkg.scripts['contract:ci'], /contract:export/)
    assert.match(pkg.scripts['contract:ci'], /contract:check/)
    assert.match(pkg.scripts['contract:ci'], /web:build/)
    assert.match(pkg.scripts['contract:ci'], /admin:build/)
  })

  it('shares generated OpenAPI types and error-code helpers with both frontends', () => {
    const sharedIndex = text('packages/shared-types/src/index.ts')
    assert.match(sharedIndex, /generated\/openapi/)
    assert.match(sharedIndex, /ApiErrorCode/)
    assert.match(text('apps/web/src/services/authApi.ts'), /@fx-platform\/shared-types/)
    assert.match(text('apps/admin/src/types.ts'), /@fx-platform\/shared-types/)
    assert.match(text('apps/web/src/services/apiClient.ts'), /friendlyApiErrorMessage/)
    assert.match(text('apps/admin/src/services/apiClient.ts'), /friendlyApiErrorMessage/)
  })

  it('has a CI workflow for the full API contract chain', () => {
    assert.equal(existsSync(new URL('../.github/workflows/fx-contract.yml', import.meta.url)), true)
    const workflow = text('../.github/workflows/fx-contract.yml')
    for (const command of [
      'npm run contract:export',
      'npm run contract:check',
      'npm run web:test',
      'npm run web:build',
      'npm run admin:build',
      'npm run smoke:backend'
    ]) {
      assert.match(workflow, new RegExp(command.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
    }
  })
})
```

- [ ] **Step 2: Run Node RED test**

Run: `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform test -- scripts/contract-governance.test.mjs"`

Expected: FAIL because contract scripts, shared exports, and CI workflow are missing.

- [ ] **Step 3: Write failing backend error-code contract test**

```java
package com.fxplatform.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ErrorCodeContractTest {

  @Test
  void exposesStandardApiErrorCodesForBackendAndFrontendContracts() {
    assertThat(ErrorCode.standardCodes()).containsExactlyInAnyOrderElementsOf(Set.of(
        "AUTH_TOKEN_EXPIRED",
        "AUTH_REFRESH_TOKEN_INVALID",
        "USER_DISABLED",
        "ACCOUNT_NOT_FOUND",
        "ACCOUNT_NOT_ACTIVE",
        "SYMBOL_NOT_TRADABLE",
        "QUOTE_STALE",
        "INSUFFICIENT_BALANCE",
        "INSUFFICIENT_MARGIN",
        "ORDER_NOT_CANCELABLE",
        "ORDER_ALREADY_FILLED",
        "DUPLICATE_CLIENT_ORDER_ID",
        "EXECUTION_UNAVAILABLE"));
  }
}
```

- [ ] **Step 4: Run Java RED test**

Run: `cd fx-trading-platform/backend && mvn -Dtest=ErrorCodeContractTest test`

Expected: FAIL to compile because `ErrorCode` does not exist yet.

## Task 2: OpenAPI Export And Type Generation

**Files:**
- Create: `scripts/export-openapi.mjs`
- Create: `scripts/generate-openapi-types.mjs`
- Create: `packages/shared-types/src/generated/openapi.ts`
- Modify: `package.json`
- Modify: `package-lock.json`

- [ ] **Step 1: Add `openapi-typescript` dev dependency**

Run: `cmd.exe /d /s /c "cd fx-trading-platform && npm.cmd install --save-dev openapi-typescript"`

Expected: `package.json` and `package-lock.json` include `openapi-typescript`.

- [ ] **Step 2: Implement export script**

`scripts/export-openapi.mjs` fetches `OPENAPI_SOURCE_URL` or `http://localhost:${OPENAPI_BACKEND_PORT || 8080}/v3/api-docs`, validates `openapi` and `paths`, creates `artifacts/openapi`, and writes `backend-openapi.json`.

- [ ] **Step 3: Implement generation script**

`scripts/generate-openapi-types.mjs` runs local `openapi-typescript` against `artifacts/openapi/backend-openapi.json`. With `--check`, it writes to a temporary file and fails if output differs from `packages/shared-types/src/generated/openapi.ts`.

- [ ] **Step 4: Run script tests**

Run: `cmd.exe /d /s /c "cd fx-trading-platform && node --test scripts/contract-governance.test.mjs"`

Expected: the package-script portion passes after this task.

## Task 3: Shared Frontend Types And Error-Code Mapping

**Files:**
- Create: `packages/shared-types/src/apiTypes.ts`
- Create: `packages/shared-types/src/errorCodes.ts`
- Modify: `packages/shared-types/src/index.ts`
- Modify: `apps/web/package.json`
- Modify: `apps/admin/package.json`
- Modify: `apps/web/src/services/authApi.ts`
- Modify: `apps/admin/src/types.ts`
- Modify: `apps/web/src/services/apiClient.ts`
- Modify: `apps/admin/src/services/apiClient.ts`

- [ ] **Step 1: Add shared package dependency to both frontend apps**

Add `"@fx-platform/shared-types": "0.1.0"` to `apps/web/package.json` and `apps/admin/package.json`.

- [ ] **Step 2: Add shared OpenAPI-derived type aliases**

Create aliases for `AuthResponse`, `SessionStatusResponse`, and `ApiResponse<T>` from generated OpenAPI declarations.

- [ ] **Step 3: Add standard error-code helper**

Export `STANDARD_API_ERROR_CODES`, `ApiErrorCode`, `DEFAULT_API_ERROR_MESSAGES`, and `friendlyApiErrorMessage(code, fallback)`.

- [ ] **Step 4: Use shared types and error messages in web/admin**

Make `apps/web` and `apps/admin` import shared types/helpers instead of hand-duplicating `AuthResponse` and `ApiResponse` where practical.

- [ ] **Step 5: Run frontend self-check**

Run:
- `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"`
- `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"`
- `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run admin:build"`

Expected: all pass, or any unrelated pre-existing failures are recorded with exact output.

## Task 4: Backend Error-Code Contract

**Files:**
- Create: `backend/src/main/java/com/fxplatform/common/exception/ErrorCode.java`
- Modify: `backend/src/main/java/com/fxplatform/auth/service/AuthService.java`
- Modify: `backend/src/main/java/com/fxplatform/common/security/SecurityErrorResponseWriter.java`
- Modify: relevant backend tests that assert renamed codes.

- [ ] **Step 1: Implement `ErrorCode` constants**

Create constants for the 13 standard codes and `standardCodes()`.

- [ ] **Step 2: Switch matching backend errors to standard constants**

Use `ErrorCode.AUTH_REFRESH_TOKEN_INVALID` for invalid refresh token paths, `ErrorCode.USER_DISABLED` for inactive users, `ErrorCode.AUTH_TOKEN_EXPIRED` for security unauthorized responses, and existing matching constants for account, quote, margin, and cancel errors as files are touched.

- [ ] **Step 3: Run backend self-check**

Run: `cd fx-trading-platform/backend && mvn -Dtest=ErrorCodeContractTest,AuthServiceTest,SecurityErrorResponseWriterTest test`

Expected: selected backend tests pass.

## Task 5: CI Contract Chain

**Files:**
- Create: `.github/workflows/fx-contract.yml`
- Modify: `package.json`

- [ ] **Step 1: Add workflow**

Workflow starts PostgreSQL and Redis services, runs backend with CI-safe env, waits for `/actuator/health`, runs `npm run contract:export`, `npm run contract:check`, `npm run web:test`, `npm run web:build`, `npm run admin:build`, and `npm run smoke:backend`.

- [ ] **Step 2: Run static CI contract test**

Run: `cmd.exe /d /s /c "cd fx-trading-platform && node --test scripts/contract-governance.test.mjs"`

Expected: PASS.

## Task 6: Full Chain Verification

**Files:**
- No new files unless verification exposes a defect.

- [ ] **Step 1: Export real backend OpenAPI**

Start backend with local or CI-safe env, wait for health, run `npm run contract:export`.

- [ ] **Step 2: Generate/check types**

Run:
- `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run contract:generate"`
- `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run contract:check"`

Expected: generated types are current and check exits 0.

- [ ] **Step 3: Run full requested chain**

Run:
- `cd fx-trading-platform/backend && mvn test`
- `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"`
- `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"`
- `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run admin:build"`
- `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:backend"`
- Any required runtime smoke that validates backend export -> generated types -> frontend compilation.

Expected: report exact pass/fail output and do not mark complete unless all required evidence is present.
