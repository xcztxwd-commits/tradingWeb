# Trading Lab 验收总报告

Overall status: **PASS**

Cleanup status: **PASS**

最终权威 runId：`20260729T003900378Z-task9-acaf157e`

证据截止时间：`2026-07-29T02:48:05.3397725Z`

failed / blocked / not-run：**none**

本文件是验收证据封存后的 durable 总结。权威机器证据仍以该 run 的
final report、cleanup receipt 和独立 postflight JSON 为准。

> 证据边界：最终 run 封存时，source、candidate 和 current manifest
> 完全一致，SHA-256 为
> `b67ab52ab74db9ee5c99b36e5dabe924e796de4958102c0e156093629a7803a6`，
> 共 2609 files / 471 directories。本 durable 报告是在封存后新增，
> 因而不伪称其自身已包含在该 candidate manifest 中。

## 1. 结论

Trading Lab 已按交接文档完成本地 Demo 验收：

- 15/15 runner phases 全部 PASS；
- 46 条命令记录与 10 条 Task 9 exact command 全部验证通过；
- 后端完整测试 396 classes / 3372 tests，0 failure、0 error、0 skip；
- Admin 691/691、Web 461/461、Supervisor 27/27；
- validation HTTP replay 7 classes / 10 tests，0 failure、0 error、0 skip；
- 两轮真实浏览器 smoke 均为 7/7 journeys PASS；
- >50 MiB large-report、runtime isolation、凭据扫描、下载和打印调用点证据
  全部 PASS；
- 最终 cleanup PASS，run-owned 进程、端口、账号和固定 validation stack
  均按租约收回；
- 未连接真实 broker、FIX、LP 或 production execution。

Cleanup PASS 不是对失败的掩盖：历史失败轮次继续保留为 FAIL；当前结论只由
最终 fresh retry 的完整证据链支持。

## 2. 证据层级

1. `C:\workspace\tradingWeb\.superpowers\sdd\trading-lab-handoff-2026-07-22.md`
   规定范围、顺序和完成条件，不是运行通过证明。
2. Formal1–7 是真实失败历史，不能被后续成功覆盖。
3. Formal8 是完整 runner 的首个历史 PASS，但不能替代之后新增的 Task 9
   exact-command 验收。
4. Task 9 首跑因 Windows Admin launcher 的 `spawn EINVAL` 为 FAIL，
   尽管 cleanup 为 PASS。
5. Task 9 fresh retry `20260729T003900378Z-task9-acaf157e` 是本报告的
   最终权威证据。

## 3. Formal1–8 历史

| Formal | runId | Overall | Cleanup | 真实阻断点 |
|---|---|---:|---:|---|
| 1 | `20260728T073434937Z-8252cd2a` | FAIL | PASS | browser print 未出现“打印流程已结束”；同时暴露旧 print streaming 与 ownership postflight 问题 |
| 2 | `20260728T092951533Z-fdf4ade3` | FAIL | PASS | small report 未产生 headless print invocation receipt |
| 3 | `20260728T111308832Z-a4cf1a9b` | FAIL | PASS | small report invocation receipt timeout；并暴露 async timeout/旧 bridge 生命周期问题 |
| 4 | `20260728T132916686Z-c9b93c3c` | FAIL | PASS | 16 MiB 报告写入 live `<pre>` 造成 browser backpressure，print receipt timeout |
| 5 | `20260728T152058379Z-e8f5535e` | FAIL | PASS | 两个已完成 print 的 `net::ERR_ABORTED` 未被 receipt 解释，按 fail-closed 判 FAIL |
| 6 | `20260728T170950154Z-5d3119f9` | FAIL | PASS | full backend 3372 tests 中 1 failure |
| 7 | `20260728T190954040Z-11dc8049` | FAIL | PASS | Maven 实际 3372/3372 绿，但 runner 收集空 `RequiredClasses` 时参数绑定失败 |
| 8 | `20260728T211828194Z-0ae05729` | PASS | PASS | 15 phases 完整关闭，无 failed/not-run gate |

Formal8 的 source/candidate manifest：

- SHA-256：
  `8e232a46f65e70b00cec387ee79d0b679b98c4ae9307c2e602987f2966cc2138`
- files：2609
- directories：471
- final report SHA-256：
  `0d664d0eaa1729783004b92c19f7fdd8c7196969e25742547fd4e2cc518f40f2`

## 4. Task 9 首跑失败与最小修复

首跑：

- runId：`20260728T231136646Z-task9-36a72cb1`
- Overall：FAIL
- Cleanup：PASS
- backend pre-Docker、HTTP IT、Admin、Web、architecture、embedded browser
  和 embedded runtime-isolation 均已通过；
- exact no-arg smoke 在启动 owned Admin 时失败：

```text
Error: spawn EINVAL
    at ChildProcess.spawn
    at launchOwnedAdmin (...\scripts\smoke-trading-lab.mjs:633:17)
```

根因是 Windows 下以 `shell:false` 直接执行 `npm.cmd`。`.cmd` 是 command
shim，不是当前 Node `spawn()` 可直接执行的原生 executable。

最小修复位于：

- `scripts/smoke-trading-lab.mjs`
- `scripts/smoke-trading-lab.test.mjs`

修复增加 `resolveOwnedAdminNpmLaunch()`：

- 非 Windows 仍直接使用 `npm`；
- Windows 使用 `process.execPath`；
- 第一参数为相邻的
  `node_modules/npm/bin/npm-cli.js`；
- 保留 `shell:false`，不引入 shell command injection 面；
- npm CLI 不存在时 fail closed；
- `launchOwnedAdmin()` 只消费该 resolver 返回的 executable/arguments。

TDD 与回归边界：

- RED：Windows launcher 行为测试因 helper 不存在而失败；
- focused GREEN：1/1；
- smoke + isolated contracts：63/63；
- runner contracts：67/67；
- 实际 launcher proof：`node.exe npm-cli.js --version` exit 0，
  stdout `11.16.0`；
- fresh retry exact no-arg smoke exit 0，stderr 为空，7/7 journeys PASS；
- 未使用 `shell:true`。

最终源码哈希：

- `smoke-trading-lab.mjs`：
  `9f4cfb0e133f2d97ab59afdc8d2ffe2a718de7327e85e1958f0c6fe0c0684ef0`
- `smoke-trading-lab.test.mjs`：
  `3164688c607b83a12456d61b594c522c0230ca9ecbc96438cc1d7e002fa7bbf8`

## 5. 产品入口与权限矩阵

Desktop-only Admin route：`/trading/lab`

| Authority | 查看场景/Run/Event/Report | 创建与控制 Run | validation 基础设施 | >50 MiB 打印确认 |
|---|---:|---:|---:|---:|
| `TRADING_LAB_VIEW` | 是 | 否 | 否 | 否 |
| `TRADING_LAB_EXECUTE` | 是 | 是 | 否 | 否 |
| `SUPER_ADMIN` | 是 | 是 | 是 | 是 |

约束：

- `/api/admin/**` 的权限由后端强制执行，不能只依赖前端隐藏按钮；
- `TRADING_LAB_EXECUTE` 可 pause/resume/cancel Run，但不能控制
  validation infrastructure；
- >50 MiB 报告只允许 `SUPER_ADMIN` 二次确认后打印；
- mobile guard 明确拒绝移动端使用，不能把 desktop-only 页面写成移动端支持。

## 6. API 与生命周期

Admin API：

- `GET /api/admin/trading-lab/config`
- `GET /api/admin/trading-lab/scenarios`
- `GET /api/admin/trading-lab/scenarios/{id}`
- `POST /api/admin/trading-lab/scenarios/{id}/runs`
- `GET /api/admin/trading-lab/runs/{id}`
- `GET /api/admin/trading-lab/runs/{id}/events`
- `POST /api/admin/trading-lab/runs/{id}/pause`
- `POST /api/admin/trading-lab/runs/{id}/resume`
- `POST /api/admin/trading-lab/runs/{id}/cancel`
- `GET /api/admin/trading-lab/reports/{id}`
- `GET /api/admin/trading-lab/reports/{id}/download`
- `POST /api/admin/trading-lab/reports/{id}/permanent`
- `GET /api/admin/trading-lab/reports/{id}/print-info`
- `POST /api/admin/trading-lab/reports/{id}/print-confirmation`
- `GET /api/admin/trading-lab/reports/{id}/print`
- `GET /api/admin/trading-lab/environment`
- `POST /api/admin/trading-lab/environment/{action}`

Internal validation API：

- `/internal/validation/reset`
- `/internal/validation/state`
- `/internal/validation/accounts/{accountId}/seed`
- `/internal/validation/market/path`
- `/internal/validation/system/step`
- `/internal/validation/runs`

运行边界：

- 固定 Compose project：`fx-trading-validation`
- 固定 services：`validation-backend`、`validation-postgres`、
  `validation-redis`
- validation backend 仅绑定 `127.0.0.1:18087`
- Supervisor 仅监听 loopback，使用固定 token，动作白名单为
  `status/start/stop/restart/health`
- 正向顺序：
  `start -> status -> health -> reset -> run/replay -> owned stop`
- pause/resume/cancel 必须产生真实 durable event、SSE 和 report 证据
- 最终完成使用 `stop`，保留 validation volumes/network；没有使用
  `-KeepValidationRunning` 冒充完成

## 7. 独立 BigInt 定点 Oracle

浏览器 Oracle 不复用后端交易计算器。精确十进制模型为：

```ts
type Decimal = {
  coefficient: bigint
  scale: number
}
```

权威计算规则：

- 输入仅接受 plain signed decimal string 或 `bigint`；
- 使用 `BigInt` coefficient 和显式 scale 做加、减、乘、除、quantize、
  step rounding；
- 支持 `DOWN`、`UP`、`HALF_UP`；
- 禁止 IEEE-754 浮点作为权威交易计算；
- Spot 核对 wallet debit/credit、weighted-average cost、asset ledger、
  account summary；
- perpetual 核对 isolated/cross margin、realized/unrealized PnL、
  protection、funding、liquidation；
- deterministic virtual time 与 canonical scenario hash 用于 fixed/random
  replay 比较；
- Canvas 等显示边界可转换 Number，但 authoritative calculation/report
  保持 decimal string/BigInt 定点语义。

## 8. Durable report 与场景矩阵

每份 Trading Lab report 固定包含 14 个 section：

1. `metadata`
2. `actor`
3. `environment`
4. `scenario`
5. `modelVersion`
6. `configSnapshot`
7. `localCalculation`
8. `lifecycle`
9. `apiTrace`
10. `marketTicks`
11. `checkpoints`
12. `actualState`
13. `errors`
14. `cleanup`

持久化要求：

- chunk sequence 单调；
- checksum、row total bytes、terminal state 可复核；
- large report chunk size 有界；
- download/print 增量读取，不能整体加载到内存；
- `API_TRACE` 在 durable append 前执行有界 credential sanitization；
- expected-negative 4xx 保留真实 status/code/body 的 sanitized trace；
- Authorization、Bearer、Cookie、password、token、DB password 的 raw 或
  escaped 表达均不得泄漏；
- late secret 只能阻止发现后尚未持久化的 tail，不能声称追溯删除历史 chunk。

Fixtures：

- 24 个 fixed categories，从 `phase4-fixed-01-spot-cycle` 开始；
- 12 个 deterministic legal random seeds，从
  `phase4-random-legal-01-spot-balanced` 开始；
- 12 个 deterministic negative random seeds。

真实 HTTP replay 覆盖 Spot、isolated perpetual、cross multi-symbol、
protection、funding、expected-negative、lifecycle 和 isolation。

## 9. 最终 fresh retry 证据

runId：`20260729T003900378Z-task9-acaf157e`

| Gate | 结果 |
|---|---|
| Candidate parity | source/candidate/current 均为 `b67ab52…7803a6`；2609 files / 471 dirs |
| Backend pre-Docker | 3 classes / 35 tests，F0/E0/S0 |
| Supervisor contract | 27/27 |
| Validation HTTP IT | 7 classes / 10 tests，F0/E0/S0，BUILD SUCCESS |
| Admin test | 691/691 |
| Admin build | PASS；仅既有 chunk-size warning |
| Web test | 461/461 |
| Web build | PASS；仅既有 chunk-size warning |
| Architecture | PASS |
| Isolated browser smoke | 7/7 journeys、7 screenshots、status PASS |
| Direct exact no-arg smoke | exit 0、stderr 0、7/7 journeys、7 screenshots |
| Large-report gate | 2 classes / 3 tests，F0/E0/S0，BUILD SUCCESS |
| Large report | 112,924,568 bytes |
| Runtime-isolation contract | 35/35 |
| Exact full backend | 396 classes / 3372 tests，F0/E0/S0，BUILD SUCCESS |
| Runner | 15/15 phases；46 command records；10 exact commands |
| Cleanup | PASS |

两轮浏览器均动态验证：

- mobile guard；
- core UI 与本地 Oracle；
- random replay 与实际 KLineCharts；
- 权限拒绝；
- expected-negative 真实后端错误；
- controlled failure 与 cancel；
- download、large report 与 print；
- COMPLETED、FAILED、CANCELLED 生命周期；
- console、network、backend log、database-backed report 证据；
- runtime-isolation preflight/smoke/postflight。

Direct smoke summary：

- SHA-256：
  `fedd6a7c29b9bdc651877b46c1ab575f719dd24be1b123b05f42349d386c9fc3`
- journeyCount：7
- reportPrintReceiptCount：2
- 唯一 console error 是 permission journey 预期的 403
- `error=null`
- `ownershipError=null`

## 10. Print 证据诚实性

自动化证据明确为：

- `printMode=HEADLESS_PRINT_INVOCATION_INTERCEPT`
- `physicalPrinter=false`
- small report 观察到 1 次 invocation receipt；
- `TRADING_LAB_EXECUTE` 对 >50 MiB report 的操作被拒绝，且
  `printInvocationObserved=false`；
- `SUPER_ADMIN` 二次确认后观察到 large-report invocation receipt；
- small/large print sequence 均为 1；
- UI 完成状态为“打印流程已结束”。

这证明应用抵达受控的最终 `window.print()` handoff，不证明打印机、驱动、
纸张或物理输出。本文不使用“真实物理打印成功”表述。

## 11. Isolation 与 Cleanup

最终 cleanup receipt：

- status：PASS
- completedAtUtc：`2026-07-29T02:46:47.7581071Z`
- owned Admin PID 29400：STOPPED
- owned backend PID 47824：STOPPED
- owned Supervisor PID 39540：STOPPED
- deletedUsers：3
- tombstonedSuper：1
- deletedSessions：15
- remainingOwnedAccess：0
- fixed validation stack：按 leased container IDs 停止
- candidate：RETAINED

最终 live/postflight 状态：

- `8080/18086/18087/18088/5174/5175` listener count 均为 0；
- fixed-stack guard 不存在；
- `validation-backend`
  `ac77d97e4c9cd9a1c422fe995c4d616f0edc1a0fff49c50c80c855db80991ec3`
  为 exited；
- `validation-postgres`
  `5304441876f5b76578869b9991600596bf0127ffd644cac460a1801e31560cb5`
  为 exited；
- `validation-redis`
  `dc206d6d4b912cc92ba1ebd4d0b6384ef70ce27ee20565e0a13379c37f0ff7d1`
  为 exited；
- 三个 observed container ID 与 lease 完全一致；
- main PostgreSQL/Redis 前后 fingerprints 由 runtime-isolation
  postflight 验证一致；
- 未停止或修改用户既有 main PostgreSQL/Redis；
- 未删除、reset、checkout、stash、stage 或 commit 用户 dirty/untracked
  文件。

## 12. 主要实现文件

Runner 与动态验收：

- `scripts/run-trading-lab-validation.ps1`
- `scripts/smoke-trading-lab.mjs`
- `scripts/smoke-trading-lab.test.mjs`
- `scripts/smoke-trading-lab-isolated.mjs`
- `scripts/verify-trading-lab-runtime-isolation.mjs`
- `scripts/validation-supervisor.mjs`
- `infra/docker-compose.validation.yml`

Admin：

- `apps/admin/src/features/tradingLab/TradingLabPage.tsx`
- `apps/admin/src/features/tradingLab/api/tradingLabApi.ts`
- `apps/admin/src/features/tradingLab/api/tradingLabStream.ts`
- `apps/admin/src/features/tradingLab/api/tradingLabReportTransfer.ts`
- `apps/admin/src/features/tradingLab/run/tradingLabRunSessionController.ts`
- `apps/admin/src/features/tradingLab/oracle/decimal.ts`
- `apps/admin/src/features/tradingLab/oracle/spotOracle.ts`
- `apps/admin/src/features/tradingLab/oracle/perpetualOracle.ts`
- `apps/admin/src/features/tradingLab/oracle/riskOracle.ts`

Backend：

- `backend/src/main/java/com/fxplatform/tradinglab/admin/TradingLabAdminController.java`
- `backend/src/main/java/com/fxplatform/tradinglab/admin/TradingLabReportController.java`
- `backend/src/main/java/com/fxplatform/tradinglab/admin/TradingLabEnvironmentController.java`
- `backend/src/main/java/com/fxplatform/tradinglab/admin/report/TradingLabReportAdminService.java`
- `backend/src/main/java/com/fxplatform/tradinglab/report/TradingLabChunkedReportWriter.java`
- `backend/src/main/java/com/fxplatform/validation/controller/ValidationRunController.java`
- `backend/src/main/java/com/fxplatform/validation/controller/ValidationStateController.java`

Fixtures/schema：

- `docs/testing/trading-lab/fixed-scenarios.json`
- `docs/testing/trading-lab/random-seeds.json`
- `docs/testing/trading-lab/report-schema.json`

## 13. Local/Demo 边界

本实现与本报告只覆盖：

- `SPRING_PROFILES_ACTIVE=validation`
- `execution.mode=demo`
- 本地受控行情和 virtual time
- validation 专用 PostgreSQL/Redis
- scheduler、provider、realtime、broadcast 和外部队列默认关闭
- 外部 URL 指向 `http://127.0.0.1:9` fail sink

本报告不证明：

- 真实 broker、FIX、LP 或生产行情连接；
- production broker adapter 的真实执行；
- 真实流动性、真实清算或生产容量；
- 真实打印机物理输出。

## 14. Closure checklist

- [x] fresh retry final report Overall PASS / Cleanup PASS
- [x] 15/15 phases PASS，无 failed/blocked/not-run
- [x] exact no-arg smoke exit 0，Windows `spawn EINVAL` 未复现
- [x] HTTP replay 10/10，无 skip
- [x] Admin 691/691，build PASS
- [x] Web 461/461，build PASS
- [x] architecture PASS
- [x] 两轮 browser 均 7/7 journeys PASS
- [x] small/large print receipts 存在，`physicalPrinter=false`
- [x] large report >50 MiB，multi-chunk/schema/checksum/bytes/credential scan PASS
- [x] runtime-isolation preflight/smoke/postflight 与 contract PASS
- [x] full backend 3372/3372，F/E/S=0
- [x] required Surefire XML 完整，无 dump/dumpstream
- [x] evidence cutoff 时 source/candidate/current manifest 一致
- [x] owned Admin/backend/Supervisor PIDs 均停止
- [x] validation stack 按 leased IDs 停止
- [x] 6 个受控端口均无 listener
- [x] fixed-stack guard 不存在
- [x] 三个 validation containers 均 exited
- [x] remainingOwnedAccess=0
- [x] main DB/Redis fingerprints 前后一致
- [x] cleanup receipt PASS
- [x] dirty/untracked worktree 未被清理、回滚、暂存或提交
- [x] 未把 headless print 写成物理打印
- [x] 未把 local/Demo 写成 production broker/FIX/LP 验收

## 15. 权威证据索引

最终 fresh retry：

- Artifact root：

  `C:\workspace\tradingWeb\fx-trading-platform\.run-logs\trading-lab\20260729T003900378Z-task9-acaf157e`

- Final report：

  `...\trading-lab-verification-report.md`

  SHA-256：
  `1960183d86340a5305e63b79bef14cfaadd7570b441a53f04cec3985727f4910`

- Cleanup receipt：

  `...\cleanup-receipt.json`

  SHA-256：
  `63216778c2229c155c2fd3506c4f69e72402e9a5b765b7867998b527b2fa28ab`

- Independent postflight：

  `...\task9-independent-postflight.json`

  SHA-256：
  `dfa4b1faebaefe3d15157ea0ba29dc59753b089667a7304b6a75de8a06d88b73`

- Browser：

  `...\summary.json`
  `...\runtime-isolation.json`
  `...\network-trace.json`
  `...\console.json`
  `...\downloads.json`

- Direct no-arg smoke：

  `...\direct-smoke-artifacts\summary.json`

- Exact full backend：

  `...\commands\required-full-backend-tests.stdout.log`

- Task 9 first FAIL：

  `C:\workspace\tradingWeb\fx-trading-platform\.run-logs\trading-lab\20260728T231136646Z-task9-36a72cb1`

- Formal8：

  `C:\workspace\tradingWeb\fx-trading-platform\.run-logs\trading-lab\20260728T211828194Z-0ae05729`
