# Admin 交易路径实验室设计

> 日期：2026-07-17
> 状态：依据用户提供的完整需求基线直接批准实施
> 工作区：`C:\workspace\tradingWeb`
> 业务项目：`fx-trading-platform`
> 约束：保留当前脏工作树；不创建 Git 分支或 Commit；不连接真实 broker、FIX、LP、生产行情或真实账户

## 1. 目标

在现有 `fx-trading-platform` 内新增仅限 Admin 使用的“交易路径实验室”，同时提供：

- 浏览器内独立实现的现货与 USDT 线性永续交易路径 Oracle。
- 可编辑、可保存、可确定性随机生成的多品种场景时间线。
- 完全隔离的 validation PostgreSQL、Redis 和 Spring Boot 后端。
- 通过真实 HTTP、认证、Controller、Service、数据库、钱包和账本执行的 Demo 回放。
- 固定顺序的虚拟时钟、行情 Tick、撮合、条件单、资金费和强平编排。
- SSE 运行进度，以及暂停、继续、取消和可恢复串行队列。
- 不做自动 Pass/Fail 的本地预期与 API 实际结果并列展示。
- 分块、压缩、可流式下载和打印的原始 JSON 报告。
- 细粒度 Admin 权限、环境控制白名单和完整审计。

浏览器 Oracle 与后端交易计算只共享 API 数据契约，不共享核心计算实现。

## 2. 当前基线与必须保留的工作

当前仓库已经具备较完整的 Demo 交易底座：

- 现货移动加权平均成本、永续 ONE_WAY/HEDGE、CROSS/ISOLATED。
- 市价、限价、STOP_MARKET、现货 OCO、TP/SL、Reduce Only。
- 钱包余额、资产账本、现金账本和账户汇总。
- 资金费、逐仓/全仓风险与全量强平。
- `clientOrderId`、请求 fingerprint、数据库唯一约束和并发锁。
- Admin RBAC、按钮权限、审计与高风险二次确认。
- 独立 Java 测试 Oracle、191 个固定场景目录和安全数据库 runner。

当前工作树存在大量用户修改和未跟踪文件，尤其集中于：

- `backend/src/main/java/com/fxplatform/trading/**`
- `backend/src/test/java/com/fxplatform/trading/**`
- `backend/src/test/java/com/fxplatform/trading/scenario/**`
- `scripts/smoke-usdt-demo-browser.mjs`
- `scripts/run-spot-perp-scenario-tests.ps1`

实现时必须在这些改动之上做窄幅追加，不得重写或恢复用户文件。现有 Java 场景目录可复用场景词汇、独立公式和测试矩阵，但现有 `ScenarioExecutor` 直接调用 Service/JDBC，不能作为实验室真实 HTTP 编排器。

## 3. 范围拆分

该目标包含四个相互依赖但边界清晰的子项目：

1. **Demo 交易能力补齐**
   - 补充 Stop Limit、IOC、FOK、Post Only、追踪止损、可配置手续费/滑点和深度部分成交。
   - 统一目标要求的 USDT 手续费口径。
   - 保持默认 Demo 行为兼容，只有显式配置深度模式时启用部分成交。

2. **隔离验证数据面与编排控制面**
   - 新增 validation profile、Docker Compose、受控行情、虚拟时钟、内部 reset/seed/system-step API。
   - 主后台持有场景、队列、SSE、报告与审计；所有用户交易动作通过 validation-backend 公共 HTTP API。

3. **Admin 本地 Oracle 与实验室页面**
   - 新增 `/trading/lab`。
   - 浏览器使用 TypeScript + `BigInt` 定点十进制独立计算。
   - 提供场景时间线、随机生成、图表、运行控制和报告查看。

4. **端到端验收与运行安全**
   - Supervisor 固定白名单。
   - 完全隔离、失败固化、清理恢复、报告流式导出和大报告打印确认。
   - 固定矩阵、随机复现、真实 HTTP 回放和浏览器 E2E。

实施顺序严格按以上四阶段推进，避免同时修改同一交易核心文件。

## 4. 架构方案比较

### 4.1 方案 A：主后台控制面 + 同代码 validation 数据面 + 独立 Supervisor

```text
Admin Browser
  -> Main Backend /api/admin/trading-lab/**
       -> PostgreSQL 场景、队列、报告元数据和报告块
       -> SSE
       -> Validation Supervisor HTTP
       -> Validation Backend HTTP
            -> 公共 Auth / Account / Trading API
            -> validation 专属 reset / seed / market / system-step API
            -> validation PostgreSQL / Redis
```

优点：

- 复用当前 Spring Boot、MyBatis-Plus、Flyway、认证和交易服务。
- 浏览器只接触主后台，权限和审计边界清晰。
- validation-backend 运行相同生产代码，能证明真实 Controller/Service/DB 链路。
- 主后台不挂载 Docker Socket；Supervisor 可独立最小化授权。
- 新增模块仍位于现有仓库和现有后端，不创建第二套业务系统。

缺点：

- 同一代码库需要通过 profile 严格区分主后台与 validation 专属 Bean。
- 主后台编排器需要谨慎处理内部用户凭据和不确定 HTTP 结果。

这是推荐方案。

### 4.2 方案 B：新增独立编排微服务

新增单独 Java/Node 服务负责队列、虚拟时钟、报告和 validation HTTP 回放。

优点是进程隔离更强；缺点是引入新的部署单元、认证模型、数据库访问层和重复基础设施，超出当前项目的最小演进方向，也增加跨服务一致性成本。

不采用。

### 4.3 方案 C：浏览器逐操作驱动主后台代理

浏览器每个虚拟秒或每个动作调用一次主后台，再由主后台代理 validation。

实现较少，但违反“浏览器只调用一次启动价格路径 API”、后台持续虚拟时间、可靠暂停/恢复和断线后继续运行要求；大报告也会受浏览器生命周期限制。

不采用。

## 5. 代码和模块边界

### 5.1 主后台新增 `com.fxplatform.tradinglab`

建议包结构：

```text
com.fxplatform.tradinglab
  admin/
    TradingLabAdminController
    TradingLabEnvironmentController
    TradingLabReportController
  application/
    TradingLabScenarioService
    TradingLabRunService
    TradingLabQueueWorker
    TradingLabRunCoordinator
    TradingLabStateMachine
    TradingLabReportWriter
    TradingLabSseService
  client/
    ValidationBackendClient
    ValidationSupervisorClient
  domain/
    scenario/
    run/
    report/
  persistence/
    entity/
    repository/
```

硬性边界：

- `tradinglab.application` 不得注入 `OrderService`、`FundingService`、`LiquidationService`、钱包或账本 Service。
- 主后台协调器只能通过 `ValidationBackendClient` 发出 HTTP。
- 主后台只负责排队、一次启动、控制、事件镜像、报告持久化和 Admin SSE，不逐 Tick 调用交易 API。
- 主后台数据库只保存正式场景、运行状态、报告元数据、报告块和审计，不保存 validation 账户的业务真值。

### 5.2 validation 专属 `com.fxplatform.validation`

建议包结构：

```text
com.fxplatform.validation
  controller/
    ValidationResetController
    ValidationAccountSeedController
    ValidationMarketController
    ValidationSystemStepController
    ValidationStateController
  service/
    ValidationResetService
    ValidationAccountSeedService
    ValidationRunEngine
    ValidationLoopbackHttpClient
    ValidationRunEventStore
    ValidationMarketClock
    ValidationMatchingCoordinator
    ValidationSystemStepService
  security/
    ValidationInternalAuthenticationFilter
    ValidationNetworkGuard
```

这些 Bean 只在 `validation` profile 注册。

validation 专属 API 可以执行环境 reset、初始余额 seed、受控行情、运行控制和主动系统步骤，但不得代替公共交易 API 创建订单、持仓或成交。`ValidationRunEngine` 在 validation-backend 内持续推进虚拟时钟，并通过 loopback HTTP 调用同一进程的公共 `/api/auth/**`、`/api/accounts/**`、`/api/trading/**`、查询 API 以及受限内部 system-step API；禁止直接调用交易内部 Service。

## 6. 权限与审计

新增精确 authority：

- `TRADING_LAB_VIEW`
- `TRADING_LAB_EXECUTE`
- `SUPER_ADMIN`

`AdminAuthorityService` 除菜单和按钮 authority 外，还发布启用 RBAC role 的 `role_code`。
`SUPER_ADMIN` 仅信任 V61 固定 UUID `00000000-0000-0000-0000-000000000061`、启用且带
`system_managed = true` 迁移来源标记的种子角色，不得由同名历史角色、菜单键或按钮值合成。
V61 同时使用固定菜单/权限行 UUID `...0062`、`...0063`；迁移前若历史 role code、menu
permission key 或 button 在去除边缘空格/控制字符后占用三个新增 authority，或存在部分固定
seed、属性不符、固定 permission ID/固定 pair 冲突，则事务失败并要求人工处置。只有完整匹配
规范且角色具有迁移来源标记的三行图允许幂等重放；既有用户绑定仅在该受信图建立后保留。

权限规则：

| 能力 | authority |
|---|---|
| 查看页面、场景、运行与报告 | `TRADING_LAB_VIEW` |
| 保存场景、启动模拟、暂停、继续、取消、负向模式 | `TRADING_LAB_EXECUTE` |
| start/stop/restart validation 环境 | `SUPER_ADMIN` |
| 确认打印超过 50 MB 的报告 | `SUPER_ADMIN` |

所有入口仍位于 `/api/admin/**`，并在 Controller 方法上使用 `@PreAuthorize`。

新增实验室审计表或专属实体，直接记录：

- `actorId`
- 可信客户端 IP
- `requestId`
- `scenarioId`
- `runId`
- `action`
- `result`
- `details`
- `createdAt`

IP 只从配置过的可信代理头读取，否则使用 socket remote address。认证凭据、Cookie、Token、
密码和内部密钥不得写入审计或报告。递归清洗会规范化键名，并保守删除任何包含
authorization、cookie、password、token、API-key、secret、access-key、auth-code、
credential、private-key 或 encryption-key 片段的组合字段，例如
`MASSIVE_S3_ACCESS_KEY_ID`、`access-key-id`、`security.config.encryption-key`、
`authCodeValue`、`credentialBundle`、`privateKeyPem`、`secretAccessKey`、`passwordHash`、
`authorizationHeader` 和 `clientSecretValue`。

## 7. 数据模型与 Flyway

Phase 1 已将连续 Flyway 链推进到 `V59`；Phase 2 的实验室迁移从 `V60` 开始。不得复用或
重命名 Phase 1 的 `V54` 至 `V59`。

建议新建 `trading_lab` schema：

### 7.1 `trading_lab.scenarios`

- `id`
- `name`
- `description`
- `status`
- `negative_mode`
- `seed`
- `model_version`
- `scenario_json`
- `config_snapshot_json`
- `config_snapshot_hash`
- `symbol_config_version`
- `code_version`
- `created_by`
- `updated_by`
- `created_at`
- `updated_at`
- `version`

运行创建后使用不可变快照，不再直接读取可变场景行。

### 7.2 `trading_lab.runs`

- `id`
- `scenario_id`
- `state`
- `queue_sequence`
- `lease_owner`
- `lease_until`
- `cancel_requested`
- `pause_requested`
- `virtual_started_at`
- `virtual_current_at`
- `processed_ticks`
- `total_ticks`
- `speed_multiplier`
- `current_step`
- `failure_code`
- `failure_message`
- `report_id`
- `created_by`
- `created_at`
- `updated_at`
- `started_at`
- `finished_at`
- `version`

数据库只允许一个持有有效租约的 `RUNNING/RESETTING/CLEANING` 任务。其他任务保持 `QUEUED`。
运行创建时必须关联唯一报告；终态报告被显式删除或过期清理后，`report_id` 由外键
`ON DELETE SET NULL` 清空，运行、转换和事件历史继续保留。

### 7.3 `trading_lab.run_transitions`

记录每次状态转换、版本、原因、真实时间、虚拟时间和幂等键，用于恢复和报告。

### 7.4 `trading_lab.reports`

- 元数据、状态、原始字节数、压缩字节数、默认保留时间、永久保留标记。
- 失败和取消同样保存。

### 7.5 `trading_lab.report_chunks`

- `report_id`
- `section`
- `sequence`
- `encoding`
- `uncompressed_bytes`
- `compressed_bytes`
- `payload BYTEA`
- `checksum`

每块保存独立 GZIP 压缩的 UTF-8 JSON/NDJSON。唯一键为 `(report_id, section, sequence)`。写入端按大小阈值或事件数量及时 flush，禁止在内存构造完整报告。

### 7.6 `trading_lab.audit_events`

保存实验室专属 actor/IP/scenario/run/action/result 数据，并通过 `requestId` 关联通用审计日志。

## 8. 配置快照与版本

`GET /api/admin/trading-lab/config` 返回只读配置：

- 启用的 `CRYPTO_SPOT` 和 `LINEAR_PERP` 品种。
- 价格、数量精度和 tick/step。
- 最小数量、最大数量和最小名义价值。
- 默认 maker/taker、强平手续费和滑点。
- 风险档位、初始保证金率、维持保证金率和最大杠杆。
- `modelVersion`、品种配置版本和后端 `codeVersion`。

浏览器加载后保存规范化 JSON，并用 Web Crypto `SHA-256` 生成 `configSnapshotHash`。保存场景和创建运行时，后端再次规范化和校验 hash；运行记录冻结完整快照。

`codeVersion` 优先来自构建环境变量；本地没有时使用 Maven build 信息和明确的 `working-tree` 标记，不能伪造 Git commit。

## 9. 浏览器本地 Oracle

### 9.1 数值实现

新增独立 TypeScript 模块，不导入后端 Java Oracle 或生产计算代码：

```text
apps/admin/src/features/tradingLab/oracle/
  decimal.ts
  spotOracle.ts
  perpetualOracle.ts
  riskOracle.ts
  liquidationOracle.ts
```

`decimal.ts` 使用字符串解析和 `BigInt` 保存整数尾数与 scale，提供显式：

- 加减乘除。
- 向下、向上、四舍五入和指定 scale。
- tick/step 对齐。
- JSON 字符串输出。

任何金额、价格、数量、手续费、保证金和 PnL 都不得转成 JS `number` 参与计算；`number` 只用于图表坐标和非资金 UI。

### 9.2 现货规则

- 移动加权平均成本。
- 卖出后剩余平均成本不变。
- 独立显示持仓平均成本、累计净投入和回本价。
- 首版不实现 FIFO。
- 所有手续费统一从 USDT 扣除。
- 卖出已实现盈亏、手续费和净收益分开记录。

该手续费口径会同步修改后端 Demo 交易实现和既有测试，不能只在 Oracle 中伪装。

### 9.3 永续规则

- 同向加仓加权平均开仓价。
- 部分减仓保持开仓均价。
- 反向开仓先平旧方向，再以同一成交价建立剩余方向。
- ONE_WAY 使用 BOTH 槽；HEDGE 使用 LONG/SHORT 独立槽。
- 毛盈亏、手续费、资金费、净盈亏分开。
- 逐仓保证金、全仓共享 USDT、账户权益、风险率、维持保证金和预计强平价。
- 多品种全仓预计强平价明确采用“其他品种 mark 不变”假设。
- 价格达到强平条件时执行全量平仓和强平手续费；不实现部分强平、保险基金和 ADL。

### 9.4 本地输出

每个时间线动作后生成不可变本地快照：

- orders
- trades
- spotPositions
- perpetualPositions
- wallets
- accountSummary
- ledgerProjection
- risk
- warnings

产品页面只并列显示本地预期和实际 API 数据，不自动判断 Pass/Fail。

## 10. 核心 Demo 交易能力扩展

目标明确要求的平台能力扩展按以下项目自有语义实现，不复制交易所完整模型。

### 10.1 Time in force 和 Post Only

- `GTC`：保留现有行为。
- `IOC`：立即按当前可用深度成交，剩余数量取消；允许部分成交。
- `FOK`：提交时必须能立即全量成交，否则整单取消且无成交。
- `Post Only`：如果提交时会立即成交，返回明确拒绝码；否则进入挂单。

默认简单撮合具有足够单档流动性，因此 IOC/FOK 通常全量成交；深度模式按场景流动性计算。

### 10.2 Stop Limit

- 包含 `triggerPrice` 和 `limitPrice`。
- 触发后转为普通限价单，不保证成交。
- 现货默认使用 `last`；永续默认使用 `mark`。场景可以显式选择支持的价格类型。

### 10.3 追踪止损

- 配置激活价、回撤绝对值或回撤比例，两者只能选一。
- 激活后持续维护有利方向极值。
- 触发后生成标准系统 `STOP_MARKET` 关闭单。
- 状态、极值、激活和触发 Tick 全部写入报告。

### 10.4 简单与深度撮合

- `SIMPLE`：买按 ask、卖按 bid，加场景滑点，一次完全成交。
- `DEPTH`：使用多档订单簿、每秒流动性和每秒最大成交量，生成一个或多个 Trade，支持 `PARTIALLY_FILLED`。

普通 Demo 默认仍为 `SIMPLE`；只有 validation 运行的冻结配置可以选择 `DEPTH`，避免无意改变现有用户体验。

### 10.5 可配置费用和滑点

默认值继续使用当前平台费率，但 validation 运行可以通过冻结配置设置 maker、taker、强平手续费、资金费率和滑点。运行中不可修改。

## 11. 场景和时间线

### 11.1 浏览器草稿

使用原生 IndexedDB 保存本地草稿，支持：

- 自动保存。
- 新增、删除、复制、编辑。
- 拖动排序、上下移动。
- JSON 导入导出。
- 从后台场景复制。

场景进入 `VALIDATING` 后冻结；已启动运行的快照不可修改。

### 11.2 动作模型

动作包含：

- `id`
- `sequence`
- `type`
- `symbol`
- `productType`
- `trigger`
- `parameters`
- `overrides`
- `expectedError`

触发器支持：

- 指定虚拟时间。
- 指定 bid/ask/last/mark/index 条件。
- 上一动作完成后的延迟。
- `ALL`/`ANY` 组合。

同一虚拟秒内用户动作严格按 `sequence` 执行。系统事件使用固定顺序：

1. 生成并发布完整 Tick。
2. 更新追踪止损极值。
3. 撮合普通挂单。
4. 触发 Stop/Stop Limit/OCO/TP/SL/追踪止损。
5. 执行本秒用户动作。
6. 执行手动或到期资金费。
7. 重新计算风险并执行强平。
8. 查询并保存完整 checkpoint。

该顺序写入 model version 和报告，测试不得依赖隐式线程调度顺序。

## 12. 确定性随机生成

浏览器使用固定、文档化的 32 位 PRNG 算法和规范化参数序列：

- 输入相同 seed、品种集合、动作数量、价格范围、杠杆范围、费率范围和模式。
- 输出字节级一致的规范化 scenario JSON。
- “重新随机”仅生成新 seed，再按同一算法重建。
- 默认只生成合法场景。
- 负向模式显式生成一个或多个带 `expectedError` 的非法动作。

随机生成器只负责生成可编辑场景，不直接启动运行。

## 13. 价格路径与虚拟时钟

### 13.1 价格模型

每个 Tick 必须包含：

- `bid（买一价）`
- `ask（卖一价）`
- `last（最新价）`
- `mark（标记价）`
- `index（指数价）`
- 虚拟时间和 sequence

简单模式根据起点、终点、点差、基差和随机参数生成五类价格。

高级模式允许五类价格分别配置路径点、持续秒数、偏移、波动和速度。

关闭“模拟真实行情”时，上涨段单调不下降、下跌段单调不上升。开启时在目标轨迹附近确定性波动。每个路径段的最后一个 Tick 强制精确等于目标。

### 13.2 时钟

- 所有品种共享一个虚拟时钟。
- 每个虚拟秒恰好生成一条完整 Tick。
- 5 分钟恰好 300 Tick。
- 速度倍率只影响真实等待，不影响虚拟时间和输出。
- 无单次时长上限。
- 长任务按 chunk 写报告，不保留无限 Tick 列表。

浏览器只调用一次运行创建/启动 API。主后台领取队列任务后只向 validation-backend 调用一次内部 run start API；`ValidationRunEngine` 随后按速度倍率持续生成虚拟秒 Tick、执行时间线并持久化递增事件。主后台通过可续传的内部事件流镜像进度、Tick 摘要、步骤和 API 结果，再向 Admin 浏览器提供 SSE。

## 14. validation Docker 环境

新增：

```text
infra/docker-compose.validation.yml
backend/Dockerfile
backend/src/main/resources/application-validation.yml
```

服务：

- `validation-postgres`
- `validation-redis`
- `validation-backend`

隔离要求：

- 独立 Compose project、内部 network、数据库用户、数据库、Redis、volume 和端口。
- PostgreSQL、Redis 不复用主站容器或 volume。
- validation-backend 只挂 validation internal network，并将 HTTP 端口绑定到 `127.0.0.1`。
- runtime network 使用 Docker `internal: true`，阻止访问公网 provider。
- Binance、OKX、Massive 和 live execution endpoint 在 validation profile 中显式禁用或指向不可达地址。
- `execution.mode=demo`，启动安全校验拒绝其他 mode。
- 所有 scheduler Bean 在 validation profile 中禁用，不只依赖 `spring.task.scheduling.enabled=false`。
- validation-backend 提供 healthcheck，数据库和 Redis 提供 readiness。

重置顺序：

1. 拒绝新动作并等待当前受控步骤结束。
2. 清空 validation 数据库业务 schema，并重新执行 Flyway。
3. `FLUSHDB` 独立 Redis。
4. 清空 JVM 中的行情、队列、锁、时钟和 session 状态。
5. 验证数据库、Redis、内存 generation 一致。

失败或取消时先关闭报告 chunk，再重置。

## 15. Validation Supervisor

Supervisor 是独立的最小 Node.js 标准库服务：

```text
scripts/validation-supervisor.mjs
```

特性：

- 默认绑定 `127.0.0.1`。
- 使用环境变量内部 bearer token。
- Compose 文件绝对路径、project name 和三个服务名写死在代码/启动配置中。
- 请求体只能包含固定 enum action，不接收命令、路径、服务名或额外参数。
- 使用 `spawn` 参数数组调用固定 Docker Compose 命令，不启用 shell。

唯一动作：

- `status`
- `start`
- `stop`
- `restart`
- `health`

主后台只通过 HTTP 调用 Supervisor，本身不挂载 Docker Socket、不执行 Docker 命令。

有运行中任务时，主后台拒绝 stop/restart；只有先显式取消、固化部分报告并完成 CLEANING 后才能调用。

Supervisor 单元测试覆盖：

- 未知 action。
- shell 元字符、路径、服务名和额外参数注入。
- 缺少或错误认证。
- 固定命令映射。
- 仅 loopback 监听。

## 16. 运行流程与状态机

状态：

```text
DRAFT
VALIDATING
QUEUED
RESETTING
RUNNING
PAUSED
CANCELLING
CANCELLED
FAILED
COMPLETED
CLEANING
```

合法转换由单一 `TradingLabStateMachine` 定义，并使用数据库版本号和幂等 transition key。

运行流程：

1. 浏览器提交场景快照和本地 Oracle 结果。
2. 主后台校验权限、场景、配置 hash 和负向模式。
3. 创建 `runId`、报告元数据和 `QUEUED` 记录。
4. Queue worker 获取唯一租约。
5. 检查 Supervisor 和 validation health。
6. `RESETTING`：调用 validation reset。
7. 向 validation-backend 调用一次内部 run start API，传入冻结场景、配置快照、本地计算和随机种子。
8. `ValidationRunEngine` 通过 loopback 公共 `/api/auth/register` 创建唯一测试用户和 session。
9. `ValidationRunEngine` 通过内部 seed HTTP API 设置冻结的初始 USDT/现货资产余额。
10. `ValidationRunEngine` 通过 loopback 公共 HTTP 设置 position mode、margin mode、leverage。
11. `ValidationRunEngine` 启动一次价格路径，并按虚拟秒通过 loopback HTTP 执行 Tick、用户动作和系统步骤。
12. 每个动作后通过 loopback 公共 HTTP 查询订单、成交、持仓、钱包、账本、账户汇总和风险。
13. validation 持久化递增 run event；主后台按 sequence 镜像并持续写报告 chunk 和 Admin SSE。
14. 完成、失败或取消时关闭报告。
15. `CLEANING`：再次 reset validation。
16. 保存 cleanup 结果和主后台报告元数据。

暂停只在 Tick/动作边界生效。主后台把暂停、继续、取消 REST 控制转发到 validation run API；取消设置持久化 flag，`ValidationRunEngine` 在最近安全边界转为 `CANCELLING`，固化未执行步骤和当前状态后清理。

## 17. HTTP、幂等与不确定结果

`ValidationRunEngine` 发出的每个交易 POST 使用确定性幂等键：

```text
runId:timelineActionId:attempt
```

网络超时后：

1. 不立即重发。
2. 使用 `clientOrderId`、requestId 或资源查询 API 检查结果。
3. 已存在则记录 recovered result。
4. 明确不存在才以同一幂等键重放。
5. 同 key 不同 fingerprint 视为运行失败并记录冲突。

报告对每个 HTTP hop 保存：

- sequence
- environment
- method
- URL
- virtualTime
- realTime
- sanitized request/response headers
- request/response body
- status
- durationMs
- traceId
- correlationId
- runId
- exception

脱敏器在写报告前移除：

- `Authorization`
- `Cookie`
- 密码
- Token
- 数据库密码
- Supervisor/validation 内部密钥
- access key / auth code / credential / private key / encryption key

认证信息只保留类型、actorId、scopes、expiresAt 和 SHA-256 fingerprint。

## 18. SSE

主后台新增：

```text
GET /api/admin/trading-lab/runs/{runId}/events
Content-Type: text/event-stream
```

使用 Spring MVC `SseEmitter`，事件带单调递增 `id`。主后台先从 validation 内部事件流按 sequence 镜像到主数据库，再向 Admin 发送：

- `state`
- `progress`
- `tick`
- `action`
- `api-trace`
- `checkpoint`
- `warning`
- `error`
- `complete`

客户端用带 Bearer header 的 fetch-stream adapter，不使用原生 `EventSource`。断线重连带 `Last-Event-ID`；主后台先从持久化 run event 补发，再切到实时订阅。

高频 Tick 不把完整报告数据全部推给浏览器。SSE 发送图表所需 Tick 和摘要，完整原始内容只进 report chunk。

## 19. Admin 页面

新增路由与菜单：

```text
/trading/lab
交易路径实验室
```

页面采用路由级 `lazy()` 拆包，避免把图表和 Oracle 加入现有 Admin 首屏 bundle。

最低宽度 1280px；更窄时显示不可操作提示，不专门适配手机。

页面布局：

1. 顶部：环境状态、场景状态、权限、运行按钮、虚拟时钟和进度。
2. 左栏：场景元数据、品种、初始余额、默认参数、随机生成。
3. 中栏：可拖动时间线和动作编辑器。
4. 右栏：本地预期摘要、风险与校验错误。
5. 下部：多品种图表、API 实际结果、checkpoint、原始报告。

复用当前 Admin 视觉变量、PageHeader、StateBlock 和高风险对话框。新增专属 API 模块，不能继续扩大 1548 行的 `adminApi.ts`。

### 19.1 图表

Admin 显式接入外层仓库的 KLineCharts 构建产物，并复用现有 `ensure-klinecharts-dist` 机制。

实验室图表 wrapper 只实现目标所需能力：

- Tick 折线/K 线切换。
- bid/ask/last/mark/index 开关。
- 多品种标签页和 K 线周期。
- 缩放、拖动、十字光标和虚拟时间定位。
- 开仓、加仓、减仓、TP、SL、资金费和强平标记。
- 本地预期路径和真实 API 路径分别查看。

高频 Tick 使用 ref 直接更新 chart，避免每 Tick 触发整页 React render。

### 19.2 打印

- 先查询 report size 和预计页数。
- 小于等于 50 MB：打开专用打印窗口，流式写入格式化原始 JSON 文本，完成后调用 `window.print()`。
- 大于 50 MB：要求 `SUPER_ADMIN` 二次确认，并向后端发送一次性确认 token。
- 不截断字段、不生成摘要。
- 页面本身不一次性把完整报告放入 React state 或 DOM。

## 20. 报告 Schema

导出的顶层顺序固定：

```json
{
  "metadata": {},
  "actor": {},
  "environment": {},
  "scenario": {},
  "modelVersion": "",
  "configSnapshot": {},
  "localCalculation": {},
  "lifecycle": [],
  "apiTrace": [],
  "marketTicks": [],
  "checkpoints": [],
  "actualState": {},
  "errors": [],
  "cleanup": {}
}
```

数据库内部可以按 section 保存 NDJSON chunk；下载时由 `StreamingResponseBody` 顺序组装为一个合法 JSON 对象。任何 section 都不需要一次性载入内存。

默认保留 30 天。清理 scheduler 默认关闭，只有显式启用时删除未永久保留且已过期的报告；删除报告会级联删除 chunks，并把关联终态 run 的 `report_id` 置空而不删除运行历史。测试和 validation profile 始终关闭。

## 21. 错误处理与恢复

- 场景校验错误：保持 DRAFT，中文字段级错误，不创建运行。
- 环境未启动：普通管理员禁用“真实模拟”；SUPER_ADMIN 可显式 start。
- validation health 失败：运行保持 QUEUED 或转 FAILED，不能静默启动 Docker。
- HTTP 4xx：按正常或负向动作记录；普通模式的意外 4xx 使运行失败。
- HTTP 5xx/网络错误：先按幂等查询恢复，再决定失败。
- 进程重启：Queue worker 根据租约和最后 transition 恢复；报告块使用唯一键防重复。
- 失败/取消：先固化 failure point、stack、未执行步骤和 actual state，再 reset。
- cleanup 失败：报告仍保存，run 标记 FAILED 并明确 `cleanupFailed=true`，不得宣称环境干净。

负向模式只记录预期错误与真实 HTTP 结果，不在产品 UI 中自动判断通过或失败；自动化测试可以断言。

## 22. 测试策略

所有功能遵循 RED → GREEN：

### 22.1 后端单元和合同测试

- 权限、RBAC role code 和越权。
- 状态机合法/非法转换、租约、幂等和恢复。
- 报告分块、压缩、checksum、流式组装和脱敏。
- Supervisor 固定白名单与注入拒绝。
- validation profile demo-only、scheduler-off、外联 fail-closed。
- 虚拟时钟、路径最终值、单调/波动、每秒一 Tick。
- IOC/FOK/Post Only/Stop Limit/追踪止损/深度部分成交。
- USDT 手续费、钱包余额、资产账本和账户汇总。

### 22.2 HTTP 集成测试

使用独立 validation Compose，至少覆盖：

- 现货买卖和成本。
- 逐仓永续。
- 多品种全仓。
- TP/SL。
- 资金费。
- 负向请求。
- 暂停、继续、取消。
- 失败后部分报告和 reset。
- 两次运行无污染。

测试不得用 Service 或 JDBC 直接创建订单、持仓和成交。

### 22.3 浏览器和 Admin 测试

- 本地 BigInt Oracle 固定公式。
- 确定性随机 seed。
- IndexedDB 草稿和 JSON 导入导出。
- 时间线编辑和冻结。
- 图表 Tick/K 线、标记和多品种。
- fetch-SSE 重连。
- 本地预期/实际并列，无 Pass/Fail。
- 大报告分块下载和 50 MB 打印权限。
- 1280px 桌面 E2E。

### 22.4 架构检查

扩展 `verify-architecture.mjs`，静态验证：

- Admin 只调用 `/api/admin/**`。
- 主编排器不依赖交易 Service。
- validation 专属 Controller 受 profile 和内部认证保护。
- validation Compose 独立 network/volume/user/port。
- validation profile 关闭 live provider 和 scheduler。
- Supervisor 不接受任意命令、路径或服务名。
- 报告脱敏字段和分块表存在。

## 23. 最低验收命令

最终必须真实运行并报告：

```powershell
docker compose -f fx-trading-platform/infra/docker-compose.validation.yml up -d
cd fx-trading-platform/backend && mvn test
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web run build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

并补充：

- Admin tests。
- 显式 `*IT` validation HTTP 测试并解析 Surefire XML。
- Supervisor 单元/运行测试。
- validation reset/health/stop。
- 浏览器 E2E。
- 报告下载/打印和大报告测试。

Docker、外部权限或本机浏览器依赖不可用时必须报告为明确 blocker，不能伪装通过。

## 24. 明确不做

- 不连接真实 broker、FIX、LP、生产行情或真实账户。
- 不实现 BTC/ETH/USDC 多资产抵押。
- 不实现 FIFO。
- 不实现部分强平、保险基金或 ADL。
- 不复制 Binance/OKX 完整交易模型。
- 不在产品 UI 自动比较本地与实际结果或生成 Pass/Fail。
- 不让运行时调用 Codex或自动修改代码。
- 不自动创建分支、Commit、Push 或 PR。

## 25. 设计自审结论

- 无未决占位或依赖后续产品选择的空白项。
- 权限、HTTP 边界、数据归属、报告流式存储和环境控制边界一致。
- 高级订单与 Spot USDT 手续费被明确视为本目标授权的 Demo 业务能力扩展，并要求同步测试和 Web/Admin 兼容检查。
- 超大目标已拆成四个按依赖排序的实施阶段，可分别 RED → GREEN 并最终做完整验收。
- 用户需求已明确要求持续实施，因此本设计不增加额外人工审批停顿。
