# 前端后续优化执行计划

> **给 Codex/agentic workers 的硬性要求：** 执行本计划前必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`。本计划使用 checkbox（`- [ ]`）格式，执行时逐项勾选，不要跳步。

**目标：** 完成前端审计后的剩余优化：移除通用盘口组件对页面目录的最后反向依赖，继续拆分图表绘图工具 chunk，并建立可重复执行的 bundle 预算与浏览器验收流程。

**架构方向：** 不改变交易业务行为。把共享加载 UI 移到真正拥有它的通用组件目录；把只在图表区域需要的重型工具延迟加载；用一个小型 Node 脚本把 chunk 预算变成可执行校验，防止后续改动悄悄把懒加载模块合回主包。

**技术栈：** React 19、Vite 7、TypeScript 5.8、Node `node:test`、Windows PowerShell、Codex Browser 插件。

---

## 0. 背景说明

本计划继续执行：

`fx-trading-platform/docs/frontend-code-audit-optimization-2026-06-12.md`

上一轮优化已经完成的事项：

- `createPanelMarket` 在空行情快照时保留当前交易品种，不再把非 BTC 品种错误回退到 `BTC-USDT`。
- 生产 session 状态中已移除 `offline-preview`。
- 旧 `/trade` 页面实现文件已删除，路由仍保留 `/trade -> /trading` 跳转。
- `tradingMarketApi`、`tradingMarketAdapters`、`tradingModels`、`mockTradingData` 已迁移到 `features/market`。
- `parseSymbolAssets` 与 `formatDecimal` 已合并到 `features/trading/utils`。
- 未编辑的限价输入会跟随同一 symbol 的盘口变化，编辑中不会被覆盖。
- 行情订阅已收窄到当前选中、自选和首屏 markets。
- `MobileTradingTerminal` 与 `IndicatorSettingsModal` 已 lazy split。

本计划要解决的剩余项：

1. `components/market-side-panel/MarketSidePanel.tsx` 仍从 `pages/trading/components/TerminalSkeleton` 引入 `OrderBookSkeleton`。
2. `ChartDrawingToolbar` 仍被 `ChartWorkspace` 静态导入，仍在 `TradingPage` 初始 chunk 内。
3. 当前没有可执行的 bundle budget，无法阻止后续 chunk 回退。
4. 最终验收需要固定包含测试、构建、架构校验、类型检查和浏览器验证。

不要把根目录图表库大类重构混进本计划。`src/Chart.ts`、`src/Store.ts`、`src/common/EventHandler.ts` 的大类拆分属于独立低优先级技术债，不在本次业务前端优化范围内。

---

## 1. 执行规则

- 工作目录固定为：`C:\Users\User\Desktop\workspace\tradingView-KlineChart`
- 执行前先运行 `git status --short`。
- 不要回滚或删除用户已有改动。
- 只改本计划列出的文件。
- 代码修改必须 TDD：先写或改测试，确认失败，再写最小实现，确认通过。
- Windows 上运行 npm 脚本优先使用：

```powershell
cmd.exe /d /s /c "npm.cmd ..."
```

- 不新增运行时依赖。
- 不机械修复无关 mojibake 文案。只在本次修改涉及的组件内调整必要字符串。
- 如果用户要求 commit，每个任务完成并验证通过后单独提交；否则保持未暂存状态并报告验证结果。

---

## 2. 目标文件清单

### 新增文件

- `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.tsx`  
  盘口加载 skeleton 的组件实现，归属通用 `market-side-panel`。

- `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.module.css`  
  盘口 skeleton 样式，只服务 `OrderBookSkeleton`。

- `fx-trading-platform/scripts/check-web-bundle-budget.mjs`  
  检查生产构建产物 chunk 是否存在且不超过预算。

- `fx-trading-platform/docs/frontend-follow-up-optimization-completion-2026-06-13.md`  
  完成后的验证报告。

### 修改文件

- `fx-trading-platform/apps/web/src/components/market-side-panel/MarketSidePanel.tsx`
- `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBook.test.ts`
- `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.tsx`
- `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.module.css`
- `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.test.ts`
- `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.tsx`
- `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.module.css`
- `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.test.ts`
- `fx-trading-platform/package.json`
- `fx-trading-platform/scripts/verify-architecture.mjs`

---

## 3. 成功标准

完成后必须同时满足：

- `MarketSidePanel.tsx` 不再出现 `../../pages/trading`。
- `components/market-side-panel` 下没有任何文件 import `pages/trading`。
- `ChartWorkspace.tsx` 使用 `lazy(() => import('./ChartDrawingToolbar'))`。
- 构建产物中有独立 chunk：
  - `TradingPage-*.js`
  - `MobileTradingTerminal-*.js`
  - `IndicatorSettingsModal-*.js`
  - `ChartDrawingToolbar-*.js`
- 以下命令全部通过：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:bundle-budget"
cmd.exe /d /s /c "pnpm.cmd type-check"
```

- Browser 验证 `/trading` 桌面端与移动端都无 console error。

---

# Task 0：建立基线

**文件：** 只读，不改文件。

## Step 0.1：检查当前工作区

运行：

```powershell
git status --short
```

预期：

- 可能看到已有未跟踪文件或用户改动。
- 不要清理、不回滚。
- 记录本计划涉及的文件是否已被修改。

## Step 0.2：运行当前聚焦测试

运行：

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\components\market-side-panel\OrderBook.test.ts src\components\market-side-panel\quoteMarketDataAdapter.test.ts src\pages\trading\components\TerminalSkeleton.test.ts src\pages\trading\components\ChartWorkspace.test.ts"
```

预期：

- 当前应 PASS。
- 这是修改前基线。

## Step 0.3：构建一次并记录 chunk

运行：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
Get-ChildItem -LiteralPath "fx-trading-platform\apps\web\dist\assets" -File | Where-Object { $_.Name -match "TradingPage|MobileTradingTerminal|IndicatorSettingsModal|ChartDrawingToolbar" } | Select-Object Name,Length
```

预期：

- `TradingPage-*.js` 存在。
- `MobileTradingTerminal-*.js` 存在。
- `IndicatorSettingsModal-*.js` 存在。
- `ChartDrawingToolbar-*.js` 在 Task 2 前通常不存在。

---

# Task 1：把 OrderBookSkeleton 移到 market-side-panel

**目标：** 通用 `MarketSidePanel` 不再依赖 `pages/trading`。

**涉及文件：**

- 新增：`fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.tsx`
- 新增：`fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.module.css`
- 修改：`fx-trading-platform/apps/web/src/components/market-side-panel/MarketSidePanel.tsx`
- 修改：`fx-trading-platform/apps/web/src/components/market-side-panel/OrderBook.test.ts`
- 修改：`fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.tsx`
- 修改：`fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.module.css`
- 修改：`fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.test.ts`

## Step 1.1：先写失败测试

修改 `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBook.test.ts`。

把第一个测试替换为：

```ts
  it('shows a market-owned skeleton while the first market snapshot is empty', () => {
    assert.match(sidePanelSource, /import \{ OrderBookSkeleton \} from '\.\/OrderBookSkeleton'/)
    assert.match(sidePanelSource, /OrderBookSkeleton/)
    assert.match(sidePanelSource, /isInitialMarketSnapshot/)
    assert.doesNotMatch(sidePanelSource, /\.\.\/\.\.\/pages\/trading/)
  })
```

运行：

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\components\market-side-panel\OrderBook.test.ts"
```

预期：

- FAIL。
- 失败原因应是 `MarketSidePanel.tsx` 仍从 `../../pages/trading/components/TerminalSkeleton` import。

## Step 1.2：新增 OrderBookSkeleton 组件

创建 `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.tsx`：

```tsx
import styles from './OrderBookSkeleton.module.css'

type OrderBookSkeletonProps = {
  rows?: number
}

export function OrderBookSkeleton({ rows = 14 }: OrderBookSkeletonProps) {
  return (
    <div className={styles.orderBookSkeleton} role="status" aria-live="polite" aria-label="正在同步盘口">
      <div className={styles.headerRow} aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
      <div className={styles.rows} aria-hidden="true">
        {Array.from({ length: rows }, (_, index) => (
          <span key={index} className={index % 2 === 0 ? styles.askRow : styles.bidRow} />
        ))}
      </div>
    </div>
  )
}
```

创建 `fx-trading-platform/apps/web/src/components/market-side-panel/OrderBookSkeleton.module.css`：

```css
.orderBookSkeleton {
  --market-skeleton-surface: var(--trading-surface-2);
  --market-skeleton-line: var(--trading-surface-3);
  --market-skeleton-highlight: var(--trading-border-strong);
  min-width: 0;
  min-height: 180px;
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  background: var(--market-skeleton-surface);
  color: var(--trading-muted);
  overflow: hidden;
}

.headerRow {
  min-height: 28px;
  display: grid;
  grid-template-columns: minmax(92px, 1fr) minmax(86px, 0.9fr) minmax(86px, 0.9fr);
  gap: 8px;
}

.rows {
  display: grid;
  gap: 6px;
}

.headerRow span,
.rows span {
  position: relative;
  overflow: hidden;
  border-radius: 4px;
  background: var(--market-skeleton-line);
}

.headerRow span {
  height: 12px;
}

.rows span {
  height: 20px;
}

.askRow {
  box-shadow: inset 3px 0 0 var(--trading-sell);
}

.bidRow {
  box-shadow: inset 3px 0 0 var(--trading-buy);
}

.headerRow span::after,
.rows span::after {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, var(--market-skeleton-highlight), transparent);
  opacity: 0.34;
  transform: translateX(-100%);
  animation: market-skeleton-scan 1400ms ease-in-out infinite;
  content: "";
}

@keyframes market-skeleton-scan {
  0% {
    transform: translateX(-100%);
  }

  100% {
    transform: translateX(100%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .headerRow span::after,
  .rows span::after {
    animation: none;
    transform: none;
    opacity: 0.18;
  }
}

@media (max-width: 760px) {
  .orderBookSkeleton {
    min-height: 150px;
    padding: 8px 6px;
  }

  .headerRow {
    grid-template-columns: minmax(64px, 1fr) minmax(54px, 0.9fr);
  }

  .headerRow span:nth-child(3) {
    display: none;
  }
}
```

## Step 1.3：修改 MarketSidePanel import

在 `fx-trading-platform/apps/web/src/components/market-side-panel/MarketSidePanel.tsx` 中，把：

```ts
import { OrderBookSkeleton } from '../../pages/trading/components/TerminalSkeleton'
```

改成：

```ts
import { OrderBookSkeleton } from './OrderBookSkeleton'
```

## Step 1.4：让 TerminalSkeleton 只负责账户表格 skeleton

把 `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.tsx` 改成：

```tsx
import styles from './TerminalSkeleton.module.css'

type SkeletonRowsProps = {
  rows?: number
}

export function TableSkeleton({ rows = 4 }: SkeletonRowsProps) {
  return (
    <div className={styles.tableSkeleton} role="status" aria-live="polite" aria-label="正在同步账户表格">
      <div className={styles.tableHeader} aria-hidden="true">
        <span />
        <span />
        <span />
        <span />
      </div>
      <div className={styles.tableRows} aria-hidden="true">
        {Array.from({ length: rows }, (_, index) => (
          <span key={index} />
        ))}
      </div>
    </div>
  )
}
```

把 `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.module.css` 改成只保留 table skeleton：

```css
.tableSkeleton {
  --terminal-skeleton-surface: var(--trading-surface-2);
  --terminal-skeleton-line: var(--trading-surface-3);
  --terminal-skeleton-highlight: var(--trading-border-strong);
  min-width: 0;
  min-height: 180px;
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  background: var(--terminal-skeleton-surface);
  color: var(--trading-muted);
  overflow: hidden;
}

.tableHeader {
  min-height: 28px;
  display: grid;
  grid-template-columns: repeat(4, minmax(54px, 1fr));
  gap: 8px;
}

.tableRows {
  display: grid;
  gap: 6px;
}

.tableHeader span,
.tableRows span {
  position: relative;
  overflow: hidden;
  border-radius: 4px;
  background: var(--terminal-skeleton-line);
}

.tableHeader span {
  height: 12px;
}

.tableRows span {
  height: 28px;
}

.tableHeader span::after,
.tableRows span::after {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, var(--terminal-skeleton-highlight), transparent);
  opacity: 0.34;
  transform: translateX(-100%);
  animation: terminal-skeleton-scan 1400ms ease-in-out infinite;
  content: "";
}

@keyframes terminal-skeleton-scan {
  0% {
    transform: translateX(-100%);
  }

  100% {
    transform: translateX(100%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .tableHeader span::after,
  .tableRows span::after {
    animation: none;
    transform: none;
    opacity: 0.18;
  }
}

@media (max-width: 760px) {
  .tableSkeleton {
    min-height: 150px;
    padding: 8px 6px;
  }
}
```

## Step 1.5：更新 TerminalSkeleton 测试

在 `fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.test.ts` 中，把第一个测试改成：

```ts
  it('exports the table skeleton used by account regions', () => {
    assert.equal(existsSync(componentPath), true, 'TerminalSkeleton.tsx should exist')

    const source = readFileSync(componentPath, 'utf8')
    assert.match(source, /export function TableSkeleton/)
    assert.doesNotMatch(source, /export function OrderBookSkeleton/)
    assert.match(source, /role="status"/)
    assert.match(source, /aria-live="polite"/)
  })
```

把第二个测试改成：

```ts
  it('uses stable dimensions and reduced-motion support', () => {
    assert.equal(existsSync(stylesPath), true, 'TerminalSkeleton.module.css should exist')

    const styles = readFileSync(stylesPath, 'utf8')
    assert.match(styles, /prefers-reduced-motion:\s*reduce/)
    assert.match(styles, /--terminal-skeleton-surface/)
    assert.match(styles, /min-height/)
    assert.match(styles, /@keyframes terminal-skeleton-scan/)
    assert.doesNotMatch(styles, /\.orderBookSkeleton/)
  })
```

## Step 1.6：运行聚焦验证

运行：

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\components\market-side-panel\OrderBook.test.ts src\components\market-side-panel\quoteMarketDataAdapter.test.ts src\pages\trading\components\TerminalSkeleton.test.ts"
```

预期：

- PASS。

## Step 1.7：检查 page 目录反向依赖

运行：

```powershell
rg -n -F "../../pages/trading" fx-trading-platform/apps/web/src/components/market-side-panel
```

预期：

- 无输出。

---

# Task 2：Lazy split ChartDrawingToolbar

**目标：** 把绘图工具栏从 `TradingPage` 初始 chunk 中拆出去，同时保持图表区域布局稳定。

**涉及文件：**

- `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.tsx`
- `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.module.css`
- `fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.test.ts`

## Step 2.1：先写失败测试

在 `ChartWorkspace.test.ts` 添加：

```ts
  it('lazy loads the drawing toolbar with a stable rail fallback', () => {
    assert.match(workspaceSource, /const ChartDrawingToolbar = lazy\(/)
    assert.match(workspaceSource, /import\('\.\/ChartDrawingToolbar'\)/)
    assert.match(workspaceSource, /function DrawingToolbarFallback\(\)/)
    assert.match(workspaceSource, /className=\{styles\.drawingToolbarLoading\}/)
    assert.match(workspaceStyles, /\.drawingToolbarLoading\s*{/)
  })
```

运行：

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\pages\trading\components\ChartWorkspace.test.ts"
```

预期：

- FAIL。
- 因为当前 `ChartDrawingToolbar` 仍是静态 import。

## Step 2.2：把静态导入改成 lazy

在 `ChartWorkspace.tsx` 删除：

```ts
import { ChartDrawingToolbar } from './ChartDrawingToolbar'
```

在 `IndicatorSettingsModal` lazy 声明后添加：

```ts
const ChartDrawingToolbar = lazy(() =>
  import('./ChartDrawingToolbar').then((module) => ({ default: module.ChartDrawingToolbar }))
)
```

## Step 2.3：用 Suspense 包住绘图工具栏

把原来的 `<ChartDrawingToolbar ... />` 替换为：

```tsx
        <Suspense fallback={<DrawingToolbarFallback />}>
          <ChartDrawingToolbar
            drawingsHidden={drawingsHidden}
            indicatorsHidden={indicatorsHidden}
            settings={settings.drawingToolSettings}
            onDrawingToolChange={handleDrawingToolChange}
            onDrawingMagnetModeChange={onDrawingMagnetModeChange}
            onClearDrawings={handleClearDrawings}
            onToggleAllHidden={handleToggleAllHidden}
            onToggleDrawingsHidden={() => setDrawingsHidden((hidden) => !hidden)}
            onToggleIndicatorsHidden={() => setIndicatorsHidden((hidden) => !hidden)}
          />
        </Suspense>
```

在文件底部 `canUseNativeFullscreen` 前添加：

```tsx
function DrawingToolbarFallback() {
  return (
    <div className={styles.drawingToolbarLoading} role="status" aria-label="正在加载绘图工具">
      <span aria-hidden="true" />
      <span aria-hidden="true" />
      <span aria-hidden="true" />
      <span aria-hidden="true" />
    </div>
  )
}
```

## Step 2.4：添加稳定 fallback 样式

在 `ChartWorkspace.module.css` 添加：

```css
.drawingToolbarLoading {
  width: 46px;
  min-width: 46px;
  min-height: 320px;
  display: grid;
  align-content: start;
  justify-items: center;
  gap: 8px;
  padding: 8px 6px;
  border-right: 1px solid var(--trading-border);
  background: var(--trading-surface-2);
}

.drawingToolbarLoading span {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--trading-surface-3);
}
```

如果文件已有 `@media (max-width: 768px)`，在其中加入：

```css
  .drawingToolbarLoading {
    display: none;
  }
```

## Step 2.5：运行聚焦测试

运行：

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\pages\trading\components\ChartWorkspace.test.ts src\pages\trading\components\ChartDrawingToolbar.test.ts src\pages\trading\components\KLineChartPanel.test.ts"
```

预期：

- PASS。

## Step 2.6：构建并确认新 chunk

运行：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
Get-ChildItem -LiteralPath "fx-trading-platform\apps\web\dist\assets" -File | Where-Object { $_.Name -match "TradingPage|ChartDrawingToolbar|IndicatorSettingsModal|MobileTradingTerminal" } | Select-Object Name,Length
```

预期：

- 出现 `ChartDrawingToolbar-*.js`。
- `TradingPage-*.js` 仍存在。
- `MobileTradingTerminal-*.js` 仍存在。
- `IndicatorSettingsModal-*.js` 仍存在。

---

# Task 3：新增 bundle budget 校验

**目标：** 把 chunk 拆分结果变成自动化约束，防止后续回退。

**涉及文件：**

- 新增：`fx-trading-platform/scripts/check-web-bundle-budget.mjs`
- 修改：`fx-trading-platform/package.json`

## Step 3.1：先确认脚本缺失

运行：

```powershell
cmd.exe /d /s /c "node fx-trading-platform\scripts\check-web-bundle-budget.mjs"
```

预期：

- FAIL。
- 原因是脚本尚不存在。

## Step 3.2：新增 bundle budget 脚本

创建 `fx-trading-platform/scripts/check-web-bundle-budget.mjs`：

```js
import { existsSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')
const assetsDir = join(projectRoot, 'apps/web/dist/assets')

const budgets = [
  {
    label: 'TradingPage JS',
    pattern: /^TradingPage-.*\.js$/,
    maxBytes: 380_000
  },
  {
    label: 'MobileTradingTerminal JS',
    pattern: /^MobileTradingTerminal-.*\.js$/,
    maxBytes: 20_000
  },
  {
    label: 'IndicatorSettingsModal JS',
    pattern: /^IndicatorSettingsModal-.*\.js$/,
    maxBytes: 20_000
  },
  {
    label: 'ChartDrawingToolbar JS',
    pattern: /^ChartDrawingToolbar-.*\.js$/,
    maxBytes: 80_000
  }
]

if (!existsSync(assetsDir)) {
  fail(`Missing build assets directory: ${assetsDir}. Run web:build first.`)
}

const files = readdirSync(assetsDir)
const failures = []
const report = []

for (const budget of budgets) {
  const matches = files.filter((file) => budget.pattern.test(file))
  if (matches.length === 0) {
    failures.push(`Missing required chunk: ${budget.label}`)
    continue
  }

  for (const file of matches) {
    const size = statSync(join(assetsDir, file)).size
    report.push({ label: budget.label, file, size, maxBytes: budget.maxBytes })
    if (size > budget.maxBytes) {
      failures.push(`${budget.label} is ${size} bytes, expected <= ${budget.maxBytes} bytes (${file})`)
    }
  }
}

for (const item of report) {
  console.log(`${item.label}: ${item.size}/${item.maxBytes} bytes (${item.file})`)
}

if (failures.length > 0) {
  fail(`Bundle budget failed:\n${failures.map((failure) => `- ${failure}`).join('\n')}`)
}

console.log('Bundle budget passed.')

function fail(message) {
  console.error(message)
  process.exit(1)
}
```

## Step 3.3：添加 npm script

在 `fx-trading-platform/package.json` 的 `scripts` 中加入：

```json
"web:bundle-budget": "npm run web:build && node scripts/check-web-bundle-budget.mjs"
```

不要改其他 scripts。

## Step 3.4：运行预算校验

运行：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:bundle-budget"
```

预期：

- PASS。
- 输出包含 `Bundle budget passed.`
- 输出包含：
  - `TradingPage JS`
  - `MobileTradingTerminal JS`
  - `IndicatorSettingsModal JS`
  - `ChartDrawingToolbar JS`

---

# Task 4：扩展架构校验

**目标：** 把 `market-side-panel` 不依赖 `pages/trading` 变成架构校验规则。

**涉及文件：**

- `fx-trading-platform/scripts/verify-architecture.mjs`

## Step 4.1：添加必须存在的内容检查

在 `contentChecks` 中加入：

```js
  ['apps/web/src/components/market-side-panel/MarketSidePanel.tsx', './OrderBookSkeleton'],
  ['apps/web/src/components/market-side-panel/OrderBookSkeleton.tsx', 'export function OrderBookSkeleton']
```

## Step 4.2：添加禁止 import 检查

在 `forbiddenContentChecks` 后添加：

```js
const forbiddenFrontendImports = [
  ['apps/web/src/components/market-side-panel/MarketSidePanel.tsx', '../../pages/trading'],
  ['apps/web/src/components/market-side-panel/quoteMarketDataAdapter.ts', '../../pages/trading'],
  ['apps/web/src/components/market-side-panel/quoteMarketDataSnapshot.ts', '../../pages/trading']
]
```

在现有 forbidden 检查循环后添加：

```js
for (const [file, forbidden] of forbiddenFrontendImports) {
  const path = join(root, file)
  if (!existsSync(path)) {
    failures.push(`Missing frontend import target: ${file}`)
    continue
  }
  const content = readFileSync(path, 'utf8')
  if (content.includes(forbidden)) {
    failures.push(`Forbidden frontend import "${forbidden}" found in ${file}`)
  }
}
```

## Step 4.3：运行架构校验

运行：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

预期：

- PASS。
- 输出 `Architecture verification passed.`

---

# Task 5：浏览器 QA 和完成报告

**目标：** 用真实页面证明本次优化没有造成空白、遮挡、懒加载卡住或 console error。

**涉及文件：**

- 新增：`fx-trading-platform/docs/frontend-follow-up-optimization-completion-2026-06-13.md`

## Step 5.1：启动前端服务

运行：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:dev"
```

如果 `5173` 被占用，使用 Vite 输出的新端口。

用 HTTP 检查：

```powershell
try { (Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:5173/trading' -TimeoutSec 5).StatusCode } catch { $_.Exception.Message }
```

预期：

- `200`

## Step 5.2：桌面浏览器 QA

使用 Codex Browser 插件打开：

```text
http://127.0.0.1:5173/trading
```

检查：

- Markets 侧栏可见。
- Symbol header 可见。
- `KLineCharts 图表区` 可见且非空。
- `BTC-USDT 交易面板` 可见且非空。
- 点击图表工具栏里的 `设置`。
- `指标设置` 弹窗出现。
- 关闭弹窗。
- 绘图工具栏正常出现，fallback 没有卡住。
- console error 数量为 `0`。

记录：

- viewport 尺寸。
- 图表区域宽高。
- 交易面板宽高。
- console error 数量。

## Step 5.3：移动端浏览器 QA

设置 viewport：

```text
390 x 844
```

打开：

```text
http://127.0.0.1:5173/trading
```

检查：

- `移动端交易终端` 可见。
- 桌面 `交易工作台` 不可见。
- `正在加载移动端交易终端` 没有卡住。
- 底部移动快捷操作栏可见。
- console error 数量为 `0`。

记录：

- viewport 尺寸。
- 移动端终端宽高。
- fallback 是否残留。
- console error 数量。

## Step 5.4：最终全量验证

运行：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:bundle-budget"
cmd.exe /d /s /c "pnpm.cmd type-check"
```

预期：

- 全部 PASS。
- `web:test` 报告所有测试通过。
- `verify:architecture` 输出 `Architecture verification passed.`
- `web:bundle-budget` 输出 `Bundle budget passed.`
- `type-check` exit code 为 `0`。

## Step 5.5：写完成报告

创建 `fx-trading-platform/docs/frontend-follow-up-optimization-completion-2026-06-13.md`。

报告内容使用以下结构，并把括号内说明替换成真实执行结果：

```markdown
# 前端后续优化完成报告

日期：2026-06-13

## 已完成

- `OrderBookSkeleton` 已移入 `components/market-side-panel`。
- `MarketSidePanel` 已移除对 `pages/trading` 的反向依赖。
- `ChartDrawingToolbar` 已 lazy split，并添加稳定 rail fallback。
- 已新增 `web:bundle-budget`，防止交易终端 chunk 回退。
- 已扩展架构校验，覆盖 market-side-panel 边界。

## 命令验证

- `npm.cmd --prefix fx-trading-platform run web:test`：PASS，填写真实测试数量。
- `npm.cmd --prefix fx-trading-platform run verify:architecture`：PASS。
- `npm.cmd --prefix fx-trading-platform run web:bundle-budget`：PASS。
- `pnpm.cmd type-check`：PASS。

## Browser 验证

桌面 `/trading`：

- Viewport：填写真实尺寸。
- Chart area：填写真实宽高。
- Trade panel：填写真实宽高。
- 指标设置弹窗：可打开。
- Console errors：0。

移动 `/trading`：

- Viewport：填写真实尺寸。
- Mobile terminal：填写真实宽高。
- Desktop workspace visible：no。
- Loading fallback stuck：no。
- Console errors：0。

## 说明

- 根目录图表库大类拆分没有纳入本次优化。
- 没有新增运行时依赖。
```

不要在最终报告里保留“填写真实...”这类说明文字，必须替换成真实数据。

---

## 最终自检清单

完成所有任务后运行：

```powershell
rg -n -F "../../pages/trading" fx-trading-platform/apps/web/src/components/market-side-panel
rg -n "const ChartDrawingToolbar = lazy|import\('\./ChartDrawingToolbar'\)" fx-trading-platform/apps/web/src/pages/trading/components/ChartWorkspace.tsx
Get-ChildItem fx-trading-platform/apps/web/dist/assets | Where-Object { $_.Name -match "ChartDrawingToolbar" }
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:bundle-budget"
cmd.exe /d /s /c "pnpm.cmd type-check"
```

预期：

- 第一条 `rg` 无输出。
- 第二条 `rg` 能看到 lazy import。
- `dist/assets` 中有 `ChartDrawingToolbar-*.js`。
- 所有命令 PASS。

---

## 交给用户的最终回复模板

执行完成后，用中文回复：

```markdown
已完成后续前端优化：

- `MarketSidePanel` 已脱离 `pages/trading`，`OrderBookSkeleton` 移到 `market-side-panel` 内部。
- `ChartDrawingToolbar` 已 lazy split，构建产物出现独立 `ChartDrawingToolbar-*.js` chunk。
- 新增 `web:bundle-budget`，防止 `TradingPage` 和关键 lazy chunk 回退。
- 架构校验已覆盖 `market-side-panel` 反向依赖。

验证：
- web:test：PASS，填写真实测试数量。
- verify:architecture：PASS。
- web:bundle-budget：PASS。
- pnpm type-check：PASS。
- Browser 桌面/移动 `/trading`：PASS，console errors 0。
```

最终回复里的“填写真实测试数量”必须替换成真实命令输出。

---

## 执行交接

中文计划已保存到：

`fx-trading-platform/docs/superpowers/plans/2026-06-13-frontend-follow-up-optimization-zh.md`

推荐执行方式：

1. **Subagent-Driven（推荐）**：每个 Task 派一个新子代理执行，主线程做 review 和集成。
2. **Inline Execution**：在当前线程用 `superpowers:executing-plans` 按 Task 执行，每个 Task 后做验证 checkpoint。

开始执行前必须先选择一种执行方式。
