# Web PC/Mobile 双 UI 与公共前端层设计规格

> 最后更新：2026-07-15
>
> 状态：架构与迁移顺序已确认，等待实施计划
>
> 适用范围：`fx-trading-platform/apps/web` 及新增的前端 workspace packages
>
> 权威性：本文件是本轮前端拆分、公共组件库建设和 PC/Mobile 双 UI 迁移的产品与技术基准。

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

截至 2026-07-15，当前代码具有以下特征：

- `apps/web` 是 React 19、Vite 7、TypeScript 5.8、React Router 7 和 Zustand 5 的单体应用。
- 根 workspace 已包含 `apps/*` 与 `packages/*`，并已有 `packages/shared-types`，适合继续增加内部包。
- `TradingPage` 已通过 `TradingDesktopView`、`TradingMobileView` 和 `MobileTradingTerminal` 局部区分两端 UI。
- `design-system` 目前只有主题系统和少量组件，还不是可独立依赖的 workspace UI 包。
- `apps/web/src/styles.css` 约 6,300 行；样式同时包含全局规则、页面规则和响应式规则。
- 页面目录是最大代码区域；`MarketsPage.tsx`、`AccountPages.tsx`、`WalletPage.tsx`、`KLineChartPanel.tsx` 等文件体量较大。
- 源码中存在大量 `@media`、硬编码颜色和全局选择器，PC/Mobile 的样式边界尚未形成。
- 当前工作区存在大量与前端相关的未提交修改和未跟踪文件。任何迁移会话都必须先执行基线门禁，禁止覆盖、重置或误提交这些改动。

## 3. 范围外事项

本轮不做：

- 不拆成两个独立部署应用。
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
- `/trading` 与 `/trade` 兼容重定向
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

### Phase 0：基线门禁

- 读取根 `AGENTS.md` 和本规格。
- 执行 `git status --short`，列出与迁移目标重叠的未提交文件。
- 若当前大量前端改动仍未形成安全基线，停止移动旧文件，只输出冲突清单并请求用户决定如何保存现有改动。
- 禁止 `git reset --hard`、`git checkout --` 或删除用户改动。
- 在安全基线上记录 web test、web build 与 architecture verification 结果。

### Phase 1：依赖守卫与包骨架

- 先写会失败的边界检查。
- 创建 `packages/ui` 与 `packages/frontend-core` 的最小 package/tsconfig/export 骨架。
- 增加根 npm scripts，保证两个包可以独立 typecheck/test。
- 边界检查纳入 `verify:architecture` 或等价总门禁。

### Phase 2：抽离 UI 包

- 先迁移主题 Token、ThemeProvider 和最小基础组件。
- 使用兼容 re-export 保持现有页面可运行。
- 每迁移一个组件，先迁移测试和明确公共 props，再更新消费者。
- 本阶段不改页面布局和视觉稿。

### Phase 3：抽离 frontend-core

- 先迁移纯函数、类型、API client 和存储接口。
- 再迁移行情、认证、交易 session、交易表单和账户 view-model。
- 每个模块迁移后立即更新 import，并运行相关测试。
- 禁止在新核心包中复制一份旧逻辑；旧路径只允许 re-export。

### Phase 4：建立自适应入口

- 增加唯一设备判断和 `PlatformView`。
- 把公共 Provider 放在平台选择之上。
- 保持一份路由契约，并验证深链、刷新和 query 参数。
- 检查构建产物，确认 PC/Mobile 形成懒加载 chunk，首屏不同时下载全部平台页面。

### Phase 5：逐页建立双 UI

按风险从低到高迁移：

1. App Shell、PC 顶栏、Mobile 底栏与导航。
2. 登录、注册、找回密码和首页。
3. Dashboard、账户、安全与设置。
4. Markets、Orders、Positions 与 Wallet。
5. Trading terminal。

每个路由独立完成 controller、PC view、Mobile view、viewport 测试和旧实现清理后，才迁移下一个路由。

### Phase 6：交易终端专项迁移

- 复用现有 `TradingDesktopView` 与 `TradingMobileView` 作为起点。
- 把 `TradingPage` 中数据准备、行情、session、规则、下单和 overlay command 移入公共 controller/model。
- PC 保留分栏、resize、桌面工具栏和底部账户面板。
- Mobile 保留触屏导航、sheet/drawer 和移动交易流程。
- 两端消费同一份 selected market、quote snapshot、balances、orders、positions、validation 和 submit command。

### Phase 7：删除兼容层与全局耦合

- 删除全部临时 re-export 和无人使用的旧组件。
- 清理重复 API、重复 hook、重复类型和重复样式。
- 将残余业务全局 CSS 移入对应平台或组件模块。
- 更新 architecture verifier、README 与架构文档。

## 12. 测试与验收

### 12.1 每个阶段的静态门禁

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

新增包建立后，总门禁还必须覆盖 `ui` 与 `frontend-core` 的 typecheck/test。

### 12.2 视口验收

至少覆盖：

- PC：1440×900。
- Mobile：390×844。
- 边界：899px 与 901px。
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
- 某一路由迁移失败时，只回滚该路由适配器，不回滚已稳定的公共包。
- 动态 import 失败使用统一 ErrorState，并提供重新加载，不静默回退到错误平台。
- 核心层保留标准错误码和 retry command；展示层不能吞掉业务拒绝原因。
- 边界脚本或构建失败时不得通过删除测试、放宽规则或复制代码绕过。

## 14. 主要风险与缓解

| 风险 | 缓解措施 |
|---|---|
| 当前未提交改动被覆盖 | Phase 0 强制停机门禁；只在安全基线上移动文件 |
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
4. 认证、API、行情、交易、账户和钱包逻辑从两套 UI 中移除并由 `frontend-core` 统一提供。
5. 主题和无业务基础组件由 `@fx-platform/ui` 统一提供。
6. `pc`、`mobile`、`ui`、`frontend-core` 的非法依赖检查为零失败。
7. 不存在 PC 与 Mobile 互相 import。
8. 不存在两端各自拼接 API URL、各自创建行情订阅或各自实现交易校验。
9. 旧兼容 re-export 和被替代的旧页面/组件已删除。
10. 全局 `styles.css` 不再承载具体页面和两端布局。
11. 当前 web tests、build、architecture verification 全部通过。
12. PC、Mobile 和断点边界视觉/交互验收通过。
13. 钱包 balance、asset ledger、account summary 以及交易订单/持仓回归通过。
14. `demo` 与 `live` 隔离、后端风控权威性和 admin 权限边界保持不变。
15. 架构文档与实际目录、scripts 和依赖规则一致。

## 16. 实施计划与新会话交接

详细实施计划将在本规格经用户复核后生成到：

`docs/superpowers/plans/2026-07-15-web-pc-mobile-ui-separation-implementation.md`

实施计划将把每个 Phase 拆成可单独测试、审核和提交的任务，并给出：

- 精确文件路径和公共接口。
- 失败测试、实现步骤、验证命令与预期结果。
- 每个新会话可直接复制的任务命令。
- 会话开始时的工作区检查和冲突停止条件。
- 阶段完成后的提交边界和下一会话入口。
