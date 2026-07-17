# Web PC/Mobile 双 UI 与公共前端层设计规格

> 最后更新：2026-07-17
>
> 状态：需求访谈与设计已确认，等待书面规格复核后更新实施计划
>
> 适用范围：`fx-trading-platform/apps/web` 及新增的前端 workspace packages
>
> 权威性：本文件是本轮前端拆分、公共组件库建设和 PC/Mobile 双 UI 迁移的产品与技术基准；正式执行时以当前工作区的可验证视觉、行为和业务契约为迁移基线。

## 1. 目标

保留一个 `apps/web` 构建和一套路由，在运行时按设备宽度懒加载 PC 或 Mobile UI，同时把两端共享的业务能力和无业务 UI 从现有单体应用中抽离出来。

最终达到：

- PC 与 Mobile 各自拥有完整、独立的页面结构、布局和交互实现。
- 认证、API、行情、交易、账户、钱包、状态管理和 view-model 只有一份实现。
- 建立内部 `@fx-platform/ui`，统一主题、Token 和无业务基础组件。
- 建立内部 `@fx-platform/frontend-core`，承载可被两套 UI 共同消费的前端业务能力。
- 保持现有 URL、后端 API、权限边界、行情语义和交易语义不变。
- 后续修改主题或基础控件时只改 UI 包；修改业务规则或数据获取时只改核心包。
- 迁移过程始终保持应用可构建、可测试、可逐步回滚。

## 2. 当前基线

截至 2026-07-17，当前代码具有以下特征：

- `apps/web` 是 React 19、Vite 7、TypeScript 5.8、React Router 7 和 Zustand 5 的单体应用。
- 根 workspace 已包含 `apps/*` 与 `packages/*`，并已有 `packages/shared-types`，适合继续增加内部包。
- `apps/web/src` 当前约有 109 个 TSX、156 个 TS 和 32 个 CSS 文件，合计约 57,700 行（包含测试）。
- `packages/ui`、`packages/frontend-core`、`apps/web/src/pc`、`apps/web/src/mobile`、`apps/web/src/shared-widgets` 和前端依赖守卫均尚未创建。
- `TradingPage` 已通过 `TradingDesktopView`、`TradingMobileView` 和 `MobileTradingTerminal` 局部区分两端 UI，但设备判断仍为 `max-width: 768px`，且未形成全站统一平台入口。
- `design-system` 目前只有主题系统和 `TerminalIconButton`，还不是可独立依赖的 workspace UI 包。
- `apps/web/src/styles.css` 有 6,263 行，`features/trading/styles/trade-panel.css` 有 2,354 行；源码中共有 65 个媒体查询和约 735 处直接颜色表达式。
- 页面与交易目录是最大代码区域；`MarketsPage.tsx`、`KLineChartPanel.tsx`、`AccountPages.tsx`、`WalletPage.tsx` 等文件体量较大。
- 当前 `web:test` 的 530 个测试全部通过，`web:build` 与 `verify:architecture` 通过。
- 当前 `web:bundle-budget` 失败：`TradingPage` JS 为 444,078 bytes，超过 380,000 bytes；`audit:large-files` 失败：`TradingPage.tsx` 为 340/330 行，`TradePanel.tsx` 为 307/230 行。这些是必须通过抽离和懒加载修复的已知基线，禁止放宽阈值。
- 当前仓库存在大量后端未提交改动；前端重叠改动集中在 `features/trading/services/orderAdapter.ts` 及其测试。迁移必须保留这些改动，不得覆盖、重置、误提交无关文件或破坏交易业务链路。

## 3. 范围外事项

本轮不做：

- 不拆成两个独立部署应用。
- 不把 `apps/admin` 纳入 PC/Mobile 双 UI 或本轮公共 UI 包迁移。
- 不改变后端 API 或数据库。
- 不重写交易、钱包、行情或认证业务规则。
- 不以 UI 拆分为由连接真实 broker、FIX、LP 或私有交易接口。
- 不修改 `/api/admin/**` 权限边界。
- 不同时重做全站视觉方案。
- 不在首轮引入 Storybook、微前端、Module Federation 或新的状态管理框架。
- 不把仅仅“外观类似”的 PC/Mobile 业务组件强行合并。
- 不在公共层稳定之前大批量复制页面。

## 4. 已评估方案

### 4.1 采用：公共包 + 单应用双展示层

使用 `packages/ui`、`packages/frontend-core`、`apps/web/src/pc` 和 `apps/web/src/mobile` 形成明确依赖图。路由和公共 Provider 唯一，页面控制器准备数据后选择一套 UI 渲染。

优点：边界可自动检查；两端可以独立演进；业务与视觉修改各有唯一入口；符合现有 npm workspace。

代价：需要先抽公共层，并在迁移期维护短期兼容出口。

### 4.2 未采用：每个 feature 内建立 `pc/mobile/shared`

迁移速度较快，但不同 feature 容易形成不同分层习惯，公共 UI 和核心业务仍难以成为全局稳定依赖。

### 4.3 未采用：继续在现有页面增加响应式分支

初期改动小，但会继续扩大条件渲染、全局 CSS 和页面状态耦合，不能满足后续统一修改的目标。

## 5. 目标架构

```text
fx-trading-platform/
├─ apps/
│  └─ web/
│     └─ src/
│        ├─ app/                  # 启动、Provider、唯一的路由契约、设备选择
│        ├─ routes/               # 路由适配器；连接 core model 与平台 UI
│        ├─ pc/                   # PC Shell、页面、布局、桌面专属交互
│        ├─ mobile/               # Mobile Shell、页面、触屏布局与交互
│        └─ shared-widgets/       # 两端行为和结构确实相同的业务组合组件
└─ packages/
   ├─ ui/                         # Token、主题、基础组件与无业务交互原语
   ├─ frontend-core/              # API、认证、行情、交易、账户、hooks、stores、view-model
   └─ shared-types/               # 已有 OpenAPI 与领域共享类型
```

允许的依赖方向：

```text
apps/web/src/pc ───────────────┐
                               ├─> apps/web/src/shared-widgets ─> @fx-platform/ui
apps/web/src/mobile ───────────┘                 │
          │                                      └─> @fx-platform/frontend-core
          ├────────────────────────────────────────> @fx-platform/frontend-core
          └────────────────────────────────────────> @fx-platform/ui

@fx-platform/frontend-core ─> @fx-platform/shared-types
@fx-platform/ui ─────────────> React（peer dependency）
```

禁止的依赖：

- `pc` 不得 import `mobile`。
- `mobile` 不得 import `pc`。
- `ui` 不得 import `frontend-core`、应用路由、业务 service、store 或页面。
- `frontend-core` 不得 import `ui`、CSS、页面组件或 React Router。
- `shared-widgets` 不得反向依赖具体 PC/Mobile 页面。
- `shared-types` 不得依赖任何前端实现包。

这些规则必须由脚本检查，不能只依靠评审约定。

## 6. `@fx-platform/ui` 边界

### 6.1 可以进入 UI 包

- 颜色、间距、字体、圆角、阴影、层级和动效 Token。
- `ThemeProvider`、主题定义和 CSS 变量桥接。
- Button、IconButton、Input、Select、Checkbox、Tabs、Dialog、Drawer、Sheet、Popover、Tooltip。
- Skeleton、Spinner、EmptyState、ErrorState。
- 无业务含义的 DataTable 外壳、Field、FormMessage 和可访问性辅助组件。
- 无业务数据依赖的布局原语；仅在两端均有明确复用价值时加入。

### 6.2 不进入 UI 包

- `TradePanel`、`OrderBook`、`AccountSummary`、`MarketWatch`、`KLineChartPanel` 等业务组件。
- 包含 symbol、order、position、wallet、ledger 或 account 语义的组件。
- 直接调用 API、订阅 STOMP、读取认证 token 或访问业务 store 的组件。
- 具体页面导航、PC 顶栏、Mobile 底栏和交易终端布局。

### 6.3 当前组件的初始归类

| 当前实现 | 目标位置 | 原因 |
|---|---|---|
| `design-system/theme/*` | `packages/ui/src/theme` | 全应用主题能力 |
| `TerminalIconButton` | `packages/ui/src/components/IconButton` | 无业务按钮原语 |
| `SelectField` | `packages/ui/src/components/Select` 或 `Field` | 无业务表单能力 |
| `PageState` | 拆成 UI 状态原语后进入 `ui` | 当前可复用，但需移除页面语义 |
| `DataTable` | 无业务外壳进入 `ui` | 列定义与业务单元格留在消费方 |
| `AssetMark` | `shared-widgets/asset` | 带资产领域语义 |
| `ExchangeLoading` | 通用部分进入 `ui`，品牌组合留在应用 | 避免 UI 包绑定具体产品文案 |
| `ResizablePanel`、`TradingWorkspace` | 初期留在 `pc` | 当前主要服务桌面交易布局 |
| `MobilePanels` | 原语进入 `ui`，业务内容留在 `mobile` | Sheet/Drawer 可复用，交易内容不可复用 |

组件只有同时满足“无业务依赖、公开 props 稳定、至少两个明确消费点”时才进入 UI 包。否则先留在应用层。

## 7. `@fx-platform/frontend-core` 边界

核心包可以依赖 React hooks、Zustand 和 `shared-types`，但不渲染具体 UI。

目标模块：

```text
packages/frontend-core/src/
├─ api/                # apiClient 及 account/auth/finance/ledger/market/trading API
├─ auth/               # token、session、登录状态与存储
├─ market/             # 行情模型、适配器、订阅、favorites、snapshot
├─ trading/            # 表单状态、校验、提交、订单适配、交易 session
├─ account/            # 账户、余额、钱包、订单、持仓 view-model
├─ storage/            # 浏览器存储的窄接口与实现
├─ models/             # UI 无关的展示模型与格式化函数
└─ index.ts            # 经审核的公共出口
```

规则：

- API 层返回标准结果或抛出标准错误，不直接显示 toast/dialog。
- hooks 返回可测试的状态和 command，不返回 JSX。
- view-model 负责把领域数据转换成两端都能消费的稳定 props。
- 路由跳转由应用层传入回调，核心包不 import React Router。
- 浏览器存储和 WebSocket 通过窄接口隔离，便于 Node 测试。
- `demo` 与 `live` 模式继续由后端与现有 session 语义判定，公共层不得把两者合并。
- 迁移期原路径可以保留纯 re-export；所有消费者迁移后必须删除兼容出口。

## 8. 单应用运行时选择

### 8.1 唯一设备规则

- 以 `max-width: 900px` 作为 Mobile UI 选择条件。
- 设备判断使用 `matchMedia`，监听宽度变化，并通过一个 `useDeviceClass` 出口提供 `pc | mobile`。
- 不使用 User-Agent 作为主要判断条件。
- 不同时挂载两套页面再用 CSS 隐藏；未使用的平台 UI 必须通过动态 import 留在独立 chunk。

### 8.2 Provider 与路由层级

```text
BrowserRouter
└─ ThemeProvider
   └─ Shared runtime providers / stores
      └─ AppRoutes（唯一）
         └─ Route adapter / page controller（保持挂载）
            └─ PlatformView
               ├─ lazy PC view
               └─ lazy Mobile view
```

路由适配器先调用公共 model/hook，再把稳定 props 传给 PC 或 Mobile 视图。切换设备宽度时，认证、行情订阅、交易 session、选中 symbol 和提交状态不得因 UI 切换而重新创建。

PC 和 Mobile 的纯布局状态可以独立保存，例如桌面分栏尺寸与移动抽屉开关使用不同 storage key。业务状态不得存放在平台专属布局组件中。

### 8.3 路由兼容

保持当前路径及 query 语义，包括：

- `/`
- `/trade` 与 `/trading` 兼容重定向
- `/trade/spot/:symbol?` 与 `/trade/perpetual/:symbol?`
- `/trade/:product/:symbol?` 的安全重定向
- `/login`、`/register`、`/forgot-password`、`/two-factor-help`
- `/dashboard`、`/markets`、`/orders`、`/positions`、`/wallet`
- `/account/**`、`/security`、`/settings`

同一个 URL 在不同宽度渲染不同 UI，但访问同一份数据和业务命令。

## 9. 页面与组件数据流

页面遵循以下数据流：

```text
HTTP / STOMP / storage
        ↓
@fx-platform/frontend-core API、store、hook
        ↓
route controller / page model
        ↓
稳定且平台无关的 ViewProps + Commands
        ↓
PC view 或 Mobile view
        ↓
@fx-platform/ui primitives
```

约束：

- 两套 UI 不自行拼接 API URL。
- 两套 UI 不分别实现下单校验、余额推导、行情 fallback 或错误码映射。
- 用户动作通过 command 回到公共 controller；交易风险判断仍以后端为准。
- 错误由核心层标准化，PC/Mobile 决定展示为 inline、toast、dialog 或 sheet。
- loading、empty、error、stale 和 unauthenticated 必须是明确状态，不用伪数据掩盖错误。

## 10. 样式策略

- `packages/ui` 提供语义 Token，不允许消费方依赖具体主题色名称。
- 全局 `styles.css` 最终只保留 reset、文档根节点和极少量应用级基础规则。
- PC 与 Mobile 页面样式分别使用 CSS Modules，禁止跨平台全局选择器。
- 业务组件只使用语义变量；主题色和值集中在 UI 包主题文件。
- 平台选择只由 `useDeviceClass` 决定；迁移完成后不再用大段媒体查询把同一个页面改造成另一套结构。
- 组件内部对窄容器的微调可以使用媒体查询或容器查询，但不能借此重新混合两套页面。
- 图表等需要 JavaScript 色值的模块通过主题对象读取 Token，不复制颜色常量。
- 动效继续遵守 `prefers-reduced-motion`。

## 11. 迁移策略

### 11.1 总目标运行模式

- 在同一个 Codex 任务中建立唯一总目标和 Phase 0–11 进度台账。
- 每个阶段先测试、再实现、验证、提交并更新台账；阶段完成后自动进入下一阶段。
- 编号命令是任务中断、上下文压缩或失败后的恢复入口，不要求用户逐条创建新任务。
- 单个阶段或单个提交完成不等于总目标完成；只有第 15 节全部满足才能结束。
- 测试失败、工作区 dirty 或实现复杂不能作为停止理由；必须分析根因并在授权范围内继续。

### Phase 0：基线审计、改动归属与依赖守卫

- 读取根 `AGENTS.md`、本规格、当前实施计划和当前 Git 状态。
- 记录 staged、unstaged、untracked 文件，并建立“执行前改动 / 本阶段改动 / 无关改动”归属清单。
- 对重叠文件分析调用链、API 契约、测试和业务数据流；业务行为、执行前改动和测试结果优先，目录方案允许调整。
- 禁止 `git reset --hard`、`git checkout --`、自动 stash、删除未知文件或把无关改动混入提交。
- 记录 `web:test`、`web:build`、`verify:architecture`、bundle budget 和大文件审计基线；已知失败必须在后续计划指定阶段关闭。
- 先写会失败的边界检查。
- 创建 `packages/ui` 与 `packages/frontend-core` 的最小 package/tsconfig/export 骨架。
- 增加根 npm scripts，保证两个包可以独立 typecheck/test。
- 边界检查纳入 `verify:architecture` 和统一 `frontend:check` 门禁。

### Phase 1：建立 UI 包并迁移主题

- 将主题 Token、ThemeProvider 和 CSS 变量桥接迁入 `packages/ui`，保持现有 token 值、Provider 层级和视觉输出。
- 建立 UI 包公开出口、独立 test/typecheck 和应用集成测试。
- 使用短期兼容 re-export 保持现有页面可运行，消费者迁移完成后立即删除。

### Phase 2：抽离公共 UI 组件

- 先迁移 Select、IconButton 等已有明确复用点的基础组件。
- 再拆分 PageState、DataTable、Dialog、Sheet/Drawer、Skeleton 等无业务原语；只有无业务依赖、公开 props 稳定且至少有两个明确消费点的组件才进入 UI 包。
- 使用短期兼容 re-export 保持现有页面可运行，消费者迁移完成后立即删除。
- 每迁移一个组件，先迁移或补充测试并明确公共 props，再更新消费者；不改视觉方案。

### Phase 3：抽离 API、认证与行情 Core

- 先迁移纯函数、共享类型、API client、认证存储和浏览器存储窄接口。
- 再迁移行情 runtime、favorites、provider status、HTTP/STOMP adapter 和行情 view-model。
- 每个模块迁移后立即更新 import 并运行相关测试；禁止复制旧实现规避冲突。
- 翻译、路由跳转和展示决策留在应用层，core 返回标准状态、错误 key 和 command。

### Phase 4：抽离 Account 与 Wallet Core

- 迁移账户、余额、钱包、订单、持仓、流水和纯表格 view-model。
- 保持 wallet balance、asset ledger 与 account summary 的既有数据来源和一致性。
- API endpoint、存储 key、错误模型和刷新语义保持不变。

### Phase 5：抽离交易 Controller

- 迁移 trading session、表单状态、校验、订单适配、提交、防重复提交和纯 view-model。
- 保留并验证当前 `orderAdapter.ts` 与测试中的 idempotency/client order 改动。
- React 页面组件、CSS、翻译和平台布局继续留在 `apps/web`。
- 验证 login gate、snapshot freshness、submit dedupe、orders、positions、wallet balance、asset ledger、account summary 和 demo/live 隔离。

### Phase 6：建立自适应入口

- 增加唯一 `useDeviceClass`、`PlatformView`、route adapter、PC Shell 与 Mobile Shell。
- 把公共 Provider、controller、认证、行情订阅和交易 session 放在平台选择之上。
- 保持一份路由契约，并验证深链、刷新、前进后退和 query 参数。
- PC/Mobile 页面使用动态 import 形成独立 chunk，不能同时挂载后再用 CSS 隐藏。

### Phase 7：Auth 与 Home 双 UI

- 迁移首页、登录、注册、找回密码和双因素帮助页面。
- 两端消费同一 controller 和 command，保持现有认证 API、成功跳转和错误语义。

### Phase 8：Account 与 Settings 双 UI

- 迁移 Dashboard、Account、Security 与 Settings。
- 覆盖 loading、empty、error、unauthenticated 和 ready 状态，不修改账户数据或权限语义。

### Phase 9：数据页面双 UI

- 迁移 Markets、Orders、Positions 与 Wallet。
- 验证 wallet balance、asset ledger、account summary、orders、positions、provider status 和 quote。

### Phase 10：Trading Terminal 双 UI

- 复用现有 `TradingDesktopView`、`TradingMobileView` 和 `MobileTradingTerminal` 作为起点。
- PC 保留分栏、resize、桌面工具栏和底部账户面板；Mobile 保留触屏导航、sheet/drawer 和移动交易流程。
- 两端消费同一 selected market、quote snapshot、balances、orders、positions、validation 和 submit command。
- 修复 TradingPage bundle budget 和 TradingPage/TradePanel 大文件门禁，不得放宽现有阈值。

### Phase 11：删除兼容层与总验收

- 删除全部临时 re-export、旧页面、无人使用的旧组件、重复 API/hook/type 和重复样式。
- 将残余页面与平台布局从全局 CSS 移入对应 CSS Modules；全局 `styles.css` 仅保留 reset、根节点和极少量应用级规则。
- 更新 architecture verifier、bundle budget、large-file audit、README 与架构文档。
- 执行完整静态、视觉、交互和业务回归，逐项关闭第 15 节。

## 12. 测试与验收

### 12.1 每个阶段的静态门禁

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

新增包建立后，总门禁还必须覆盖 `ui` 与 `frontend-core` 的 typecheck/test。

统一静态门禁目标为：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

Phase 0 允许如实记录当前 bundle budget 和大文件审计的已知失败；Phase 10 结束前必须修复根因，后续不得重新出现，也不得提高阈值隐藏失败。

### 12.2 视口验收

至少覆盖：

- PC：1440×900。
- Mobile：390×844。
- 边界：899px、900px 与 901px。
- 纵横屏变化和运行时跨断点切换。

每个路由验证直接访问、刷新、前进/后退、query 参数和认证状态。

### 12.3 业务回归

- 登录、退出、token 恢复和未登录门禁。
- 行情 provider 状态、symbol binding、quote、order book、recent trades 和 candles。
- 下单校验、提交、防重复提交、订单、持仓和历史刷新。
- 钱包 balance、asset ledger 与 account summary 一致性。
- `demo` 和 `live` 不混淆；dev profile 继续使用 demo execution。
- PC/Mobile 不重复创建行情或账户订阅。

相关集成环境可用时运行：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:user-core-pages"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:trading-login-gate"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:visual-qa"
```

## 13. 错误处理与回滚

- 包移动采用“新位置实现 → 旧位置 re-export → 更新消费者 → 删除旧出口”的顺序。
- 每个 Phase 由多个可独立审核、可独立回滚的小提交组成。
- 开始阶段前记录现有差异；无关 dirty 文件不暂存、不提交，重叠文件必须保留执行前行为与差异来源。
- 冲突处理采用“调用链与契约分析 → 补保护测试 → 语义合并 → 聚焦验证 → 完整门禁”，不能因为重叠直接停止。
- 若现有实现与目标目录不能原样兼容，优先保留业务行为、API 契约、执行前改动和测试结果，允许调整抽离边界。
- 提交前必须检查 `git diff --cached`；移动重叠文件时，在阶段报告中标明执行前已有改动。
- 某一路由迁移失败时，只回滚该路由适配器，不回滚已稳定的公共包。
- 动态 import 失败使用统一 ErrorState，并提供重新加载，不静默回退到错误平台。
- 核心层保留标准错误码和 retry command；展示层不能吞掉业务拒绝原因。
- 边界脚本或构建失败时不得通过删除测试、放宽规则或复制代码绕过。

## 14. 主要风险与缓解

| 风险 | 缓解措施 |
|---|---|
| 当前未提交改动被覆盖或误提交 | Phase 0 建立改动归属清单；语义合并重叠文件；显式暂存路径或 hunk；提交前审查 staged diff |
| 迁移期循环依赖 | 先建立自动 import 边界，再移动代码 |
| 公共组件演变成业务组件仓库 | 使用严格准入规则；业务组合留在 `shared-widgets` |
| PC/Mobile 重复业务逻辑 | route controller 和 frontend-core 是唯一数据/command 来源 |
| 运行时切换导致 session 或订阅重建 | Provider 与 controller 位于 PlatformView 之上 |
| 单构建包含两端全部首屏代码 | 两套视图动态 import，并检查 Vite chunk |
| 全局 CSS 继续互相覆盖 | CSS Modules、平台目录和最终全局 CSS 清理门禁 |
| 交易页回归风险高 | 最后迁移，单独验证行情、订单、持仓、钱包和 demo/live |
| 源码字符串测试对移动文件敏感 | 迁移时优先转为纯函数/行为测试，保留必要架构检查 |

## 15. 完成定义

只有全部满足才可宣称完成：

1. 仍只有一个 `apps/web` 构建和一份 URL 契约。
2. PC 与 Mobile 都覆盖当前用户端全部路由，并拥有独立 Shell、布局和交互。
3. 构建产物中 PC/Mobile 页面是独立懒加载 chunk，不会同时挂载。
4. `900px` 及以下选择 Mobile，`901px` 起选择 PC；运行时跨断点不会重建公共 session 或重复订阅。
5. 认证、API、行情、交易、账户和钱包逻辑从两套 UI 中移除并由 `frontend-core` 统一提供。
6. 主题和无业务基础组件由 `@fx-platform/ui` 统一提供。
7. `pc`、`mobile`、`ui`、`frontend-core` 的非法依赖检查为零失败。
8. 不存在 PC 与 Mobile 互相 import。
9. 不存在两端各自拼接 API URL、各自创建行情订阅或各自实现交易校验。
10. 旧兼容 re-export、重复实现和被替代的旧页面/组件已删除。
11. 全局 `styles.css` 不再承载具体页面和两端布局。
12. UI/Core/Web 的测试、typecheck、build、architecture verification 全部通过。
13. bundle budget 与 large-file audit 通过，且未放宽现有阈值。
14. 1440×900、390×844、899/900/901px 和运行时跨断点视觉/交互验收通过。
15. 登录、token 恢复、行情 provider/symbol/quote/order book/recent trades/candles、提交防重、订单、持仓和历史刷新通过。
16. 钱包 balance、asset ledger 与 account summary 一致。
17. `demo` 与 `live` 隔离、后端风控权威性和 admin 权限边界保持不变。
18. 架构文档与实际目录、scripts、依赖规则和命令集一致。

## 16. 实施计划与新会话交接

本规格经用户书面复核后，使用 `superpowers:writing-plans` 将现有实施计划重写为端到端计划：

`docs/superpowers/plans/2026-07-15-web-pc-mobile-ui-separation-implementation.md`

同时更新命令集：

`docs/superpowers/plans/2026-07-15-web-pc-mobile-ui-separation-new-session-commands.md`

实施计划将把每个 Phase 拆成可单独测试、审核和提交的任务，并给出：

- 精确文件路径和公共接口。
- 失败测试、实现步骤、验证命令与预期结果。
- 一个可直接复制的总目标命令，在同一 Codex 任务中连续执行全部阶段。
- R0–R11 分阶段恢复命令，用于中断、上下文压缩或失败后的续作。
- 会话开始时的工作区检查、改动归属和冲突语义合并规则。
- 阶段完成后的提交边界、进度台账和自动继续条件。

## 17. 已确认的实施决策

- 范围仅为用户端 `apps/web`；`apps/admin` 不参与双 UI，也不消费本轮 UI 包。
- 保持现有视觉与交互，不做重新设计。
- 保留单应用、单路由；`max-width: 900px` 选择 Mobile UI。
- `@fx-platform/ui` 与 `@fx-platform/frontend-core` 均为仓库内部 workspace 包。
- 覆盖全部用户路由，公共基础优先、交易终端最后。
- 首轮 UI 包只抽离已有明确复用价值的组件，不引入 Storybook、Tailwind 或 CSS-in-JS。
- 页面继续使用 CSS Modules；颜色、间距、字体等进入语义 Token，全局 CSS 逐步收缩。
- 迁移期允许短期 re-export，但最终必须删除。
- 同一个 Codex 任务建立总目标并自动推进；每个阶段验证后形成独立提交。
- 正式执行时的当前工作区是视觉与行为基线；遇到重叠先深度分析再自动语义合并，不破坏业务链路。
- 本轮只交付分析、规格、完整实施计划和 Codex 命令集，不直接执行前端重构。
