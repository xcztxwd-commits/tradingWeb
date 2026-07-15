# Web PC/Mobile 拆分：新会话命令集

> 使用方式：每次只复制一段到一个新 Codex 会话。严格按编号顺序执行；一个会话只完成一个任务或一个计划，不自动继续下一段。

权威文档：

- 设计规格：`fx-trading-platform/docs/superpowers/specs/2026-07-15-web-pc-mobile-ui-separation-design.md`
- 第一阶段实施计划：`fx-trading-platform/docs/superpowers/plans/2026-07-15-web-pc-mobile-ui-separation-implementation.md`

当前特别说明：2026-07-15 的工作区存在大量与前端重叠的未提交改动。命令 0 是强制门禁；在这些改动形成安全基线前，不得执行命令 1。

## 命令 0：只读基线检查

```text
在 C:\workspace\tradingWeb 工作。本会话只做只读基线检查，不修改、暂存、提交或删除任何文件。

先完整读取：
1. C:\workspace\tradingWeb\AGENTS.md
2. fx-trading-platform/docs/superpowers/specs/2026-07-15-web-pc-mobile-ui-separation-design.md
3. fx-trading-platform/docs/superpowers/plans/2026-07-15-web-pc-mobile-ui-separation-implementation.md

然后执行 git status --short、git diff --name-only、git diff --cached --name-only，并把结果与实施计划的 Preflight Gate 路径逐项比对。

如果有任何重叠，停止，不要 stash、reset、checkout、commit 或创建 worktree；只输出：
- 重叠文件清单；
- staged / unstaged / untracked 分类；
- 为什么现在不能安全迁移；
- 需要用户先决定如何保存现有改动。

如果没有重叠，再运行 web:test、web:build、verify:architecture，记录每条命令的退出码和结果。最终只给出 READY 或 BLOCKED 结论，不实施 Task 1。
```

## 命令 1：实施依赖边界守卫

```text
在 C:\workspace\tradingWeb 工作。使用 superpowers:executing-plans、superpowers:test-driven-development 和 superpowers:verification-before-completion。

完整读取 AGENTS.md、设计规格和实施计划。只执行实施计划中的 Task 1: Add Enforced Frontend Dependency Boundaries，不执行 Task 2。

开始前重新执行 Preflight Gate；若 Task 1 涉及文件有未提交重叠，立即停止并报告。严格先写失败测试，再实现 verify-frontend-boundaries，运行计划列出的三组验证。不要放宽规则，不修改业务代码。

验证通过后只提交 Task 1 文件，提交信息使用：test: enforce frontend package boundaries

最终输出：修改文件、失败测试证据、通过的测试命令及结果、提交 hash、Task 2 是否已具备执行条件。不要继续下一任务。
```

## 命令 2：创建 UI 包并迁移主题

```text
在 C:\workspace\tradingWeb 工作。使用 superpowers:executing-plans、superpowers:test-driven-development 和 superpowers:verification-before-completion。

完整读取 AGENTS.md、设计规格和实施计划，确认 Task 1 的提交存在且 verify:frontend-boundaries 通过。只执行 Task 2: Create @fx-platform/ui and Move the Theme System。

开始前检查 Task 2 全部文件的 git 状态；存在用户改动重叠就停止。严格保留现有主题 token 值、ThemeProvider 行为和 Provider 层级，只改变所有权与 import。使用 package-lock-only --ignore-scripts 更新锁文件。

执行计划中的 ui:test、ui:typecheck、web:test、web:build、verify:architecture。验证通过后提交：refactor: extract shared theme package

最终输出修改文件、测试结果、提交 hash；不要迁移 SelectField，不执行 Task 3。
```

## 命令 3：迁移首个公共组件 SelectField

```text
在 C:\workspace\tradingWeb 工作。使用 superpowers:executing-plans、superpowers:test-driven-development 和 superpowers:verification-before-completion。

读取 AGENTS.md、设计规格和实施计划。确认 @fx-platform/ui 的主题任务已提交并通过。只执行 Task 3: Move SelectField as the First Shared UI Component。

开始前检查 Task 3 文件是否 dirty；重叠就停止。保持 SelectField 的受控 props、键盘操作、ARIA listbox/option 语义和当前视觉。基础 CSS 归 UI 包，LanguageSwitcher 和 market sort 的消费方覆盖仍留在 apps/web。不要顺手迁移 DataTable、PageState 或其他组件。

运行 ui:test、ui:typecheck、web:test、web:build。通过后提交：refactor: move select field into ui package

最终报告 CSS 从哪里移到哪里、消费者 import、测试命令与提交 hash。不要执行 Task 4。
```

## 命令 4：创建 frontend-core 并迁移模型、认证存储与 API

```text
在 C:\workspace\tradingWeb 工作。使用 superpowers:executing-plans、superpowers:test-driven-development 和 superpowers:verification-before-completion。

完整读取 AGENTS.md、设计规格和实施计划。只执行 Task 4: Create @fx-platform/frontend-core and Move Models, Auth Storage, and API Clients。

这是高重叠任务。开始前必须确认 apps/web/src/services、types/trading.ts、components/tables/types.ts、tradingSessionStorage.ts 及所有计划列出的消费者没有未提交改动；任何重叠都停止，不能自行合并用户改动。

严格采用移动而非复制：endpoint、storage key、refresh retry、ApiClientError、STOMP topic 和 payload type 均不得改变。更新所有消费者到 @fx-platform/frontend-core/api、/auth、/models；完成后旧 services/types 实现必须消失。同步更新 source-reading tests 与 verify-architecture 路径。

运行 frontend-core:test、frontend-core:typecheck、web:test、web:build、verify:architecture。通过后提交：refactor: extract frontend api and models

最终输出所有移动映射、rg 零匹配证据、测试结果和提交 hash。不要执行 market 迁移。
```

## 命令 5：迁移共享行情运行时

```text
在 C:\workspace\tradingWeb 工作。使用 superpowers:executing-plans、superpowers:test-driven-development 和 superpowers:verification-before-completion。

读取 AGENTS.md、设计规格和实施计划。确认 frontend-core 的 API/auth/models 已提交并且边界检查通过。只执行 Task 5: Move the Shared Market Runtime into frontend-core。

开始前检查整个 apps/web/src/features/market 及计划列出的消费者是否 dirty；有重叠就停止。整体 git mv 该目录，不复制实现。只修正四类跨模块 import，所有行情模型、snapshot、favorites、provider status、HTTP/STOMP 和 fallback 语义保持不变。

把所有应用消费者改为 @fx-platform/frontend-core/market，更新五个测试类型 import、userPages source path 和 verify-architecture。用 rg 证明 core 不反向依赖 app、app 不再引用 features/market。

运行 frontend-core:test、frontend-core:typecheck、web:test、web:build、verify:architecture。通过后提交：refactor: move market runtime into frontend core

最终输出移动文件数、非法依赖扫描、测试结果和提交 hash。不要处理交易表单或 PC/Mobile 页面。
```

## 命令 6：抽离纯表格模型

```text
在 C:\workspace\tradingWeb 工作。使用 superpowers:executing-plans、superpowers:test-driven-development 和 superpowers:verification-before-completion。

读取 AGENTS.md、设计规格和实施计划。只执行 Task 6: Extract Pure Table Models and Close the First Public-Core Slice。

开始前检查 userPageModels.ts/test 和 frontend-core models 是否 dirty；重叠就停止。只移动 SortDirection、filterByStatus、sortRows、paginateRows、toNumber 及其私有比较函数。formatApiError、ApiErrorView 和 i18n 必须留在应用层。

运行 frontend-core:test、frontend-core:typecheck、web:test、web:build。通过后提交：refactor: extract shared table models

最终输出移动的函数、测试职责拆分和提交 hash。不要继续 Task 7。
```

## 命令 7：关闭公共基础层阶段

```text
在 C:\workspace\tradingWeb 工作。使用 superpowers:executing-plans、superpowers:test-driven-development 和 superpowers:verification-before-completion。

读取 AGENTS.md、设计规格和实施计划。只执行 Task 7: Add the Unified Frontend Gate and Document the New Foundation。

开始前检查 package.json、architecture.md 和验证脚本是否 dirty；重叠就停止。先写 frontend:check 的失败断言，再添加统一命令。架构文档必须准确描述已经落地的 package 所有权，并明确 PC/Mobile runtime 尚未启用。

运行 frontend:check、admin:build、web:bundle-budget、audit:large-files，以及计划中的三组 rg 依赖扫描。任何失败都必须修正根因，不能删除检查。

验证通过后提交：docs: close shared frontend foundation

最终逐项核对 Foundation Definition of Done，报告所有命令与结果、提交 hash，并明确下一步是交易 controller 抽离计划。不要直接开始下一子项目。
```

## 命令 8：生成交易 Controller 抽离的独立计划

仅在命令 7 完成后使用。

```text
在 C:\workspace\tradingWeb 工作。本会话只分析和写计划，不实现代码。

使用 superpowers:brainstorming 与 superpowers:writing-plans。读取 AGENTS.md、PC/Mobile 双 UI 设计规格、已完成的 shared foundation 实施计划和当前代码。

聚焦一个子项目：把 features/trading-session、features/trading/hooks、features/trading/services、features/trading/types、纯 trading view-model 抽入 @fx-platform/frontend-core；React 组件和 CSS 留在 apps/web。必须先消除 core 对 react-i18next、页面组件和应用 services/types 的依赖：公共 controller 返回错误 key/command，翻译与展示留在应用层。

计划必须包含失败测试、精确文件移动、公共接口、兼容步骤、消费者更新、交易回归，并验证 login gate、行情 snapshot、submit dedupe、orders、positions、wallet balance、asset ledger、account summary、demo/live 隔离。

保存到 fx-trading-platform/docs/superpowers/plans/2026-07-15-trading-controller-extraction-implementation.md。自检占位词、接口一致性和范围后，只提交计划文档，不执行实现。
```

## 命令 9：生成单应用双 UI 运行时计划

仅在交易 Controller 抽离完成后使用。

```text
在 C:\workspace\tradingWeb 工作。本会话只分析和写计划，不实现代码。

使用 superpowers:brainstorming 与 superpowers:writing-plans。读取 AGENTS.md、双 UI 设计规格、shared foundation 计划、trading controller 计划及当前代码。

聚焦一个子项目：保持单一 apps/web 与一份 AppRoutes，新增 useDeviceClass（max-width: 900px）、PlatformView、route adapter、PC Shell、Mobile Shell 和动态 import。公共 Provider、route controller、认证、行情订阅和交易 session 必须位于平台选择之上；不能同时挂载两套 UI 再用 CSS 隐藏。

计划必须验证 1440x900、390x844、899px、901px、运行时跨断点、刷新、深链、前进后退、query 参数、lazy chunks 和订阅不重复。

保存到 fx-trading-platform/docs/superpowers/plans/2026-07-15-adaptive-web-runtime-implementation.md。自检后只提交计划文档，不实施页面迁移。
```

## 命令 10A：规划 Auth 与 Home 双 UI

```text
在 C:\workspace\tradingWeb 工作。读取 AGENTS.md、双 UI 设计规格、shared foundation 计划、trading controller 计划、adaptive runtime 计划和当前代码。

本会话只处理 login、register、forgot-password、two-factor-help 和 home。先使用 superpowers:brainstorming 确认 PC/Mobile 信息层级与交互差异，再使用 superpowers:writing-plans 生成独立实施计划；用户确认后才能实现。

route controller 和业务 command 共用；PC 与 Mobile view 不互相 import；UI package 只接收无业务组件；不修改后端或认证语义。每个路由同时覆盖 loading、error、unauthenticated 和 ready。

计划保存为 fx-trading-platform/docs/superpowers/plans/2026-07-15-auth-home-pc-mobile-ui-implementation.md。不要处理其他路由。
```

## 命令 10B：规划 Account 与 Settings 双 UI

```text
在 C:\workspace\tradingWeb 工作。读取 AGENTS.md、双 UI 设计规格、shared foundation 计划、trading controller 计划、adaptive runtime 计划和当前代码。

本会话只处理 dashboard、account、security 和 settings。先使用 superpowers:brainstorming 确认 PC/Mobile 信息层级与交互差异，再使用 superpowers:writing-plans 生成独立实施计划；用户确认后才能实现。

route controller 和业务 command 共用；PC 与 Mobile view 不互相 import；UI package 只接收无业务组件；不修改后端、账户数据或权限语义。每个路由同时覆盖 loading、empty、error、unauthenticated 和 ready。

计划保存为 fx-trading-platform/docs/superpowers/plans/2026-07-15-account-settings-pc-mobile-ui-implementation.md。不要处理其他路由。
```

## 命令 10C：规划 Markets、Orders、Positions 与 Wallet 双 UI

```text
在 C:\workspace\tradingWeb 工作。读取 AGENTS.md、双 UI 设计规格、shared foundation 计划、trading controller 计划、adaptive runtime 计划和当前代码。

本会话只处理 markets、orders、positions 和 wallet。先使用 superpowers:brainstorming 确认 PC/Mobile 信息层级与交互差异，再使用 superpowers:writing-plans 生成独立实施计划；用户确认后才能实现。

route controller 和业务 command 共用；PC 与 Mobile view 不互相 import；不修改后端、API、风控、钱包或行情语义。计划必须验证 wallet balance、asset ledger、account summary、orders、positions、provider status 和 quote。

计划保存为 fx-trading-platform/docs/superpowers/plans/2026-07-15-market-account-data-pc-mobile-ui-implementation.md。不要处理 trading terminal 或其他路由。
```

## 命令 10D：规划 Trading Terminal 双 UI

```text
在 C:\workspace\tradingWeb 工作。读取 AGENTS.md、双 UI 设计规格、shared foundation 计划、trading controller 计划、adaptive runtime 计划和当前代码。

本会话只处理 trading terminal。先使用 superpowers:brainstorming 确认 PC/Mobile 信息层级与交互差异，再使用 superpowers:writing-plans 生成独立实施计划；用户确认后才能实现。

复用同一个 trading controller、selected market、snapshot、balances、orders、positions、validation 和 submit command；PC 保留分栏/resize/桌面工具栏，Mobile 保留触屏导航/sheet/drawer。不得改变后端风控、行情 fallback、下单语义或 demo/live 隔离。

计划保存为 fx-trading-platform/docs/superpowers/plans/2026-07-15-trading-terminal-pc-mobile-ui-implementation.md。不要处理其他路由。
```

## 命令 11：最终清理与总验收计划

仅在四个路由组全部完成后使用。

```text
在 C:\workspace\tradingWeb 工作。本会话先审计、再写最终清理计划，不直接删除文件。

读取 AGENTS.md、双 UI 设计规格和全部已完成实施计划。审计旧 re-export、旧页面、重复 API/hook/type、apps/web/src/styles.css 中的页面/平台布局、跨平台 import、未使用依赖和同时进入首屏的 PC/Mobile chunk。

使用 superpowers:writing-plans 生成 legacy-web-ui-cleanup-implementation.md。计划必须包含可证明的删除清单、每项引用扫描、frontend:check、bundle budget、PC/Mobile visual QA、用户核心页面 smoke、交易登录门禁、钱包 balance/asset ledger/account summary、provider sync/symbol binding/quote/candles 和 demo/live 隔离验收。

只提交计划文档，等待用户确认后实施。
```
