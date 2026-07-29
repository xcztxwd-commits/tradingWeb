# Trading Path Lab Phase 4: End-to-End Verification and Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the complete Admin trading path lab through isolated Docker services, real HTTP replay, deterministic fixed/random scenarios, browser UI, SSE controls, large reports, security boundaries, cleanup, and the required final verification commands.

**Architecture:** A single PowerShell runner owns only the validation Compose project and artifacts it creates. Backend integration tests exercise main-control-plane HTTP to validation-backend HTTP. A Node browser runner uses the real Admin page and backend without mocking trading APIs, while database/report assertions remain read-only or use documented validation controls.

**Tech Stack:** PowerShell, Docker Compose, Maven/Surefire XML, Node.js 24 standard library, Chrome/Edge DevTools Protocol, Spring Boot integration tests, PostgreSQL, Redis.

## Global Constraints

- Preserve the dirty tree and existing P0 scenario/smoke work.
- Do not create a branch, Commit, Push, or PR.
- Do not use `docker compose down -v`, wildcard database deletion, or deletion outside the exact validation project/artifact directory.
- Do not treat skipped Docker tests as passing.
- Do not mock or intercept trading, wallet, ledger, account, risk, market path, or report APIs in the full E2E.
- Do not connect to live market or broker endpoints.
- Every bug discovered gets reproduction steps and a regression test before the fix.
- Failure and cancellation reports must be retained.
- Final output must list exact commands, exit codes, test counts, failures, errors, skipped tests, and blockers.

---

## File Map

### Runner and scripts

- Create `scripts/run-trading-lab-validation.ps1`
- Create `scripts/run-trading-lab-validation.test.mjs`
- Create `scripts/smoke-trading-lab.mjs`
- Create `scripts/smoke-trading-lab.test.mjs`
- Create `scripts/trading-lab-report-check.mjs`
- Modify `package.json`
- Modify `scripts/verify-architecture.mjs`

### Fixed and random fixtures

- Create `docs/testing/trading-lab/fixed-scenarios.json`
- Create `docs/testing/trading-lab/random-seeds.json`
- Create `docs/testing/trading-lab/report-schema.json`
- Create `docs/testing/trading-lab/README.md`

### Backend integration tests

- Create:
  - `backend/src/test/java/com/fxplatform/tradinglab/e2e/TradingLabSpotHttpIT.java`
  - `backend/src/test/java/com/fxplatform/tradinglab/e2e/TradingLabIsolatedPerpetualHttpIT.java`
  - `backend/src/test/java/com/fxplatform/tradinglab/e2e/TradingLabCrossMultiSymbolHttpIT.java`
  - `backend/src/test/java/com/fxplatform/tradinglab/e2e/TradingLabProtectionFundingHttpIT.java`
  - `backend/src/test/java/com/fxplatform/tradinglab/e2e/TradingLabNegativeHttpIT.java`
  - `backend/src/test/java/com/fxplatform/tradinglab/e2e/TradingLabLifecycleHttpIT.java`
  - `backend/src/test/java/com/fxplatform/tradinglab/e2e/TradingLabIsolationHttpIT.java`
  - `backend/src/test/java/com/fxplatform/tradinglab/e2e/TradingLabLargeReportIT.java`

### Final report

- Create `docs/testing/trading-lab/trading-lab-verification-report.md`

---

### Task 1: Lock the one-command runner safety contract

**Interfaces:**

```powershell
powershell -ExecutionPolicy Bypass -File `
  fx-trading-platform/scripts/run-trading-lab-validation.ps1
```

Optional switches:

```text
-SkipBrowser
-KeepValidationRunning
-ArtifactsDirectory <path-inside-fx-trading-platform/.run-logs/trading-lab>
```

- [ ] **Step 1: Write failing runner contract tests**

`run-trading-lab-validation.test.mjs` reads the script and asserts:

- fixed Compose path.
- fixed project name.
- no `down -v`.
- no wildcard DB/volume/container deletion.
- `try/finally`.
- preflight records dirty-tree status without modifying it.
- Surefire XML parsing rejects missing classes, failures, errors, and skips.
- artifacts directory is resolved and checked to remain inside `.run-logs/trading-lab`.
- final cleanup stops only the fixed validation project unless `-KeepValidationRunning`.

- [ ] **Step 2: Run and confirm RED**

```powershell
node --test fx-trading-platform/scripts/run-trading-lab-validation.test.mjs
```

Expected: failure because the runner does not exist.

- [ ] **Step 3: Implement preflight**

Record:

```text
timestamp
git branch
git HEAD
git status --short
Java/Maven/Node/npm versions
docker version
docker compose version
available browser path/version
main Compose status
validation Compose status
```

Use `git -c safe.directory=C:/workspace/tradingWeb` for read-only Git commands; do not mutate global Git config.

- [ ] **Step 4: Implement owned artifact directory**

Create:

```text
fx-trading-platform/.run-logs/trading-lab/<UTC>-<8hex>/
```

Write an ownership marker containing the generated run token. Cleanup only paths whose resolved parent and marker match.

- [ ] **Step 5: Implement command execution and result capture**

For each command record start/end, exit code, stdout/stderr file, test counts, and classification `PASS/FAIL/BLOCKED`.

- [ ] **Step 6: Run runner contract tests**

Expected: all pass.

### Task 2: Define fixed scenario matrix and deterministic random seed set

**Interfaces:**

`fixed-scenarios.json` top-level:

```json
{
  "modelVersion": "trading-lab-v1",
  "scenarios": []
}
```

Each scenario has stable `scenarioId`, `category`, `requiredCapabilities`, `seed`, `initialBalances`, `symbols`, `defaults`, and `timeline`.

- [ ] **Step 1: Write fixture schema tests**

Assert unique IDs, stable ordering, valid config, and required coverage.

- [ ] **Step 2: Add fixed scenarios**

Include at least:

1. Spot repeated buy/partial sell/buy/final sell.
2. Spot weighted cost and break-even.
3. Long repeated add/partial close.
4. Short repeated add/partial close.
5. Add-close-add-close.
6. ONE_WAY reversal.
7. HEDGE LONG/SHORT slots.
8. ISOLATED margin add/remove and leverage change.
9. CROSS multi-symbol shared USDT.
10. MARKET/LIMIT/STOP_LIMIT/OCO/trailing.
11. Post Only/IOC/FOK/Reduce Only.
12. Batch TP/SL.
13. Manual and automatic funding.
14. SIMPLE and DEPTH partial fill.
15. Monotonic single-target path.
16. Realistic multi-waypoint path.
17. Isolated liquidation.
18. Cross full liquidation.
19. Insufficient balance/margin/precision/min-notional negative requests.
20. Pause/resume/cancel.
21. Failure report and cleanup.
22. Two-run contamination check.
23. Permission and Supervisor injection.
24. Large report chunk/print confirmation.

- [ ] **Step 3: Add random seed set**

Store at least 20 named seeds covering legal and negative generation. Tests regenerate each scenario twice and compare canonical hashes.

- [ ] **Step 4: Add JSON Schema for final report**

Require every top-level section from the design and required API trace fields.

- [ ] **Step 5: Run fixture tests**

```powershell
node --test fx-trading-platform/scripts/smoke-trading-lab.test.mjs
```

Expected: fixture contract portion passes.

### Task 3: Build real HTTP integration tests against the isolated stack

**Interfaces:**

Every test follows:

```java
UUID scenarioId = createScenarioAsAdmin(fixture);
UUID runId = startRunAsAdmin(scenarioId, localCalculation);
awaitTerminalRun(runId);
RawReport report = downloadAndValidate(runId);
```

No test method may call a trading service or repository to create an order, trade, or position.

- [ ] **Step 1: Write Spot HTTP RED test**

Assert:

- main Admin endpoint accepted the run.
- `apiTrace` contains main Admin API, validation auth, public order API, wallet, ledger, and account summary calls.
- actual Spot average cost and USDT fee match exact expected strings.
- cleanup succeeded.

- [ ] **Step 2: Write isolated and cross Perpetual RED tests**

Assert exact entry, realized/unrealized PnL, margin, funding, risk rate, liquidation estimate, wallet/account summary, and full close.

- [ ] **Step 3: Write protection/funding/negative RED tests**

Assert TP/SL/trailing/funding system ordering and exact HTTP status/error body for negative actions.

- [ ] **Step 4: Write lifecycle/isolation RED tests**

Assert pause stops processed Tick growth, resume continues, cancel retains a partial report, and run N+1 starts from clean DB/Redis/memory generation.

- [ ] **Step 5: Run and confirm RED**

Start validation Compose, then:

```powershell
cd fx-trading-platform/backend
mvn "-Dtest=TradingLab*HttpIT" test
```

Expected: failures identify missing orchestration or fixture behavior.

- [ ] **Step 6: Fix only through production HTTP paths**

For every defect:

1. preserve failing integration case.
2. add the smallest focused unit regression.
3. implement the fix.
4. rerun focused unit test.
5. rerun the affected HTTP IT.

- [ ] **Step 7: Run all HTTP ITs**

Expected: zero failures, errors, and skips.

### Task 4: Implement the real Admin browser smoke

**Interfaces:**

```powershell
node fx-trading-platform/scripts/smoke-trading-lab.mjs `
  --artifacts=<owned-directory>
```

The script launches/attaches to a real Chrome or Edge through DevTools Protocol and uses actual Admin UI/network.

- [ ] **Step 1: Write source contract tests**

Assert:

- no request interception or trading API mocking.
- browser viewport is at least `1440x900`.
- real Admin login form is used.
- page route is `/trading/lab`.
- script captures console, unhandled rejection, network 4xx/5xx, screenshots, and downloads.
- selectors use roles, labels, and stable `data-testid` for complex controls.

- [ ] **Step 2: Implement environment discovery**

Reuse safe concepts from the current P0 runner without modifying its active code:

- verify backend/Admin port identity.
- launch owned processes only.
- wait on health and page readiness.
- record PIDs and command lines.
- terminate only owned processes in `finally`.

- [ ] **Step 3: Implement core UI journey**

The browser must:

1. log in as an Admin with VIEW/EXECUTE/SUPER_ADMIN.
2. open `/trading/lab`.
3. verify environment status.
4. create a manual Spot + Perpetual multi-symbol scenario.
5. run local Oracle and inspect expected snapshot.
6. save scenario.
7. start real simulation.
8. observe SSE Tick/progress.
9. pause, verify Tick count stable, resume.
10. wait for completion.
11. inspect actual state and raw report metadata.
12. download report.

- [ ] **Step 4: Implement random/chart journey**

Generate from a fixed seed, verify regenerating gives the same hash, rerandomize changes seed, edit the result, and verify:

- Tick/K-line switch.
- bid/ask/last/mark/index toggles.
- multiple symbol tabs.
- event markers.
- zoom/drag/crosshair.
- virtual-time location.
- local vs actual path switch.

- [ ] **Step 5: Implement negative and permission journey**

- VIEW-only Admin cannot execute.
- EXECUTE Admin cannot start/stop environment or confirm >50 MB print.
- negative mode shows warning and records real error without product Pass/Fail.
- ordinary Admin cannot call environment endpoint directly.

- [ ] **Step 6: Implement failure/cancel and print journey**

- cancel a running scenario and verify partial report + cleanup.
- trigger a controlled validation failure and verify failure point/unexecuted steps.
- print a small report.
- for synthetic >50 MB report, verify size/page prompt and SUPER_ADMIN second confirmation without forcing a physical printer.

- [ ] **Step 7: Run browser smoke**

Expected:

- exit 0.
- no unexplained console errors or 5xx.
- screenshots, downloaded JSON, network trace, and summary JSON exist.

### Task 5: Verify runtime isolation and security boundaries

**Interfaces:**

The isolation test must prove absence of connectivity, not merely disabled feature flags.

- [ ] **Step 1: Add validation container network probes**

From `validation-backend`, attempts to connect to:

- public Binance/OKX/Massive hosts.
- main PostgreSQL port.
- main Redis port.
- configured broker/FIX/LP targets.

must fail. Connections to validation PostgreSQL/Redis must succeed.

- [ ] **Step 2: Add main/validation data fingerprint tests**

Before and after a validation run:

- record main DB counts and selected checksums.
- record main Redis key inventory.
- assert no changes caused by validation.

- [ ] **Step 3: Add Supervisor injection runtime tests**

Send payloads containing:

```json
{"action":"start","command":"rm -rf /"}
{"action":"../docker-compose.yml"}
{"action":"restart","service":"postgres"}
```

Expected: 400, no Docker process started for the injected value, audit records rejection.

- [ ] **Step 4: Add report credential scan**

Search downloaded reports for:

```text
Authorization
Bearer
Cookie
password
JWT secret
validation internal token
database password
```

Expected: no raw credential values. The schema may contain sanitized credential metadata field names.

- [ ] **Step 5: Run isolation/security tests**

Expected: all pass.

### Task 6: Verify large report chunking, streaming, retention, and cleanup

- [ ] **Step 1: Generate a long virtual run**

Use enough ticks/API traces to exceed the configured multi-chunk threshold without exceeding local disk limits.

- [ ] **Step 2: Assert database chunks**

Verify:

- multiple chunks in `MARKET_TICKS` and `API_TRACE`.
- monotonic sequence.
- checksum valid.
- bounded uncompressed size per chunk.
- report row exact total bytes.

- [ ] **Step 3: Stream and validate download**

Pipe the HTTP response directly into `trading-lab-report-check.mjs`. Validate JSON incrementally and check the report schema without reading the full file into Node memory.

- [ ] **Step 4: Test retention**

Create expired, active, permanent, failed, and cancelled reports. Enable cleanup only in the dedicated test profile. Assert only expired non-permanent terminal reports are deleted.

- [ ] **Step 5: Test scheduler defaults**

Main test and validation profiles must show cleanup scheduler absent/disabled by default.

- [ ] **Step 6: Run large-report tests**

Expected: all pass with recorded peak memory below the runner threshold.

### Task 7: Extend architecture verification

- [ ] **Step 1: Add failing architecture fixtures**

Temporarily test the verifier against synthetic violations:

- Admin direct validation URL.
- main orchestrator import of `OrderService`.
- Supervisor dynamic command/path.
- validation Compose sharing main volume.
- validation profile live execution or external provider enabled.
- report entity without chunk table.

- [ ] **Step 2: Implement verifier rules**

Keep checks deterministic and path-based. Do not parse generated build output.

- [ ] **Step 3: Run verifier tests and command**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

Expected: pass on the repository and fail on each synthetic violation fixture.

### Task 8: Implement the one-command orchestration and truthful report

**Runner order:**

1. preflight and dirty-tree snapshot.
2. backend unit tests needed before Docker.
3. build validation image.
4. start validation Compose.
5. start Supervisor.
6. start main backend and Admin if not already owned/healthy.
7. explicit validation HTTP ITs.
8. Admin tests/build.
9. Web tests/build.
10. architecture verification.
11. browser smoke.
12. large report and isolation checks.
13. required full backend `mvn test`.
14. report generation.
15. cleanup/stop.

- [ ] **Step 1: Add package aliases**

```json
{
  "validation:run": "powershell -ExecutionPolicy Bypass -File scripts/run-trading-lab-validation.ps1",
  "validation:supervisor:test": "node --test scripts/validation-supervisor.test.mjs",
  "smoke:trading-lab": "node scripts/smoke-trading-lab.mjs"
}
```

- [ ] **Step 2: Parse Surefire XML**

Fail if:

- required test class XML is missing.
- tests = 0.
- failures > 0.
- errors > 0.
- skipped > 0 for required validation classes.

- [ ] **Step 3: Generate Markdown verification report**

Include:

- implementation summary.
- page route and permissions.
- APIs.
- validation start/stop/reset.
- local calculation rules.
- report Schema.
- fixed/random scenarios.
- every command and actual result.
- failed/blocked items and exact reason.
- known limitations.
- absolute paths to primary changed files.
- cleanup receipt.

- [ ] **Step 4: Run the complete runner**

```powershell
powershell -ExecutionPolicy Bypass -File fx-trading-platform/scripts/run-trading-lab-validation.ps1
```

Expected: exit 0 and a final report with no hidden failure or skipped required validation case.

### Task 9: Run the user-required final commands independently

Do not rely only on the wrapper. Run and record these exact commands:

- [ ] **Validation Compose**

```powershell
docker compose -f fx-trading-platform/infra/docker-compose.validation.yml up -d
```

- [ ] **Backend tests**

```powershell
cd fx-trading-platform/backend
mvn test
```

- [ ] **Admin build**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run build"
```

- [ ] **Web build**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web run build"
```

- [ ] **Architecture**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

- [ ] **Additional required tests**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web test"
node --test fx-trading-platform/scripts/validation-supervisor.test.mjs
node fx-trading-platform/scripts/smoke-trading-lab.mjs
```

- [ ] **Stop validation**

```powershell
docker compose -f fx-trading-platform/infra/docker-compose.validation.yml stop
```

Record actual exit codes and output summaries in `docs/testing/trading-lab/trading-lab-verification-report.md`.
