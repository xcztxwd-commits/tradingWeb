# USDT Spot and Perpetual Demo Continuation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Continue from the verified Task 5 checkpoint and finish the Binance/OKX-inspired Demo Spot and USDT perpetual platform through Task 19 without redoing completed work.

**Architecture:** Keep the existing Spring Boot monolith, PostgreSQL/Flyway/MyBatis-Plus persistence, React Web/Admin applications and Task 4 market-bundle fallback. The backend remains the authority for Demo order, wallet, position, funding and liquidation state; no real matching engine, private venue API, blockchain integration or financial-grade infrastructure is introduced.

**Tech Stack:** Java 21, Spring Boot 3.5.7, MyBatis-Plus, Flyway, PostgreSQL 16, Redis, React/TypeScript, KLineCharts, Maven, npm.

## Global Constraints

- Communicate in Simplified Chinese; keep code, commands, paths, configuration keys and API names in English.
- Follow `C:\workspace\tradingWeb\AGENTS.md`; database changes use Flyway and repositories remain MyBatis-Plus, not JPA.
- Continue in the isolated worktree `C:\workspace\.codex-worktrees\tradingWeb-usdt-spot-perp-p0` on branch `codex/usdt-spot-perp-p0`.
- Do not modify the original checkout `C:\workspace\tradingWeb`.
- Follow Binance and OKX semantics where the product decision is not explicit; choose the smallest coherent Demo implementation and do not ask the user to choose.
- Reuse existing code and V46/V47 schema before adding code. Do not introduce a matching engine, MQ, microservices, event sourcing, distributed transactions, private exchange APIs, real wallets, deposits or withdrawals.
- `demo` and `live` must remain separate. Every trading writer uses `DemoExecutionGuard`; `/api/admin/**` retains backend authorization.
- Market authority is one whole bundle per attempt: Binance public -> OKX public -> `LOCAL_SIMULATED`. Never mix providers field-by-field and never call a provider while holding a trading mutation lock.
- New Demo fills are full-fill-only. Historical `PARTIALLY_FILLED` rows remain readable but no new code may write that state.
- Global mutation lock order remains account -> sorted wallets -> positions -> UUID-sorted orders/group rows -> ledger/event writes.
- Schedulers stay disabled by default, especially in tests.
- Every behavior change starts with a failing test, then minimal implementation, focused GREEN, full regression, review and a small commit.
- PostgreSQL/Testcontainers tests use real PostgreSQL only. If Docker is unavailable, report exact skips and never represent skipped behavior as passed.

---

## 1. Authoritative Sources and Read Order

Read these files before changing code, in this order:

1. `C:\workspace\tradingWeb\AGENTS.md`
2. `fx-trading-platform/docs/superpowers/specs/2026-07-10-usdt-spot-perpetual-demo-scheme-a-design-and-implementation.md`
3. `fx-trading-platform/docs/superpowers/plans/2026-07-11-usdt-spot-perpetual-demo-p0-implementation.md`
4. This handoff document.
5. `.superpowers/sdd/progress.md`
6. `.superpowers/sdd/task-6-brief.md`
7. `.superpowers/sdd/task-5-report.md`
8. `.superpowers/sdd/task-6-report.md`

The specification controls product semantics. The July 11 plan controls Tasks 0-19. This document controls the restart point, environment and session workflow. The Task 6 brief contains the hardened Spot/OCO acceptance matrix.

## 2. Frozen Handoff State

### Repository state

- Worktree: `C:\workspace\.codex-worktrees\tradingWeb-usdt-spot-perp-p0`
- Branch: `codex/usdt-spot-perp-p0`
- Verified implementation baseline: `3886df7b docs: harden spot order task boundary`.
- The handoff document itself may be a later documentation-only commit; do not require `3886df7b` to remain HEAD.
- Tracked worktree state at handoff: clean.
- Task 6 implementation agents were interrupted before production code or RED tests were written.
- `.superpowers/sdd/task-6-report.md` exists as an ignored working ledger with status `IN PROGRESS`; its RED/GREEN sections are still empty.

Verify the checkpoint:

```powershell
cd C:\workspace\.codex-worktrees\tradingWeb-usdt-spot-perp-p0
git branch --show-current
git status --short
git merge-base --is-ancestor 3886df7b HEAD
$LASTEXITCODE
git log -5 --oneline
```

Expected:

```text
codex/usdt-spot-perp-p0
0
```

`git status --short` must print nothing. The five-line log must contain `3886df7b` and retain
`c05b1a97` and `0297e3e2` in its history; a later documentation-only handoff commit is valid.

If tracked changes appear, inspect them before proceeding. Do not discard user or previous-session changes with reset/checkout.

### Completed tasks

- Task 0 complete: isolated environment and baseline.
- Task 1 complete: V46/V47 migration contracts.
- Task 2 complete: enums, entities and DTO contracts.
- Task 3 complete: Demo guard and account-first serialization.
- Task 4 complete: Binance -> OKX -> local whole-bundle fallback, chart interval/settings hardening.
- Task 5 complete: canonical full-fill coordinator, full-fill invariant, fee/source metadata, `REQUIRES_NEW`, transaction-external bundle retry and pending isolation.

Task 5 final commits and evidence:

- `0297e3e2 refactor: unify demo full-fill execution`
- `c05b1a97 fix: enforce pre-write market freshness`
- Focused tests: 113/113 passed.
- Backend default suite: 821/821 passed.
- Independent specification review: Ready Yes, no findings.
- Independent quality review: Ready Yes, no Critical/Important findings.
- `Task5PostgresFullFillIT`: compiled, but 5/5 skipped because Docker was unavailable.

Do not reimplement Tasks 0-5. Fix them only if a new failing test proves a regression required by a later task.

## 3. Local Environment

Use the repository-provided toolchain:

```powershell
$env:JAVA_HOME='C:\workspace\.tools\microsoft-jdk-21\jdk-21.0.11+10'
$env:Path="$env:JAVA_HOME\bin;C:\workspace\.tools\apache-maven-3.9.9\bin;$env:Path"
$mvn='C:\workspace\.tools\apache-maven-3.9.9\bin\mvn.cmd'
$node='C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
$npmCli='E:\software\nodejs\node_modules\npm\bin\npm-cli.js'
```

Known environment limits:

- Docker reports no usable virtualization. PostgreSQL/Testcontainers integration tests compile but skip locally.
- Do not substitute H2 and do not install/enable Windows virtualization features.
- Web production build is blocked by the outer KLineCharts checkout missing `dist/index.esm.js`; do not create a fake dist artifact to hide this.
- The pre-commit hook can fail in the outer repository with `structuredClone is not defined`. Run required verification first; if this is the only hook failure, record it and use `git commit --no-verify` without modifying the hook.

## 4. Per-Task Execution Protocol

Apply this protocol to every remaining task:

- [ ] Read the exact task section in the July 11 plan and the relevant specification sections.
- [ ] Create or refresh `.superpowers/sdd/task-N-brief.md` with exact files, interfaces, invariants and focused commands.
- [ ] Write narrowly scoped RED tests before production changes.
- [ ] Run the focused command and record the expected failure in `.superpowers/sdd/task-N-report.md`.
- [ ] Implement the smallest production change that makes the RED tests pass.
- [ ] Run focused GREEN, affected regression suites, architecture checks and the full relevant application suite sequentially.
- [ ] Run Docker-gated PostgreSQL tests explicitly and record pass/skip truthfully.
- [ ] Run `git diff --check` and task-specific static searches.
- [ ] Commit one task-sized change.
- [ ] Prepare a review package from the task base commit to the task commit.
- [ ] Obtain independent specification and quality reviews; resolve Critical/Important findings before closing the task.
- [ ] Update `.superpowers/sdd/progress.md` only after both reviews accept the task.

Do not run multiple Maven processes concurrently because Surefire reports can be overwritten or miscounted.

## 5. Immediate Restart: Task 6 Spot Closed Loop

Task 6 is the only task currently in progress. Its detailed contract is in `.superpowers/sdd/task-6-brief.md` and the hardened plan section starts at Task 6 in the July 11 plan.

### Task 6A: Public quantity contract and one pricing authority

**Files:**

- Modify: `backend/src/main/java/com/fxplatform/trading/dto/request/CreateOrderRequest.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/OrderCommand.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/OrderCommandFactory.java`
- Create: `backend/src/main/java/com/fxplatform/trading/service/QuantityConversionService.java`
- Modify: `backend/src/main/java/com/fxplatform/execution/FullFillCoordinator.java`
- Modify: `backend/src/main/java/com/fxplatform/risk/service/InstrumentRulesEngine.java`
- Test: `backend/src/test/java/com/fxplatform/trading/dto/request/CreateOrderRequestTest.java`
- Test: `backend/src/test/java/com/fxplatform/trading/service/QuantityConversionServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/execution/FullFillCoordinatorTest.java`
- Test: `backend/src/test/java/com/fxplatform/risk/service/InstrumentRulesEngineTest.java`

**Interfaces:**

- Public Spot MARKET BUY keeps `QuantityUnit.QUOTE` and the USDT budget as `originalQuantity`.
- Internal execution uses step-rounded-down BASE quantity from the same `ExecutableMarketSnapshot` later used for fill.
- MARKET SELL, LIMIT, STOP_MARKET and OCO use BASE.
- P0 Spot rejects CONTRACTS, legacy STOP, attached protections, reduce-only and non-`CASH/BOTH` semantics.
- P0 Spot must not call `QuoteService`; provider instrument rules are preferred with symbol fields as fallback.

- [ ] Write RED tests for the complete type/unit matrix, BTC quantities below 0.01, quote dust, step/min/max/min-notional and zero-mutation rejection.
- [ ] Run the focused tests and record the exact failures in `.superpowers/sdd/task-6-report.md`.
- [ ] Add a side-effect-free pricing/parameter projection owned by the existing full-fill policy; do not duplicate slippage or maker/taker constants.
- [ ] Implement canonical QUOTE-to-BASE conversion and post-conversion rule validation.
- [ ] Run the focused tests until GREEN.

Focused command:

```powershell
& $mvn -f fx-trading-platform/backend/pom.xml "-Dtest=CreateOrderRequestTest,QuantityConversionServiceTest,FullFillCoordinatorTest,InstrumentRulesEngineTest" test
```

### Task 6B: MARKET, LIMIT, STOP_MARKET and holds

**Files:**

- Create: `backend/src/main/java/com/fxplatform/trading/service/OrderHoldCalculator.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/OrderService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/OrderEntityFactory.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/SpotSettlementService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/OrderFillService.java`
- Test: `backend/src/test/java/com/fxplatform/trading/service/OrderHoldCalculatorTest.java`
- Test: `backend/src/test/java/com/fxplatform/trading/service/OrderServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/trading/service/SpotSettlementServiceTest.java`

**Interfaces:**

- MARKET settles immediately without a pending hold.
- LIMIT BUY is immediately taker-filled when `ask <= limitPrice`; LIMIT SELL when `bid >= limitPrice`; otherwise it is PENDING/GTC and later maker-filled.
- STOP_MARKET BUY triggers at `last >= triggerPrice`; SELL at `last <= triggerPrice`; fill uses bid/ask plus canonical market slippage.
- LIMIT BUY hold is BASE quantity times limit price plus worst fee. STOP BUY uses `max(triggerPrice, ask)` plus slippage and worst fee. SELL hold is BASE. Quote holds round upward to scale 8.
- A jump beyond a BUY hold rolls the attempt back and leaves the order PENDING with non-negative balances.

- [ ] Write exact BigDecimal RED tests for hold formulas and all immediate/pending paths.
- [ ] Prove P0 Spot makes no `QuoteService` call and uses one whole bundle per attempt.
- [ ] Implement transaction-external bundle resolution and the existing `TradingTransactionExecutor` mutation boundary.
- [ ] Reuse `FullFillExecutionPath.IMMEDIATE_LIMIT`, `RESTING_LIMIT` and `TRIGGERED_STOP_MARKET` rather than deriving fee role after the fill.
- [ ] Assert `total = available + locked`, one Trade and matching Order/Trade fee metadata on every path.
- [ ] Run the focused tests until GREEN.

### Task 6C: OCO group and pending processor

**Files:**

- Create: `backend/src/main/java/com/fxplatform/trading/dto/request/CreateOcoOrderRequest.java`
- Create: `backend/src/main/java/com/fxplatform/trading/dto/response/OcoOrderGroupResponse.java`
- Create: `backend/src/main/java/com/fxplatform/trading/service/OcoOrderService.java`
- Create: `backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionProcessor.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/repository/OrderRepository.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/controller/TradingController.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/OrderResponseMapper.java`
- Test: `backend/src/test/java/com/fxplatform/trading/service/OcoOrderServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/trading/service/PendingOrderExecutionProcessorTest.java`
- Test: `backend/src/test/java/com/fxplatform/trading/service/PendingOrderExecutionServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/trading/service/OrderResponseMapperTest.java`
- Test: `backend/src/test/java/com/fxplatform/trading/controller/TradingControllerTest.java`

**Interfaces:**

- `POST /api/trading/oco` creates two orders with one `contingencyGroupId`.
- Both legs reference one `holdOwnerOrderId`; only the owner row stores positive hold.
- SELL requires `limit > last > stop`; BUY requires `limit < last < stop`.
- Filling either leg atomically claims it, cancels the peer, consumes/releases the owner hold and writes events.
- Canceling either leg cancels the whole group and releases once. Ordinary PATCH of one OCO leg is rejected.
- Group replay and concurrent replay return one group/two rows/one hold; concurrent leg triggers create at most one Trade.
- `OrderResponseMapper` returns all V46 quantity, fee, trigger and OCO fields.

- [ ] Write RED creation, replay, cancel, fill-vs-cancel, non-owner fill and rollback tests.
- [ ] Add UUID-sorted group row locks after account/wallet/position locks.
- [ ] Keep `PendingOrderExecutionProcessor` free of a second transaction annotation; it invokes the Task 5 executor once per candidate.
- [ ] Implement group settlement and event writes in one transaction.
- [ ] Run focused and wallet reconciliation tests until GREEN.

### Task 6D: PostgreSQL and final Task 6 gate

**Files:**

- Create: `backend/src/test/java/com/fxplatform/trading/service/Task6PostgresSpotIT.java`
- Update: `.superpowers/sdd/task-6-report.md`

- [ ] Add Docker-gated tests for concurrent group creation, dual-leg trigger, fill-vs-cancel, non-owner fill, injected failure rollback, stale account-lock wait and jump beyond hold.
- [ ] Explicitly assert one group, two orders, one hold, at most one Trade, no duplicate release, no negative balances and `total=available+locked`.
- [ ] Run the Task 6 focused suite.
- [ ] Run wallet/ledger reconciliation and architecture tests.
- [ ] Run the full backend suite.
- [ ] Run `Task6PostgresSpotIT` explicitly and record its real result or exact Docker skips.
- [ ] Static-search for P0 `QuoteService`, new `PARTIALLY_FILLED` writes, provider calls under locks and enabled-by-default schedulers.
- [ ] Commit as `feat: complete spot demo orders and OCO`.
- [ ] Obtain independent specification and quality reviews before marking Task 6 complete.

## 6. Remaining Sequence After Task 6

Execute the exact detailed tasks in the July 11 plan in this order. Do not reorder tasks that consume earlier interfaces.

| Task | Deliverable | Required checkpoint |
|---:|---|---|
| 7 | One active DEMO account, 50,000 Spot USDT, 50,000 Perp USDT, reset gate and atomic Spot/Perp transfer | Initialization, reset, transfer idempotency and conservation tests |
| 8 | Perp position mode, margin mode, leverage 1-100 (default 10) and BASE/QUOTE/CONTRACTS normalization | ONE_WAY/HEDGE matrix and settings guards |
| 9 | Perp market/limit orders, Cross/Isolated holds, position accounting and margin adjustment | Exact PnL/margin invariants and reduce-only guards |
| 10 | User-entered partial close, close-all and up to 10 multi-level partial TP/SL orders | Direction, quantity totals, resize/expire and batch isolation |
| 11 | Binance/OKX/fixed funding source fallback, actual settlement and catch-up | Source truth, sign, cycle idempotency and restart catch-up |
| 12 | Simplified Demo liquidation, bankruptcy shortfall, batch actions and Admin force cleanup | Standard Order/Trade/Fee/PnL/Ledger/Event records and authorization |
| 13 | Authenticated account events and frozen OpenAPI contract | No anonymous account topic and generated contract checks |
| 14 | Canonical `/trade/spot/:symbol?` and `/trade/perpetual/:symbol?` Web routes and real order controls | Desktop functional tests and no fake balances/orders |
| 15 | Web real-time account refresh, market source state and mobile equivalence | 1440x900 and 390x844 behavior plus reconnect/fallback tests |
| 16 | Admin account operations, funding configuration, force cleanup/reset and mobile layout | Role, confirmation, reason, requestId and audit tests |
| 17 | PostgreSQL concurrency and backend end-to-end regression | Docker-capable final gate or explicit unresolved environment report |
| 18 | Real browser smoke tests for public, provider failover and offline/local modes | Browser console/network/visual evidence |
| 19 | Final architecture, security and acceptance verification | All P0 loops, static guards, builds and final report |

For each task, use its exact Files, Interfaces, RED, GREEN and commit sections from:

`fx-trading-platform/docs/superpowers/plans/2026-07-11-usdt-spot-perpetual-demo-p0-implementation.md`

## 7. Carried Review Findings and Final Gates

These are accepted non-blocking findings that must remain visible:

- Rerun Task 1/3/5 PostgreSQL tests on a Docker-capable host.
- Extend Task 5 real-database coverage for final freshness failure after ensure-created rows and for missing-row status/trigger early returns.
- Consider a short single-flight whole-bundle cache only if later load tests justify it; never mix providers.
- Strengthen coarse Demo guard source checks if later behavioral tests do not cover every new writer.
- Add a mounted React hook timing test if a suitable test renderer is introduced.
- Web production build remains blocked until the outer KLineCharts package has its legitimate build artifact.

Do not treat these as permission to add infrastructure or redesign completed tasks.

## 8. Standard Verification Commands

Run commands from `C:\workspace\.codex-worktrees\tradingWeb-usdt-spot-perp-p0`.

Backend focused/full:

```powershell
& $mvn -f fx-trading-platform/backend/pom.xml test
```

Web tests and TypeScript/build checks:

```powershell
& $node $npmCli --prefix fx-trading-platform/apps/web test -- --run
& $node $npmCli --prefix fx-trading-platform/apps/web exec -- tsc --noEmit
& $node $npmCli --prefix fx-trading-platform/apps/web run build
```

Admin tests and build:

```powershell
& $node $npmCli --prefix fx-trading-platform/apps/admin test -- --run
& $node $npmCli --prefix fx-trading-platform/apps/admin run build
```

Architecture:

```powershell
& $node $npmCli --prefix fx-trading-platform run verify:architecture
```

Database services, only on a Docker-capable host:

```powershell
docker compose -f fx-trading-platform/infra/docker-compose.yml up -d
```

Before every completion claim:

```powershell
git diff --check
git status --short
```

Report exact test counts, failures, errors and skips. A successful Maven process with skipped Testcontainers tests is not PostgreSQL behavior verification.

## 9. New Session Opening Prompt

Use the following message in the new session:

```text
继续执行 USDT 现货与永续 Demo 项目。工作树是
C:\workspace\.codex-worktrees\tradingWeb-usdt-spot-perp-p0，分支
codex/usdt-spot-perp-p0。

先完整阅读 AGENTS.md、权威规格、2026-07-11 总实施计划，以及
2026-07-12 会话交接计划。不要重做 Task 0-5；从 Task 6 的 RED 测试开始。
严格按 Binance/OKX 最合理且最小化语义执行，不要向我提问。使用 TDD，逐任务验证、
提交并做规格/质量复审。Docker 不可用时如实记录 Testcontainers skip，不使用 H2，
不伪造 Web/KLineCharts 构建产物。
```

## 10. Handoff Definition of Ready

The next session is ready to execute when all of the following are true:

- [ ] The branch is correct and `3886df7b` is an ancestor of HEAD; every later commit is documentation-only or explicitly understood.
- [ ] Tracked worktree changes have been inspected and preserved.
- [ ] The specification, July 11 plan, this handoff and Task 6 brief have been read completely.
- [ ] Task 6 report still shows no unverified RED/GREEN claims.
- [ ] Java 21 and Maven 3.9.9 paths are configured.
- [ ] The worker begins with Task 6A failing tests, not production implementation.
