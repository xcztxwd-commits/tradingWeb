# FX Trader 产品原型架构系列图

日期：2026-06-13  
范围：`fx-trading-platform/apps/web` 用户交易端、`fx-trading-platform/apps/admin` 独立后台、`fx-trading-platform/backend` 业务服务  
目标：把当前项目整理成可用于 PRD、原型评审和后续 UI 重整的产品架构图。  

## 设计前提

- 用户端不再暴露 `后台` 页面；后台管理系统由 `apps/admin` 独立承载。
- 用户端需要新增官方首页，`/` 不应继续直接跳到 `/trading`。
- 桌面端主导航应一行可见，移动端底部导航最多 5 项。
- 交易平台的产品特征是专业、可信、数据优先、移动端可快速触达关键功能。
- 当前已有页面包括：`概览`、`终端`、`行情`、`订单`、`持仓`、`资金`、`安全`、`设置`、`我的`、登录/注册/找回密码/2FA 辅助。

## 1. 产品系统总览图

```mermaid
flowchart TB
  U["普通交易用户"] --> W["用户交易端 apps/web"]
  A["运营/风控/财务管理员"] --> AD["独立后台 apps/admin"]

  W --> API["后端业务服务 backend"]
  AD --> API

  API --> AUTH["认证与会话"]
  API --> MARKET["行情与品种"]
  API --> TRADE["订单/成交/持仓"]
  API --> ASSET["账户/资金/流水"]
  API --> RISK["风控与审计"]
  API --> CONTENT["公告/消息/配置"]

  AUTH --> DB[("数据库")]
  MARKET --> DB
  TRADE --> DB
  ASSET --> DB
  RISK --> DB
  CONTENT --> DB

  W -. "公开看盘：行情、K线、盘口" .-> MARKET
  W -. "登录后：下单、订单、持仓、资金、安全" .-> AUTH
  AD -. "独立管理入口，不放入用户端导航" .-> AUTH
```

## 2. 用户端信息架构图

```mermaid
flowchart LR
  HOME["/ 首页 官方主页"] --> DASH["/dashboard 概览"]
  HOME --> TRADING["/trading 交易终端"]
  HOME --> MARKETS["/markets 行情"]
  HOME --> ORDERS["/orders 订单"]
  HOME --> POSITIONS["/positions 持仓"]
  HOME --> WALLET["/wallet 资金"]
  HOME --> SECURITY["/security 安全"]
  HOME --> SETTINGS["/settings 设置"]

  HOME --> AUTH["登录/注册辅助"]
  AUTH --> LOGIN["/login 登录"]
  AUTH --> REGISTER["/register 注册"]
  AUTH --> FORGOT["/forgot-password 找回密码"]
  AUTH --> TWOFA["/two-factor-help 2FA 提示"]

  ACCOUNT["/account 我的"] --> DASH
  ACCOUNT --> POSITIONS
  ACCOUNT --> WALLET
  ACCOUNT --> SECURITY
  ACCOUNT --> SETTINGS

  LEGACY["/trade 旧入口"] --> TRADING

  X["用户端 /admin"]:::removed
  X -. "取消：由 apps/admin 独立承载" .-> REMOVED["不进入用户端导航/路由"]

  classDef removed fill:#fff1f0,stroke:#b42318,color:#7a1a14;
```

## 3. 桌面端顶部导航原型图

```mermaid
flowchart TB
  SHELL["用户端桌面 Shell"] --> TOP["固定顶部导航 64px"]
  SHELL --> MAIN["主内容区"]

  TOP --> BRAND["品牌 FX Trader"]
  TOP --> NAV["横向主导航"]
  TOP --> TOOLS["语言 / 登录状态 / 账户入口"]

  NAV --> N1["首页"]
  NAV --> N2["概览"]
  NAV --> N3["终端"]
  NAV --> N4["行情"]
  NAV --> N5["订单"]
  NAV --> N6["持仓"]
  NAV --> N7["资金"]
  NAV --> N8["安全"]
  NAV --> N9["设置"]

  MAIN --> PAGE["当前路由页面"]

  PAGE --> HOME_PAGE["首页：产品说明 + 功能入口 + 行情摘要"]
  PAGE --> USER_PAGE["账户类页面：浅色工作台"]
  PAGE --> TERMINAL_PAGE["交易终端：高密度深色交易界面"]

  NOTE["设计约束：桌面 1440px 一行展示；1024px 以下允许折叠次级项到更多菜单"]
```

## 4. 移动端导航与入口原型图

```mermaid
flowchart TB
  MOBILE["用户端移动 Shell"] --> TOPBAR["顶部栏：品牌 / 当前页 / 语言或账户"]
  MOBILE --> CONTENT["页面内容区"]
  MOBILE --> BOTTOM["底部 Tab，最多 5 项"]

  BOTTOM --> B1["首页"]
  BOTTOM --> B2["终端"]
  BOTTOM --> B3["行情"]
  BOTTOM --> B4["订单"]
  BOTTOM --> B5["我的"]

  B5 --> HUB["/account 我的入口聚合"]
  HUB --> H1["概览"]
  HUB --> H2["持仓"]
  HUB --> H3["资金"]
  HUB --> H4["安全"]
  HUB --> H5["设置"]

  CONTENT --> SAFE["底部安全区与导航避让"]
  CONTENT --> TOUCH["触控目标 >= 44px"]
  CONTENT --> NO_SCROLL["无横向滚动"]
```

## 5. 官方首页首屏原型架构图

```mermaid
flowchart TB
  HOME["首页 /"] --> HERO["首屏 Hero：品牌价值 + 交易终端预览"]
  HOME --> MARKET_STRIP["实时行情条：重点品种价格/涨跌/状态"]
  HOME --> FEATURE_GRID["功能宫格：8 个用户侧能力"]
  HOME --> TRUST["可信说明：模拟/真实链路状态、风控、安全、数据同步"]
  HOME --> CTA["主行动：进入交易终端 / 登录账户 / 查看行情"]

  HERO --> H_TITLE["标题：专业交易终端与账户工作台"]
  HERO --> H_COPY["副文案：行情、K线、下单、订单、持仓、资金统一管理"]
  HERO --> H_PREVIEW["终端截图/图表预览区域"]

  FEATURE_GRID --> F1["概览：账户净值/风险/快捷操作"]
  FEATURE_GRID --> F2["终端：K线/盘口/下单"]
  FEATURE_GRID --> F3["行情：品种/报价/榜单"]
  FEATURE_GRID --> F4["订单：当前委托/历史/成交"]
  FEATURE_GRID --> F5["持仓：当前持仓/保护单/平仓"]
  FEATURE_GRID --> F6["资金：余额/充值/提现/流水"]
  FEATURE_GRID --> F7["安全：2FA/设备/白名单"]
  FEATURE_GRID --> F8["设置：主题/语言/偏好"]
```

## 6. 交易终端页面原型架构图

```mermaid
flowchart TB
  TRADING["/trading 交易终端"] --> DESKTOP["桌面端终端"]
  TRADING --> MOBILE_T["移动端终端"]

  DESKTOP --> LEFT["左侧行情列表 MarketSidebar"]
  DESKTOP --> CENTER["中部图表工作区 ChartWorkspace"]
  DESKTOP --> RIGHT["右侧交易面板 TradePanel / Quote / OrderBook"]
  DESKTOP --> BOTTOM["底部账户区 BottomAccountPanel"]

  CENTER --> TOOLBAR["周期/图表类型/指标/画线工具"]
  CENTER --> KLINE["KLineCharts 图表"]
  CENTER --> INDICATOR["指标设置弹窗"]

  RIGHT --> ORDERBOOK["盘口"]
  RIGHT --> RECENT["最新成交"]
  RIGHT --> ORDERFORM["买卖表单"]
  RIGHT --> CONFIRM["下单确认"]

  BOTTOM --> BO1["当前委托"]
  BOTTOM --> BO2["历史订单"]
  BOTTOM --> BO3["当前持仓"]
  BOTTOM --> BO4["历史持仓"]
  BOTTOM --> BO5["资产"]
  BOTTOM --> BO6["策略"]

  MOBILE_T --> MTOP["交易对/价格/设置"]
  MOBILE_T --> MCHART["K线主视图"]
  MOBILE_T --> MACTION["快捷操作：行情/盘口/下单/账户"]
  MOBILE_T --> MSHEET["底部下单 Sheet"]
  MOBILE_T --> MDRAWER["行情/盘口 Drawer"]
```

## 7. 账户工作台功能域图

```mermaid
flowchart TB
  ACCOUNT["账户工作台"] --> DASH["概览 Dashboard"]
  ACCOUNT --> ORDERS["订单 Orders"]
  ACCOUNT --> POSITIONS["持仓 Positions"]
  ACCOUNT --> WALLET["资金 Wallet"]
  ACCOUNT --> SECURITY["安全 Security"]
  ACCOUNT --> SETTINGS["设置 Settings"]

  DASH --> D1["资产结构"]
  DASH --> D2["风险摘要"]
  DASH --> D3["最近订单"]
  DASH --> D4["最近流水"]
  DASH --> D5["快捷操作"]

  ORDERS --> O1["当前委托"]
  ORDERS --> O2["历史订单"]
  ORDERS --> O3["成交记录"]
  ORDERS --> O4["订单事件"]
  ORDERS --> O5["撤单/改单"]

  POSITIONS --> P1["当前持仓"]
  POSITIONS --> P2["历史持仓"]
  POSITIONS --> P3["止盈止损"]
  POSITIONS --> P4["平仓确认"]

  WALLET --> W1["余额/可用/冻结"]
  WALLET --> W2["充值申请"]
  WALLET --> W3["提现申请"]
  WALLET --> W4["资金流水"]
  WALLET --> W5["地址簿入口"]

  SECURITY --> S1["登录密码"]
  SECURITY --> S2["2FA"]
  SECURITY --> S3["设备管理"]
  SECURITY --> S4["登录历史"]
  SECURITY --> S5["提现白名单"]

  SETTINGS --> SE1["主题"]
  SETTINGS --> SE2["语言"]
  SETTINGS --> SE3["交易偏好"]
  SETTINGS --> SE4["通知偏好"]
  SETTINGS --> SE5["移动端表格偏好"]
```

## 8. 用户交易执行流程图

```mermaid
sequenceDiagram
  actor User as 用户
  participant Home as 首页/行情
  participant Terminal as 交易终端
  participant Auth as 登录会话
  participant Order as 下单服务
  participant Position as 持仓服务
  participant Risk as 风控校验

  User->>Home: 查看产品与行情
  Home->>Terminal: 进入交易终端
  Terminal->>Auth: 检查登录状态
  alt 未登录
    Auth-->>Terminal: 游客看盘模式
    Terminal-->>User: 可看行情/K线/盘口，提示登录后下单
    User->>Auth: 登录
    Auth-->>Terminal: 返回原交易对
  else 已登录
    Auth-->>Terminal: 账户链路就绪
  end
  User->>Terminal: 填写买卖方向、价格、数量、策略
  Terminal->>Risk: 前端参数校验与风险提示
  Risk-->>Terminal: 校验通过
  Terminal->>User: 下单确认
  User->>Order: 确认提交
  Order->>Risk: 后端风控/余额/精度校验
  Order-->>Terminal: 订单状态
  Terminal->>Position: 同步订单/持仓/资产
  Position-->>User: 更新底部账户区与账户页面
```

## 9. 用户端数据与服务流图

```mermaid
flowchart LR
  WEB["apps/web React"] --> ROUTER["React Router"]
  WEB --> I18N["i18n zh-CN/en-US/ja-JP"]
  WEB --> THEME["ThemeProvider / themes"]
  WEB --> STORE["Zustand stores"]

  ROUTER --> PAGES["页面层"]
  PAGES --> USER_COMPONENTS["user-page 通用组件"]
  PAGES --> TRADING_COMPONENTS["trading 终端组件"]

  USER_COMPONENTS --> API_CLIENT["apiClient"]
  TRADING_COMPONENTS --> API_CLIENT
  TRADING_COMPONENTS --> MARKET_STREAM["marketStream / mock quote stream"]

  API_CLIENT --> AUTH_API["authApi"]
  API_CLIENT --> MARKET_API["marketApi"]
  API_CLIENT --> TRADING_API["tradingApi / orderApi"]
  API_CLIENT --> ACCOUNT_API["accountApi"]
  API_CLIENT --> LEDGER_API["ledgerApi"]
  API_CLIENT --> FINANCE_API["financeApi"]

  AUTH_API --> BACKEND["backend REST API"]
  MARKET_API --> BACKEND
  TRADING_API --> BACKEND
  ACCOUNT_API --> BACKEND
  LEDGER_API --> BACKEND
  FINANCE_API --> BACKEND

  MARKET_STREAM --> QUOTES["实时/模拟报价"]
  QUOTES --> TRADING_COMPONENTS
```

## 10. 独立后台管理系统边界图

```mermaid
flowchart TB
  ADMIN["apps/admin 独立后台"] --> LOGIN["/login 管理员登录"]
  LOGIN --> GUARD["RequireAdmin"]
  GUARD --> LAYOUT["AdminLayout"]

  LAYOUT --> HOME["/dashboard 仪表盘"]
  LAYOUT --> PERM["权限：用户/角色/部门/菜单/岗位"]
  LAYOUT --> PRODUCT["产品管理：产品/分类/价格计划"]
  LAYOUT --> FINANCE["财务：流水/充值/提现/支付方式"]
  LAYOUT --> MEMBER["用户：列表/支付账户"]
  LAYOUT --> ORDER["订单：挂单/持仓/历史"]
  LAYOUT --> LOG["日志：验证码/请求日志/审计"]
  LAYOUT --> CONTENT["内容：公告/新闻/通知"]
  LAYOUT --> CONFIG["配置：站点/上传/短信/邮件/底部导航"]
  LAYOUT --> RISK["风险：风控管理"]
  LAYOUT --> MARKET["行情：品种/状态"]

  ADMIN -. "不放入 apps/web 普通用户端" .-> SEPARATE["独立域名/独立端口/独立权限"]
```

## 11. 页面状态原型图

```mermaid
stateDiagram-v2
  [*] --> Public
  Public: 游客状态
  Public --> PublicMarket: 可查看首页/行情/终端看盘
  Public --> LoginRequired: 访问概览/订单/持仓/资金时需要登录
  LoginRequired --> Login: 点击前往登录
  Login --> Authenticated: 登录成功
  Authenticated --> Loading: 请求账户/订单/持仓/资金
  Loading --> Ready: 数据加载成功
  Loading --> Error: 接口错误或会话异常
  Error --> Loading: 重试
  Ready --> Empty: 无订单/无持仓/无流水
  Ready --> ActionConfirm: 下单/撤单/改单/平仓/提现确认
  ActionConfirm --> Loading: 提交
  Loading --> Ready: 同步完成
  Authenticated --> SessionExpired: token 失效
  SessionExpired --> Login
```

## 12. 响应式布局断点图

```mermaid
flowchart LR
  V1["<= 520px 小手机"] --> M1["底部 5 Tab\n首页/终端/行情/订单/我的"]
  V2["521-767px 大手机"] --> M2["底部 5 Tab\n首页功能宫格两列"]
  V3["768-1023px 平板"] --> T1["顶部品牌 + 紧凑导航\n次级入口进更多菜单"]
  V4[">= 1024px 桌面"] --> D1["顶部横向导航完整展示"]
  V5[">= 1440px 大桌面"] --> D2["内容区 max-width 控制\n交易终端可高密度全屏"]

  M1 --> RULES["规则：44px 触控、无横向滚动、底栏避让"]
  M2 --> RULES
  T1 --> RULES2["规则：导航不换成侧边栏，保持产品一致性"]
  D1 --> RULES3["规则：用户端不显示后台，后台独立入口"]
```

## 13. 后续落地优先级图

```mermaid
flowchart TB
  P0["P0 信息架构修正"] --> P0A["新增官方首页 /"]
  P0 --> P0B["用户端移除 /admin"]
  P0 --> P0C["桌面侧边栏改顶部导航"]
  P0 --> P0D["手机底部导航增加首页，保持 5 项"]

  P1["P1 首页与导航体验"] --> P1A["首页首屏：行情摘要 + 功能宫格 + 终端预览"]
  P1 --> P1B["我的页承载账户二级入口"]
  P1 --> P1C["登录状态和游客状态文案统一"]

  P2["P2 页面一致性"] --> P2A["概览/行情/订单/持仓/资金/安全/设置统一账户工作台样式"]
  P2 --> P2B["终端保持深色高密度专业风格"]
  P2 --> P2C["移动端表格/卡片/底部避让统一"]

  P3["P3 验证与交付"] --> P3A["web:test"]
  P3 --> P3B["web:build"]
  P3 --> P3C["smoke:visual-qa 覆盖 / 首页和核心页面"]
```

## 原型验收标准

- 用户访问 `/` 看到官方首页，而不是直接进入交易终端。
- 用户端导航不出现 `后台`，后台只在 `apps/admin` 独立系统中存在。
- 桌面端 1440px 下核心导航一行可见。
- 手机端底部导航不超过 5 项，且资金/安全/设置通过“我的”或首页功能宫格可达。
- 首页首屏必须出现产品身份、交易终端预览、行情摘要、主要功能入口。
- 交易终端保持专业高密度；账户页保持清晰工作台风格。
- 移动端 375px、390px、430px 无横向滚动，固定底栏不遮挡关键操作。
