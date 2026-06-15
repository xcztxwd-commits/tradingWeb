# 前端页面功能分类与原型设计总文档

创建时间：2026-06-14 05:58:59 +08:00  
适用仓库：`C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform`  
当前阶段：已记录 PC 端首页、行情页面、交易入口与交易页面分类、个人中心；后续每个页面描述完成后，继续追加到本文档。

## 文档目的

本文档用于防止长上下文对话中需求遗失，作为后续 Codex 执行页面设计、前后端开发、验证与交接的单一事实源。

本文档记录：

- 用户逐步描述的页面功能分类。
- 所有参考图片和对应设计意图。
- UI 设计方向、交互规则、状态分支。
- 前端组件拆分、后端接口、数据库与实时数据要求。
- 可交给 Codex 执行的修改流程和验收标准。

后续规则：

1. 用户每描述完一个页面，就在 `页面需求追加记录` 下新增一节。
2. 每完成一个开发步骤，就在 `实施记录` 下新增一条记录，写明完成内容、涉及文件、验证命令、残留风险。
3. 不删除历史需求；如果需求变化，用“变更记录”追加说明。
4. 不把未描述的页面提前设计成确定方案，只保留占位。

## 全局信息架构

顶部栏目标分类：

- 首页
- 行情
- 交易
- 钱包
- 消息
- 公告
- 客服中心
- 语言切换

登录态显示规则：

| 状态 | 顶部栏显示 |
| --- | --- |
| 未登录 | 首页、行情、交易、登录、注册、语言切换、风格/主题按钮 |
| 已登录 | 首页、行情、交易、个人中心、钱包、消息、公告、客服中心、语言切换、风格/主题按钮 |

未登录状态不显示：

- 钱包
- 消息
- 公告
- 客服中心

## 全局 UI 方向

当前确定风格：黑金风格，后续页面沿用。

设计关键词：

- 黑色背景、金色强调。
- 极简但有质感。
- 非专业交易风格，避免过重的交易终端感。
- 大众用户能理解，重点突出注册、验证、交易、充值等主路径。
- 动效克制，主要用于数字增长、按钮反馈、面板浮动，不做复杂粒子和强装饰。

建议设计 token：

```text
background: #111318
surface: #242b38
surfaceSoft: #1a1f2a
accent: #f0b90b
accentStrong: #f5c518
textPrimary: #f6f7fb
textSecondary: #aab3c5
textMuted: #7f889a
success: #24c789
danger: #f05252
border: rgba(255, 255, 255, 0.10)
panelRadius: 18px
buttonRadius: 8px
```

动效规则：

- 数字变化时执行轻微弹跳：`scale(1.04)` 后回落。
- 面板 hover 只允许轻微 `translateY(-2px)`、边框提亮或阴影增强。
- 页面进入使用 `opacity + translateY(8px)`。
- 必须支持 `prefers-reduced-motion: reduce`，关闭非必要动画。
- 不使用大面积渐变球、粒子背景、夸张 glow、复杂 3D 装饰。

## 参考图片资产

所有图片已从临时目录复制到仓库，后续以仓库内文件为准。

### 首页未登录参考图

![首页未登录参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/home-pc-black-gold/home-guest-reference.png)

原始来源：

```text
C:\Users\User\AppData\Local\Temp\codex-clipboard-965f95e6-3a22-4b9b-876d-1484f748ced7.png
```

仓库资产：

```text
fx-trading-platform/docs/prototypes/screenshots/home-pc-black-gold/home-guest-reference.png
```

设计要点：

- 首页左右两栏。
- 左侧为品牌/用户数宣传和注册入口。
- 大数字突出“xxx 用户的共同选择”。
- 数字需要后端持久化记录，并每秒随机增长 1 到 3。
- 前端实时刷新数字，并带弹跳动态特效。
- 右侧为热门交易品种和新闻。
- 底部展示荣誉背书。
- 未登录右上角仅出现登录、注册、语言、风格按钮等，不显示个人中心、钱包、消息、公告、客服中心。

### 登录/注册入口参考图

![登录注册入口参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/home-pc-black-gold/auth-entry-reference.png)

原始来源：

```text
C:\Users\User\AppData\Local\Temp\codex-clipboard-81fc4794-5c9f-4a8f-ae06-a2862c2a9489.png
```

仓库资产：

```text
fx-trading-platform/docs/prototypes/screenshots/home-pc-black-gold/auth-entry-reference.png
```

设计要点：

- 点击登录或注册后进入该风格页面。
- 页面左右两栏。
- 左侧展示注册奖励、用户数量宣传、安全宣传。
- 用户数量同样来自后端持久化 counter，并每秒跳动增长。
- 右侧为注册/登录表单。
- 表单可选择邮箱注册或手机号注册。
- 手机号注册必须支持国际区号选择。
- 如果账号已存在，点击继续后在当前页面增加密码输入框。
- 如果账号不存在，跳转到邮箱验证码或手机号验证码步骤。
- 表单步骤变化不跳转页面，只改变当前表单内容。

### 首页已登录未验证参考图

![首页已登录未验证参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/home-pc-black-gold/home-unverified-reference.png)

原始来源：

```text
C:\Users\User\AppData\Local\Temp\codex-clipboard-a8e4c58b-bd32-4e93-9108-54911c2b37c0.png
```

仓库资产：

```text
fx-trading-platform/docs/prototypes/screenshots/home-pc-black-gold/home-unverified-reference.png
```

设计要点：

- 已登录后右上角登录/注册消失。
- 右上角增加个人中心、钱包、消息、公告、客服中心等入口。
- 未验证身份时首页主文案变成“完成身份认证，开启加密货币之旅”。
- 左侧展示资产概览，资产可以为 `0.00 BTC ≈ $0.00`。
- 主按钮为“立即验证”，点击跳转个人中心。
- 保留右侧热门交易品种和新闻区块。

### 行情总览参考图

![行情总览参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/markets-black-gold/markets-overview-table-reference.png)

原始来源：

```text
C:\Users\User\AppData\Local\Temp\codex-clipboard-ae836724-4fca-4c13-9d75-cd677f7dd9ea.png
```

仓库资产：

```text
fx-trading-platform/docs/prototypes/screenshots/markets-black-gold/markets-overview-table-reference.png
```

设计要点：

- 行情页整体参考 Binance `https://www.binance.com/zh-CN/markets/overview` 的信息结构，但不能复制品牌资产或依赖 Binance 页面 DOM。
- 页面一级内容分为“总览”和“交易数据”。
- 总览包含顶部榜单摘要、分类导航、行情表格。
- 行情表格每条数据要包含名称、价格、24h 涨跌、24h 成交量、市值和操作。
- 点击任意一行默认进入交易页面。
- 操作列第一版只保留“交易”按钮。

### 行情交易数据参考图

![行情交易数据参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/markets-black-gold/markets-trading-data-reference.png)

原始来源：

```text
C:\Users\User\AppData\Local\Temp\codex-clipboard-0db5e72f-4696-4328-ab9f-75b25946d841.png
```

仓库资产：

```text
fx-trading-platform/docs/prototypes/screenshots/markets-black-gold/markets-trading-data-reference.png
```

设计要点：

- 交易数据页展示排行榜卡片。
- 内部二级分类包含排行榜、U 本位合约、币本位合约、期权。
- 榜单卡片可展示热门币种、涨幅榜、跌幅榜、成交榜、U 本位合约、币本位合约。
- 榜单行点击进入交易页面。

### 行情自选参考图

![行情自选参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/markets-black-gold/markets-favorites-reference.png)

原始来源：

```text
C:\Users\User\AppData\Local\Temp\codex-clipboard-d240a7e6-941a-4209-8dbb-997bbb275db7.png
```

仓库资产：

```text
fx-trading-platform/docs/prototypes/screenshots/markets-black-gold/markets-favorites-reference.png
```

设计要点：

- 总览下的小分类包含自选。
- 自选可展示已选择品种卡片。
- 可批量勾选后点击“添加自选”。
- 可点击“添加其它币对”打开后续搜索或选择器。

### 行情行 hover 与操作参考图

![行情行 hover 与操作参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/markets-black-gold/markets-row-hover-reference.png)

原始来源：

```text
C:\Users\User\AppData\Local\Temp\codex-clipboard-4e46f3a8-e91d-4406-8f64-77ea1fdb3f48.png
```

仓库资产：

```text
fx-trading-platform/docs/prototypes/screenshots/markets-black-gold/markets-row-hover-reference.png
```

设计要点：

- 行 hover 后背景变为深灰蓝。
- 操作区只保留“交易”。
- 如需要说明，可在 hover 时显示小 tooltip，但不要默认放多个操作入口。
- 交易按钮和行点击进入同一交易页面。

### 交易顶部菜单参考图

![交易顶部菜单参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/trading-black-gold/trading-nav-menu-reference.png)

原始来源：

```text
C:\Users\User\AppData\Local\Temp\codex-clipboard-caa00fb8-c6a6-423d-bd1d-f2ca75ec2503.png
```

仓库资产：

```text
fx-trading-platform/docs/prototypes/screenshots/trading-black-gold/trading-nav-menu-reference.png
```

设计要点：

- 顶部栏 `交易` 在鼠标点击或移入时展示下拉菜单。
- 参考图中的大菜单只作为结构和动效参考，不照搬全部入口。
- 第一版只保留用户指定的三个选项：
  - 虚拟币交易。
  - 外汇交易。
  - 合约。
- 点击任一选项后进入现有 trading 页面。
- 每个分类需要记住用户上一次最后点击的品种。

### 个人中心顶部下拉参考图

![个人中心顶部下拉参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/account-center-black-gold/account-dropdown-reference.png)

设计意图：
- 鼠标 hover、点击或键盘 focus 到顶部个人中心图标时，展开用户菜单。
- 菜单展示头像、脱敏邮箱/手机号、UID、用户等级、认证状态、绑定状态。
- 菜单入口包含总览、资产、订单、账户、设置、退出。
- 退出必须与普通导航用分割线隔开，避免误触。

### 个人中心总览参考图

![个人中心总览参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/account-center-black-gold/account-overview-reference.png)

设计意图：
- 点击个人中心默认进入总览。
- 总览展示用户资料、新手指引、身份认证、充值、交易和资产摘要。
- 侧边栏作为个人中心内部导航，当前项高亮。

### 个人中心资产新版参考图

![个人中心资产新版参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/account-center-black-gold/account-assets-revised-reference.png)

设计意图：
- 资产页面从“仅钱包总览”调整为资产看板。
- 左侧主卡展示总资产估值和资金变动曲线。
- 右侧展示近期资金账单。
- 下方展示资产账户组合。
- 最终 UI 仍使用平台已确定的黑金暗色风格，不沿用参考图的浅色风格。

### 个人中心资金流水参考图

![个人中心资金流水参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/account-center-black-gold/account-funding-records-reference.png)

设计意图：
- 订单下的资金流水页面包含总览、充值、提现三个子页。
- 提供类型、时间、资产筛选。
- 无数据时显示空状态。

### 个人中心交易订单参考图

![个人中心交易订单参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/account-center-black-gold/account-trade-orders-reference.png)

设计意图：
- 交易订单包含当前委托、历史委托、历史成交。
- 表格保留交易订单所需字段。
- 当前第一版不扩展 C2C 订单、合约订单等复杂模块。

### 个人中心订单筛选参考图

![个人中心订单筛选参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/account-center-black-gold/account-order-filters-reference.png)

设计意图：
- 交易对筛选支持搜索。
- 方向筛选只需要全部、买、卖。
- 参考图中的第三个筛选控件第一版不实现，避免范围扩大。

### 个人中心身份认证参考图

![个人中心身份认证参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/account-center-black-gold/account-kyc-reference.png)

设计意图：
- 账户页面当前只需要身份认证。
- 未认证显示立即认证入口。
- 已认证后显示认证信息摘要，不再展示未认证引导。

### 个人中心设置参考图

![个人中心设置参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/account-center-black-gold/account-settings-reference.png)

设计意图：
- 设置页面当前只需要个人资料、通知语言和偏好设置。
- 参考图中的 C2C 个人资料不做第一版范围。

### 个人中心偏好设置参考图

![个人中心偏好设置参考图](C:/Users/User/Desktop/workspace/tradingView-KlineChart/fx-trading-platform/docs/prototypes/screenshots/account-center-black-gold/account-preferences-reference.png)

设计意图：
- 偏好设置包含颜色偏好、风格设置、UTC 时区、键盘快捷键和主题背景。
- 保持黑金主风格，偏好项只改变数据展示习惯和主题模式，不破坏整体品牌视觉。

## 页面需求追加记录

### 2026-06-14：首页 PC 端

范围：

- 只设计 PC 端首页。
- 手机端首页暂不设计。
- 首页分三种状态：
  - 未登录状态。
  - 已登录但未身份验证状态。
  - 已登录且已身份验证状态。

#### 未登录首页

内容结构：

- 顶部栏。
- 左侧宣传区。
- 右侧热门交易品种和新闻。
- 底部荣誉背书。

左侧宣传区：

- 文案结构：`xxx 用户的共同选择`。
- `xxx` 为动态数字。
- 动态数字每秒随机增长 1 到 3。
- 增长后的数字必须记录到数据库。
- 前端每秒拿到最新数字并带弹跳动态刷新。
- 下方有注册输入框和注册按钮。

右侧信息区：

- 热门交易品种。
- 新闻。

底部：

- 平台荣誉、媒体背书、行业排名等内容。

未登录顶部栏：

- 显示登录。
- 显示注册。
- 显示语言切换。
- 显示风格/主题按钮。
- 不显示个人中心、钱包、消息、公告、客服中心。

#### 登录/注册入口页

触发：

- 点击首页顶部登录。
- 点击首页顶部注册。
- 点击首页注册输入框后的注册按钮。

页面结构：

- 左侧宣传区。
- 右侧表单区。

左侧：

- 注册奖励最高可达 `100 USD`。
- 用户数量宣传，同步数据库动态数字并每秒跳动。
- 安全宣传。

右侧表单：

- 可选择邮箱注册。
- 可选择手机号注册。
- 手机号注册必须可选择国际区号。
- 第一步输入邮箱或手机号，点击继续。
- 如果账号已存在：
  - 不跳转页面。
  - 当前表单增加密码输入框。
  - 再次点击继续后执行登录。
- 如果账号不存在：
  - 不跳转页面。
  - 当前表单切换为验证码步骤。
  - 根据渠道展示邮箱验证码或手机号验证码。
  - 注册成功后自动登录或写入 token。

#### 已登录但未身份验证首页

登录后默认跳转：

- 登录成功后自动跳转个人中心。
- 个人中心页面后续单独说明。

用户回到首页时：

- 首页变成未验证身份版本。
- 左侧主文案：`完成身份认证，开启加密货币之旅`。
- 展示资产概览。
- 主按钮：`立即验证`。
- 点击 `立即验证` 跳转个人中心。

#### 已登录且已身份验证首页

登录后默认跳转：

- 登录成功后自动跳转个人中心。

用户回到首页时：

- 首页主说明变成：`以保证用户的资金安全为第一要素，专业安全团队保障用户的交易安全`。
- 展示 `当前有 xx 用户正在进行交易`。
- `xx` 来自数据库动态 counter。
- 后端每秒随机增加 1 到 3，并持久化。
- 前端实时刷新并展示弹跳动效。
- 按钮变成：
  - 立即交易。
  - 充值金额。

### 2026-06-14：行情页面（总览与交易数据）

参考地址：

```text
https://www.binance.com/zh-CN/markets/overview
```

范围：

- 本阶段只设计行情页 PC 端。
- 当前确认一级内容为：
  - 总览。
  - 交易数据。
- 暂不实现 `AI 精选`、`代币解锁`，这些可以作为后续扩展，不在第一版 UI 上提前展示未实现入口。

页面定位：

- 行情页是用户从首页进入交易前的“市场发现与筛选页”。
- 目标不是专业 K 线交易终端，而是让非专业用户快速理解市场、筛选品种并进入交易。
- 路由建议使用现有 `/markets`。
- 点击任意行情行或交易按钮，进入 `/trading?symbol=BTCUSDT` 形式的交易页面。

#### 总览

总览分三段：

1. 顶部市场摘要卡片。
2. 分类导航。
3. 行情数据表。

顶部市场摘要卡片：

- `热门`。
- `新币榜`。
- `领涨榜`。
- `成交榜`。
- 每张卡片显示 3 条核心品种。
- 每张卡片右上角有 `更多`。
- 点击卡片内品种进入交易页面。

总览分类导航：

```text
自选 | 虚拟币 | 外汇 | 合约
```

说明：

- `自选` 是用户自定义关注列表。
- `虚拟币` 用于现货/加密货币品种。
- `外汇` 用于外汇品种。
- `合约` 用于合约品种。
- 如后续需要更细分，可在分类下增加轻量二级筛选，例如 `全部 / BNB Chain / Solana / RWA / MEME / AI / DeFi`，但第一版不建议做太多行业标签，避免复杂化。

行情数据表字段：

```text
名称 | 价格 | 24h涨跌 | 24h成交量 | 市值 | 操作
```

每条数据包含：

- 品种 icon。
- symbol，例如 `BTC`。
- 全称，例如 `Bitcoin`。
- 主价格。
- 副价格或法币折算。
- 24h 涨跌。
- 24h 成交量。
- 市值。
- 操作按钮。

操作规则：

- 行整体可点击。
- 点击行默认进入交易页面。
- 操作列第一版只保留 `交易` 按钮。
- `交易` 按钮与行点击进入同一路由。
- `交易` 按钮需要 `stopPropagation`，避免后续扩展时重复触发。
- 暂不做详情、收藏弹窗、K 线预览等额外入口。

排序与搜索：

- 表头支持排序：
  - 价格。
  - 24h 涨跌。
  - 24h 成交量。
  - 市值。
- 搜索框支持 symbol 和名称搜索。
- 正涨幅绿色，负涨幅红色，0 或无变化使用弱文本。

#### 自选

自选是总览下的独立分类。

未登录用户：

- 可使用 `localStorage` 保存自选。
- 不阻塞用户添加或查看。
- 后续登录后可提示同步。

已登录用户：

- 自选同步到后端。
- 刷新或换设备后仍可恢复。

自选 UI：

- 自选为空或编辑模式时，可展示参考图中的选择卡片。
- 卡片包含 symbol、交易对、价格、涨跌、勾选态。
- 用户可批量勾选后点击 `添加自选`。
- 用户可点击 `添加其它币对` 打开搜索或选择器。
- 已自选品种在表格中显示高亮星标或勾选态。

#### 交易数据

交易数据页结构参考图 2。

一级页签仍为：

```text
总览 | 交易数据
```

交易数据内部二级页签：

```text
排行榜 | U本位合约 | 币本位合约 | 期权
```

排行榜默认展示榜单卡片：

- 热门币种。
- 涨幅榜。
- 跌幅榜。
- 成交榜。
- U 本位合约。
- 币本位合约。

榜单卡片字段：

```text
排名 | 名称 | 价格 | 24h涨跌
```

榜单交互：

- 卡片右上角可有品类筛选，例如 `虚拟币 / 外汇 / 合约`。
- 点击榜单行进入交易页面。
- 涨幅榜按 24h 涨幅降序。
- 跌幅榜按 24h 涨幅升序。
- 成交榜按 24h 成交量降序。

#### 行情页 UI 方向

视觉风格继续沿用黑金系统。

布局：

- 页面内容区居中，最大宽度建议 `1200px` 到 `1280px`。
- 顶部保留全局导航。
- 页签区域使用金色短下划线表示 active。
- 摘要卡片保持低高度，避免喧宾夺主。
- 行情表格是页面主体。
- 表格行高度舒适，不要做高密度交易所终端。

动效：

- 页签 active 下划线切换使用 160ms 滑动。
- 行 hover 背景变为深灰蓝，参考图 4。
- hover 时 `交易` 按钮从弱边框变为金色描边或金色实心。
- 实时价格更新时只做轻微数字闪烁，不做强烈跳动。
- 榜单卡片 hover 轻微上浮 `translateY(-2px)`。
- 搜索框 focus 时使用金色边框，背景不大幅变亮。
- 支持 `prefers-reduced-motion`。

#### 行情页前端架构

建议文件结构：

```text
apps/web/src/pages/markets/
  MarketsPage.tsx
  MarketsPage.module.css
  components/
    MarketsPageTabs.tsx
    MarketOverview.tsx
    MarketSummaryCards.tsx
    MarketCategoryTabs.tsx
    MarketInstrumentTable.tsx
    MarketInstrumentRow.tsx
    FavoritePickerGrid.tsx
    TradingDataDashboard.tsx
    RankingCard.tsx
    MarketSearchBox.tsx
  hooks/
    useMarketInstruments.ts
    useMarketFavorites.ts
    useMarketRankings.ts
```

职责边界：

- `MarketsPage.tsx` 只负责页签状态和页面组合。
- `MarketOverview.tsx` 负责总览布局。
- `TradingDataDashboard.tsx` 负责交易数据布局。
- `MarketInstrumentTable.tsx` 负责表格结构、排序、空状态。
- `MarketInstrumentRow.tsx` 负责单行展示和点击交易。
- `FavoritePickerGrid.tsx` 负责自选编辑。
- `useMarketInstruments.ts` 负责列表请求、搜索、排序和分类筛选。
- `useMarketFavorites.ts` 负责本地/后端自选同步。
- `useMarketRankings.ts` 负责榜单数据。

不要把表格、榜单、自选、搜索全部写进 `MarketsPage.tsx`。

#### 行情页后端接口设计

建议接口：

```text
GET /api/markets/instruments?category=crypto|forex|contract&keyword=&sort=&period=24h
GET /api/markets/rankings?scope=spot|usdt_futures|coin_futures|options
GET /api/markets/favorites
PUT /api/markets/favorites
```

`MarketInstrument` 数据模型：

```ts
type MarketInstrument = {
  symbol: string
  baseAsset: string
  quoteAsset: string
  displayName: string
  category: 'crypto' | 'forex' | 'contract'
  price: string
  fiatPrice?: string
  change24h: string
  volume24h: string
  marketCap?: string
  isFavorite: boolean
}
```

实现原则：

- 不直接抓取 Binance 页面 DOM。
- 不使用 Binance 品牌资产、logo 或完整视觉复制。
- 可复用现有行情、报价、交易 symbol 数据。
- 如果外汇和合约数据暂不完整，可以先通过后端返回明确分类的演示数据，但接口结构必须按真实数据设计。
- 交易页面跳转使用平台内部 symbol，不依赖 Binance symbol 规则。

#### 行情页修改流程

1. 阅读现有文件：
   - `apps/web/src/pages/markets/*`
   - `apps/web/src/app/App.tsx`
   - `apps/web/src/app/navigation.ts`
   - `apps/web/src/services/*market*`
   - `backend/src/main/java/com/fxplatform/market/**`
2. 先增加或更新测试：
   - `/markets` 默认显示 `总览`。
   - 总览包含 `自选 / 虚拟币 / 外汇 / 合约`。
   - 表格字段包含名称、价格、24h涨跌、24h成交量、市值、操作。
   - 行点击会跳转交易页。
   - `交易` 按钮也会跳转交易页。
   - 交易数据页显示榜单卡片。
3. 后端补齐接口和数据模型。
4. 前端实现组件拆分。
5. 接入黑金 UI 和动效。
6. 更新 smoke 脚本覆盖行情页核心结构。

#### 行情页 Codex 执行提示词

```text
请在 C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform 中实现行情页 PC 端第一版。

先阅读 docs/prototypes/frontend-page-prototype-master.md 的“行情页面（总览与交易数据）”章节，并以该章节为唯一需求来源。

本轮只做 /markets 的总览和交易数据，不做 AI 精选、代币解锁，不复制 Binance 品牌资产。

要求：
1. 先读当前 markets 页面、路由、导航、market service 和后端 market 模块。
2. 先补测试，再做最小实现。
3. /markets 默认展示总览。
4. 一级页签为 总览 / 交易数据。
5. 总览包含顶部摘要卡片、分类 自选 / 虚拟币 / 外汇 / 合约、行情表格。
6. 行情表格字段为 名称 / 价格 / 24h涨跌 / 24h成交量 / 市值 / 操作。
7. 每条数据点击整行进入 /trading?symbol=xxx。
8. 操作列只保留交易按钮，点击也进入交易页。
9. 自选支持未登录 localStorage，已登录后端同步的接口边界。
10. 交易数据展示排行榜、U本位合约、币本位合约、期权，并至少实现排行榜卡片。
11. UI 沿用黑金风格，hover、tab、搜索框 focus 和价格刷新动效保持克制。
12. 每完成一个阶段，把记录追加到 docs/prototypes/frontend-page-prototype-master.md 的“实施记录”。
```

#### 行情页验收标准

- `/markets` 默认进入总览。
- 页面展示 `总览` 和 `交易数据` 页签。
- 总览展示 `自选 / 虚拟币 / 外汇 / 合约`。
- 行情表格字段完整。
- 行 hover 样式接近参考图 4。
- 每行和交易按钮都能进入交易页。
- `交易` 是第一版唯一操作按钮。
- 交易数据页展示榜单卡片。
- 未实现功能不出现在 UI 上，避免空入口。
- 不出现横向滚动、内容重叠、控制台错误或框架错误遮罩。

### 2026-06-14：交易入口与交易页面分类

范围：

- 本阶段只设计顶部栏 `交易` 菜单和进入现有 trading 页的分类逻辑。
- 不重做现有 `TradingPage` 页面结构。
- 不新增独立的虚拟币交易页、外汇交易页、合约交易页。
- 三个入口都复用现有 trading 页面。

触发方式：

- 鼠标移动到顶部栏 `交易`。
- 鼠标点击顶部栏 `交易`。
- 键盘 focus 到顶部栏 `交易`。

展开后显示三个选项：

```text
虚拟币交易
外汇交易
合约
```

点击后进入：

```text
/trading?category=crypto&symbol=BTCUSDT
/trading?category=forex&symbol=EURUSD
/trading?category=contract&symbol=ETHUSDT
```

其中 `symbol` 不是固定写死，而是该分类上一次用户最后点击的品种。

#### 分类与默认品种

交易分类：

```ts
type TradingCategory = 'crypto' | 'forex' | 'contract'
```

默认品种：

```ts
const defaultTradingSymbols = {
  crypto: 'BTCUSDT',
  forex: 'EURUSD',
  contract: 'ETHUSDT'
}
```

示例：

- 用户上一次在 `contract` 分类最后点击的是 `ETHUSDT`。
- 用户下次从顶部 `交易 -> 合约` 进入。
- 默认进入 `/trading?category=contract&symbol=ETHUSDT`。

如果用户之前没有记录：

- `虚拟币交易` 默认 `BTCUSDT`。
- `外汇交易` 默认 `EURUSD`。
- `合约` 默认 `ETHUSDT`。

#### 上一次品种记忆规则

按分类独立保存：

```ts
type LastTradingSymbolPreference = {
  crypto: string
  forex: string
  contract: string
}
```

保存触发点：

- 用户从顶部菜单进入某个交易分类。
- 用户在行情页点击某个品种进入交易页。
- 用户在交易页内部切换交易品种。

读取规则：

1. 先读取当前登录用户的后端偏好。
2. 如果没有后端偏好，读取 `localStorage`。
3. 如果都没有，使用分类默认品种。

未登录：

- 使用 `localStorage` 保存。
- key 建议：`fx-platform-last-trading-symbols`。

已登录：

- 优先同步到后端用户偏好。
- 本地仍保留 fallback。

后端偏好接口可以作为后续统一用户偏好能力：

```text
GET /api/user/preferences/trading-symbols
PUT /api/user/preferences/trading-symbols
```

#### 顶部交易菜单 UI

视觉方向：

- 沿用黑金风格。
- 背景为深黑或深灰蓝。
- active/hover 使用金色文字、金色 icon 或金色短线。
- 菜单卡片边框使用低透明白色。
- 不使用强阴影和大面积发光。

菜单内容建议：

```text
虚拟币交易
查看 BTC、ETH、BNB 等主流数字资产

外汇交易
查看 EURUSD、GBPUSD、USDJPY 等外汇品种

合约
进入杠杆/永续合约交易品种
```

动效：

- 展开：`opacity + translateY(6px)`。
- 时长：120ms 到 160ms。
- hover：背景轻微变亮，图标变金色。
- 支持 `prefers-reduced-motion`。

交互细节：

- 鼠标移入 `交易` 或菜单区域时保持展开。
- 鼠标移出菜单区域后延迟约 120ms 关闭，避免误触。
- 点击 `交易`：
  - 如果菜单关闭，则打开菜单。
  - 如果菜单已打开，可再次点击进入最近使用分类；建议默认分类为 `crypto`。
- 键盘：
  - focus 到 `交易` 时展开菜单。
  - `Esc` 关闭菜单。
  - 方向键可切换菜单项。
  - `Enter` 进入当前菜单项。

#### 前端架构建议

相关现有入口：

```text
apps/web/src/app/AppShell.tsx
apps/web/src/app/navigation.ts
apps/web/src/pages/trading/TradingPage.tsx
apps/web/src/pages/trading/*
```

建议新增：

```text
apps/web/src/app/components/
  TradingNavMenu.tsx
  TradingNavMenu.module.css

apps/web/src/app/hooks/
  useLastTradingSymbol.ts
```

职责：

- `TradingNavMenu.tsx`：负责顶部交易菜单展示、hover/click/focus 行为、菜单项跳转。
- `useLastTradingSymbol.ts`：负责读取、写入、合并本地和后端的分类品种偏好。
- `TradingPage.tsx`：只读取 URL 中的 `category` 和 `symbol`，没有 `symbol` 时通过偏好 resolver 得到默认品种。

不要把菜单逻辑直接堆进 `AppShell.tsx`。

#### 交易页参数处理

交易页需要接受：

```text
category=crypto|forex|contract
symbol=BTCUSDT
```

处理逻辑：

1. 如果 URL 有 `category` 和 `symbol`，直接加载该品种，并保存为该分类最近品种。
2. 如果 URL 有 `category` 但没有 `symbol`，读取该分类最近品种。
3. 如果 URL 没有 `category`，默认 `crypto`。
4. 如果最近品种不存在，使用分类默认品种。
5. 如果 symbol 不属于当前 category，优先以 symbol 所属分类为准，或回退到默认品种；具体实现时需要明确测试。

#### 与行情页联动

行情页点击行或 `交易` 按钮时应传入分类：

```text
/trading?category=crypto&symbol=BTCUSDT
/trading?category=forex&symbol=EURUSD
/trading?category=contract&symbol=ETHUSDT
```

进入交易页后同步写入最近品种偏好。

#### 后端设计

第一版可先用本地偏好实现闭环。

如果同轮实现后端同步，建议做通用用户偏好接口：

```text
GET /api/user/preferences
PUT /api/user/preferences
```

或更聚焦：

```text
GET /api/user/preferences/trading-symbols
PUT /api/user/preferences/trading-symbols
```

存储结构建议：

```json
{
  "lastTradingSymbols": {
    "crypto": "BTCUSDT",
    "forex": "EURUSD",
    "contract": "ETHUSDT"
  }
}
```

不要为这一个偏好创建复杂配置系统。

#### 交易入口修改流程

1. 阅读现有：
   - `apps/web/src/app/AppShell.tsx`
   - `apps/web/src/app/navigation.ts`
   - `apps/web/src/pages/trading/TradingPage.tsx`
   - `apps/web/src/pages/trading/TradingPage.test.ts`
   - `apps/web/src/pages/markets/*`
2. 先补测试：
   - 顶部 `交易` 支持菜单。
   - 菜单包含 `虚拟币交易 / 外汇交易 / 合约`。
   - 点击 `合约` 会跳转到最近合约品种。
   - 没有最近品种时使用默认品种。
   - 交易页读取 URL 参数初始化 symbol。
3. 实现 `TradingNavMenu`。
4. 实现 `useLastTradingSymbol`。
5. 让交易页和行情页统一写入最近品种。
6. 更新 smoke，覆盖菜单展开和其中一个菜单项跳转。

#### 交易入口 Codex 执行提示词

```text
请在 C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform 中实现顶部交易菜单和交易分类默认品种逻辑。

先阅读 docs/prototypes/frontend-page-prototype-master.md 的“交易入口与交易页面分类”章节，并以该章节为唯一需求来源。

本轮只做顶部栏交易菜单、分类路由参数、每类最近品种记忆，不重做 TradingPage 页面。

要求：
1. 先读 AppShell、navigation.ts、TradingPage、TradingPage.test、markets 页面，不要直接改。
2. 先补测试，再做最小实现。
3. 顶部栏交易 hover/click/focus 展开菜单。
4. 菜单只包含 虚拟币交易 / 外汇交易 / 合约。
5. 点击菜单项进入 /trading?category=xxx&symbol=yyy。
6. yyy 来自该分类上一次最后点击品种；没有则使用默认品种。
7. 未登录使用 localStorage，已登录预留后端同步接口边界。
8. 交易页读取 category 和 symbol 初始化当前品种。
9. 行情页进入交易页时也写入该分类最近品种。
10. 不复制参考图里 DEX、Alpha、C2C、API 等未要求入口。
11. 每完成一个阶段，把记录追加到 docs/prototypes/frontend-page-prototype-master.md 的“实施记录”。
```

#### 交易入口验收标准

- 顶部栏 `交易` hover/click/focus 可打开菜单。
- 菜单只展示 `虚拟币交易 / 外汇交易 / 合约`。
- 点击每个菜单项都进入现有 trading 页面。
- 每个分类有独立最近品种。
- 最近品种缺失时使用默认品种。
- 从行情页进入交易页后会更新最近品种。
- 页面无横向滚动、菜单不遮挡顶部栏文字、不出现内容重叠。
- 未实现入口不显示。

### 2026-06-14：个人中心

范围：

- 顶部栏个人中心图标的下拉菜单。
- 个人中心默认总览页。
- 资产页，使用新版资产看板结构。
- 订单页，包含资金流水和交易订单。
- 账户页，当前只做身份认证。
- 设置页，当前只做昵称头像、通知语言和偏好设置。
- 继续使用黑金风格，与首页、行情、交易入口保持一致。

#### 顶部个人中心下拉菜单

触发规则：
- 仅已登录状态显示个人中心图标。
- 鼠标 hover、点击图标、键盘 focus 到图标时展开。
- 鼠标离开、点击外部、按 `Escape` 时关闭。
- 下拉层需要有足够 `z-index`，不能被行情、交易图表、表格遮挡。

展示内容：

```text
头像
脱敏邮箱或手机号
UID
普通用户
未认证 / 已认证
绑定状态
总览
资产
订单
账户
设置
退出
```

跳转规则：
- 点击个人中心图标默认进入 `/account/overview`。
- 点击总览进入 `/account/overview`。
- 点击资产进入 `/account/assets`。
- 点击订单进入 `/account/orders/funding`。
- 点击账户进入 `/account/security/kyc`。
- 点击设置进入 `/account/settings`。
- 点击退出需要二次确认或至少使用明确的退出按钮状态，退出后回到首页未登录状态。

UI 规则：
- 菜单宽度参考图 1，保持紧凑但不要拥挤。
- 头像、UID、邮箱/手机号必须垂直对齐。
- `未认证` 使用金色标签，`已认证` 使用绿色或金色完成态标签。
- 退出与普通菜单项之间用分割线隔开。
- 下拉出现动画使用 150-220ms opacity + translateY，关闭更快，支持 `prefers-reduced-motion`。

#### 个人中心整体布局

个人中心使用固定顶部栏 + 左侧侧边栏 + 主内容区。

侧边栏一级结构：

```text
总览
资产
订单
奖励中心
邀请奖励
账户
子账户
设置
```

第一版实现范围：
- `总览`
- `资产 > 钱包总览`
- `订单 > 资金流水`
- `订单 > 交易订单`
- `账户 > 身份认证`
- `设置`

暂不实现但可保留为后续入口的项：
- 奖励中心
- 邀请奖励
- 子账户
- 账户安全
- 支付方式
- API 管理
- 账户结单
- 财务报告
- C2C 订单

如果未实现入口出现在侧边栏，必须使用禁用态或不显示，不能跳空白页。

#### 总览页

路由：`/account/overview`

页面结构参考图 2：
- 顶部用户资料摘要：
  - 头像。
  - 用户名，例如 `User-222ef`。
  - UID。
  - VIP 等级。
  - 已关注、粉丝等轻量信息。
  - 认证状态。
- 新手指引：
  - 身份认证。
  - 充值。
  - 交易。
- 资产摘要：
  - 预估总资产。
  - 计价单位。
  - 今日盈亏。
  - 充值、提现、买币按钮。

状态规则：
- 未认证用户：身份认证卡片为高亮主任务，按钮为 `立即认证`。
- 已认证用户：身份认证卡片变为完成态，下一步重点可转为 `充值`。
- 无资产时显示 `0.00`，不要显示加载失败或空白。

#### 资产页

路由：`/account/assets`

资产页使用新版参考图结构，但视觉必须改成黑金暗色风格。

页面布局：
- 左侧主卡：总资产估值 + 资金变动曲线。
- 右侧卡：近期资金账单。
- 下方区域：资产账户组合。

总资产估值卡字段：

```text
总资产估值
显示/隐藏金额按钮
金额
计价单位
今日收益金额
今日收益百分比
充值
提现
划转
```

第一版主操作：
- `充值`
- `提现`

可展示但不展开复杂流程的次操作：
- `划转`

资金变动曲线：
- 时间范围：

```text
1日 | 1周 | 1月 | 半年 | 1年
```

- 切换时间后重新请求对应范围资产快照。
- `1日` 可使用分钟级或 15 分钟级点位。
- `1周` 可使用小时级点位。
- `1月`、`半年`、`1年` 可使用天级或周级聚合。
- 曲线颜色使用金色主线，涨跌色仅用于收益和 tooltip。
- 图表背景使用深色面板，不使用参考图的白色卡片。
- hover 时显示时间、资产估值、涨跌变化。
- 无数据时显示“资产快照不足”，不要显示空白图。

近期资金账单：
- 展示最近 2-5 条资金变动。
- 字段：

```text
类型 | 资产 | 时间 | 金额
```

- 金额正数使用绿色，负数使用红色。
- 点击 `查看更多` 跳转 `/account/orders/funding`。

资产账户组合：
- 第一版展示：

```text
资金账户
交易账户
合约账户
```

- 每张账户卡显示账户名称、折算余额、简短说明。
- 点击账户卡可进入账户详情；如果详情未实现，第一版只保留 hover 状态，不做跳转。

资产页数据来源：
- 资产余额来自后端账户聚合，不在前端临时拼接。
- 资产曲线来自资产快照表。
- 充值、提现、划转、交易成交后需要写入资金流水，并触发或影响资产快照。

建议后端接口：

```text
GET /api/account/assets/overview?currency=USDT&range=1d
GET /api/account/assets/accounts
GET /api/account/ledger/recent?limit=5
```

建议数据表或模型：
- `asset_accounts`
- `asset_balances`
- `asset_balance_snapshots`
- `asset_ledger_entries`

#### 订单页

订单页分为资金流水和交易订单。

左侧导航：

```text
订单
  资金流水
  交易订单
```

资金流水路由：`/account/orders/funding`

资金流水子页：

```text
总览
充值
提现
```

资金流水筛选：
- 类型。
- 时间，默认近 30 天。
- 资产。
- 重置按钮。

资金流水表格字段：

```text
时间(UTC+8) | 类别 | 资产 | 数量 | 备注
```

空状态：
- 无记录时展示空状态图标和 `暂无记录`。
- 不要使用全屏空白。

建议接口：

```text
GET /api/account/orders/funding?type=all&range=30d&asset=all
```

#### 交易订单

路由：`/account/orders/trades`

交易订单页签：

```text
当前委托 | 历史委托 | 历史成交
```

第一版筛选只做：
- 交易对。
- 方向。

交易对筛选：
- 下拉内有搜索框。
- 支持输入 symbol 过滤，例如 `BTC/USDT`。
- 默认 `全部`。

方向筛选：

```text
全部 | 买 | 卖
```

明确不做：
- 参考图中的第三个筛选控件第一版不做。
- 不做 C2C 订单。
- 不做合约订单。
- 不做批量撤单以外的复杂订单操作。

当前委托字段：

```text
时间 | 交易对 | 类型 | 方向 | 价格 | 数量 | 冰山单数量 | 完成度 | 成交额 | 触发条件 | 止盈/止损 | 操作
```

历史委托和历史成交可根据现有数据精简，但必须保留：

```text
时间 | 交易对 | 方向 | 价格 | 数量 | 成交额 | 状态
```

建议接口：

```text
GET /api/account/orders/trades/open?symbol=all&side=all
GET /api/account/orders/trades/history?symbol=all&side=all
GET /api/account/orders/trades/fills?symbol=all&side=all
```

#### 账户页

路由：`/account/security/kyc`

当前只做身份认证。

未认证状态：
- 展示头像、用户名、UID、未认证标签。
- 展示 `完成身份认证` 卡片。
- 主按钮为 `立即认证`。
- 可展示 `需要帮助?`、`身份认证常见问题` 两个辅助入口。

已认证状态：
- 展示认证信息摘要。
- 字段建议：

```text
认证状态
认证等级
姓名脱敏
证件类型
认证时间
```

- 已认证后不要继续显示 `立即认证` 主按钮。

暂不实现：
- 账户安全。
- 支付方式。
- API 管理。
- 账户结单。
- 财务报告。

建议接口：

```text
GET /api/account/kyc/status
POST /api/account/kyc/start
```

#### 设置页

路由：`/account/settings`

当前只做三组：
- 昵称和头像。
- 通知语言。
- 偏好设置。

个人资料：
- 昵称。
- 头像。
- 编辑按钮。
- 不做 C2C 个人资料。

通知语言：
- 显示当前语言。
- 编辑后影响站内消息、邮件和 App 推送语言。
- 语言选项应复用全局语言配置。

偏好设置：

```text
颜色偏好设置
风格设置
UTC 时区
键盘快捷键
主题背景
```

说明：
- 颜色偏好用于涨跌色，例如绿涨/红跌、红涨/绿跌。
- 风格设置用于新版色或经典色。
- UTC 时区用于数据时间展示。
- 键盘快捷键为开关。
- 主题背景支持黑夜模式，并保留后续浅色模式扩展。

建议接口：

```text
GET /api/account/settings
PATCH /api/account/profile
PATCH /api/account/preferences
PATCH /api/account/notification-language
```

建议数据表或模型：
- `user_profiles`
- `user_preferences`
- `notification_preferences`

#### 个人中心前端拆分建议

保持可扩展，但不要过度抽象。

建议组件：

```text
AccountShell
AccountSidebar
AccountUserMenu
AccountOverviewPage
AccountAssetsPage
AssetSummaryChartCard
AssetRangeTabs
RecentLedgerPanel
AssetAccountCards
FundingRecordsPage
TradeOrdersPage
OrderPairSelect
OrderSideSelect
KycPage
AccountSettingsPage
PreferenceRows
```

建议服务：

```text
accountApi.getProfile()
accountApi.getOverview()
accountApi.getAssetOverview()
accountApi.getAssetAccounts()
accountApi.getRecentLedger()
accountApi.getFundingRecords()
accountApi.getTradeOrders()
accountApi.getKycStatus()
accountApi.updateProfile()
accountApi.updatePreferences()
```

路由建议：

```text
/account/overview
/account/assets
/account/orders/funding
/account/orders/trades
/account/security/kyc
/account/settings
```

#### 个人中心 UI 与动效

视觉规则：
- 背景使用深黑蓝。
- 卡片使用深灰面板和细边框。
- 主按钮使用金色。
- 次按钮使用深灰底，hover 时边框变金色。
- 表格行 hover 使用弱高亮，不改变行高。
- 图表线条使用金色，不使用大面积荧光。
- 保持 8px 左右圆角，不做过度圆润卡片。

动效规则：
- 下拉框、侧栏展开、筛选下拉使用轻微淡入和位移。
- 图表切换时间范围使用 crossfade 或线条重绘。
- 所有动画必须支持 `prefers-reduced-motion`。
- 不使用大面积粒子、渐变球、复杂 3D 装饰。

#### 个人中心 Codex 执行提示词

```text
你在 C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform 中工作。

先阅读 docs/prototypes/frontend-page-prototype-master.md 的“个人中心”章节，并以该章节为唯一需求来源。

目标：
1. 完成顶部个人中心图标下拉菜单。
2. 点击个人中心默认进入 /account/overview。
3. 完成个人中心 AccountShell、左侧导航和以下页面：
   - /account/overview
   - /account/assets
   - /account/orders/funding
   - /account/orders/trades
   - /account/security/kyc
   - /account/settings
4. 资产页必须使用新版资产看板结构：
   - 总资产估值
   - 资金变动曲线
   - 1日/1周/1月/半年/1年切换
   - 充值、提现按钮
   - 近期资金账单
   - 资产账户组合
5. 账户页当前只实现身份认证。
6. 设置页当前只实现昵称头像、通知语言和偏好设置。
7. 订单页资金流水只实现总览、充值、提现；交易订单只实现当前委托、历史委托、历史成交和交易对/方向筛选。
8. 保持黑金暗色风格，不能使用参考资产页的浅色风格。
9. 不实现文档中明确暂不实现的入口。
10. 代码保持解耦，优先复用现有 AppShell、navigation、路由和 API 风格。
11. 不改动无关交易页、行情页、首页逻辑。
12. 每完成一个阶段，把完成记录追加到 docs/prototypes/frontend-page-prototype-master.md 的“实施记录”。

验证：
- cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
- cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
- 如已有 smoke:user-core-pages 或 smoke:visual-qa，扩展覆盖个人中心关键路由。
- 用桌面视口检查 /account/overview、/account/assets、/account/orders/funding、/account/orders/trades、/account/security/kyc、/account/settings。
```

#### 个人中心验收标准

- 已登录顶部栏显示个人中心图标，未登录不显示。
- hover、点击、键盘 focus 均可打开个人中心下拉。
- 点击个人中心默认进入总览。
- 总览页展示用户资料、新手指引和资产摘要。
- 资产页展示总资产估值、资金曲线、时间范围切换、充值、提现、近期资金账单、资产账户组合。
- 资金流水页展示总览、充值、提现页签和类型、时间、资产筛选。
- 交易订单页展示当前委托、历史委托、历史成交，并仅提供交易对和方向筛选。
- 账户页未认证显示立即认证，已认证显示认证信息。
- 设置页只展示昵称头像、通知语言、偏好设置。
- 未实现入口不跳空白页。
- 页面无横向滚动、无内容重叠、无控制台错误、无框架错误遮罩。
- 所有动画在 reduced motion 下关闭或降级。

## 推荐前端架构

目标：保证扩展性、解耦合、可读性、代码风格优雅，但不做过度架构。

当前相关入口：

```text
apps/web/src/pages/home/HomePage.tsx
apps/web/src/pages/home/HomePage.module.css
apps/web/src/pages/login/LoginPage.tsx
apps/web/src/pages/login/AuthSupportPage.tsx
apps/web/src/app/AppShell.tsx
apps/web/src/app/navigation.ts
apps/web/src/services/authApi.ts
apps/web/src/features/trading-session/tradingSessionStorage.ts
```

建议拆分：

```text
apps/web/src/pages/home/
  HomePage.tsx
  HomePage.module.css
  components/
    HomeTopbarGate.tsx
    HomeHeroGuest.tsx
    HomeHeroUnverified.tsx
    HomeHeroVerified.tsx
    MarketPreviewPanel.tsx
    NewsPreviewPanel.tsx
    TrustAwardsStrip.tsx
    AnimatedCounter.tsx
  hooks/
    useHomeCounters.ts
    useHomeAuthVariant.ts
```

职责边界：

- `HomePage.tsx` 只做页面组合和状态分支。
- `useHomeAuthVariant.ts` 负责把 session 转成 `guest`、`authenticated_unverified`、`authenticated_verified`。
- `useHomeCounters.ts` 负责读取动态数字、轮询、错误降级。
- `AnimatedCounter.tsx` 负责数字展示和弹跳动效。
- `HomeHeroGuest.tsx` 只渲染未登录 hero。
- `HomeHeroUnverified.tsx` 只渲染已登录未验证 hero。
- `HomeHeroVerified.tsx` 只渲染已登录已验证 hero。
- `MarketPreviewPanel.tsx` 和 `NewsPreviewPanel.tsx` 独立，后续可接真实行情/内容接口。
- `TrustAwardsStrip.tsx` 独立，避免荣誉背书混在 hero 内。

认证入口建议拆分：

```text
apps/web/src/pages/login/
  AuthEntryPage.tsx
  AuthEntryPage.module.css
  components/
    AuthIdentifierStep.tsx
    AuthPasswordStep.tsx
    AuthVerificationStep.tsx
    CountryCodeSelect.tsx
```

认证表单状态机：

```text
identifier -> password_existing_user -> logged_in
identifier -> verification_new_user -> registered_and_logged_in
```

前端状态类型建议：

```ts
type AuthChannel = 'email' | 'phone'

type AuthEntryStep =
  | 'identifier'
  | 'password'
  | 'verification'

type HomeAuthVariant =
  | 'guest'
  | 'authenticated_unverified'
  | 'authenticated_verified'
```

## 推荐后端架构

目标：只补齐本次首页和认证入口所需能力，不引入复杂实时系统。

当前相关后端入口：

```text
backend/src/main/java/com/fxplatform/auth/controller/AuthController.java
backend/src/main/java/com/fxplatform/auth/service/AuthService.java
backend/src/main/java/com/fxplatform/auth/dto/response/SessionStatusResponse.java
backend/src/main/java/com/fxplatform/auth/entity/UserEntity.java
backend/src/main/java/com/fxplatform/auth/repository/UserRepository.java
backend/src/main/resources/db/migration/
```

### Session 返回 KYC 状态

现有 `GET /api/auth/session` 应扩展返回 `kycStatus`，用于首页判断验证状态。

建议响应：

```ts
type SessionStatus = {
  status: 'guest' | 'valid_token' | 'invalid_token'
  authenticated: boolean
  userId: string | null
  email: string | null
  role: string | null
  kycStatus: 'NOT_SUBMITTED' | 'PENDING' | 'APPROVED' | 'REJECTED' | null
  loginPath: string
}
```

判断规则：

- `status !== 'valid_token'` 或 `authenticated === false`：`guest`。
- `authenticated === true` 且 `kycStatus !== 'APPROVED'`：`authenticated_unverified`。
- `authenticated === true` 且 `kycStatus === 'APPROVED'`：`authenticated_verified`。

### 动态数字 counter

需要后端持久化，不允许只在前端随机增长。

建议新增表：

```sql
CREATE TABLE core.public_counters (
  counter_key VARCHAR(64) PRIMARY KEY,
  counter_value BIGINT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

建议初始 key：

```text
home_total_users_choice
auth_total_users_trust
home_active_traders
```

后端行为：

- 使用定时任务每秒执行一次。
- 对每个 counter 随机增加 1 到 3。
- 持久化更新后的值。
- 对外提供只读接口。

建议接口：

```text
GET /api/public/home-counters
```

建议响应：

```json
{
  "homeTotalUsersChoice": 321320363,
  "authTotalUsersTrust": 321329546,
  "homeActiveTraders": 184208
}
```

前端行为：

- 首页和认证入口页每秒轮询。
- 如果接口失败，保留最后一次成功值。
- 没有最后成功值时使用后端配置的静态 fallback，不在前端继续伪增长。
- 每次值变化时触发 `AnimatedCounter` 弹跳。

### 账号存在性与验证码流程

为了支持“已有账号显示密码，新账号进入验证码步骤”，建议增加账号检查接口。

接口：

```text
POST /api/auth/identity/check
```

请求：

```json
{
  "channel": "email",
  "email": "user@example.com"
}
```

或：

```json
{
  "channel": "phone",
  "phoneCountryCode": "+60",
  "phone": "123456789"
}
```

响应：

```json
{
  "exists": true,
  "maskedDestination": "u***@example.com"
}
```

验证码发送接口：

```text
POST /api/auth/verification/send
```

注册接口扩展：

```text
POST /api/auth/register
```

支持邮箱或手机号：

```json
{
  "channel": "phone",
  "phoneCountryCode": "+60",
  "phone": "123456789",
  "verificationCode": "123456",
  "password": "..."
}
```

注意事项：

- 如果当前后端暂未接入真实短信/邮件服务，可先做可测试的验证码服务接口边界。
- 不要把验证码写死在前端。
- 新用户验证码步骤需要设置密码，否则后续无法安全登录。

## 修改流程

### 第一阶段：需求固化与测试先行

1. 阅读当前文件：
   - `apps/web/src/pages/home/HomePage.tsx`
   - `apps/web/src/pages/home/HomePage.module.css`
   - `apps/web/src/pages/login/LoginPage.tsx`
   - `apps/web/src/pages/login/AuthSupportPage.tsx`
   - `apps/web/src/app/AppShell.tsx`
   - `apps/web/src/app/navigation.ts`
   - `apps/web/src/services/authApi.ts`
   - `backend/src/main/java/com/fxplatform/auth/controller/AuthController.java`
   - `backend/src/main/java/com/fxplatform/auth/service/AuthService.java`
   - `backend/src/main/java/com/fxplatform/auth/dto/response/SessionStatusResponse.java`
2. 增加或更新测试，先覆盖：
   - 未登录首页不显示个人中心、钱包、消息、公告、客服中心。
   - 已登录顶部栏隐藏登录/注册。
   - `kycStatus` 为未认证时首页显示立即验证。
   - `kycStatus` 为 `APPROVED` 时首页显示立即交易和充值金额。
   - 认证入口表单在当前页面切换步骤，不发生路由跳转。

### 第二阶段：后端最小能力

1. 扩展 `SessionStatusResponse` 返回 `kycStatus`。
2. 扩展 session 测试。
3. 新增 `public_counters` migration。
4. 新增 counter repository/service/controller。
5. 新增定时增长逻辑。
6. 新增账号存在性检查接口。
7. 如果做验证码边界，新增验证码发送接口和注册 DTO 扩展。

### 第三阶段：前端状态和 API

1. 扩展 `apps/web/src/services/authApi.ts` 类型。
2. 新增 `getHomeCounters()`。
3. 新增 `checkAuthIdentity()`。
4. 新增 `sendAuthVerificationCode()`。
5. 新增 `useHomeAuthVariant()`。
6. 新增 `useHomeCounters()`。
7. 新增 `AnimatedCounter`。

### 第四阶段：首页 UI

1. 拆分首页组件。
2. 实现未登录首页。
3. 实现已登录未验证首页。
4. 实现已登录已验证首页。
5. 接入右侧热门交易品种和新闻区块。
6. 接入荣誉背书。
7. 实现黑金视觉和动效。
8. 确认 `prefers-reduced-motion` 生效。

### 第五阶段：认证入口 UI

1. 用图 2 风格替换现有注册支持页。
2. 实现邮箱/手机号切换。
3. 实现国际区号选择。
4. 实现账号存在性检查。
5. 已有账号在当前表单展开密码输入。
6. 新账号在当前表单切换验证码步骤。
7. 登录或注册成功后写入 token 并跳转 `/account`。

### 第六阶段：视觉与验收

1. 启动本地 web。
2. 桌面宽度检查：
   - `1440x900`
   - `1920x940`
3. 检查未登录、已登录未验证、已登录已验证三态。
4. 检查认证页表单状态切换。
5. 执行自动化验证命令。
6. 保存截图和 JSON 报告。

## Codex 执行提示词

后续可以将以下提示词交给 Codex 执行：

```text
请在 C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform 中继续执行前端页面功能分类与原型落地。

先阅读 docs/prototypes/frontend-page-prototype-master.md，并严格以该文档为单一事实源。

本轮只实现首页 PC 端和认证入口相关最小闭环，不实现后续尚未描述的页面。

要求：
1. 先读当前代码和测试，不要直接改。
2. 先补测试，再做最小实现。
3. 首页支持 guest / authenticated_unverified / authenticated_verified 三态。
4. 顶部栏根据登录态显示不同菜单。
5. 动态用户数和交易用户数来自后端持久化 counter，每秒随机 +1 到 +3。
6. 前端数字刷新带轻微弹跳动效，并支持 prefers-reduced-motion。
7. 认证入口支持邮箱/手机号、国际区号、账号存在性检查、密码步骤、验证码步骤。
8. 登录或注册成功后跳转 /account。
9. 只修改本次相关文件，不顺手重构交易页、个人中心或后台。
10. 每完成一个阶段，把完成记录追加到 docs/prototypes/frontend-page-prototype-master.md 的“实施记录”。
```

## 验收命令

Windows 下优先使用：

```bat
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:user-core-pages"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:visual-qa"
```

如果修改后端：

```bat
cmd.exe /d /s /c "cd /d fx-trading-platform\backend && mvn test"
```

验收标准：

- 未登录首页不出现个人中心、钱包、消息、公告、客服中心。
- 已登录首页不出现登录、注册。
- 已登录未验证首页展示“立即验证”。
- 已登录已验证首页展示“立即交易”和“充值金额”。
- 登录/注册入口不通过路由跳转切换表单步骤。
- 用户数和交易用户数来自后端持久化 counter。
- 数字刷新有轻微弹跳，且 reduced motion 下关闭。
- 桌面截图无横向滚动、无内容重叠、无控制台错误、无框架错误遮罩。

## 待用户继续描述的页面

以下页面只记录为占位，未开始设计：

- 钱包
- 消息
- 公告
- 客服中心
- 语言切换
- 手机端首页

## 变更记录

| 时间 | 变更 |
| --- | --- |
| 2026-06-14 05:58:59 +08:00 | 创建文档，记录 PC 首页三态、认证入口、黑金 UI 方向、前后端架构、修改流程和验收标准。 |
| 2026-06-14 06:16:47 +08:00 | 追加行情页面，记录总览、交易数据、自选、行情表格、榜单卡片、前后端接口、UI 动效和验收标准。 |
| 2026-06-14 06:23:46 +08:00 | 追加交易入口与交易页面分类，记录顶部交易菜单、虚拟币/外汇/合约入口、每类最近品种记忆、路由参数和验收标准。 |
| 2026-06-14 06:45:30 +08:00 | 追加个人中心，合并顶部下拉、总览、资产新版看板、订单、账户身份认证、设置与偏好设置需求。 |

## 实施记录

| 时间 | 阶段 | 完成内容 | 涉及文件 | 验证 | 残留风险 |
| --- | --- | --- | --- | --- | --- |
| 2026-06-14 05:58:59 +08:00 | 文档初始化 | 复制三张参考图到仓库内，并创建本需求总文档。 | `docs/prototypes/frontend-page-prototype-master.md`，`docs/prototypes/screenshots/home-pc-black-gold/*` | 文档和图片文件已创建。 | 尚未开始代码实现；后续页面仍待用户描述。 |
| 2026-06-14 06:16:47 +08:00 | 行情页面需求确认 | 复制四张行情参考图到仓库内，并追加行情页总览、交易数据、自选、表格、榜单、前后端接口和验收标准。 | `docs/prototypes/frontend-page-prototype-master.md`，`docs/prototypes/screenshots/markets-black-gold/*` | 文档和图片文件已更新。 | 尚未开始代码实现；交易、个人中心、钱包、消息、公告、客服中心、语言切换和手机端首页仍待描述。 |
| 2026-06-14 06:23:46 +08:00 | 交易入口需求确认 | 复制交易顶部菜单参考图到仓库内，并追加交易菜单、分类默认品种、最近品种记忆、TradingPage 参数处理和验收标准。 | `docs/prototypes/frontend-page-prototype-master.md`，`docs/prototypes/screenshots/trading-black-gold/*` | 文档和图片文件已更新。 | 尚未开始代码实现；个人中心、钱包、消息、公告、客服中心、语言切换和手机端首页仍待描述。 |
| 2026-06-14 06:45:30 +08:00 | 个人中心需求确认 | 复制个人中心九张参考图到仓库内，并追加个人中心下拉、总览、资产新版看板、资金流水、交易订单、身份认证、设置偏好、前后端接口和验收标准。 | `docs/prototypes/frontend-page-prototype-master.md`，`docs/prototypes/screenshots/account-center-black-gold/*` | 文档和图片文件已更新。 | 尚未开始代码实现；钱包、消息、公告、客服中心、语言切换和手机端首页仍待描述。 |
| 2026-06-14 07:40:56 +08:00 | 前端原型首轮落地 | 按文档和参考图完成黑金用户侧 IA 收敛：首页三态、认证入口同页步骤、登录态导航、交易分类下拉、最近交易品种记忆、行情总览/交易数据、账户中心核心六路由；隐藏旧主导航中的 dashboard/orders/positions/security/settings 等非首屏入口，旧业务页保留兼容路由。 | `apps/web/src/app/*`，`apps/web/src/pages/home/*`，`apps/web/src/pages/login/*`，`apps/web/src/pages/markets/MarketsPage.tsx`，`apps/web/src/pages/account/AccountPages.tsx`，`apps/web/src/styles.css`，`apps/web/src/services/*` | `web:test` 270/270 通过；`web:build` 通过；`verify:architecture` 通过；`smoke:visual-qa` 22/22 通过，报告位于 `test-results/visual-qa-smoke/2026-06-13T23-49-40-366Z/report.json`；本地 HTTP 探针确认 `/`、`/markets`、`/account/overview`、`/account/assets`、`/account/orders/funding`、`/account/orders/trades`、`/account/security/kyc`、`/account/settings`、`/register` 均返回 Vite 应用入口。 | `smoke:user-core-pages` 后端健康通过但数据 seed 因 `QUOTE_STALE` 停止；首页计数已保留 `/api/public/home-counters` 前端接口边界和降级数据，后端持久化计数端点仍可在下一轮补齐。 |
| 2026-06-14 08:40:12 +08:00 | 全链路收尾与回归 | 补齐 `/api/public/home-counters` 后端端点和安全白名单，修正交易报价过期时刷新逻辑，增强用户核心 smoke 对订单/持仓真实 ID 的定位；确认 Markets loading/error 状态、订单修改/事件/取消、持仓 TP/SL/平仓、钱包资金申请均走真实 API。 | `backend/src/main/java/com/fxplatform/home/*`，`backend/src/main/java/com/fxplatform/market/service/QuoteService.java`，`backend/src/main/java/com/fxplatform/common/security/SecurityConfig.java`，`apps/web/src/pages/orders/OrdersPage.tsx`，`apps/web/src/pages/positions/PositionsPage.tsx`，`apps/web/src/pages/markets/MarketsPage.tsx`，`scripts/smoke-user-core-pages.mjs` | `web:test` 272/272 通过；`web:build` 通过；`verify:architecture` 通过；`mvn -q test -Dtest=QuoteServiceTest,HomeCountersServiceTest,HomeControllerTest` 通过；`smoke:user-core-pages` 在 `http://127.0.0.1:8081` + `http://127.0.0.1:5174` 全部 PASS；`smoke:visual-qa` 22/22 通过，报告位于 `test-results/visual-qa-smoke/2026-06-14T00-40-12-966Z/report.json`。 | 无当前阻塞；真实外部行情源、支付通道和生产鉴权策略仍需在对应环境做专项验收。 |
