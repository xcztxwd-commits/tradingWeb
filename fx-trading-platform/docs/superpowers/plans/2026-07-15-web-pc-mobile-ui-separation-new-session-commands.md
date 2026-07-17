# Web PC/Mobile UI 整理：Codex 总目标与恢复命令集

这是一组可直接粘贴给 Codex 的任务指令，不是需要在 PowerShell 中执行的脚本。

权威文档：

- 设计规格：`fx-trading-platform/docs/superpowers/specs/2026-07-15-web-pc-mobile-ui-separation-design.md`
- 实施计划：`fx-trading-platform/docs/superpowers/plans/2026-07-15-web-pc-mobile-ui-separation-implementation.md`
- 仓库规则：`C:\workspace\tradingWeb\AGENTS.md`

推荐第一次只使用“命令 M”。如果任务被中断，优先使用“命令 A”自动判断恢复点；只有已明确知道中断阶段时才使用 R0–R11。

## 固定执行原则

所有命令都隐含以下不可覆盖规则：

- 目标是完成实施计划 Task 1–16 和严格 DoD，不是只生成新计划。
- 只有 `apps/web`、`packages/ui`、`packages/frontend-core`、前端验证脚本和对应文档在范围内；不改 `apps/admin`、backend 业务代码。
- 当前 worktree（包括未提交前端改动）是基线。遇到重叠先读 diff、调用链和测试，再自动语义合并；不得因 dirty 直接停止。
- 优先保留现有业务行为、API 合同、用户改动和测试。不得 reset、checkout、stash、覆盖或删除无关改动。
- 已知 `orderAdapter.ts` 及测试中的独立随机 `idempotencyKey` 行为必须保留。
- `smoke-visual-qa.mjs` 必须在现有用户 diff 上扩展，不能替换成旧版本。
- 每个任务先写失败测试，执行相关验证，只暂存计划路径中的任务 hunks 并独立提交；同文件的无关既有 hunks 留在工作树。
- 每一阶段完成后自动进入下一阶段，不等待人工确认；只有外部业务合同将被改变、会产生不可逆数据影响或缺少必要外部权限时才请求用户。
- 不自动 push，不创建 PR，不连接真实 broker/FIX/LP。

## 命令 M：建立总体目标并执行到全部完成

```text
在 C:\workspace\tradingWeb 工作。建立或继续一个总体目标：完整实施 apps/web 的 PC/Mobile 双 UI、@fx-platform/ui、@fx-platform/frontend-core 和全部严格验收；不得只完成公共基础层，也不得在阶段之间等待确认。

开始前完整读取：
1. C:\workspace\tradingWeb\AGENTS.md
2. fx-trading-platform/docs/superpowers/specs/2026-07-15-web-pc-mobile-ui-separation-design.md
3. fx-trading-platform/docs/superpowers/plans/2026-07-15-web-pc-mobile-ui-separation-implementation.md

使用 superpowers:executing-plans 或 superpowers:subagent-driven-development 执行计划；每个实现任务使用 superpowers:test-driven-development；每次声称阶段或总体完成前使用 superpowers:verification-before-completion。严格按 Task 1 到 Task 16 顺序工作并更新计划检查状态。

当前实际 worktree 是执行基线。先运行计划中的 Worktree Reconciliation Protocol。对计划路径中的现有改动逐文件读 git diff、调用链和测试后，在当前内容上做语义合并；不因 dirty 停止，不 stash/reset/checkout，不覆盖用户代码。非重叠的 backend/admin/文档/脚本改动不暂存、不提交。若架构建议与业务链路冲突，自动调整架构并保留业务行为、API 合同、用户改动和现有测试。

特别保留 apps/web/src/features/trading/services/orderAdapter.ts 及测试中独立 UUID idempotencyKey 与 clientOrderId 分离的行为；迁移该文件时先增加/保留标准订单和 OCO 回归测试。处理 scripts/smoke-visual-qa.mjs 时先读当前 diff并语义合并。

每个 Task 必须：先观察失败测试；做最小实现；运行计划列出的目标验证；运行包边界检查；对 dirty 重叠文件使用 hunk 级暂存；确认 staged diff 不含无关文件或 hunks；使用计划规定的 commit subject。阶段完成后直接继续下一 Task。若会话被压缩或中断，使用 git log、测试结果和计划 checkpoint 判断最后一个真实完成任务，从未完成的最小步骤继续，不重做已完成工作。

最终必须达到计划 Strict Definition of Done：全部内容 URL 有 PC/Mobile view 并共用 Controller，redirect/fallback 两端目标一致，900/901 和运行时 resize 正确；旧实现/re-export/重复逻辑/非法依赖清零；styles.css 收敛；UI/core/web/architecture/bundle/large-file 全绿；全路由视觉矩阵、账户钱包和 demo 交易链路通过。不得放宽测试、包体或行数阈值。完成后输出阶段提交表、最终文件所有权、所有验证命令与实际结果、视觉报告路径、仍存在的范围外用户改动；不要 push 或创建 PR。
```

## 命令 A：中断后自动判断并续跑到完成

```text
在 C:\workspace\tradingWeb 恢复“Web PC/Mobile UI 整理”总体目标，并持续执行到严格 DoD 全部满足。

完整读取 AGENTS.md、设计规格和实施计划。先执行 git status --short、git diff --name-status、git diff --cached --name-status、git log -20 --oneline，再运行与最近 checkpoint 对应的最小验证。依据实施计划的 Phase Checkpoint Map 判断：
- 已有规定 commit 且该阶段验证仍通过：视为已完成，不重做；
- 有未提交部分实现：从该 Task 的第一个未满足测试/步骤继续；
- commit 存在但验证失败：先用 systematic-debugging 查明回归，再继续；
- worktree 有重叠用户改动：按 Worktree Reconciliation Protocol 语义合并，不因 dirty 停止。

使用 superpowers:executing-plans、test-driven-development 和 verification-before-completion。从真实未完成的最早 Task 开始，按 Task 1–16 顺序自动完成余下所有阶段；每阶段按计划验证和提交，不等待确认。保持业务链路/API/测试优先，保留 orderAdapter 独立 idempotencyKey 和 smoke-visual-qa 当前改动，不触碰范围外 backend/admin 文件。最终执行完整静态、视觉、账户钱包和 demo 交易验收；不 push、不建 PR。

最终输出恢复点判断证据、完成的阶段提交、全部验证实际结果和严格 DoD 对照。
```

## 命令 R0：从 Phase 0 基线与依赖守卫恢复

```text
在 C:\workspace\tradingWeb 恢复总体目标，从实施计划 Task 1 / Phase 0 开始。完整读取 AGENTS.md、设计规格、实施计划，使用 executing-plans、test-driven-development、verification-before-completion。

重新测量 web:test、web:build、verify:architecture 以及已知 bundle/large-file 债务；创建并测试 verify-frontend-boundaries；建立 ui/frontend-core 最小 package/tsconfig/export/test 骨架与 `frontend:check`；整合架构检查和文档。当前 worktree 是基线，重叠时语义合并，不因 dirty 停止，不触碰范围外文件。完成并验证 commit `test(frontend): enforce target dependency boundaries` 后，自动继续 Task 2–16，直到严格 DoD 全部完成。不要 push。
```

## 命令 R1：从 Phase 1 UI 主题包恢复

```text
在 C:\workspace\tradingWeb 恢复总体目标。先确认 Task 1 checkpoint 和验证真实通过；然后从 Task 2 / Phase 1 的 @fx-platform/ui 与主题迁移继续。

按实施计划先迁移行为测试，再移动当前 worktree 的 theme 实现，保持 token、storage key、Provider 层级和视觉不变；更新 workspace/lockfile并运行 UI、web、边界验证。语义合并现有改动，不覆盖或暂存范围外文件。完成 commit `refactor(ui): extract shared theme package` 后自动执行余下 Task 3–16，直到总验收通过。不要 push。
```

## 命令 R2：从 Phase 2 公共 UI 组件恢复

```text
在 C:\workspace\tradingWeb 恢复总体目标。验证 Task 2 checkpoint 后，从实施计划 Task 3 开始，连续完成 Task 3–4。

只抽取已有多消费者证据的 SelectField、IconButton、StateSurface、Skeleton、DataTable/DataCardList、Dialog 和已有 market/quote 两个调用点的 Drawer；品牌 `ExchangeLoading` 留在应用。MobileOrderSheet 只有一个消费者，也先留在应用。先补行为/ARIA/键盘测试，使用 CSS Modules，UI 包不得依赖业务、router 或 i18n。短期 app adapter 可以存在到对应页面迁移，但不得复制算法。完成 `refactor(ui): extract shared state data and dialog primitives` checkpoint 后自动继续 Task 5–16，直到严格 DoD。保持 dirty 语义合并策略，不 push。
```

## 命令 R3：从 Phase 3 API/Auth/Market Core 恢复

```text
在 C:\workspace\tradingWeb 恢复总体目标。确认 UI 阶段验证后，从 Task 5 开始连续完成 Task 5–6。

创建 @fx-platform/frontend-core；先迁移测试，再移动 API、auth storage、模型、纯表格函数和完整 market runtime。保持 endpoint、HTTP payload、401 refresh、requestId、storage key、legacy token、行情来源/fallback/STOMP/reconnect 完全不变。Core 禁止 app、router、i18n、CSS。用 rg 证明旧 import/re-export 消失。完成 `refactor(core): move market runtime into frontend core` 后自动继续 Task 7–16。现有改动语义合并，不 push。
```

## 命令 R4：从 Phase 4 Account/Wallet Core 恢复

```text
在 C:\workspace\tradingWeb 恢复总体目标，从 Task 7 / Phase 4 继续。先验证最近 checkpoint 和当前 worktree，再写账户/钱包 Controller 失败测试。

抽离 refresh coordinator、account models/operations/hooks 和 wallet controller；core 返回 message descriptor，不依赖 i18n/router/CSS。重点验证 wallet balance、asset ledger、account summary 在 transfer/reset 后一致刷新，以及 guest/loading/error/ready 行为。完成 `refactor(core): extract account and wallet controllers` 后自动继续 Task 8–16。按语义合并策略保护用户改动与业务链路，不 push。
```

## 命令 R5：从 Phase 5 Trading Controller 恢复

```text
在 C:\workspace\tradingWeb 恢复总体目标，从 Task 8 开始连续完成 Task 8–9。使用 TDD 先冻结当前订单 payload、表单校验、session、STOMP、批量动作和 position protection 行为。

迁移 orderAdapter 时必须保留当前未提交改动中的独立随机 UUID idempotencyKey，且标准订单/OCO 都有测试、不得与 clientOrderId 复用。移除 core 对 i18n 的依赖，使用 message descriptor。建立一个供两端复用的 TradingRouteModel 和 Controller，但暂不拆终端布局。完成 `refactor(core): extract trading session controller` 后自动继续 Task 10–16。不要触碰 backend/admin，不 push。
```

## 命令 R6：从 Phase 6 自适应运行时恢复

```text
在 C:\workspace\tradingWeb 恢复总体目标，从 Task 10 / Phase 6 继续。先写 899/900/901、matchMedia listener cleanup 和 Controller 不重建测试。

建立唯一 `(max-width: 900px)` DeviceClassProvider 与 PlatformView；AppShell 继续稳定持有 session/main，PC topbar 与 Mobile tabs 只作为 chrome 切换。删除交易局部 768px hook，增加 data-platform-view 标记和 901->900->899->901 连续 resize 验证。完成 `refactor(web): add stable pc mobile runtime` 后自动继续 Task 11–16。语义合并 dirty 文件，不 push。
```

## 命令 R7：从 Phase 7 Home/Auth 双 UI 恢复

```text
在 C:\workspace\tradingWeb 恢复总体目标，从 Task 11 / Phase 7 继续。为 `/`、`/login`、`/register`、`/forgot-password`、`/two-factor-help` 建立常驻 route controller 和独立 PC/Mobile views。

PC 1440x900 与 Mobile 390x844 保持当前内容、表单、链接、认证和 redirect 语义；视图不直接调用 API/storage。把旧响应式 DOM 与 CSS 按平台归属移动，仅在两个真实消费者完全相同时创建 shared widget。完成 `refactor(web): split home and auth platform views` 后自动继续 Task 12–16，并跑计划 viewport 验证。不要重设计、不 push。
```

## 命令 R8：从 Phase 8 Account/Settings 双 UI 恢复

```text
在 C:\workspace\tradingWeb 恢复总体目标，从 Task 12 / Phase 8 继续。迁移 dashboard、account overview/assets/funding/trades/KYC/settings、security 和 settings 的全部路由。

每个路由使用一个 Controller 和独立 PC/Mobile view；PC 显式使用 table，Mobile 显式使用 card list，不用 CSS 在同一 DOM 中隐藏另一套结构。保持 guest redirect、loading/error/empty、account/KYC/settings 行为。完成 `refactor(web): split account and settings platform views` 后自动继续 Task 13–16。运行 guest/auth 与 899/900/901 验证，不 push。
```

## 命令 R9：从 Phase 9 数据页面双 UI 恢复

```text
在 C:\workspace\tradingWeb 恢复总体目标，从 Task 13 开始连续完成 Task 13–14：Markets、Orders、Positions、Wallet。

先冻结 market filter/sort/favorite/source/navigation、order cancel/batch payload、position close/protection、wallet transfer/reset/ledger/requestId 合同。每页一个 Controller、两套 view；resize 中 Controller 和 pending 操作保持，不重复订阅/提交。仅抽取真实共享 widget。完成 `refactor(web): split orders positions and wallet views` 后自动继续 Task 15–16，并执行相关 core/web/visual/user-core-pages 验证。语义合并现有改动，不 push。
```

## 命令 R10：从 Phase 10 Trading Terminal 双 UI 恢复

```text
在 C:\workspace\tradingWeb 恢复总体目标，从 Task 15 / Phase 10 继续。Trading 最后迁移，先运行全部 trading/session/market/orderAdapter 回归并写 Controller 连续性、订阅唯一性、form/pending/resize 测试。

把 desktop terminal 移到 pc，把 mobile terminal/drawer/order sheet 移到 mobile，公共 chart/quote/account widget 只在两端真正复用时抽取。TradingRoute Controller 常驻并 lazy-load 两端 view；删除 TradingPage、768px hook 和旧包装。保持所有 canonical route、chart、行情、订单/OCO/TP-SL、批量动作和 position protection 行为。更新 bundle/large-file 检查时不得放宽阈值；已知两项债务必须通过。完成 `refactor(web): split trading terminal platform views` 后自动继续 Task 16。不要连接真实 broker，不 push。
```

## 命令 R11：从 Phase 11 清理与总验收恢复

```text
在 C:\workspace\tradingWeb 恢复总体目标，从 Task 16 / Phase 11 继续。先读当前全部 diff，特别是 scripts/smoke-visual-qa.mjs 的用户改动，在当前版本上语义扩展。

删除零消费者兼容层、旧页面、旧 re-export、重复逻辑和非法跨端 import；styles.css 只保留 <=400 行基础样式且无 route selector。扩展并保留现有 visual smoke：1440x900、390x844、899x844、900x844、901x844，执行同页 901->900->899->901，覆盖全部 Route Contract URL 的适用 guest/auth 状态。

执行实施计划 Task 16 的全部静态门禁、visual QA、smoke:user-core-pages、web:smoke:trading、acceptance:p0-user-trading。任何失败都先使用 systematic-debugging 查根因并修复，不删测试、不放宽阈值。用 rg 完成非法依赖/placeholder/兼容层审计。所有结果真实通过后提交 `refactor(web): complete pc mobile ui separation`。

最终逐条对照 Strict Definition of Done，输出所有阶段 commit hash、测试命令与实际结果、视觉报告路径、范围外未提交改动。不要 push 或创建 PR。
```

## 完成判定

只有同时满足以下条件，Codex 才能结束总体目标：

1. 实施计划 Task 1–16 全部完成，R0–R11 checkpoint 均可在 Git 历史中验证。
2. 所有内容路由都有 PC/Mobile view 并共用 Controller，redirect/fallback 两端一致；900/901 与动态 resize 通过。
3. UI/core 边界、旧路径清理、CSS 归属、bundle 和 large-file 门禁通过。
4. 全部静态测试、全路由视觉矩阵、账户/钱包与 demo 交易 smoke 实际通过。
5. 没有混入范围外 backend/admin 改动，没有自动 push，没有连接真实交易设施。
