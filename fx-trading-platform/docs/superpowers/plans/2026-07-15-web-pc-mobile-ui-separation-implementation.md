# Web PC/Mobile UI Separation and Shared Frontend Libraries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变现有 URL、后端 API、认证语义、交易规则、钱包规则、行情 fallback 和 `demo/live` 隔离的前提下，把 `apps/web` 整理为单应用、单路由集、运行时选择的 PC/Mobile 两套 UI；抽离无业务 UI 包 `@fx-platform/ui` 与无页面依赖的业务前端包 `@fx-platform/frontend-core`，迁移全部用户路由并通过严格总验收。

**Architecture:** `apps/web` 保留为唯一 Vite 应用。路由组件和业务 Controller 常驻在设备选择层之上；`PlatformView` 仅在 `max-width: 900px` 与 `min-width: 901px` 之间切换展示组件，切换时不得重建认证、行情、账户或交易 Controller。`packages/ui` 只依赖 React/ReactDOM 与无业务图标库 `lucide-react` 并提供主题与无业务组件；`packages/frontend-core` 只依赖 React hooks、Zustand、STOMP 与 `shared-types` 等现有运行时能力，可包含 API、存储、模型、状态和业务 Controller，但不得依赖页面、React Router、i18n 或 CSS。PC/Mobile 页面只负责平台布局与交互呈现，完全相同且已有至少两个消费者的业务展示组合留在 `apps/web/src/shared-widgets`。

**Tech Stack:** React 19、TypeScript 5.8、Vite 7、npm workspaces、Node test runner、Zustand 5、STOMP、CSS Modules、CSS custom properties、Chrome DevTools Protocol visual smoke。

## Global Constraints

- 每次执行前完整读取 `C:\workspace\tradingWeb\AGENTS.md`、设计规格 `fx-trading-platform/docs/superpowers/specs/2026-07-15-web-pc-mobile-ui-separation-design.md` 和本计划。
- 本计划只修改 `fx-trading-platform/apps/web`、新建的两个前端 package、前端验证脚本及相关文档；不得修改 `apps/admin` 或 `backend` 业务代码。
- 当前实际 worktree（包括未提交前端修改）就是执行基线。不得要求先清空工作区，也不得执行 `git reset --hard`、`git checkout --`、自动 stash、递归删除工作区或覆盖用户改动。
- 若计划文件与现有改动重叠，必须先读 `git diff`、相关调用链和测试，再在当前内容上做语义合并。优先级固定为：现有业务行为/API 合同/用户改动/已有测试 > 本计划建议的文件形态。
- 已知重叠 `apps/web/src/features/trading/services/orderAdapter.ts` 及其测试增加了独立随机 `idempotencyKey`；迁移时必须保留它与 `clientOrderId` 相互独立、普通订单与 OCO 都生成 UUID 的行为。
- `scripts/smoke-visual-qa.mjs` 当前也有用户修改；Phase 11 必须在现有 diff 上扩展，不能用旧版本替换。
- 非重叠的 backend、文档、脚本和未跟踪文件一律不暂存、不提交、不格式化。
- 每个任务都先增加或调整失败测试，再做最小实现；不得删除测试、放宽阈值或改断言来隐藏失败。
- 每个任务只暂存列出的路径和本任务拥有的 hunks，执行 `git diff --cached --name-only` 与 `git diff --cached` 核对后提交。重叠文件中的无关既有 hunks 使用交互式/hunk 级暂存留在工作树；只有本任务必须共同迁移的前端基线行为（例如 `orderAdapter`）才随文件提交，并在阶段报告中显式记录。
- 保留一个 `apps/web`、一套路由和当前全部 URL。不得建立第二个独立应用，也不得使用 UA 判断。
- 设备规则唯一：`width <= 900` 为 Mobile，`width >= 901` 为 PC；所有结构性选择都消费同一个设备运行时。
- 保留现有视觉与交互，不做品牌重设计。允许为了组件边界移动 DOM，但相同视口下的内容、可用动作、键盘行为、ARIA 语义和业务结果必须保持。
- 继续使用 CSS Modules 与语义 token；不引入 Tailwind、CSS-in-JS、Storybook 或第二套主题框架。
- 只有已有至少两个真实消费者的无业务能力进入 `@fx-platform/ui`。只有完全相同且已有至少两个真实消费者的业务组合进入 `shared-widgets`。
- 迁移中允许短期 re-export 或 app adapter；消费方迁完后立即删除，Phase 11 结束时不得残留兼容层、旧实现、非法跨端 import 或重复业务实现。
- UI 文案翻译留在 `apps/web`；core 只返回稳定的错误码或 `{ key, values }` 描述，不得调用 `i18next`/`react-i18next`。
- 交易风控始终以后端为准；前端校验只是提示。不得连接真实 broker/FIX/LP，验证环境使用 `dev` profile 的 demo execution。

## Authoritative Inputs and Known Baseline

设计决策以设计规格为准。本计划基于 2026-07-17 审计得到以下起点，Task 1 必须重新测量并记录差异：

| 项目 | 当前证据 |
| --- | --- |
| `apps/web/src` 规模 | 109 个 TSX / 15,683 行；156 个 TS / 24,963 行；32 个 CSS / 17,026 行 |
| 最大全局样式 | `apps/web/src/styles.css` 约 6,263 行 |
| 交易样式 | `features/trading/styles/trade-panel.css` 约 2,354 行 |
| 最大页面 | `MarketsPage.tsx` 约 1,256 行；`KLineChartPanel.tsx` 约 1,230 行 |
| 自动测试 | `web:test`：78 suites、530 tests，通过 |
| 构建 | `web:build` 通过；`TradingPage` chunk 约 444,078 bytes |
| 架构 | `verify:architecture` 通过 |
| 已知债务门禁 | `web:bundle-budget` 因 TradingPage 超过 380,000 bytes 失败；`audit:large-files` 因 `TradingPage.tsx` 340/330、`TradePanel.tsx` 307/230 失败 |
| 现有设备判断 | 交易终端局部使用 `(max-width: 768px)`，全应用没有统一运行时 |
| 现有公共层 | 只有 `packages/shared-types`；无 `packages/ui`、`packages/frontend-core`、`src/pc`、`src/mobile`、`src/shared-widgets` |

已知债务失败不是提前停止理由，但每阶段不得新增失败；Phase 10/11 必须让两项门禁真正通过，不能提高现有预算或行数限制。

## Target Dependency Contract

```text
apps/web/src/routes ───────────────┐
apps/web/src/pc ───────────────────┼──> @fx-platform/ui
apps/web/src/mobile ───────────────┼──> @fx-platform/frontend-core
apps/web/src/shared-widgets ───────┘

@fx-platform/frontend-core ───────────> React / Zustand / STOMP / @fx-platform/shared-types
@fx-platform/ui ──────────────────────> React / ReactDOM / lucide-react
```

强制规则：

| 来源 | 允许 | 禁止 |
| --- | --- | --- |
| `packages/ui/src` | React/ReactDOM、`lucide-react`、包内模块 | `frontend-core`、`apps/*`、router、i18n、业务类型、API、页面 CSS |
| `packages/frontend-core/src` | React hooks、Zustand、STOMP、`shared-types`、包内模块 | `ui`、`apps/*`、router、i18n、`.css`/`.module.css` |
| `apps/web/src/shared-widgets` | `ui`、`frontend-core`、包内 shared widget | `pc`、`mobile` |
| `apps/web/src/pc` | `ui`、`frontend-core`、`shared-widgets` | `mobile` |
| `apps/web/src/mobile` | `ui`、`frontend-core`、`shared-widgets` | `pc` |
| `apps/web/src/routes` | 两个平台 view、core、router、i18n | 直接复制 API 或业务状态机 |

## Target File Map

```text
fx-trading-platform/
  packages/
    ui/
      package.json
      tsconfig.json
      src/
        index.ts
        theme/{index.ts,theme.css,themes.ts,ThemeProvider.tsx}
        select-field/{SelectField.tsx,SelectField.module.css}
        icon-button/{IconButton.tsx,IconButton.module.css}
        state-surface/{StateSurface.tsx,StateSurface.module.css}
        skeleton/{Skeleton.tsx,Skeleton.module.css}
        data-view/{DataTable.tsx,DataCardList.tsx,DataView.module.css}
        dialog/{Dialog.tsx,Dialog.module.css}
        drawer/{Drawer.tsx,Drawer.module.css}
    frontend-core/
      package.json
      tsconfig.json
      src/
        index.ts
        api/{index.ts,apiClient.ts,accountApi.ts,authApi.ts,financeApi.ts,homeApi.ts,ledgerApi.ts,marketApi.ts,tradingApi.ts}
        auth/{index.ts,sessionStorage.ts}
        storage/{index.ts,browserStorage.ts}
        models/{index.ts,account.ts,table.ts,trading.ts}
        market/{index.ts,authoritativeMarketSnapshot.ts,binanceMarketData.ts,marketDataStore.ts,marketDataTypes.ts,marketFavorites.ts,marketStream.ts,marketSymbol.ts,quoteMarketDataAdapter.ts,tradingMarketAdapters.ts,tradingMarketApi.ts,tradingModels.ts,useMarketFavorites.ts}
        account/{index.ts,accountErrors.ts,accountOperations.ts,accountRefreshCoordinator.ts,accountSessionModels.ts,useAccountData.ts,useWalletController.ts}
        trading/{index.ts,format.ts,orderAdapter.ts,orderApi.ts,orderTypes.ts,orderValidation.ts,symbols.ts,tradingSession.ts,tradingSessionPositions.ts,tradingStore.ts,useTradeForm.ts,useTradeSubmit.ts,useTradingSession.ts,useTradingSettings.ts}
  apps/web/src/
    app/
      App.tsx
      AppShell.tsx
      device/{DeviceClassProvider.tsx,deviceClass.ts,deviceClass.test.ts}
      platform/{PlatformView.tsx,PlatformView.test.ts}
    routes/
      home/{HomeRoute.tsx,homeRoute.types.ts,useHomeRouteController.ts}
      auth/{AuthRoute.tsx,authRoute.types.ts,useAuthRouteController.ts}
      account/{AccountRoutes.tsx,accountRoute.types.ts,useAccountRouteController.ts}
      markets/{MarketsRoute.tsx,marketsRoute.types.ts,useMarketsRouteController.ts}
      orders/{OrdersRoute.tsx,ordersRoute.types.ts,useOrdersRouteController.ts}
      positions/{PositionsRoute.tsx,positionsRoute.types.ts,usePositionsRouteController.ts}
      wallet/{WalletRoute.tsx,walletRoute.types.ts,useWalletRouteController.ts}
      trading/{TradingRoute.tsx,tradingRoute.types.ts,useTradingRouteController.ts}
    pc/
      shell/{PcShellChrome.tsx,PcShellChrome.module.css}
      pages/{home,auth,account,markets,orders,positions,wallet,trading}/
    mobile/
      shell/{MobileShellChrome.tsx,MobileShellChrome.module.css}
      pages/{home,auth,account,markets,orders,positions,wallet,trading}/
    shared-widgets/
      asset/
      market/
      account/
      trading/
```

文件图是目标所有权，不要求机械地创建空目录。某个 `shared-widgets` 子目录只有在任务中找到至少两个相同消费者并写测试后才创建。

Package export map 固定为：

```json
{
  "@fx-platform/ui": [".", "./theme", "./theme.css"],
  "@fx-platform/frontend-core": [".", "./api", "./auth", "./storage", "./models", "./market", "./account", "./trading"]
}
```

每个 subpath 对应其 `src/<subpath>/index.ts`；CSS 只有 UI 包的 `./theme.css` 出口。应用不得深度 import package 内部文件。

## Route Contract

以下 URL 必须保持不变。内容路由在最终状态由 Route Controller + PC View + Mobile View 组成；redirect 与 fallback 路由保持纯重定向，并在两种设备 class 下验证相同目标：

| 组 | URL |
| --- | --- |
| Home | `/` |
| Trading redirects | `/trade`、`/trading`、无效 `/trade/:product/:symbol?` |
| Trading canonical | `/trade/spot/:symbol?`、`/trade/perpetual/:symbol?` |
| Auth | `/login`、`/register`、`/forgot-password`、`/two-factor-help` |
| Primary data | `/dashboard`、`/markets`、`/orders`、`/positions`、`/wallet` |
| Account redirect | `/account` -> `/account/overview` |
| Account | `/account/overview`、`/account/assets`、`/account/orders/funding`、`/account/orders/trades`、`/account/security/kyc`、`/account/settings` |
| Settings/security | `/security`、`/settings` |
| Fallback | `*` -> `/` |

Controller/View ownership is fixed:

- Route Controller owns API calls, subscriptions, auth/session resolution, loading/error state, filters, sort, pagination, selected records, form values, pending flags, dialogs/sheets that must survive a platform switch, and all business commands.
- PC/Mobile View owns DOM refs, focus, hover, animation and platform-only expansion state that is safe to reset.
- A view receives one read-only model plus typed callback commands; it does not construct request payloads beyond passing typed user input to a controller command.
- Router params/query/navigation and translation adapters stay in the route layer; reusable business state/hook implementations live in `frontend-core`.

## Worktree Reconciliation Protocol

每个任务开始时执行：

```powershell
Set-Location C:\workspace\tradingWeb
git status --short
git diff --name-status
git diff --cached --name-status
git log -12 --oneline
```

对任务列出的每个 dirty 路径执行 `git diff -- <path>` 并分类：

1. **不重叠：** 保持原样，不加入本任务暂存区。
2. **同目标重叠：** 以当前工作树为输入，补测试后语义合并；不得从 HEAD 重新生成文件。
3. **业务合同重叠：** 追踪调用者、请求 payload、存储键和测试；先固定现有行为，再移动所有权。
4. **文本冲突但意图兼容：** 合并两方行为并增加覆盖二者的测试。
5. **意图真正互斥：** 按“业务链路与现有测试优先”自动选取最小架构调整，在任务报告中记录取舍；只有会改变外部业务合同或造成不可逆数据影响时才请求用户。

暂存前执行。对同一文件中可分离的无关既有 hunks，使用 `git add -p -- <path>` 或等价的 index-only patch 只暂存任务 hunks；不得用整文件暂存把无关 backend/docs/script 改动带入提交：

```powershell
git diff --check
git diff --cached --check
git diff --cached --name-only
```

输出必须证明暂存区没有 backend、admin 或无关用户文件。

## Common Verification Commands

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:frontend-boundaries"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:typecheck"
```

Task 1 建立两个 package 后，每个阶段都执行这些命令；除 Phase 10 前已记录的 bundle/large-file 债务外，所列命令必须退出 0。页面迁移阶段运行当前完整 `smoke:visual-qa` 和该路由的 Node 测试；现有脚本没有 route filter，因此不要为了缩短中间验证提前改写 dirty 脚本。Task 16 再语义合并并扩展完整矩阵。

## Testing Strategy

- 继续使用仓库现有 Node test runner，不为本次整理引入第二套测试框架。
- 纯模型、状态转换、键盘决策、设备判断和 Controller 使用可直接执行的 `.test.ts` 单元测试。
- TSX 所有权、import、ARIA 与组合合同可沿用现有 source-contract 测试；真实点击、键盘、focus、resize 和视觉行为由现有 Chrome DevTools Protocol smoke 扩展验证。
- 新 package 的 `test` 脚本只收集 `.test.ts`，不要求 Node 直接解析 `.tsx` 测试文件。
- 每个迁移测试先指向新 owner 并观察“缺少模块/export/行为”的失败，再移动实现；不得仅在迁移后补一条始终为绿的源码断言。
- 现有 smoke 使用的 ready/class hooks 在中间阶段可作为无样式兼容标记保留；Task 16 把脚本改为稳定的 `data-*`/ARIA 合同后再删除这些临时 class hooks。

---

### Task 1: Phase 0 — Capture the Baseline and Enforce Frontend Boundaries

**Files:**

- Create: `fx-trading-platform/scripts/verify-frontend-boundaries.mjs`
- Create: `fx-trading-platform/scripts/verify-frontend-boundaries.test.mjs`
- Create: `fx-trading-platform/packages/ui/{package.json,tsconfig.json,src/index.ts,src/package-contract.test.ts}`
- Create: `fx-trading-platform/packages/frontend-core/{package.json,tsconfig.json,src/index.ts,src/package-contract.test.ts}`
- Modify: `fx-trading-platform/package.json`
- Modify: `fx-trading-platform/package-lock.json`
- Modify: `fx-trading-platform/scripts/verify-architecture.mjs`
- Modify: `fx-trading-platform/docs/architecture.md`

**Contracts:**

- Export `findFrontendBoundaryViolations(rootDir: string): string[]` from the verifier.
- Add root scripts `test:frontend-boundaries`, `verify:frontend-boundaries`, `ui:test`, `ui:typecheck`, `frontend-core:test`, `frontend-core:typecheck`, and `frontend:check`.
- `frontend:check` runs package tests/typechecks, `web:test`, `web:build`, both architecture checks, bundle budget and large-file audit. It is expected to report the two known debt failures until Phase 10 closes them.

```json
{
  "test:frontend-boundaries": "node --test scripts/verify-frontend-boundaries.test.mjs",
  "verify:frontend-boundaries": "node scripts/verify-frontend-boundaries.mjs",
  "ui:test": "npm --workspace packages/ui run test",
  "ui:typecheck": "npm --workspace packages/ui run typecheck",
  "frontend-core:test": "npm --workspace packages/frontend-core run test",
  "frontend-core:typecheck": "npm --workspace packages/frontend-core run typecheck",
  "frontend:check": "npm run ui:test && npm run ui:typecheck && npm run frontend-core:test && npm run frontend-core:typecheck && npm run web:test && npm run web:build && npm run verify:frontend-boundaries && npm run verify:architecture && npm run web:bundle-budget && npm run audit:large-files"
}
```

- [ ] **Step 1: Re-measure the baseline without changing application code**

Run the three current green gates and the two known debt gates. Record exact exit codes in the phase handoff. A changed count is acceptable; a newly failing `web:test`, `web:build` or `verify:architecture` must be understood before continuing.

- [ ] **Step 2: Write failing fixture tests for every dependency rule**

Use temporary fixture directories. Cover static import, export-from, dynamic import, Windows separators, allowed intra-layer imports, CSS import rejection in core, PC/Mobile cross-imports and rejection of `@fx-platform/ui/src/*` / `@fx-platform/frontend-core/src/*` deep imports. Assert deterministic sorted violation messages containing source and target.

- [ ] **Step 3: Implement the dependency scanner**

Scan `.ts`, `.tsx`, `.js`, `.jsx`, `.mjs`; ignore `dist`, `node_modules`, coverage and declaration files. Resolve relative paths before applying the matrix. Do not add a parser dependency solely for this scanner; support the import forms covered by tests.

- [ ] **Step 4: Create both minimal package skeletons and install workspace links**

Both packages must have valid source exports, independent `test`/`typecheck` scripts and a package-contract test. `@fx-platform/ui` declares React/ReactDOM and `lucide-react` as peer/dev dependencies; `@fx-platform/frontend-core` initially depends only on `@fx-platform/shared-types` and adds React/Zustand/STOMP when moved modules first require them. Run `npm.cmd --prefix fx-trading-platform install --ignore-scripts` so the lockfile and workspace links are current.

- [ ] **Step 5: Integrate it into architecture verification**

`verify:architecture` must invoke or share the new scanner and fail on violations. Document the dependency table and 900px rule in `docs/architecture.md`, preserving unrelated current edits by semantic merge.

- [ ] **Step 6: Verify and commit**

```powershell
node --test fx-trading-platform/scripts/verify-frontend-boundaries.test.mjs
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:frontend-boundaries"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
git add -- fx-trading-platform/scripts/verify-frontend-boundaries.mjs fx-trading-platform/scripts/verify-frontend-boundaries.test.mjs fx-trading-platform/packages/ui fx-trading-platform/packages/frontend-core fx-trading-platform/package.json fx-trading-platform/package-lock.json fx-trading-platform/scripts/verify-architecture.mjs fx-trading-platform/docs/architecture.md
git diff --cached --name-only
git commit -m "test(frontend): enforce target dependency boundaries"
```

Expected: dependency fixture tests fail before implementation and pass after it; current source passes rules that apply to existing directories; future directory rules are active as soon as paths appear.

---

### Task 2: Phase 1 — Create `@fx-platform/ui` and Move the Theme

**Files:**

- Modify: `fx-trading-platform/packages/ui/package.json`
- Modify: `fx-trading-platform/packages/ui/tsconfig.json` only if theme tests require DOM library types
- Move: `apps/web/src/design-system/theme/themes.ts` -> `packages/ui/src/theme/themes.ts`
- Move: `apps/web/src/design-system/theme/themes.test.ts` -> `packages/ui/src/theme/themes.test.ts`
- Move: `apps/web/src/design-system/theme/ThemeProvider.tsx` -> `packages/ui/src/theme/ThemeProvider.tsx`
- Move: `apps/web/src/design-system/theme/ThemeProvider.test.ts` -> `packages/ui/src/theme/ThemeProvider.test.ts`
- Move: `apps/web/src/design-system/theme/theme.css` -> `packages/ui/src/theme/theme.css`
- Create: `packages/ui/src/theme/index.ts`
- Modify: `packages/ui/src/index.ts`
- Modify: `apps/web/src/main.tsx`
- Modify: `apps/web/package.json`
- Modify: root `package.json` and `package-lock.json`

**Public API:**

```ts
export type { TradingTheme, TradingThemeId, TradingThemeTokens } from './theme/themes'
export { defaultThemeId, getTradingTheme, tradingThemes } from './theme/themes'
export { ThemeProvider, applyThemeToDocument, useTheme } from './theme/ThemeProvider'
```

- [ ] **Step 1: Add package contract tests first**

Assert the two theme IDs, every existing token value, fallback behavior, local-storage key `fx-trading-theme-mode`, document dataset and CSS variable application. The first run must fail because the package does not exist.

- [ ] **Step 2: Create the workspace package**

Set `name` to `@fx-platform/ui`, `private: true`, `type: module`; expose source entry points for Vite and configure `test`/`typecheck`. Declare React/ReactDOM as peer dependencies and dev dependencies. Do not add router, i18n or business packages.

- [ ] **Step 3: Move rather than copy the theme**

Use the current worktree versions as source. Keep token names, values, storage behavior and provider order unchanged. Update `main.tsx` to import `ThemeProvider` from `@fx-platform/ui` and `@fx-platform/ui/theme.css` before app styles.

- [ ] **Step 4: Verify and commit**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform install --ignore-scripts"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:frontend-boundaries"
git add -- fx-trading-platform/packages/ui fx-trading-platform/apps/web/src/main.tsx fx-trading-platform/apps/web/package.json fx-trading-platform/package.json fx-trading-platform/package-lock.json fx-trading-platform/apps/web/src/design-system/theme
git commit -m "refactor(ui): extract shared theme package"
```

Expected: old theme implementation path is absent; theme behavior tests and current web tests pass.

---

### Task 3: Phase 2A — Extract `SelectField` and `IconButton`

**Files:**

- Move: `apps/web/src/components/SelectField.tsx` -> `packages/ui/src/select-field/SelectField.tsx`
- Create: `packages/ui/src/select-field/SelectField.module.css`
- Move/rename: `apps/web/src/design-system/components/TerminalIconButton.tsx` -> `packages/ui/src/icon-button/IconButton.tsx`
- Move/rename: `TerminalIconButton.module.css` -> `packages/ui/src/icon-button/IconButton.module.css`
- Create tests under each package component directory
- Modify consumers: `LanguageSwitcher.tsx`, `MarketsPage.tsx`, trading toolbar consumers
- Modify: `apps/web/src/styles.css` to remove migrated `select-field*` selectors only
- Modify: `packages/ui/src/index.ts`

**Public API:**

```ts
export type SelectFieldOption<T extends string = string> = { label: string; value: T }
export type SelectFieldProps<T extends string = string> = {
  ariaLabel?: string
  className?: string
  labelledBy?: string
  onChange: (value: T) => void
  options: readonly SelectFieldOption<T>[]
  value: T
}
export function SelectField<T extends string>(props: SelectFieldProps<T>): JSX.Element

export type IconButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  icon: ReactNode
  label: string
  tone?: 'neutral' | 'primary'
}
```

- [ ] **Step 1: Move existing behavior contracts into the UI package and make them fail against the missing exports**

Extract the menu key/state transition into a pure helper for Node unit tests. Cover click selection through CDP integration, outside click, Escape/Tab, ArrowUp/ArrowDown wrapping, Home/End, Enter/Space, disabled empty options, `aria-haspopup`, `aria-expanded`, listbox/option and labelled-by behavior. Cover IconButton `type=button`, accessible label, title, disabled and custom class with source/render contracts.

- [ ] **Step 2: Migrate CSS from global selectors into CSS Modules**

Preserve computed values and visual states. Consumers may pass layout classes, but component internals must not depend on app-global selectors.

- [ ] **Step 3: Update all consumers and delete old implementations**

Use `rg` to prove no imports reference `components/SelectField` or `design-system/components/TerminalIconButton`.

- [ ] **Step 4: Verify and commit**

Run UI tests/typecheck, web tests/build and both architecture checks. Commit:

```powershell
git commit -m "refactor(ui): extract select and icon button primitives"
```

---

### Task 4: Phase 2B — Extract State, Skeleton, Data View, Dialog and Drawer Primitives

**Files:**

- Create: `packages/ui/src/state-surface/{StateSurface.tsx,StateSurface.module.css,StateSurface.test.ts}`
- Create: `packages/ui/src/skeleton/{Skeleton.tsx,Skeleton.module.css,Skeleton.test.ts}`
- Create: `packages/ui/src/data-view/{DataTable.tsx,DataCardList.tsx,DataView.module.css,DataView.test.ts}`
- Create: `packages/ui/src/dialog/{Dialog.tsx,Dialog.module.css,Dialog.test.ts}`
- Create: `packages/ui/src/drawer/{Drawer.tsx,Drawer.module.css,Drawer.test.ts}`
- Modify: `apps/web/src/components/user-page/{PageState.tsx,DataTable.tsx,userPageUi.test.ts}`
- Modify: `apps/web/src/components/loading/{TerminalSkeleton.tsx,TerminalSkeleton.module.css,TerminalSkeleton.test.ts}`
- Modify existing dialog consumers in wallet, positions and trading
- Modify: `apps/web/src/pages/trading/components/{MobilePanels.tsx,MobilePanels.module.css}`
- Modify: `apps/web/src/styles.css` to remove only the state/data/dialog selectors now owned by UI modules
- Modify: `packages/ui/src/index.ts`

**Contracts:**

- `StateSurface` receives all title/message/action strings as props and exposes `default|empty|login|error|loading` variants; no translation import.
- `Skeleton` is an aria-hidden visual block with `shape: 'line' | 'block' | 'circle'`, optional size/class props and `prefers-reduced-motion` handling. App-owned `TerminalSkeleton` and `StateSurface` compose it; branded `ExchangeLoading` stays in the app.
- `DataTable<T>` renders a semantic desktop table from controlled rows/columns/sort callbacks.
- `DataCardList<T>` renders the same controlled data as mobile cards; it does not use media queries to choose itself.
- Pagination and sort labels are explicit props. `apps/web` adapters may temporarily translate and compose both components until route migration.
- `Dialog` owns modal semantics, labelled-by, Escape, backdrop close policy and focus return; domain form fields and business validation stay in app/core.
- `Drawer` owns `open`, `title`, `side: 'left' | 'right'`, backdrop/Escape/focus and children. It has two current consumers: trading market drawer and quote drawer.
- `MobileOrderSheet` remains in the app because it has one current consumer. It may move only if a second identical consumer appears before Task 15; otherwise it becomes Mobile trading-owned code.

- [ ] **Step 1: Write package behavior tests and source-boundary tests**

Tests must prove no `react-i18next` import, correct table semantics/`aria-sort`, empty rendering, pure pagination/sort transitions, Skeleton shape/size classes and reduced-motion CSS. Extract dialog/drawer close decisions into a pure helper; validate Escape/backdrop/pending-close, side class and focus behavior in the CDP integration slice.

- [ ] **Step 2: Build generic components and thin app adapters**

Keep current `PageState.tsx` and `DataTable.tsx` only as translation/composition adapters during migration. Mark their removal in Task 16; do not duplicate sort/pagination algorithms.

- [ ] **Step 3: Convert at least two current dialogs to the same primitive**

Use `TransferDialog` plus `OrderConfirmationDialog` or `PositionActionDialog`; preserve their domain-specific class hooks when necessary. If focus behavior exposes an existing defect, add a regression test and fix within the primitive without changing business actions.

Replace the internal `MobileDrawer` implementation with the package primitive for both market and quote usages. Keep a temporary app adapter only while current trading imports migrate.

- [ ] **Step 4: Verify and commit**

Run UI tests/typecheck, relevant wallet/position/trading tests, full web tests/build and boundaries. Commit:

```powershell
git commit -m "refactor(ui): extract shared state data and dialog primitives"
```

---

### Task 5: Phase 3A — Create `@fx-platform/frontend-core` and Move API, Auth and Models

**Files:**

- Modify: `packages/frontend-core/{package.json,tsconfig.json,src/index.ts}`
- Move services: `apiClient.ts`, `accountApi.ts`, `authApi.ts`, `financeApi.ts`, `homeApi.ts`, `ledgerApi.ts`, `tradingApi.ts` into `packages/frontend-core/src/api/`
- Move: `features/trading-session/tradingSessionStorage.ts` -> `packages/frontend-core/src/auth/sessionStorage.ts`
- Create: `packages/frontend-core/src/storage/{browserStorage.ts,index.ts}`
- Move: `apps/web/src/types/trading.ts` -> `packages/frontend-core/src/models/trading.ts`
- Move: `apps/web/src/components/tables/types.ts` -> `packages/frontend-core/src/models/account.ts`
- Split pure functions from `components/user-page/userPageModels.ts` into `packages/frontend-core/src/models/table.ts`
- Create barrel files for `api`, `auth`, `models`
- Modify all current consumers and source-reading tests
- Modify workspace package files and lockfile

**Contracts:**

- Endpoints, HTTP methods, payloads, `ApiClientError`, request ID propagation, one-time 401 refresh and token clearing stay byte-for-byte equivalent in behavior.
- Keep storage keys `fx-platform-auth-token`, `fx-platform-auth-refresh-token`, legacy migration from `fx-platform-demo-token`, and event `fx-platform-auth-session-changed`.
- Core may access browser storage/fetch through narrow defaults but must accept injected `Storage` in existing storage functions for tests.
- `browserStorage.ts` owns `KeyValueStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>` and safe access to `globalThis.localStorage`; auth and favorites code reuse this interface rather than defining competing storage abstractions.
- `formatApiError`, translated titles and app links remain in `apps/web`; pure sort/filter/paginate/number conversion moves to core.

- [ ] **Step 1: Add package tests before moving code**

Move existing API/auth/model tests and add explicit tests for refresh retry, failed refresh clearing, request ID, legacy token migration, blocked localStorage and endpoint payload stability.

- [ ] **Step 2: Create package and move current implementations**

Do not copy. Update imports incrementally until web and core tests compile. The package can depend on React only when a moved hook first requires it; this task should remain model/API only.

Run `npm.cmd --prefix fx-trading-platform install --ignore-scripts` after adding the workspace dependency so `node_modules` workspace links and `package-lock.json` are both current.

- [ ] **Step 3: Prove old ownership is gone**

```powershell
rg -n "src/services|\.\./services|components/tables/types|types/trading|tradingSessionStorage" fx-trading-platform/apps/web/src fx-trading-platform/packages/frontend-core/src
```

Expected: no implementation import uses old paths; temporary app adapters are permitted only if they contain app-specific translation, not re-exported core code.

- [ ] **Step 4: Verify and commit**

Run core tests/typecheck, web tests/build and boundaries. Commit:

```powershell
git commit -m "refactor(core): extract frontend api auth and models"
```

---

### Task 6: Phase 3B — Move the Market Runtime into `frontend-core`

**Files:**

- Move all files from `apps/web/src/features/market/` to `packages/frontend-core/src/market/`
- Move: `apps/web/src/services/marketApi.ts` -> `packages/frontend-core/src/api/marketApi.ts`
- Move: `apps/web/src/services/marketStream.ts` and test -> `packages/frontend-core/src/market/marketStream.ts` and test
- Move: `apps/web/src/utils/marketSymbol.ts` -> `packages/frontend-core/src/market/marketSymbol.ts`
- Delete app compatibility re-exports `components/market-side-panel/marketDataStore.ts` and `quoteMarketDataAdapter.ts` after consumers migrate
- Modify Home, Markets, Trading, MarketSidePanel and tests

**Required market exports:**

`authoritativeMarketSnapshot`, `binanceMarketData`, `marketDataStore`, `marketDataTypes`, `marketFavorites`, `quoteMarketDataAdapter`, `tradingMarketAdapters`, `tradingMarketApi`, `tradingModels`, `useMarketFavorites`, `marketStream`, and `marketSymbol` using their existing exported names.

- [ ] **Step 1: Move the complete existing market test set first**

Tests must preserve authoritative snapshot selection, source status, provider/source changes, favorites persistence, symbol normalization, quote/order-book adaptation, REST initial load, STOMP incremental updates, reconnection and fallback semantics.

- [ ] **Step 2: Move the complete runtime as one dependency unit**

Fix imports only; do not rename response fields or alter timing constants. Reuse `storage/KeyValueStorage` for favorites without changing its keys or failure handling. `marketDataStore` may keep its React `useSyncExternalStore` hook, so add React as a core peer dependency at this point; add STOMP when `marketStream` moves.

- [ ] **Step 3: Remove old imports and re-exports**

Use `rg` to prove `apps/web/src/features/market` and the two market-side-panel re-export files are no longer referenced, then delete them.

- [ ] **Step 4: Verify and commit**

Run core tests/typecheck, web tests/build, architecture, boundaries. Commit:

```powershell
git commit -m "refactor(core): move market runtime into frontend core"
```

---

### Task 7: Phase 4 — Extract Account and Wallet Core

**Files:**

- Move: `features/trading-session/accountRefreshCoordinator.ts` and test -> `packages/frontend-core/src/account/`
- Move/split: `tradingSessionModels.ts` -> `packages/frontend-core/src/account/accountSessionModels.ts`
- Create: `packages/frontend-core/src/account/accountErrors.ts`
- Create: `packages/frontend-core/src/account/accountOperations.ts`
- Create: `packages/frontend-core/src/account/useAccountData.ts`
- Create: `packages/frontend-core/src/account/useWalletController.ts`
- Modify Dashboard, Account pages, Orders, Positions, Wallet and trading session consumers
- Add `packages/frontend-core/src/account/index.ts`

**Contracts:**

```ts
export type CoreMessage = {
  key: string
  values?: Record<string, string | number>
}

export type AccountDataState<T> =
  | { status: 'loading'; data?: T }
  | { status: 'login-required' }
  | { status: 'ready'; data: T }
  | { status: 'error'; error: ApiClientError; message: CoreMessage }
```

- Account identity, balances, ledger, funding, trade history, transfer and demo reset results must remain consistent.
- Core returns message descriptors; route/views translate them.
- `wallet balance`、`asset ledger`、`account summary` must refresh together after transfer/reset.

- [ ] **Step 1: Add failing orchestration tests**

Mock only package API functions. Cover unauthenticated state, first-or-created account, concurrent refresh coalescing, stale response suppression, transfer direction/amount, reset, refresh-after-mutation and partial API errors.

- [ ] **Step 2: Extract operations from pages/session without moving markup**

Pages temporarily consume core hooks through their current components. Do not put navigation or translated strings in core.

- [ ] **Step 3: Run wallet/account regression**

Run core tests, `web:test`, `web:build`. If the demo backend is available, also run `smoke:user-core-pages`; otherwise record the environment prerequisite and keep the final run mandatory in Task 16.

- [ ] **Step 4: Commit**

```powershell
git commit -m "refactor(core): extract account and wallet controllers"
```

---

### Task 8: Phase 5A — Extract Trading Form, Validation and Order Operations

**Files:**

- Move: `features/trading/types/order.ts` -> `packages/frontend-core/src/trading/orderTypes.ts`
- Move: `features/trading/utils/{format.ts,symbols.ts,tradingUtils.test.ts}` -> `packages/frontend-core/src/trading/`
- Move: `features/trading/services/{orderAdapter.ts,orderAdapter.test.ts,orderApi.ts,orderApi.test.ts}` -> `packages/frontend-core/src/trading/`
- Move/refactor: `hooks/useOrderValidation.ts` and tests -> `packages/frontend-core/src/trading/orderValidation.ts` and tests
- Move/refactor: `hooks/useTradeForm.ts` and tests -> `packages/frontend-core/src/trading/useTradeForm.ts` and tests
- Move/refactor: `hooks/useTradePanelSubmit.ts` -> `packages/frontend-core/src/trading/useTradeSubmit.ts`
- Move: `features/trading-settings/useTradingSettings.ts` and test -> `packages/frontend-core/src/trading/useTradingSettings.ts`

**Behavior invariants:**

- Preserve spot/perpetual, side, order type, quantity unit, margin mode, leverage, reduce-only, trigger type, attached TP/SL and OCO mappings.
- Preserve front-end validation keys and numeric precision, but represent messages as `CoreMessage` rather than translated strings.
- Preserve the dirty-worktree idempotency behavior: each submit creates a random UUID `idempotencyKey`; it is not reused as `clientOrderId`; both standard and OCO payloads are covered.
- Submit errors preserve `ApiClientError.code`, `status`, `requestId` and user-visible translation key.

- [ ] **Step 1: Freeze current behavior in package tests**

Copy no implementation until all payload matrix and dirty-worktree UUID tests execute from the future package path and fail for missing exports.

- [ ] **Step 2: Remove i18n from form and submit hooks**

Replace `TFunction`/`useTranslation` dependencies with message descriptors. Add a thin app helper `translateCoreMessage` under `apps/web/src/routes/shared/` only when views need strings.

- [ ] **Step 3: Update current TradePanel consumer**

Keep markup unchanged. Verify all existing TradePanel and P0 control tests.

- [ ] **Step 4: Verify and commit**

Run targeted core tests, full core/web gates and boundaries. Commit:

```powershell
git commit -m "refactor(core): extract trading form and order operations"
```

---

### Task 9: Phase 5B — Extract the Trading Session and Route Controller Contract

**Files:**

- Move/refactor: `features/trading-session/tradingSession.ts` and test -> `packages/frontend-core/src/trading/tradingSession.ts`
- Move: `tradingSessionPositions.ts` -> `packages/frontend-core/src/trading/tradingSessionPositions.ts`
- Move/refactor: `useTradingSession.ts` -> `packages/frontend-core/src/trading/useTradingSession.ts`
- Move: `apps/web/src/stores/tradingStore.ts` -> `packages/frontend-core/src/trading/tradingStore.ts`
- Move/refactor: `pages/trading/tradingPageViewModels.ts` -> `apps/web/src/routes/trading/tradingRoute.types.ts`
- Create: `apps/web/src/routes/trading/useTradingRouteController.ts`
- Add/update tests for session and route model

**Controller contract:**

```ts
/** Temporary name used only while the current views are migrated in Task 15. */
export type TradingTerminalViewProps = TradingRouteModel & {
  workspaceLayoutControls: TradingWorkspaceLayoutControls
}
```

Rename the current `TradingTerminalViewProps` type declaration to `TradingRouteModel` and move its common field body from `tradingPageViewModels.ts` without dropping or renaming account panel/account ID/balances, chart settings/callbacks/theme/title/indicators, login/session state, market/markets/favorites/status, navigation and market actions, product/quote/quotes, order/OCO submitters, perpetual controls, symbol/rules/precision/prefill and token. Remove the PC-only `workspaceLayoutControls` field from the common model and add it back only in the temporary compatibility type shown above while the old desktop view migrates. Both views receive the same `TradingRouteModel` object. Router params, navigation, translation and platform layout state remain in the app route adapter/view; core owns account/order/position refresh, subscriptions and mutations. In Task 15, `PcTradingTerminal` owns `useResizableLayout`, Mobile never imports it, and the temporary compatibility type is deleted.

- [ ] **Step 1: Add tests for session continuity and business operations**

Cover login-required/loading/ready/error, refresh intervals, STOMP refresh, reconnect, submit/OCO, cancel all, close all, close position, update protection and repricing.

- [ ] **Step 2: Refactor `formatTradingSessionError`**

Remove `TFunction`; return a core message descriptor while preserving fallback and request ID data.

- [ ] **Step 3: Build the route controller behind the current TradingPage**

Current `TradingPage.tsx` consumes `useTradingRouteController` and adapts the model to its existing desktop/mobile view props. Do not split layout yet. This isolates behavior before structural UI work.

- [ ] **Step 4: Verify and commit**

Run every trading/session/market test, core gates, full web test/build and boundaries. Commit:

```powershell
git commit -m "refactor(core): extract trading session controller"
```

---

### Task 10: Phase 6 — Add the Stable 900px Adaptive Runtime and Split Shell Chrome

**Files:**

- Create: `apps/web/src/app/device/deviceClass.ts`
- Create: `apps/web/src/app/device/deviceClass.test.ts`
- Create: `apps/web/src/app/device/DeviceClassProvider.tsx`
- Create: `apps/web/src/app/platform/PlatformView.tsx`
- Create: `apps/web/src/app/platform/PlatformView.test.ts`
- Create: `apps/web/src/pc/shell/{PcShellChrome.tsx,PcShellChrome.module.css}`
- Create: `apps/web/src/mobile/shell/{MobileShellChrome.tsx,MobileShellChrome.module.css}`
- Modify: `apps/web/src/main.tsx`, `app/AppShell.tsx`, `app/App.test.ts`, navigation tests
- Delete after migration: `pages/trading/useMobileTerminalViewport.ts`

**Runtime API:**

```ts
export const mobileViewportQuery = '(max-width: 900px)'
export type DeviceClass = 'mobile' | 'pc'
export function getDeviceClass(matchesMobile: boolean): DeviceClass
export function useDeviceClass(): DeviceClass
export function PlatformView<T>(props: {
  model: T
  pc: ComponentType<{ model: T }> | LazyExoticComponent<ComponentType<{ model: T }>>
  mobile: ComponentType<{ model: T }> | LazyExoticComponent<ComponentType<{ model: T }>>
  fallback: ReactNode
}): ReactNode
```

- [ ] **Step 1: Write boundary tests for 899/900/901 and runtime changes**

Use a fake `MediaQueryList`. Assert 899 and 900 are mobile, 901 is PC, listener cleanup works, and resizing does not reset a stateful controller rendered above `PlatformView`.

- [ ] **Step 2: Implement one device source**

Use `matchMedia` subscription with an SSR/test fallback. Do not read `window.innerWidth` independently in pages.

- [ ] **Step 3: Split only shell chrome**

`AppShell` remains the stable owner of session lookup, route classification and logout. Render `PcShellChrome` or `MobileShellChrome` as siblings around a stable `<main>`; PC owns topbar, Mobile owns mobile tabs. Do not conditionally replace the provider/router/controller tree.

- [ ] **Step 4: Add diagnostic platform markers**

Each active view root exposes `data-platform-view="pc|mobile"`; these markers are used only by tests and visual smoke.

- [ ] **Step 5: Prove route navigation remains stable**

Add App tests for direct deep links, refresh-equivalent rerender, browser back/forward, existing query strings and canonical redirects under both device classes. `PlatformView` renders only the active component inside `Suspense`; it never mounts both and hides one with CSS.

- [ ] **Step 6: Verify and commit**

Run app/device tests, full web gates and a manual/smoke resize 901 -> 900 -> 899 -> 901. Commit:

```powershell
git commit -m "refactor(web): add stable pc mobile runtime"
```

---

### Task 11: Phase 7 — Migrate Home and Auth to Two Views

**Files:**

- Create: `routes/home/{HomeRoute.tsx,homeRoute.types.ts,useHomeRouteController.ts}`
- Create: `pc/pages/home/{PcHomePage.tsx,PcHomePage.module.css}`
- Create: `mobile/pages/home/{MobileHomePage.tsx,MobileHomePage.module.css}`
- Create: `routes/auth/{AuthRoute.tsx,authRoute.types.ts,useAuthRouteController.ts}`
- Create: `pc/pages/auth/{PcLoginPage.tsx,PcRegisterPage.tsx,PcForgotPasswordPage.tsx,PcTwoFactorHelpPage.tsx,PcAuthPages.module.css}`
- Create: `mobile/pages/auth/{MobileLoginPage.tsx,MobileRegisterPage.tsx,MobileForgotPasswordPage.tsx,MobileTwoFactorHelpPage.tsx,MobileAuthPages.module.css}`
- Move: `apps/web/src/components/asset/{AssetMark.tsx,AssetMark.module.css,assetMarkModel.ts,assetMarkModel.test.ts}` -> `apps/web/src/shared-widgets/asset/`
- Move identical home content widgets with two consumers into `shared-widgets/home/` only if PC and Mobile render the same DOM contract
- Modify: `app/App.tsx`, home/auth tests
- Delete migrated legacy home/login implementations after all imports move

**Controller rules:**

- Home controller owns auth variant, counter data, market/news data and loading/error state.
- Auth controller owns form state, validation, login/register calls, token writes and redirect target.
- Views own platform layout and translated labels; they do not call API/storage directly.
- `AssetMark` is a domain widget, not a UI primitive; move it once and update Home, Account, Markets, Orders, Positions, Wallet and Trading imports without changing its model or CSS.

- [ ] **Step 1: Add route contract tests**

For every URL, render at PC and Mobile device classes and assert the correct root marker, preserved links, form labels, submit actions, redirect query and auth variants.

- [ ] **Step 2: Transplant current desktop and responsive mobile DOM into separate views**

PC output at 1440x900 and Mobile output at 390x844 must match current content and actions. Move selectors from `styles.css`/old modules into the corresponding platform module. At route-module scope declare `const PcHomePage = lazy(() => import('../../pc/pages/home/PcHomePage').then((module) => ({ default: module.PcHomePage })))` and `const MobileHomePage = lazy(() => import('../../mobile/pages/home/MobileHomePage').then((module) => ({ default: module.MobileHomePage })))`, then pass them to `PlatformView`; source tests must prove there are no static platform-view imports.

- [ ] **Step 3: Verify route slice and commit**

Run targeted tests, web gates and visual smoke for `/`, `/login`, `/register`, `/forgot-password`, `/two-factor-help` at 1440x900, 390x844, 899, 900 and 901 widths. Commit:

```powershell
git commit -m "refactor(web): split home and auth platform views"
```

---

### Task 12: Phase 8 — Migrate Dashboard, Account, Security and Settings

**Files:**

- Create/complete `routes/account/{AccountRoutes.tsx,accountRoute.types.ts,useAccountRouteController.ts}`
- Create: `pc/pages/account/{PcDashboardPage.tsx,PcAccountOverviewPage.tsx,PcAccountAssetsPage.tsx,PcFundingRecordsPage.tsx,PcTradeRecordsPage.tsx,PcKycPage.tsx,PcAccountSettingsPage.tsx,PcSecurityCenterPage.tsx,PcSettingsPage.tsx,PcAccountPages.module.css}`
- Create: `mobile/pages/account/{MobileDashboardPage.tsx,MobileAccountOverviewPage.tsx,MobileAccountAssetsPage.tsx,MobileFundingRecordsPage.tsx,MobileTradeRecordsPage.tsx,MobileKycPage.tsx,MobileAccountSettingsPage.tsx,MobileSecurityCenterPage.tsx,MobileSettingsPage.tsx,MobileAccountPages.module.css}`
- Create shared account widgets only for identical summary/asset/state content used by both platforms
- Modify `app/App.tsx` and all current account/dashboard/security/settings tests
- Delete the corresponding legacy page files after imports move

**Business invariants:**

- Guest protected-state and `/login?redirect=<route>` behavior remain unchanged.
- Authenticated account ID, balances, KYC/security state and settings persistence remain unchanged.
- `/account` still redirects to `/account/overview` with `replace`.

- [ ] **Step 1: Add PC/Mobile route-model tests for all nine views**

Assert loading, login-required, ready, empty and API error states; ensure the same core action is invoked from both views.

- [ ] **Step 2: Split layout and styles**

Use `DataTable` in PC views and `DataCardList` in Mobile views explicitly. Remove structural table/card media-query switching from adapters once all consumers in this phase migrate. Every route lazy-loads its PC and Mobile page modules independently.

- [ ] **Step 3: Verify and commit**

Run targeted tests, full web gates, authenticated/guest visual smoke and resize continuity. Commit:

```powershell
git commit -m "refactor(web): split account and settings platform views"
```

---

### Task 13: Phase 9A — Migrate Markets

**Files:**

- Create: `routes/markets/{MarketsRoute.tsx,marketsRoute.types.ts,useMarketsRouteController.ts}`
- Create: `pc/pages/markets/{PcMarketsPage.tsx,PcMarketsPage.module.css}`
- Create: `mobile/pages/markets/{MobileMarketsPage.tsx,MobileMarketsPage.module.css}`
- Move shared market row/asset/price widgets with two consumers into `shared-widgets/market/`
- Move pure route target helper `marketTradingTarget.ts` to the route directory
- Modify `app/App.tsx` and market tests
- Delete legacy `MarketsPage.tsx` after migration

- [ ] **Step 1: Freeze the market model and navigation contract**

Tests cover tabs/filter/sort/favorites, source/status, loading/error/empty, spot/perpetual target resolution and current symbol formatting.

- [ ] **Step 2: Build platform-specific views**

PC uses dense ranking/table layout; Mobile uses touch-sized list/cards. Both consume one controller and market store; neither opens a second market subscription. Both view modules are independent dynamic imports.

- [ ] **Step 3: Verify and commit**

Run core market tests, market page tests, web gates and viewport matrix. Commit:

```powershell
git commit -m "refactor(web): split markets platform views"
```

---

### Task 14: Phase 9B — Migrate Orders, Positions and Wallet

**Files:**

- Create: `routes/orders/{OrdersRoute.tsx,ordersRoute.types.ts,useOrdersRouteController.ts}`
- Create: `routes/positions/{PositionsRoute.tsx,positionsRoute.types.ts,usePositionsRouteController.ts}`
- Create: `routes/wallet/{WalletRoute.tsx,walletRoute.types.ts,useWalletRouteController.ts}`
- Create: `pc/pages/orders/{PcOrdersPage.tsx,PcOrdersPage.module.css}` and `mobile/pages/orders/{MobileOrdersPage.tsx,MobileOrdersPage.module.css}`
- Create: `pc/pages/positions/{PcPositionsPage.tsx,PcPositionsPage.module.css}` and `mobile/pages/positions/{MobilePositionsPage.tsx,MobilePositionsPage.module.css}`
- Create: `pc/pages/wallet/{PcWalletPage.tsx,PcWalletPage.module.css}` and `mobile/pages/wallet/{MobileWalletPage.tsx,MobileWalletPage.module.css}`
- Move `orderActionPayloads.ts`, `orderActionPolicy.ts` into `routes/orders/` or core when UI-independent and shared by multiple routes
- Move `positionProtectionPolicy.ts` into `routes/positions/` or core under the same rule
- Keep wallet domain dialogs in the platform/app layer while composing `@fx-platform/ui/Dialog`
- Modify all relevant tests and `app/App.tsx`
- Delete legacy page implementations after migration

**Business invariants:**

- Orders: cancel, batch cancel, status tabs, pagination and payloads unchanged.
- Positions: close, close-all, protection update, algorithm/side fields unchanged.
- Wallet: balance, available/locked, ledger, transfer direction, amount validation, request ID and demo reset unchanged.
- One controller instance survives PC/Mobile resize; a pending operation cannot submit twice during a switch.

- [ ] **Step 1: Add shared-controller dual-view tests**

Render both platform views against the same fake controller and assert each action produces the identical payload. Add a resize-during-pending regression test.

- [ ] **Step 2: Split each layout and remove old responsive branches**

PC uses tables/forms suited to wide layout; Mobile uses cards/sheets/touch actions. Do not duplicate API calls or mutation state in the views. Each route statically owns only its controller and lazy component declarations, not either platform implementation.

- [ ] **Step 3: Verify and commit**

Run all order/position/wallet/core tests, full web gates, guest/auth visual smoke, and if demo backend is available `smoke:user-core-pages`. Commit:

```powershell
git commit -m "refactor(web): split orders positions and wallet views"
```

---

### Task 15: Phase 10 — Migrate the Trading Terminal Last

**Files:**

- Create: `routes/trading/TradingRoute.tsx` using existing controller/type files
- Create: `pc/pages/trading/{PcTradingTerminal.tsx,PcTradingTerminal.module.css}`
- Create: `mobile/pages/trading/{MobileTradingTerminal.tsx,MobileTradingTerminal.module.css}`
- Move current `TradingDesktopView` implementation to PC ownership
- Move current `mobile/MobileTradingTerminal` and `TradingMobileView` composition to Mobile ownership
- Move identical chart, symbol, quote, account and order-form widgets with two consumers into `shared-widgets/trading/`
- Move `components/market-side-panel/` (after Task 6 removed its two core re-export files) into `shared-widgets/trading/market-data/`; PC right panel and Mobile quote drawer are its two consumers
- Move `features/trading/components/` order-form composition into `shared-widgets/trading/order-form/` when both platform views consume the same component; keep platform-specific wrappers in their platform directory
- Split `features/trading/styles/trade-panel.css` into CSS Modules owned by the order-form components; no global trade-panel stylesheet remains
- Move `components/layout/`, `hooks/useResizableLayout.ts` and `stores/layoutStore.ts`/test into `pc/pages/trading/layout/`
- Move `hooks/useResizeObserver.ts` beside the shared chart widget under `shared-widgets/trading/chart/`
- Move Mobile-only drawer/order-sheet components to `mobile/pages/trading/`
- Modify `app/App.tsx`, trading tests, bundle budget and large-file audit
- Delete `pages/trading/TradingPage.tsx`, `useMobileTerminalViewport.ts`, old platform view wrappers and obsolete CSS after migration

**Critical architecture:**

```tsx
const PcTradingTerminal = lazy(() =>
  import('../../pc/pages/trading/PcTradingTerminal').then((module) => ({ default: module.PcTradingTerminal }))
)
const MobileTradingTerminal = lazy(() =>
  import('../../mobile/pages/trading/MobileTradingTerminal').then((module) => ({ default: module.MobileTradingTerminal }))
)

export function TradingRoute({ product }: { product: TradingProduct }) {
  const model = useTradingRouteController(product)
  return (
    <PlatformView
      model={model}
      pc={PcTradingTerminal}
      mobile={MobileTradingTerminal}
      fallback={<TerminalSkeleton />}
    />
  )
}
```

Actual platform views should be lazy-loaded into separate chunks, while `TradingRoute` and its controller remain mounted over width changes.

**Business invariants:**

- Canonical route resolution, symbol persistence and spot/perpetual switching remain unchanged.
- One market subscription and one trading session exist per mounted route.
- Chart interval/settings/drawings/markers, source change notice, favorites, quotes and perpetual references remain unchanged.
- Standard/OCO/advanced orders, confirmation, skip-confirm, leverage/margin, TP/SL, batch actions and position protection remain unchanged.
- Resizing with form input, open drawer/dialog or pending submit preserves controller state and never duplicates a request.

- [ ] **Step 1: Add controller/view and runtime regression tests**

Cover 899/900/901, same-controller identity, no duplicate subscription, form continuity, dialog/drawer safe behavior, canonical redirects and both product types.

- [ ] **Step 2: Move PC view with no mobile imports**

Keep wide terminal geometry and current keyboard/ARIA behavior. `TradingWorkspace`/resizable panels and layout store are PC-only. Move their tests with them and prove the Mobile chunk has no layout-store import.

- [ ] **Step 3: Move Mobile view with no PC imports**

Keep current drawer/order sheet, mobile chart controls and bottom navigation clearance. Remove the 768px hook; platform selection comes only from the app device runtime.

- [ ] **Step 4: Preserve and strengthen budgets**

Update `check-web-bundle-budget.mjs` for renamed chunks without weakening limits: keep Mobile terminal <= 20,000 bytes, Indicator settings <= 20,000, Drawing toolbar <= 80,000, and require `TradingRoute + PcTradingTerminal` entry chunks together <= 380,000 bytes. Replace deleted large-file entries with controller/PC/Mobile/TradePanel targets whose individual limits do not exceed the old 330/230 limits.

- [ ] **Step 5: Verify and commit**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:bundle-budget"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run audit:large-files"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:frontend-boundaries"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
git commit -m "refactor(web): split trading terminal platform views"
```

Expected: the two previously known debt gates now pass without raised limits.

---

### Task 16: Phase 11 — Remove Compatibility Layers and Run Total Acceptance

**Files:**

- Modify: `apps/web/src/styles.css`
- Modify with semantic merge: `scripts/smoke-visual-qa.mjs`
- Modify: visual smoke tests if present
- Create: `scripts/verify-frontend-styles.mjs`
- Create: `scripts/verify-frontend-styles.test.mjs`
- Modify: `scripts/check-web-bundle-budget.mjs`, `scripts/audit-large-files.mjs`, `scripts/verify-architecture.mjs`
- Modify: `package.json`, `README.md`, `docs/architecture.md`, this plan's status checkboxes only if execution policy permits
- Delete all legacy re-exports, old page implementations, obsolete global route styles and empty directories

- [ ] **Step 1: Delete every compatibility layer after proving zero consumers**

The following old ownership paths must be absent or contain only truly app-specific code, never core/UI re-exports:

```text
apps/web/src/services/
apps/web/src/types/trading.ts
apps/web/src/features/market/
apps/web/src/features/trading-session/
apps/web/src/features/trading/
apps/web/src/design-system/
apps/web/src/components/asset/
apps/web/src/components/user-page/
apps/web/src/components/layout/
apps/web/src/components/market-side-panel/
apps/web/src/components/tables/
apps/web/src/pages/
```

Run `rg` for every old import prefix before deletion. Do not delete current behavior tests; move them to new owners.

- [ ] **Step 2: Finish CSS ownership**

`apps/web/src/styles.css` may contain only reset, document/body base, generic typography and stable app-root defaults; it must be <= 400 lines and contain no route-specific selector. All UI colors in CSS use theme semantic variables. Intentional chart color constants may remain only in a named chart-theme adapter with tests.

Add `verify-frontend-styles.mjs` with fixture tests. It must fail when `styles.css` exceeds 400 lines, contains any class selector outside an explicit base-selector allowlist, or when app/UI component CSS contains hex/rgb/hsl literals outside `packages/ui/src/theme/theme.css`. Permit `transparent`, `currentColor`, CSS variables and intentional chart colors only in the tested chart-theme TypeScript adapter. Add root script `verify:frontend-styles` and append it to `frontend:check`.

- [ ] **Step 3: Extend visual smoke by semantic merge**

Preserve every current user change and existing viewport. Add mandatory viewports 899x844, 900x844 and 901x844 to existing 1440x900 and 390x844. Add a same-page runtime sequence 901 -> 900 -> 899 -> 901 that asserts `data-platform-view`, no console error, no body overflow and stable controller sentinel. Cover every Route Contract URL, including canonical trading routes and account routes, in guest/auth states where applicable.

- [ ] **Step 4: Synchronize durable documentation**

Update `README.md` and `docs/architecture.md` to the actual final tree, dependency matrix, package export map, 900px rule, install/dev commands, `frontend:check`, individual package tests and visual/business acceptance commands. Remove descriptions of the old `pages/services/features` ownership; do not edit unrelated backend documentation.

- [ ] **Step 5: Run static total gate**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:frontend-boundaries"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:frontend-styles"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:bundle-budget"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run audit:large-files"
```

Expected: every command exits 0; thresholds are unchanged or stricter.

- [ ] **Step 6: Run UI and business acceptance**

Start only local PostgreSQL/Redis and backend `dev` profile as required by existing smoke scripts; never configure a real broker.

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:visual-qa"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:user-core-pages"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:smoke:trading"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run acceptance:p0-user-trading"
```

Expected: all route/viewport checks pass; guest/auth, wallet/account and trading flows pass against demo execution. Report visual artifact and report paths.

- [ ] **Step 7: Perform spec and code self-review**

```powershell
rg -n "TODO|TBD|placeholder|temporary|compat" fx-trading-platform/apps/web/src fx-trading-platform/packages/ui/src fx-trading-platform/packages/frontend-core/src
rg -n "react-i18next|i18next|react-router-dom|\.module\.css|\.css'|\.css\"" fx-trading-platform/packages/frontend-core/src
rg -n "@fx-platform/frontend-core|apps/web|react-router-dom|react-i18next" fx-trading-platform/packages/ui/src
rg -n "(/mobile/|\\mobile\\)" fx-trading-platform/apps/web/src/pc
rg -n "(/pc/|\\pc\\)" fx-trading-platform/apps/web/src/mobile
```

Expected: first command only returns intentional user-facing placeholder copy if any, with no migration scaffolding; remaining commands return no illegal dependency.

- [ ] **Step 8: Commit final cleanup**

Stage only frontend/package/script/docs files belonging to this goal; inspect staged names and diff. Commit:

```powershell
git commit -m "refactor(web): complete pc mobile ui separation"
```

Do not push or open a PR unless the user separately requests it.

## Phase Checkpoint Map

| Recovery phase | Tasks | Required last commit subject |
| --- | --- | --- |
| R0 | Task 1 | `test(frontend): enforce target dependency boundaries` |
| R1 | Task 2 | `refactor(ui): extract shared theme package` |
| R2 | Tasks 3–4 | `refactor(ui): extract shared state data and dialog primitives` |
| R3 | Tasks 5–6 | `refactor(core): move market runtime into frontend core` |
| R4 | Task 7 | `refactor(core): extract account and wallet controllers` |
| R5 | Tasks 8–9 | `refactor(core): extract trading session controller` |
| R6 | Task 10 | `refactor(web): add stable pc mobile runtime` |
| R7 | Task 11 | `refactor(web): split home and auth platform views` |
| R8 | Task 12 | `refactor(web): split account and settings platform views` |
| R9 | Tasks 13–14 | `refactor(web): split orders positions and wallet views` |
| R10 | Task 15 | `refactor(web): split trading terminal platform views` |
| R11 | Task 16 | `refactor(web): complete pc mobile ui separation` |

## Strict Definition of Done

- Route Contract 中每个内容 URL 都有独立 PC View 与 Mobile View，并由同一个 Route Controller 驱动；redirect/fallback 在两端保持同一目标。
- 每个 PC/Mobile 页面由独立 dynamic import 形成 chunk；运行时只挂载当前平台 view，不用 CSS 同时隐藏另一端。
- 900px 属于 Mobile，901px 属于 PC；运行时来回切换不会重建认证、行情、账户或交易 Controller。
- `@fx-platform/ui` 无业务/router/i18n依赖；`@fx-platform/frontend-core` 无页面/router/i18n/CSS依赖。
- PC 与 Mobile 互不 import；`shared-widgets` 不反向依赖平台目录。
- 当前 API、storage key、请求 payload、错误/request ID、STOMP、行情 fallback、钱包与交易行为全部保持。
- 当前 `orderAdapter` 的独立 UUID `idempotencyKey` 改动已保留并有测试。
- 旧实现、短期 adapter/re-export、重复业务逻辑、旧 768px 设备 hook、结构性响应式分支已删除。
- `styles.css` <= 400 行且无 route selector；组件与页面 CSS 归属清晰，UI 色彩使用语义 token。
- UI/core tests/typecheck、web test/build、dependency/style/architecture checks、bundle budget、large-file audit 全部通过。
- 1440x900、390x844、899x844、900x844、901x844 与运行时 resize 的全路由视觉/核心交互验收通过。
- Guest/auth、account/wallet、trading demo 业务 smoke 通过；没有连接真实 broker/FIX/LP。
- 每个阶段都有独立提交且未混入无关 backend/admin/用户文件；最终未自动 push。

## Plan Self-Review Checklist

- [x] 每个设计规格 Phase 0–11 都映射到任务和恢复命令。
- [x] 每个任务列出精确所有权、行为合同、失败测试、验证命令和 commit subject。
- [x] 文件路径、export 名称、设备阈值和 viewport 在全文一致。
- [x] 没有要求清空 dirty worktree，也没有授权破坏性 Git 操作。
- [x] 没有把 admin/backend、视觉重设计、Storybook 或新 CSS 框架扩入范围。
- [x] 没有通过放宽 bundle/large-file/test 阈值规避基线债务。
- [x] 没有把 app 翻译/router/CSS 依赖放进 core 或 UI package。
- [x] 最终验收覆盖全部路由、两端 UI、边界 900/901、resize 与业务链路。
