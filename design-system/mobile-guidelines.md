# Mobile First Guidelines

## 目标设备

优先保证：

- 375 x 667：小屏手机基线。
- 390 x 844：主流 iPhone 尺寸。
- 412 x 915：主流 Android 尺寸。
- 横屏：至少能看 K 线和关键交易入口。

桌面和平板是扩展布局，不是手机布局的压缩版。

## 手机端信息优先级

`/trading` 第一屏优先级：

1. 当前交易对、价格、涨跌幅。
2. K 线和周期切换。
3. 买入/卖出入口。
4. 盘口入口。
5. 订单和资产入口。
6. 指标、绘图、布局设置。

低频内容放入 sheet 或二级页面：

- 绘图工具全量列表
- 指标参数
- 布局 preset
- 高级订单策略
- 后台复杂表格设置

## 交易页手机布局

推荐结构：

```text
Safe Area Top
Symbol Header
Chart Toolbar
K-Line Chart
Compact Quote Strip
Sticky Trade Action Bar
Safe Area Bottom
```

交互入口：

- Markets：左侧 drawer。
- Quote / OrderBook：右侧 drawer 或底部 sheet。
- Trade：bottom sheet。
- Orders / Assets：底部导航或页面内 tabs。

不要把桌面 `TradingWorkspace` 的四块面板原样堆叠到手机第一屏。

## Bottom Action Bar

交易页的手机底部栏应只放高频交易动作：

- Markets
- Trade
- Quote

规则：

- 高度 52-56px + `env(safe-area-inset-bottom)`。
- `Trade` 是视觉主操作，但不能使用品牌式大按钮占据半屏。
- 每个按钮 touch target 至少 44px。
- 如果存在全局底部导航，交易页隐藏全局导航，避免双底栏。

## Bottom Sheet

下单 sheet：

- 默认高度 78-88vh。
- 顶部显示交易对和当前价格。
- `Buy/Sell` 分段控件 sticky。
- submit footer sticky。
- 输入框获得焦点时避免被键盘遮挡。
- 高级区折叠，默认只显示基础下单。

设置 sheet：

- 主题切换：显示 5 个主题 swatch，不只显示文字。
- 语言切换：中文、English、日本語。
- 密度切换：compact、normal、comfortable。
- 保存后即时生效，不要求刷新。

## Chart On Mobile

K 线手机规则：

- 图表高度默认 320-420px，可折叠但不能默认隐藏。
- 周期条最多显示 6 个常用项：1m、5m、15m、1h、4h、1d，其余进 "More"。
- 十字线通过长按触发。
- 绘图工具默认收起，只显示一个 tools 按钮。
- 指标最多默认显示主图 + 1 个副图，更多指标要用户主动开启。

图表性能：

- 首屏不超过 160 根 K 线。
- 大历史数据通过时间范围加载和 downsample。
- 高频报价更新要节流到每帧最多一次。

## Order Book On Mobile

手机盘口：

- 默认两列或三列可切换。
- 价格列优先，数量列其次，合计可隐藏。
- 行高 22-24px。
- spread 行固定在中间。
- tick size 和显示模式使用 compact toolbar。
- 深度条只作为背景，不改变文字对比。

窄屏 `<= 375px`：

- `Price` 和 `Amount` 两列。
- `Total` 合并到辅助小字或隐藏。
- 最近成交单独 tab，不和盘口同时显示。

## Forms On Mobile

规则：

- 输入框高度 44-48px。
- 输入字号 16px。
- 使用正确键盘：
  - email: `type="email"`
  - password: `type="password"`
  - 数字：`inputMode="decimal"`
- 数字字段要保留单位 suffix。
- 错误在字段下方显示，不能只用 toast。
- 提交按钮 sticky 到 sheet footer。

下单表单顺序：

1. 订单类型
2. 价格
3. 数量
4. 百分比
5. 预估值
6. 风险提示
7. 提交

## Tables On Mobile

复杂表格转为摘要列表：

订单列表卡片：

- 第一行：交易对、方向、状态。
- 第二行：价格、数量、成交额。
- 第三行：时间、订单号短文本、操作。

资产列表卡片：

- 第一行：币种、估值。
- 第二行：可用、冻结。
- 第三行：充值、提现、划转。

后台表格：

- 允许横向滚动，但表头 sticky。
- 工具栏折叠为 filter sheet。
- 行操作收进 more menu。

## Touch And Gesture

必须：

- Touch target >= 44 x 44px。
- 相邻 touch target 间距 >= 8px。
- 使用 `touch-action: manipulation`。
- 不依赖 hover。
- 所有 swipe 操作有可见替代按钮。

避免：

- 主内容区横向 swipe 和系统返回冲突。
- drawer、chart pan、table scroll 三层手势叠加。
- 过小图标按钮没有 hit area。

## Safe Area

必须使用：

```css
padding-top: env(safe-area-inset-top);
padding-bottom: env(safe-area-inset-bottom);
```

应用位置：

- fixed bottom action bar
- bottom sheet footer
- mobile drawer
- full-screen chart
- toast stack

滚动内容底部要额外保留底栏高度，避免最后一行被遮挡。

## Navigation

手机导航原则：

- 交易页保留交易动作优先。
- 非交易页使用全局 bottom nav。
- 页面返回遵循浏览器历史，不使用强制 replace 破坏返回。
- 登录成功回到 `redirect`，但需校验 redirect 安全。
- 弹层关闭后焦点回到触发按钮。

## Accessibility

手机端也要保留：

- `aria-label` for icon buttons。
- `aria-live` for session、loading、toast。
- 表单 label 和 error 关联。
- color 不作为唯一信息。
- 动效尊重 `prefers-reduced-motion`。
- 文本缩放到 125%-150% 不溢出按钮。

## Performance

目标：

- 首屏交互时间低于 2.5s，本地开发环境以相对指标观察。
- 高频行情更新不触发整页 re-render。
- 长列表虚拟化或分页。
- Drawer 和 sheet 使用懒加载或轻量内容。
- 图表容器预留高度，避免 CLS。

## 手机端验收清单

- 375px 下无水平滚动。
- 下单 sheet 打开、输入、提交、关闭可完成。
- Markets drawer 可搜索和选择交易对。
- Quote drawer 中盘口文字不被深度条覆盖。
- 键盘弹出时提交按钮仍可访问。
- 底部栏不遮挡滚动内容。
- dark 和 arctic-light 两个主题都可读。
- 中文、英文、日文切换后按钮文字不溢出。
