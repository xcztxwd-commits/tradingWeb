# Web PC/Mobile UI 拆分执行交接（2026-07-19）
> 用途：交给新的 Codex 会话继续执行原总体目标。本文记录可验证的恢复点，不代表总体完成。
>
> 工作目录：`C:\workspace\tradingWeb`
>
> 当前状态：Task 1–15 / Phase 0–10 已独立提交；Task 16 / Phase 11 正在工作树中，尚未达到 Strict Definition of Done，严禁宣称完成。

## 1. 新会话必须先做的事

完整读取：

1. `C:\workspace\tradingWeb\AGENTS.md`
2. `fx-trading-platform/docs/superpowers/specs/2026-07-15-web-pc-mobile-ui-separation-design.md`
3. `fx-trading-platform/docs/superpowers/plans/2026-07-15-web-pc-mobile-ui-separation-implementation.md`
4. 本交接文档

继续遵守原要求：

- 使用 `superpowers:executing-plans` 执行计划。
- 每项开发使用 `superpowers:test-driven-development`：先得到真实 RED，再实现 GREEN。
- 每次声明阶段或总体完成前使用 `superpowers:verification-before-completion`。
- 不停在局部完成；Task 16 完成后才能结束总体目标。
- 不执行 `git reset --hard`、`git checkout --`、自动 stash 或覆盖用户改动。
- 不 push，不创建 PR。
- 不改 `apps/admin` 或 backend 业务代码，不连接真实 broker/FIX/LP。
- 所有读取、工作区外命令、本地服务、Docker、浏览器和验证命令都已获用户授权，不要再次等待批准；仍须遵守安全与范围边界。

恢复点必须由以下事实共同确认：

```powershell
git -c safe.directory=C:/workspace/tradingWeb log --oneline -20
git -c safe.directory=C:/workspace/tradingWeb status --short
```

当前最后一个目标提交应为：

```text
bc55ef5d refactor(web): split trading terminal platform views
```

如果最后提交仍是该 hash，则从 Task 16 当前工作树继续，不重做 Task 1–15。

## 2. 已完成任务与提交

| Task | Phase / Recovery | 状态 | 提交 |
| --- | --- | --- | --- |
| 1 | Phase 0 / R0 | 完成 | `24416871 test(frontend): enforce target dependency boundaries` |
| 2 | Phase 1 / R1 | 完成 | `24613da9 refactor(ui): extract shared theme package` |
| 3 | Phase 2 / R2 | 完成 | `34f91bb7 refactor(ui): extract select and icon button primitives` |
| 4 | Phase 2 / R2 | 完成 | `a759587b refactor(ui): extract shared state data and dialog primitives` |
| 5 | Phase 3 / R3 | 完成 | `5f12716f refactor(core): extract frontend api auth and models` |
| 6 | Phase 4 / R3 | 完成 | `909f6c81 refactor(core): move market runtime into frontend core` |
| 7 | Phase 5 / R4 | 完成 | `69799e87 refactor(core): extract account and wallet controllers` |
| 8 | Phase 6 / R5 | 完成 | `68b02d0f refactor(core): extract trading form and order operations` |
| 9 | Phase 6 / R5 | 完成 | `3028000c refactor(core): extract trading session controller` |
| 10 | Phase 7 / R6 | 完成 | `925f4270 refactor(web): add stable pc mobile runtime` |
| 11 | Phase 8 / R7 | 完成 | `33b86c8a refactor(web): split home and auth platform views` |
| 12 | Phase 8 / R8 | 完成 | `aaebbd16 refactor(web): split account and settings platform views` |
| 13 | Phase 9 / R9 | 完成 | `9b12e1b1 refactor(web): split markets platform views` |
| 14 | Phase 9 / R9 | 完成 | `fa43d24e refactor(web): split orders positions and wallet views` |
| 15 | Phase 10 / R10 | 完成 | `bc55ef5d refactor(web): split trading terminal platform views` |
| 16 | Phase 11 / R11 | **进行中** | 待提交：`refactor(web): complete pc mobile ui separation` |

较长 hash（需要精确引用 Task 9/15 时）：

- Task 9：`3028000cc4f58c2e1286d926fa977ae8a810485f`
- Task 15：`bc55ef5d5a623b507d14ebce06bc8389e09612db`

## 3. Task 1–15 已实现的最终结构

主要结构已经形成：

```text
apps/web/src/
├─ app/
│  ├─ device/
│  ├─ platform/
│  └─ shell/
├─ routes/<domain>/
├─ pc/pages/<domain>/
├─ mobile/pages/<domain>/
├─ shared-widgets/<domain>/
├─ i18n/
└─ styles.css

packages/ui/src/
├─ theme/
├─ select-field/
├─ icon-button/
├─ skeleton/
├─ state-surface/
├─ data-view/
├─ dialog/
└─ drawer/

packages/frontend-core/src/
├─ api/
├─ auth/
├─ storage/
├─ models/
├─ market/
├─ account/
└─ trading/
```

已建立的依赖边界：

```text
pc ───────────┐
mobile ───────┼─> shared-widgets ─> @fx-platform/ui
routes ───────┘              └────> @fx-platform/frontend-core

@fx-platform/frontend-core ─> @fx-platform/shared-types
@fx-platform/ui ─────────────> React / ReactDOM / lucide-react
```

已实现的核心运行时合同：

- `width <= 900px` 选择 Mobile，`width >= 901px` 选择 PC。
- `DeviceClassProvider` / `PlatformView` 位于端侧 View 选择层。
- 内容路由由同一个 Route Controller 驱动两端 View。
- PC/Mobile 页面通过动态 import 形成不同 chunk。
- 公共 Provider、认证、行情、账户与交易 Controller 位于平台 View 之上。
- PC 与 Mobile 不互相 import；`shared-widgets` 不反向依赖平台目录。

## 4. 必须保留的业务改动

原用户特别要求保留的订单幂等逻辑已经迁入：

- `fx-trading-platform/packages/frontend-core/src/trading/orderAdapter.ts`
- `fx-trading-platform/packages/frontend-core/src/trading/orderAdapter.test.ts`

合同必须保持：

- 普通订单生成独立随机 UUID `idempotencyKey`。
- OCO 生成独立随机 UUID `idempotencyKey`。
- `idempotencyKey` 不得复用 `clientOrderId`。
- 不得为了目录清理或兼容层删除回退该行为。

## 5. Task 15 已有验证证据

Task 15 提交前的已知新鲜证据：

- UI tests：40/40 通过。
- frontend-core tests：189/189 通过。
- web tests：407/407 通过。
- UI/core typecheck 通过。
- web build 通过。
- dependency boundary 与 architecture 通过。
- bundle：
  - Trading + PC：92,576 bytes / 380,000 budget。
  - Mobile terminal：10,092 bytes / 20,000 budget。
  - Indicator：9,797 bytes。
  - Drawing：7,899 bytes。
- large-file gates 通过。
- Task 15 Visual QA：21/21 PASS。
- 报告：`C:\Users\Admin\AppData\Local\Temp\fx-trading-visual-qa\task15-trading-matrix\report.json`

这些证据只能证明 Task 15 当时通过，不能替代 Task 16 最终重新验证。

## 6. Task 16 工作树中已做但未提交的内容

### 6.1 旧所有权清理

新增：

- `apps/web/src/routes/legacyOwnership.test.ts`

已迁移：

- `components/user-page/PageState.tsx` → `shared-widgets/data/PageState.tsx`
- `components/user-page/userPageModels.ts` 与测试 → `shared-widgets/data/`
- `components/user-page/userPageUi.test.ts` → `shared-widgets/data/`
- `pages/prototypeFidelity.test.ts` → `routes/prototypeFidelity.test.ts`
- `pages/userPages.test.ts` → `routes/userPages.test.ts`

已删除的无人使用旧文件：

- `pages/PlaceholderPage.tsx`
- `pages/account/AccountHubPage.tsx`
- `components/tables/OrdersTable.tsx`
- `components/tables/PositionsTable.tsx`
- `components/tables/LedgerTable.tsx`

旧目录可能在磁盘上作为空目录存在；Git 不跟踪空目录。最终仍要用 `rg --files` 和 `legacyOwnership.test.ts` 证明旧实现/兼容 re-export 已清零。

相关定向测试曾 40/40 通过，但最终必须重跑。

### 6.2 样式验证器和 token 收敛

新增：

- `scripts/verify-frontend-styles.mjs`
- `scripts/verify-frontend-styles.test.mjs`

根 `package.json` 已增加：

- `test:frontend-styles`
- `verify:frontend-styles`
- `verify:frontend-styles` 已接入 `frontend:check`

验证器当前检查：

- `apps/web/src/styles.css` 不超过 400 行。
- `styles.css` 除显式 allowlist（当前 `.sr-only`）外没有 class selector。
- app/UI CSS 中不允许 hex/rgb/hsl 字面量，`packages/ui/src/theme/theme.css` 除外。
- 禁止旧 `max-width: 768px` / `min-width: 769px` 设备断点。

当前 `styles.css` 约 109 行，只保留 base/reset。

大量直接颜色已转为 `packages/ui/src/theme/theme.css` 中的 `--theme-reference-*` token。图表颜色另见 6.3。

样式验证 fixture 曾 5/5 通过，真实验证器曾 PASS；相关源测试曾 74/74 通过。

**但是样式所有权仍有重大未完成问题，见第 7 节。**

### 6.3 图表颜色 adapter

新增：

- `apps/web/src/shared-widgets/trading/chartTheme.ts`
- `apps/web/src/shared-widgets/trading/chartTheme.test.ts`

已将图表颜色集中到命名 adapter，并让以下模块消费：

- `chartSettings.ts`
- `chartTradeMarkers.ts`
- `chartDrawingPersistence.ts`
- `KLineChartPanel.tsx`
- `MarketsContent.tsx`

`packages/frontend-core/src/market/binanceMarketData.ts` 已从返回直接颜色改为语义 `tone`，Web 再通过 `futuresChartColor` 映射。

相关定向测试曾 35/35 通过；frontend-core typecheck 通过。

### 6.4 frontend-core package exports

`packages/frontend-core/package.json` 已从只导出根入口扩展为：

- `.`
- `./api`
- `./auth`
- `./storage`
- `./models`
- `./market`
- `./account`
- `./trading`

`packages/frontend-core/src/package-contract.test.ts` 已更新为精确导出合同。

TDD 证据：

- RED：2/3 通过，因缺少 7 个子路径失败。
- GREEN：3/3 通过。
- `npm run frontend-core:typecheck` 通过。

### 6.5 Visual QA 脚本语义合并

`scripts/smoke-visual-qa.mjs` 原本已有大量用户改动。本轮是在当前用户 diff 上语义合并，不能用旧文件覆盖。

已保留的既有视口：

- 1440x900
- 390x844
- 375x667
- 412x915
- 844x390
- 915x412

已新增：

- 899x844
- 900x844
- 901x844
- 同页 `901 → 900 → 899 → 901` 连续 resize

路由矩阵已扩展到 37 个 Route Contract 条目，包括：

- 根路由与 fallback。
- `/trade`、`/trading`、非法 product redirect。
- canonical spot/perpetual guest/auth。
- markets。
- orders/positions/wallet guest/auth。
- dashboard guest/auth。
- account redirect 与全部 account 子路由 guest/auth。
- settings/security。
- login/register/forgot-password/two-factor-help。

断言已覆盖：

- 只挂载预期 `data-platform-view`，对端为 0。
- Controller sentinel。
- 无 console error。
- 无 body overflow。
- 交易终端、Mobile 三个固定 action。
- 连续 resize 时 sentinel 稳定。
- resize 中输入值保留。
- API mock 扩展到账户、钱包、流水、订单、持仓、行情与 perpetual reference。

新增：

- `scripts/smoke-visual-qa.test.mjs`
- 根脚本 `test:visual-qa-contract`
- 已接入 `frontend:check`

脚本合同测试曾 3/3 通过。

### 6.6 README 与架构文档

已修改：

- `fx-trading-platform/README.md`
- `fx-trading-platform/docs/architecture.md`

已加入：

- 最终目录。
- 依赖矩阵。
- package export map。
- 900/901 规则。
- install/dev 命令。
- `frontend:check` 和独立 package/gate 命令。
- Visual 与业务 acceptance 命令。
- 样式所有权说明。

注意：`docs/architecture.md` 在本目标开始前已有用户新增的 Java 后端文档链接。最终必须 hunk stage，只暂存本目标前端文档段落，把该既有用户 hunk 留在工作区。

## 7. 当前第一优先级缺陷：ApplicationSurfaces CSS 无效

### 7.1 发现过程

为把原 `styles.css` 的约 5,750 行页面/路由规则移出全局文件，当前工作树创建了：

- `apps/web/src/app/ApplicationSurfaces.module.css`

该文件约 113,970 bytes，主体写法是：

```css
.root {
  min-width: 0;
  isolation: isolate;
}

:global {
  /* 数千行旧全局选择器 */
}
```

`AppShell.tsx` 当前通过本地 `root` class 消费该 CSS Module；`App.test.ts` 新增了对应测试。

TDD 证据：

- RED：App test 14/15，因仍是 side-effect import 且未消费本地 class 失败。
- GREEN：App test 15/15，通过导入 `applicationSurfaceStyles` 并把 `applicationSurfaceStyles.root` 加到 app shell。

### 7.2 不能被“测试绿/构建退出 0”掩盖的问题

最新 `npm run web:build` 虽然退出 0，但输出：

```text
[esbuild css minify]
▲ [WARNING] Unexpected "{" [css-syntax-error]

    <stdin>:347:1:
      347 │  {
          ╵  ^
```

本地 class 未消费之前，约 114 KB 的 ApplicationSurfaces CSS 根本没有进入构建产物。消费 `root` 后，主 CSS 从约 27 KB 增到 138.79 KB，但 `:global { ... }` 被 CSS Modules 处理成非法裸 `{ ... }` 块，生产 CSS 仍不可靠。

这解释了最新 Visual QA 中大量症状：

- `.mobile-tabs` 只有约 24px 固有高度，滚动后离开视口，而不是固定 64px 底栏。
- route/user-page/app-shell 的旧全局规则没有可靠生效。
- markets drawer input 只有 21px / 13px。
- 多个移动页面 bottom-bar clearance 计算出现巨大负值。
- auth support / guest account 页面部分 ready 合同超时。
- runtime trade input 可见高度为 0，返回 `null`。

### 7.3 新会话正确处理方向

不得仅把大文件改名成普通 `.css` 并把 5,750 行全局路由样式长期留下；这不满足 Phase 11 “页面样式归属 CSS Modules”的目标。

建议按 TDD 做：

1. 先增加能证明**生产 CSS 实际有效**的失败测试，而不只检查源码文本：
   - 构建产物不得含非法裸块/构建 warning。
   - 产物中必须存在有效 `.mobile-tabs { position: fixed; ... }` 或通过浏览器 computed style 证明。
   - `AppShell`、Mobile shell、route view 的关键样式必须来自实际消费的 module。
2. 把 shell 级选择器迁到相邻模块：
   - `AppShell.module.css` / 当前 ApplicationSurfaces 的局部 shell module。
   - `PcShellChrome.module.css`。
   - `MobileShellChrome.module.css`。
3. 把 route/shared-widget 选择器迁到各自相邻 CSS Module，并把 JSX 字符串 class 改为 module class；如需短期桥接，只允许明确的 `:global(.class-name)` 单选择器，迁完立即删除。
4. 删除巨型 `:global { ... }` 包装和已迁移旧规则。
5. 不要放宽 `styles.css <= 400`、bundle 或 large-file 阈值。
6. 运行 build，构建 warning 必须为 0，再重跑 Visual QA。
7. 即使现有 `audit:large-files` 不扫描 CSS，也不能把 5,750 行巨型模块当作最终完成。

## 8. 最新 Visual QA 实际结果

最新完整报告：

`C:\Users\Admin\AppData\Local\Temp\fx-trading-visual-qa\2026-07-18T20-52-35-837Z\report.json`

结果：

- Status：FAIL
- Checks：103
- Passed：63
- Failed：40
- 生成了全部 103 个路由/视口/resize 结果及截图。
- 执行命令：`node scripts/smoke-visual-qa.mjs`
- 脚本最终 exit code：1。

失败主要分组：

1. 应用级 CSS 未可靠生效（最可能覆盖大多数失败）：
   - Mobile bottom bar overlap / 负坐标。
   - drawer 输入目标 21px、字体 13px。
   - auth-support 和 guest route ready timeout。
   - home 5px overflow。
2. 短视口图表断言：
   - 375x667 chart 高 270px，当前合同要求 >=300px。
   - 844x390 chart 高 288px，当前合同要求 >=300px。
   - 修复 CSS 后先重跑，不要先降低阈值。
3. runtime resize：
   - `Runtime resize trade value was not accepted: null`。
   - 很可能是 CSS 未生效导致 input 可见高度为 0；修复样式后复测。
4. orders/positions/wallet guest/auth 的若干 ready timeout/non-empty：
   - 修复 CSS 后按报告逐路由确认。
   - 不得用放宽 ready 条件掩盖真实未渲染。

另有一次更早的 Visual QA 运行在 51 张截图时被外部终止，没有 `report.json`，目录为：

`C:\Users\Admin\AppData\Local\Temp\fx-trading-visual-qa\2026-07-18T19-24-14-596Z`

不要把该不完整运行作为证据。

## 9. Task 16 当前验证记录

已通过（定向证据）：

- `node --test packages/frontend-core/src/package-contract.test.ts`：3/3。
- `npm run frontend-core:typecheck`：通过。
- `node --test apps/web/src/app/App.test.ts`：15/15。
- `scripts/smoke-visual-qa.test.mjs`：曾 3/3。
- `verify-frontend-styles.test.mjs`：曾 5/5。
- 样式/相关源测试：曾 74/74。
- 图表主题相关测试：曾 35/35。
- 旧所有权迁移相关测试：曾 40/40。

当前不能算通过：

- 最新 `web:build` exit 0，但有 CSS syntax warning；按 Strict DoD 视为失败。
- 最新 `smoke:visual-qa` 63/103，FAIL。
- Task 16 改动后的完整 UI/core/web tests/typecheck/gates 尚未全部新鲜重跑。
- 四个最终业务 smoke 尚未全部在当前 Task 16 状态下通过。

## 10. 精确续做顺序

### A. 修复样式所有权与生产 CSS

1. 检查当前 diff：
   - `App.test.ts`
   - `AppShell.tsx`
   - `ApplicationSurfaces.module.css`
   - `styles.css`
   - 各 route/shared-widget CSS Module
2. 按第 7 节先写能捕捉构建无效 CSS 的 RED 测试。
3. 将 shell 与 route 样式迁到有效 CSS Modules，消除 `:global { ... }` 非法包装。
4. 运行：
   - App 定向测试。
   - `npm run verify:frontend-styles`。
   - `npm run web:build`；不得有 CSS syntax warning。
   - 检查构建 CSS/浏览器 computed style。

### B. 重跑并修复 Visual QA

```powershell
cd C:\workspace\tradingWeb\fx-trading-platform
node scripts/smoke-visual-qa.mjs
```

读取最新 `report.json`，按实际失败修复；不得降低阈值来隐藏失败。

必须最终证明：

- 1440x900。
- 390x844。
- 899x844 / 900x844 为 Mobile。
- 901x844 为 PC。
- `901 → 900 → 899 → 901` sentinel 不变。
- 无重复 mount、console error、body overflow。
- PC/Mobile 不是同时挂载后 CSS 隐藏。

### C. 运行 Task 16 静态总门禁

按实施计划逐条运行，不用只跑聚合命令：

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

阈值不可放宽；build warning 也必须处理。

### D. 运行最终 UI 与 demo 业务验收

只启动本地 PostgreSQL/Redis 与 backend `dev` profile，不配置真实 broker：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:visual-qa"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:user-core-pages"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:smoke:trading"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run acceptance:p0-user-trading"
```

必须覆盖登录、认证恢复、行情、订单、持仓、钱包、流水和 demo 交易。

### E. 自审

```powershell
rg -n "TODO|TBD|placeholder|temporary|compat" fx-trading-platform/apps/web/src fx-trading-platform/packages/ui/src fx-trading-platform/packages/frontend-core/src
rg -n "react-i18next|i18next|react-router-dom|\.module\.css|\.css'|\.css\"" fx-trading-platform/packages/frontend-core/src
rg -n "@fx-platform/frontend-core|apps/web|react-router-dom|react-i18next" fx-trading-platform/packages/ui/src
rg -n "(/mobile/|\\mobile\\)" fx-trading-platform/apps/web/src/pc
rg -n "(/pc/|\\pc\\)" fx-trading-platform/apps/web/src/mobile
rg -n "max-width:\s*768px|min-width:\s*769px" fx-trading-platform/apps/web/src fx-trading-platform/packages/ui/src
```

同时核对：

- 无旧 compatibility re-export。
- 无旧 `pages/services/features` 业务所有权。
- 无 UI/core 非法依赖。
- 无 PC/Mobile 互相 import。
- `styles.css` <= 400 且无 route selector。
- `orderAdapter` UUID 合同仍在。

### F. 精确暂存与最终提交

Task 16 只能提交本目标文件或 hunks。

最终 subject：

```text
refactor(web): complete pc mobile ui separation
```

提交前必须：

```powershell
git -c safe.directory=C:/workspace/tradingWeb diff --cached --name-status
git -c safe.directory=C:/workspace/tradingWeb diff --cached
```

不要 push，不创建 PR。

## 11. Dirty worktree 与暂存边界

当前 worktree 非常脏，这是执行基线，不是清理对象。

### 11.1 本目标 Task 16 文件（完成后可精确暂存）

主要包括：

- `fx-trading-platform/README.md`
- `fx-trading-platform/apps/web/**` 中 Task 16 清理、样式、chartTheme 与测试。
- `fx-trading-platform/packages/ui/src/theme/theme.css`
- `fx-trading-platform/packages/frontend-core/package.json`
- `fx-trading-platform/packages/frontend-core/src/package-contract.test.ts`
- `fx-trading-platform/packages/frontend-core/src/market/binanceMarketData.ts`
- `fx-trading-platform/package.json` 的前端门禁脚本 hunks。
- `fx-trading-platform/scripts/smoke-visual-qa.mjs`
- `fx-trading-platform/scripts/smoke-visual-qa.test.mjs`
- `fx-trading-platform/scripts/verify-frontend-styles.mjs`
- `fx-trading-platform/scripts/verify-frontend-styles.test.mjs`
- `fx-trading-platform/scripts/verify-frontend-boundaries.test.mjs` 的本目标 hunk。
- `fx-trading-platform/scripts/verify-architecture.mjs` 的本目标 hunk。
- `fx-trading-platform/docs/architecture.md` 的本目标前端文档 hunks。

### 11.2 必须保留、不暂存的范围外用户改动

不要暂存或提交：

- 全部 `fx-trading-platform/backend/**` 改动和新增文件。
- 全部 `apps/admin/**`（若出现）。
- `packages/shared-types/**` 的用户改动。
- 根目录 `.task3-*.patch`。
- `fx-trading-platform/.run-logs/**`。
- 用户新增的 backend/trading-lab/数据库 migration/场景测试。
- 与本目标无关的 docs/specs/plans/reports/testing。
- 与本目标无关的脚本，例如：
  - `contract-governance.test.mjs`
  - `p0-user-trading-artifacts.mjs`
  - `p0-user-trading-runner.test.mjs`
  - `smoke-usdt-demo-browser.mjs`
  - `trading-contract.test.mjs`
  - scenario/report generator/validator 脚本。

### 11.3 两个必须 hunk-stage 的混合文件

1. `scripts/verify-architecture.mjs`
   - 本目标：frontend boundary/style 接线。
   - 用户范围外：scheduler required files、`@Scheduled` 检查从 services 移到 schedulers 等 backend hunks。
   - 只能 hunk stage 本目标部分。

2. `docs/architecture.md`
   - 本目标：前端目录、依赖、exports、900/901、样式和验证命令。
   - 用户既有：Java 后端架构文档链接。
   - 只能 hunk stage 本目标前端部分。

`scripts/smoke-visual-qa.mjs` 是用户原 diff 上的语义合并结果；最终可整体暂存前必须再次检查完整 diff，确认没有覆盖原视口/行为。

## 12. 最终完成标准（不可缩小）

只有全部满足才可调用 goal complete：

- 每个内容路由都有独立 PC/Mobile View，共用同一个 Route Controller。
- 900px 及以下 Mobile；901px 及以上 PC。
- 跨断点不重建认证、行情、账户或交易 Controller，不重复订阅/提交。
- PC/Mobile 动态加载；不同时挂载后 CSS 隐藏。
- UI/Core/PC/Mobile/shared-widgets 依赖边界全部通过。
- 旧页面、兼容 re-export、重复逻辑、旧 768px 判断和非法跨端依赖清零。
- `styles.css` <=400；页面样式归属有效 CSS Modules 与语义 token。
- UI/core/web tests、typecheck、build、dependency/style/architecture、bundle budget、large-file audit 全通过，阈值未放宽，构建无 CSS 语法 warning。
- 1440x900、390x844、899/900/901 和连续 resize 全路由验收 PASS。
- guest/auth、account/wallet、trading demo smokes PASS。
- 每阶段独立提交，不混入 backend/admin/用户范围外文件。
- 未 push、未创建 PR。

## 13. 建议给新会话的启动提示

```text
继续执行 C:\workspace\tradingWeb 的持久总体目标。先完整读取 AGENTS.md、设计、实施计划和
docs/superpowers/reports/2026-07-19-web-pc-mobile-ui-separation-handoff.md。
用 git log/status/diff 确认恢复点：Task 1–15 已提交，Task 16 在工作树。
不要重做前 15 项。第一优先级按 TDD 修复 ApplicationSurfaces.module.css 的非法 :global 块和
CSS 所有权，最新 build 有 Unexpected "{" warning，最新 Visual QA 为 63/103 FAIL。
修复后继续 Task 16 的完整静态门禁、Visual QA、四个 demo 业务 smoke、自审、精确 hunk staging
和最终独立提交。不要碰或暂存 backend/admin/shared-types/用户范围外改动，不 push/PR。
```

## 14. 本交接文档自身

本文是用户要求的新会话操作交接材料。除非用户明确要求把交接材料纳入 Task 16 提交，否则最终提交时可把本文留在工作区，避免把临时执行记录混入产品架构提交。
