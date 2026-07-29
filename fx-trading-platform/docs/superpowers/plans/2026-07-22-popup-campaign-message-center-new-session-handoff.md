# 弹窗活动与消息中心实施交接（2026-07-22）

## 1. 新会话启动方式

建议在新会话直接发送：

```text
/goal @ponytail

继续执行：
C:/workspace/tradingWeb/fx-trading-platform/docs/superpowers/plans/2026-07-22-popup-campaign-message-center-new-session-handoff.md

以原实施计划为验收基准：
C:/workspace/tradingWeb/fx-trading-platform/docs/superpowers/plans/2026-07-19-popup-campaign-message-center-new-session-implementation.md
```

新会话开始后必须先完整阅读：

1. `C:/workspace/tradingWeb/AGENTS.md`
2. `C:/Users/Admin/.codex/plugins/cache/ponytail/ponytail/4.8.4/skills/ponytail/SKILL.md`
3. 本交接文档
4. 原实施计划

默认使用简体中文。继续遵循 `ponytail`：优先根因修复、JDK/现有能力和最少状态，不引入依赖或推测性抽象，也不能以删测试、放宽超时或削弱 fail-closed 语义换取绿灯。

## 2. 目标与当前结论

主目标仍是完整实施并验收原计划的 Task 0–12，不是只修 TradingLab。弹窗活动、消息中心、Admin、用户端、迁移、权限、审计、契约与浏览器烟测主体已经完成；最后收口被后端全量测试中的 TradingLab 安全边界问题阻塞。

当前不能宣称完成，原因是：

- 新增安全 RED 已稳定复现。
- `TradingLabChunkedReportWriter.java` 正处于半完成补丁状态，当前预计不能编译。
- 修复完成后尚未重跑最终 backend 全量、frontend/admin/contract 门禁。
- 原实施计划的最终收口记录仍是旧结果。

## 3. 仓库安全边界

- 工作区已有大量用户改动；不要 `git reset --hard`、`git checkout --`、批量删除、stage、commit 或 push。
- 整个 TradingLab 目录在当前外层仓库中是 untracked，`git diff` 不会展示这些文件的增量；必须直接读文件和运行测试。
- 之前一次快照约有 481 个 dirty/untracked 条目、178 个用户已暂存文件；最终交付前重新测量，不要把旧数字当作当前值。
- 不连接真实 broker/FIX/LP；`dev` 继续使用 demo execution。
- 不改变 `/api/admin/**` 的后端权限校验。

## 4. 已完成的业务实施（Task 0–12）

| Task | 已完成内容 | 已有证据（最终仍需重跑相关门禁） |
|---|---|---|
| 0 | 基线、范围冻结、契约清单 | 原计划记录与生成契约 |
| 1 | V63：12 张 engagement 表、21 个索引、3 个函数/触发器、legacy backfill | 真实 PostgreSQL 4/4 |
| 2 | lifecycle、audience、time window、frequency、revision、sanitizer、可注入 `Clock` | 53/53 |
| 3 | atomic claim、queue、lease、幂等 outcome | 69/69；PostgreSQL 3 |
| 4 | 消息、receipt、V64 legacy 只读 | 48/48；PostgreSQL 4 |
| 5 | 11 个权限、V65、Admin 权限与审计 | 46/46；PostgreSQL 2 |
| 6 | scheduler、outbox、STOMP；scheduler 默认关闭 | 17/17，producer 40，PostgreSQL 2 |
| 7 | 资源上传、Tiptap、sanitizer、路由、preview | backend 32，admin 84 |
| 8 | Admin campaign 页面、统计、用户详情、policy | PostgreSQL 3，admin 111 |
| 9 | 用户消息页与 Admin message 页面 | admin 130；5 个视觉场景 |
| 10 | `frontend-core` 与 API 合同 | frontend 231；backend 93；32 paths / 39 operations |
| 11 | `AppShell`、Dialog、web engagement/messages | ui 58，frontend-core 235，web 461 |
| 12 | engagement 浏览器烟测、清理与最终前端门禁 | 14/14 场景、22 张截图、58 个 cleanup item |

完整 engagement 烟测报告：

`C:/workspace/tradingWeb/fx-trading-platform/test-results/engagement-smoke-20260720-2210-full/engagement-smoke-20260720-2210-full-report.json`

Task 9 视觉证据目录：

`C:/workspace/tradingWeb/fx-trading-platform/test-results/task9-member-notices/`

已实现 API 概况：

- Admin：26 operations / 19 paths（campaign 14、message 8、support 4）。
- User：12 operations / 12 paths（popup 6、messages 6）。
- Public：asset GET。
- 总 OpenAPI：32 paths / 39 operations。

权限精确集合：

```text
content:campaign:read
content:campaign:edit
content:campaign:publish
content:campaign:delete
content:campaign:stats
content:campaign:user-detail
content:popup-policy:update
content:message:read
content:message:edit
content:message:send
content:message:delete
```

审计动作共 21 个，前缀为 `ENGAGEMENT_`：

- Campaign 12：`CREATE`、`EDIT`、`PUBLISH`、`SCHEDULE`、`PAUSE`、`RESUME`、`END`、`DELETE`、`RESTORE`、`RESET_DELIVERY`、`TEST_POPUP`、`USER_DETAIL_VIEW`。
- Message 7：`CREATE`、`EDIT`、`SEND`、`SCHEDULE`、`CANCEL_SCHEDULE`、`DELETE`、`RESTORE`。
- 另有 policy update、asset upload。

## 5. 已有验证证据及其有效范围

以下是修复 TradingLab 新边界之前的已有绿灯，不能替代最终重跑：

- `frontend:check`：ui 58、frontend-core 235、web 461、visual contract 17，以及 build/typecheck/boundary/style/architecture/bundle/large-file 全绿。
- Admin：131 tests + build 通过。
- Engagement smoke contract：12 tests 通过。
- `contract:ci`：在隔离的 dev/demo backend 上通过，包含 OpenAPI、web/admin build，以及 demo wallet / asset-ledger / account-summary / Spot order-cancel。
- `contract:check`：当时通过。
- 后端旧全量：2804 tests，0 failures，0 errors，17 skipped；这是扩大安全测试之前的结果。
- TradingLab 真实 PostgreSQL：

  ```powershell
  mvn "-Dapi.version=1.40" "-Dtest=TradingLabReportStorePostgresIT" test
  ```

  结果为 20 tests，0 failures/errors/skips，Flyway 65 migrations。

注意：之后启动过一次 `mvn "-Dapi.version=1.40" test`，因为发现新的安全缺陷而主动终止。该运行不能作为任何最终绿灯证据。

本机 Docker Engine 29.6.1 要求 API >= 1.40，而 Testcontainers 1.21.3 默认回退到 1.32。正确的 JVM 参数是 `-Dapi.version=1.40`；只设置 `DOCKER_API_VERSION` 不足以解决本项目的 docker-java 探测。

## 6. 已稳定复现的安全 RED

新增测试位于：

`C:/workspace/tradingWeb/fx-trading-platform/backend/src/test/java/com/fxplatform/tradinglab/report/TradingLabChunkedReportWriterTest.java`

- `traceRegistryOverflowDiscardsUntrustedBufferedTail`：overflow 后再次 append 必须以 `TRADING_LAB_REPORT_UNSAFE_TRACE` 拒绝。
- `reportRegistryOverflowDiscardsUntrustedBufferedTail`：同上。
- `replacementFenceCarriesDynamicSecretsAcrossTheFreshBuffer`：同 JVM fence replacement 必须保留动态 secret 安全上下文，但丢弃旧 owner 未持久化 tail。
- `recoveredWriterRejectsEvidenceWhenDynamicSecretContextWasLost`：新 writer 遇到 durable evidence 必须 purge + generic terminal + 拒绝新 evidence。
- `recoveredTerminalCallCannotEchoLostDynamicSecretContext`：直接 fail/cancel 恢复不能把未知动态 secret 写入 terminal 文本。
- `knownSecretCannotSpanTwoBufferedAppends`：canary 不能跨两个内存 append 拼接泄露。
- `knownSecretCannotSpanTwoPersistedBatches`：canary 不能跨两个已持久化批次拼接泄露。

性能 RED 位于：

`C:/workspace/tradingWeb/fx-trading-platform/backend/src/test/java/com/fxplatform/tradinglab/report/TradingLabReportCanonicalizerTest.java`

- `boundsFinalCanaryScanForLongCommonPrefixesAndHighRootDegree` 现在包含 256 个 `A<不同字符>-never-matches` secret 和 15 MiB 的 `A` 字符串，在最大 16 MiB 配置下保留 5 秒硬超时。
- 当前热点明确落在 `TradingLabBoundedCredentialSanitizer.SecretMatcher.directTarget` 的非根节点线性边扫描。
- 不要降低输入规模或放宽 5 秒超时。

最近两次权威 RED：

1. writer + canonicalizer 初次组合：41 tests，4 failures，均为 overflow/recovery fail-closed 预期未满足。
2. 精确边界组合：3 tests，3 failures；两个跨 append 测试未抛异常，sanitizer 性能测试 5 秒超时。

精确边界 RED 命令：

```powershell
cd C:/workspace/tradingWeb/fx-trading-platform/backend
mvn "-Dtest=TradingLabChunkedReportWriterTest#knownSecretCannotSpanTwoBufferedAppends+knownSecretCannotSpanTwoPersistedBatches,TradingLabReportCanonicalizerTest#boundsFinalCanaryScanForLongCommonPrefixesAndHighRootDegree" test
```

## 7. 当前代码状态（重要：writer 半完成）

以下已在本次会话实际写入：

1. `TradingLabCanonicalCanaryScanner.escapedBytes` 从 `private` 改为 package-private。
2. `TradingLabReportSecretRegistry` 新增：
   - `maxCanaryPatternBytes`
   - 注册时统计 raw UTF-8 与 JSON escaped pattern 的最大字节数
   - `canaryTailBytes()` 返回最大 pattern 长度减一
3. `TradingLabReportCanonicalizer` 新增 package-private overload：
   - `requireNoCanary(byte[] prefix, byte[] canonical, TradingLabReportSecretRegistry secrets)`
   - 使用一个 scanner 连续扫描 prefix 与当前 canonical
4. `TradingLabChunkedReportWriter` 已部分接线：
   - append 的 fence mismatch 改为调用 `replaceFence`
   - 新 buffer 先调用 `discardDurableEvidence` 探测恢复
   - append/flush 调用 `requireEvidenceOpen`
   - append 在接受后进行 boundary scan 并更新 `acceptedTail`
   - persist 成功后复制 `durableTail`
   - reject/late-secret 路径开始使用 `sealUnsafe` 与 tail rebuild

但是 writer 补丁尚未完成，当前文件引用了还未定义的符号：

- `replaceFence(...)`
- `appendTail(...)`
- `unsafeTrace()`
- `SectionBuffer.acceptedTail`
- `SectionBuffer.durableTail`

此外，`close(...)` 还没有完成 fresh recovery / generic terminal 改造。因此新会话第一步应先完成 writer，而不是直接跑全量测试。

本次并行 agent 因会话权限切换被中断，没有可依赖的完成结果，也不要等待它们。

## 8. 最小修复设计

### 8.1 Writer fail-closed 状态

- `genericTerminal` 是 append-closed 状态；后续 append/flush 必须抛 `TradingLabReportException`，code 为 `TRADING_LAB_REPORT_UNSAFE_TRACE`。
- 若 `discardRequired` 为 true，先把内存状态 seal 为 unsafe，再重试 purge；成功后仍保持 generic terminal，不得继续接受 evidence。
- overflow 必须：`tainted=true`、`genericTerminal=true`、清空全部 buffered/tail/identity、事务性 purge durable evidence。
- `complete` 遇到 tainted/generic report 必须返回 `TRADING_LAB_REPORT_INCOMPLETE`，不能伪装成功。
- `fail` / `cancel` 遇到 generic report 只写固定泛化 terminal：

  ```text
  TRADING_LAB_REPORT_UNSAFE_TRACE
  Trading Lab report contains unsafe trace evidence
  ```

### 8.2 Fresh writer 恢复

当前没有持久化或可安全重建的动态 secret registry。不要新增 schema 或加密存储来扩大设计。

最小安全行为：

- 新 `ReportBuffer` 首次使用时调用 `store.discardEvidence(reportId, fence)`。
- 返回 false：没有 durable evidence，可继续。
- 返回 true：说明安全上下文可能丢失；durable evidence 已清除，buffer 进入 tainted + generic terminal，append 被拒绝。
- 直接 recovered `fail/cancel` 也必须先执行同一探测，然后使用固定 generic terminal，不能 sanitize 后回显未知 secret。
- recovered `complete` 必须拒绝为 incomplete。
- purge 结果不确定时保留 `discardRequired`；下一次动作重试后也要 fail-closed，不可因第二次返回 false 就恢复为可写。

### 8.3 Fence replacement

同 JVM、同 report 的 fence replacement：

- 保留 `secrets`、`tainted`、`genericTerminal`、`discardRequired`。
- 丢弃旧 owner 尚未确认持久化的 appends/events/singleton。
- 正常情况复制每个 section 的 `durableTail` 到新 buffer 的 `acceptedTail` 与 `durableTail`。
- 若旧 buffer `commitUncertain`，保守复制 `acceptedTail`，因为该候选可能已提交；允许假阳性拒绝，不允许漏检。
- 不要重放旧 owner 的 pending batch。

### 8.4 跨 append/flush canary tail

每个 `SectionBuffer` 增加：

```java
private byte[] acceptedTail = new byte[0];
private byte[] durableTail = new byte[0];
```

规则：

- tail 最大长度使用 `report.secrets.canaryTailBytes()`，即已注册 raw/escaped pattern 最大长度减一。
- 每次真正接受 append 前，以一个 scanner 连续扫描 `acceptedTail + canonical`。
- 接受后只保留组合值末尾最多 `maxTailBytes`；无需拼接完整历史。
- persist 成功后 `durableTail = acceptedTail.clone()`，清 buffered append 时不要清 tail。
- purge 全部 evidence 时同时清两个 tail。
- 动态 secret 新注册且 `discardEvidence` 返回 false 时，根据当前 buffered appends 从空 tail 重建；若有 durable evidence，直接 purge + generic terminal。

建议的 `appendTail` 应只分配最终 tail：若当前 canonical 已足够长，直接复制其 suffix；否则复制旧 tail suffix 再接 canonical，避免物化整个历史。

### 8.5 Sanitizer 非根高出度索引

只改 `TradingLabBoundedCredentialSanitizer.SecretMatcher`：

- 保留根节点现有 65,536 direct array。
- 非根节点出度达到 16 时，为该节点建立 JDK `Map<Character, Integer>`（或等价 JDK 原生索引）。
- 低出度继续走链表，避免所有节点都分配 Map。
- 新 edge 必须同步已有索引；节点数组扩容时同步索引容器。
- 不引入依赖，也不抽共享 matcher 层。

`TradingLabCanonicalCanaryScanner` 自己的 byte matcher 已经有非根 16 阈值 dense 256-byte transition，不要重复改坏。

## 9. 推荐执行顺序

1. 完成 `TradingLabChunkedReportWriter` 缺失的方法、字段与 `close(...)` 恢复逻辑。
2. 完成 sanitizer 非根高出度索引。
3. 先编译并跑最小 RED 组合，确保 3/3 转绿。
4. 跑完整 writer/canonicalizer targeted tests。
5. 跑相关 TradingLab 单元测试。
6. 跑真实 PostgreSQL store IT。
7. 独立做一次安全复核，重点检查 overflow retry、fresh recovery、fence replacement、commit uncertainty、跨 flush tail。
8. 只在代码稳定后跑一次 backend 全量。
9. 重跑 frontend/admin/contract 最终门禁。
10. 更新原实施计划的收口记录，重新核对 dirty worktree，再完成 goal。

## 10. 精确验证命令

在 backend：

```powershell
cd C:/workspace/tradingWeb/fx-trading-platform/backend

mvn "-Dtest=TradingLabChunkedReportWriterTest#knownSecretCannotSpanTwoBufferedAppends+knownSecretCannotSpanTwoPersistedBatches,TradingLabReportCanonicalizerTest#boundsFinalCanaryScanForLongCommonPrefixesAndHighRootDegree" test

mvn "-Dtest=TradingLabChunkedReportWriterTest,TradingLabReportCanonicalizerTest,TradingLabCanonicalCanaryScannerTest,TradingLabHttpTraceSanitizerTest,TradingLabHttpTraceBudgetAdversarialTest" test

mvn "-Dapi.version=1.40" "-Dtest=TradingLabReportStorePostgresIT" test

mvn "-Dapi.version=1.40" test
```

在项目根目录：

```powershell
cd C:/workspace/tradingWeb

cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend:check"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run test:engagement-smoke-contract"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run contract:check"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run contract:ci"
```

`contract:ci` 会启动/使用 demo backend 并覆盖 web/admin 构建与 backend smoke；确认没有遗留服务占用端口。若需要重跑浏览器 engagement smoke，使用项目脚本 `smoke:engagement`，并把新报告路径写回原实施计划。

## 11. 原计划需要更新的位置

原计划末尾当前仍记录旧的 TradingLab 修复与旧全量结果（约在 1048–1053 行），严格验收 checkbox 也已提前勾选（约 1095 行）。最终绿灯前不要保留误导性完成声明。

最终更新应包含：

- overflow 后会话闭合；
- fresh recovery fail-closed；
- fence replacement 动态 secret 继承；
- 跨 append/flush canary tail；
- sanitizer 非根高出度索引；
- targeted、PostgreSQL、backend 全量与前端/契约的最新真实结果；
- 浏览器烟测报告路径；
- 工作区未提交用户改动声明。

## 12. 最终交付必须覆盖

完成后最终回复至少给出：

1. 结果摘要。
2. Task 0–12 状态表。
3. 主要文件。
4. Flyway 迁移摘要。
5. Admin/User API 摘要。
6. 权限与审计摘要。
7. 精确测试命令与真实结果。
8. 视觉 QA / smoke 报告路径。
9. dirty/untracked 用户改动未被提交或覆盖的说明。
10. 剩余风险；若无未完成项，明确写明。

所有要求与门禁均有当前证据后，才调用 `update_goal({status: "complete"})`。在此之前保持 goal active。
