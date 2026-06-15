# Design Tokens

## Token 原则

所有组件只消费语义 token，不直接消费裸色值。裸色值只能出现在主题定义文件中。

命名分层：

- 基础色板：`palette.*`
- 语义色：`--color-*`
- 组件别名：`--component-*`
- 数据语义：`--market-*`

CSS 推荐入口：

```css
:root {
  color-scheme: dark;
}

[data-theme='terminal-pro'] {
  --color-bg-app: #05080c;
  --color-bg-surface: #0d1219;
}
```

## 主题清单

### 1. terminal-pro

默认暗色交易终端主题。

| Token | Value | 用途 |
|---|---:|---|
| `--color-bg-app` | `#05080C` | 页面底色 |
| `--color-bg-surface` | `#0D1219` | 面板底色 |
| `--color-bg-surface-2` | `#090E14` | 次级面板 |
| `--color-bg-surface-3` | `#111923` | hover/active 面 |
| `--color-bg-field` | `#101821` | 输入框 |
| `--color-border-subtle` | `#141B24` | 弱边界 |
| `--color-border` | `#1D2530` | 默认边界 |
| `--color-border-strong` | `#283341` | 强边界 |
| `--color-text-primary` | `#F6F8FB` | 主文本 |
| `--color-text-secondary` | `#DCE3EC` | 正文 |
| `--color-text-muted` | `#8A94A3` | 辅助 |
| `--color-text-faint` | `#4F5A68` | 弱辅助 |
| `--color-accent` | `#F2B84B` | 主要强调 |
| `--color-accent-soft` | `rgba(242,184,75,0.12)` | 强调底 |
| `--color-accent-border` | `rgba(242,184,75,0.42)` | 强调边框 |
| `--color-on-accent` | `#15100A` | 强调文本 |

### 2. graphite-gold

稳重石墨金融主题，适合资产、后台和高级账户场景。

| Token | Value |
|---|---:|
| `--color-bg-app` | `#080A0D` |
| `--color-bg-surface` | `#111418` |
| `--color-bg-surface-2` | `#0C0F13` |
| `--color-bg-surface-3` | `#191D23` |
| `--color-bg-field` | `#151A20` |
| `--color-border-subtle` | `#1A2028` |
| `--color-border` | `#252C36` |
| `--color-border-strong` | `#39424E` |
| `--color-text-primary` | `#F4F1EA` |
| `--color-text-secondary` | `#D8D1C4` |
| `--color-text-muted` | `#9B9387` |
| `--color-text-faint` | `#605A51` |
| `--color-accent` | `#C9A45C` |
| `--color-accent-soft` | `rgba(201,164,92,0.13)` |
| `--color-accent-border` | `rgba(201,164,92,0.42)` |
| `--color-on-accent` | `#11100C` |

### 3. midnight-blue

深蓝科技主题，适合行情监控和图表分析。

| Token | Value |
|---|---:|
| `--color-bg-app` | `#050914` |
| `--color-bg-surface` | `#0B1220` |
| `--color-bg-surface-2` | `#07101C` |
| `--color-bg-surface-3` | `#101A2B` |
| `--color-bg-field` | `#0F1A29` |
| `--color-border-subtle` | `#142033` |
| `--color-border` | `#1D2B42` |
| `--color-border-strong` | `#2B3E5B` |
| `--color-text-primary` | `#F1F7FF` |
| `--color-text-secondary` | `#D6E2F1` |
| `--color-text-muted` | `#8B9CB3` |
| `--color-text-faint` | `#556479` |
| `--color-accent` | `#4FB6FF` |
| `--color-accent-soft` | `rgba(79,182,255,0.13)` |
| `--color-accent-border` | `rgba(79,182,255,0.38)` |
| `--color-on-accent` | `#06111C` |

### 4. oxide-green

黑绿终端主题，适合高频数据、监控和风控提示。

| Token | Value |
|---|---:|
| `--color-bg-app` | `#040908` |
| `--color-bg-surface` | `#0B1412` |
| `--color-bg-surface-2` | `#07100E` |
| `--color-bg-surface-3` | `#10201B` |
| `--color-bg-field` | `#0D1A17` |
| `--color-border-subtle` | `#12231E` |
| `--color-border` | `#1C332C` |
| `--color-border-strong` | `#2F4E43` |
| `--color-text-primary` | `#EFFAF5` |
| `--color-text-secondary` | `#D3E6DD` |
| `--color-text-muted` | `#8BA096` |
| `--color-text-faint` | `#50685D` |
| `--color-accent` | `#42D392` |
| `--color-accent-soft` | `rgba(66,211,146,0.12)` |
| `--color-accent-border` | `rgba(66,211,146,0.38)` |
| `--color-on-accent` | `#04100B` |

### 5. arctic-light

浅色辅助主题，适合强光环境和后台表格。

| Token | Value |
|---|---:|
| `--color-bg-app` | `#F3F6FA` |
| `--color-bg-surface` | `#FFFFFF` |
| `--color-bg-surface-2` | `#F8FAFC` |
| `--color-bg-surface-3` | `#EEF3F8` |
| `--color-bg-field` | `#EEF2F6` |
| `--color-border-subtle` | `#E7EBF1` |
| `--color-border` | `#D8DEE8` |
| `--color-border-strong` | `#C8D0DC` |
| `--color-text-primary` | `#0F172A` |
| `--color-text-secondary` | `#1F2937` |
| `--color-text-muted` | `#657386` |
| `--color-text-faint` | `#A7B0BD` |
| `--color-accent` | `#111827` |
| `--color-accent-soft` | `rgba(17,24,39,0.08)` |
| `--color-accent-border` | `rgba(17,24,39,0.22)` |
| `--color-on-accent` | `#FFFFFF` |

## 行情与状态色

所有主题共享语义方向，只允许调整明度和透明度。

| Token | Default | 用途 |
|---|---:|---|
| `--market-up` | `#20B26B` | 上涨、买入、盈利 |
| `--market-up-strong` | `#26A69A` | 主买按钮、强上涨 |
| `--market-up-soft` | `rgba(32,178,107,0.12)` | 上涨底色 |
| `--market-down` | `#F05267` | 下跌、卖出、亏损 |
| `--market-down-strong` | `#EF5350` | 主卖按钮、强下跌 |
| `--market-down-soft` | `rgba(240,82,103,0.12)` | 下跌底色 |
| `--market-bid-depth` | `rgba(23,154,91,0.25)` | 买盘深度条 |
| `--market-ask-depth` | `rgba(201,47,65,0.27)` | 卖盘深度条 |
| `--state-warning` | `#F2B84B` | 风险提示、等待 |
| `--state-danger` | `#F05267` | 错误、强风险 |
| `--state-success` | `#20B26B` | 成功、已完成 |
| `--state-info` | `#4FB6FF` | 信息、同步中 |

可访问性规则：

- 涨跌不能只靠颜色表达，关键位置必须同时显示 `+/-`、箭头或文字。
- 盘口深度条 opacity 不超过 0.32，不能影响数字可读性。
- 错误、警告、成功文本对背景至少达到 WCAG AA。

## 字体

推荐字体栈：

```css
--font-ui: Inter, "Noto Sans SC", "Noto Sans JP", ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
--font-data: "JetBrains Mono", "SFMono-Regular", Consolas, "Liberation Mono", monospace;
```

规则：

- UI 文本使用 `--font-ui`。
- 价格、数量、百分比、时间、订单号短片段使用 `--font-data` 或 `font-variant-numeric: tabular-nums`。
- 不使用展示字体，不使用负 letter spacing。
- 不通过 viewport width 缩放字体。

字号：

| Token | Size | Line Height | 用途 |
|---|---:|---:|---|
| `--font-size-10` | `10px` | `14px` | 极小标签，仅桌面密集表头 |
| `--font-size-11` | `11px` | `16px` | 行情辅助 |
| `--font-size-12` | `12px` | `16px` | 表格、盘口 |
| `--font-size-13` | `13px` | `18px` | 密集正文 |
| `--font-size-14` | `14px` | `20px` | 默认 UI |
| `--font-size-16` | `16px` | `24px` | 表单输入、移动端正文 |
| `--font-size-20` | `20px` | `28px` | 页面标题 |
| `--font-size-24` | `24px` | `32px` | 关键价格、登录页标题 |

移动端输入框字号不得低于 `16px`，避免 iOS 自动缩放。

## 间距

采用 4px 基础网格。

| Token | Value | 用途 |
|---|---:|---|
| `--space-0` | `0` | 无间距 |
| `--space-1` | `4px` | 紧密图标间距 |
| `--space-2` | `8px` | 默认组件内间距 |
| `--space-3` | `12px` | 面板 padding |
| `--space-4` | `16px` | section 间距 |
| `--space-5` | `20px` | 页面内边距 |
| `--space-6` | `24px` | 大 section |
| `--space-8` | `32px` | 登录页、空状态 |

密度：

- `compact`：交易端默认，行高 20-28px，面板 padding 8-12px。
- `normal`：后台默认，表格行高 40-44px，页面 padding 16-20px。
- `comfortable`：设置页、登录页、低频表单，表格行高 48px。

## 圆角

交易端控制圆角，避免卡片感过强。

| Token | Value | 用途 |
|---|---:|---|
| `--radius-0` | `0` | 图表、终端分割区 |
| `--radius-1` | `2px` | 指示条、K 线标签 |
| `--radius-2` | `4px` | 小按钮、表格标签 |
| `--radius-3` | `6px` | 输入框、分段控件 |
| `--radius-4` | `8px` | 面板、弹窗 |
| `--radius-pill` | `999px` | badge、switch、pill |

规则：

- 面板最大 8px。
- 不做 16px 以上的卡片圆角。
- 图表容器内部区域尽量 0px，外层 panel 可 6-8px。

## 阴影

暗色交易端主要靠边框和 surface 层级，少用阴影。

| Token | Value | 用途 |
|---|---|---|
| `--shadow-none` | `none` | 普通面板 |
| `--shadow-sm` | `0 1px 2px rgba(0,0,0,0.24)` | 小浮层 |
| `--shadow-md` | `0 14px 34px rgba(0,0,0,0.42)` | Popover |
| `--shadow-lg` | `0 18px 48px rgba(0,0,0,0.48)` | Drawer、Modal |
| `--shadow-focus` | `0 0 0 3px var(--color-accent-soft)` | focus |

浅色主题阴影 opacity 降低，避免 SaaS 卡片感过强。

## 动效

| Token | Value | 用途 |
|---|---:|---|
| `--motion-fast` | `120ms` | pressed |
| `--motion-base` | `160ms` | hover、focus |
| `--motion-panel` | `220ms` | drawer、sheet |
| `--motion-slow` | `300ms` | 页面级进入 |
| `--ease-out` | `cubic-bezier(0.16,1,0.3,1)` | 进入 |
| `--ease-in` | `cubic-bezier(0.7,0,0.84,0)` | 退出 |
| `--ease-standard` | `cubic-bezier(0.2,0.8,0.2,1)` | 常规 |

规则：

- 只动画 `opacity` 和 `transform`。
- 不动画 `width`、`height`、`top`、`left`。
- 盘口价格变动使用 120-160ms 背景闪烁，不移动布局。
- `prefers-reduced-motion: reduce` 下禁用 shimmer、扫描线和非必要过渡。

## Z-Index

| Token | Value | 用途 |
|---|---:|---|
| `--z-base` | `0` | 页面内容 |
| `--z-sticky` | `100` | sticky header |
| `--z-bottom-nav` | `300` | 手机底部栏 |
| `--z-popover` | `600` | dropdown/popover |
| `--z-drawer` | `900` | drawer/sheet |
| `--z-modal` | `1200` | modal |
| `--z-toast` | `1400` | toast |

## 图表专用 token

| Token | Default | 用途 |
|---|---:|---|
| `--chart-bg` | `var(--color-bg-surface-2)` | 图表背景 |
| `--chart-grid-x` | `#111923` | 纵向网格 |
| `--chart-grid-y` | `#151D28` | 横向网格 |
| `--chart-axis` | `var(--color-text-faint)` | 坐标轴 |
| `--chart-crosshair` | `rgba(220,227,236,0.46)` | 十字线 |
| `--chart-volume-up` | `rgba(32,178,107,0.38)` | 上涨量柱 |
| `--chart-volume-down` | `rgba(240,82,103,0.38)` | 下跌量柱 |

K 线可见数量：

- 手机端默认 80-160 根。
- 桌面默认 160-320 根。
- 同屏最多不超过 500 根，超出使用聚合或缩放。
