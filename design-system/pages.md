# Page Redesign Plan

## 页面范围

本计划覆盖两个前端应用：

- `fx-trading-platform/apps/web`：用户交易端，手机端优先。
- `fx-trading-platform/apps/admin`：后台运营端，桌面优先但必须支持窄屏基础可用。

根 `klinecharts` 图表库不做 UI 页面重构，只保留图表样式适配接口。

## apps/web 页面清单

| Route | 当前组件 | 当前状态 | 重构目标 |
|---|---|---|---|
| `/` | Redirect | 跳转 `/trading` | 保持 |
| `/trade` | Redirect | legacy 跳转 | 保持，停止新功能进入 |
| `/trading` | `TradingPage` | 核心交易页，局部主题 | 作为 Design System 主样板 |
| `/login` | `LoginPage` | 有视觉基础但文案乱码 | 作为认证样板，修复文案、主题、语言 |
| `/dashboard` | `PlaceholderPage` | 占位 | 做账户概览 |
| `/markets` | `PlaceholderPage` | 占位 | 做市场行情列表 |
| `/orders` | `PlaceholderPage` | 占位 | 做订单中心 |
| `/positions` | `PlaceholderPage` | 占位 | 做持仓中心 |
| `/wallet` | `PlaceholderPage` | 占位 | 做资产与流水 |
| `/settings` | `PlaceholderPage` | 占位 | 做主题、语言、安全设置 |
| `/admin` | `AdminPage` | web 内嵌旧后台 | 评估移除或跳转独立 `apps/admin` |

## apps/admin 页面清单

| Route Group | 当前组件 | 当前状态 | 重构目标 |
|---|---|---|---|
| `/login` | `LoginPage` | 后台登录，文案乱码 | 使用统一认证和后台主题 |
| `/dashboard` | `DashboardPage` | 后台总览 | 管理台仪表盘 |
| `/system/*` | `FeatureCrudPage` | 通用 CRUD | 统一表格、筛选、弹窗 |
| `/products/*` | `FeatureCrudPage` | 通用 CRUD | 产品管理表格 |
| `/finance/*` | `FeatureCrudPage` | 通用 CRUD | 财务管理高风险动作强化 |
| `/members/*` | `FeatureCrudPage` | 通用 CRUD | 用户和支付账户管理 |
| `/orders/history` | `FeatureCrudPage` | 通用 CRUD | 订单历史 |
| `/logs/*` | `FeatureCrudPage` | 通用 CRUD | 日志表格 |
| `/content/*` | `FeatureCrudPage` | 通用 CRUD | 内容管理 |
| `/config/*` | `FeatureCrudPage` | 通用 CRUD | 配置管理 |
| legacy pages | 多个页面 | 过渡状态 | 后续合并到通用页面或删除 |

## 第一阶段：设计系统基础

目标：

- 建立全局 tokens。
- 建立主题 provider。
- 建立 i18n provider。
- 修复编码和乱码源头。

工作项：

1. 新增 `apps/web/src/design-system`：
   - `tokens.css`
   - `themes.css`
   - `ThemeProvider.tsx`
   - `LocaleProvider.tsx`
   - `types.ts`
2. 建立 5 套主题。
3. 建立 `zh-CN`、`en-US`、`ja-JP` 文案目录。
4. 将 `TradingPage` 局部 `data-theme` 升级为全局主题。
5. 将 `ExchangeLoading` 改为消费全局主题，而不是自己读 `localStorage`。

验证：

- 主题切换后 `/login`、`/trading`、route loading 一致。
- 语言切换后导航和主要按钮无乱码。
- 现有测试和 build 通过。

## 第二阶段：登录页样板

目标：

- 将 `/login` 做成低频认证样板，不再像营销页。
- 修复文案乱码。
- 加入主题、语言切换入口。
- 保持移动端优先。

设计：

- 手机端单列。
- 顶部显示品牌文本、环境状态和语言/主题入口。
- 表单为主体，不使用大面积装饰网格。
- 辅助说明精简，不堆产品口号。
- 登录失败显示字段或表单级错误。

组件：

- `AuthShell`
- `AuthPanel`
- `TextField`
- `PasswordField`
- `ThemeSwitcher`
- `LocaleSwitcher`

验证：

- 375px 下无水平滚动。
- 中/英/日切换后按钮不溢出。
- dark + arctic-light 对比合格。
- 登录 loading 和错误状态清晰。

## 第三阶段：移动端交易页样板

目标：

- `/trading` 成为专业交易平台手机端样板。
- 同时保持桌面终端布局可用。

设计：

- 手机第一屏：symbol header、K 线、快速交易入口。
- 盘口、市场列表、下单全部 sheet/drawer 化。
- 订单和资产信息从底部面板迁移为手机 tabs 或页面入口。
- 桌面保留 `TradingWorkspace` 可调布局，但接入全局 tokens。

组件迁移：

- `SymbolHeader` 使用统一 `MarketTickerHeader` contract。
- `ChartWorkspace` 接入 `chart.*` tokens。
- `MarketSidePanel` 接入 `OrderBook` spec。
- `TradePanel` 接入统一 field、button、segmented control。
- `BottomAccountPanel` 接入 table/list spec。
- `MobileDrawer`、`MobileOrderSheet` 合并为通用 `Sheet`/`Drawer`。

验证：

- 375px 手机可完成：选交易对、看 K 线、打开盘口、打开下单、输入数量、处理未登录。
- 盘口深度条不遮挡数字。
- 图表不空白，切主题后 K 线颜色同步。
- 订单面板键盘弹出后提交按钮可访问。

## 第四阶段：公共组件抽象

抽象顺序：

1. 基础：
   - `Button`
   - `IconButton`
   - `TextField`
   - `NumberField`
   - `SegmentedControl`
   - `Switch`
   - `Select`
2. 反馈：
   - `Toast`
   - `Dialog`
   - `Drawer`
   - `Sheet`
   - `Skeleton`
   - `StateBlock`
3. 数据：
   - `DataTable`
   - `DataList`
   - `MetricStrip`
   - `StatusBadge`
4. 交易：
   - `OrderBook`
   - `TradeForm`
   - `ChartPanel`
   - `MarketTicker`

规则：

- 不为了抽象而抽象。至少两个页面真实复用再提升。
- 先在登录页和交易页验证，再迁移其他页面。
- 每个组件必须有状态列表和移动端规则。

## 第五阶段：迁移所有用户端页面

### Dashboard

目标：

- 账户权益
- 今日盈亏
- 保证金状态
- 最近订单
- 风险提示

手机布局：

- `MetricStrip`
- `AssetSummaryCard`
- `RecentActivityList`

### Markets

目标：

- 交易对列表
- 搜索
- 自选
- 涨跌幅排序
- 成交额排序

手机布局：

- sticky search
- tab: Favorites / USDT / Spot / Futures
- compact market rows

### Orders

目标：

- 当前订单
- 历史订单
- 成交记录
- 筛选

手机布局：

- tabs + filter sheet
- order summary cards
- row action menu

### Positions

目标：

- 当前持仓
- 历史持仓
- 平仓动作
- 风险和保证金

手机布局：

- position cards
- close confirmation sheet
- PnL 使用行情语义色和符号

### Wallet

目标：

- 资产总览
- 币种余额
- 流水
- 充值、提现、划转入口

手机布局：

- total asset header
- asset list
- ledger timeline

### Settings

目标：

- 主题切换
- 语言切换
- 密度切换
- 安全设置
- 通知偏好

手机布局：

- grouped settings list
- swatch theme picker
- locale picker

## 第六阶段：迁移后台

后台优先级：

1. 修复乱码和 i18n。
2. 将 `apps/admin/src/styles.css` 接入共享 tokens。
3. 统一 `AdminLayout` 和 `FeatureCrudPage` 的表格、筛选、按钮、弹窗。
4. 财务、风控、强制操作增加危险态确认规范。
5. legacy 页面逐步合并或标记。

后台设计不追求交易端的移动交易体验，但应保持同一色彩、字体、状态和组件语言。

## 第七阶段：响应式、性能、无障碍、回归

### 响应式

- 375、390、412、768、1024、1440。
- 交易页横屏检查。
- 后台表格窄屏检查。

### 性能

- 图表和行情更新不能触发整页 re-render。
- 长表格分页或虚拟化。
- Drawer/sheet 懒加载重内容。
- Skeleton 预留高度，避免布局跳动。

### 无障碍

- 所有图标按钮有 label。
- 弹窗焦点管理。
- Toast 使用 aria-live。
- 表单错误关联字段。
- 颜色不作为唯一信息。

### 回归命令

交易端：

```powershell
npm.cmd --workspace apps/web run test
npm.cmd --workspace apps/web run build
```

后台端：

```powershell
npm.cmd --workspace apps/admin run test
npm.cmd --workspace apps/admin run build
```

根入口：

```powershell
npm.cmd run fx:web:check
```

## 分阶段验收

| 阶段 | 验收标准 |
|---|---|
| Design System 基础 | 5 主题、3 语言、无新增硬编码文案 |
| 登录页样板 | 主题和语言可切换，手机端无溢出 |
| 交易页样板 | 手机端核心交易路径完整 |
| 公共组件 | 基础组件可复用，状态完整 |
| 用户端页面 | 占位页全部替换为真实结构 |
| 后台迁移 | 表格、筛选、弹窗统一 |
| 最终回归 | 测试、build、移动端、无障碍通过 |
