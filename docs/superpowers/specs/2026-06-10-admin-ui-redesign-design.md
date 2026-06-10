# Admin UI Redesign Design

## 目标

重构 `fx-trading-platform/apps/admin` 独立后台管理系统的前端 UI，使其更专业、更适合高频后台运营工作，并加入克制的按钮点击反馈、菜单切换、页签切换、弹窗进入和表格行 hover 动效。

## 范围

- 仅覆盖 `fx-trading-platform/apps/admin`。
- 不修改 `apps/web` 交易终端。
- 不修改根目录 KLineChart 图表库。
- 不改变后台 API 路由、请求参数、token 存储、业务动作语义。
- 不重构当前未被 `AdminApp.tsx` 路由使用的旧 `AdminDashboard.tsx` 业务结构；样式保持兼容它已引用的类名。

## 设计方向

采用 `ui-ux-pro-max` 推荐的 Data-Dense Dashboard 方向：

- 浅色运营工作台背景，深色专业侧栏。
- 蓝色作为主操作和选中态，红色只用于危险操作。
- 面向表格、筛选、审核、配置和财务数据，信息密度高但层级清晰。
- 控件尺寸满足后台桌面端效率，同时移动端保持 44px 以上触控目标。
- 动效只服务状态变化，不做装饰动画。

## 关键界面

- 登录页：更像专业管理入口，包含左侧产品信号和右侧登录表单；保留原登录逻辑。
- 后台外壳：侧栏、品牌区、菜单分组、折叠按钮、顶部面包屑、顶部工具按钮、头像、路由页签。
- 控制台：指标卡片更有层级，加载/错误状态与页面背景一致。
- 通用 CRUD 页：页面标题、筛选区、工具栏、消息提示、表格、分页、弹窗、表格设置。
- 普通数据页：通过共享 `PageHeader`、`StateBlock`、`DataTable` 继承新的后台视觉语言。

## 交互要求

- 按钮 hover、focus、active 有明确反馈，active 使用轻微 `translateY` 或 `scale`，不造成布局跳动。
- 菜单项和页签选中态平滑切换，当前页有明显但不过度的视觉锚点。
- 表格行 hover 高亮，排序按钮 disabled 状态清晰。
- 弹窗蒙层淡入，内容轻微上移进入。
- 加载、错误、空状态不显示空白页面。
- 使用 `prefers-reduced-motion: reduce` 关闭非必要动画。

## 成功标准

- `npm.cmd --workspace apps/admin run test` 通过。
- `npm.cmd --workspace apps/admin run build` 通过。
- `http://localhost:5174/` 能打开后台页面或登录页，无 Vite/React 错误覆盖层。
- 桌面视口和移动视口下无明显文字重叠、横向整体溢出或主要操作按钮不可见。
- 至少验证一个核心交互：侧栏折叠、页签切换、登录按钮 loading、表格设置弹窗或分页按钮。

## 权衡

- 选择重构共享 UI 外壳和样式，而不是全量重写每个页面。这样所有后台页面都会统一变专业，同时避免触碰业务 API。
- 不引入新的 UI 库。当前项目已有 React、React Router 和 `lucide-react`，新增依赖会提高构建和样式冲突风险。
- 不做过度装饰。后台管理系统优先扫描效率、表格可读性和操作反馈。
