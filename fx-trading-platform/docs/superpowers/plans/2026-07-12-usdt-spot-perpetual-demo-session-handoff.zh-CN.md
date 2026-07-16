# USDT 现货与永续 Demo 项目续作交接计划

> **面向执行代理：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，严格按任务逐项执行。所有步骤使用 `- [ ]` 复选框跟踪。

**目标：** 从已经验证完成的 Task 5 检查点继续，完成 Task 6–19，将项目建设成外观、页面结构和操作流程接近 Binance/OKX 的现货与 USDT 永续合约模拟交易平台，同时避免返工 Task 0–5。

**架构：** 保留现有 Spring Boot 单体后端、PostgreSQL/Flyway/MyBatis-Plus 持久层、React Web/Admin 应用和 Task 4 行情整包降级链。后端继续作为模拟订单、钱包、持仓、资金费和强平状态的唯一权威；不引入真实撮合引擎、交易所私有接口、区块链系统或金融级基础设施。

**技术栈：** Java 21、Spring Boot 3.5.7、MyBatis-Plus、Flyway、PostgreSQL 16、Redis、React、TypeScript、KLineCharts、Maven、npm。

## 全局约束

- 默认使用简体中文沟通；代码、命令、路径、配置键和 API 名称保留英文。
- 遵守 `C:\workspace\tradingWeb\AGENTS.md`。数据库变化必须通过 Flyway；持久层继续使用 MyBatis-Plus，不引入 JPA。
- 继续使用隔离工作树 `C:\workspace\.codex-worktrees\tradingWeb-usdt-spot-perp-p0` 和分支 `codex/usdt-spot-perp-p0`。
- 不得修改原始检出目录 `C:\workspace\tradingWeb`。
- 产品决策未明确时参考 Binance 和 OKX，自动选择最合理、最小且内部一致的 Demo 语义，不再向用户提问。
- 优先复用现有代码和 V46/V47 数据结构。不得引入撮合引擎、消息队列、微服务、事件溯源、分布式事务、交易所私有接口、真实钱包、充值或提现。
- `demo` 与 `live` 必须隔离。所有交易写入口必须经过 `DemoExecutionGuard`；`/api/admin/**` 必须保留后端权限校验。
- 每次交易尝试只能使用一个完整行情包，降级顺序为 Binance public → OKX public → `LOCAL_SIMULATED`。禁止逐字段混用来源，禁止持有交易写锁时请求外部 provider。
- 新 Demo 成交必须一次全量成交。历史 `PARTIALLY_FILLED` 记录可以读取，但任何新代码不得写入该状态。
- 全局写入锁顺序保持：account → 排序后的 wallets → positions → 按 UUID 排序的 orders/group rows → ledger/events。
- scheduler 默认关闭，测试环境尤其如此。
- 所有行为变化必须先写失败测试，再做最小实现，然后依次完成聚焦 GREEN、全量回归、独立复审和小粒度提交。
- PostgreSQL/Testcontainers 测试只能使用真实 PostgreSQL。Docker 不可用时必须记录准确 skip 数量，不得把跳过描述为通过。

---

## 一、权威资料及阅读顺序

新会话开始后，在修改代码前依次完整阅读：

1. `C:\workspace\tradingWeb\AGENTS.md`
2. `fx-trading-platform/docs/superpowers/specs/2026-07-10-usdt-spot-perpetual-demo-scheme-a-design-and-implementation.md`
3. `fx-trading-platform/docs/superpowers/plans/2026-07-11-usdt-spot-perpetual-demo-p0-implementation.md`
4. 本中文交接计划。
5. `.superpowers/sdd/progress.md`
6. `.superpowers/sdd/task-6-brief.md`
7. `.superpowers/sdd/task-5-report.md`
8. `.superpowers/sdd/task-6-report.md`

权威规格决定产品语义；2026-07-11 总计划决定 Task 0–19 的实施范围；本文件决定新会话的恢复点、环境和执行流程；Task 6 brief 保存经过加固的现货/OCO 验收矩阵。

## 二、冻结的交接状态

### 2.1 仓库状态

- 工作树：`C:\workspace\.codex-worktrees\tradingWeb-usdt-spot-perp-p0`
- 分支：`codex/usdt-spot-perp-p0`
- 已验证的实现基线：`3886df7b docs: harden spot order task boundary`
- 英文交接文档提交：`d2dab23f docs: add continuation session handoff plan`
- 中文交接文档可能位于更晚的纯文档提交中，因此不要求 `3886df7b` 或 `d2dab23f` 是 HEAD。
- 创建中文文档前，tracked worktree 为干净状态。
- Task 6 实现代理已经停止，尚未写入生产代码或 RED 测试。
- `.superpowers/sdd/task-6-report.md` 是被 Git 忽略的工作台账，当前状态为 `IN PROGRESS`，RED/GREEN 仍为空。

恢复时执行：

```powershell
cd C:\workspace\.codex-worktrees\tradingWeb-usdt-spot-perp-p0
git branch --show-current
git status --short
git merge-base --is-ancestor 3886df7b HEAD
$LASTEXITCODE
git log -6 --oneline
```

必须满足：

```text
当前分支为 codex/usdt-spot-perp-p0
git status --short 没有输出
ancestor 检查返回 0
提交历史包含 3886df7b、c05b1a97 和 0297e3e2
```

若存在 tracked 修改，必须先逐项检查并保留。禁止使用 reset 或 checkout 丢弃用户或上个会话的更改。

### 2.2 已完成任务

- Task 0：隔离执行环境和基线验证完成。
- Task 1：V46/V47 数据库迁移合同完成。
- Task 2：枚举、实体和 DTO 合同完成。
- Task 3：Demo 防护和 account-first 串行化完成。
- Task 4：Binance → OKX → 本地整包降级及图表周期/设置加固完成。
- Task 5：统一全量成交协调器、全量成交不变量、手续费与来源元数据、`REQUIRES_NEW`、事务外行情重取和 pending 隔离完成。

Task 5 最终证据：

- `0297e3e2 refactor: unify demo full-fill execution`
- `c05b1a97 fix: enforce pre-write market freshness`
- 聚焦测试：113/113 通过。
- 后端默认全量测试：821/821 通过。
- 独立规格复审：Ready Yes，无遗留 finding。
- 独立质量复审：Ready Yes，无 Critical/Important finding。
- `Task5PostgresFullFillIT` 已成功编译，但由于 Docker 不可用，5/5 全部跳过。

不得重新实现 Task 0–5。只有后续任务通过新的失败测试证明存在必要回归修复时，才允许最小修改已完成代码。

## 三、本地工具环境

使用现有工具链：

```powershell
$env:JAVA_HOME='C:\workspace\.tools\microsoft-jdk-21\jdk-21.0.11+10'
$env:Path="$env:JAVA_HOME\bin;C:\workspace\.tools\apache-maven-3.9.9\bin;$env:Path"
$mvn='C:\workspace\.tools\apache-maven-3.9.9\bin\mvn.cmd'
$node='C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
$npmCli='E:\software\nodejs\node_modules\npm\bin\npm-cli.js'
```

已知环境限制：

- 当前 Windows 环境没有可用虚拟化能力，Docker/Testcontainers 无法实际运行 PostgreSQL 测试。
- 不得改用 H2，不得安装或启用 Windows 虚拟化功能。
- Web production build 因外层 KLineCharts 缺少合法生成的 `dist/index.esm.js` 而阻塞；不得创建伪造 dist 文件掩盖问题。
- pre-commit hook 可能因外层仓库 Node 环境报 `structuredClone is not defined`。必须先完成本任务要求的验证；若这是唯一阻塞，可记录后使用 `git commit --no-verify`，不得修改 hook。

## 四、每个任务的统一执行协议

所有剩余任务均执行以下流程：

- [ ] 完整阅读 2026-07-11 总计划中对应 Task 和权威规格相关章节。
- [ ] 创建或更新 `.superpowers/sdd/task-N-brief.md`，写清精确文件、接口、不变量和聚焦命令。
- [ ] 在修改生产代码前写范围明确的 RED 测试。
- [ ] 执行聚焦命令，并把准确失败原因记录到 `.superpowers/sdd/task-N-report.md`。
- [ ] 只实现使 RED 通过所需的最小生产改动。
- [ ] 顺序执行聚焦 GREEN、受影响回归、架构检查和相应应用的全量测试。
- [ ] 显式执行 Docker-gated PostgreSQL 测试，如实记录 pass 或 skip。
- [ ] 执行 `git diff --check` 和任务专属静态搜索。
- [ ] 创建一个任务粒度的提交。
- [ ] 从任务基线提交到任务实现提交生成 review package。
- [ ] 进行独立规格复审和质量复审；关闭 Critical/Important finding 后才能完成任务。
- [ ] 两项复审均接受后，才更新 `.superpowers/sdd/progress.md`。

禁止并行运行多个 Maven 进程，避免 Surefire 报告互相覆盖或测试数量失真。

## 五、新会话立即执行：Task 6 现货闭环

Task 6 是当前唯一进行中的任务。完整合同在 `.superpowers/sdd/task-6-brief.md`；总计划中的 Task 6 已在提交 `3886df7b` 中加固。

### Task 6A：公共数量合同与单一价格权威

**涉及文件：**

- 修改：`backend/src/main/java/com/fxplatform/trading/dto/request/CreateOrderRequest.java`
- 修改：`backend/src/main/java/com/fxplatform/trading/service/OrderCommand.java`
- 修改：`backend/src/main/java/com/fxplatform/trading/service/OrderCommandFactory.java`
- 新建：`backend/src/main/java/com/fxplatform/trading/service/QuantityConversionService.java`
- 修改：`backend/src/main/java/com/fxplatform/execution/FullFillCoordinator.java`
- 修改：`backend/src/main/java/com/fxplatform/risk/service/InstrumentRulesEngine.java`
- 测试：`CreateOrderRequestTest`、`QuantityConversionServiceTest`、`FullFillCoordinatorTest`、`InstrumentRulesEngineTest`

**必须形成的合同：**

- Spot MARKET BUY 的公共 `quantityUnit` 保持 `QUOTE`，USDT budget 保存为 `originalQuantity`。
- 内部执行使用同一个 `ExecutableMarketSnapshot` 换算出的 BASE 数量，并按 step 向下取整。
- MARKET SELL、LIMIT、STOP_MARKET、OCO 使用 BASE 数量。
- P0 Spot 拒绝 CONTRACTS、历史 STOP、attached protections、reduce-only 和非 `CASH/BOTH` 语义。
- P0 Spot 禁止调用 `QuoteService`；交易规则由 provider instrument 优先、symbol 字段 fallback 的聚合规则提供。

- [ ] 为完整 type/unit 矩阵、低于 0.01 的合法 BTC 数量、quote dust、step/min/max/min-notional 和零 mutation 拒绝编写 RED 测试。
- [ ] 运行聚焦测试，把精确失败记录到 `.superpowers/sdd/task-6-report.md`。
- [ ] 在现有 full-fill policy 中增加无副作用的价格/参数投影；不得复制滑点和 maker/taker 常量。
- [ ] 实现 QUOTE → canonical BASE 换算以及换算后的规则校验。
- [ ] 运行聚焦测试直至 GREEN。

聚焦命令：

```powershell
& $mvn -f fx-trading-platform/backend/pom.xml "-Dtest=CreateOrderRequestTest,QuantityConversionServiceTest,FullFillCoordinatorTest,InstrumentRulesEngineTest" test
```

### Task 6B：MARKET、LIMIT、STOP_MARKET 与冻结资金

**涉及文件：**

- 新建：`backend/src/main/java/com/fxplatform/trading/service/OrderHoldCalculator.java`
- 修改：`OrderService.java`、`OrderEntityFactory.java`、`SpotSettlementService.java`、`OrderFillService.java`
- 测试：`OrderHoldCalculatorTest`、`OrderServiceTest`、`SpotSettlementServiceTest`

**必须形成的合同：**

- MARKET 立即成交，不建立 pending hold。
- LIMIT BUY 在 `ask <= limitPrice` 时立即 taker 成交；LIMIT SELL 在 `bid >= limitPrice` 时立即 taker 成交；否则保存为 PENDING/GTC，后续成交为 maker。
- STOP_MARKET BUY 在 `last >= triggerPrice` 时触发；SELL 在 `last <= triggerPrice` 时触发；成交价使用 bid/ask 加统一滑点，绝不能使用 triggerPrice。
- LIMIT BUY hold 为 BASE × limitPrice 加最坏手续费；STOP BUY 使用 `max(triggerPrice, ask)` 加滑点和最坏手续费；SELL hold 为 BASE 数量；quote hold 向上舍入到 8 位。
- BUY 跳价导致 locked 不足时，整笔尝试回滚，订单保持 PENDING，余额不得为负。

- [ ] 为冻结公式和所有立即/等待路径编写精确 BigDecimal RED 测试。
- [ ] 证明 P0 Spot 不调用 `QuoteService`，一次尝试只消费一个完整行情包。
- [ ] 行情解析保持在事务外，写入复用现有 `TradingTransactionExecutor`。
- [ ] 复用 `IMMEDIATE_LIMIT`、`RESTING_LIMIT`、`TRIGGERED_STOP_MARKET`，不得成交后反推流动性角色。
- [ ] 每条路径断言 `total = available + locked`、一条 Trade、Order/Trade 手续费元数据一致。
- [ ] 运行聚焦测试直至 GREEN。

### Task 6C：OCO 分组与 pending processor

**涉及文件：**

- 新建：`CreateOcoOrderRequest.java`
- 新建：`OcoOrderGroupResponse.java`
- 新建：`OcoOrderService.java`
- 新建：`PendingOrderExecutionProcessor.java`
- 修改：`PendingOrderExecutionService.java`、`OrderRepository.java`、`TradingController.java`、`OrderResponseMapper.java`
- 测试：`OcoOrderServiceTest`、`PendingOrderExecutionProcessorTest`、`PendingOrderExecutionServiceTest`、`OrderResponseMapperTest`、`TradingControllerTest`

**必须形成的合同：**

- `POST /api/trading/oco` 创建两个共享一个 `contingencyGroupId` 的订单。
- 两腿引用同一个 `holdOwnerOrderId`，只有 owner 行保存正 hold。
- SELL 必须满足 `limit > last > stop`；BUY 必须满足 `limit < last < stop`。
- 任一腿成交时，原子 claim winner、取消 peer、消费或释放 owner hold，并写双方事件。
- 取消任一腿等价于取消整个组，只释放一次；普通 PATCH 不允许修改 OCO 单腿。
- group replay 与并发 replay 最终只能得到一组、两行、一份 hold；两腿并发触发最多生成一条 Trade。
- `OrderResponseMapper` 必须返回全部 V46 数量、手续费、触发和 OCO 字段。

- [ ] 编写创建、重放、撤单、fill-vs-cancel、非 owner 腿成交和异常回滚 RED 测试。
- [ ] 在 account/wallet/position locks 后增加按 UUID 排序的 group order locks。
- [ ] `PendingOrderExecutionProcessor` 不声明第二套事务；每个候选只调用一次 Task 5 executor。
- [ ] 在一个事务中实现 group 成交、peer 取消、共享 hold 结算和事件写入。
- [ ] 运行聚焦测试和 wallet reconciliation 直至 GREEN。

### Task 6D：PostgreSQL 与 Task 6 最终门禁

**涉及文件：**

- 新建：`backend/src/test/java/com/fxplatform/trading/service/Task6PostgresSpotIT.java`
- 更新：`.superpowers/sdd/task-6-report.md`

- [ ] 增加 Docker-gated 并发 group 创建、双腿触发、fill-vs-cancel、非 owner 腿成交、注入失败回滚、账户锁等待过期和跳价超 hold 测试。
- [ ] 显式断言一组、两订单、一份 hold、最多一条 Trade、无重复 release、无负余额和 `total=available+locked`。
- [ ] 顺序运行 Task 6 聚焦测试。
- [ ] 运行 wallet/ledger reconciliation 和 architecture tests。
- [ ] 运行 backend 全量测试。
- [ ] 显式运行 `Task6PostgresSpotIT`，记录真实结果或准确 Docker skip。
- [ ] 静态检查 P0 `QuoteService`、新增 `PARTIALLY_FILLED` 写入、锁内 provider 请求和默认启用 scheduler。
- [ ] 提交为 `feat: complete spot demo orders and OCO`。
- [ ] 完成独立规格与质量复审后，才能把 Task 6 标记为完成。

## 六、Task 6 之后的顺序

Task 6 完成后，严格按 2026-07-11 总计划依次执行：

| Task | 交付内容 | 关键验收 |
|---:|---|---|
| 7 | 唯一 active DEMO account、Spot 50,000 USDT、Perp 50,000 USDT、reset gate、Spot/Perp 划转 | 初始化幂等、reset、划转守恒和并发余额检查 |
| 8 | 永续持仓模式、保证金模式、1–100× 杠杆（默认 10×）、BASE/QUOTE/CONTRACTS 换算 | ONE_WAY/HEDGE 矩阵和设置切换 guard |
| 9 | 永续市价/限价、Cross/Isolated 占资、仓位记账和保证金调整 | 精确 PnL/保证金不变量和 reduce-only guard |
| 10 | 用户输入部分平仓、一键全平、最多 10 档分批 TP/SL | 方向、数量总和、resize/expire 和批处理隔离 |
| 11 | Binance/OKX/固定资金费率降级、实际结算和补结算 | 来源真实性、正负号、周期幂等和重启补算 |
| 12 | 简化 Demo 强平、穿仓差额、批量操作和 Admin force cleanup | 标准 Order/Trade/Fee/PnL/Ledger/Event 与权限 |
| 13 | 认证账户事件和冻结 OpenAPI 合同 | 禁止匿名账户 topic，生成合同通过 |
| 14 | `/trade/spot/:symbol?`、`/trade/perpetual/:symbol?` 及真实下单控件 | 桌面端功能测试，无假余额/假订单 |
| 15 | Web 实时账户刷新、行情来源状态和移动端等价 | 1440×900、390×844、重连和 fallback |
| 16 | Admin 账户操作、资金费配置、force cleanup/reset 和移动布局 | 权限、二次确认、reason、requestId 和 audit |
| 17 | PostgreSQL 并发与后端端到端回归 | Docker-capable 门禁或明确环境未验证报告 |
| 18 | public、provider failover、offline/local 的真实浏览器 smoke | console、network、视觉证据 |
| 19 | 最终架构、安全和验收验证 | 全部 P0 闭环、静态 guard、构建和最终报告 |

每个 Task 的精确 Files、Interfaces、RED、GREEN 和提交要求以此文件为准：

`fx-trading-platform/docs/superpowers/plans/2026-07-11-usdt-spot-perpetual-demo-p0-implementation.md`

## 七、保留到最终验收的发现

- 在 Docker 可用环境重新执行 Task 1、Task 3、Task 5 的 PostgreSQL tests。
- 为 Task 5 增加真实数据库覆盖：ensure 创建缺失行后 final freshness 失败回滚，以及缺失行情况下 status/trigger 提前返回不创建数据。
- 只有后续负载证明确有必要时，才考虑短周期 whole-bundle single-flight cache；不得混合 provider。
- 若后续行为测试未覆盖每个新写入口，应加强粗粒度 Demo guard source check。
- 若引入合适 React test renderer，补充 mounted hook timing test。
- 外层 KLineCharts 生成合法构建产物前，Web production build 阻塞仍然存在。

这些均不是引入新基础设施或重构已完成任务的授权。

## 八、标准验证命令

在工作树根目录执行。

后端：

```powershell
& $mvn -f fx-trading-platform/backend/pom.xml test
```

Web：

```powershell
& $node $npmCli --prefix fx-trading-platform/apps/web test -- --run
& $node $npmCli --prefix fx-trading-platform/apps/web exec -- tsc --noEmit
& $node $npmCli --prefix fx-trading-platform/apps/web run build
```

Admin：

```powershell
& $node $npmCli --prefix fx-trading-platform/apps/admin test -- --run
& $node $npmCli --prefix fx-trading-platform/apps/admin run build
```

架构检查：

```powershell
& $node $npmCli --prefix fx-trading-platform run verify:architecture
```

仅在 Docker-capable 主机启动数据库服务：

```powershell
docker compose -f fx-trading-platform/infra/docker-compose.yml up -d
```

每次完成声明前：

```powershell
git diff --check
git status --short
```

必须报告准确的 tests、failures、errors 和 skips。Maven `BUILD SUCCESS` 但 Testcontainers 全部 skipped，不等于 PostgreSQL 行为验证通过。

## 九、新会话启动提示词

在新会话中直接发送：

```text
继续执行 USDT 现货与永续 Demo 项目。工作树是
C:\workspace\.codex-worktrees\tradingWeb-usdt-spot-perp-p0，分支
codex/usdt-spot-perp-p0。

先完整阅读 AGENTS.md、权威规格、2026-07-11 总实施计划，以及
2026-07-12 中文会话交接计划。不要重做 Task 0-5；从 Task 6A 的 RED 测试开始。
严格按 Binance/OKX 最合理且最小化语义执行，不要向我提问。使用 TDD，逐任务验证、
提交并进行规格/质量双复审。Docker 不可用时如实记录 Testcontainers skip，不使用 H2，
不伪造 Web/KLineCharts 构建产物。
```

## 十、新会话开工条件

- [ ] 当前分支正确，`3886df7b` 是 HEAD 的祖先，之后每个提交都是纯文档或已经明确理解。
- [ ] 已检查并保留工作树中的所有 tracked 修改。
- [ ] 已完整阅读权威规格、2026-07-11 总计划、本中文交接计划和 Task 6 brief。
- [ ] Task 6 report 没有未经验证的 RED/GREEN 声明。
- [ ] 已配置 Java 21 和 Maven 3.9.9。
- [ ] 第一项实际工作是 Task 6A 的失败测试，而不是直接修改生产代码。
