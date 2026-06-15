# 全栈后续优化 Implementation Plan

> **给 agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务执行本计划。所有步骤使用 checkbox（`- [ ]`）语法跟踪。

**Goal:** 在不改变交易行为的前提下，处理 2026-06-12 全栈审计后仍然存在的前后端架构债务、边界反依赖、低价值注释和大文件风险。

**Architecture:** 这是一次“清理与边界收敛”项目，不是功能项目。先处理小而可测的死代码，再收敛 `features/market` 的所有权，然后降低后台 admin/audit 耦合，最后拆分交易页和交易面板中的高密度 UI。每个任务必须保留已经修复的交易行为：选中 symbol 下单、`OPEN` 条件平仓、挂单抢占、后台资金幂等、重复订单回读、游客 watch-only 登录提示。

**Tech Stack:** React 19, Vite, TypeScript, Node `node:test`, Spring Boot 3, Java 21, MyBatis-Plus, Flyway, Hutool JSON, Maven, PowerShell on Windows.

---

## 当前状态快照

执行前先读这个快照，避免重复分析已经确认过的问题。

### 已修复的问题

- 老的 `apps/web/src/pages/trade/TradePage.tsx` 已不存在。
- 老的 `apps/web/src/components/order-panel/OrderPanel.tsx` 已不存在。
- `/trade` 已重定向到 `/trading`。
- `parseSymbolAssets` 和 `formatDecimal` 已集中到 `apps/web/src/features/trading/utils`。
- 下单 symbol fallback 已有 `tradePanelMarket.test.ts` 覆盖。
- `offline-preview` 已从交易 UI 状态中移除。
- `FxBaseMapper.findAll(...)` 已使用 sort whitelist，不再直接接收 raw `orderColumn`。
- 平仓已使用 `PositionRepository.closeIfOpen(...)`。
- 挂单执行已使用 `OrderRepository.claimPending(...)`。
- 订单幂等已捕获 `DataIntegrityViolationException` 并回读已存在订单。
- 后台资金幂等已有 `V25__admin_fund_operation_idempotency.sql` 和 `insertIfAbsent(...)`。
- `AdminFeatureOperationService` 已使用 `List<AdminFeatureActionHandler>` 注册动作处理器。

### 仍然存在的问题

- `apps/web/src/styles.css` 仍有已经退役的 `.order-panel`、`.order-actions` 等全局样式。
- `apps/web/src/components/market-side-panel/MarketSidePanel.tsx` 仍从 `pages/trading/components/TerminalSkeleton` 导入 skeleton，形成共享组件反向依赖页面模块的问题。
- `apps/web/src/features/market/tradingMarketAdapters.ts` 仍从 `components/market-side-panel/types` 导入市场数据类型，feature 反向依赖 UI 组件目录。
- `TradePanel.tsx` 仍从 `components/market-side-panel/marketDataStore` 导入市场数据 store。
- `AdminFeatureCatalogService.java` 仍是约 725 行的大型静态 catalog builder。
- audit `details` 构造只完成了一部分统一，多个 admin service 仍手写 JSON 或使用 `JSONUtil.toJsonStr(MapUtil.builder())`。
- `TradingPage.tsx` 仍约 490 行，`TradePanel.tsx` 仍约 315 行。
- 根目录 KLineCharts 核心文件仍偏大：`src/Store.ts`、`src/Chart.ts`、`src/common/EventHandler.ts`。
- 后端生产代码仍存在低价值模板注释，例如“执行 xxx 业务流程”这类没有解释业务约束的注释。
- `RealtimeCandleRepository` 中仍存在一个 `JdbcTemplate` 使用点；这次只记录为后续治理项，除非执行者确认其行为和事务边界，否则不在本计划内强行替换。

## 非目标

- 不改登录鉴权、登录提示、游客 watch-only 行为、交易下单语义、已应用的 Flyway 历史迁移、KLineCharts 渲染算法。
- 不把所有注释机械改成中文。
- 不删除与本计划无关的预存死代码。
- 不一次性重写大文件。只有测试或架构规则能证明边界时才拆分。
- 不修改已应用过的旧 `V*.sql` migration；如需数据库变更，必须新增 versioned migration。

## 执行规则

- 推荐新建分支：`codex/fullstack-followup-optimization`。
- 每个任务开始前运行：

```powershell
git status --short
```

- 如果工作区已有用户改动，不要回滚；需要围绕现有改动继续。
- 每个任务必须先增加一个失败测试或失败架构规则，再实现最小改动让它通过。
- 每个任务完成后只运行该任务的聚焦验证；全部任务完成后再运行最终验证。
- 如果用户需要 commit，每个任务单独 commit，commit 范围只包含该任务涉及的文件。
- Windows 下运行 Node/npm 命令优先使用 `cmd.exe /d /s /c` 包裹 `npm.cmd`、`pnpm.cmd`、`mvn.cmd`。

## 官方参考

- React `lazy` 组件必须在 `Suspense` 边界内渲染，移动端 route chunk 继续使用此模式：https://react.dev/reference/react/lazy
- Spring 声明式事务边界应保留在由 Spring 管理的 public service 方法上：https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
- Flyway versioned migrations 一经应用会进入 schema history，后续变更用新 migration 向前滚动：https://documentation.red-gate.com/fd/versioned-migrations-273973333.html

---

## Task 1: 删除退役的前端 OrderPanel 全局样式

**目的：** 完成旧 `/trade` 和旧 `OrderPanel` 的清理，防止后续误以为 `.order-panel` 仍是有效 UI。

**文件：**

- Modify: `apps/web/src/app/App.test.ts`
- Modify: `apps/web/src/styles.css`

- [ ] **Step 1: 增加失败测试**

在 `apps/web/src/app/App.test.ts` 中找到已有的 `sourcePath` 或同类文件读取逻辑，增加：

```ts
const stylesPath = join(currentDir, '..', 'styles.css')
```

追加测试：

```ts
  it('removes retired order panel global styles', () => {
    const styles = readFileSync(stylesPath, 'utf8')

    assert.doesNotMatch(styles, /\.order-panel\b/)
    assert.doesNotMatch(styles, /\.order-actions\b/)
  })
```

- [ ] **Step 2: 运行聚焦测试，确认失败**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\app\App.test.ts"
```

预期：`removes retired order panel global styles` 失败，因为 `.order-panel` 和 `.order-actions` 仍在 `styles.css`。

- [ ] **Step 3: 删除退役 selector**

从 `apps/web/src/styles.css` 删除这些 selector block：

```css
.order-panel {
  padding-bottom: 14px;
}

.order-panel label {
  display: grid;
  gap: 6px;
  padding: 12px 14px 0;
  color: #50606d;
  font-size: 13px;
}

.order-panel input,
.order-panel select {
  min-height: 38px;
  border: 1px solid #d6dee7;
  border-radius: 6px;
  padding: 0 10px;
  background: #ffffff;
}

.order-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 16px 14px 0;
}

.order-actions button {
  min-height: 40px;
  border: 0;
  border-radius: 8px;
  color: #ffffff;
  font-weight: 700;
}
```

删除 `.two-inputs` 前先确认：

```powershell
rg -n "two-inputs" fx-trading-platform\apps\web\src
```

预期：如果唯一命中是 `styles.css`，删除 `.two-inputs` block；如果 JSX 中仍使用，保留。

- [ ] **Step 4: 验证测试通过**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\app\App.test.ts"
```

预期：PASS。

- [ ] **Step 5: grep 验证无生产引用**

```powershell
rg -n "\.order-panel|\.order-actions|components\\order-panel|pages\\trade" fx-trading-platform\apps\web\src
```

预期：生产源码无命中。测试文件中为退役检查保留的命中可以接受。

- [ ] **Step 6: 可选 commit**

```powershell
git add fx-trading-platform/apps/web/src/app/App.test.ts fx-trading-platform/apps/web/src/styles.css
git commit -m "chore(web): remove retired order panel styles"
```

---

## Task 2: 将 TerminalSkeleton 从页面目录移到共享 loading 组件

**目的：** 移除 `components/market-side-panel` 对 `pages/trading/components` 的反向依赖。

**文件：**

- Create: `apps/web/src/components/loading/TerminalSkeleton.tsx`
- Create: `apps/web/src/components/loading/TerminalSkeleton.module.css`
- Create: `apps/web/src/components/loading/TerminalSkeleton.test.ts`
- Modify: `apps/web/src/pages/trading/components/TerminalSkeleton.tsx`
- Modify: `apps/web/src/pages/trading/components/TerminalSkeleton.test.ts`
- Modify: `apps/web/src/components/market-side-panel/MarketSidePanel.tsx`
- Modify: `apps/web/src/components/market-side-panel/OrderBook.test.ts`

- [ ] **Step 1: 增加边界失败测试**

在 `apps/web/src/components/market-side-panel/OrderBook.test.ts` 追加：

```ts
  it('does not import loading skeletons from page-owned trading modules', () => {
    const sidePanelSource = readFileSync(join(currentDir, 'MarketSidePanel.tsx'), 'utf8')

    assert.doesNotMatch(sidePanelSource, /pages\/trading/)
    assert.doesNotMatch(sidePanelSource, /\.\.\/\.\.\/pages\/trading/)
    assert.match(sidePanelSource, /components\/loading\/TerminalSkeleton/)
  })
```

- [ ] **Step 2: 确认测试失败**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\components\market-side-panel\OrderBook.test.ts"
```

预期：失败，因为 `MarketSidePanel.tsx` 仍导入 `../../pages/trading/components/TerminalSkeleton`。

- [ ] **Step 3: 创建共享 skeleton**

把当前 `apps/web/src/pages/trading/components/TerminalSkeleton.tsx` 的实现移动到：

```text
apps/web/src/components/loading/TerminalSkeleton.tsx
```

新文件中的 CSS import 使用：

```ts
import styles from './TerminalSkeleton.module.css'
```

把 CSS 文件移动到：

```text
apps/web/src/components/loading/TerminalSkeleton.module.css
```

- [ ] **Step 4: 保留旧路径兼容 re-export**

将 `apps/web/src/pages/trading/components/TerminalSkeleton.tsx` 改为：

```ts
export { OrderBookSkeleton, TableSkeleton } from '../../../components/loading/TerminalSkeleton'
```

删除旧 CSS 文件前先确认无引用：

```powershell
rg -n "TerminalSkeleton\.module\.css|pages/trading/components/TerminalSkeleton\.module\.css" fx-trading-platform\apps\web\src
```

预期：除旧实现文件外无引用。

- [ ] **Step 5: 修改 market side-panel import**

在 `apps/web/src/components/market-side-panel/MarketSidePanel.tsx` 中改为：

```ts
import { OrderBookSkeleton } from '../loading/TerminalSkeleton'
```

- [ ] **Step 6: 增加共享 skeleton 测试**

创建 `apps/web/src/components/loading/TerminalSkeleton.test.ts`：

```ts
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const componentPath = join(currentDir, 'TerminalSkeleton.tsx')
const stylesPath = join(currentDir, 'TerminalSkeleton.module.css')

describe('shared terminal skeleton components', () => {
  it('exports order book and table skeleton surfaces from shared components', () => {
    assert.equal(existsSync(componentPath), true)

    const source = readFileSync(componentPath, 'utf8')
    assert.match(source, /export function OrderBookSkeleton/)
    assert.match(source, /export function TableSkeleton/)
    assert.match(source, /role="status"/)
    assert.match(source, /aria-live="polite"/)
  })

  it('keeps skeleton styling outside page-owned trading modules', () => {
    assert.equal(existsSync(stylesPath), true)

    const styles = readFileSync(stylesPath, 'utf8')
    assert.match(styles, /prefers-reduced-motion:\s*reduce/)
    assert.match(styles, /--terminal-skeleton-surface/)
    assert.match(styles, /min-height/)
    assert.match(styles, /@keyframes terminal-skeleton-scan/)
  })
})
```

- [ ] **Step 7: 更新页面旧测试**

将 `apps/web/src/pages/trading/components/TerminalSkeleton.test.ts` 改成断言页面文件只是兼容导出：

```ts
  it('keeps page skeleton imports as compatibility re-exports', () => {
    const source = readFileSync(componentPath, 'utf8')

    assert.match(source, /export \{ OrderBookSkeleton, TableSkeleton \}/)
    assert.match(source, /components\/loading\/TerminalSkeleton/)
  })
```

删除原先要求页面文件内存在 `role="status"` 的断言，因为实现已经移动到 `components/loading`。

- [ ] **Step 8: 运行聚焦测试**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\components\loading\TerminalSkeleton.test.ts src\pages\trading\components\TerminalSkeleton.test.ts src\components\market-side-panel\OrderBook.test.ts"
```

预期：PASS。

- [ ] **Step 9: 可选 commit**

```powershell
git add fx-trading-platform/apps/web/src/components/loading fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.tsx fx-trading-platform/apps/web/src/pages/trading/components/TerminalSkeleton.test.ts fx-trading-platform/apps/web/src/components/market-side-panel/MarketSidePanel.tsx fx-trading-platform/apps/web/src/components/market-side-panel/OrderBook.test.ts
git commit -m "refactor(web): move terminal skeletons to shared loading components"
```

---

## Task 3: 将市场数据类型移入 `features/market`

**目的：** 让 `features/market` 不再依赖 UI 组件目录提供 domain type。

**文件：**

- Create: `apps/web/src/features/market/marketDataTypes.ts`
- Modify: `apps/web/src/features/market/tradingMarketAdapters.ts`
- Modify: `apps/web/src/components/market-side-panel/types.ts`
- Modify: `apps/web/src/features/market/tradingMarketApi.test.ts`
- Modify: `apps/web/src/components/market-side-panel/quoteMarketDataAdapter.test.ts`

- [ ] **Step 1: 增加失败架构断言**

在 `apps/web/src/features/market/tradingMarketApi.test.ts` 中补齐文件读取 imports：

```ts
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
```

加入：

```ts
const currentDir = dirname(fileURLToPath(import.meta.url))

describe('market feature boundaries', () => {
  it('does not import market data types from component folders', () => {
    const source = readFileSync(join(currentDir, 'tradingMarketAdapters.ts'), 'utf8')

    assert.doesNotMatch(source, /components\/market-side-panel\/types/)
    assert.match(source, /from '\.\/marketDataTypes'/)
  })
})
```

- [ ] **Step 2: 确认测试失败**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\features\market\tradingMarketApi.test.ts"
```

预期：失败，因为 `tradingMarketAdapters.ts` 仍从 `../../components/market-side-panel/types` 导入。

- [ ] **Step 3: 创建 `marketDataTypes.ts`**

将 `apps/web/src/components/market-side-panel/types.ts` 中的类型移动到：

```text
apps/web/src/features/market/marketDataTypes.ts
```

新文件至少导出：

```ts
export type OrderBookSide = 'bid' | 'ask'

export type OrderBookLevel = {
  price: number
  amount: number
  side: OrderBookSide
}

export type TradeItem = {
  id: string
  price: number
  amount: number
  side: 'buy' | 'sell'
  time: number
}

export type MarketDataSnapshot = {
  bids: Array<{ price: number; amount: number }>
  asks: Array<{ price: number; amount: number }>
  lastPrice: number
  lastPriceDirection: 'up' | 'down' | 'flat'
  recentTrades: TradeItem[]
  updatedAt: number
}
```

如果旧 `types.ts` 还有其他导出，一并移动。

- [ ] **Step 4: 旧 `types.ts` 改成兼容导出**

```ts
export type {
  MarketDataSnapshot,
  OrderBookLevel,
  OrderBookSide,
  TradeItem
} from '../../features/market/marketDataTypes'
```

如果旧文件有额外类型，补进这个 re-export。

- [ ] **Step 5: 修改 adapters import**

`apps/web/src/features/market/tradingMarketAdapters.ts` 改为：

```ts
import type { MarketDataSnapshot, TradeItem } from './marketDataTypes'
```

- [ ] **Step 6: 验证**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\features\market\tradingMarketApi.test.ts src\components\market-side-panel\quoteMarketDataAdapter.test.ts"
```

预期：PASS。

- [ ] **Step 7: 可选 commit**

```powershell
git add fx-trading-platform/apps/web/src/features/market/marketDataTypes.ts fx-trading-platform/apps/web/src/features/market/tradingMarketAdapters.ts fx-trading-platform/apps/web/src/components/market-side-panel/types.ts fx-trading-platform/apps/web/src/features/market/tradingMarketApi.test.ts fx-trading-platform/apps/web/src/components/market-side-panel/quoteMarketDataAdapter.test.ts
git commit -m "refactor(web): move market data types into market feature"
```

---

## Task 4: 将市场数据 store 和 adapter 所有权移入 `features/market`

**目的：** 让 `features/market` 负责 quote、order book、recent trades 的数据编排，`components/market-side-panel` 只负责 UI。

**文件：**

- Create: `apps/web/src/features/market/marketDataStore.ts`
- Create: `apps/web/src/features/market/quoteMarketDataSnapshot.ts`
- Create: `apps/web/src/features/market/quoteMarketDataAdapter.ts`
- Modify: `apps/web/src/components/market-side-panel/marketDataStore.ts`
- Modify: `apps/web/src/components/market-side-panel/quoteMarketDataSnapshot.ts`
- Modify: `apps/web/src/components/market-side-panel/quoteMarketDataAdapter.ts`
- Modify: `apps/web/src/features/trading/components/TradePanel.tsx`
- Modify: `apps/web/src/pages/trading/components/RightTradingPanel.tsx`
- Modify: related tests under `apps/web/src/components/market-side-panel`

- [ ] **Step 1: 增加失败边界测试**

在 `apps/web/src/features/market/tradingMarketApi.test.ts` 追加：

```ts
  it('keeps market store and adapters in the market feature', () => {
    const tradePanel = readFileSync(join(currentDir, '..', 'trading', 'components', 'TradePanel.tsx'), 'utf8')

    assert.doesNotMatch(tradePanel, /components\/market-side-panel\/marketDataStore/)
    assert.match(tradePanel, /features\/market\/marketDataStore/)
  })
```

- [ ] **Step 2: 确认测试失败**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\features\market\tradingMarketApi.test.ts"
```

预期：失败，因为 `TradePanel.tsx` 仍导入旧路径。

- [ ] **Step 3: 移动 store 和 adapter 文件**

移动实现，先不改行为：

```text
apps/web/src/components/market-side-panel/marketDataStore.ts -> apps/web/src/features/market/marketDataStore.ts
apps/web/src/components/market-side-panel/quoteMarketDataSnapshot.ts -> apps/web/src/features/market/quoteMarketDataSnapshot.ts
apps/web/src/components/market-side-panel/quoteMarketDataAdapter.ts -> apps/web/src/features/market/quoteMarketDataAdapter.ts
```

移动后修正 imports，目标方向如下：

```ts
import { subscribeOrderBook, subscribeQuote, subscribeRecentTrades } from '../../services/marketStream'
import { fetchMarketOrderBook, fetchMarketQuote, fetchMarketRecentTrades } from './tradingMarketApi'
import { mapOrderBookToMarketData, mapQuoteToTradingQuote, mapRecentTradesToMarketData } from './tradingMarketAdapters'
import type { BackendOrderBook, BackendQuote, BackendRecentTrade } from './tradingMarketAdapters'
import type { TradingQuote } from './tradingModels'
import type { MarketDataSnapshot } from './marketDataTypes'
```

- [ ] **Step 4: 旧组件目录文件改为兼容导出**

`apps/web/src/components/market-side-panel/marketDataStore.ts`：

```ts
export { marketDataStore, useMarketDataSnapshot } from '../../features/market/marketDataStore'
export type { MarketDataStoreState } from '../../features/market/marketDataStore'
```

`apps/web/src/components/market-side-panel/quoteMarketDataSnapshot.ts`：

```ts
export { createFallbackMarketDataSnapshot, createQuoteMarketDataSnapshot } from '../../features/market/quoteMarketDataSnapshot'
```

`apps/web/src/components/market-side-panel/quoteMarketDataAdapter.ts`：

```ts
export { startQuoteMarketDataAdapter } from '../../features/market/quoteMarketDataAdapter'
```

如果 `MarketDataStoreState` 未从新 store 导出，先在 `features/market/marketDataStore.ts` 导出。

- [ ] **Step 5: 修改直接 imports**

`apps/web/src/features/trading/components/TradePanel.tsx`：

```ts
import { useMarketDataSnapshot } from '../../market/marketDataStore'
```

`apps/web/src/pages/trading/components/RightTradingPanel.tsx` 中启动或释放 quote adapter 的 import 改成指向 `features/market/quoteMarketDataAdapter` 的正确相对路径。

- [ ] **Step 6: 运行聚焦测试**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\features\market\tradingMarketApi.test.ts src\components\market-side-panel\marketDataStore.test.ts src\components\market-side-panel\quoteMarketDataAdapter.test.ts src\features\trading\components\TradePanel.test.ts"
```

预期：PASS。

- [ ] **Step 7: grep 验证边界**

```powershell
rg -n "features/market.*components/market-side-panel|components/market-side-panel/marketDataStore|components/market-side-panel/quoteMarketDataAdapter" fx-trading-platform\apps\web\src
```

预期：生产代码不再直接导入旧 component-owned store/adapter。兼容 re-export 文件自身命中可以接受。

- [ ] **Step 8: 可选 commit**

```powershell
git add fx-trading-platform/apps/web/src/features/market fx-trading-platform/apps/web/src/components/market-side-panel fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx fx-trading-platform/apps/web/src/pages/trading/components/RightTradingPanel.tsx
git commit -m "refactor(web): move market data orchestration into market feature"
```

---

## Task 5: 完成 AuditDetailsBuilder 的统一采用

**目的：** 让后台写操作中的 audit `details` 构造统一，非 audit JSON 字段不在本任务内强行改造。

**文件：**

- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminFeatureOperationService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminUserService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminFundOrderService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminRiskCommandService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminMemberService.java`
- Modify: `backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java`

- [ ] **Step 1: 加强架构测试**

在 `ArchitectureRulesTest.adminAuditDetailsUseSharedJsonBuilder` 中扩大扫描范围：

```java
String featureOperation = Files.readString(Path.of(
    "src/main/java/com/fxplatform/admin/service/AdminFeatureOperationService.java"));
String userService = Files.readString(Path.of(
    "src/main/java/com/fxplatform/admin/service/AdminUserService.java"));
String fundOrderService = Files.readString(Path.of(
    "src/main/java/com/fxplatform/admin/service/AdminFundOrderService.java"));
String riskCommand = Files.readString(Path.of(
    "src/main/java/com/fxplatform/admin/service/AdminRiskCommandService.java"));
String memberService = Files.readString(Path.of(
    "src/main/java/com/fxplatform/admin/service/AdminMemberService.java"));
String combined = financeCommand
    + tradingCommand
    + featureOperation
    + userService
    + fundOrderService
    + riskCommand
    + memberService;
```

增加断言：

```java
assertThat(combined).doesNotContain("private String escape");
assertThat(combined).doesNotContain("return \"{\"");
assertThat(combined).doesNotContain("JSONUtil.toJsonStr(MapUtil.builder()");
assertThat(combined).contains("AuditDetailsBuilder.create()");
```

- [ ] **Step 2: 确认架构测试失败**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd -Dtest=ArchitectureRulesTest#adminAuditDetailsUseSharedJsonBuilder test"
```

预期：失败，因为仍有 service 手写 audit details。

- [ ] **Step 3: 改造 `AdminFeatureOperationService`**

增加 import：

```java
import com.fxplatform.audit.service.AuditDetailsBuilder;
```

将 audit details 构造改为：

```java
String auditDetails = AuditDetailsBuilder.create()
    .put("pageKey", pageKey)
    .put("action", action.key())
    .put("rowId", request.rowId())
    .put("reason", request.reason())
    .toJson();
```

`auditLogService.record(...)` 传入 `auditDetails`。

注意：`AdminFeatureRecordEntity.data` 的 `JSONUtil.toJsonStr(enrichPayload(...))` 不是 audit details，本任务不要改。

- [ ] **Step 4: 改造其他 details helper**

只修改这些文件中的 audit details helper：

```text
AdminUserService.java
AdminFundOrderService.java
AdminRiskCommandService.java
AdminMemberService.java
```

统一模式：

```java
private String details(String reason, String before, String after, String note) {
  return AuditDetailsBuilder.create()
      .put("reason", reason)
      .put("before", before)
      .put("after", after)
      .put("note", note)
      .toJson();
}
```

字段名必须匹配当前已有 JSON key。除非现有测试明确要求改名，否则不要改 key。

- [ ] **Step 5: 运行聚焦后端测试**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd -Dtest=ArchitectureRulesTest,AdminFeatureOperationServiceTest,AdminFundOrderServiceTest,AdminUserServiceTest,AdminRiskCommandServiceTest,AdminMemberServiceTest test"
```

如果某个测试类不存在，先查询真实测试类：

```powershell
Get-ChildItem -Recurse -File fx-trading-platform\backend\src\test\java -Filter '*ServiceTest.java' | Select-String -Pattern 'AdminUserService|AdminRiskCommandService|AdminMemberService|AdminFundOrderService'
```

预期：存在的相关测试全部 PASS。

- [ ] **Step 6: 可选 commit**

```powershell
git add fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service fx-trading-platform/backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java
git commit -m "refactor(backend): standardize admin audit details builder usage"
```

---

## Task 6: 按 catalog group 拆分 `AdminFeatureCatalogService`

**目的：** 将 725 行左右的静态 catalog 类拆成按业务组维护的小 builder，同时保持对外 API 不变。

**文件：**

- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminFeatureCatalogDsl.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminPermissionFeaturePages.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminProductFeaturePages.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminFinanceFeaturePages.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminMemberFeaturePages.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminContentFeaturePages.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminSettingsFeaturePages.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminFeatureCatalogService.java`
- Modify: `backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java`
- Modify: `backend/src/test/java/com/fxplatform/admin/service/AdminFeatureCatalogServiceTest.java` if present

- [ ] **Step 1: 增加大小与结构保护测试**

在 `ArchitectureRulesTest` 加：

```java
@Test
void adminFeatureCatalogIsSplitByPageGroup() throws Exception {
  Path catalogPath = Path.of("src/main/java/com/fxplatform/admin/service/AdminFeatureCatalogService.java");
  String catalog = Files.readString(catalogPath);
  long lines = Files.lines(catalogPath).count();

  assertThat(lines).isLessThanOrEqualTo(250);
  assertThat(catalog).contains("AdminPermissionFeaturePages.pages()");
  assertThat(catalog).contains("AdminProductFeaturePages.pages()");
  assertThat(catalog).contains("AdminFinanceFeaturePages.pages()");
  assertThat(catalog).contains("AdminMemberFeaturePages.pages()");
  assertThat(catalog).contains("AdminContentFeaturePages.pages()");
  assertThat(catalog).contains("AdminSettingsFeaturePages.pages()");
}
```

- [ ] **Step 2: 确认测试失败**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd -Dtest=ArchitectureRulesTest#adminFeatureCatalogIsSplitByPageGroup test"
```

预期：失败，因为类仍约 725 行，group builder 不存在。

- [ ] **Step 3: 创建 `AdminFeatureCatalogDsl`**

将 `AdminFeatureCatalogService` 底部 helper 方法搬到 package-private DSL 类：

```java
final class AdminFeatureCatalogDsl {

  private AdminFeatureCatalogDsl() {
  }

  static AdminFeaturePageResponse page(
      String key,
      String title,
      String group,
      List<AdminFeatureFieldResponse> fields,
      List<AdminFeatureColumnResponse> columns,
      List<AdminFeatureActionResponse> toolbarActions,
      List<AdminFeatureActionResponse> rowActions,
      List<Map<String, Object>> rows
  ) {
    return new AdminFeaturePageResponse(key, title, group, fields, columns, toolbarActions, rowActions, rows);
  }

  static List<AdminFeatureFieldResponse> fields(AdminFeatureFieldResponse... fields) {
    return List.of(fields);
  }

  static List<AdminFeatureColumnResponse> columns(AdminFeatureColumnResponse... columns) {
    return List.of(columns);
  }

  static List<AdminFeatureActionResponse> actions(AdminFeatureActionResponse... actions) {
    return List.of(actions);
  }

  @SafeVarargs
  static List<Map<String, Object>> rows(Map<String, Object>... rows) {
    return List.of(rows);
  }

  static AdminFeatureFieldResponse field(String key, String label, String component) {
    return new AdminFeatureFieldResponse(key, label, component, List.of());
  }

  static AdminFeatureFieldResponse select(String key, String label, String... options) {
    List<AdminFeatureOptionResponse> items = Stream.of(options)
        .map(option -> new AdminFeatureOptionResponse(option, option))
        .toList();
    return new AdminFeatureFieldResponse(key, label, "select", items);
  }

  static AdminFeatureColumnResponse col(String key, String label) {
    return new AdminFeatureColumnResponse(key, label, false);
  }

  static AdminFeatureColumnResponse sortableCol(String key, String label) {
    return new AdminFeatureColumnResponse(key, label, true);
  }

  static AdminFeatureActionResponse action(String key, String label, String type) {
    return new AdminFeatureActionResponse(key, label, type);
  }

  static Map<String, Object> row(Object... entries) {
    if (entries.length % 2 != 0) {
      throw new IllegalArgumentException("row entries must be key/value pairs");
    }
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) {
      row.put(String.valueOf(entries[i]), entries[i + 1]);
    }
    return Collections.unmodifiableMap(row);
  }
}
```

- [ ] **Step 4: 按 group 抽取页面定义**

新建 group builder，每个类结构：

```java
final class AdminPermissionFeaturePages {

  private AdminPermissionFeaturePages() {
  }

  static List<AdminFeaturePageResponse> pages() {
    return List.of(
        AdminFeatureCatalogDsl.page(
            "system-users",
            "用户管理",
            "权限",
            AdminFeatureCatalogDsl.fields(
                AdminFeatureCatalogDsl.field("department", "搜索部门", "input")
            ),
            AdminFeatureCatalogDsl.columns(
                AdminFeatureCatalogDsl.col("avatar", "头像")
            ),
            AdminFeatureCatalogDsl.actions(
                AdminFeatureCatalogDsl.action("create", "新增", "modal")
            ),
            AdminFeatureCatalogDsl.actions(
                AdminFeatureCatalogDsl.action("edit", "编辑", "modal")
            ),
            AdminFeatureCatalogDsl.rows()
        )
    );
  }
}
```

实际执行时，不要用上面的最小示例替代真实内容；要把当前 `AdminFeatureCatalogService` 里的完整 page 定义原样搬入对应类。分组如下：

```text
AdminPermissionFeaturePages: system-users, system-roles, system-departments, system-menus, system-posts
AdminProductFeaturePages: products, product-categories, price-schedules
AdminFinanceFeaturePages: finance-ledger, recharge-orders, withdrawal-orders, payment-methods
AdminMemberFeaturePages: members, member-payment-accounts
AdminContentFeaturePages: notices, news, member-notices, verification-codes, request-logs
AdminSettingsFeaturePages: settings-site, settings-upload, settings-sms, settings-email, settings-footer
```

要求：保留原有 key、label、action、row 内容，不重设计 catalog。

- [ ] **Step 5: 缩小 `buildPages()`**

`AdminFeatureCatalogService.buildPages()` 改为：

```java
private static Map<String, AdminFeaturePageResponse> buildPages() {
  LinkedHashMap<String, AdminFeaturePageResponse> pages = new LinkedHashMap<>();

  Stream.of(
          AdminPermissionFeaturePages.pages(),
          AdminProductFeaturePages.pages(),
          AdminFinanceFeaturePages.pages(),
          AdminMemberFeaturePages.pages(),
          AdminContentFeaturePages.pages(),
          AdminSettingsFeaturePages.pages())
      .flatMap(List::stream)
      .forEach(page -> pages.put(page.key(), page));

  return Collections.unmodifiableMap(pages);
}
```

- [ ] **Step 6: 验证**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd -Dtest=ArchitectureRulesTest,AdminFeatureOperationServiceTest test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

预期：两个命令都 PASS。

- [ ] **Step 7: 可选 commit**

```powershell
git add fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service fx-trading-platform/backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java
git commit -m "refactor(backend): split admin feature catalog by group"
```

---

## Task 7: 将 `TradingPage` 拆成编排层和展示层

**目的：** 降低 `TradingPage.tsx` 复杂度，把 desktop/mobile layout 和 settings dialog 移到聚焦组件中，交易行为不变。

**文件：**

- Create: `apps/web/src/pages/trading/components/TradingDesktopView.tsx`
- Create: `apps/web/src/pages/trading/components/TradingMobileView.tsx`
- Create: `apps/web/src/pages/trading/components/TradingSettingsDialog.tsx`
- Create: `apps/web/src/pages/trading/tradingPageViewModels.ts`
- Modify: `apps/web/src/pages/trading/TradingPage.tsx`
- Modify: `apps/web/src/pages/trading/TradingPage.test.ts`

- [ ] **Step 1: 增加源码结构测试**

在 `TradingPage.test.ts` 中追加：

```ts
  it('keeps TradingPage as an orchestration shell with extracted views', () => {
    assert.match(source, /<TradingDesktopView/)
    assert.match(source, /<TradingMobileView/)
    assert.match(source, /<TradingSettingsDialog/)
    assert.doesNotMatch(source, /<TradingWorkspace[\s\S]*<ChartWorkspace[\s\S]*<TradePanel[\s\S]*<BottomAccountPanel/)
  })
```

- [ ] **Step 2: 确认测试失败**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\pages\trading\TradingPage.test.ts"
```

预期：失败，因为 `TradingPage.tsx` 仍内联 desktop/mobile layout。

- [ ] **Step 3: 创建共享 view model 类型**

创建 `apps/web/src/pages/trading/tradingPageViewModels.ts`。该文件只定义跨 view 的 props/type，不放 React 逻辑。

建议结构：

```ts
import type { ReactNode } from 'react'

import type { ChartSettings, ChartType, DrawingMagnetMode, DrawingTool, IndicatorSettings } from './chartSettings'
import type { TradingMarket, TradingPeriod, TradingQuote } from '../../features/market/tradingModels'
import type { TradingAccount, OrderResponse, PositionResponse, LedgerEntry } from '../../components/tables/types'

export type ChartThemeMode = 'dark' | 'light'

export type TradingChartCallbacks = {
  onChartTypeChange: (chartType: ChartType) => void
  onDrawingMagnetModeChange: (magnetMode: DrawingMagnetMode) => void
  onDrawingToolChange: (activeTool: DrawingTool) => void
  onIndicatorSettingsChange: (indicatorSettings: IndicatorSettings) => void
  onIndicatorToggle: (indicator: string) => void
  onPeriodChange: (interval: TradingPeriod) => void
}

export type TradingAccountPanelData = {
  account: TradingAccount | null
  ledgerEntries: LedgerEntry[]
  loading: boolean
  orders: OrderResponse[]
  positions: PositionResponse[]
  sessionReady: boolean
  onClosePosition: (positionId: string) => Promise<void> | void
}

export type TradingTerminalViewProps = {
  accountPanel: TradingAccountPanelData
  accountId?: string
  balances: Record<string, number>
  chartCallbacks: TradingChartCallbacks
  chartSettings: ChartSettings
  chartThemeMode: ChartThemeMode
  indicators: string[]
  lastOrderError?: unknown
  loginRequired: boolean
  market: TradingMarket
  markets: TradingMarket[]
  onLoginRequired: () => void
  onOpenMarkets: () => void
  onOpenQuote: () => void
  onOpenSettings: () => void
  onOpenTrade: () => void
  onRetrySession: () => Promise<void> | void
  onSelectSymbol: (symbol: string) => void
  quote: TradingQuote
  quotes: Record<string, TradingQuote>
  sessionError?: string | null
  sessionReady: boolean
  sessionStatusLabel: string
  sessionStatusText: string
  sessionTelemetryState: string
  showSessionRetry: boolean
  submitOrder: (payload: unknown) => Promise<unknown>
  symbol: string
  terminalLoading: boolean
  token: string | null
  tradePanelSessionMode: 'loading' | 'ready' | 'login-required' | 'error'
}

export type TradingSettingsDialogProps = {
  open: boolean
  onClose: () => void
}

export type MobileSuspenseProps = {
  fallback: ReactNode
}
```

如果本地真实类型名不同，按真实导出修正 import，但保持这个文件作为 view prop contract 的唯一入口。

- [ ] **Step 4: 抽出 settings dialog**

创建 `apps/web/src/pages/trading/components/TradingSettingsDialog.tsx`：

```tsx
import { X } from 'lucide-react'

import { ThemeSwitcher } from '../../../design-system/theme/ThemeSwitcher'
import styles from '../TradingPage.module.css'
import type { TradingSettingsDialogProps } from '../tradingPageViewModels'

export function TradingSettingsDialog({ open, onClose }: TradingSettingsDialogProps) {
  if (!open) return null

  return (
    <div
      className={styles.settingsLayer}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <section className={styles.settingsDialog} role="dialog" aria-modal="true" aria-label="交易设置">
        <header className={styles.settingsHeader}>
          <h2>交易设置</h2>
          <button type="button" aria-label="关闭交易设置" onClick={onClose}>
            <X size={20} aria-hidden="true" />
          </button>
        </header>
        <div className={styles.settingsRow}>
          <ThemeSwitcher />
        </div>
      </section>
    </div>
  )
}
```

如果当前源码中的中文文案与上面不同，以现有源码为准。

- [ ] **Step 5: 抽出 desktop view**

创建 `apps/web/src/pages/trading/components/TradingDesktopView.tsx`。从 `TradingPage.tsx` 中移动 desktop 分支 JSX，保留所有 child component 和 prop 值。

必要 imports：

```tsx
import { Settings } from 'lucide-react'
import { TradingWorkspace } from '../../../components/layout/TradingWorkspace'
import { TradePanel } from '../../../features/trading/components/TradePanel'
import { BottomAccountPanel } from './BottomAccountPanel'
import { ChartWorkspace } from './ChartWorkspace'
import { MarketSidebar } from './MarketSidebar'
import { RightTradingPanel } from './RightTradingPanel'
import { SymbolHeader } from './SymbolHeader'
import styles from '../TradingPage.module.css'
import type { TradingTerminalViewProps } from '../tradingPageViewModels'
```

导出：

```tsx
export function TradingDesktopView(props: TradingTerminalViewProps) {
  const {
    accountPanel,
    accountId,
    balances,
    chartCallbacks,
    chartSettings,
    chartThemeMode,
    indicators,
    lastOrderError,
    loginRequired,
    market,
    markets,
    onLoginRequired,
    onOpenMarkets,
    onOpenQuote,
    onOpenSettings,
    onRetrySession,
    onSelectSymbol,
    quote,
    quotes,
    sessionError,
    sessionReady,
    sessionStatusLabel,
    sessionStatusText,
    sessionTelemetryState,
    showSessionRetry,
    submitOrder,
    symbol,
    terminalLoading,
    token,
    tradePanelSessionMode
  } = props

  return (
    <div className={`${styles.layout} ${styles.desktopTerminal}`}>
      {/* 把 TradingPage.tsx 当前 desktop JSX 原样移动到这里。 */}
    </div>
  )
}
```

执行者必须用当前源码中的真实 JSX 替换注释位置。不要重新设计 layout。

- [ ] **Step 6: 抽出 mobile view**

创建 `apps/web/src/pages/trading/components/TradingMobileView.tsx`。从 `TradingPage.tsx` 移动 mobile 分支 JSX。

保留 lazy loading：

```tsx
const MobileTradingTerminal = lazy(() =>
  import('../mobile/MobileTradingTerminal').then((module) => ({ default: module.MobileTradingTerminal }))
)
```

导出：

```tsx
export function TradingMobileView(props: TradingTerminalViewProps) {
  return (
    <div className={styles.mobileTerminal}>
      {/* 把 TradingPage.tsx 当前 mobile JSX 原样移动到这里。 */}
    </div>
  )
}
```

`MobileTerminalFallback` 移到此文件内作为 private function。

- [ ] **Step 7: 更新 `TradingPage.tsx`**

`TradingPage.tsx` 只保留：

- data loading 和 state
- callbacks
- drawers 和 login prompt
- desktop/mobile view selection

渲染结构：

```tsx
{!isMobileTerminal ? (
  <TradingDesktopView {...viewProps} />
) : null}

{isMobileTerminal ? (
  <TradingMobileView {...viewProps} />
) : null}

<TradingSettingsDialog open={settingsOpen} onClose={() => setSettingsOpen(false)} />
```

`viewProps` 可以是 plain object；只有在依赖清晰时才用 `useMemo`，不要为了“优化”制造更难维护的依赖数组。

- [ ] **Step 8: 运行聚焦测试**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\pages\trading\TradingPage.test.ts src\pages\trading\components\ChartWorkspace.test.ts src\pages\trading\components\BottomAccountPanel.test.ts"
```

预期：PASS。

- [ ] **Step 9: 验证行数**

```powershell
powershell -NoProfile -Command "(Get-Content -LiteralPath 'fx-trading-platform\apps\web\src\pages\trading\TradingPage.tsx').Count"
```

预期：`TradingPage.tsx` 小于 330 行。

- [ ] **Step 10: 可选 commit**

```powershell
git add fx-trading-platform/apps/web/src/pages/trading
git commit -m "refactor(web): split trading page views from orchestration"
```

---

## Task 8: 拆分 `TradePanel` 的状态、提交逻辑和子视图

**目的：** 让 `TradePanel.tsx` 聚焦组合，把 session 状态、杠杆控件、提交处理 helper 拆出。

**文件：**

- Create: `apps/web/src/features/trading/components/TradePanelSessionStatus.tsx`
- Create: `apps/web/src/features/trading/components/TradePanelLeverageControls.tsx`
- Create: `apps/web/src/features/trading/hooks/useTradePanelSubmit.ts`
- Modify: `apps/web/src/features/trading/components/TradePanel.tsx`
- Modify: `apps/web/src/features/trading/components/TradePanel.test.ts`

- [ ] **Step 1: 增加结构测试**

在 `TradePanel.test.ts` 追加：

```ts
  it('delegates session status and leverage surfaces to focused components', () => {
    assert.match(tradePanelSource, /<TradePanelSessionStatus/)
    assert.match(tradePanelSource, /<TradePanelLeverageControls/)
    assert.match(tradePanelSource, /useTradePanelSubmit/)
    assert.doesNotMatch(tradePanelSource, /function LeverageCell/)
  })
```

- [ ] **Step 2: 确认测试失败**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\features\trading\components\TradePanel.test.ts"
```

预期：失败，因为子组件和 hook 尚未存在，`LeverageCell` 仍在本地。

- [ ] **Step 3: 抽出 `TradePanelSessionStatus`**

创建 `apps/web/src/features/trading/components/TradePanelSessionStatus.tsx`。组件只负责展示 session badge、状态文案、错误文案和重试按钮。

```tsx
type Props = {
  accountStatus: string
  onRetrySession?: () => Promise<void> | void
  sessionBadge: string
  sessionErrorText: string
  sessionHasError: boolean
}

export function TradePanelSessionStatus({
  accountStatus,
  onRetrySession,
  sessionBadge,
  sessionErrorText,
  sessionHasError
}: Props) {
  return (
    <>
      <div className="trade-panel__session-status" aria-live="polite">
        <span>{sessionBadge}</span>
        <small>{accountStatus}</small>
      </div>

      {sessionHasError ? (
        <div className="trade-panel__session-error" role="alert">
          <span>{sessionErrorText}</span>
          {onRetrySession ? (
            <button type="button" onClick={() => void onRetrySession()}>
              重试连接
            </button>
          ) : null}
        </div>
      ) : null}
    </>
  )
}
```

如当前 `TradePanel.tsx` 中使用不同文案，执行时从原文件复制真实文案，避免修改 UI 文案。

- [ ] **Step 4: 抽出 `TradePanelLeverageControls`**

创建 `apps/web/src/features/trading/components/TradePanelLeverageControls.tsx`。该组件接收杠杆值、弹窗状态、快捷选项、打开/关闭/更新回调。

核心结构：

```tsx
import { ChevronDown, X } from 'lucide-react'

import type { TradeSide } from '../types/order'

type Props = {
  leverage: number
  leverageOpen: boolean
  leverageOptions: number[]
  onClose: () => void
  onOpen: () => void
  onUpdateLeverage: (value: number) => void
}

export function TradePanelLeverageControls({
  leverage,
  leverageOpen,
  leverageOptions,
  onClose,
  onOpen,
  onUpdateLeverage
}: Props) {
  return (
    <>
      {leverageOpen ? (
        <div className="trade-panel__leverage-popover" role="dialog" aria-label="调整杠杆">
          <div className="trade-panel__leverage-popover-head">
            <strong>调整杠杆</strong>
            <button type="button" aria-label="关闭杠杆弹窗" onClick={onClose}>
              <X size={16} aria-hidden="true" />
            </button>
          </div>
          <label className="trade-panel__field">
            <span className="trade-panel__field-label">杠杆倍数</span>
            <span className="trade-panel__control">
              <input
                aria-label="杠杆倍数"
                inputMode="numeric"
                value={`${leverage}.00`}
                onChange={(event) => onUpdateLeverage(Number(event.target.value))}
              />
              <span className="trade-panel__unit">x</span>
            </span>
          </label>
          <div className="trade-panel__leverage-options" aria-label="杠杆快捷选项">
            {leverageOptions.map((option) => (
              <button
                key={option}
                type="button"
                className={option === leverage ? 'trade-panel__leverage-option--active' : ''}
                onClick={() => onUpdateLeverage(option)}
              >
                {option}x
              </button>
            ))}
          </div>
          <p className="trade-panel__leverage-hint">当前最高支持 10x 杠杆，请注意风险</p>
          <button type="button" className="trade-panel__leverage-confirm" onClick={onClose}>
            确认
          </button>
        </div>
      ) : null}

      <div className="trade-panel__leverage-row" aria-label="逐仓和杠杆设置">
        <LeverageCell side="buy" leverage={leverage} onOpen={onOpen} />
        <LeverageCell side="sell" leverage={leverage} onOpen={onOpen} />
      </div>
    </>
  )
}

function LeverageCell({ side, leverage, onOpen }: { side: TradeSide; leverage: number; onOpen: () => void }) {
  return (
    <div className={`trade-panel__leverage-cell trade-panel__leverage-cell--${side}`}>
      <button type="button" onClick={onOpen}>
        逐仓
        <ChevronDown size={13} aria-hidden="true" />
      </button>
      <button type="button" onClick={onOpen}>
        {leverage}x
        <ChevronDown size={13} aria-hidden="true" />
      </button>
    </div>
  )
}
```

执行时以原 `TradePanel.tsx` 的真实文案和 className 为准。不要引入新交互。

- [ ] **Step 5: 抽出 `useTradePanelSubmit`**

创建 `apps/web/src/features/trading/hooks/useTradePanelSubmit.ts`。该 hook 管理：

- `submittingSide`
- `attempted`
- `handleSubmit`
- `formatOrderError`

骨架：

```ts
import { useCallback, useState } from 'react'

import { toOrderPayload } from '../services/orderAdapter'
import type { OrderValidationResult, TradeFormState, TradeMarket, TradeSide } from '../types/order'
import { ApiClientError } from '../../../services/apiClient'
import type { OrderPayload } from '../../../types/trading'
import type { OrderResponse } from '../../../components/tables/types'

type Options = {
  accountId?: string
  backendReady: boolean
  canTrade: boolean
  loginRequired: boolean
  market: TradeMarket
  onLoginRequired?: () => void
  onSubmitOrder?: (payload: OrderPayload) => Promise<OrderResponse | void>
  sessionHasError: boolean
  setNotice: (message: string) => void
}

export function useTradePanelSubmit(options: Options) {
  const {
    accountId,
    backendReady,
    canTrade,
    loginRequired,
    market,
    onLoginRequired,
    onSubmitOrder,
    sessionHasError,
    setNotice
  } = options

  const [submittingSide, setSubmittingSide] = useState<TradeSide | null>(null)
  const [attempted, setAttempted] = useState<Record<TradeSide, boolean>>({ buy: false, sell: false })

  const handleSubmit = useCallback(
    async (form: TradeFormState, validation: OrderValidationResult, reset: () => void) => {
      setAttempted((current) => ({ ...current, [form.side]: true }))

      if (loginRequired) {
        setNotice('请先登录后再下单')
        onLoginRequired?.()
        return
      }

      if (!canTrade) {
        setNotice(sessionHasError ? '后端会话异常，请先重试连接' : '后端会话尚未就绪，暂不能提交真实订单')
        return
      }

      if (!validation.canSubmit) {
        const firstError = validation.errors[0]
        setNotice(firstError ? validation.fieldErrors[firstError] ?? '请检查订单参数' : '请检查订单参数')
        return
      }

      setSubmittingSide(form.side)
      try {
        if (!backendReady || !accountId || !onSubmitOrder) {
          setNotice('后端会话尚未就绪，不能提交真实订单')
          return
        }

        const payload = toOrderPayload(accountId, form, market)
        const response = await onSubmitOrder(payload)
        reset()
        setAttempted((current) => ({ ...current, [form.side]: false }))
        setNotice(`后端下单成功：${response?.status ?? '已提交'}`)
      } catch (error) {
        setNotice(`后端下单失败：${formatOrderError(error)}`)
      } finally {
        setSubmittingSide(null)
      }
    },
    [accountId, backendReady, canTrade, loginRequired, market, onLoginRequired, onSubmitOrder, sessionHasError, setNotice]
  )

  return { attempted, handleSubmit, submittingSide }
}

function formatOrderError(error: unknown) {
  if (error instanceof ApiClientError) {
    const details = [`错误码：${error.code}`, `HTTP 状态：${error.status}`]
    if (error.requestId) details.push(`Request ID：${error.requestId}`)
    return `${error.message}（${details.join('，')}）`
  }

  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error) return error
  return '请稍后重试'
}
```

注意：如果当前 `TradePanel.tsx` 文案不同，以现有文案为准，不要因为拆文件改变用户看到的文本。

- [ ] **Step 6: 连接 `TradePanel.tsx`**

从 `TradePanel.tsx` 删除本地的：

- `formatOrderError`
- `LeverageCell`
- `submittingSide` state
- `attempted` state
- 内联 session status JSX
- 内联 leverage popover/row JSX

新增 imports：

```ts
import { TradePanelLeverageControls } from './TradePanelLeverageControls'
import { TradePanelSessionStatus } from './TradePanelSessionStatus'
import { useTradePanelSubmit } from '../hooks/useTradePanelSubmit'
```

使用：

```ts
const { attempted, handleSubmit, submittingSide } = useTradePanelSubmit({
  accountId,
  backendReady,
  canTrade,
  loginRequired,
  market,
  onLoginRequired,
  onSubmitOrder,
  sessionHasError,
  setNotice
})
```

- [ ] **Step 7: 运行聚焦测试**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\apps\web && node --test src\features\trading\components\TradePanel.test.ts src\features\trading\services\orderAdapter.test.ts src\features\trading\components\tradePanelMarket.test.ts"
```

预期：PASS。

- [ ] **Step 8: 验证行数**

```powershell
powershell -NoProfile -Command "(Get-Content -LiteralPath 'fx-trading-platform\apps\web\src\features\trading\components\TradePanel.tsx').Count"
```

预期：`TradePanel.tsx` 小于 230 行。

- [ ] **Step 9: 可选 commit**

```powershell
git add fx-trading-platform/apps/web/src/features/trading
git commit -m "refactor(web): split trade panel session and leverage surfaces"
```

---

## Task 9: 为大文件治理增加 guardrails

**目的：** 在拆 `src/Store.ts`、`src/Chart.ts`、`src/common/EventHandler.ts` 前，先有可度量的大文件审计规则，避免无边界重构。

**文件：**

- Create: `scripts/audit-large-files.mjs`
- Modify: `package.json`

- [ ] **Step 1: 创建大文件审计脚本**

创建 `scripts/audit-large-files.mjs`：

```js
import { readFileSync } from 'node:fs'

const limits = [
  ['src/Store.ts', 1900],
  ['src/Chart.ts', 1450],
  ['src/common/EventHandler.ts', 1000],
  ['fx-trading-platform/apps/web/src/pages/trading/TradingPage.tsx', 330],
  ['fx-trading-platform/apps/web/src/features/trading/components/TradePanel.tsx', 230],
  ['fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFeatureCatalogService.java', 250]
]

const failures = []

for (const [path, limit] of limits) {
  const lines = readFileSync(path, 'utf8').split(/\r?\n/).length
  if (lines > limit) {
    failures.push(`${path} has ${lines} lines; limit is ${limit}`)
  }
}

if (failures.length > 0) {
  console.error('Large file audit failed:')
  for (const failure of failures) console.error(`- ${failure}`)
  process.exit(1)
}

console.log('Large file audit passed.')
```

- [ ] **Step 2: 增加 root package script**

在根目录 `package.json` 添加：

```json
"audit:large-files": "node scripts/audit-large-files.mjs"
```

保持 JSON comma 合法。

- [ ] **Step 3: 运行并解读**

```powershell
cmd.exe /d /s /c "pnpm.cmd audit:large-files"
```

预期：在 Task 6-8 完成后，`TradingPage`、`TradePanel`、`AdminFeatureCatalogService` 应通过限制；KLineCharts 根文件使用当前宽松阈值，不强制在本计划中拆。

- [ ] **Step 4: 可选 commit**

```powershell
git add scripts/audit-large-files.mjs package.json
git commit -m "chore: add large file audit guardrails"
```

---

## Task 10: 替换高风险后端路径中的低价值模板注释

**目的：** 提升注释质量。只改注释，不改代码逻辑。注释必须解释业务约束、幂等边界、并发保护，而不是重复方法名。

**文件：**

- Modify: `backend/src/main/java/com/fxplatform/trading/service/PositionService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/repository/PositionRepository.java`
- Modify: `backend/src/main/java/com/fxplatform/trading/repository/OrderRepository.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java`
- Modify: `backend/src/main/java/com/fxplatform/finance/repository/AdminFundOperationRepository.java`
- Modify: `backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java`

- [ ] **Step 1: 增加高风险路径注释质量测试**

在 `ArchitectureRulesTest` 加：

```java
@Test
void highRiskTradingAndFinancePathsDoNotUseTemplateBusinessComments() throws Exception {
  String combined = Files.readString(Path.of("src/main/java/com/fxplatform/trading/service/PositionService.java"))
      + Files.readString(Path.of("src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java"))
      + Files.readString(Path.of("src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java"))
      + Files.readString(Path.of("src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java"));

  assertThat(combined).doesNotContain("执行 closePosition 业务流程");
  assertThat(combined).doesNotContain("执行 tryExecute 业务流程");
  assertThat(combined).doesNotContain("执行 shouldClose 业务流程");
  assertThat(combined).doesNotContain("执行 applyBalanceChange 业务流程");
}
```

如果当前源码因为编码显示为乱码，断言要使用源码中真实字节解码后的字符串；不要让测试因为终端编码差异误判。

- [ ] **Step 2: 确认测试失败**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd -Dtest=ArchitectureRulesTest#highRiskTradingAndFinancePathsDoNotUseTemplateBusinessComments test"
```

预期：如果这些模板注释仍存在，测试失败。

- [ ] **Step 3: 替换为业务约束注释**

只替换注释，代码不动。

`PositionService.closePosition(...)`：

```java
/**
 * 用户主动平仓入口。真正的重复结算保护在 closeOwnedPosition 和 closeIfOpen 中完成，
 * 这里先验证账户归属，避免跨账户平仓。
 */
```

`PositionService.closeOwnedPosition(...)` 中 `closeIfOpen` 前：

```java
// 先用 OPEN 条件更新抢占平仓权，再写账户和流水，避免并发请求重复释放保证金。
```

`PendingOrderExecutionService.tryExecute(...)`：

```java
/**
 * 挂单触价后先抢占 PENDING 状态，只有抢占成功的实例可以继续成交。
 */
```

`ProtectiveOrderExecutionService.shouldClose(...)`：

```java
/**
 * 止盈止损只扫描 OPEN 持仓；并发重复触发交给 PositionService 的 OPEN 条件更新兜底。
 */
```

`AdminFinanceCommandService.applyBalanceChange(...)`：

```java
/**
 * 后台资金写操作以 accountId + operationType + idempotencyKey 作为命令幂等边界。
 * 非空幂等键必须先抢占资金操作记录，抢占成功后才允许改账户余额和写流水。
 */
```

`AdminFundOperationRepository.insertIfAbsent(...)`：

```java
/**
 * 用数据库唯一索引抢占非空幂等键；返回 0 表示已有同一命令结果，调用方应回读。
 */
```

- [ ] **Step 4: 运行后端测试**

```powershell
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd -Dtest=ArchitectureRulesTest,PositionServiceTest,PendingOrderExecutionServiceTest,AdminFinanceCommandServiceTest test"
```

如果 `AdminFinanceCommandServiceTest` 不存在，先查询：

```powershell
Get-ChildItem -Recurse -File fx-trading-platform\backend\src\test\java -Filter '*Finance*Test.java'
```

预期：相关存在测试全部 PASS。

- [ ] **Step 5: 可选 commit**

```powershell
git add fx-trading-platform/backend/src/main/java/com/fxplatform/trading fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java fx-trading-platform/backend/src/main/java/com/fxplatform/finance/repository/AdminFundOperationRepository.java fx-trading-platform/backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java
git commit -m "docs(backend): replace template comments in high-risk flows"
```

---

## 最终验证

全部任务完成后运行：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn.cmd test"
cmd.exe /d /s /c "pnpm.cmd type-check"
cmd.exe /d /s /c "pnpm.cmd audit:large-files"
```

预期：

- architecture verification exit code 0
- web tests exit code 0
- web build exit code 0
- backend Maven tests exit code 0
- root type check exit code 0
- large-file audit exit code 0

## 推荐执行拆分

推荐使用 `superpowers:subagent-driven-development`。

- Agent 1: Task 1-4，前端边界、market ownership、共享 loading。
- Agent 2: Task 5-6 和 Task 10，后端 audit/catalog/comment quality。
- Agent 3: Task 7-9，交易页/交易面板拆分和大文件 guardrails。

每个 agent 返回后都要：

1. 查看 diff。
2. 运行该任务的聚焦测试。
3. 确认没有无关格式化或顺手重构。
4. 再进入最终验证。

## 自检清单

- 覆盖范围：包含仍存在的死 CSS、页面反向依赖、feature/component 边界、audit JSON 统一、大型 admin catalog、`TradingPage`/`TradePanel` 密度、KLineCharts guardrails、后端高风险路径注释质量。
- 占位符扫描：本文档不包含未完成占位指令。
- 类型一致性：前端新增类型集中在 `tradingPageViewModels.ts` 和 `marketDataTypes.ts`；后端 helper 名称匹配现有 service/repository。
- 行为约束：不改变交易下单、登录提示、平仓、挂单、资金幂等、订单幂等行为。
- 风险控制：每个任务都先用测试或架构规则锁住目标，再做最小实现。
