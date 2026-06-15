# 前端 UI 产品体验下一步优化计划

日期：2026-06-13  
范围：`fx-trading-platform/apps/web` 前端页面、共享 UI 组件、前端 smoke/视觉验收脚本。  
目标：把交易终端以外的页面提升到可上线的基础产品体验，同时保持代码简洁、边界清晰，不引入高级交易功能、复杂撮合逻辑或重型抽象。

## 当前判断

当前前端已经从 demo 形态推进到“可用交易平台雏形”：

- `/trading` 已有终端布局、游客看盘、登录提示、下单确认、盘口/成交回填等核心体验。
- `/dashboard`、`/markets`、`/orders`、`/positions`、`/wallet`、`/settings`、`/security` 已经有基础页面和入口。
- 共享的 `DataTable`、`PageState`、危险按钮、移动端卡片表格方向正确。
- 已有 `web:test`、`web:build`、`smoke:user-core-pages` 和临时视觉 smoke 作为验证基础。

主要问题不是功能完全缺失，而是上线前会被用户第一眼感知到的专业度问题：产品文案偏工程化、跨页面视觉语言不完全一致、移动端导航覆盖不完整、弹窗和表格可访问性不足、Markets 榜单数据可信度弱、测试对真实交互的证明还不够强。

## 优化原则

1. 用户可见优先：先改第一屏、导航、文案、危险动作、移动端可读性。
2. 产品语言优先：页面不暴露 `API`、`STOMP`、`真实后端接口`、`前端展示` 这类工程语义。
3. 一致性优先：非交易终端页面统一为账户/工作台风格，交易终端可保留高密度深色专业风格。
4. 小步修改：只在现有组件和样式体系内修补，不新增复杂设计系统或大规模重构。
5. 可验证闭环：每一批优化必须有测试、构建、桌面/移动截图或脚本证据。

## P0：产品文案与第一眼专业度

### 1. 移除工程化页面文案

现状：

- `MarketsPage.tsx` 暴露 `market API 与 STOMP`。
- `PositionsPage.tsx` 暴露 `真实后端接口`。
- `SettingsPage.tsx` 暴露 `当前只保存为前端展示`、`不触发真实交易规则变更`。
- `SecurityCenterPage.tsx` 和登录辅助页部分文案仍像实现说明。

建议：

- 把工程词替换为产品词：
  - `market API 与 STOMP` -> `实时行情源`
  - `真实后端接口` -> `账户数据同步`
  - `当前只保存为前端展示` -> `当前为模拟交易偏好`
  - `真实消息通道后续接入` -> `更多提醒渠道即将支持`
- 在模拟能力上保持诚实，但用产品语气表达，例如“模拟交易模式”“即将支持”“展示预览”。
- 保留开发说明到代码注释或文档，不放到用户界面。

涉及文件：

- `apps/web/src/pages/markets/MarketsPage.tsx`
- `apps/web/src/pages/positions/PositionsPage.tsx`
- `apps/web/src/pages/settings/SettingsPage.tsx`
- `apps/web/src/pages/security/SecurityCenterPage.tsx`
- `apps/web/src/pages/login/AuthSupportPage.tsx`
- `apps/web/src/pages/wallet/WalletPage.tsx`

验收标准：

- 主要页面不出现 `API`、`STOMP`、`后端接口`、`前端展示`、`不做真实` 这类用户不该看到的工程说明。
- 模拟和未实现能力仍清楚标注，不误导用户。
- 页面标题、副标题、空状态、错误状态语气一致。

### 2. Markets 榜单可信度优化

现状：

- `Top Movers`、涨幅榜、跌幅榜、成交额榜已经存在。
- 当数据变化全为 `0.00%` 时，多个榜单看起来像假数据。
- sparkline 目前基于 symbol seed 和 changePercent 生成，占位性质明显。

建议：

- 当榜单全部为 `0.00%` 时，显示更诚实的状态：
  - “暂无显著波动”
  - “等待更多行情更新”
  - “趋势预览”
- sparkline 文案统一为“趋势预览”，不要暗示真实历史曲线。
- 成交额榜当前是按价格和 volume 估算，标题或说明应写成“成交额预估”。
- 不做高级数据分析，不引入历史行情算法；只让展示语义和数据可信度匹配。

涉及文件：

- `apps/web/src/pages/markets/MarketsPage.tsx`
- `apps/web/src/styles.css`

验收标准：

- 全零行情时页面看起来仍专业，不出现五个榜单都强行排序但数值完全一致的尴尬状态。
- sparkline 有可访问说明，屏幕阅读器不会把占位当真实趋势。
- 桌面 `/markets` 首屏仍保持信息密度，不变成营销页。

## P1：移动端导航与内容避让

### 1. 移动端补齐自然入口

现状：

- `App.tsx` 通过 `navItems.slice(0, 5)` 只展示概览、终端、行情、订单、持仓。
- 移动端侧栏隐藏后，资金、安全、设置、后台入口不可见或不自然。

建议：

- 底部导航保持 5 个以内：
  - 概览
  - 终端
  - 行情
  - 订单
  - 我的
- “我的”可以落到 `/wallet` 或新增轻量入口页，承载资金、安全、设置。
- 后台入口不应默认占用普通移动端主导航，可保留桌面侧栏或按角色显示。

涉及文件：

- `apps/web/src/app/App.tsx`
- `apps/web/src/styles.css`
- 可选：`apps/web/src/pages/account/AccountHubPage.tsx`

验收标准：

- 移动端可以从自然路径进入资金、安全、设置。
- 底部导航不超过 5 项，每项有图标和文本。
- 普通用户不会在移动底栏看到“后台”这种强管理语义入口。

### 2. 固定底栏与内容滚动避让

现状：

- 移动端 `.mobile-tabs` 已有 safe-area padding。
- 页面内容底部仍可能贴近或被底栏视觉遮挡，尤其是长列表和设置页。

建议：

- 非交易终端页面在移动端增加统一底部内容 inset，例如在 `.main-region` 或 `.user-page` 上按底栏高度预留空间。
- 避免每个页面单独写 padding，优先用 shell 层或 `user-page` 层统一处理。
- `/trading` 的移动端交易操作栏单独处理，不与账户页规则混用。

涉及文件：

- `apps/web/src/styles.css`
- `apps/web/src/pages/trading/components/MobilePanels.module.css`

验收标准：

- 390 x 844、375 x 667、430 x 932 下无内容被底栏遮挡。
- `document.documentElement.scrollWidth <= window.innerWidth`。
- 最后一张卡片、最后一个按钮、分页控件均可完整滚动到可见区域。

## P1：跨页面视觉一致性

### 1. 统一非终端页面的视觉语言

现状：

- `Dashboard`、`Markets`、`Orders`、`Positions`、`Wallet` 使用浅色 `user-page` 体系。
- `Settings` 使用 `theme` 和 `trading` token，移动端截图呈现明显深色卡片，与其他账户页割裂。

建议：

- 非交易终端页面统一使用 `user-page` 账户工作台风格：
  - 浅色背景
  - 8px 卡片圆角
  - 同一套边框、阴影、标题、副标题、按钮层级
- 交易终端继续使用深色专业终端风格。
- Settings 的主题切换可以保留，但页面本身不应突然切换到另一套视觉产品。

涉及文件：

- `apps/web/src/pages/settings/SettingsPage.tsx`
- `apps/web/src/pages/settings/SettingsPage.module.css`
- `apps/web/src/pages/security/SecurityCenterPage.tsx`
- `apps/web/src/styles.css`
- `apps/web/src/design-system/theme/*`

验收标准：

- `/dashboard`、`/markets`、`/orders`、`/positions`、`/wallet`、`/settings`、`/security` 在桌面和移动端看起来属于同一个产品。
- 卡片、按钮、空状态、错误状态、加载态的边框、圆角、字号、间距一致。
- Settings 的主题选择表达为“偏好设置”，不改变账户页自身视觉基调。

### 2. 建立页面级文案和 UI 对照表

建议新增一个轻量文案表，不需要复杂 i18n：

- 页面标题
- 页面副标题
- 主要 CTA
- 空状态标题/描述/动作
- 错误状态标题/描述/动作
- 危险动作确认标题/说明

涉及文件：

- 可选：`apps/web/src/components/user-page/userPageCopy.ts`
- 或在现有页面内先保持局部常量，不急于抽象。

验收标准：

- 同类页面不再出现中英混杂或语气不一致。
- 空状态都有下一步动作。
- 危险操作都明确说明影响。

## P2：交互可访问性与危险操作确认

### 1. 完善平仓确认弹窗可访问性

现状：

- `PositionsPage.tsx` 已有平仓确认弹窗和 `table-action--danger`。
- 弹窗缺少完整的 Escape 关闭、初始焦点、焦点回到触发按钮、背景 inert/focus trap。

建议：

- 打开弹窗后焦点进入“取消”或弹窗标题区域。
- 支持 `Escape` 关闭。
- 关闭后焦点回到触发“平仓”的按钮。
- 确认按钮保持危险色，取消按钮保持次级样式。
- loading 状态期间避免重复提交。

涉及文件：

- `apps/web/src/pages/positions/PositionsPage.tsx`
- `apps/web/src/styles.css`
- 可选：只在页面内用小 hook，不抽通用 modal 框架，除非下单确认、平仓确认、提现确认都需要共享。

验收标准：

- 键盘用户可以完整打开、阅读、取消、确认。
- 屏幕阅读器可以读到标题、说明、关键字段。
- 关闭后焦点位置可预测。

### 2. DataTable 排序语义补齐

现状：

- `DataTable.tsx` 有排序按钮和箭头。
- `th` 未设置 `aria-sort`，屏幕阅读器无法得知当前排序状态。

建议：

- 在可排序列的 `th` 上设置 `aria-sort="ascending|descending|none"`。
- 排序按钮增加 `aria-label`，例如“按 Created time 升序排序”。
- 移动端卡片中可以不展示排序控件，但桌面排序状态要完整。

涉及文件：

- `apps/web/src/components/user-page/DataTable.tsx`
- `apps/web/src/components/user-page/userPageUi.test.ts`

验收标准：

- 排序状态可通过 DOM 属性断言。
- 视觉排序箭头和 `aria-sort` 一致。
- 现有表格分页和移动端卡片不回归。

### 3. Tab、错误和 toast 状态语义

建议：

- `user-page__tabs` 中的按钮补齐 `role="tab"`、`aria-selected`，内容区域可选加 `role="tabpanel"`。
- `ApiErrorState` 可增加 `role="alert"` 或 `aria-live="assertive"`，错误被及时读出。
- 成功提示使用 `aria-live="polite"`，不抢焦点。

涉及文件：

- `apps/web/src/components/user-page/PageState.tsx`
- `apps/web/src/pages/orders/OrdersPage.tsx`
- `apps/web/src/pages/positions/PositionsPage.tsx`
- `apps/web/src/styles.css`

验收标准：

- 键盘 tab 顺序自然。
- 错误和成功反馈能被辅助技术感知。
- 不改变现有视觉层级。

## P2：测试与验收链路加强

### 1. 把源码字符串测试升级为行为测试

现状：

- `userPages.test.ts` 主要用 `assert.match` 检查源码字符串。
- 这能防止入口被删，但不能证明交互正确。

建议：

- 保留少量结构 guardrail。
- 新增/扩展纯函数测试：
  - Markets 收藏 localStorage 读写和 merge。
  - Orders 状态、交易对、时间筛选。
  - Wallet 资金流水筛选。
  - Positions TP/SL 状态格式化。
  - Dashboard 风险摘要计算。
- 对弹窗和表格语义增加轻量 DOM 或源码结构测试。
- 不急于引入大规模 UI 测试框架，除非现有 Node test 难以覆盖。

涉及文件：

- `apps/web/src/pages/userPages.test.ts`
- `apps/web/src/features/market/tradingModels.test.ts`
- `apps/web/src/components/user-page/userPageUi.test.ts`
- 可选新增：`apps/web/src/pages/orders/ordersPageModels.test.ts`

验收标准：

- 不是只断言“字符串存在”，而能证明筛选、排序、收藏、弹窗状态逻辑。
- `npm.cmd --prefix fx-trading-platform run web:test` 通过。

### 2. 固化全局页面视觉 smoke

现状：

- 已有临时视觉 smoke 证明主要页面无横向溢出、无 framework overlay、无 runtime error。
- 证据在临时目录，不利于后续重复执行。

建议：

- 新增仓库脚本，例如 `smoke:web-pages` 或扩展 `smoke:user-core-pages`。
- 覆盖路由：
  - `/dashboard`
  - `/markets`
  - `/orders`
  - `/positions`
  - `/wallet`
  - `/settings`
  - `/security`
  - `/login`
  - `/register`
  - `/forgot-password`
  - `/two-factor-help`
- 覆盖视口：
  - desktop 1440 x 900
  - mobile 390 x 844
- 断言：
  - 无 console error
  - 无 framework overlay
  - 无 body 横向滚动
  - 页面非空
  - 移动端底栏不遮挡最后一个关键操作

涉及文件：

- `scripts/smoke-user-core-pages.mjs`
- 可选新增：`scripts/smoke-web-pages-visual.mjs`
- `package.json`

验收标准：

- 一条命令能生成截图和 JSON 报告。
- 报告路径在终端输出。
- CI 或本地回归可复用。

## P3：样式债务和设计 token 收敛

### 1. 减少全局 CSS 增长风险

现状：

- `styles.css` 承载了 shell、账户页、表格、行情榜单、settings、安全、移动端规则。
- 当前仍可维护，但继续叠加会变成新的大文件热点。

建议：

- 保留全局 shell 和通用 `user-page` 基础样式。
- 页面专属复杂样式逐步迁到 module css：
  - Markets rank card
  - Wallet action card
  - Dashboard overview card
  - Settings surface
- 不要一次性拆完；每次改页面时顺手迁移局部样式。

涉及文件：

- `apps/web/src/styles.css`
- `apps/web/src/pages/*/*.module.css`

验收标准：

- 新增页面样式优先在页面 module 中完成。
- 通用样式只保留真正跨页面复用的规则。
- `styles.css` 不继续无限增长。

### 2. 语义 token 统一

建议：

- 账户页使用一套语义变量：
  - `--user-page-bg`
  - `--user-surface`
  - `--user-border`
  - `--user-text`
  - `--user-muted`
  - `--user-primary`
  - `--user-danger`
- 交易终端使用 `--theme-*` 和 `--trading-*`。
- 不在同一页面混用两套 token，除非是明确嵌入交易终端组件。

验收标准：

- Settings 不再混用账户页和交易终端视觉语义。
- 危险、成功、警告、主按钮颜色在所有账户页含义一致。

## 暂不纳入本轮的内容

以下事项有价值，但不适合和本轮 UI 专业度优化混在一起：

- 高级行情分析、真实历史 sparkline、复杂指标计算。
- 真实支付、真实链上充值提现、支付渠道闭环。
- 自选同步服务端接口，如果后端没有现成能力，本轮只保留 localStorage。
- 新增复杂 modal framework、全量 i18n、完整设计系统重写。
- 根目录 KLineCharts 大文件治理。
- 后端撮合、策略、风控算法升级。

## 推荐执行顺序

### 第 1 批：快速可见优化

目标：半天内提升第一眼专业感。

内容：

1. 清理工程化文案。
2. 修正 Markets 全零榜单和 sparkline 占位语义。
3. 移动端增加“我的/资产”自然入口。
4. 给移动端账户页增加底部内容避让。

验证：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
```

浏览器检查：

- `/markets` desktop 1440 x 900
- `/markets` mobile 390 x 844
- `/settings` mobile 390 x 844
- `/wallet` mobile 390 x 844

通过标准：

- 无横向滚动。
- 底部导航不遮挡最后内容。
- 用户界面不出现工程化文案。

### 第 2 批：一致性和可访问性

目标：把账户页统一成一个产品。

内容：

1. Settings/Security 统一账户页浅色视觉。
2. 平仓确认弹窗补焦点和 Escape。
3. DataTable 补 `aria-sort`。
4. Tab 和错误状态补语义。

验证：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
```

浏览器检查：

- `/dashboard`、`/markets`、`/orders`、`/positions`、`/wallet`、`/settings`、`/security`
- desktop 1440 x 900
- mobile 390 x 844

通过标准：

- 非终端页面视觉一致。
- 危险操作可键盘完成和取消。
- 排序状态有 DOM 语义。

### 第 3 批：验收脚本固化

目标：把当前人工/临时视觉验证变成可重复脚本。

内容：

1. 固化全局页面视觉 smoke。
2. 为登录态下的 Dashboard/Wallet/Orders/Positions 捕获截图。
3. smoke 输出截图路径和 JSON 报告路径。
4. 把移动端底部遮挡和横向滚动作为硬断言。

验证：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:user-core-pages"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
```

通过标准：

- 页面链路、构建、视觉 smoke 三类证据齐全。
- 失败时能定位到具体路由、视口、截图。

## 完成定义

本轮优化完成必须同时满足：

- `web:test` 通过。
- `web:build` 通过。
- 核心页面 smoke 通过。
- 桌面和移动端截图可复查。
- 无 body 横向滚动。
- 移动端底部栏不遮挡关键内容。
- 用户界面无明显工程化文案。
- 危险操作视觉和交互语义明确。
- 新增代码保持在现有页面/组件边界内，不新增复杂抽象。

## 建议交付物

- 代码改动清单。
- 验证命令和结果。
- 截图目录或 smoke JSON 报告路径。
- 剩余风险说明。
- 如新增脚本，说明脚本覆盖哪些路由和断言。
