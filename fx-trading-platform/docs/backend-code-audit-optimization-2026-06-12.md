# 后端代码审计与优化方向

审计日期：2026-06-12  
审计范围：`fx-trading-platform/backend/src/main/java`、`src/main/resources/db/migration`、`src/test/java`。  
审计方式：静态阅读、全局搜索、Superpowers 子 agent 后端审计、本地 `mvn test` 验证。

## 结论

后端总体分域清楚：`account/admin/audit/auth/chart/common/config/content/execution/finance/ledger/market/risk/trading` 已经按业务模块拆开。数据库访问主体已经迁移到 MyBatis-Plus：39 个 repository 都继承 `FxBaseMapper`，mapper XML 数为 0。当前没有发现明确 SQL 注入漏洞。

主要风险在交易闭环和后台资金闭环：平仓缺少终态保护，挂单和止盈止损调度没有抢占式条件更新或锁，后台资金写操作带 `idempotencyKey` 但未真正幂等。另一个结构性问题是后台通用页面元数据和动作分发服务偏大，后续扩展成本高。

## 已验证结果

- `mvn test`：122 个测试通过，构建成功。
- 后端测试覆盖包含 `ArchitectureRulesTest`、交易服务、持仓服务、挂单执行、止盈止损执行、后台服务等。
- 需要注意：测试全绿不代表没有并发/幂等风险；本次高风险项主要来自静态路径和缺失保护条件。

## 重点发现

### 1. 平仓接口缺少 OPEN 终态保护

严重级别：高  
影响：重复调用平仓可能重复释放保证金、重复记录 PnL 流水。

证据：

- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PositionService.java:94`：`closeOwnedPosition` 是用户平仓和系统平仓的公共路径。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PositionService.java:96`：只按 id 查询 position。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PositionService.java:98`：只校验账户归属。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PositionService.java:111`：直接设置 `CLOSED`。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PositionService.java:121` 和 `:122`：记录保证金释放和交易 PnL。
- 对比：`updateProtection` 在 `PositionService.java:77` 有 `OPEN` 校验，平仓路径没有。

建议：平仓入口先校验 `position.status == OPEN`。更稳的做法是 repository 提供 `closeIfOpen(positionId, accountId, ...)` 条件更新，更新行数为 1 才继续写流水。

### 2. 挂单/止盈止损调度缺少锁或条件抢占

严重级别：高  
影响：多实例部署、任务重入或长事务情况下，可能重复成交、重复创建仓位、重复平仓。

证据：

- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java:42`：定时扫描挂单。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java:44`：遍历所有 `PENDING`。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java:77`：触发后直接复用 `orderFillService.fill`。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/OrderFillService.java:59`：设置订单成交状态。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/OrderFillService.java:76`：创建成交记录。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/OrderFillService.java:88`：创建持仓。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java:32`：遍历所有 `OPEN` position。
- 全局搜索未发现生产代码级 `@Version`、`FOR UPDATE`、乐观锁或状态条件更新。

建议：给执行器增加状态抢占。挂单先 `UPDATE orders SET status='WORKING' WHERE id=? AND status='PENDING'`，成功后再成交；止盈止损先把仓位从 `OPEN` 抢占到 `CLOSING` 或用 `close_if_open` 条件更新。

### 3. 后台资金操作有幂等键但不幂等

严重级别：高  
影响：后台入金、出金、余额调整在请求重试时可能重复改余额。

证据：

- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/dto/request/AdminFundOperationRequest.java:16`：注释说明 `idempotencyKey` 用于后续接入命令幂等表。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java:56`、`:73`、`:97`：写操作接收 idempotency key。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java:163`：每次加载账户。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java:169` 到 `:172`：每次都更新余额。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java:194`：只保存 key，不查询已有操作。
- `fx-trading-platform/backend/src/main/resources/db/migration/V15__admin_finance_management.sql:32`：`idempotency_key` 是普通字段，没有唯一约束。

建议：新增唯一约束 `(account_id, operation_type, idempotency_key)` 或独立命令幂等表；服务入口先查既有结果，唯一冲突时回读已有操作。

### 4. 订单幂等存在并发窗口

严重级别：中高  
影响：并发重复提交同一订单时，“先查后插”可能抛数据库唯一约束异常，而不是稳定返回已有订单。

证据：

- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/OrderService.java:47`：先查 `clientOrderId`。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/trading/service/OrderService.java:49`：再查 `idempotencyKey`。
- `fx-trading-platform/backend/src/main/resources/db/migration/V5__trading_tables.sql:17`：存在 `UNIQUE(user_id, idempotency_key)`。
- `fx-trading-platform/backend/src/main/resources/db/migration/V12__foundation_oms_fields.sql:31`：存在 `ux_orders_user_account_client_order_id`。
- 全局搜索未发现 `DataIntegrityViolationException` 或 `DuplicateKey` 处理。

建议：保留唯一约束，同时捕获唯一冲突并按幂等键回读订单。

### 5. 数据库操作不是 100% MyBatis-Plus，但当前未确认 SQL 注入

严重级别：中  
结论：主体使用 MyBatis-Plus；例外存在，但当前例外 SQL 使用参数绑定。

证据：

- `fx-trading-platform/backend/pom.xml:59` 和 `:64`：引入 `mybatis-plus-spring-boot3-starter` 与 `mybatis-plus-jsqlparser`。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/common/mybatis/FxBaseMapper.java:21`：统一 mapper 继承 `BaseMapper`。
- 本地统计：39 个 `*Repository.java` 均继承 `FxBaseMapper`；mapper XML 数为 0。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/market/service/MarketTestDataService.java:20`：例外使用 `JdbcTemplate`。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/market/service/MarketTestDataService.java:140` 到 `:156`：使用 `?` 参数绑定，没有拼接用户输入。

SQL 注入判断：

- 当前未发现明确 SQL 注入路径。
- 风险点是未来误用动态排序：`FxBaseMapper.findAll(Page, orderColumn, asc)` 允许原始列名进入 `orderBy`。

### 6. 动态排序总体白名单化，但公共方法有误用风险

严重级别：中  
证据：

- `fx-trading-platform/backend/src/main/java/com/fxplatform/common/mybatis/FxBaseMapper.java:59`：`findAll(Page<T> page, String orderColumn, boolean asc)` 暴露原始列名。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/common/mybatis/FxBaseMapper.java:62`：直接 `query.orderBy(true, asc, orderColumn)`。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFeatureQuerySupport.java:29` 到 `:36`：后台通用列表使用 map 白名单。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminRbacService.java:53` 到 `:83`：RBAC 排序列白名单。

建议：删除或收窄 `FxBaseMapper.findAll(Page, orderColumn, asc)`，要求调用方传 `Map<String,String>` 白名单 key，或者只保留无排序版本。

### 7. 审计 JSON 手写构造重复

严重级别：中  
影响：字段转义、结构一致性和可读性不稳定。

证据：

- `fx-trading-platform/backend/src/main/resources/db/migration/V8__audit_tables.sql:8`：审计 details 是 `JSONB`。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java:248`：手写 `details`。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java:264`：手写 escape。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminTradingCommandService.java:154` 到 `:160`：另一份手写 JSON。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFeatureOperationService.java:101` 到 `:112`：已有 `JSONUtil.toJsonStr` 的更稳做法。

建议：抽 `AuditDetailsBuilder` 或 `AuditLogService.record(..., Map<String,Object>)`，统一用 JSON 库序列化。

### 8. 后台页面元数据和动作服务偏大

严重级别：中  
影响：后台新增页面/动作时容易继续堆在巨型 service，违反功能拆分完全的目标。

证据：

- 本地统计：`AdminFeatureCatalogService.java` 约 678 行，`AdminFeatureOperationService.java` 约 502 行，`AdminRbacService.java` 约 411 行。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFeatureCatalogService.java:65` 起硬编码大量页面字段、列和动作。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFeatureOperationService.java:150` 起按 `pageKey/action` 大量分发领域副作用。
- `fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminRbacService.java:85` 到 `:92` 同时持有 role/menu/permission/user-role/data-scope/department/post 多个 repository。

建议：按优先级拆分，不做一次性大重构。先把 `AdminFeatureOperationService` 的 `pageKey/action` 分发改成 `AdminFeatureActionHandler` 注册表；再把 RBAC 的 role/menu/org/post 分为独立 command/query service。

## 解耦合与设计方法评价

已做得较好的部分：

- 交易创建路径已有 `OrderCommandFactory`、`OrderEntityFactory`、`OrderStatusPolicy`、`OrderFillService`、`OrderResponseMapper`。
- 执行侧使用 `ExecutionAdapter`，并有 `SimulatedExecutionAdapter/FixExecutionAdapter/LpExecutionAdapter/BrokerExecutionAdapter` 多实现，属于策略/适配器思路。
- 查询与写入在后台部分 service 已有 `QueryService/CommandService` 分离，例如 market、finance、content、risk。

不足：

- 多个 execution adapter 都是 Spring bean，当前靠 `@Primary` 固定模拟实现；后续接真实执行时建议用配置选择，而不是多个未启用实现默认注册。
- 后台通用操作服务仍是大 if 分发，不够符合开放封闭原则。
- 交易并发和幂等没有形成统一模式。

## 命名与中文注释评价

- 包名和类名总体符合领域拆分：controller/service/repository/entity/dto/enums 清楚。
- 后端 299 个生产 Java 文件中，15 个没有中文字符，包括 `OrderService.java`、`OrderFillService.java`、`RiskCheckService.java`、`SimulatedExecutionAdapter.java` 等核心类。
- 全局命中 142 处模板化注释，如“执行 xxx 业务流程”“处理 xxx 接口请求”。这类注释不是详细注释，解释价值偏低。

建议：不要机械补全所有中文注释。应优先给这些位置补“为什么”：

- 平仓只允许 OPEN 且只能结算一次。
- 挂单执行状态抢占。
- 幂等键如何命中既有结果。
- 动态排序为什么必须白名单。
- 资金写操作为什么必须先落幂等表或唯一约束。

## 后端优化路线

1. 修复平仓终态保护，并新增重复平仓测试。
2. 给挂单成交和止盈止损平仓增加状态抢占或乐观锁。
3. 落地后台资金写操作幂等，避免重试重复入账。
4. 捕获订单唯一约束冲突，回读既有订单。
5. 收窄 `FxBaseMapper` 动态排序入口。
6. 统一审计 JSON 构造。
7. 将 `MarketTestDataService` 的 `JdbcTemplate` upsert 封装成专用 repository 方法，或明确保留原因。
8. 将后台通用动作按 handler 注册表拆分。
9. 补高价值中文注释，删除低价值模板化注释。
