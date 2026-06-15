# Component Specifications

## 总规则

- 组件不得写裸 hex，必须消费 `tokens.md` 中的语义 token。
- 所有交互元素必须有 hover、pressed、focus-visible、disabled 状态。
- 手机端所有主要触控目标不小于 44px，密集表格行可小于 44px，但行内按钮必须扩展 hit area。
- 图标统一使用 `lucide-react`，同一层级保持 16px 或 18px，stroke 风格一致。
- 不使用 emoji 作为结构图标。
- 不在组件内硬编码可见文案，文案通过 i18n key 注入。

## Buttons

### 尺寸

| Name | Height | Padding | Font | 用途 |
|---|---:|---:|---|---|
| `button-xs` | `26px` | `0 8px` | 12/16 | 表格行操作 |
| `button-sm` | `32px` | `0 10px` | 12/16 | 工具栏 |
| `button-md` | `40px` | `0 14px` | 14/20 | 默认 |
| `button-lg` | `48px` | `0 16px` | 16/24 | 手机主操作 |

### 变体

- `primary`：普通主操作，使用 `--color-accent`。
- `buy`：买入、做多，使用 `--market-up-strong`。
- `sell`：卖出、做空，使用 `--market-down-strong`。
- `ghost`：工具栏和次级操作，透明背景。
- `surface`：面板内按钮，使用 `--color-bg-field`。
- `danger`：删除、强风险、强制平仓。

### 规则

- 每个屏幕只允许一个非买卖型 primary。
- 买/卖按钮不算普通 primary，它们是交易语义按钮。
- 图标按钮必须有 `aria-label` 和 tooltip。
- disabled 不只降低透明度，还要取消 pointer action。

## Forms

### 输入框

交易输入框：

- 高度：手机端 44-48px，桌面紧凑 36-40px。
- 字号：手机端 16px，桌面 13-14px。
- 数字输入：右对齐或使用 data font，单位作为右侧 suffix。
- label 常驻显示，不使用 placeholder 代替 label。
- 错误显示在字段下方，不只显示在顶部。

### 金融表单规则

- 价格、数量、金额必须显示单位。
- 手续费、可用余额、预估成交额在按钮前可见。
- 下单按钮前必须显示订单预览区：
  - 方向
  - 类型
  - 价格
  - 数量
  - 预估成交额
  - 预估手续费
  - 风险提示
- 错误必须说明原因和恢复路径，例如重试、登录、调整数量、切换市价。

### Select / Dropdown

- 用于订单类型、周期、指标、语言、主题。
- 面板宽度至少等于触发器宽度。
- 手机端优先用 bottom sheet，桌面用 popover。
- 长列表要支持搜索或分组。

## Tables And Lists

### 表格密度

| Density | Row Height | Font | 用途 |
|---|---:|---|---|
| compact | 28-34px | 12/16 | 盘口、成交、订单历史 |
| normal | 40-44px | 13/18 | 后台列表 |
| comfortable | 48px | 14/20 | 低频设置 |

### 表格规则

- 数字列右对齐，文本列左对齐，状态列居中或左对齐但固定宽度。
- 价格、数量、金额使用 tabular figures。
- 表头 sticky 时必须有明确背景和底边线。
- 手机端复杂表格不强行压进 375px：
  - 订单历史用 card-list row summary。
  - 资产列表显示主币种、可用、冻结、估值。
  - 后台表格允许横向滚动，但必须有固定首列或清晰边界。
- 行操作不得只靠图标，危险操作需要确认。

## K-Line And Chart Container

### 容器

- 外层使用 `surface-panel`，圆角 6-8px。
- 内部图表区域 radius 为 0。
- 顶部工具条高度 36-40px。
- 绘图工具栏在手机端默认折叠。
- 图表加载失败必须显示重试，不显示空白 canvas。

### K 线规则

- Bullish：`--market-up-strong`
- Bearish：`--market-down-strong`
- Volume opacity：0.35-0.45
- Grid line 低对比，不抢数据。
- Crosshair label 背景必须高对比，文字 11-12px。
- 可访问 fallback：提供 OHLC 数字摘要或数据表入口。

### 图表交互

- 手机端保留 pinch zoom、crosshair 长按、周期切换。
- 不在主图区域放过多按钮，避免遮挡 K 线。
- 周期选择：常用周期横向分段，更多周期进 sheet。
- 指标设置：桌面 modal，手机 bottom sheet。

## Order Book

### 布局

默认三列：

- Price
- Amount
- Total

手机窄屏可切换两列：

- Price
- Amount/Total 组合

### 规则

- 卖盘在上，买盘在下，中间显示最新价和 spread。
- 深度条从右向左铺，不能盖住数字。
- 行高桌面 20px，手机 22-24px。
- 最新价区域高度 32-36px。
- tick size 控件放在工具栏右侧。
- 买卖压力、spread、合计等辅助信息放底部，不挤占主行。

### 状态

- 空快照：显示 `OrderBookSkeleton`。
- 断线：保留最后快照，顶部显示 reconnecting。
- 价格更新：只闪烁背景，不移动行高。
- 无数据：显示明确文案和重试。

## Trade Panel

### 信息架构

从上到下：

1. 账户状态和模式：现货、保证金、合约等。
2. 方向 tabs：Buy / Sell 或左右双栏。
3. 订单类型：Limit / Market / Stop / Advanced。
4. 价格输入。
5. 数量输入。
6. 百分比 slider。
7. TP/SL 和高级选项。
8. 余额、预估手续费、预估成交额。
9. 主操作按钮。
10. 错误、登录、重试提示。

### 手机端

- 下单面板用 bottom sheet。
- Buy / Sell 用 segmented control。
- 打开数字键盘后，提交按钮保持可见或提供 sticky footer。
- 表单字段不超过一屏时优先单列。
- 高级选项默认折叠。

### 验证

- 空值、非数字、超余额、最小下单量、风险拒绝都要有字段级错误。
- 登录必需状态显示登录按钮，不提交订单。
- 下单中按钮 loading，防重复点击。
- 提交成功显示 toast + 订单行高亮。

## Bottom Navigation

交易端手机底部导航建议 4-5 项：

- Markets
- Trade
- Orders
- Assets
- Account 或 Settings

规则：

- 高度 56px + safe area。
- 图标 20px，文字 11px。
- 当前项用 accent 或 text primary，不使用大面积背景。
- `/trading` 页内的快速操作条和全局底部导航不能同时挤占同一区域。交易页优先显示交易快速操作，其他页面显示全局 bottom nav。

## Modals, Drawers, Sheets

### Modal

- 用于确认、指标设置、危险操作。
- 桌面宽度 360-520px。
- 手机端尽量转为 sheet。
- scrim opacity 暗色 0.50，浅色 0.32。
- Esc 和关闭按钮都可关闭，危险操作除外。

### Drawer

- 左侧用于 Markets。
- 右侧用于 Quote / OrderBook。
- 手机端宽度 `min(92vw, 420px)`。
- 必须保留标题、关闭按钮和焦点管理。

### Bottom Sheet

- 用于 Trade、主题、语言、周期、指标设置。
- 支持 swipe down 关闭，但必须有可点击关闭按钮。
- sticky footer 用于主操作。
- 内容底部必须避开 home indicator。

## Toast

用途：

- 下单成功
- 订单提交失败
- 已复制
- 设置已保存
- 网络重连

规则：

- 3-5 秒自动消失。
- 错误 toast 可保留重试按钮。
- 不抢焦点，使用 `aria-live="polite"`。
- 手机端底部 toast 避开 bottom nav。

## Loading And Skeleton

### Route Loading

- `ExchangeLoading` 作为路由级加载。
- 显示产品名、连接状态、简化行情块。
- 不使用普通 spinner。

### Panel Skeleton

- 盘口 skeleton 行数固定，避免布局跳动。
- 表格 skeleton 保持列宽。
- Shimmer 使用 transform，`prefers-reduced-motion` 下关闭。

### Inline Loading

- 按钮提交显示 spinner 或 loading text。
- 列表刷新保留旧数据，显示顶部 sync 状态。

## Empty And Error States

空状态结构：

- 标题
- 简短原因
- 一个恢复动作

错误状态结构：

- 错误类型
- 可读原因
- request id 或状态码
- 重试入口

交易错误不使用模糊文案，例如只写 "Invalid input"。必须说明哪个字段、为什么、怎么修。
