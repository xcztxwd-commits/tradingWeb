# 架构说明

`fx-trading-platform` 是独立于根目录 KLineCharts 源码的交易平台子项目，按后端、交易端、管理端、基础设施和验证脚本分层。

当前完整前后端架构和数据库设计请先看：

- [FX Trading Platform 前后端架构与数据库设计总览](./architecture-and-database-design-cn.md)

## 分层边界

- `backend/`: Spring Boot 后端，统一承载认证、账户、行情、交易、风控、执行、资金流水、后台和审计。
- `apps/web/`: 单构建、单路由集的 PC/Mobile 交易端，只调用平台 API，不直接访问 Massive 或数据库。
- `packages/ui/`: `@fx-platform/ui`，只承载主题、语义 token 和无业务 UI 原语；不得依赖业务 core、路由、i18n 或应用代码。
- `packages/frontend-core/`: `@fx-platform/frontend-core`，承载 API、认证、行情、交易、账户、钱包、状态和 view-model；不得依赖 UI、页面、路由、i18n 或 CSS。
- `apps/admin/`: 后台管理端，只访问 `/api/admin/**` 和登录接口，不复用交易端状态。
- `infra/`: 本地 PostgreSQL、Redis 和服务编排。
- `scripts/`: 本地架构约束和端到端 smoke 验证。

## 关键规则

- 行情源适配通过 `market/provider` 的 `ProviderResolver` / `MarketDataRouter` 路由到 Massive、Binance、OKX 等 provider adapter，出站统一为平台 `QuoteResponse`、`CandleResponse`、盘口和近期成交 DTO。
- 下单必须经过 `RiskCheckService`，成交写入统一走 `OrderFillService`。
- 市价单、挂单、止盈止损可以有不同触发入口，但订单、仓位、保证金和资金流水必须复用同一套服务。
- 前端展示异步交易状态时通过后端 API 刷新，不能自行推断订单已成交。

## Web 前端依赖与设备规则

允许的依赖方向为：

```text
apps/web/src/pc ───────────────┐
apps/web/src/mobile ───────────┼─> apps/web/src/shared-widgets ─> @fx-platform/ui
apps/web/src/routes ───────────┘                         └──────> @fx-platform/frontend-core

@fx-platform/frontend-core ─> @fx-platform/shared-types
@fx-platform/ui ─────────────> React / ReactDOM / lucide-react
```

- `pc` 与 `mobile` 不得互相 import；`shared-widgets` 不得反向依赖任一平台目录。
- `@fx-platform/ui` 不得 import `@fx-platform/frontend-core`、应用、router、i18n 或业务类型。
- `@fx-platform/frontend-core` 不得 import `@fx-platform/ui`、应用、router、i18n 或 CSS。
- 应用不得深度 import `@fx-platform/ui/src/*` 或 `@fx-platform/frontend-core/src/*`。
- 唯一结构性设备规则是 `width <= 900px` 使用 Mobile，`width >= 901px` 使用 PC；运行时选择使用 `matchMedia('(max-width: 900px)')`。
- 路由 Controller 和共享 Provider 位于平台 View 之上；PC/Mobile View 独立动态加载，不能同时挂载后通过 CSS 隐藏。
- `npm run verify:frontend-boundaries` 独立检查依赖边界，`npm run verify:architecture` 同时执行该检查。

### 最终目录与职责

```text
apps/web/src/
├─ app/
│  ├─ device/                 # 900/901 设备分类
│  ├─ platform/               # 动态选择且只挂载一个平台 View
│  └─ shell/                  # 平台无关的应用外壳模型
├─ routes/<domain>/           # 每个内容路由唯一的 Controller、model 和 command
├─ pc/pages/<domain>/         # PC View；只消费 route model/command
├─ mobile/pages/<domain>/     # Mobile View；只消费 route model/command
├─ shared-widgets/<domain>/   # 两端共享的展示组合，不持有平台选择
├─ i18n/                      # 应用翻译边界
└─ styles.css                 # reset、文档根节点和应用级基础规则

packages/ui/src/
├─ theme/                     # 主题、语义 token 与 ThemeProvider
├─ select-field/              # SelectField
├─ icon-button/               # IconButton
├─ skeleton/                  # Skeleton
├─ state-surface/             # Loading/Error/Empty 状态面
├─ data-view/                 # DataTable/DataCardList
├─ dialog/                    # Dialog
└─ drawer/                    # Drawer

packages/frontend-core/src/
├─ api/                       # API client 与领域 API
├─ auth/                      # session/storage 语义
├─ storage/                   # 浏览器存储适配
├─ models/                    # 平台无关模型
├─ market/                    # 行情 store、stream、fallback 与 adapter
├─ account/                   # 账户/钱包 Controller 和操作
└─ trading/                   # 表单、订单 adapter 与稳定交易 session
```

`routes` 是应用层唯一的数据/command 入口；`pc` 和 `mobile` 只负责端侧布局与交互表达。认证、行情、账户和交易 Provider/Controller 的生命周期高于平台 View，因此在 `901 → 900 → 899 → 901` 切换中不会重建 session、重复订阅或重复提交。

### 公共包导出

| 包 | 公共导出 | 允许依赖 |
| --- | --- | --- |
| `@fx-platform/ui` | `.`, `./theme`, `./theme.css` | React、ReactDOM、lucide-react（peer） |
| `@fx-platform/frontend-core` | `.`, `./api`, `./auth`, `./storage`, `./models`, `./market`, `./account`, `./trading` | `@fx-platform/shared-types`、STOMP、Zustand、React（peer） |

消费方只能使用上述 package exports，不得深度访问 `src/*`。UI 不含业务、router 或 i18n；frontend-core 不含页面、router、i18n 或 CSS。

### 样式所有权

- `apps/web/src/styles.css` 只承载 reset、根节点、基础排版和稳定 app-root 规则，保持不超过 400 行且不含路由 class selector。
- 页面、平台布局和 shared-widget 样式归属相邻 CSS Module。
- UI 色彩使用 `packages/ui/src/theme/theme.css` 中的语义变量；图表库要求的颜色值只允许集中在有测试的 `shared-widgets/trading/chartTheme.ts` adapter。
- 禁止重新引入 `768/769px` 结构性分支；唯一 PC/Mobile 边界是 `900/901px`。

### 前端开发与验收

```powershell
cd fx-trading-platform
npm install
npm run web:dev

# 一次执行 UI/Core/Web、类型、构建、边界、样式、架构、bundle 和大文件门禁
npm run frontend:check

# 可单独定位层级问题
npm run ui:test
npm run ui:typecheck
npm run frontend-core:test
npm run frontend-core:typecheck
npm run web:test
npm run web:build
npm run verify:frontend-boundaries
npm run verify:frontend-styles
npm run verify:architecture
npm run web:bundle-budget
npm run audit:large-files

# 本地 PostgreSQL/Redis 和 dev/demo 后端就绪后
npm run smoke:visual-qa
npm run smoke:user-core-pages
npm run web:smoke:trading
npm run acceptance:p0-user-trading
```

视觉矩阵至少覆盖 `1440x900`、`390x844`、`899x844`、`900x844`、`901x844` 及同页连续 resize；业务 smoke 覆盖 guest/auth、认证恢复、行情、账户、订单、持仓、钱包、流水和 demo 交易。验收不得连接真实 broker、FIX 或 LP。

## 阶段文档

- [交易下单/持仓链路分层改造说明](./trading-order-position-refactor.md)
