# Binance WACZ UI / Frontend Analysis

分析对象：`C:\Users\User\Downloads\binance.wacz`  
分析时间：2026-06-14  
分析方式：只读解析 `WACZ` / `WARC` / `CDX` / `pages.jsonl`，未执行归档内 JavaScript，未回放登录态请求。  
敏感信息处理：报告不包含 cookie、token、邮箱、UID、余额等具体值；只保留 UI、布局、资源与组件层面的统计和结论。

## 1. 归档概览

| 项目 | 结果 |
|---|---:|
| 文件大小 | `45,178,403 bytes` |
| 文件 SHA-256 | `52DB24F04475D97101A599022F00E736B45C0E156532AB0219AAC1EEC9CD6A1F` |
| WACZ 版本 | `1.1.1` |
| 生成工具 | `Webrecorder ArchiveWeb.page 0.16.2`, `warcio.js 2.4.10` |
| 归档创建时间 | `2026-06-14T02:20:37.765Z` |
| 归档修改时间 | `2026-06-14T02:25:37.809Z` |
| 抓取时间范围 | `20260614022048050` 到 `20260614022537807` |
| 页面清单 URL 记录 | 23 条 |
| WARC 记录 | 7,843 条 |
| CDX URL 记录 | 3,921 条 |

WACZ 内部结构：

```text
pages/pages.jsonl
archive/data.warc.gz
indexes/index.cdx.gz
indexes/index.idx
datapackage.json
datapackage-digest.json
```

`datapackage.json` 中声明的 `pages.jsonl`、`data.warc.gz`、`index.cdx.gz`、`index.idx` 哈希均已校验通过。

## 2. 前端资源覆盖范围

从 HTTP `200` 响应中识别到的资源量：

| 类型 | 记录数 | 解码后体积 |
|---|---:|---:|
| HTML | 46 条响应，去重后约 22 个非空页面 |
| CSS | 290 条 | 约 6.10 MB |
| JavaScript | 1,300 条 | 约 68.78 MB |
| JSON | 1,755 条 | 约 39.12 MB |
| Image | 403 条 | 约 1.56 MB |

这说明该文件不仅包含页面快照，还包含大量前端运行时代码、样式、静态资源和接口 JSON。它可以分析“当时访问过的页面”的 UI、布局、组件和前端资源结构，但不能等同于 Binance 完整源码仓库。

## 3. 覆盖的页面与信息架构

归档覆盖的页面主要分为四类：

### 3.1 公共首页与行情

| URL | 页面标题 / 主要内容 |
|---|---|
| `https://www.binance.com/zh-CN` | 首页，标题为“币安：全球最值得信赖的加密货币买卖和投资平台” |
| `https://www.binance.com/zh-CN/markets/overview` | 加密货币市场总览 |
| `https://www.binance.com/zh-CN/markets/ai-select` | AI 精选，包含技术指标、情绪类 tab |
| `https://www.binance.com/zh-CN/markets/trading_data/rankings` | 交易数据 / 排行榜 |
| `https://www.binance.com/zh-CN/markets/trading_data/futures/perpetual/trading-data` | U 本位 / 永续合约交易数据 |
| `https://www.binance.com/zh-CN/markets/trading_data/futures/quarterly/trading-data` | 币本位 / 季度合约交易数据 |

### 3.2 登录后账户中心

| URL | 页面类型 |
|---|---|
| `/zh-CN/my/dashboard` | 用户总览 |
| `/zh-CN/my/settings/preference` | 设置 / 个人资料偏好 |
| `/zh-CN/my/security` | 账户安全 |
| `/zh-CN/my/settings/kyc` | 身份认证 |
| `/zh-CN/my/settings/api-management` | API 管理 |
| `/zh-CN/my/payment/c2c` | C2C 支付方式 |
| `/zh-CN/my/financial-reports` | 财务报告 |

### 3.3 钱包与资产

| URL | 页面类型 |
|---|---|
| `/zh-CN/my/wallet/account/overview` | 钱包总览 |
| `/zh-CN/my/wallet/account/main` | 现货账户 |
| `/zh-CN/my/wallet/account/margin` | 杠杆账户 |
| `/zh-CN/my/wallet/account/statement` | 账户结单 |
| `/zh-CN/my/wallet/history/overview` | 资金流水 / 划转历史 |

### 3.4 订单与 P2P

| URL | 页面类型 |
|---|---|
| `/zh-CN/my/orders/exchange` | 现货订单 |
| `/zh-CN/my/orders/p2p` | C2C 订单 |
| `https://p2p.binance.com/zh-CN/userCenter` | P2P 用户中心 |

另有 `https://accounts.binance.com/zh-CN/login/switch/callback`，它是登录切换回调页，不是主要 UI 页面。

## 4. 应用壳与布局骨架

HTML 中反复出现以下根节点：

```text
__APP_TOP_PORTAL
__APP_HEADER
__APP_SIDEBAR
__APP
__APP_FOOTER
__APP_DATA
__APP_EXTENSION
```

据此可分出三种页面壳：

### 4.1 公共页面壳

适用于首页、市场、P2P 用户中心等页面。

结构大致为：

```text
Top portal
Header / top navigation
Main app content
Footer
App data bootstrap
Extension mount
```

公共导航中可见的高频栏目包括：

```text
一键买币
行情
交易
合约
理财
广场
更多
充值
```

页脚是大型站点地图式结构，标题包括：

```text
社区
关于我们
产品
商业
学习
服务
帮助
```

### 4.2 登录后账户页面壳

适用于 `/my/...` 下的账户、钱包、订单、设置、KYC、API 管理等页面。

结构大致为：

```text
Top portal
Header
Sidebar
Main app content
Footer
App data bootstrap
Extension mount
```

账户侧边栏高频导航项包括：

```text
总览
资产
钱包总览
现货账户
杠杆账户
第三方钱包
订单
资金流水
现货订单
C2C订单
奖励中心
邀请奖励
账户
身份认证
账户安全
支付方式
API管理
账户结单
财务报告
子账户
设置
```

这个壳的视觉特征是：顶部全站导航保持一致，左侧账户导航承担二级信息架构，主内容区承载具体业务卡片、表格、筛选器和操作按钮。

### 4.3 合约交易数据页面壳

合约交易数据页面根节点中没有反复出现 `__APP_HEADER` / `__APP_SIDEBAR` / `__APP_FOOTER`，而是以 `__APP` 为核心承载自身页面框架。页面文案显示它有独立的合约导航：

```text
合约
期权
交易机器人
跟单交易
聪明钱
活动
数据
更多
总览
交易数据
AI 精选
代币解锁
排行榜
```

这类页面更偏交易数据工具页，内部包含交易对切换、周期切换、视图模式和“去合约交易”CTA。

## 5. 页面级 UI 结构

### 5.1 首页

主要结构：

```text
Header
Announcement / news strip
Hero / identity verification prompt
Asset summary card
Market tabs: 热门 / 新币
App download section
FAQ sections
Footer sitemap
```

可见 heading：

```text
完成身份认证，开启加密货币之旅
随时随地，开启交易。
常见问题
为什么币安是加密货币交易者的最佳交易平台？
币安提供哪些产品？
如何在币安购买比特币和其他加密货币
如何追踪加密货币价格
如何在币安进行加密货币交易
如何在币安赚取加密货币收益
```

组件表现：

- 首页不是纯营销页，而是登录态下的资产入口与 KYC 入口混合页。
- 主 CTA 偏“身份认证 / 立即验证”，不是单纯注册。
- 市场模块使用 tabs 区分“热门 / 新币”。
- 下载模块包含桌面版、简易版、专业版等 tab。

### 5.2 市场总览

主要结构：

```text
Header
Market overview title
Category / trend sections
Market table
Footer
```

CSS 中出现专门的 `.markets-overview-table`，其布局规则表明：

- 桌面端表格宽度约 `1232px`。
- 移动端表格宽度为 `100%`。
- 行使用 `display:flex`，而不是传统 table row 布局。
- `tbody tr` 设置为 `cursor:pointer`，整行可点击进入交易/详情。
- 单元格高度约 `64px`，表头约 `40px`。

### 5.3 AI 精选

主要结构：

```text
Header
Markets subnav
Disclaimer
Tabs: 技术指标 / 情绪
Filter / reset action
Content cards or lists
Footer
```

组件表现：

- 有明确免责声明，说明内容由 AI 生成且不构成建议。
- tab 数量少，采用二分内容切换。
- 有“重置”按钮，说明页面存在筛选状态。

### 5.4 合约交易数据

主要结构：

```text
Futures product nav
Trading data nav
Contract selector: BTCUSDT / ETHUSDT / ETHUSD 等
View switch: List View / Card View
Period tabs: 5分 / 15分 / 30分 / 1时 / 2时 / 4时 / 6时 / 12时 / 1天
Data widgets / charts / tables
CTA: 去合约交易
```

组件表现：

- 数据页有强工具属性，信息密度高。
- 周期切换是高频交互控件。
- JS 中检测到 `Highcharts`、`chart`、`canvas`、`WebSocket` 等特征，说明行情/合约数据页很可能包含图表和实时数据模块。

### 5.5 钱包总览 / 现货账户

主要结构：

```text
Header
Sidebar
Page title
Asset summary
Action buttons: 提现 / 划转 / 历史记录
Asset view switch
Small balance toggle
Asset table
Footer
```

可见表格字段：

```text
资产
数量
资产价格
操作
```

组件表现：

- 资产页是典型后台式布局：左侧导航 + 右侧数据表。
- 操作按钮短而强，集中在资产卡片附近。
- 有“隐藏资产 < 1 USD”这类密度控制选项。
- 表格和筛选状态依赖大量 JSON 数据，离线回放只能还原当时收录状态。

### 5.6 资金流水 / 账户结单

主要结构：

```text
Header
Sidebar
Filter controls
Category tabs or filters
Date / asset / type inputs
Actions: 搜索 / 重置 / 导出
Result table or empty state
Footer
```

组件表现：

- `账户结单` 页面出现“导出 / 搜索 / 重置”按钮。
- `资金流水` 页面出现“重置”按钮和空状态“暂无记录”。
- CSS 中有导出弹窗相关样式，例如 `.export-history-modal-wrap`、`.history-export-download-table`，说明导出流程使用 modal + table。

### 5.7 账户安全

主要结构：

```text
Header
Sidebar
Security settings list
Status rows
Action buttons: 绑定 / 管理 / 开启
Footer
```

组件表现：

- 安全项是行列表结构，每行右侧提供短动作按钮。
- 主要动词包括“绑定”“管理”“开启”，对应配置型页面常见操作。
- 很多操作可能会触发 modal、drawer 或二次验证流程，但归档不保证覆盖所有弹出状态。

### 5.8 API 管理

主要结构：

```text
Header
Sidebar
API 管理说明
Primary action: 创建 API
Destructive action: 删除所有API
规则说明
Footer
```

可见说明包括：

```text
每个账户最多可以创建 30 个 API Key。
请勿将您的API密钥、HMAC密钥、Ed25519或RSA私钥透露给任何人，以免造成资产损失。
```

组件表现：

- 页面用一个主按钮承载创建动作。
- 删除所有 API 是高风险动作，理论上应配合确认 modal。
- 文案强调密钥安全，属于安全敏感业务页面。

### 5.9 C2C / P2P

`/my/orders/p2p` 主要 tabs：

```text
处理中
全部订单
全部
待支付
已支付
申诉
已完成
已取消
```

`p2p.binance.com/zh-CN/userCenter` 主要 tabs：

```text
C2C收款方式
评价 (0)
拉黑表
关注
取消限制中心
通知设置
```

主要按钮：

```text
成为广告方
添加收款方式
```

组件表现：

- P2P 用户中心是多 tab 个人中心结构。
- 订单页使用状态分组 tabs，符合交易订单管理场景。
- 个人中心显示交易统计，如 30 日成单数、成单率、平均放行、平均付款、好评率等。

## 6. CSS 架构与设计系统

### 6.1 样式来源

高频 CSS 来源：

```text
https://public.bnbstatic.com/unpkg/common-widget/*.css
https://bin.bnbstatic.com/static/css/*.chunk.css
https://bin.bnbstatic.com/static/css/*.css
```

这说明样式由两层组成：

1. 全站公共组件库：`common-widget`
2. 页面/路由级 chunk 样式：`static/css/*.chunk.css`

### 6.2 设计 token

CSS 中存在明确的 design token，尤其是颜色、间距、圆角、阴影。

颜色 token 样例：

| Token | Value |
|---|---|
| `--color-BasicBg` | `#ffffff` |
| `--color-SecondaryBg` | `#fafafa` |
| `--color-CardBg` | `#ffffff` |
| `--color-Line` | `#ededed` |
| `--color-PrimaryText` | `#000000` |
| `--color-SecondaryText` | `#757575` |
| `--color-TertiaryText` | `#9c9c9c` |
| `--color-BtnBg` | `#fcd535` |
| `--color-PrimaryYellow` | `#f0b90b` |
| `--color-TextLink` | `#d89f00` |
| `--color-Sell` | `#f6465d` |
| `--color-Buy` | `#2ebd85` |
| `--color-Error` | `#f6465d` |
| `--color-Success` | `#2ebd85` |
| `--color-Mask` | `rgba(0, 0, 0, 0.6)` |

间距 token：

| Token | Value |
|---|---|
| `--space-5xs` | `2px` |
| `--space-4xs` | `4px` |
| `--space-3xs` | `5px` |
| `--space-2xs` | `8px` |
| `--space-xs` | `10px` |
| `--space-s` | `12px` |
| `--space-mm` | `16px` |
| `--space-m` | `15px` |
| `--space-l` | `20px` |
| `--space-xl` | `24px` |
| `--space-3xl` | `32px` |
| `--space-5xl` | `40px` |

圆角 token：

| Token | Value |
|---|---|
| `--radii-2xs` | `2px` |
| `--radii-xs` | `4px` |
| `--radii-s` | `6px` |
| `--radii-m` | `8px` |
| `--radii-l` | `10px` |
| `--radii-2l` | `12px` |
| `--radii-xl` | `16px` |
| `--radii-2xl` | `30px` |
| `--radii-circle` | `100em` |

阴影 token：

```text
--shadow-shadow1
--shadow-shadow2
--shadow-shadow3
--shadow-shadow4: 0px 8px 16px rgba(24,26,32,.16)
```

### 6.3 色彩风格

高频颜色显示 Binance 前端使用的是金融交易产品常见的浅色、数据密集风格：

```text
白色 / 浅灰: #fff, #fafafa, #eaecef, #ededed
品牌黄: #f0b90b, #fcd535
正文深色: #000000, #1e2329, #181a20, #202630
辅助文字: #757575, #848e9c, #9ca3af
买入 / 成功: #2ebd85, #0ecb81
卖出 / 错误: #f6465d, #f23051
链接蓝: #1f8df9
```

样式不是单色主题，而是“品牌黄 + 黑白灰底色 + 交易红绿 + 少量蓝色链接”的金融平台配色。

### 6.4 字体系统

高频 `font-family`：

```text
DINPro
IBMPlexSans
-apple-system
PingFang SC
Microsoft YaHei
WenQuanYi Micro Hei
Fira Sans
Droid Sans
Helvetica Neue
Arial
Segoe UI
BinancePlex
bnc-icon
```

字体特征：

- 金融数据类页面大量使用 `DINPro` / `IBMPlexSans`。
- 中文 fallback 覆盖 `PingFang SC`、`Microsoft YaHei`、`WenQuanYi Micro Hei`。
- 图标字体使用 `bnc-icon`。
- 数字、价格、比例类信息倾向于更紧凑、可读的数字字体。

常见字号：

| 字号 | 频次特征 |
|---|---|
| `12px` | 标签、辅助文本、表格小字 |
| `14px` | 高频正文 / 表格正文 |
| `16px` | 按钮、正文、二级标题 |
| `20px` | 页面区块标题 / tab 字体 |
| `24px` | 标题 |
| `32px+` | 首页/营销或重点数据 |

高频行高：

```text
18px
20px
22px
24px
28px
32px
```

整体是高密度业务 UI，而不是大留白营销式排版。

### 6.5 响应式断点

CSS 中高频 media query：

```text
only screen and (min-width:768px)
max-width:767px
min-width:767px
min-width:1024px
min-width:1280px
min-width:1440px
min-width:1536px
min-width:1920px
```

可以推断其响应式体系核心为：

```text
< 768px: mobile
768px+: tablet / desktop entry
1024px+: desktop nav/layout
1280px+ / 1440px+ / 1536px+ / 1920px+: wide desktop optimization
```

HTML 中也出现 `rwd-1024-hide-flex`、`rwd-768-show`、`rwd-1200-visi-flex` 等工具类，说明导航和布局在断点处会切换显示。

## 7. 布局技术特征

CSS 统计：

| 特征 | 频次 |
|---|---:|
| `display:flex` | 1,730 |
| `display:grid` | 62 |
| `position:fixed` | 114 |
| `position:sticky` | 93 |
| `overflow-y:auto` | 177 |
| `overflow-x:auto` | 12 |
| `box-shadow` | 628 |
| `border-radius` | 3,067 |
| `transition` | 1,333 |
| `transform` | 2,314 |
| `z-index` | 890 |
| `max-width` | 1,125 |
| `min-width` | 4,892 |
| `grid-template-columns` | 158 |
| `flex-direction:column` | 660 |
| `gap:` | 4,355 |

结论：

- 主布局以 `flex` 为核心，`grid` 用于局部表格/卡片排列。
- 大量 `min-width` 和 `overflow` 说明桌面数据表、弹窗、选择器需要保持最小可读宽度。
- `position:fixed` / `sticky` 高频，常用于 header、drawer、modal、tooltip、sticky 表头/导航。
- `transition` 和 `transform` 高频，组件库具有较完整的交互动效。
- `border-radius` 高频且 token 化，组件圆角统一。

## 8. 组件系统

CSS class 显示前端有一套明显的 Binance UI 组件库，命名以 `bn-*` 为核心。

### 8.1 组件类族

| 类族 | 观察 |
|---|---|
| `bn-button` | 按钮系统，支持 primary、secondary、text、buy、sell、icon、不同 size |
| `bn-tab` | tab / tablist / pane / badge / arrow |
| `bn-web-table` / `bn-table` | Web 数据表格 |
| `bn-modal` | modal、confirm、header、footer、actions |
| `bn-drawer` | drawer / bottom sheet / overlay |
| `bn-tooltips` / `bn-tooltip` | tooltip、safe triangle、wrap、web variant |
| `bn-skeleton` | skeleton loading，支持 image、avatar、paragraph、title |
| `bn-textField` | 输入框 |
| `bn-select-field` | 下拉选择 |
| `bn-bubble` | 浮层 / popover |
| `bn-svg` | SVG 图标 |

### 8.2 Button

按钮通过 CSS 变量控制背景和文字：

```text
--button-bg
--button-color
```

按钮语义：

| 类型 | 表现 |
|---|---|
| secondary | 背景使用 `--color-Line`，文字使用 `--color-PrimaryText` |
| text | 无背景，常用于弱动作 / 链接动作 |
| buy | 背景 `--color-Buy`，active/hover 使用 `--color-BuyHover` |
| sell | 背景 `--color-Sell`，active/hover 使用 `--color-SellHover` |
| icon | 透明背景，边框，固定宽度，圆角 |

尺寸示例：

```text
large: height 48px, min-width 80px, font-size 16px, border-radius var(--radii-m)
icon large: width 48px, min-width 48px, border-radius var(--radii-xl)
```

组件表现：

- CTA 按钮和交易按钮有清晰语义色。
- icon-only button 有固定尺寸，利于触控命中。
- disabled / inactive 状态使用 `--color-DisableText` 和透明背景。

### 8.3 Tabs

tab 组件支持多种形态：

```text
bn-tab-list__default
bn-tab-list__default-breakline
bn-tab-list__primary
bn-tab-list__primary-gray
bn-tab__segment
bn-tab__segment-outline
```

典型表现：

- default large gap 使用 `--space-5xl`。
- primary active 使用品牌黄文字和 `--color-BadgeBg` 背景。
- primary gray active 使用正文色和 input 背景。
- pane 通过 `.bn-tab-pane:not(.active){display:none}` 控制展示。
- 横向溢出时有左右 arrow。

适用页面：

- 首页市场 tabs
- P2P 订单状态 tabs
- P2P 用户中心 tabs
- 合约周期 tabs
- AI 精选技术指标/情绪 tabs

### 8.4 Table

表格组件分为通用 `bn-web-table` 和业务专用表格。

市场表格 `.markets-overview-table`：

```text
table-layout: fixed
border-collapse: collapse
desktop width: 1232px
mobile width: 100%
row display: flex
row cursor: pointer
th height: 40px
td height: 64px
```

账户/历史表格：

- 使用 `bn-web-table-wrapper__line`、`bn-web-table-cell`。
- 导出历史弹窗中表格有边框和圆角。
- 长表格区域可能使用 `overflow-x:auto` 或隐藏 scrollbar。

### 8.5 Modal / Drawer / Tooltip

Modal：

```text
bn-modal
bn-modal-wrap
bn-modal-content
bn-modal-header
bn-modal-footer
bn-modal-confirm-title
bn-modal-confirm-desc
bn-modal-confirm-actions
```

典型 modal 宽度：

```text
width: 520px
max-width: 90vw
```

Drawer：

```text
bn-drawer
bn-drawer-wrap
bn-drawer-handle
bn-drawer-handle-icon
```

遮罩：

```text
rgba(0, 0, 0, 0.6)
```

Tooltip：

```text
bn-tooltips
bn-tooltips-wrap
bn-tooltips-ele
bn-tooltips-safety-triangle
```

组件表现：

- 弹层系统比较完整，覆盖 confirm、drawer、popover、tooltip。
- 有 safe triangle，说明下拉/tooltip 有鼠标移动容错区。
- 弹窗宽度对移动端做了 `90vw` 限制。

### 8.6 Skeleton Loading

`bn-skeleton` 类族非常多：

```text
bn-skeleton
bn-skeleton-active
bn-skeleton-image
bn-skeleton-avatar
bn-skeleton-title
bn-skeleton-paragraph
bn-skeleton-paragraph-row
```

这说明页面采用 skeleton 而不是单纯 spinner，用于降低加载等待感。钱包、市场、账户信息等依赖接口数据的区域很可能先显示骨架屏。

## 9. JavaScript 架构

### 9.1 资源来源

高频脚本来源：

```text
https://bin.bnbstatic.com/static/webpack-runtime.*.js
https://bin.bnbstatic.com/static/common/framework.*.js
https://bin.bnbstatic.com/static/main.*.js
https://bin.bnbstatic.com/static/chunks/layout-*.js
https://bin.bnbstatic.com/static/chunks/page-*.js
https://public.bnbstatic.com/unpkg/common-widget/*.min.js
https://public.bnbstatic.com/unpkg/themis/themis@0.0.39.js
https://bin.bnbstatic.com/static/runtime/sentry/7.38.0/bundle.es5.min.js
```

### 9.2 应用框架线索

HTML 和 JS 中的关键线索：

```text
data-shuvi-head
__APP_DATA
shuvi
webpackChunk
__webpack_require__
React.createElement
hydrateRoot
createRoot
redux
i18next
```

推断：

- 前端是 React 体系。
- 构建/路由框架不是简单静态页面，HTML 中有 `__APP_DATA` 注水数据和 `data-shuvi-head`，说明使用了 Shuvi 或 Binance 内部基于 Shuvi 的 SSR/CSR 框架。
- 资源按 runtime、common、layout、page chunk 拆分。
- 多个页面共享 `common-widget`，但每个业务页面还有自己的 page chunk。

### 9.3 JS 功能域

检测到的关键技术词频：

| 词 | 频次 |
|---|---:|
| `webpackChunk` | 746 |
| `React.createElement` | 1,756 |
| `jsx` | 12,723 |
| `hydrateRoot` | 55 |
| `createRoot` | 78 |
| `shuvi` | 1,239 |
| `redux` | 540 |
| `i18next` | 862 |
| `sentry` | 356 |
| `themis` | 1,290 |
| `WebSocket` | 837 |
| `Highcharts` | 723 |
| `TradingView` | 20 |
| `canvas` | 612 |

功能含义：

- `WebSocket`：行情、消息、账户状态或数据实时刷新。
- `Highcharts` / `chart` / `canvas`：市场和合约数据图表。
- `redux`：部分业务页面使用集中式状态管理。
- `i18next`：国际化资源加载。
- `sentry`：错误监控。
- `themis`：风控/验证/授权相关前端模块。
- `common-widget`：共享 header、footer、sidebar、data、extension 组件。

### 9.4 `__APP_DATA`

每个主要 HTML 页面都有 `__APP_DATA`，常见结构：

```text
dynamicIds
ssr
appState
pageData
basename
runtimeConfig
filesByRoutId
publicPath
```

`pageData` 常见键：

```text
shuviInitialState
redux
i18nResource
i18nNamespaces
ssrData
```

`runtimeConfig` 只在本报告中记录配置键，不记录具体值。它包含 API host、静态资源 host、WebSocket host、Sentry、GA/GTM、Themis、i18n 等配置项。

## 10. 交互与状态表现

从 HTML、CSS、JS 和页面文案可观察到的交互模式：

| 模式 | 证据 / 表现 |
|---|---|
| 顶部 dropdown | header 中有语言/币种/下载/更多等入口 |
| 左侧 sidebar | `/my/...` 页面均有账户侧边栏 |
| tabs | 市场、P2P、合约周期、下载模式等 |
| table row click | 市场表格 `tbody tr` 设置 `cursor:pointer` |
| skeleton loading | 大量 `bn-skeleton-*` |
| modal confirm | `bn-modal-confirm-*` |
| drawer / sheet | `bn-drawer-*` |
| tooltip / popover | `bn-tooltips-*`, `bn-bubble` |
| export workflow | 账户结单/历史导出弹窗样式 |
| filter reset | 多个页面有“重置”按钮 |
| real-time update | `WebSocket` 和行情数据相关脚本 |

## 11. 可访问性线索

HTML 中存在以下 ARIA / role：

| 属性 / role | 观察 |
|---|---|
| `role="tab"` | 70 次 |
| `role="tabpanel"` | 64 次 |
| `role="link"` | 51 次 |
| `role="combobox"` | 16 次 |
| `role="table"` | 3 次 |
| `role="checkbox"` | 3 次 |
| `role="alert"` | 2 次 |
| `aria-controls` | 86 次 |
| `aria-selected` | 70 次 |
| `aria-hidden` | 66 次 |
| `aria-label` | 51 次 |
| `aria-expanded` | 16 次 |
| `aria-live` | 11 次 |

结论：

- Tab、combobox、alert、table 等组件有基本 ARIA 结构。
- 归档未做实际键盘导航和屏幕阅读器测试，因此不能断言可访问性完全达标。
- 仅从静态结构看，组件库至少有一定的 ARIA 支持。

## 12. UI 风格总结

整体风格可以概括为：

```text
金融交易平台
高信息密度
浅色为主
品牌黄作为主强调
红绿表达交易方向和状态
组件库高度统一
桌面数据表优先
移动端通过 768px 断点重排
登录后页面更像运营/账户后台
```

视觉特点：

- 页面不追求强装饰，强调可扫描、可比较、可操作。
- 大量卡片、表格、tabs、筛选器、弹窗和骨架屏。
- 首页保留营销和引导，但登录态下会把资产/KYC/账户入口前置。
- 钱包、订单、账户安全等页面是典型 dashboard / control panel 型 UI。

## 13. 如果要复刻这类 UI，关键规格

### 13.1 基础布局

```text
Desktop:
  top header: full width, sticky/fixed
  account pages: left sidebar + content area
  content max width: 1024px / 1200px / 1232px / 1280px depending page
  tables may require horizontal scroll or fixed desktop width

Mobile:
  breakpoint: < 768px
  header/nav collapsed
  sidebar likely becomes drawer
  tables adapt to 100% width or card/list form
```

### 13.2 Token

```css
:root {
  --color-PrimaryYellow: #f0b90b;
  --color-BtnBg: #fcd535;
  --color-BasicBg: #ffffff;
  --color-SecondaryBg: #fafafa;
  --color-PrimaryText: #000000;
  --color-SecondaryText: #757575;
  --color-Line: #ededed;
  --color-Buy: #2ebd85;
  --color-Sell: #f6465d;
  --space-2xs: 8px;
  --space-s: 12px;
  --space-mm: 16px;
  --space-xl: 24px;
  --radii-m: 8px;
  --radii-xl: 16px;
}
```

### 13.3 组件优先级

复刻或分析时，应优先关注：

```text
App shell: Header / Sidebar / Footer
Navigation: top nav / sidebar nav / tabs
Data display: table / cards / stat rows
Controls: button / select / textField / date range
Overlays: modal / drawer / tooltip / bubble
Loading: skeleton
Financial states: buy/sell/success/error colors
```

## 14. 限制与注意事项

1. 这份归档只覆盖约 5 分钟内访问过的页面，不包含 Binance 所有页面和所有交互状态。
2. JavaScript 是构建后的 bundle，不是原始源码；可分析资源结构、库线索、chunk 关系，但不能直接还原完整源代码目录。
3. 某些 modal、dropdown、error state、empty state 只有在访问时触发才会被完整收录。
4. 登录态接口 JSON 会影响页面显示；离线 UI 回放可能还原当时状态，但不代表当前线上状态。
5. 归档中包含请求头、cookie、授权头和账户相关 JSON 字段，应按敏感文件处理。
6. 本报告没有执行其中 JS，也没有联网验证外部域名归属，因此不会判断当前生产站点的最新实现。

## 15. 结论

`binance.wacz` 包含了足够多的前端信息来分析已访问页面的 UI、布局、样式、组件和资源架构。它保存了 HTML、CSS、JS、图片、SVG、字体、接口 JSON 和请求/响应记录，因此对“当时浏览器看到的 Binance 中文站登录态体验”有较高还原价值。

核心 UI 结论：

- 前端是 React + Shuvi/SSR 注水 + webpack chunk 架构。
- 页面壳清晰分为公共壳、账户中心壳和合约数据壳。
- 组件库以 `bn-*` 为核心，覆盖 button、tab、table、modal、drawer、tooltip、skeleton、input、select 等。
- 视觉系统有明确 token：品牌黄、黑白灰背景、交易红绿、8px 附近间距体系、2px 到 30px 圆角体系。
- 布局以 flex 为主，数据密集页面通过表格、tabs、筛选器和弹窗承载复杂业务。
- 归档可用于 UI 逆向分析、样式规范总结和离线页面回放，但不能代表完整源码或完整产品状态。
