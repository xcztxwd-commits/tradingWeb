# Trading Path Lab Phase 2: Validation Environment and Control Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the isolated validation stack, restricted Supervisor, Admin permissions, persistent run queue, real HTTP orchestrator, SSE stream, and chunked raw report backend for the trading path lab.

**Architecture:** The main backend is the control plane and never calls trading services. A second instance of the same Spring Boot code runs with the `validation` profile against isolated PostgreSQL and Redis. The control plane calls validation public user APIs for all user actions and validation-only internal APIs for reset, balance seed, controlled market, and deterministic system steps.

**Tech Stack:** Java 21, Spring Boot MVC/Security, `RestClient`, MyBatis-Plus, Flyway, PostgreSQL 16, Redis 7, Docker Compose, Node.js standard library Supervisor, JUnit Jupiter, MockWebServer-style local HTTP stubs using JDK `HttpServer` where possible.

## Global Constraints

- Preserve the existing dirty tree; do not overwrite the untracked scenario harness or modified P0 runner.
- Do not create a branch, Commit, Push, or PR.
- All Admin endpoints remain under `/api/admin/**` with backend permission checks.
- Main backend trading-lab code must not import or inject trading, wallet, ledger, funding, liquidation, or market execution services.
- validation must use `execution.mode=demo`, local controlled market data, and scheduler-off behavior.
- Runtime validation containers must not reach Binance, OKX, Massive, broker, FIX, LP, production Redis, or production PostgreSQL.
- Browser never calls validation-backend or Supervisor directly.
- Every reset, run transition, environment action, HTTP hop, failure, and cleanup result must be reportable and auditable.
- Reports must be chunked; no code path may build an unbounded full report in memory.

---

## File Map

### Flyway and permissions

- Create `backend/src/main/resources/db/migration/V60__trading_lab_schema.sql`
- Create `backend/src/main/resources/db/migration/V61__trading_lab_rbac.sql`
- Modify `backend/src/main/java/com/fxplatform/admin/service/AdminPermissionCatalog.java`
- Modify `backend/src/main/java/com/fxplatform/admin/service/AdminAuthorityService.java`
- Modify `backend/src/main/java/com/fxplatform/admin/service/AdminBootstrapService.java`

### Main control plane

- Create `backend/src/main/java/com/fxplatform/tradinglab/admin/TradingLabAdminController.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/admin/TradingLabEnvironmentController.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/admin/TradingLabReportController.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/application/TradingLabScenarioService.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/application/TradingLabRunService.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/application/TradingLabStateMachine.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/application/TradingLabQueueWorker.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/application/TradingLabRunCoordinator.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/application/TradingLabReportWriter.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/application/TradingLabReportStreamer.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/application/TradingLabSseService.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/application/TradingLabAuditService.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/client/ValidationBackendClient.java`
- Create `backend/src/main/java/com/fxplatform/tradinglab/client/ValidationSupervisorClient.java`
- Create focused DTO, entity, enum, and repository files under `com.fxplatform.tradinglab`.

### Validation data plane

- Create `backend/src/main/java/com/fxplatform/validation/security/ValidationInternalAuthenticationFilter.java`
- Create `backend/src/main/java/com/fxplatform/validation/security/ValidationProfileSafetyValidator.java`
- Create `backend/src/main/java/com/fxplatform/validation/controller/ValidationResetController.java`
- Create `backend/src/main/java/com/fxplatform/validation/controller/ValidationAccountSeedController.java`
- Create `backend/src/main/java/com/fxplatform/validation/controller/ValidationMarketController.java`
- Create `backend/src/main/java/com/fxplatform/validation/controller/ValidationSystemStepController.java`
- Create `backend/src/main/java/com/fxplatform/validation/controller/ValidationStateController.java`
- Create `backend/src/main/java/com/fxplatform/validation/service/ValidationResetService.java`
- Create `backend/src/main/java/com/fxplatform/validation/service/ValidationAccountSeedService.java`
- Create `backend/src/main/java/com/fxplatform/validation/service/ValidationRunEngine.java`
- Create `backend/src/main/java/com/fxplatform/validation/service/ValidationLoopbackHttpClient.java`
- Create `backend/src/main/java/com/fxplatform/validation/service/ValidationRunEventStore.java`
- Create `backend/src/main/java/com/fxplatform/validation/service/ValidationMarketClock.java`
- Create `backend/src/main/java/com/fxplatform/validation/service/ValidationMarketState.java`
- Create `backend/src/main/java/com/fxplatform/validation/service/ValidationSystemStepService.java`
- Create `backend/src/main/java/com/fxplatform/validation/service/ValidationDemoExecutionPolicyProvider.java`
- Modify scheduler classes so every scheduled Bean is absent in `validation`.

### Infrastructure and Supervisor

- Create `backend/Dockerfile`
- Create `backend/src/main/resources/application-validation.yml`
- Create `infra/docker-compose.validation.yml`
- Create `scripts/validation-supervisor.mjs`
- Create `scripts/validation-supervisor.test.mjs`
- Modify `package.json` with validation and Supervisor scripts.

### Tests

- Create backend tests under:
  - `backend/src/test/java/com/fxplatform/tradinglab/**`
  - `backend/src/test/java/com/fxplatform/validation/**`
- Create `backend/src/test/resources/application-validation-test.yml`
- Extend `backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java`

---

### Task 1: Create trading-lab schema, RBAC authorities, and audit model

**Interfaces:**

```java
public static final String TRADING_LAB_VIEW = "TRADING_LAB_VIEW";
public static final String TRADING_LAB_EXECUTE = "TRADING_LAB_EXECUTE";
public static final String SUPER_ADMIN = "SUPER_ADMIN";
public static final UUID SUPER_ADMIN_ROLE_ID =
    UUID.fromString("00000000-0000-0000-0000-000000000061");
```

`AdminAuthorityService.authoritiesFor(user)` must include enabled RBAC `role_code` values in addition to menu and button authorities.
The reserved `SUPER_ADMIN` authority is published only by the enabled V61 role with the fixed seed
identity and migration-created `system_managed` provenance marker, never by another same-named
role and never synthesized from an arbitrary menu key or button. Commands that mutate the
authorization graph require both `ROLE_ADMIN` and `SUPER_ADMIN` at the command-service boundary;
department/post CRUD remains available to ordinary Admins.

- [ ] **Step 1: Write failing permission tests**

Cover:

```java
@Test void enabled_role_code_is_exposed_as_authority() {}
@Test void disabled_role_code_is_not_exposed() {}
@Test void view_execute_and_super_admin_are_distinct() {}
@Test void ordinary_admin_cannot_control_validation_environment() {}
```

Use `@WithMockUser(authorities = {"ROLE_ADMIN", "TRADING_LAB_VIEW"})` for Admin URL tests. Task 1
may use a test-only method-security fixture to prove the authority matrix; the real environment
controller and its MVC matrix remain Task 8 work and must not be replaced by a fake success/501
endpoint.

- [ ] **Step 2: Run and confirm RED**

```powershell
cd fx-trading-platform/backend
mvn "-Dtest=AdminAuthorityServiceTest,TradingLabAuthorizationTest" test
```

Expected: failures because the constants, controllers, and role-code authority do not exist.

- [ ] **Step 3: Add `V60__trading_lab_schema.sql`**

Create schema and tables:

```sql
CREATE SCHEMA IF NOT EXISTS trading_lab;

CREATE TABLE trading_lab.scenarios (
  id UUID PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  description TEXT,
  status VARCHAR(32) NOT NULL,
  negative_mode BOOLEAN NOT NULL DEFAULT FALSE,
  seed BIGINT,
  model_version VARCHAR(80) NOT NULL,
  scenario_json JSONB NOT NULL,
  config_snapshot_json JSONB NOT NULL,
  config_snapshot_hash VARCHAR(64) NOT NULL,
  symbol_config_version VARCHAR(120) NOT NULL,
  code_version VARCHAR(160) NOT NULL,
  created_by UUID NOT NULL,
  updated_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0
);
```

Add `runs`, `run_transitions`, `run_events`, `reports`, `report_chunks`, and `audit_events` exactly as defined in the design. Add unique constraints for:

- run transition idempotency key.
- event `(run_id, sequence)`.
- report chunk `(report_id, section, sequence)`.
- one active queue lease using a partial unique index on a fixed singleton key.

`runs.updated_at` is non-null for Task 2 CAS writes. A run receives a unique report at creation,
while nullable `runs.report_id ... ON DELETE SET NULL` lets terminal report deletion and retention
cleanup preserve the run history; report chunks still cascade with the report.

- [ ] **Step 4: Add `V61__trading_lab_rbac.sql`**

Insert idempotently:

- menu permission `TRADING_LAB_VIEW`, path `/trading/lab`, fixed ID
  `00000000-0000-0000-0000-000000000062`.
- role `SUPER_ADMIN`, fixed ID `00000000-0000-0000-0000-000000000061`.
- role-menu row with fixed ID `00000000-0000-0000-0000-000000000063`, whose buttons contain
  `TRADING_LAB_EXECUTE` and `SUPER_ADMIN`.

Add `admin.roles.system_managed BOOLEAN NOT NULL DEFAULT FALSE`; V61 sets it to true only for its
canonical role seed. Before inserting, fail the migration transaction if a legacy role code, menu
permission key, or button value occupies any reserved authority after edge spaces/control
characters are removed. Also reject partial fixed seeds, mismatched canonical attributes, fixed
permission IDs, and competing fixed `(role_id, menu_id)` pairs. Replay is allowed only for the
complete canonical graph with `system_managed = true`; an existing user binding to that trusted
post-migration graph is preserved.

Do not assign every existing Admin to SUPER_ADMIN. Update `AdminBootstrapService` so only the explicit bootstrap Admin receives the seeded SUPER_ADMIN role.

- [ ] **Step 5: Implement entities/repositories and audit service**

`TradingLabAuditService.record(...)` signature:

```java
void record(
    UUID actorId,
    String clientIp,
    UUID requestId,
    UUID scenarioId,
    UUID runId,
    String action,
    String result,
    Map<String, Object> details
);
```

The service writes both `trading_lab.audit_events` and a correlated generic audit record. Never
include credentials in `details`: normalize each nested key and conservatively remove it whenever
it contains an authorization, cookie, password, token, API-key, secret, access-key, auth-code,
credential, private-key, or encryption-key fragment. Cover environment-variable and real platform
spellings such as `MASSIVE_S3_ACCESS_KEY_ID`, `access-key-id`,
`security.config.encryption-key`, `authCodeValue`, `credentialBundle`, and `privateKeyPem`, as well
as `secretAccessKey`, `passwordHash`, `authorizationHeader`, and `clientSecretValue`.

- [ ] **Step 6: Run permission and migration tests**

```powershell
mvn "-Dtest=AdminAuthorityServiceTest,TradingLabAuthorizationTest,V60V61EmptyDatabaseIT" test
```

Expected: all tests pass and the migration IT is not skipped.

### Task 2: Implement persistent state machine and single-run queue

**Interfaces:**

```java
public enum TradingLabRunState {
  DRAFT, VALIDATING, QUEUED, RESETTING, RUNNING, PAUSED,
  CANCELLING, CANCELLED, FAILED, COMPLETED, CLEANING
}

public record RunTransitionCommand(
    UUID runId,
    TradingLabRunState expected,
    TradingLabRunState target,
    String idempotencyKey,
    String reason,
    Instant virtualTime
) {}
```

- [ ] **Step 1: Write failing state-machine tests**

Assert:

- every legal transition from the design.
- illegal skips such as `QUEUED -> COMPLETED`.
- same idempotency key replays the prior transition.
- stale version loses.
- `PAUSED -> RUNNING` and `RUNNING -> CANCELLING`.

- [ ] **Step 2: Write failing queue lease tests**

Use PostgreSQL integration tests proving:

- only one worker acquires the singleton lease.
- expired lease is recoverable.
- queued runs preserve FIFO `queue_sequence`.
- a worker crash leaves the run recoverable from the last persisted transition.

- [ ] **Step 3: Run and confirm RED**

```powershell
mvn "-Dtest=TradingLabStateMachineTest,TradingLabQueueRepositoryIT" test
```

Expected: compilation failures.

- [ ] **Step 4: Implement state machine and repository compare-and-set**

All writes use:

```sql
UPDATE trading_lab.runs
SET state = ?, version = version + 1, updated_at = now()
WHERE id = ? AND state = ? AND version = ?
```

Insert the transition in the same transaction. A duplicate transition key returns the existing result rather than inserting again.

- [ ] **Step 5: Implement `TradingLabQueueWorker`**

Register only when:

```yaml
trading-lab:
  queue:
    enabled: true
```

The main test profile defaults this off. The worker:

1. acquires/renews the singleton lease.
2. claims the oldest QUEUED run.
3. invokes `TradingLabRunCoordinator`.
4. releases the lease in `finally`.

- [ ] **Step 6: Run state and queue tests**

```powershell
mvn "-Dtest=TradingLabStateMachineTest,TradingLabQueueRepositoryIT,TradingLabQueueWorkerTest" test
```

Expected: all pass with zero skips.

### Task 3: Implement chunked, compressed, sanitized reports

**Interfaces:**

```java
public enum TradingLabReportSection {
  METADATA, ACTOR, ENVIRONMENT, SCENARIO, MODEL_VERSION, CONFIG_SNAPSHOT,
  LOCAL_CALCULATION, LIFECYCLE, API_TRACE, MARKET_TICKS, CHECKPOINTS,
  ACTUAL_STATE, ERRORS, CLEANUP
}

public interface TradingLabReportWriter {
  void append(UUID reportId, TradingLabReportSection section, Object value);
  void flush(UUID reportId);
  void complete(UUID reportId);
}
```

- [ ] **Step 1: Write failing report tests**

Cover:

- chunk flush at configured uncompressed byte threshold.
- independent GZIP chunks.
- checksum verification.
- duplicate `(report, section, sequence)` replay.
- header/body credential redaction.
- failed run report remains readable.
- 100 MB synthetic report is streamed with bounded heap growth.

- [ ] **Step 2: Run and confirm RED**

```powershell
mvn "-Dtest=TradingLabReportWriterTest,TradingLabReportStreamerTest,TradingLabCredentialSanitizerTest" test
```

Expected: compilation failures.

- [ ] **Step 3: Implement sanitizer**

Case-insensitively normalize every nested key and remove it when it contains any of these sensitive
fragments (including inside composite names such as `secretAccessKey` or `passwordHash`):

```text
authorization
cookie
set-cookie
password
token
api-key
database-password
internal-secret
```

Replace an authentication object with:

```json
{
  "credentialType": "BEARER",
  "actorId": "...",
  "scopes": ["..."],
  "expiresAt": "...",
  "fingerprint": "sha256:..."
}
```

- [ ] **Step 4: Implement bounded chunk writer**

Keep one bounded buffer per active `(report, section)`. Flush when it reaches `trading-lab.report.chunk-bytes`, default `262144`. Write NDJSON for array sections and canonical JSON for object sections.

- [ ] **Step 5: Implement streaming JSON assembly**

Use `StreamingResponseBody`. Write the fixed top-level key order and stream each section without materializing all chunks. Validate checksum before emitting decompressed bytes.

- [ ] **Step 6: Run report tests**

```powershell
mvn "-Dtest=TradingLabReportWriterTest,TradingLabReportStreamerTest,TradingLabCredentialSanitizerTest" test
```

Expected: all pass. The bounded-memory test records peak retained buffer below twice the configured chunk size per section.

### Task 4: Build validation profile, Docker image, and isolated Compose

**Interfaces:**

- Host backend port: `127.0.0.1:18087`.
- Compose project: `fx-trading-validation`.
- Services: `validation-backend`, `validation-postgres`, `validation-redis`.
- No PostgreSQL or Redis host port is required; only backend health is published.

- [ ] **Step 1: Write failing static isolation tests**

Create tests that read the target files and assert:

- three exact service names.
- separate named volumes.
- `internal: true` network.
- backend port bound to `127.0.0.1`.
- no main database/Redis names.
- `SPRING_PROFILES_ACTIVE=validation`.
- `EXECUTION_MODE=demo`.
- all provider and scheduler flags false.

- [ ] **Step 2: Run and confirm RED**

```powershell
mvn "-Dtest=ValidationComposeContractTest,ValidationProfileSafetyTest" test
```

Expected: failures because the files do not exist.

- [ ] **Step 3: Create `backend/Dockerfile`**

Use a multi-stage build:

```dockerfile
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/fx-platform-backend-0.1.0.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 4: Create `application-validation.yml`**

Set:

- `server.port=8080`.
- validation DB/Redis service names.
- `execution.mode=demo`.
- all existing schedulers false.
- validation queue false.
- market realtime/provider sync/test data/quote broadcast false.
- external provider URLs to `http://127.0.0.1:9`.
- internal API secret from `VALIDATION_INTERNAL_SECRET`.
- startup fail-closed validator enabled.

- [ ] **Step 5: Disable unconditional scheduled Beans**

Add a shared condition such as:

```java
@Profile("!validation")
```

to unconditional scheduler/job components including wallet reconciliation, wallet daily snapshot, and home counters. Keep current property conditions on trading schedulers.

- [ ] **Step 6: Create isolated Compose**

Use:

- distinct DB name/user/password.
- distinct volumes for PostgreSQL and Redis.
- healthchecks for all services.
- only the internal network.
- `validation-backend` depends on healthy DB/Redis.
- no real secrets checked into source; use safe local defaults only in `.env.example`.

- [ ] **Step 7: Run static tests and Compose config**

```powershell
mvn "-Dtest=ValidationComposeContractTest,ValidationProfileSafetyTest" test
docker compose -f fx-trading-platform/infra/docker-compose.validation.yml config
```

Expected: tests pass and Compose config exits 0.

### Task 5: Implement validation internal authentication, reset, seed, clock, and system step

**Interfaces:**

Internal prefix:

```text
/internal/validation/**
```

Required header:

```text
X-Validation-Internal-Token
```

Endpoints:

```text
POST /internal/validation/reset
POST /internal/validation/accounts/{accountId}/seed
POST /internal/validation/market/path
POST /internal/validation/system/step
POST /internal/validation/runs
GET  /internal/validation/runs/{runId}/events
POST /internal/validation/runs/{runId}/pause
POST /internal/validation/runs/{runId}/resume
POST /internal/validation/runs/{runId}/cancel
GET  /internal/validation/state
```

- [ ] **Step 1: Write failing security and reset tests**

Assert:

- internal endpoints are absent outside `validation`.
- missing/wrong token returns 401 JSON.
- valid token succeeds.
- reset increments generation and clears DB, Redis, market state, locks, and virtual clock.
- reset never affects a database whose name does not start with the fixed validation prefix.

- [ ] **Step 2: Run and confirm RED**

```powershell
mvn "-Dtest=ValidationInternalAuthenticationTest,ValidationResetServiceIT" test
```

Expected: compilation failures.

- [ ] **Step 3: Implement profile-only internal filter and controllers**

The filter compares a constant-time SHA-256 digest of the configured secret and supplied header. It must not log the supplied value.

- [ ] **Step 4: Implement reset**

Reset only the validation database. Use a fixed allowlist of application schemas and Flyway migration through a validation-only administrative datasource. Then:

```text
Redis FLUSHDB
ValidationMarketState.clear()
ValidationMarketClock.reset()
ValidationDemoExecutionPolicyProvider.reset()
in-memory lock/queue registry clear
```

Return a receipt with database name, Redis generation, memory generation, startedAt, finishedAt, and per-step result.

- [ ] **Step 5: Implement balance seed**

The seed endpoint:

- accepts initial USDT and Spot asset balances.
- requires a newly registered empty Demo account.
- rejects existing orders, trades, open positions, or non-initial ledger history.
- writes wallet/cash ledger through existing wallet and ledger services in one transaction.
- cannot create positions, orders, or trades.

- [ ] **Step 6: Implement controlled market path and virtual clock**

`ValidationMarketClock` owns virtual `Instant` and tick sequence. `ValidationMarketState` exposes complete bid/ask/last/mark/index bundles to the normal market resolver used by trading and risk. No external provider fallback is allowed.

`ValidationDemoExecutionPolicyProvider` implements the Phase 1 `DemoExecutionPolicyProvider`, is registered only in `validation`, and freezes one run policy from start until reset. The default property-backed provider remains absent when this Bean exists.

- [ ] **Step 7: Implement deterministic system step**

`POST /system/step` is called only by the validation loopback client. It accepts one complete Tick and executes:

1. publish Tick.
2. trailing stop update.
3. pending/depth matching.
4. conditional/protection processing.
5. optional manual/automatic funding.
6. liquidation scan.
7. state snapshot.

Return ordered sub-step results and correlation IDs.

- [ ] **Step 8: Implement autonomous validation run engine**

`POST /internal/validation/runs` starts one run and returns immediately. `ValidationRunEngine`:

1. persists the frozen scenario and starting event sequence in the validation database.
2. calls loopback public `/api/auth/register`.
3. calls loopback internal seed and public account settings APIs.
4. starts the price path once.
5. advances one virtual second at a time according to the speed multiplier.
6. calls loopback public trading/account APIs for due user actions.
7. calls loopback internal `/system/step` for deterministic system work.
8. queries public state APIs after every action.
9. persists every Tick, API trace, checkpoint, state change, and failure as a monotonic validation run event.

The loopback base URL is fixed to the current validation server and never accepts a URL from the scenario. The engine may inject only `ValidationLoopbackHttpClient`, validation state stores, clock, and event store; it must not inject trading, wallet, ledger, risk, funding, liquidation, or market execution services.

- [ ] **Step 9: Implement resumable validation event stream and controls**

`GET /runs/{runId}/events` accepts `afterSequence`. Pause/resume/cancel update durable validation run flags. A backend restart resumes from the last completed virtual-second boundary and reuses the same idempotency keys.

- [ ] **Step 10: Run validation service tests**

```powershell
mvn "-Dtest=ValidationInternalAuthenticationTest,ValidationResetServiceIT,ValidationAccountSeedServiceIT,ValidationMarketClockTest,ValidationSystemStepServiceIT,ValidationRunEngineIT,ValidationRunRecoveryIT" test
```

Expected: all pass with zero skips.

### Task 6: Implement the restricted Validation Supervisor

**Interfaces:**

```http
POST /validation-supervisor
Authorization: Bearer <internal token>
Content-Type: application/json

{"action":"status"}
```

Allowed action values are exactly `status`, `start`, `stop`, `restart`, `health`.

- [ ] **Step 1: Write Node tests first**

`validation-supervisor.test.mjs` must assert:

- unknown action returns 400.
- extra `command`, `path`, and `service` fields return 400.
- shell metacharacters never reach `spawn`.
- missing/wrong bearer returns 401.
- fixed action maps to an exact executable and argument array.
- listen host defaults to `127.0.0.1`.

- [ ] **Step 2: Run and confirm RED**

```powershell
node --test fx-trading-platform/scripts/validation-supervisor.test.mjs
```

Expected: failure because the Supervisor module does not exist.

- [ ] **Step 3: Implement with Node standard library**

Use `node:http`, `node:child_process.spawn`, `node:path`, and `node:crypto`. Set `shell: false`. The Compose path, project name, and service names are constants resolved from the script directory.

Exact mappings:

```js
status  -> docker compose -p fx-trading-validation -f <fixed> ps --format json
start   -> docker compose -p fx-trading-validation -f <fixed> up -d
stop    -> docker compose -p fx-trading-validation -f <fixed> stop
restart -> docker compose -p fx-trading-validation -f <fixed> restart
health  -> HTTP GET http://127.0.0.1:18087/actuator/health
```

- [ ] **Step 4: Run tests**

```powershell
node --test fx-trading-platform/scripts/validation-supervisor.test.mjs
```

Expected: all tests pass.

### Task 7: Implement one-shot validation run client and main run coordinator

**Interfaces:**

```java
public interface ValidationBackendClient {
  ValidationHttpResult reset(UUID runId);
  ValidationHttpResult startRun(UUID runId, ValidationRunStartRequest request);
  List<ValidationRunEvent> eventsAfter(UUID runId, long sequence);
  ValidationHttpResult pause(UUID runId);
  ValidationHttpResult resume(UUID runId);
  ValidationHttpResult cancel(UUID runId);
}
```

- [ ] **Step 1: Write architecture and HTTP sequence RED tests**

Assert:

- `TradingLabRunCoordinator` package has no imports from trading/wallet/ledger/risk/market service packages.
- main backend sends exactly one validation run start request.
- coordinator mirrors validation events monotonically and idempotently.
- pause/resume/cancel are forwarded to validation run controls.
- coordinator reconnects from the last mirrored sequence.

- [ ] **Step 2: Run and confirm RED**

```powershell
mvn "-Dtest=TradingLabArchitectureTest,TradingLabRunCoordinatorTest" test
```

Expected: compilation failures.

- [ ] **Step 3: Implement sanitized HTTP tracing**

Every main-to-validation `RestClient` exchange creates a `ValidationHttpResult` with:

```java
record ValidationHttpResult(
    long sequence,
    String environment,
    String method,
    URI url,
    Instant virtualTime,
    Instant realTime,
    int status,
    Duration duration,
    Map<String, Object> sanitizedRequest,
    Map<String, Object> sanitizedResponse,
    String traceId,
    String correlationId,
    ThrowableInfo exception
) {}
```

Append it immediately to the report; do not wait until run completion.

- [ ] **Step 4: Implement coordinator lifecycle**

Reset validation, start the run once, mirror ordered events, update the main state machine, flush report chunks, and issue final reset after validation reaches a terminal event. Test-user credentials remain entirely inside validation and only sanitized credential metadata reaches the main report.

- [ ] **Step 5: Implement event-stream recovery**

After a main/validation network interruption, query the validation run state and request events after the last mirrored sequence. Do not restart an already accepted validation run. The validation engine itself owns order timeout recovery by `clientOrderId` and requestId before replay.

- [ ] **Step 6: Run coordinator tests**

```powershell
mvn "-Dtest=TradingLabArchitectureTest,TradingLabRunCoordinatorTest,TradingLabEventMirrorRecoveryTest" test
```

Expected: all pass.

### Task 8: Implement Admin APIs, SSE replay, pause/resume/cancel, environment control, and report streaming

**Interfaces:**

Main API:

```text
GET    /api/admin/trading-lab/config
GET    /api/admin/trading-lab/scenarios
POST   /api/admin/trading-lab/scenarios
GET    /api/admin/trading-lab/scenarios/{id}
PUT    /api/admin/trading-lab/scenarios/{id}
DELETE /api/admin/trading-lab/scenarios/{id}
POST   /api/admin/trading-lab/scenarios/{id}/runs
GET    /api/admin/trading-lab/runs/{id}
GET    /api/admin/trading-lab/runs/{id}/events
POST   /api/admin/trading-lab/runs/{id}/pause
POST   /api/admin/trading-lab/runs/{id}/resume
POST   /api/admin/trading-lab/runs/{id}/cancel
GET    /api/admin/trading-lab/environment
POST   /api/admin/trading-lab/environment/{action}
GET    /api/admin/trading-lab/reports/{id}
GET    /api/admin/trading-lab/reports/{id}/download
DELETE /api/admin/trading-lab/reports/{id}
POST   /api/admin/trading-lab/reports/{id}/permanent
GET    /api/admin/trading-lab/reports/{id}/print-info
POST   /api/admin/trading-lab/reports/{id}/print-confirmation
GET    /api/admin/trading-lab/reports/{id}/print
```

- [ ] **Step 1: Write failing MVC tests**

Cover view/execute/super-admin permissions, frozen scenario mutation, invalid state control, SSE Last-Event-ID replay, report deletion, permanent retention, 50 MB print confirmation, and streamed download content type.

- [ ] **Step 2: Run and confirm RED**

```powershell
mvn "-Dtest=TradingLabAdminControllerTest,TradingLabEnvironmentControllerTest,TradingLabReportControllerTest,TradingLabSseServiceTest" test
```

Expected: compilation failures.

- [ ] **Step 3: Implement scenario/config/run APIs**

Use DTOs, not persistence entities. Normalize scenario JSON before hash verification. Reject a run when the client hash differs from the server normalization.

- [ ] **Step 4: Implement SSE persistence and replay**

Persist each event before sending. On connect:

1. authorize run visibility.
2. read `Last-Event-ID`.
3. replay later persisted events in order.
4. attach the live emitter.
5. send heartbeat comments.

- [ ] **Step 5: Implement controls**

Pause/resume/cancel set durable flags with state validation. Environment stop/restart returns conflict while an active run exists.

- [ ] **Step 6: Implement download and print**

- download: `application/json`, `StreamingResponseBody`.
- delete: require execute authority, reject active-run reports, delete chunks transactionally, and audit the deletion.
- permanent: require execute authority and idempotently set or clear the permanent flag.
- print info: exact uncompressed bytes and estimated page count.
- over 50 MB: one-time, actor-bound, report-bound, short-lived confirmation token requiring `SUPER_ADMIN`.
- print: `text/plain; charset=UTF-8`, streaming formatted JSON.

- [ ] **Step 7: Run MVC tests**

```powershell
mvn "-Dtest=TradingLabAdminControllerTest,TradingLabEnvironmentControllerTest,TradingLabReportControllerTest,TradingLabSseServiceTest" test
```

Expected: all pass.

### Task 9: Run the Phase 2 integration gates

- [ ] **Step 1: Start validation Compose**

```powershell
docker compose -f fx-trading-platform/infra/docker-compose.validation.yml up -d --build
```

Expected: all three services become healthy.

- [ ] **Step 2: Run Supervisor and health checks**

Start the Supervisor with a local internal token, then call `status` and `health`. Expected: fixed JSON responses and no visible command shell.

- [ ] **Step 3: Run explicit validation HTTP integration tests**

```powershell
cd fx-trading-platform/backend
mvn "-Dtest=ValidationResetHttpIT,ValidationSpotReplayIT,ValidationIsolatedPerpetualReplayIT,ValidationCrossMultiSymbolReplayIT,ValidationProtectionReplayIT,ValidationFundingReplayIT,ValidationNegativeReplayIT" test
```

Expected: zero failures, errors, and skips.

- [ ] **Step 4: Run backend and architecture**

```powershell
mvn test
cd ..
npm run verify:architecture
```

Expected: pass. Parse Surefire XML and report skipped Docker tests separately.

- [ ] **Step 5: Stop validation stack**

```powershell
docker compose -f fx-trading-platform/infra/docker-compose.validation.yml stop
```

Expected: services stop without deleting main or validation volumes.
