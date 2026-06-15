# FX Trading Platform Design System Master

更新时间：2026-06-12

## 目标

本设计系统用于重构 `C:\Users\User\Desktop\workspace\tradingView-KlineChart` 中的前端体验，核心对象是嵌套项目 `fx-trading-platform`：

- 交易端：`fx-trading-platform/apps/web`，主要手机端入口为 `/trading`。
- 后台端：`fx-trading-platform/apps/admin`，运营和风控后台入口为 `http://localhost:5174`。
- 根项目：`src/` 是 `klinecharts` 图表库源码，除图表样式接口外不作为本轮 UI 重构主体。

目标不是做表面美化，而是建立可扩展的专业交易平台设计系统，支撑行情、K 线、订单簿、下单、资产、订单历史、后台表格等高密度金融场景。视觉参考现代加密货币交易产品的信息密度、极简暗色、行情语义色和终端感，但不得复制任何品牌 Logo、图标、文案、布局细节或商标元素。

## 当前项目结构审计

### 技术栈

`apps/web`：

- React 19
- Vite 7
- React Router 7
- Zustand 5
- CSS Modules + 全局 CSS
- `lucide-react`
- 本地 `klinecharts` 构建产物作为图表依赖

`apps/admin`：

- React 19
- Vite 7
- React Router 7
- 全局 CSS
- `lucide-react`
- 无独立状态管理库，主要使用组件本地状态和服务层请求

### 路由与页面

`apps/web/src/app/App.tsx`：

- `/` -> `/trading`
- `/trade` -> `/trading`
- `/trading` -> `TradingPage`
- `/login` -> `LoginPage`
- `/dashboard`、`/markets`、`/orders`、`/positions`、`/wallet`、`/settings` -> `PlaceholderPage`
- `/admin` -> 旧版内嵌 `AdminPage`

`apps/admin/src/app/AdminApp.tsx`：

- `/login` -> 后台登录
- `/dashboard`
- 系统、产品、财务、会员、订单、日志、内容、配置等大量 `FeatureCrudPage`
- 若干 legacy 页面继续保留

### 组件分层

交易端主要组件：

- 布局：`TradingWorkspace`、`ResizablePanel`、`ResizeHandle`
- 行情侧栏：`MarketSidebar`
- 顶部行情：`SymbolHeader`
- 图表：`ChartWorkspace`、`KLineChartPanel`、`ChartTopToolbar`、`ChartDrawingToolbar`
- 盘口和成交：`MarketSidePanel`、`OrderBook`、`RecentTrades`
- 下单：`TradePanel`、`OrderFormSide`、`PriceInput`、`AmountInput`、`PercentSlider`、`TpSlPanel`
- 底部账户：`BottomAccountPanel`
- 移动端：`MobileDrawer`、`MobileOrderSheet`
- 反馈：`ExchangeLoading`、`TerminalSkeleton`、`LoginPromptDialog`

后台端主要组件：

- `AdminLayout`
- `FeatureCrudPage`
- `adminPageUtils`
- 多个资源页面薄封装

### 样式现状

当前样式分布较分散：

- `apps/web/src/styles.css`：全局 shell、旧交易页、导航、placeholder 基础样式。
- `apps/web/src/pages/trading/TradingPage.module.css`：交易页局部 tokens，只有 `dark` 和 `light` 两套。
- `apps/web/src/features/trading/styles/trade-panel.css`：下单面板局部 tokens，依赖 `--trading-*`。
- 多个交易组件各自维护 radius、shadow、focus、surface、toolbar 样式。
- `apps/admin/src/styles.css`：后台独立 tokens，和交易端命名、视觉语言不一致。

### 状态管理

- 交易符号、图表设置、抽屉开关、登录弹窗、主题模式：主要在 `TradingPage` 本地状态。
- 交易布局：`layoutStore.ts` + `localStorage`。
- 交易市场快照：`marketDataStore.ts`。
- 交易业务会话：`useTradingSession`。
- 后台表格偏好：`FeatureCrudPage` 本地状态 + 后端偏好接口。

### 多语言与文案

当前没有完整 i18n 层：

- 源码中大量中文文案硬编码。
- 多处中文已出现乱码，说明历史写入或编码链路损坏。
- 少量 `Intl.DateTimeFormat('en-US')` 固定为英文。
- 后台数据字段里有 `language` 字段，但它不是前端语言切换体系。
- 缺少 `zh-CN`、`en-US`、`ja-JP` 的统一 message catalog、数字格式、时间格式和语言持久化策略。

### 主题

当前只有局部主题能力：

- `/trading` 通过 `data-theme="dark|light"` 切换两套 CSS 变量。
- `ExchangeLoading` 独立读取同一个 `localStorage` key。
- K 线组件只接收 `dark|light`，没有多主题 token 映射。
- `apps/admin` 没有和交易端共享主题系统。
- 缺少用户可切换的至少 5 套主题、系统主题跟随、主题预览、跨页面持久化。

## 为什么当前 UI 显得 AI 化、不专业

1. **文案乱码直接破坏信任感**  
   交易产品对准确性要求极高，导航、按钮、提示、错误文案乱码会让用户认为系统不可靠。

2. **没有单一设计源头**  
   交易页、下单面板、盘口、后台分别定义自己的变量和样式，导致同一种控件在不同页面出现不同高度、边框、圆角、hover、focus 和阴影。

3. **视觉语言混杂**  
   登录页有大面积氛围渐变和网格动画，交易页是终端风格，后台是偏 SaaS 管理台，旧全局 shell 又是浅色卡片风格。它们不像同一个交易产品。

4. **移动端不是第一设计对象**  
   当前 `/trading` 有移动抽屉和底部操作条，但核心布局仍是桌面终端拆到手机。小屏优先级、固定底部栏 safe area、图表折叠、订单输入键盘避让、行情列表密度还没有系统规则。

5. **主题不可扩展**  
   `dark/light` 被写在页面局部，无法自然扩展到 5 套主题，也无法让后台、登录页、Loading、K 线和业务组件同步切换。

6. **数据密度缺少等级**  
   盘口、表格、订单历史、资产卡、表单都需要不同密度。当前主要靠局部 CSS 调整，缺少 `compact`、`normal`、`comfortable` 的统一定义。

7. **行情语义色不够系统**  
   买入/卖出、上涨/下跌、警告、风险、冻结、成交、撤单等状态应有独立 token。现在大量状态色散落在 CSS 中，浅色和暗色下的对比不总是可审计。

8. **金融数字排版不统一**  
   价格、数量、涨跌幅、余额、时间、手续费应统一使用 tabular figures 或 mono 数据字体。当前有部分 `font-variant-numeric`，但没有系统化规则。

9. **placeholder 页面削弱真实产品感**  
   `/dashboard`、`/markets`、`/orders`、`/positions`、`/wallet`、`/settings` 仍是占位页，和真实交易平台的信息架构不匹配。

10. **交互反馈分散**  
   Modal、Drawer、Toast、Loading、Skeleton、错误恢复、空状态、重试入口没有统一规范。局部组件做得不错，但缺少全局一致性。

## 设计方向

### 产品人格

关键词：

- 专业
- 克制
- 高密度
- 可信
- 实时
- 低装饰
- 数据优先
- 手机端可快速交易

避免：

- 大面积营销式 hero
- 夸张渐变和发光
- 卡片堆叠过多
- 情绪化文案
- 仿品牌视觉
- 用颜色装饰而非表达状态

### 视觉原则

1. **数据优先**  
   第一屏优先展示价格、涨跌、K 线、盘口、下单入口，不用装饰挤占空间。

2. **暗色为主，浅色为辅**  
   默认使用暗色终端主题。浅色主题用于白天环境、后台或用户偏好，不作为主要视觉。

3. **边界轻，层级清**  
   交易平台不需要厚重卡片。用 1px border、轻微 surface 差异和压缩间距建立层级。

4. **行情色只表达行情和状态**  
   绿色/红色只用于买卖、涨跌、风险状态，不能当普通装饰色。

5. **终端感来自密度和精确，不来自复杂装饰**  
   用对齐、数字字体、固定行高、紧凑控件和稳定刷新，而不是霓虹、拟物或品牌化动效。

## 主题架构

主题必须从页面局部迁移为全局 provider：

- 全局属性：`<html data-theme="terminal-pro" data-color-scheme="dark" data-density="compact">`
- 持久化 key：`fx-ui-theme`
- 语言 key：`fx-ui-locale`
- 密度 key：`fx-ui-density`
- 主题定义统一放在 `apps/web/src/design-system/themes.ts` 或等价位置。
- CSS 只消费语义 token，不在组件里写裸 hex。

至少支持 5 套主题：

1. `terminal-pro`：默认暗色，黑灰底，琥珀强调。
2. `graphite-gold`：深石墨，金色强调，更稳重。
3. `midnight-blue`：深蓝黑，青蓝强调，偏科技行情。
4. `oxide-green`：深黑绿，绿色强调，偏终端监控。
5. `arctic-light`：浅色辅助主题，适合强光环境和后台表格。

买卖语义色必须在所有主题中保持同一方向：

- `--color-market-up`
- `--color-market-down`
- `--color-bid`
- `--color-ask`

## 多语言架构

必须支持：

- 简体中文：`zh-CN`
- 英文：`en-US`
- 日文：`ja-JP`

建议采用轻量 message catalog：

- `locales/zh-CN.ts`
- `locales/en-US.ts`
- `locales/ja-JP.ts`
- `i18n.ts` 提供 `t(key, params)`、`formatNumber`、`formatPrice`、`formatPercent`、`formatTime`

规则：

- 禁止业务页面硬编码可见文案。
- 禁止把 `Intl` locale 写死为 `en-US`。
- 所有错误、空状态、按钮、导航、表格列、表单 label 都必须走 key。
- 价格和数量格式化必须和 symbol precision 分离，不能由语言文件决定交易精度。
- 语言切换应在设置抽屉中提供，立即生效并持久化。

## 迁移原则

1. 先修文案和编码，再做视觉扩展。
2. 先抽 token，再重构组件。
3. 先做 `/login` 和 `/trading` 两个样板页。
4. 不改业务逻辑，除非 UI 状态需要明确接口。
5. 每个页面迁移都要有移动端 375px 检查。
6. 所有旧样式迁移后再删除，不做顺手大清理。

## 成功标准

- 5 套主题可在前端手动切换，并覆盖登录页、交易页、Loading、Drawer、Modal、下单表单、盘口和表格。
- 中文、英文、日文可切换，且源码中不再新增可见文案硬编码。
- `/login` 和 `/trading` 成为设计系统样板页。
- 所有可点击目标在手机端不小于 44px 高或有等效 hit area。
- 行情、K 线、订单簿、下单面板、资产和订单历史共用同一套 token。
- 没有乱码文案、品牌复制、装饰性渐变堆叠和无意义 AI 风格文案。
- `npm.cmd --workspace apps/web run test` 和 `npm.cmd --workspace apps/web run build` 作为交易端基础回归。
- `npm.cmd --workspace apps/admin run test` 和 `npm.cmd --workspace apps/admin run build` 作为后台基础回归。

## 文档索引

- `design-system/tokens.md`：颜色、字体、间距、圆角、阴影、动效、z-index、密度。
- `design-system/components.md`：按钮、表单、表格、列表、图表、盘口、交易面板、导航、弹层、反馈。
- `design-system/mobile-guidelines.md`：手机端布局、触控、抽屉、下单、键盘、安全区和性能。
- `design-system/pages.md`：页面级重构计划、路由清单、迁移顺序和验收标准。
