# 现货与 USDT 永续模拟交易平台适配分析报告

> 日期：2026-07-11
>
> 审计对象：fx-trading-platform 当前工作树（HEAD e4b4e54a，含用户尚未提交的指定规格文档）
>
> 审计性质：静态、只读代码审计；未运行构建、测试、服务、Docker、数据库或 Redis
>
> 目标：在最大程度复用现有代码的前提下，把项目收敛为外观、页面结构和操作流程接近正规交易所的演示型模拟交易系统

## 0. 结论先行

**推荐继续使用当前项目，不建议换技术栈，也不建议推倒重做。**

当前项目已经具备 React 交易终端、KLineCharts、行情 REST/STOMP、登录会话、现货 MARKET/LIMIT/STOP、资产冻结与解冻、全量成交、成交落库、现货钱包结算、净持仓、PnL、TP/SL 扫描、资金费与强平骨架、后台运营页面等大量基础能力。对目标 MVP 而言，最合理的路线不是建设交易所内核，而是：

1. 保留 React 19 + Vite + Spring Boot 3.5 + MyBatis-Plus + PostgreSQL + Redis 的模块化单体。
2. 后端继续作为唯一交易状态真值，采用“市价单立即全量成交；限价单触价后全量成交；未触价保持委托；可撤单”的简单模型。
3. 行情采用“外部公共行情优先，后端本地生成器自动兜底”，并把 LIVE/SIMULATED、freshness 和 source 明确显示在页面；前端本地 mock 只允许做 loading skeleton，不得参与余额、可下单数量、成交或风险判断。
4. 现货最大复用现有 OrderService → OrderFillService → SpotSettlementService → WalletService/SpotPositionService 链路。
5. 永续最大复用 PositionEngine、PerpMarginCalculator、TradingAlgorithmEngine、PositionService、ProtectiveOrderExecutionService 和 LiquidationService；补充产品、持仓模式、仓位方向、保证金模式和 reduce-only 等最小语义。
6. 手动平仓、TP/SL、模拟强平统一走“系统生成的 reduce-only 市价平仓单”，从而自然产生订单、成交、手续费、PnL、事件和通知；不实现订单簿撮合、部分成交、保险基金、ADL 或真实清算。
7. Web 不需要新建另一套交易页：复用 TradingPage，通过 /trade/spot 和 /trade/perpetual 路由别名或 productMode 参数切换；移动端复用同一业务状态，不继续保留当前硬编码的 Cross/100x/余额壳层。
8. Admin 不需要重建后台：把已有但隐藏的账户、订单、成交、持仓、行情状态、风险和审计页放回主导航，接通真实接口，并新增 Demo-only 重置与资产明细操作。

项目最关键的 P0 不是“做更多页面”，而是修复四条可信度边界：

- 真实账户余额不得再由 useMockBalances 和 bottomAccountPanelData 补齐。
- 账户现金账与 wallet 账必须明确各自用途，Demo 初始化不能表现为三份独立的 10,000。
- 所有模拟成交入口必须受 execution.mode=demo 且 account_type=DEMO 的后端门禁保护。
- 账户 STOMP 事件不得继续允许匿名订阅任意 /topic/trading/accounts/{accountId}/events。

### 0.1 六项评分

| 评分项 | 分数 | 主要扣分原因 |
|---|---:|---|
| 当前前端与正规交易所视觉和结构接近程度 | 67/100 | 桌面终端结构、K 线、盘口、订单区和深色主题较成熟；但首页存在品牌/奖项静态宣称，Spot/Perp 产品语义冲突，移动终端大量硬编码，真实/模拟数据边界不清，Admin 仍偏通用 CRUD |
| 作为现货模拟交易平台的适配度 | 81/100 | MARKET、LIMIT、冻结、撤单、全量成交、钱包结算和成本持仓已存在；扣分在用户成交 API 缺失、挂单手续费字段不一致、scheduler 默认关闭、资金口径分叉和前端 mock 余额 |
| 作为永续合约模拟交易平台的适配度 | 49/100 | 有净持仓、杠杆、IM/MM、PnL、TP/SL、funding/liquidation 骨架；但没有正式 Perp 产品种子、hedge、isolated、reduce-only、用户可调杠杆、权威 mark feed，平仓路径也未生成标准 Order/Trade |
| 指定改进文档与当前项目适配度 | 66/100 | 对现状问题、复用链路和安全不变量判断较准；但 PUBLIC/OFFLINE 硬隔离与用户要求的自动兜底冲突，funding/backfill、runId/provenance、close batch、V46/V47 和大规模并发门禁超出本 Demo MVP，且前端视觉、移动端和 Admin 权重不足 |
| 最大程度复用现有代码的可行性 | 89/100 | 技术栈、页面壳、K 线、行情、订单、钱包、持仓、后台和数据库均可保留；主要是增量字段、统一入口和清理断链，不需要换框架 |
| 通过最小修改完成既定 MVP 的可行性 | 78/100 | 现货闭环很接近；但用户已把桌面/移动、Web/Admin、one-way/hedge、cross/isolated、TP/SL、自动强平、持久化和 reset 都纳入目标，工作量不再是单一页面级“小修” |

## 1. 审计范围、方法与限制

### 1.1 范围

已扫描 fx-trading-platform 下全部有效项目文件，排除了 node_modules、dist、build、target、.git、日志、缓存、覆盖率目录、字体和编译状态文件。有效代码主体约 1,052 个文件，其中：

| 区域 | 有效文件概况 | 审计重点 |
|---|---:|---|
| apps/web | 约 265 个源码/配置文件 | 用户端路由、终端、资产、订单、持仓、认证、主题、i18n、响应式、mock 数据 |
| apps/admin | 约 47 个源码/配置文件 | 运营后台路由、权限、真实/静态数据源、交易与资金操作、移动端和主题 |
| backend | 688 个文件 | 638 个 Java 文件、45 个 Flyway SQL、配置、测试与 OpenAPI |
| packages | 5 个 | shared-types 与 OpenAPI 生成类型 |
| scripts | 24 个 | 架构、contract、smoke 和 KLineCharts 产物检查 |
| 外层 KLineCharts | 仅审计实际集成点 | apps/web/vite.config.ts alias、dist/index.esm.js、src/index.ts 导出和 Web 端手写类型 |

指定文档仅审计：

fx-trading-platform/docs/superpowers/specs/2026-07-10-usdt-spot-perpetual-demo-scheme-a-design-and-implementation.md

未把 docs 下其他计划或报告当作需求来源。

### 1.2 方法

- 对全项目做文件、扩展名、路由、Controller、Mapper/Repository、Scheduler、WebSocket topic、配置开关、mock 引用、建表和迁移语义扫描。
- 深读用户主链的页面、hook、API adapter、Controller、Service、Entity/DTO、Repository、Flyway 和配置。
- 从 Web、Admin、Backend 三个独立方向交叉核对同一接口和数据字段。
- 以 Binance/OKX 的公开现货/永续页面语义作为结构基准，不要求像素级复制或照搬品牌。
- 所有完成度均是静态代码完成度，不等于运行验收结果。

### 1.3 限制

按用户要求，本次没有执行 npm/maven 测试、构建、架构验证、服务启动、Docker、数据库迁移或浏览器 E2E。因此：

- 报告可以确认“代码存在、调用链可追踪、配置如何声明”，不能声称“当前构建通过、迁移成功或运行闭环通过”。
- 对 UI 的判断来自组件/CSS/现有原型资产和调用链，不是本轮实际浏览器截图验收。
- 对数据库的判断来自 V1–V45 Flyway 文件，不是查询某个现存本地数据库实例。

## 2. 当前架构、技术栈和启动方式

### 2.1 项目结构

| 目录 | 定位 |
|---|---|
| fx-trading-platform/apps/web | 用户端 React 应用，PC/H5 共用 |
| fx-trading-platform/apps/admin | 运营后台 React 应用 |
| fx-trading-platform/backend | Spring Boot 模块化单体 |
| fx-trading-platform/packages/shared-types | OpenAPI 生成和共享类型 |
| fx-trading-platform/infra/docker-compose.yml | PostgreSQL 16、Redis 7 |
| fx-trading-platform/scripts | contract、smoke、架构和视觉验证脚本 |
| 外层 src、dist | KLineCharts 库源码与构建产物 |

### 2.2 前端技术栈

- Web：React 19、React Router 7、TypeScript 5.8 strict、Vite 7、Zustand 5、i18next/react-i18next、@stomp/stompjs、Lucide。
- Admin：React 19、React Router 7、TypeScript 5.8、Vite 7、Lucide。
- KLineCharts：apps/web/vite.config.ts:7-9 把 klinecharts alias 到外层 ../../../dist/index.esm.js；scripts/ensure-klinecharts-dist.mjs:6-11 在 Web build 前检查产物。
- Web 使用路由懒加载；Admin 当前全部静态 import。

### 2.3 后端技术栈

- Java 21、Spring Boot 3.5.7。
- Spring MVC、Security、Validation、WebSocket/STOMP、Redis、Actuator。
- PostgreSQL + Flyway。
- MyBatis-Plus 3.5.12；58 个 Mapper 继承 FxBaseMapper，不存在 JPA repository。
- JWT/JJWT 0.12.6、springdoc OpenAPI 2.8.17。
- JUnit + Testcontainers。
- FxPlatformApplication.java 全局启用 scheduling 和 MapperScan。

### 2.4 已定义的启动与验证命令

这些命令来自仓库，但本轮均未执行：

    docker compose -f fx-trading-platform/infra/docker-compose.yml up -d
    cd fx-trading-platform/backend
    mvn test
    mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
    npm.cmd --prefix fx-trading-platform/apps/web run dev
    npm.cmd --prefix fx-trading-platform/apps/admin run dev
    npm.cmd --prefix fx-trading-platform/apps/web run build
    npm.cmd --prefix fx-trading-platform/apps/admin run build
    npm.cmd --prefix fx-trading-platform run verify:architecture

端口：Web 5173，Admin 5174，Backend 8080，PostgreSQL 5432，Redis 6379。

### 2.5 运行模式

- application.yml:103-124 默认 execution.mode=disabled，pending/protective/funding/liquidation 等交易 scheduler 默认 false。
- application-dev.yml:17-26 把 execution.mode 改为 demo，并默认启用 market.test-data。
- application-prod.yml 保持 execution disabled、demo quote/test-data 关闭。
- SimulatedExecutionAdapter 仅在 execution.mode=demo 创建；broker/fix/lp adapter 是明确抛错的占位实现，真实交易完成度为 0%，符合本项目边界。
- 风险：ProviderInstrumentSyncScheduler、WalletReconciliationJob、WalletDailySnapshotJob、HomeCountersService 等仍存在默认调度；特别是 HomeCountersService 每秒随机增加用户数，不应作为可信产品数据。

## 3. 当前页面路由与信息架构

### 3.1 用户端路由

路由证据：apps/web/src/app/App.tsx:31-53。

| 路由 | 页面/组件 | 当前判断 |
|---|---|---|
| / | HomePage | 已实现，但需要清理静态行情、奖项和品牌宣称 |
| /trade | Navigate → /trading | 可以保留兼容 |
| /trading | TradingPage | 核心复用页；当前桌面偏 Spot，移动偏硬编码 Perp |
| /markets | MarketsPage | 市场列表与合约看板可复用，分类和排行需修正 |
| /orders | OrdersPage | 订单/事件/撤单/改单可用，“成交”不是独立 trade |
| /positions | PositionsPage | 当前/历史、整仓平仓、TP/SL 可用 |
| /wallet | WalletPage | summary、wallet、asset ledger、fund order、conversion |
| /dashboard | DashboardPage | 账户摘要可用 |
| /login、/register | LoginPage、RegisterPage | 接真实 auth API |
| /forgot-password | ForgotPasswordPage | 仅本地成功态，无后端 |
| /two-factor-help | TwoFactorHelpPage | 仅本地成功态，无后端 |
| /account/overview | AccountOverviewPage | 部分实现 |
| /account/assets | AccountAssetsPage | 部分实现，与 Wallet 信息重复 |
| /account/orders/funding | FundingRecordsPage | 有页面和数据链，但 funding 不是本期真实结算目标 |
| /account/orders/trades | TradeOrdersPage | 实际缺用户 trade API |
| /account/security/kyc | KycPage | coming soon |
| /account/settings | AccountSettingsPage | 静态 |
| /security | SecurityCenterPage | 大量静态/禁用 |
| /settings | SettingsPage | UI 完整但偏好未被交易逻辑消费 |

建议不新增第二套交易 Page。增加 /trade/spot/:symbol? 与 /trade/perpetual/:symbol? 两个语义路由，继续渲染 TradingPage，并保留 /trading 兼容重定向。

### 3.2 Admin 路由

路由证据：apps/admin/src/app/AdminApp.tsx:29-85；菜单：adminMenu.ts:14-98。

Admin 已有 9 个主导航组、29 个菜单项和大量真实 CRUD；但最关键的以下页面只能直达、未进入主导航：

- /accounts → AccountsPage
- /trading/orders → OrdersPage
- /trading/positions → PositionsPage
- /trading/trades → TradesPage
- /market/status → MarketStatusPage
- /risk → RiskPage
- /audit-logs → AuditLogsPage

相反，/system/users 和 5 个 /config/settings/* 页面通过 GET /api/admin/features/{pageKey} 展示截图目录/静态样例，不是可靠业务数据。最小调整应先修菜单和数据源，不是新建后台。

## 4. 用户端前端完成情况

### 4.1 整体完成度

- 基础架构、主题、路由、行情、账户会话：约 70%。
- 现货交易 UI 与交互：约 65%–70%。
- 永续交易 UI 与交互：约 25%–35%。
- 移动端核心交易可操作性：约 40%。
- 真实/模拟数据可信边界：约 40%。
- Web 综合：约 53%。

### 4.2 正规交易所结构对照

| 区域 | 状态标签 | 代码证据与判断 |
|---|---|---|
| 顶部导航栏 | 已实现，但需要优化 | AppShell.tsx:65-122 有导航、语言、主题、钱包；搜索/通知/帮助/下载按钮无行为 |
| 行情菜单 | 已实现，但需要优化 | MarketsPage、TradingNavMenu、MarketSidebar 有列表/搜索/收藏；Spot/Perp 分类不可靠 |
| 现货交易入口 | 部分实现 | /trading 实际桌面固定 Spot/Cash，但无明确 /spot 路由 |
| 合约交易入口 | 完全缺失 | 没有独立 Perp 路由或产品模式入口 |
| 资产入口 | 已实现，可以直接复用 | /wallet 与 /account/assets 已接账户/wallet/ledger |
| 订单入口 | 已实现，但需要优化 | /orders 支持查询、事件、撤单、改单；trade tab 定义不准确 |
| 用户中心 | 部分实现 | 总览/资产可用；KYC、安全、设置断链或静态 |
| 登录和注册 | 已实现，但需要优化 | auth API 已接；注册缺验证码、条款、密码强度 |
| 语言切换 | 部分实现 | zh-CN/en-US/ja-JP 框架存在，但大量硬编码文本 |
| 主题切换 | 已实现，可以直接复用 | ThemeProvider 提供 dark binance-inspired 与 light minimal-white |
| 响应式布局 | 部分实现 | 全局、表格、Markets 已响应式；移动交易核心是另一套静态语义 |
| 交易对信息区 | 已实现，但需要优化 | SymbolHeader/Ticker 有 symbol、价格、涨跌、高低、量、source；桌面写死 Spot/Cash |
| 价格和涨跌 | 已实现，可以直接复用 | quote snapshot + STOMP |
| K 线 | 已实现，可以直接复用 | KLineChartPanel + ChartWorkspace，功能最成熟 |
| 深度图 | 完全缺失 | 有 OrderBook 深度列表，没有独立深度曲线图；可列 P1 |
| 买卖盘口 | 已实现，但需要优化 | OrderBook + backend cache/router；缺逐能力来源标识 |
| 最新成交 | 已实现，但需要优化 | RecentTrades；可能是后端 market trades，也可能由前端合成 |
| 下单面板 | 已实现，但需要优化 | TradePanel、OrderFormSide、useTradeForm；产品语义未打通 |
| 可用余额 | 部分实现 | 真实余额存在，但 TradePanel 还把 useMockBalances 填在真实余额下层 |
| 百分比仓位按钮 | 已实现，可以直接复用 | 0/25/50/75/100 滑杆 |
| 当前委托 | 已实现，但需要优化 | BottomAccountPanel/OrdersPage；终端底部缺直接撤单/改单 |
| 历史委托 | 已实现，可以直接复用 | orders + status filter |
| 成交历史 | 部分实现 | 当前只是 FILLED/PARTIALLY_FILLED 订单筛选，无独立用户 fills/trades API |
| 资产信息 | 已实现，但需要优化 | WalletPage 和 BottomAccountPanel；存在 mock fallback 与重复信息架构 |
| 开仓/平仓模式 | 完全缺失 | payload 无 open/close intent；只能靠反向净持仓推断 |
| 买入做多/卖出做空 | 可以通过模拟数据实现 | 移动端有静态按钮，后端净持仓可复用；需接真实 product/positionSide |
| 杠杆设置 | 部分实现 | TradePanelLeverageControls 存在但未挂载；当前取 rules.maxLeverage |
| 保证金模式 | 完全缺失 | UI 没有有效 Cross/Isolated；后端所有非 Spot 响应硬编码 CROSS |
| 仓位信息 | 已实现，但需要优化 | BottomAccountPositionsGrid 字段较全 |
| 未实现盈亏 | 已实现，但需要优化 | 后端/前端均有计算；目前主要靠 2 秒全量刷新 |
| 收益率 | 已实现，但需要优化 | floatingPnlRatio 已返回；应统一显示为百分比并注明模拟 |
| 强平价格 | 部分实现 | 有展示字段，但“保证金归零”显示公式与实际 MM+fee 强平 gate 不一致 |
| 仓位止盈止损 | 部分实现 | /positions 可修改；下单策略 tab 有断链 |
| 委托列表 | 已实现，但需要优化 | 产品、方向、reduceOnly、positionSide 等字段不全 |
| 持仓列表 | 已实现，但需要优化 | 只有净持仓语义；两个“平仓/全平”按钮调用相同 handler |

### 4.3 KLineCharts 复用判断

K 线是当前最不应该重写的模块：

- KLineChartPanel.tsx:1 使用 init/dispose。
- KLineChartPanel.tsx:235-514 完成初始化、locale/timezone、主题、history、realtime bar、crosshair、指标和画线。
- ChartWorkspace.tsx:46-285 提供 toolbar、drawing、fullscreen、快捷键和配置弹窗。
- chartSettings.ts:238-520 提供 19 个周期、多种图形、普通/%/log 轴、时区与指标。
- chartTradeMarkers.ts:50-105 已支持入场、挂单、TP、SL、强平标记。
- chartDrawingPersistence.ts:20-56 已按 symbol 本地保存画线。

只需优化：

- realtime bar 的 volume 不能固定为 0。
- OI/多空/Taker 等由 candle 派生的指标必须标注 SIMULATED。
- alias 仍可保留，但应让 build/CI 明确生成或校验外层 dist，避免手写 vite-env.d.ts 与库 API 漂移。

### 4.4 当前最危险的前端 mock 混用

| 位置 | 当前行为 | 风险 | P0 处理 |
|---|---|---|---|
| TradingPage.tsx:119-139 | 先合并 mockTradingMarkets，再用后端同名 symbol 覆盖 | 后端不存在的 symbol 仍可见 | 登录/交易态只显示后端发布且 tradable 的 symbol |
| MarketsPage.tsx:122,159-195 | 初始与失败均回退 mock 市场 | 无法区分断网与真实行情 | 后端统一 fallback，前端显示 source badge |
| quoteMarketDataAdapter.ts:67-70 | quote REST 失败生成本地 snapshot | 本地价格可能被当作可交易价格 | 前端 fallback 禁止下单；可交易报价只来自后端 |
| quoteMarketDataSnapshot.ts:7-54 | 缺 depth 时生成 42 档和最近成交 | LIVE quote + SIMULATED depth 被混成一个“实时”页面 | quote/depth/trades/candles 分能力标 source |
| TradePanel.tsx:68-69 | {USDT:10000,BTC:0.25} 填补真实余额缺项 | 可显示并使用不存在的 BTC 最大卖出量 | ready 后完全移除 mock balance 合并 |
| bottomAccountPanelData.ts:64-224 | 缺数据时补 mock account/order/position/ledger/strategy | 游客/加载态像真实持仓 | 改 skeleton、empty state、demo seed 明示 |
| MobileTradingTerminal.tsx:43-127 | 所有产品写死 Perpetual/Cross/100x/7.34 | 桌面与移动语义冲突 | 复用 TradePanel/store 和后端 settings |

## 5. Admin 前端完成情况

Admin 综合产品级完成度约 45%。可复用的是架构和大量真实 API，不应按交易所用户端视觉去复制 Binance/OKX；它应接近可信运营控制台。

### 5.1 可直接复用

- AdminLayout.tsx:82-203：固定侧栏、折叠、面包屑、多标签、全屏、退出、skip link。
- RequireAdmin、adminToken、apiClient：登录、Bearer、401 refresh/replay、错误 envelope。
- PageHeader、StateBlock、DataTable、AdminPageTable。
- provider/instrument/binding 管理：测试、同步、health、latency、staleness、capability、发布 symbol、display、priority。
- RBAC、symbol、finance ledger、fund order、content、audit 等真实 API mapping。
- 后端已经存在但 UI 未接的用户风控、余额调整和 force-close 接口。

### 5.2 需要小幅修改

1. 把 /accounts、/trading/orders、/trading/positions、/trading/trades、/market/status、/risk、/audit-logs 加回 adminMenuGroups。
2. /system/users 改接真实数据或暂时隐藏；5 个 settings 页面读取真实 /api/admin/config/settings。
3. FeatureCrudPage.tsx:149-155,536-570 的 confirm 行为必须真的弹确认；当前很多删除、撤单、价格调整取消会单击即执行。
4. 接通已有 /api/admin/trading/positions/{id}/force-close、/api/admin/finance/accounts/{id}/deposit|withdraw|adjustments。
5. 增加 Demo reset、wallet balances、asset ledger 的 Admin API/UI。
6. 订单/持仓/成交 DTO 增加 productType、positionSide、marginMode、systemReason 等列，按 Spot/Perp 筛选。
7. Admin 增加深色主题和移动 drawer；当前只有浅色，手机端 29 项菜单占满首屏。

### 5.3 不适合直接保留

- AdminFeatureCatalogService 和 /api/admin/features/{pageKey} 的固定截图样例不能充当真实用户/配置数据。
- import/export/batch 当前只创建 QUEUED 记录、没有上传/worker/下载；MVP 应隐藏，不要伪装完成。
- AdminDashboard.tsx、UsersPage.tsx 等未接路由的第二套页面不应继续扩展。

## 6. 后端当前模拟能力

### 6.1 用户订单主链

实际入口：TradingController.java:31-121。

| 方法/接口 | 当前实现 |
|---|---|
| POST /api/trading/orders | OrderService.createOrder/createNewOrder；幂等、风控、锁资或立即执行 |
| GET /api/trading/orders | 当前用户全部订单 |
| GET /api/trading/orders/{orderId}/events | OrderEventService |
| POST /api/trading/orders/{orderId}/cancel | 仅 PENDING，CAS 取消，释放 hold |
| PATCH /api/trading/orders/{orderId} | 仅 PENDING，重算价格/数量/保护价和 hold |
| GET /api/trading/positions?accountId= | PositionService 当前仓位 |
| GET /api/trading/positions/history?accountId= | 历史仓位 |
| PATCH /api/trading/positions/{id}/protection | 修改 TP/SL |
| POST /api/trading/positions/{id}/close | 手动整仓平仓 |

当前缺少用户自己的 GET /api/trading/trades?accountId=。trading.trades 和 TradeRepository.findByAccountIdOrderByExecutedAtDesc 已存在，新增接口不需要迁移。

### 6.2 MARKET 实际逻辑

OrderService.createNewOrder（OrderService.java:163-215）：

1. 规范 symbol、校验账户/产品/规则/余额和新鲜报价。
2. MARKET 保存为 accepted 状态。
3. SimulatedExecutionAdapter.execute（SimulatedExecutionAdapter.java:55-77）按 BUY ask、SELL bid，固定 1bp slippage、10bp fee，单次全量成交。
4. OrderFillService.fill 更新订单、插入 trading.trades。
5. Spot 进入 SpotSettlementService；非 Spot 进入 PositionEngine。

这已经符合本项目“市价单立即全量成交”的目标，可直接复用。需要补的是统一 DemoExecutionGuard、同一报价快照和一致的 fee/slippage 计算。

### 6.3 LIMIT/STOP 实际逻辑

PendingOrderExecutionService：

- 默认每 1 秒扫描，但 trading.pending-order-execution-enabled 默认 false。
- LIMIT BUY：ask <= limit；LIMIT SELL：bid >= limit。
- STOP 为反向条件。
- 下单时 Spot BUY 锁 quote、SELL 锁 base；撤单/改单释放或重建 hold。
- 触价后 CAS PENDING → WORKING，再 full fill。

主要问题：

- 扫描整体处于一笔事务，一个订单异常可能影响整批。
- 触价成交绕过 SimulatedExecutionAdapter，订单 fee/slippage 字段可能仍为 0，而 SpotSettlementService 又按默认费率实际扣钱包。
- 只扫描 PENDING；虽然枚举有 PARTIALLY_FILLED，本项目并无部分成交生产链。

建议继续坚持 full-fill-only；每笔订单使用独立事务，触发后调用同一 LocalDemoFillCalculator/ExecutionCoordinator。

### 6.4 现货结算能力

SpotSettlementService.java:40-248 与 SpotPositionService.java:23-68 已经完成：

- 买入扣 quote、增加 base、以 base 扣 fee。
- 卖出扣 base、增加 quote、以 quote 扣 fee。
- pending hold 的 consume/release。
- core.wallet_balances 的 total/available/locked。
- ledger.asset_ledger_entries。
- trading.spot_positions 的 quantity、average_cost、realized_pnl、fee_cost。

这是现货 MVP 的核心复用资产，不应另建一套 Spot 余额或“虚拟撮合”表。

### 6.5 持仓、保证金、PnL 与强平能力

- PositionEngine.applyFill/increase/reduce/reverse（PositionEngine.java:74-214）实现每 account+symbol 的单一净仓，同向加仓、反向减仓/平仓/反手。
- PerpMarginCalculator.java:14-56 已计算 linear/inverse notional、initial margin、maintenance margin。
- TradingAlgorithmEngine.java:32-129 已计算保证金、linear/inverse PnL、fee、ROI 和简化 liquidation price。
- PositionService.java:101-169,321-357 返回 mark、current、notional、liquidation、break-even、floating/realized/funding PnL、margin、MMR。
- ProtectiveOrderExecutionService 每秒扫描 TP/SL，但默认关闭，当前直接调用 closeSystemPosition。
- LiquidationService 已有 FX stop-out 和 perp account-level MM+liquidation fee gate；scheduler 默认关闭。
- FundingService/FundingSettlementScheduler 已有表和结算骨架，但没有可靠 provider ingestion，本期不应把真实资金费结算作为 P0。

缺口：

- 无 position_mode、position_side，不能 hedge。
- 所有非 Spot marginMode 都由 PositionService.java:537-539 硬编码 CROSS。
- 无 isolated 资金池、追加/减少逐仓保证金。
- 无 reduceOnly、open/close intent、用户可调杠杆 API。
- 手动平仓、TP/SL、强平直接改 Position/Account/Ledger，不产生标准 closing Order、Trade、fee、slippage、OrderEvent。
- markPrice 多数退化为 spot bid/ask mid；Binance futures dashboard 只供展示，不参与交易/强平。

### 6.6 账户和资金真值问题

AccountService.createDemoAccount（AccountService.java:80-112）当前同时：

1. 把 core.trading_accounts.balance/equity/free_margin 设为 10,000 USD；
2. 向 FX_MARGIN/USD wallet 再 credit 10,000；
3. 向 SPOT/USDT wallet 再 credit 10,000。

这不是同一笔 10,000 的子账户拆分，而是三份可见资金口径。后台 deposit/withdraw/adjustment 只改 trading_accounts + cash ledger；AssetConversionService 只改 wallet + asset ledger；WalletReconciliationService 也只是分别校验两套账，没有跨体系核对。

最小处理建议：

- P0 明确“SPOT wallet 是现货真值；trading_accounts 是 USDT 永续 cross 账户真值”。
- Demo 初始化按配置分别给 Spot USDT 与 Perp USDT 演示资金，页面明确两类钱包；不要再把 FX_MARGIN 余额当作第三份总资产。
- 总资产只是两个子账户的展示汇总，不创建第三份可支配余额。
- Reset 在同一事务中重置这两类真值，并写 account ledger + asset ledger。

这比在本期把所有 PositionEngine 写路径迁移到 USDT_PERP wallet 更小，也避免镜像同步。

### 6.7 认证、权限与 WebSocket

- SecurityConfig.java:59-65：注册/登录/refresh/session、/ws、health/docs、GET market/chart/public 放行；/api/admin/** 需 ADMIN；其余认证。
- JWT access/refresh、session、revoked token 和 refresh rotation 已持久化，可复用。
- MarketWebSocketConfig.java:34-44：simple broker /topic,/queue，endpoint /ws。
- market topic：/topic/market/quotes/{symbol}、order-book、trades。
- account topic：/topic/trading/accounts/{accountId}/events。

严重缺口：WebSocketJwtChannelInterceptor 允许无 token CONNECT，也没有 SUBSCRIBE 授权；知道 UUID 的匿名连接可尝试订阅他人账户事件。P0 至少要拒绝未认证账户 topic，并校验 accountId 属于 principal。行情 topic 可继续匿名。

## 7. 行情、外部接口与离线模拟

### 7.1 当前来源

| 来源 | 当前能力 | 判断 |
|---|---|---|
| Massive REST | FX symbol/quote/snapshot/candle | 可保留，但不是目标 USDT 主源 |
| Binance Spot REST | symbol/quote/snapshot/candle/orderbook/trades | Spot 外部主源候选 |
| Binance Spot WS | quote/candle/orderbook/trades | 已接 RealtimeQuoteSink/STOMP |
| OKX Spot REST | quote/candle/orderbook/trades | 默认 disabled，可作次选 |
| Binance Futures REST proxy | 24H、OI、ratio、basis、funding dashboard | 只供 Markets 展示，不能当 Perp 可执行/mark provider |
| DemoMarketDataGenerator | quote/orderbook/trades/candles | 离线兜底核心，可直接复用 |
| MarketTestDataService | 默认 dev 每秒生成并写 sink | 可直接复用，但 source 必须明确 |
| V11 历史 Demo K 线 | 无 profile 条件的大量 seed | 应停止作为所有环境默认事实 |

ProviderResolver、MarketDataRouter、market.data_providers、capabilities、provider_instruments 和 symbol_provider_bindings 已构成可复用的 provider 管理骨架。

### 7.2 当前问题

- ProviderResolver 只返回首个 binding；首 provider empty/exception 后 MarketDataRouter 不尝试下一候选。
- MarketDataRouter.snapshots(...) 仍返回空 Map。
- QuoteService.status() 固定返回 connected/ready，不能代表真实健康。
- QuoteService 的 crypto demo fallback 价格模型偏 FX，不能直接作为 BTC/ETH 本地基准。
- 前端又有一套自动本地 fallback，导致后端外部 quote、前端合成 depth、seeded candles 和 mock balances 可以在同一屏混用。

### 7.3 最适配的简单方案

选择 **方案一 + 方案二 + 后端版方案四**，前端版方案三只保留非交易性视觉细节：

1. 市价单由后端立即全量成交。
2. 限价单由后端按当前权威 bid/ask 立即判断；不满足则 PENDING，由 1 秒 scheduler 或价格事件检查。
3. quote、depth、trades、candles 均优先外部公共行情；外部失败/过期后，由后端按 symbol 的 OfflineMarketProfile 生成整套一致数据。
4. 前端只能做 loading skeleton、动画插值或非关键装饰，不能自行生成可交易 price、balance、order、position 或 PnL。
5. 每种能力返回 source、dataMode、asOf、stale；交易按钮只接受后端 fresh quote。

推荐自动状态机：

    EXTERNAL_LIVE
      ├─ success/fresh → 继续使用外部 bundle
      └─ 连续失败或 stale → LOCAL_SIMULATED
                             └─ 外部连续恢复 → EXTERNAL_LIVE

切换必须按 symbol 的完整 bundle 完成，不能只换 quote 而保留另一来源的 depth/candles；页面一直显示“实时行情”或“模拟行情”徽标。该方案满足用户“离线可演示”，也比指定文档要求 PUBLIC/OFFLINE 手工硬切更贴合本阶段。

## 8. 当前数据库

Flyway 共 V1–V45、59 张实际表。

### 8.1 按 schema 全量表清单

| Schema | 表 |
|---|---|
| auth（6） | users、user_devices、user_profiles、kyc_applications、revoked_tokens、user_sessions |
| core（6） | trading_accounts、wallet_balances、wallet_daily_snapshots、account_daily_snapshots、home_counters、home_promo_cards |
| market（10） | symbols、candles、symbol_categories、symbol_admin_events、price_adjustments、user_favorite_symbols、data_providers、data_provider_capabilities、provider_instruments、symbol_provider_bindings |
| trading（10） | orders、trades、positions、order_events、spot_positions、funding_rates、funding_settlements、fx_conversion_rates、fx_financing_rates、fx_financing_settlements |
| ledger（2） | ledger_entries、asset_ledger_entries |
| risk（1） | risk_configs |
| audit（3） | audit_logs、request_logs、verification_code_logs |
| admin（13） | user_notes、feature_records、roles、menus、role_menu_permissions、user_roles、departments、posts、role_data_scopes、table_column_preferences、export_tasks、import_tasks、batch_operations |
| finance（4） | payment_methods、admin_fund_operations、fund_orders、member_payment_accounts |
| config（2） | system_dictionaries、system_settings |
| content（2） | messages、articles |

### 8.2 与 MVP 直接相关的表

| 表 | 可复用程度 | 最小用途 |
|---|---|---|
| auth.users、auth.user_sessions、auth.revoked_tokens | 直接复用 | 注册、登录、session、刷新和注销 |
| core.trading_accounts | 需小改 | Demo Perp cross 账户、position_mode、重置版本 |
| core.wallet_balances | 直接复用 | Spot 可用/冻结/总额；可显示 wallet_type |
| ledger.ledger_entries | 直接复用 | Perp margin/PnL/fee/liquidation/reset |
| ledger.asset_ledger_entries | 直接复用 | Spot debit/credit/lock/release/fee/reset |
| market.symbols | 需小改/补数据 | seed BTCUSDT 等 Spot 与 BTCUSDT-PERP 等 Perp |
| market.data_*、symbol_provider_bindings | 直接复用 | 外部与本地 provider 路由 |
| market.candles | 直接复用 | K 线历史 |
| trading.orders | 需增量字段 | Spot/Perp 统一委托和系统平仓单 |
| trading.trades | 需增量字段 | 用户成交、费用、系统原因 |
| trading.positions | 需增量字段 | Perp slot、margin mode、position side |
| trading.order_events | 直接复用 | 状态和模拟强平/TP-SL 事件 |
| trading.spot_positions | 直接复用 | Spot 平均成本、数量、realized/unrealized |
| trading.funding_* | P2 保留 | 本期只展示模拟 rate，不做真实资金费结算 |

### 8.3 迁移与数据问题

- V27 只 seed BTCUSDT、ETHUSDT、SOLUSDT、XRPUSDT，V37/V38 把它们归类 CRYPTO_SPOT；没有正式 BTCUSDT-PERP/ETHUSDT-PERP。
- V11 无 profile 条件插入两年 Demo K 线。
- V31 seed 321,443,508 用户、巨额资产和 24H 成交额，并由 HomeCountersService 每秒随机增长；这只能视为原型展示，不能作为可信运营指标。
- orders 同时保留旧 (user_id,idempotency_key) 与新 (user_id,account_id,client_order_id) 唯一约束。
- funding/financing settlement 的 position/account/ledger ID 缺 FK。
- 没有“每用户一个 active Demo account”约束，也没有 open position slot 唯一约束。
- verification_code_logs 存明文 code，不应在正式演示环境长期保留。

## 9. 可直接复用、需小改和需新增

### 9.1 可直接复用的代码

| 层 | 文件/类/方法 | 复用理由 |
|---|---|---|
| Web 壳层 | apps/web/src/app/AppShell.tsx、App.tsx | 顶部导航、移动底栏、主题/语言入口、路由懒加载 |
| 桌面终端 | TradingPage.tsx、TradingDesktopView.tsx、TradingWorkspace.tsx | ticker、watchlist、chart、right panel、trade panel、bottom account、resize/drag |
| K 线 | KLineChartPanel.tsx、ChartWorkspace.tsx、chartSettings.ts | K 线、指标、画线、全屏、周期、标记、导出 |
| 盘口/逐笔 | OrderBook、RecentTrades、tradingMarketAdapters.ts | 组件和 REST/STOMP 接入已存在 |
| 现货表单 | TradePanel.tsx、OrderFormSide.tsx、useTradeForm.ts、tradePanelMarket.ts | LIMIT/MARKET、BUY/SELL、比例、规则校验、确认 |
| 会话 | useTradingSession.ts、tradingSession.ts | account/orders/positions/ledger/wallet 七组数据和账户事件框架 |
| 订单/仓位/资产 | OrdersPage.tsx、PositionsPage.tsx、WalletPage.tsx | CRUD、保护价、整仓平仓、资产和流水页面已接后端 |
| Web 设计系统 | ThemeProvider.tsx、themes.ts、i18n/index.ts、DataTable.tsx | 深浅主题、三语言框架、桌面/移动表格 |
| Admin 壳层 | AdminLayout.tsx、RequireAdmin、apiClient、DataTable、FeatureCrudPage 骨架 | 登录、刷新、布局、分页筛选基础可保留 |
| Admin 行情运营 | DataProvidersPage、ProviderInstrumentsPage、SymbolDataBindingsPage | provider health/test/sync/binding/display 主链最成熟 |
| MARKET 成交 | OrderService.createNewOrder、SimulatedExecutionAdapter.execute、OrderFillService.fill | 已是本地单次全量成交 |
| PENDING 订单 | OrderService.cancel/modify、PendingOrderExecutionService | hold、触价、CAS、撤单和改单基础 |
| Spot 资金 | SpotSettlementService、WalletService、SpotPositionService | 现货余额、冻结、资产流水、平均成本和 PnL |
| Perp 算法 | PositionEngine、PerpMarginCalculator、TradingAlgorithmEngine、PnLCalculator | 净持仓、IM/MM、linear PnL、ROI |
| 保护/强平 | PositionService、ProtectiveOrderExecutionService、LiquidationService | 保护价、整仓平仓和强平 gate 骨架 |
| 行情 | ProviderResolver、MarketDataRouter、QuoteService、ChartService、RealtimeQuoteSink | provider 管理、cache、candle、REST/WS |
| 离线生成 | DemoMarketDataGenerator、MarketTestDataService | 本地 quote/orderbook/trades/candles 生成能力 |
| WS | MarketWsPublisher、TradingWsPublisher、MarketWebSocketConfig | 市场和账户 topic 已存在 |
| 数据库 | V1–V45 的核心表 | 不需要重建 schema；只做一份增量迁移 |

### 9.2 需要小幅修改的代码

#### Web

- apps/web/src/app/App.tsx：增加 Spot/Perp 语义路由。
- apps/web/src/pages/TradingPage.tsx：以 productMode + backend symbol profile 作为唯一产品判断，默认从 EURUSD 改到目标 USDT pair。
- TradingDesktopView.tsx、SymbolHeader.tsx、TradeTabs.tsx：不再写死 Spot/Cash，挂载 Perp controls。
- MobileTradingTerminal.tsx、TradingMobileView.tsx：移除固定 Cross/100x/7.34，复用桌面相同状态和提交链。
- TradePanel.tsx：移除真实态下的 useMockBalances 合并。
- orderAdapter.ts：保留 BTCUSDT-PERP canonical code；传 productType、positionSide、marginMode、reduceOnly、leverage、openCloseIntent。
- useTradeForm.ts、OrderFormSide.tsx：修复 TP/SL 策略断链，挂载 leverage/margin/position mode。
- BottomAccountPanel、BottomAccountOrdersGrid、BottomAccountPositionsGrid：终端内撤单、改单、平仓、TP/SL；区分 current orders、positions、trades。
- useTradingSession.ts：账户事件 debounce/single-flight；行情更新只局部更新 PnL，轮询降到 10–30 秒兜底。
- MarketsPage.tsx：真实 productType 分类，删除固定榜单/空 tab，Perp dashboard 跟随 symbol。
- WalletPage.tsx、AccountPages.tsx：统一 Spot/Perp 子账户视图和 reset。

#### Admin

- AdminApp.tsx、adminMenu.ts：把真实账户/交易/行情/风险/审计页面放入主导航。
- FeatureCrudPage.tsx：修复 confirm、loading、防双击、reason 和 audit result。
- adminApi.ts、types.ts：接 wallet、asset ledger、force-close、adjustment、reset，复用 OpenAPI generated types。
- styles.css、AdminLayout.tsx：dark theme、移动 drawer 和表格移动操作。

#### Backend

- OrderService、PendingOrderExecutionService、OrderFillService：统一 DemoExecutionGuard、报价快照、fee/slippage 和单笔事务。
- CreateOrderRequest/OrderCommand/OrderEntity/OrderResponse：增加产品和 Perp 语义。
- SymbolNormalizer：保留 -PERP 后缀；providerSymbol 只在 provider adapter 边界转换。
- PositionEngine/PositionRepository：在 one-way 基础上增加 hedge slot，不另建第二套引擎。
- PositionService：Cross/Isolated、positionSide、统一 system close。
- ProtectiveOrderExecutionService/LiquidationService/Admin force-close：调用同一个 SystemCloseOrderService。
- AccountService：修正初始化真值，限制一个 active Demo，增加 reset。
- ProviderResolver/MarketDataRouter/QuoteService：候选 provider、自动 fallback、真实 status/source/freshness。
- WebSocketJwtChannelInterceptor：账户 topic SUBSCRIBE 授权。
- 所有交易 scheduler：继续默认关闭，由 dev/demo profile 显式启用，且服务内再次检查 Demo guard。

### 9.3 需要新增的页面

**用户端 P0 不需要新增独立页面组件。** 最小方案是：

- 新增语义路由 /trade/spot/:symbol? 和 /trade/perpetual/:symbol?，都复用 TradingPage。
- 在现有 TradingPage 内增加 Spot/Perp 产品模式和永续控制条。
- 在现有 WalletPage 增加 Demo reset。
- 在现有 OrdersPage 增加真实 Trade History tab。

Admin 也不必新增完整页面；优先扩展 AccountsPage 的详情 drawer，包含 wallet、asset ledger、position、order、trade、reset。若后续运营功能增长，再把它拆成 /demo-accounts，列为 P1。

### 9.4 最小新增接口

| 接口 | 必要性 | 复用基础 | 是否需新表 |
|---|---|---|---|
| GET /api/trading/trades?accountId= | P0 | TradeRepository 已有 | 否 |
| GET /api/accounts/{id}/trading-settings | P0 | AccountService/Account entity | 需增账户字段 |
| PATCH /api/accounts/{id}/trading-settings | P0 | 同上；只允许无活动冲突时切 mode | 需增账户字段 |
| POST /api/accounts/{id}/demo-reset | P0 | Account/Wallet/Ledger/Order/Position services | 否，可用现有 ledger 幂等引用 |
| GET /api/market/perpetuals/{symbol}/reference | P0 | provider/router/cache | 可不新表 |
| 扩展 POST /api/trading/orders | P0 | 现有入口 | 需增订单字段 |
| 扩展 PositionResponse | P0 | 现有响应 | 需增持仓字段 |
| POST /api/trading/positions/close-all | P1 | SystemCloseOrderService | 可不新表 |
| GET /api/admin/accounts/{id}/wallet-balances | P0 Admin | WalletService | 否 |
| GET /api/admin/accounts/{id}/asset-ledger | P0 Admin | Asset ledger repository | 否 |
| POST /api/admin/accounts/{id}/demo-reset | P0 Admin | 同一 reset service | 否 |

不新增另一个“模拟行情 API 服务”，而是扩展现有 /api/market/** 响应的 source、dataMode、asOf、stale。

## 10. 指定改进文档逐项分析

指定文档共 24 个章节、Task 0–18、约 172 个实施检查项；规划了约 89 个新增后端路径、60 个重点修改后端路径、25 个前端修改路径、16 个新脚本和 V46/V47 两个大迁移。以下把细碎 checklist 合并为 19 个可决策需求组，逐项覆盖“改什么、当前实现、复用、前后端/数据库/模拟、是否超范围、最简替代和 MVP 判断”。

### 10.1 文档总体评价

值得保留的判断：

- 保留模块化单体，复用 OrderService/OrderFillService/SpotSettlementService/PositionEngine。
- canonical BTCUSDT-PERP，不把 provider symbol 当内部 symbol。
- MARKET/LIMIT 本地 full-fill-only，不引入部分成交和真实撮合。
- DEMO/LIVE 后端门禁、scheduler 默认关闭。
- account/wallet/ledger 真值、Order/Trade/Ledger 可审计。
- one-way/hedge、cross/isolated、reduce-only system close、TP/SL/liquidation/reset 是类交易所 Demo 的关键产品语义。
- 禁止 private exchange API、真实资金、保险基金、ADL。

需要收缩或改写：

- 文档要求 PUBLIC/OFFLINE 硬隔离且 PUBLIC 失败关闭；本轮用户明确要求外部可用时用外部、断网时自动本地兜底。应改为后端自动 fallback + 显眼 source badge，而不是前端静默 fallback。
- 真实 funding history/backfill/catch-up/settlement 明确不在本期目标；只显示模拟 funding rate 与倒计时。
- IOC/FOK、通用 STOP-MARKET、close-batch replay 不是 P0；P0 只需 MARKET、LIMIT、保护触发和 full-fill。
- runId、namespace、test-control provenance、migration reset fence、bankruptcy audit、financial-writer allowlist 属于生产级可验证工程，不应阻塞 Demo。
- 文档只安排约 25 个前端修改且几乎不触及 Admin 视觉/信息架构，和本轮“前端是主要目标、桌面/移动、Web/Admin 同等评估”不匹配。

### 10.2 Task 0–18 需求判断表

缩写：FE=前端，BE=后端，DB=数据库；“模拟”表示可在不接真实交易/资金系统的条件下完成。

| 文档任务 | 文档要求修改 | 当前实现与可复用 | FE/BE/DB/模拟 | 真实撮合/资金 | 过度设计与最简替代 | MVP |
|---|---|---|---|---|---|---|
| Task 0 工具链门禁 | 固定 Node 24、Java 21、Docker、Maven 并阻断后续任务 | package/pom/scripts 已定义；本轮未运行，无法确认主机状态 | 工程；非产品 | 否 | 完整环境 manifest 可留到实施首日；不属于功能 | P0 实施前置，但非产品 P0 |
| Task 1 V46/V47 大迁移 | 两个迁移加入产品、模式、TIF、close batch、exposure、provenance、约束 | V1–V45 已有 orders/trades/positions/wallet/funding；复用高 | BE+DB；可模拟 | 否 | 两个超大迁移风险高；用一个增量迁移加入最小字段、索引和 Perp seed | P0 收缩 |
| Task 2 领域枚举/合同 | product/mode/side/margin/reduceOnly/system reason/canonical alias | product_type 和前端模型部分存在；其余缺失 | FE+BE+DB；可模拟 | 否 | 必要；暂不加 IOC/FOK/post-only 等扩展 | P0 |
| Task 3 PUBLIC/OFFLINE 硬隔离 | 两种模式、transport 0 调用、reference/funding provider、runId | provider/router/demo generator 已有；自动候选 fallback 缺失 | FE+BE；少量 DB 配置；可模拟 | 否 | 与用户自动兜底冲突；改为 EXTERNAL_LIVE↔LOCAL_SIMULATED 自动状态机 | P0 改写 |
| Task 4 账户级写锁 | account-first 全写路径锁、allowlist 和并发 IT | Wallet/Account/Position 多数读改写，无统一行锁 | BE；可能 DB lock；可模拟 | 否 | 全量 writer audit 是中长期；P0 先覆盖下单、成交、撤单、平仓、reset 五条资金写路径 | P0 底线收缩 |
| Task 5 全量成交协调器 | 抽取单次 full-fill 计算和统一协调器 | SimulatedExecutionAdapter + OrderFillService 高复用 | BE；无新 DB；可模拟 | 否 | 必要且简单；不需要撮合队列 | P0 |
| Task 6 MARKET/LIMIT/STOP + TIF | GTC/IOC/FOK、hold、改单、scheduler、reset fence | MARKET/LIMIT/STOP、hold、cancel/modify 已有 | FE+BE+DB；可模拟 | 否 | P0 仅 MARKET+LIMIT+隐式 GTC；保护触发由 TP/SL 服务承担，IOC/FOK 后置 | P0 收缩 |
| Task 7 one-way/hedge/reduce-only | account position mode、LONG/SHORT/BOTH slot、模式切换约束 | PositionEngine 已有 one-way 净仓；hedge 缺失 | FE+BE+DB；可模拟 | 否 | 用户明确要求两种模式，不能删除；在现有 engine 中选 slot | P0 |
| Task 8 cross/isolated | 分离风险池、保证金、预计强平价 | Cross 骨架和 marginHeld 已有；marginMode 写死 CROSS | FE+BE+DB；可模拟 | 否 | 用户明确要求两种；仅做单币 USDT cross 和 position-level isolated，不做 portfolio/tier | P0 |
| Task 9 system close/TP-SL/close-all | 所有平仓生成系统订单、成交、费用、事件 | 手动/TP-SL/强平直接改仓；保护扫描已存在 | FE+BE+少量 DB；可模拟 | 否 | system close 是审计闭环必要；close-all 可 P1，单仓 TP/SL 是 P0 | P0/P1 |
| Task 10 funding | provider history、backfill、catch-up、requested/applied/unpaid、结算历史 | funding 表/服务/scheduler 已有，但无 ingestion | FE+BE+DB；模拟可做 | 涉及类似真实结算 | 本轮明确不需要真实结算；仅模拟 rate/countdown，不扣资产 | P2（展示 P1） |
| Task 11 liquidation | isolated/cross full-slot liquidation，经 system order | LiquidationService 和 ledger fee 已有 | FE+BE；可模拟 | 否 | 保留简化触发；不做 bankruptcy debt、insurance、ADL | P0 |
| Task 12 Demo 初始化/reset | 单 active Demo、Spot/Perp 资金、事务 reset、审计 | 自动开户已有但三份余额；reset 缺失 | FE+BE+DB 约束；可模拟 | 否 | 必要；不用 close_batch/exposure 新表，保留历史、重置当前态 | P0 |
| Task 13 实时订阅/canonical alias | Perp polling、binding/reference bundle、前端 alias | Spot WS/STOMP 已有；-PERP 会被 orderAdapter 去符号 | FE+BE；可模拟 | 否 | 不必复杂 generationId；一个后端 bundle version/asOf 足够 | P0 |
| Task 14 OpenAPI/shared types/API clients | 更新 contract、生成类型、启动后端验证 | shared-types/OpenAPI 脚本已有；Web/Admin 仍有手写 DTO | FE+BE；无 DB | 否 | 直接复用现有 contract pipeline | P0 实施保障 |
| Task 15 表单接通 | 产品、order、position、margin、leverage 控件 | Spot 表单成熟，leverage 控件死代码，payload 缺字段 | FE 为主+BE contract；可模拟 | 否 | 必要；不加全部高级订单 | P0 |
| Task 16 持仓/funding/reset/session | close-all、funding、reset、刷新节流 | 持仓/资产/session 已有；七请求每 2 秒且 WS 也全量刷新 | FE+BE；可模拟 | 否 | Funding history 后置；debounce + 10–30 秒轮询即可 | P0/P1 |
| Task 17 确定性 smoke/DB 门 | offline/public browser E2E、fresh DB、network allowlist、writer allowlist 归零 | scripts 已有多条 smoke；但本轮未执行 | 工程+测试 | 否 | 完整矩阵可阶段化；P0 至少保留一条 Spot、一条 Perp、一条 reset 闭环 E2E | 实施验收 P0 |
| Task 18 全量发布检查 | 全命令、helper、文档、发布/回滚 | 现有 scripts/README 可复用 | 工程+文档 | 否 | 不需本期生产级发布编排；做本地 demo runbook 即可 | P1 |

### 10.3 文档中的主要过度设计

以下内容不是“错误”，但不应放入当前 MVP：

- 完整 funding history/backfill/catch-up、closed-isolated delayed settlement。
- requested/applied/unpaid funding 和复杂负余额 clamp。
- close_batches、close_batch_items、position_exposure_events 三类新表。
- PUBLIC/OFFLINE/test-control 的 runId、Redis namespace、cache provenance 和全 transport 零调用矩阵。
- reset-required migration fence、legacy A1–A11 fixture 大矩阵。
- IOC/FOK/GTD/post-only/通用 STOP-MARKET 和复杂 payload fingerprint。
- 每个 financial writer 的全仓 architecture allowlist 门禁。
- 保险基金、ADL、真实资金费、bankruptcy debt、tiered risk limit。

更简单的实现是：一个 Demo guard、一个 authority quote bundle、一个 full-fill coordinator、一个 system close service、一个 additive Flyway migration、一个 reset transaction，加三条浏览器闭环测试。

## 11. 正规交易所前端基准与主要差距

本报告只借鉴公开、稳定的交互结构，不复制品牌、文案或视觉资产。

- Binance 官方现货指南展示的核心结构是：实时 order book、交互图表、pair list、下单区、percentage slider，以及底部 open order/order history/trade history 和 cancel。当前桌面 TradingDesktopView 已覆盖大部分骨架，但终端内撤单、独立 trade history 和真实余额仍有断点。参考：[Binance Spot Trading Guide](https://www.binance.com/en/academy/articles/your-guide-to-binance-spot-trading)。
- OKX 官方交易设置明确包含 one-way/two-way、TP/SL 通知、预计强平价、dark/light、自定义布局以及 close/close-all 等仓位操作。当前项目只有布局骨架和显示字段，模式与动作没有贯通。参考：[OKX Trading Settings](https://www.okx.com/en-us/help/trading-settings-faq)。
- OKX 官方 Demo 说明使用虚拟资金，并提供 reset；当前项目自动开户但没有 reset，且资金被三重表现。参考：[OKX Demo Futures](https://www.okx.com/en-us/help/how-to-conduct-contract-simulation-trading-transactions)。
- OKX 永续指南的最小流程包含 USDT-margined 合约、cross/isolated、leverage、long/short、TP/SL 和持仓区。当前项目字段层面有一半，操作层面不足。参考：[OKX Perpetual Futures Guide](https://www.okx.com/en-gb/help/how-do-i-trade-perpetual-futures-on-okx)。
- Binance USD-M 合约订单合同体现 positionSide、reduceOnly、closePosition、timeInForce 等语义。当前 MVP 只需 positionSide + reduceOnly + 隐式 GTC，不必一次实现完整高级参数。参考：[Binance USD-M Futures New Order](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/trade#new-order)。

### 11.1 当前最大差距

1. **产品身份不可信**：桌面固定 Spot/Cash，移动固定 Perpetual/Cross/100x；同一 symbol 在两个终端含义不同。
2. **数据来源不可信**：真实 quote、前端合成 depth、seeded candle、mock balance 和 mock position 可以同时出现。
3. **账户区不是交易闭环**：底部订单多为只读，真实成交不是独立数据，平仓/全平按钮语义相同。
4. **永续控制缺失**：position mode、position side、margin mode、leverage、reduce-only、mark/index、funding countdown 没有统一状态。
5. **移动端不是桌面终端的响应式版本**：它是另一套静态壳，必须共享同一个 product/account/form state。
6. **首页可信度不足**：静态奖项、SAFU/Binance/OKX 品牌残留、虚高 counters 不适合独立模拟平台。
7. **Admin 信息架构错位**：真实交易运营页面隐藏，静态截图目录页面反而在主菜单。
8. **后台高风险动作交互不足**：部分 confirm 实际立即执行，无 pending 防双击和真实 reason。
9. **实时刷新粗放**：账户每 2 秒七请求，加上每个账户事件再次七请求；Markets 可能订阅约 40 个 quote topic。
10. **缺 Demo 身份提示**：页面需要全局 “Demo Trading / Simulated Data / No Real Funds” 标识，不能让用户误以为真实资金系统。

## 12. 推荐的最小目标架构

    外部公共行情 ─┐
                    ├→ MarketDataRouter（候选源 + freshness）
    本地生成器 ────┘             │
                                 ▼
                     权威行情 bundle + REST/STOMP
                                 │
    Web/Admin → OrderService → DemoExecutionGuard
                                 │
                                 ▼
                       统一 full-fill coordinator
                         │                    │
                         ▼                    ▼
               SpotSettlementService    PositionEngine
                         │                    │
                         └→ Wallet/Position/Trade/Ledger → STOMP

    TP/SL、Liquidation、Admin force-close
                         └→ SystemCloseOrderService → 同一 full-fill coordinator

### 12.1 后端只需保留的简单模块判断

| 建议模块 | 当前对应实现 | 结论 |
|---|---|---|
| 用户和登录 | auth controller/service、JWT、session/revocation | 保留 |
| 模拟资产账户 | AccountService、WalletService、两类 ledger | 保留并修资金口径 |
| 模拟现货订单 | OrderService、SpotSettlementService | 保留 |
| 模拟合约订单 | OrderService、PositionEngine | 保留并补产品语义 |
| 模拟持仓 | positions + PositionEngine/Service | 保留 |
| 模拟成交记录 | trading.trades + TradeRepository | 保留，补用户 API |
| 行情代理或模拟 | provider/router + Demo generator | 保留并加自动 fallback |
| WebSocket 推送 | STOMP publishers | 保留并修账户订阅鉴权 |
| 基础参数配置 | symbols、risk_configs、system_settings | 保留，Admin 接真数据 |
| 模拟资金重置 | 当前缺失 | 新增单一事务服务/API |

不用物理删除 finance/content/admin/FX 模块；“只需保留”应理解为本阶段主动开发边界，而不是重构删除已有代码。

## 13. 现货模拟交易最小方案

### 13.1 产品

首期 3–5 个 USDT Spot：

- BTCUSDT
- ETHUSDT
- SOLUSDT
- XRPUSDT
- 可选 BNBUSDT

V27 已有前四个，主要是修展示、外部/离线行情一致性和 tradable 配置。

### 13.2 市价单

沿用当前后端主链：

1. 创建 ACCEPTED 订单。
2. 从后端 authority bundle 读取 ask/bid。
3. 按固定、可配置的演示滑点计算价格。
4. 单次全量成交，写 FILLED。
5. 写 trading.trades 和 order_events。
6. BUY：扣 USDT available/locked，增加 base；SELL 相反。
7. 记录 asset ledger、spot position、手续费。
8. 通过账户 topic 推送增量事件，页面刷新委托、成交、余额。

不需要 order book matching；“盘口”是市场展示数据，不是用户订单的撮合簿。

### 13.3 限价单

提交时：

- BUY limit >= 当前 ask：立即全量成交。
- SELL limit <= 当前 bid：立即全量成交。
- 否则 PENDING，并冻结相应 USDT/base。

触发时：

- 后端每秒扫描，或在 RealtimeQuoteSink 更新时投递轻量检查。
- 用同一个 full-fill coordinator 计算 fee/slippage、Trade 和结算。
- 每单独立事务；失败只影响该单。

撤单：

- 只允许 PENDING；保留 PARTIALLY_FILLED 枚举兼容，但本期不主动产生部分成交。
- CAS → CANCELED，恢复 locked → available，写 order_event 和 asset ledger。

### 13.4 手续费与状态

- 保留当前 10bp 默认值，但移到 symbol/system config，页面在确认框和成交表展示。
- 状态最小集合：PENDING、WORKING（内部短暂）、FILLED、CANCELED、REJECTED。
- PARTIALLY_FILLED 仅兼容历史，不纳入验收。

## 14. 永续合约模拟交易最小方案

### 14.1 产品

首期 2–3 个 USDT Linear Perp：

- BTCUSDT-PERP
- ETHUSDT-PERP
- 可选 SOLUSDT-PERP

内部 canonical code 保留 -PERP；Binance USD-M provider adapter 边界再映射到 BTCUSDT/ETHUSDT。不要让 orderAdapter.ts 当前的去符号逻辑把 BTCUSDT-PERP 变成 BTCUSDTPERP。

### 14.2 one-way 与 hedge

- ONE_WAY：复用现有 account+symbol 净仓；反向成交先减仓，再平仓，再反手。
- HEDGE：同一 account+symbol 允许 LONG 与 SHORT 两个 slot；订单必须带 positionSide。
- 模式切换：只有在没有 Perp 活动委托和开仓时允许，避免复杂迁移。

数据库最小唯一性：

- ONE_WAY：account_id + symbol + position_side=BOTH + status=OPEN。
- HEDGE：account_id + symbol + position_side=LONG/SHORT + status=OPEN。

### 14.3 cross 与 isolated

- CROSS：复用 core.trading_accounts 作为单币 USDT Demo 合约账户，所有 cross position 共享 balance/equity/freeMargin。
- ISOLATED：复用 positions.margin_held 作为 position-level 隔离保证金；该仓亏损和释放不动其他 isolated 仓的 marginHeld。
- 不做多币种统一账户、组合保证金、风险档位或 tiered MMR。

### 14.4 开平仓

订单最小字段：

- productType=LINEAR_PERP
- positionMode=ONE_WAY/HEDGE
- positionSide=BOTH/LONG/SHORT
- marginMode=CROSS/ISOLATED
- reduceOnly=true/false
- leverage
- side、type、quantity、price
- takeProfit、stopLoss

UI：

- one-way 可显示“开仓/平仓”或根据 reduceOnly 明确动作。
- hedge 显示“开多、开空、平多、平空”四个语义。
- 平仓、TP/SL、强平都由后端决定最终 reduce quantity，不能相信前端。

### 14.5 标记价格和参考数据

P0 不需要真实指数系统。authority bundle 提供：

- last/bid/ask：用于 MARKET/LIMIT。
- markPrice：外部 USD-M mark endpoint 优先；离线模式由同一生成器产生平滑 mark。
- indexPrice：外部优先；离线可由 Spot price 加小扰动生成。
- fundingRate/nextFundingTime：只展示，不结算。

页面显示来源与时间；mark stale 时禁止新开仓和强平扫描，但允许用户以明确风险提示关闭仓位。

## 15. 模拟持仓、PnL、TP/SL 和强平

### 15.1 计算模型

USDT Linear Perp 的简化计算：

    多仓未实现盈亏 = (markPrice - entryPrice) × quantity
    空仓未实现盈亏 = (entryPrice - markPrice) × quantity
    positionNotional = markPrice × quantity
    initialMargin = entryNotional ÷ leverage
    ROI = unrealizedPnl ÷ positionMargin × 100%

现有 TradingAlgorithmEngine.unrealizedPnl、PerpMarginCalculator.calculate 和 PositionService.floatingPnlRatio 可直接复用。

已实现盈亏：

- 减仓或平仓时按成交价计算该部分 gross PnL。
- 减去开/平手续费；本期 funding 不实际结算。
- 累加到 positions.realized_pnl 和 ledger.ledger_entries。
- trading.trades 返回该次 realizedPnl、fee、systemReason。

### 15.2 简化预计强平价

逐仓展示可用简化近似：

    long estimatedLiq ≈ (entryPrice - marginHeld / quantity) / (1 - MMR)
    short estimatedLiq ≈ (entryPrice + marginHeld / quantity) / (1 + MMR)

全仓不能把每仓 marginHeld 当作独占资金；应以 account equity、所有 cross UPL、maintenance margin 和 liquidation fee 做账户级 gate，再给每仓展示“预计强平价”。P0 可使用当前 LiquidationService 的 account-level MM+fee 逻辑，但必须让页面公式和实际 trigger 使用同一 PerpetualRiskService。

代码和 UI 必须明确：

> 预计强平价和收益率为简化模拟模型，仅用于演示，不代表 Binance、OKX 或任何真实交易所完整风控，不可用于真实资金交易。

### 15.3 TP/SL

用户已明确把 TP/SL 设为 P0。最小逻辑：

1. 保存到 position.stop_loss/take_profit。
2. ProtectiveOrderExecutionService 以 markPrice 检查。
3. 多仓 TP：mark >= TP；SL：mark <= SL。空仓反向。
4. 触发后创建 reduce-only MARKET system order，reason=TAKE_PROFIT/STOP_LOSS。
5. 调用统一 full-fill coordinator，产生 Order/Trade/fee/PnL/event。
6. 全仓关闭则清保护价；部分关闭（本期不主动支持）则按剩余仓保留或由用户重设。
7. 推送通知和账户增量事件。

前端需修复 useTradeForm.ts:120-128 的策略 tab 断链；普通限价单内已有的保护价提交路径可以复用。

### 15.4 模拟强平

触发：

- Isolated：position equity <= maintenance margin + estimated close fee。
- Cross：account equity <= 所有 cross maintenance margin + liquidation fee buffer。

处理：

1. 锁 account 和目标 position slot。
2. 再取新鲜 mark/closeout price。
3. 创建 system reduce-only MARKET order，reason=LIQUIDATION。
4. 单次全量平掉该 slot。
5. 更新 margin/PnL，扣普通手续费和可选模拟 liquidation fee。
6. 写 Order、Trade、OrderEvent、Ledger，position=CLOSED。
7. STOMP 通知用户“模拟强平”。

不建立保险基金、ADL、真实强平撮合、破产负债追偿、分层风险限额或多节点风控。

### 15.5 Demo reset

建议 POST /api/accounts/{id}/demo-reset 在单一事务中：

1. 后端硬校验 account_type=DEMO。
2. 取消 PENDING/WORKING 委托并释放 hold。
3. 关闭或归零当前仓位；历史订单、成交、历史仓位保留。
4. 重置 Spot USDT/base wallets 和 Perp account balance。
5. 写 DEMO_RESET cash ledger 与 asset ledger，使用 requestId 保证重试幂等。
6. 恢复默认 one-way、cross、leverage（或保留用户 setting，二选一并写明）。
7. 推送 account reset event，Web/Admin 同步刷新。

和真实交易所不同，本 Demo 可以由 reset 原子取消/清理活动状态；UI 仍应先二次确认并展示影响范围。

## 16. 功能适配矩阵

完成度表示静态代码与既定目标的接近程度，不代表本轮运行验收。

| 功能 | 当前状态 | 前端完成度 | 后端完成度 | 可复用代码 | 最小修改方式 | 是否需要模拟接口 | 复杂度 | MVP优先级 |
|---|---|---:|---:|---|---|---|---|---|
| 首页 | 已实现，但需要优化 | 75% | 65% | HomePage、hero、home counters | 删除虚假品牌/奖项/巨额指标，接真实 symbol 摘要或明确 Demo | 否，复用 market/public | 中 | P1 |
| 行情页 | 已实现，但需要优化 | 72% | 72% | MarketsPage、provider proxy、favorites | 按 productType 分类，symbol selector 真切换，移除固定排行/空 tab | 是，复用/扩展后端行情 | 中 | P0 |
| 现货交易页 | 已实现，但需要优化 | 70% | 82% | TradingPage、TradePanel、SpotSettlement | 明确 Spot 路由/身份，去 mock balance，终端内补撤单/成交 | 否，扩展现有接口 | 中 | P0 |
| 永续合约页 | 部分实现 | 32% | 47% | 同一终端、PositionEngine、Perp calculator | 同页切 Perp，补 mode/side/margin/leverage/reduceOnly/mark | 是，需 reference 与 settings | 高 | P0 |
| K 线 | 已实现，可以直接复用 | 92% | 78% | KLineChartPanel、ChartService、candles | 修 volume/source，Perp alias 与 reference marker | 否 | 低 | P0 |
| 深度图 | 完全缺失 | 0% | 70% | 可复用 orderbook data | P1 增加图形组件；MVP 保留盘口列表即可 | 可用现有 order-book | 中 | P1 |
| 盘口 | 已实现，但需要优化 | 82% | 78% | OrderBook、RealtimeQuoteSink | 来源标识、外部/离线 bundle 一致、stale 状态 | 是，扩展 metadata | 中 | P0 |
| 最新成交 | 已实现，但需要优化 | 78% | 78% | RecentTrades、market/trades | 禁止前端合成冒充 LIVE，显示 source | 是，扩展 metadata | 低 | P0 |
| 下单面板 | 已实现，但需要优化 | 72% | 72% | TradePanel、useTradeForm、OrderService | 产品化表单；桌面/移动共享 state | 否，扩展 order payload | 高 | P0 |
| 限价单 | 已实现，但需要优化 | 78% | 72% | OrderTypeTabs、PENDING/hold/scheduler | 即时 marketable 判断、独立事务、统一 fee | 否 | 中 | P0 |
| 市价单 | 已实现，可以直接复用 | 85% | 85% | SimulatedExecutionAdapter、OrderFillService | Demo guard、同一 quote snapshot、可配置 fee/slippage | 否 | 低 | P0 |
| 撤单 | 已实现，但需要优化 | 72% | 82% | OrdersPage、OrderService.cancel | 终端底部接按钮，恢复 hold 后增量推送 | 否 | 低 | P0 |
| 当前委托 | 已实现，但需要优化 | 80% | 85% | BottomAccountPanel、orders API | 增产品/仓位方向列，终端内动作 | 否 | 低 | P0 |
| 历史委托 | 已实现，可以直接复用 | 82% | 85% | OrdersPage、orders 表 | 增原因、产品、系统单筛选 | 否 | 低 | P0 |
| 成交历史 | 部分实现 | 42% | 68% | trades 表/Repository、现有表格 | 新增用户 trades API，不再用 FILLED orders 冒充 | 是，最小新 API | 低 | P0 |
| 模拟资产 | 已实现，但需要优化 | 76% | 72% | WalletPage、AccountService、wallet | 去 mock、明确 Spot/Perp 子账户和 Demo 标识 | 否 | 中 | P0 |
| 冻结资产 | 已实现，但需要优化 | 68% | 82% | wallet locked、hold、asset ledger | 页面显示锁定原因/订单关联，修挂单 fee 一致性 | 否 | 低 | P0 |
| 合约持仓 | 部分实现 | 62% | 62% | PositionsGrid、PositionEngine/Service | 增 slot、product/margin/side、行情增量 PnL | 否，扩展响应 | 高 | P0 |
| 多空方向 | 部分实现 | 25% | 52% | BUY/SELL、净持仓 | one-way BOTH；hedge LONG/SHORT slots | 否 | 高 | P0 |
| 杠杆 | 部分实现 | 28% | 70% | dead leverage UI、leverage 字段/校验 | 挂载控件，增加账户/订单更新语义 | 是，settings API | 中 | P0 |
| 保证金模式 | 完全缺失 | 12% | 28% | marginHeld、cross skeleton | Cross 共用账户；Isolated 用 position.marginHeld | 是，settings/order 扩展 | 高 | P0 |
| 未实现盈亏 | 已实现，但需要优化 | 72% | 78% | PnLCalculator、PositionService | 统一 mark 来源，按 quote event 局部刷新 | 否 | 中 | P0 |
| 已实现盈亏 | 已实现，但需要优化 | 68% | 78% | PositionEngine、ledger、position.realized | closing trade 返回 fee/realized，统一系统平仓路径 | 否 | 中 | P0 |
| 收益率 | 已实现，但需要优化 | 65% | 72% | floatingPnlRatio、ROI | 统一 margin 分母、百分比格式和模拟说明 | 否 | 低 | P0 |
| 强平价格 | 部分实现 | 58% | 52% | TradingAlgorithmEngine、PositionResponse | 展示/触发共用 risk service，分 cross/isolated | 否 | 高 | P0 |
| 模拟强平 | 可以通过模拟数据实现 | 15% | 55% | LiquidationService、ledger fee | 显式启用 Demo scheduler，经 system close order，通知用户 | 否 | 高 | P0 |
| TP/SL | 部分实现 | 52% | 62% | protection API/scheduler、chart marker | 修 form 断链，触发经 system order | 否 | 中 | P0 |
| 用户中心 | 部分实现 | 55% | 72% | account pages、auth/account API | 去硬编码 UID/Lv，合并重复资产入口，明确未实现安全项 | 否 | 中 | P1 |
| 登录注册 | 已实现，但需要优化 | 78% | 82% | Auth pages/service、JWT/session | refresh single-flight、注册条款/强度；找回密码可后置 | 否 | 中 | P0 |
| 资产页面 | 已实现，但需要优化 | 76% | 75% | WalletPage、wallet/ledger | Spot/Perp tabs、reset、去真实充值提现暗示 | 否 | 中 | P0 |
| 订单中心 | 已实现，但需要优化 | 75% | 80% | OrdersPage、order events | product/position/system reason/trades 分栏 | 是，trades API | 中 | P0 |
| WebSocket | 已实现，但需要优化 | 80% | 70% | marketStream、STOMP publishers | 账户订阅鉴权、增量 payload、debounce | 否 | 高（安全） | P0 |
| 外部行情数据 | 已实现，但需要优化 | 68% | 72% | Binance/OKX/Massive/provider router | 候选 fallback、Perp mark adapter、真实 status | 是，扩展现有 | 高 | P0 |
| 离线行情 | 可以通过模拟数据实现 | 55% | 78% | Demo generator、test-data service | 按 symbol profile 生成一致 bundle，source badge | 否 | 中 | P0 |
| 移动端适配 | 部分实现 | 45% | 适用接口 70% | TradingMobileView、DataTable cards | 移除静态 Perp 壳，共享桌面表单/session | 否 | 高 | P0 |
| 多语言 | 部分实现 | 62%（Web）/0%（Admin） | 不适用 | Web i18next/locale | 补交易核心硬编码；Admin 后置 i18n | 否 | 中 | P1 |
| 深色主题 | 部分实现 | 90%（Web）/0%（Admin） | 不适用 | Web ThemeProvider | Admin 增 token/theme toggle | 否 | 中 | P1 |
| Admin 运营闭环 | 部分实现 | 45% | 75% | AdminLayout、真实交易/admin APIs | 重排菜单、接 wallet/reset/force close，修 confirm | 是，少量 Admin API | 高 | P0 |
| 模拟资金重置 | 完全缺失 | 0% | 0% | 可复用 account/wallet/order/position/ledger | 新增 Demo-only 原子 reset | 是，新 API | 高 | P0 |
| 真实资金费结算 | 不适合当前项目 | 35% | 45% | funding 表/服务 | 本期只显示模拟 rate/countdown，不扣资产 | 可模拟展示 | 高 | P2 |
| 真实撮合 | 不适合当前项目 | 0% | 0% | 无需复用 | 不实现 | 否 | 极高 | P2 |

## 17. P0、P1、P2

### 17.1 P0：最小演示闭环

验收主链：

    行情变化
      → Web 显示 LIVE/SIMULATED source 与 freshness
      → 用户提交 Spot 或 Perp 模拟订单
      → MARKET/可成交 LIMIT 立即 full fill；其余 LIMIT 进入当前委托
      → 撤单恢复冻结，或触价后 full fill
      → Spot wallet/Perp account/position 更新
      → 生成 Trade、fee、PnL、OrderEvent、Ledger
      → STOMP 推送，订单/成交/资产/持仓实时更新

P0 功能：

1. DemoExecutionGuard 与所有交易 scheduler 的 Demo-only 门禁。
2. 3–5 个 USDT Spot、2–3 个 USDT Linear Perp canonical product。
3. 后端外部行情优先、离线生成自动兜底、source/freshness 可见。
4. Spot MARKET/LIMIT、冻结、触发、撤单、成交、手续费、资产和成交历史。
5. Perp MARKET/LIMIT、开多/开空/平多/平空。
6. ONE_WAY/HEDGE。
7. CROSS/ISOLATED 简化风险池。
8. 杠杆选择、mark/index、UPL/realized/ROI/预计强平价。
9. TP/SL 触发并经系统订单平仓。
10. 模拟强平经系统订单完成、写成交/流水并通知。
11. Demo reset，数据跨重启持久化，只有 reset 清当前状态。
12. Web 桌面和移动共享同一业务状态与操作。
13. Admin 接通账户、订单、持仓、成交、钱包、流水、reset、强平，修高风险确认。
14. 账户 STOMP topic 鉴权。
15. 至少三条运行验收：Spot 闭环、Perp 两种模式/保证金闭环、reset/强平闭环。

### 17.2 P1：增强真实感

- 独立深度图、更多盘口档位和更自然的本地市场生成。
- terminal layout preset 的实际入口。
- close-all、部分平仓、反手按钮和更完整订单筛选。
- 只展示的 funding rate/countdown；不做真实结算。
- 多语言全覆盖、Admin i18n。
- Admin 深色主题、移动 drawer。
- 页面动画、toast、声音/浏览器通知。
- 更丰富资产曲线、快照、PnL 图表。
- 首页真实 market preview、真实公告内容或明确 Demo seed。
- 找回密码、2FA、KYC 的可信演示流程。
- provider 级 quote/candle/depth/trades 健康面板。

### 17.3 P2：暂不实现

- 真实订单簿撮合、撮合队列和主动部分成交。
- 真实充值提现、链上钱包、节点、托管和用户真实资金。
- 真实 private exchange/broker/FIX/LP 下单。
- 真实资金费率结算、历史 backfill/catch-up。
- 保险基金、ADL、风险准备金、破产债务。
- 真实指数成分系统、强平撮合。
- 组合保证金、多币种统一账户、风险档位。
- 微服务拆分、消息队列、事件溯源、分布式事务。
- 高并发撮合、多节点风控和金融级清算。
- 币本位合约、交割合约、期权、C2C、VIP、理财等无后端支撑入口。
- IOC/FOK/GTD/post-only/OCO/trailing stop 等高级订单，除非后续有明确演示需求。

## 18. 推荐修改顺序

本节是后续实施顺序，不代表本轮已修改。

1. **建立可信边界**

   DemoExecutionGuard、scheduler 默认关闭、账户 STOMP 鉴权、去除真实态 mock balance/position、source/freshness contract。

2. **修正产品与资金真值**

   一份最小 Flyway 增量迁移；Spot/Perp product seed；一个 active Demo；明确 Spot wallet 与 Perp account；修 reset。

3. **统一成交内核**

   MARKET 和 pending trigger 共用 full-fill calculator；fee/slippage、Order/Trade/Event/Ledger 一致；每单独立事务。

4. **先完成 Spot P0**

   限价/市价/撤单/冻结/成交历史/资产；把桌面与移动的同一 Spot 闭环跑通。

5. **补 Perp slot 和风险语义**

   one-way/hedge、cross/isolated、leverage、reduceOnly、mark/reference；复用 PositionEngine。

6. **统一所有平仓**

   SystemCloseOrderService 接手手动、TP/SL、liquidation、Admin force-close；输出标准 Order/Trade/fee/PnL/event。

7. **完成用户端视觉和移动端**

   Spot/Perp 路由、终端头部、表单、bottom account、assets/orders，清理品牌残留、空 tab 和静态移动壳。

8. **完成 Admin 闭环**

   导航、真实账户详情、wallet/ledger/reset/force-close、确认/审计、产品筛选。

9. **合同与验收**

   更新 OpenAPI/shared types；执行 backend tests、web/admin tests/build、architecture、三条 browser E2E 和离线无公网演示。

10. **P1 真实感**

   深度图、funding 展示、动画、多语言、Admin theme、资产图表。

## 19. 预计涉及文件和数据库变化

### 19.1 Web 重点文件

| 路径 | 预计修改 |
|---|---|
| apps/web/src/app/App.tsx | Spot/Perp 语义路由 |
| apps/web/src/app/AppShell.tsx | Demo/source 全局标识、导航入口 |
| apps/web/src/pages/trading/TradingPage.tsx | product mode、canonical symbol、共享 desktop/mobile 状态 |
| apps/web/src/pages/trading/components/TradingDesktopView.tsx | Perp header/controls 和终端动作 |
| apps/web/src/pages/trading/components/TradingMobileView.tsx | 复用真实 form/session |
| apps/web/src/pages/trading/mobile/MobileTradingTerminal.tsx | 删除固定 Cross/100x/余额 |
| apps/web/src/pages/trading/components/SymbolHeader.tsx | Spot/Perp、mark/index/funding/source |
| apps/web/src/features/trading/components/TradePanel.tsx | 去 mock balance，挂载产品 controls |
| apps/web/src/features/trading/components/TradeTabs.tsx | Spot/Perp 有效切换 |
| apps/web/src/features/trading/components/TradePanelLeverageControls.tsx | 从死代码接入真实状态 |
| apps/web/src/features/trading/hooks/useTradeForm.ts | position/margin/leverage/reduceOnly/TP-SL |
| apps/web/src/features/trading/services/orderAdapter.ts | 扩展 payload，保留 -PERP |
| apps/web/src/pages/trading/components/BottomAccountPanel.tsx | orders/positions/trades/assets 动作闭环 |
| apps/web/src/pages/trading/components/BottomAccountOrdersGrid.tsx | 终端撤单/改单 |
| apps/web/src/pages/trading/components/BottomAccountPositionsGrid.tsx | 平仓/TP-SL/强平信息 |
| apps/web/src/features/trading-session/useTradingSession.ts | debounce、single-flight、增量刷新 |
| apps/web/src/services/tradingApi.ts | trades/settings/reset/close API |
| apps/web/src/services/marketStream.ts | 账户订阅错误/重连处理 |
| apps/web/src/pages/markets/MarketsPage.tsx | 产品分类和真实数据源 |
| apps/web/src/pages/orders/OrdersPage.tsx | 独立成交历史 |
| apps/web/src/pages/positions/PositionsPage.tsx | Perp slot/margin/系统原因 |
| apps/web/src/pages/wallet/WalletPage.tsx | Spot/Perp 子账户与 reset |
| apps/web/src/pages/trading/components/KLineChartPanel.tsx | Perp alias、source/volume/markers |
| apps/web/src/types/trading.ts、apps/web/src/features/market/tradingModels.ts | 共享合同字段 |

具体目录名以当前仓库实际位置为准；实施前应通过 rg 定位组件，因为部分交易组件位于 features/trading 的子目录。

### 19.2 Admin 重点文件

| 路径 | 预计修改 |
|---|---|
| apps/admin/src/app/AdminApp.tsx | 保留已有真实路由 |
| apps/admin/src/app/adminMenu.ts | 把账户/交易/行情/风险/审计纳入主菜单 |
| apps/admin/src/components/AdminLayout.tsx | 移动 drawer、theme |
| apps/admin/src/pages/FeatureCrudPage.tsx | 真确认、reason、loading、防双击、审计结果 |
| apps/admin/src/pages/AccountsPage.tsx | account/wallet/ledger/reset 详情 |
| apps/admin/src/pages/OrdersPage.tsx | Spot/Perp/系统单字段 |
| apps/admin/src/pages/PositionsPage.tsx | mode/margin/TP-SL/force-close |
| apps/admin/src/pages/TradesPage.tsx | fee/realized/system reason |
| apps/admin/src/services/adminApi.ts | wallet/asset ledger/reset/force-close wrappers |
| apps/admin/src/types.ts | 改用 generated OpenAPI schemas |
| apps/admin/src/styles.css | dark token 和移动布局 |

### 19.3 Backend 重点文件/类

| 区域 | 文件/类 |
|---|---|
| API | TradingController、AccountController、MarketController、Admin trading/account controllers |
| Order | OrderService、OrderFillService、PendingOrderExecutionService、OrderStatusPolicy |
| Execution | SimulatedExecutionAdapter、新的 DemoExecutionGuard/FullFillCoordinator |
| Spot | SpotSettlementService、SpotPositionService、WalletService |
| Perp | PositionEngine、PositionService、PositionRepository、PerpMarginCalculator、TradingAlgorithmEngine |
| System close | ProtectiveOrderExecutionService、LiquidationService、AdminTradingCommandService、新的 SystemCloseOrderService |
| Account/reset | AccountService、AccountRepository、LedgerService、WalletService |
| Market | SymbolNormalizer、ProviderResolver、MarketDataRouter、QuoteService、RealtimeQuoteSink、DemoMarketDataGenerator、MarketTestDataService |
| Perp source | 新的或现有模式扩展的 Binance USD-M public reference adapter |
| WS/security | WebSocketJwtChannelInterceptor、TradingWsPublisher、MarketWebSocketConfig |
| Contract | request/response DTO、entity、enum、shared OpenAPI |
| Config | application.yml、application-dev.yml、application-prod.yml |

### 19.4 推荐的一份最小 Flyway 增量迁移

不建议照文档一次增加 V46+V47 大量新表。MVP 可先用一份 additive migration，内容为：

| 表 | 最小新增/修正 |
|---|---|
| core.trading_accounts | position_mode；可选 default_margin_mode；active Demo 唯一约束或 service-level hard guard |
| trading.orders | product_type、position_mode、position_side、margin_mode、reduce_only、time_in_force 默认 GTC、order_origin、system_reason |
| trading.trades | product_type、position_side、margin_mode、fee、fee_asset、system_reason |
| trading.positions | product_type、position_mode、position_side、margin_mode；open slot partial unique indexes |
| market.symbols | seed BTCUSDT-PERP、ETHUSDT-PERP（可选 SOL），正确 contract/margin/settlement/MMR/liquidation fee/mark source |
| market.data_provider_capabilities/bindings | USD-M reference provider 与 OFFLINE local provider binding |
| 现有约束 | 评估并修正跨账户 idempotency 冲突；为新增 enum 值加 check |

P0 不新增 close_batches、close_batch_items、position_exposure_events、保险基金或 ADL 表。

## 20. 不建议实现的复杂功能

| 功能 | 不建议原因 |
|---|---|
| 真实撮合引擎/订单簿 | 用户订单不需要互相撮合；当前权威市场价触发 full-fill 足够演示 |
| 消息队列/微服务 | 单体和 STOMP 已能完成目标；会增加部署、幂等和调试成本 |
| 事件溯源/CQRS | 现有订单、事件、Trade、Ledger 已足以审计 Demo |
| 金融级清算/分布式事务 | 不处理真实资金，没有收益 |
| 真实链上钱包/充值提现 | 明确越界并带来安全/合规风险 |
| 真实 private Binance/OKX API | 只需公共行情；禁止把 Demo 订单外发 |
| 真实 funding 结算 | 用户明确暂不需要；保留展示即可 |
| 保险基金、ADL、风险准备金 | 仅真实衍生品清算需要 |
| 复杂 portfolio/cross margin | 单币 USDT cross + position-level isolated 足够 |
| 部分成交 | 用户已选择 full-fill 或 pending；只留 schema 兼容 |
| close batch/exposure 新表 | close-all 可顺序调用 system close，并用 client requestId 幂等 |
| 大规模 runId/provenance 基础设施 | P0 用 dataMode/source/asOf/stale + Demo guard 即可 |
| 币本位、期权、C2C、VIP、理财 | 不在目标内，当前多为静态壳或空 tab |

## 21. 最小改进清单

| 序号 | 改进目标 | 当前问题 | 复用内容 | 修改文件 | 模拟接口 | 数据库变化 | 复杂度 | 验收标准 |
|---:|---|---|---|---|---|---|---|---|
| 1 | 明确 Demo 安全边界 | account type 不参与所有成交入口 | Security/config/AccountType | OrderService、各 scheduler、新 DemoExecutionGuard | 无 | 无 | 中 | 非 DEMO 或 execution 非 demo 的任何交易写入均零 mutation |
| 2 | 修账户 WS 鉴权 | 匿名可订阅账户 topic | JWT/session/STOMP | WebSocketJwtChannelInterceptor、publisher/topic | 无 | 无 | 中 | 未登录或非账户所有者订阅被拒绝；market topic 仍公开 |
| 3 | 清除前端真值 mock | 真实余额缺项会补 10,000 USDT/0.25 BTC | 真实 session/wallet API | TradePanel、bottomAccountPanelData、TradingPage | 无 | 无 | 中 | ready 状态只显示后端资产/订单/仓位；loading 是 skeleton |
| 4 | 行情自动兜底 | router 不尝试次源；前端静默本地 fallback | provider/binding/router/demo generator | ProviderResolver、MarketDataRouter、QuoteService、前端 adapter | 扩展 /api/market metadata | 可不变 | 高 | 断网自动切 SIMULATED；恢复后切 LIVE；页面来源清晰；离线可演示 |
| 5 | 统一 Spot/Perp 产品身份 | 桌面 Spot、移动 Perp、无 -PERP seed | product_type、symbol rules | App/TradingPage/SymbolNormalizer/provider adapter | 扩展 symbols/reference | seed Perp | 中 | 同一 symbol 在 PC/H5/API 含义一致；BTCUSDT-PERP 不被改写 |
| 6 | 修 Demo 资金真值 | 开户产生 account+FX wallet+Spot wallet 三份资金 | Account/Wallet/Ledger | AccountService、WalletService、资产 UI | 无 | 可加单 active Demo 约束 | 高 | Spot 与 Perp 子账户可解释，总资产不重复计数 |
| 7 | 统一 full-fill 计算 | MARKET 与挂单触发 fee/slippage 不一致 | SimulatedExecutionAdapter、OrderFillService | PendingOrderExecutionService、OrderFillService | 无 | 无 | 中 | 相同规则下两路径 Order/Trade/Wallet fee 一致 |
| 8 | 完成 Spot 限价/市价闭环 | scheduler 默认关、终端缺撤单/成交 | 现有 Order/Spot settlement/Orders UI | dev config、BottomAccount grids、TradePanel | 无 | 无 | 中 | MARKET 即成；LIMIT 触价成/否则挂；撤单恢复冻结 |
| 9 | 用户真实成交历史 | TRADES tab 是 FILLED orders | trading.trades/Repository/DataTable | TradingController/TradeService/tradingApi/OrdersPage | GET /api/trading/trades | 无 | 低 | 每次 fill 有独立 trade、fee、price、time、realized PnL |
| 10 | one-way/hedge | 只有净仓 | PositionEngine | account settings、PositionEngine/Repository、form | settings + order 扩展 | account/order/position fields+slot index | 高 | 可切换；one-way 反向净额；hedge 同时 LONG/SHORT |
| 11 | cross/isolated | 后端写死 CROSS | marginHeld、account equity、Perp calculator | PositionService/RiskService/form | order/settings 扩展 | order/position margin_mode | 高 | Cross 共享账户；isolated 只使用本仓保证金 |
| 12 | 杠杆可调 | UI dead code、规则最大值被当当前杠杆 | leverage field/risk check | TradePanelLeverageControls、settings/order DTO | settings API | 可能仅用现有 leverage | 中 | 用户可选合法杠杆；保证金/强平价即时一致 |
| 13 | 统一系统平仓 | 手动/TP-SL/强平无标准 Order/Trade | PositionService、OrderFill、ledger | 新 SystemCloseOrderService、三个调用者 | 扩展现有 order | order/trade system fields | 高 | 四类平仓均产生可查询 Order/Trade/Event/Fee/PnL |
| 14 | TP/SL P0 | 表单策略断链，触发直接关仓 | protection API/scheduler/chart marker | useTradeForm、OrderFormSide、Protective service | 无 | system reason 字段 | 中 | 多空触发方向正确；触发后成交、仓位、资产、通知同步 |
| 15 | 模拟强平 | 有骨架但默认关且展示/触发公式不同 | LiquidationService、MM、ledger fee | PerpetualRiskService、LiquidationService、UI | 无 | system reason 字段 | 高 | Cross/isolated 触发正确；整仓关闭；有 liquidation 记录和提示 |
| 16 | Demo reset | 完全缺失 | Account/Wallet/Order/Position/Ledger | AccountService、新 reset service、Wallet/Admin UI | POST demo-reset | 无新表，可加约束 | 高 | 跨重启数据保留；只有 reset 清当前态；历史保留；重试幂等 |
| 17 | 桌面/移动同逻辑 | 移动写死 Cross/100x/7.34 | TradePanel/session/DataTable | TradingMobileView、MobileTradingTerminal | 无 | 无 | 高 | PC/H5 对同账户/产品产生相同 payload 和结果 |
| 18 | Admin 运营闭环 | 真实页隐藏、静态页在菜单、confirm 直接执行 | AdminLayout、真实 admin endpoints | adminMenu、FeatureCrudPage、Accounts/Trading pages/adminApi | wallet/ledger/reset Admin API | 无或复用同迁移 | 高 | Admin 可查用户资产/订单/成交/持仓并安全 reset/force-close |
| 19 | 会话实时更新 | 2 秒七请求 + WS 再七请求 | STOMP/useTradingSession | useTradingSession、publisher payload | 无 | 无 | 中 | 事件 100–300ms 合并；无重叠全量请求；10–30 秒兜底 |
| 20 | 可信首页与导航 | 静态奖项、品牌残留、巨额随机指标 | Home layout、content API | Home components、V31/counter job、AppShell | 可复用 market/content | 不必新表 | 中 | 页面不宣称真实资产/保障；所有可见入口有实际行为或标 Demo |

## 22. 最终建议

### 22.1 是否继续使用当前项目

**是。** 当前项目与目标的技术方向高度兼容：

- 用户端交易终端骨架已经接近交易所常见布局；
- K 线、盘口、逐笔、订单表单、资产/订单/仓位页面不需要重写；
- 后端现货闭环已经达到可复用水平；
- 永续所需的 PnL、margin、净持仓、TP/SL、funding/liquidation 基础已经存在；
- PostgreSQL 表和 Flyway 可以用一份增量迁移补齐，而不是另建系统；
- Admin 有大量隐藏的真实运营接口和页面。

不建议继续沿用的只是“混合真假数据”和“多条旁路各自结算”的方式，而不是整个项目或技术栈。

### 22.2 最终推荐方案

采用“现有模块化单体 + 后端权威混合行情 + 单次全量模拟成交 + 统一系统平仓”的最小方案。优先完成视觉真实、操作闭环、数据一致和 Demo 身份透明；把真实交易所才需要的 funding 清算、保险基金、ADL、撮合和分布式基础设施全部留在 P2。

### 22.3 本轮变更与验证说明

- 本轮没有修改任何业务代码、配置、数据库迁移或测试。
- 唯一新增内容是本分析报告。
- 未运行 build/test/service/Docker/DB，因用户明确要求静态只读分析。
- 后续若进入实施阶段，第一步应先运行仓库既有环境门禁和基线测试，再按第 18 节顺序执行。
