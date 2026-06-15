# HTML 导出原型到当前前端的分析与迁移映射

日期：2026-06-14  
原型目录：`C:\Users\User\Desktop\工具\html`  
目标项目：`C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\apps\web`

## 结论

这批 HTML 文件更适合作为视觉和信息架构参考，不适合直接复制到当前项目。原因是这些文件是站点导出产物，包含大量压缩后的第三方脚本、监控、Cookie、Web Push、验证码、品牌资源和运行时代码。当前项目已经有 React 页面、路由、状态和 smoke 验收脚本，正确做法是抽取布局、样式语言和交互意图，然后在现有组件内重建。

本轮建议先做四个局部切片：先修复目标页面的可见中文乱码，再按主页、行情页、注册页、个人中心顺序迁移视觉和布局。不要一次性重写全站。

## 原型文件概览

| 原型页面 | HTML 体量 | CSS | JS | 可用信息 | 不应迁移 |
| --- | ---: | ---: | ---: | --- | --- |
| `主页` | 2603 KB | 6 个 | 34 个 | 首页首屏、行情预览、新闻、下载、FAQ、黑金视觉 | `gtm.js`、Cookie、品牌 logo、远程 widget |
| `行情-总览` | 3533 KB | 8 个 | 35 个 | 二级 tab、热门/新币/涨幅/成交榜、行情表格 | 远程公共组件、品牌资产、完整压缩 runtime |
| `行情-交易数据-排行榜` | 2767 KB | 8 个 | 33 个 | 交易数据榜单、排名卡片、榜单 tab | 第三方脚本、统计/监控逻辑 |
| `注册` | 341 KB | 7 个 | 39 个 | 单列表单、邮箱/手机入口、社交按钮布局 | captcha、referral widget、Google/Apple/Telegram SDK |
| `个人中心-总览` | 2531 KB | 20 个 | 35 个 | 账户侧边栏、资产卡片、认证入口、充值/提现/买币操作 | sidebar widget、品牌图、Cookie、Web Push |

CSS 参考特征：

- 主色：`#f0b90b` / `#fcd535`；涨跌色：`#2ebd85`、`#f6465d`。
- 深色底：`#181a20`、`#1e2329`、`#202630`；中性文字：`#848e9c`、`#9ca3af`。
- 字号密度集中在 `12px`、`14px`、`16px`、`20px`、`24px`。
- 圆角主要是 `4px`、`8px`、`12px` 和 token 化的 `var(--radii-s/m)`。
- 动效主要是 hover、fade、spin、skeleton、轻微 transform；不要引入复杂背景动画。

## 当前项目现状

当前项目已经具备这些落点：

- 首页：`apps/web/src/pages/home/HomePage.tsx` 和 `HomePage.module.css`
- 行情页：`apps/web/src/pages/markets/MarketsPage.tsx`，主要样式在 `apps/web/src/styles.css`
- 注册页：`apps/web/src/pages/login/AuthSupportPage.tsx`，复用 `LoginPage.module.css`
- 登录页：`apps/web/src/pages/login/LoginPage.tsx`
- 个人中心：`apps/web/src/pages/account/AccountHubPage.tsx`、`AccountPages.tsx`
- 全局导航：`apps/web/src/app/AppShell.tsx`、`apps/web/src/app/navigation.ts`
- 多语言：`apps/web/src/i18n/locales/zh-CN.ts`

明显风险：

- 多个目标文件里的中文已出现 mojibake，例如 `鎬昏`、`琛屾儏`、`鐧诲綍`。如果先迁移样式，不修复文案，视觉验收仍然会失败。
- `MarketsPage.tsx` 已有行情 tab、筛选、排序、收藏、桌面表格、移动列表，不需要重写数据逻辑。
- 注册页已有邮箱/手机、多步骤、验证码和本地 fallback，不应退化成静态表单。
- 个人中心已有账户侧栏、资产、KYC、资金流水和交易订单入口，应保留路由和业务结构。

## 页面映射

### 主页

原型意图：

- 第一屏强调用户规模、注册入口和行情预览。
- 右侧或下方展示热门币、新闻、下载入口。
- 常见问题和信任说明作为后续内容。

当前落点：

- `HomePage.tsx` 已经由 `HomeHeroGuest`、`HomeHeroUnverified`、`HomeHeroVerified`、`MarketPreviewPanel`、`NewsPreviewPanel`、`TrustAwardsStrip`、`HomeSupportSections` 组成。
- 主要修改应集中在 `HomePage.module.css` 和相关 home components。

建议：

- 保留现有组件拆分，只调整首屏层级、间距、卡片密度和文案。
- 不添加导出的 footer、Cookie 或下载 SDK。
- 避免复制品牌图片；如需要视觉资产，使用当前项目自己的 logo/图标。

### 行情页

原型意图：

- 一级页面标题：加密货币市场。
- 二级 tab：总览、交易数据、AI 精选、代币解锁。
- 总览包含热门、新币、领涨、成交等卡片，以及完整行情表格。
- 交易数据页偏榜单网格。

当前落点：

- `MarketsPage.tsx` 已经有 `MarketPageTab`、`MarketSummaryDeck`、`TradingDataDashboard`、`MarketFilters`、`MarketTable`、`MarketMobileList`。
- 样式集中在 `styles.css` 的 `.market-shell`、`.market-summary-*`、`.market-table-*`、`.market-rank-*`。

建议：

- 首先修复所有可见中文、`aria-label` 和表头。
- 保留当前 `mockTradingMarkets`、实时 quote 更新、排序和收藏逻辑。
- 将原型的 tab 密度、榜单卡片、表格行高、颜色和移动端列表样式迁移进现有 CSS。
- 不新增 AI 选币或解锁接口，保留当前 placeholder，但用产品语言说明。

### 注册页

原型意图：

- 单一登录/注册壳，表单聚焦。
- 邮箱/手机入口，第三方登录按钮可作为视觉样式。
- 主按钮醒目，辅助链接轻量。

当前落点：

- `/register` 已由 `AuthSupportPage.tsx` 的 `RegisterPage` 承载。
- 登录、注册、找回密码和 2FA 共用 `LoginPage.module.css`。

建议：

- 保留当前多步骤注册逻辑。
- 修复中文文案，调整表单为更接近原型的单列紧凑布局。
- 社交登录按钮如果没有真实能力，只能作为禁用或不展示；不要引入 Google/Apple/Telegram 脚本。
- 不复制 captcha/referral 业务逻辑。

### 个人中心

原型意图：

- 左侧账户导航，右侧总览内容。
- 顶部身份/认证状态，核心资产卡片，充值/提现/买币快捷操作。
- KYC、新手任务、资金流水、账户安全入口清晰。

当前落点：

- `AccountHubPage.tsx` 更像移动/入口页。
- `AccountPages.tsx` 已有 `/account/overview`、资产、资金流水、订单、KYC、设置等子页。
- 主要样式在 `styles.css` 的 `.account-shell`、`.account-sidebar`、`.account-panel`、`.account-dashboard-*`。

建议：

- 以 `/account/overview` 为主要改造目标，不要只改 `AccountHubPage`。
- 保留侧栏和子路由结构。
- 修复中文乱码后，再调整卡片顺序：身份摘要、资产估值、认证步骤、快捷操作、最近流水。
- 移动端侧栏应转为横向 tab 或顶部入口，避免遮挡底部导航。

## 不要做的事

- 不直接复制 `vendor-*.css`、`common-*.css`、`main.*.js`、`gtm.js`、`captcha.min.js`、`web-push-*`。
- 不复制第三方品牌 logo、Cookie 弹窗、远程 common-widget。
- 不引入新的 UI 框架。
- 不把压缩 class 名迁移到当前项目。
- 不重构交易终端 `/trading`，除非后续明确要求。
- 不把原型里的“看起来有”的能力当成真实业务功能补上，例如 AI 选币、代币解锁、社交登录、真实 captcha。

## 建议执行切片

### Slice 0：目标页面中文乱码基线修复

目标：

- 修复本次涉及页面的可见中文和关键 `aria-label`。
- 范围限定在首页、行情、注册/登录、个人中心、导航和 `zh-CN.ts` 中这些页面实际用到的键。

文件：

- `apps/web/src/i18n/locales/zh-CN.ts`
- `apps/web/src/app/AppShell.tsx`
- `apps/web/src/pages/markets/MarketsPage.tsx`
- `apps/web/src/pages/login/AuthSupportPage.tsx`
- `apps/web/src/pages/account/AccountPages.tsx`
- `apps/web/src/pages/account/AccountHubPage.tsx`

验证：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
```

### Slice 1：首页视觉对齐

目标：

- 首页首屏更接近原型的信息架构：注册入口、行情预览、新闻/信任模块。
- 保持现有 `HomeHero*` 状态分支。

文件：

- `apps/web/src/pages/home/HomePage.tsx`
- `apps/web/src/pages/home/HomePage.module.css`
- `apps/web/src/pages/home/components/*`

验证：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:user-core-pages"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:visual-qa"
```

### Slice 2：行情页总览与交易数据

目标：

- 总览 tab、榜单卡片、行情表格和移动列表贴近原型。
- 只改展示和文案，不新增业务接口。

文件：

- `apps/web/src/pages/markets/MarketsPage.tsx`
- `apps/web/src/styles.css`
- `apps/web/src/pages/userPages.test.ts`

验证：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:user-core-pages"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:visual-qa"
```

### Slice 3：注册/登录页

目标：

- `/register` 更接近原型的单列表单体验。
- 保留当前注册流程和 backend/local fallback。

文件：

- `apps/web/src/pages/login/AuthSupportPage.tsx`
- `apps/web/src/pages/login/LoginPage.tsx`
- `apps/web/src/pages/login/LoginPage.module.css`

验证：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:user-core-pages"
```

### Slice 4：个人中心总览

目标：

- `/account/overview` 对齐原型的账户总览：侧栏、身份摘要、资产、KYC、快捷操作、最近记录。
- 移动端保持底部导航可用，无内容遮挡。

文件：

- `apps/web/src/pages/account/AccountPages.tsx`
- `apps/web/src/pages/account/AccountHubPage.tsx`
- `apps/web/src/styles.css`

验证：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:user-core-pages"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:visual-qa"
```

## 验收标准

- 所有目标页面没有 mojibake 可见文案。
- 桌面端无横向滚动，主内容宽度稳定。
- 移动端底部导航不遮挡最后一个按钮、卡片或表格行。
- 页面仍使用现有 React 组件、路由和服务层，不引入导出站点的压缩脚本。
- `web:test`、`web:build` 通过。
- 涉及跨页面视觉时，`smoke:user-core-pages` 和 `smoke:visual-qa` 通过，并保留截图/报告作为证据。

## 下一步建议

先执行 Slice 0。当前中文乱码会直接破坏首页、导航、行情、注册和个人中心的可读性；如果跳过它，后面的布局和样式调整无法被可靠验收。
