# Java 后端代码审计报告（2026-06-13）

## 审计范围

- 后端目录：`backend/src/main/java/com/fxplatform`
- 测试目录：`backend/src/test/java/com/fxplatform`
- 配置与迁移：`backend/pom.xml`、`backend/src/main/resources/application*.yml`、`backend/src/main/resources/db/migration`
- API 链路：本机 `127.0.0.1:8080` 当前服务，以及新构建 jar 的临时 `127.0.0.1:18080` 实例

## 总体结论

当前 Java 后端已经具备交易平台 demo / CFD 模拟交易系统的主要闭环：注册登录、账户、行情、K 线、下单、成交、持仓、平仓、挂单执行、TP/SL、账本、资金单、后台管理、RBAC、审计、Flyway 迁移、Redis 行情缓存、WebSocket 行情推送、Actuator 健康检查。

但它还不能定义为成熟生产级交易所后端。核心差距是：真实撮合引擎、订单簿撮合深度、清算/强平/风险阶梯、撮合幂等审计、资产托管与对账、市场数据供应商完整接入、运营风控规则、灾备/高可用、监管级审计和压测容量证明仍不完整。现有实现更像“模拟交易 + 后台管理 + 行情演示”的功能完整原型。

## 已修复问题

### 1. 行情 quote API symbol 归一化不一致

复现：`GET /api/market/quotes/EUR-USD` 在旧进程上返回 400，但交易下单链路会把 `EUR-USD` 归一化为 `EURUSD`。

根因：`QuoteService.latestQuote` 直接用原始 path symbol 调用 `SymbolRepository.findBySymbol`，没有复用交易侧的 symbol 归一化规则。

修复：

- `QuoteService.latestQuote` 先归一化 `-`、`_`、`/` 并转大写。
- repository、cache key、Massive adapter、demo quote fallback 统一使用归一化后的 symbol。
- 新增 `QuoteServiceTest.latestQuoteNormalizesCommonSymbolSeparators` 回归测试。

验证：

- `mvn -Dtest=QuoteServiceTest test` 通过，1/1。
- `mvn test` 通过，140/140。
- 临时新实例 `127.0.0.1:18080`：`GET /api/market/quotes/EUR-USD` 返回 `success=true`，`data.symbol=EURUSD`。

## 逐项检查

### 1. 功能完整性与交易所成熟度

已具备：

- 用户认证：注册、登录、refresh、session、logout、me。
- 交易账户：默认 demo account、余额、权益、保证金、账本。
- 行情：symbols、quote、order book、recent trades、candles、WebSocket topic。
- 交易：market / limit / stop，订单幂等键，挂单修改/撤单，订单事件。
- 成交与持仓：trade、position、部分成交字段、费用、滑点、保证金占用。
- 风控：下单保证金检查、杠杆、合约大小。
- 后台：用户、交易、行情、资金、风控、内容、配置、RBAC、审计、表格工具。

不成熟或仍偏 demo：

- `SimulatedExecutionAdapter` 仍是主要执行路径，Massive REST adapter 当前返回 empty fallback。
- `QuoteService` 无外部报价时会使用 demo quote。
- `market.test-data.enabled` 默认 true，会生成 demo realtime quote / candle。
- pending/protective executor 是配置开关，生产语义、重试、实例协调和监控还需要加强。
- 缺少真实撮合引擎、资产冻结/解冻全链路审计、强平/穿仓处理、风险阶梯、交易时段和产品规则完整校验。

结论：功能体验对 demo 和内部验收足够完整，但离成熟交易所仍有显著生产能力差距。

### 2. Bug 检查

已修复：

- quote API 不接受带分隔符 symbol。

未修复但建议排期：

- `UpdatePositionProtectionRequest` 没有 Bean Validation，`TradingController.updatePositionProtection` 也没有 `@Valid`；目前业务层只检查持仓归属和 OPEN 状态，没有校验 SL/TP 与当前价格/方向的关系。
- `GlobalExceptionHandler.handleUnexpected` 直接把 `ex.getMessage()` 暴露给客户端，生产环境可能泄漏内部异常细节。
- 大量 Java 注释是模板化的“执行 xxx 业务流程”描述，信息量低，影响阅读效率，不影响编译。

### 3. 代码冗余

主要冗余点：

- `amount/orZero/accountEquity/details/normalizeSymbol/hasText` 等小工具在多个 service/DTO 内重复。
- 后台 command service 的审计 details 构造已经部分收敛到 `AuditDetailsBuilder`，但多个 `details(...)` 私有方法仍有重复。
- `AdminRbacService` 同时承载 role/menu/department/post/permission/data scope/user role，多职责集中。

不建议现在大拆：当前测试覆盖较完整，交易核心代码没有明显无意义抽象；应先提取小型共用 helper，再按后台子域拆服务。

### 4. 结构规范、可读性、可扩展性、解耦

优点：

- 包结构按领域划分：`auth/account/market/chart/trading/risk/ledger/finance/admin/audit/common`。
- Controller / Service / Repository / DTO / Entity 分层清晰。
- 交易核心有边界：`OrderService` 依赖 `RiskCheckService`、`ExecutionAdapter`、`OrderFillService`、`OrderEventService`。
- 执行适配器抽象存在：`ExecutionAdapter`、`SimulatedExecutionAdapter`、`FixExecutionAdapter`、`LpExecutionAdapter`。
- 后台 catalog 已按 page group 拆分，并有架构测试约束。

问题：

- `admin` 模块有 172 个 Java 文件，复杂度明显集中。
- `AdminRbacService` 411 行，是当前最大 service，建议拆为 RoleService、MenuService、DepartmentPostService、DataScopeService、UserRoleService。
- 部分方法注释偏模板化，描述了方法名本身，不能帮助理解业务约束。
- `application-dev.yml` 和 `application-prod.yml` 还保留 `spring.jpa.show-sql`，与 MyBatis-Plus 架构不一致。

### 5. Hutool 使用

结论：已引入并实际使用 Hutool。

证据：

- `pom.xml` 引入 `cn.hutool:hutool-all`。
- 生产代码使用 `DateUtil`、`StrUtil`、`IdUtil`、`MapUtil`、`CollUtil`、`JSONUtil`。
- `ArchitectureRulesTest.backendUsesMybatisPlusAndHutoolInsteadOfJpa` 已约束生产代码包含 `cn.hutool`。

建议：

- 不要为了“使用 Hutool”而替换所有 JDK 标准 API；只在字符串、集合、JSON、ID、日期工具确实能减少样板代码时使用。
- 可以把重复 `hasText/normalizeSymbol` 收敛为领域工具类，内部继续用 Hutool。

### 6. 数据库操作与 MyBatis-Plus

结论：数据库主路径符合 MyBatis-Plus Java 生态结构。

证据：

- `pom.xml` 使用 `mybatis-plus-spring-boot3-starter` 和 `mybatis-plus-jsqlparser`，未使用 JPA starter。
- `FxPlatformApplication` 使用 `@MapperScan("com.fxplatform.**.repository")`。
- 40 个 Repository 都继承 `FxBaseMapper` 或作为受控 repository。
- `FxBaseMapper` 统一封装 `BaseMapper`、UUID、分页排序白名单。
- `MybatisPlusConfig` 配置分页插件，`AuditFieldFillHandler` 配置审计字段自动填充。

合理例外：

- `RealtimeCandleRepository` 使用 `JdbcTemplate` 做 PostgreSQL `ON CONFLICT` upsert，属于原子 upsert 场景。
- `AdminFundOperationRepository.insertIfAbsent` 使用 MyBatis `@Insert ... ON CONFLICT DO NOTHING`，用于资金幂等抢占，合理。

问题：

- `application-dev.yml` / `application-prod.yml` 的 `spring.jpa.show-sql` 是迁移后遗留配置，应删除或改为 MyBatis 日志配置。

### 7. API 调用链路测试

已验证：

- `GET /actuator/health`：`UP`。
- `npm run smoke:backend`：通过，覆盖注册、账户、市场单、账本、持仓、平仓、挂单不自动成交。
- `npm run smoke:real-trading-loop`：通过，覆盖市场单、挂单执行器、TP/SL 执行器、撤单。
- 临时新 jar `18080`：`GET /api/market/quotes/EUR-USD` 修复后通过。

限制：

- `smoke:real-trading-loop` 的数据库断言被跳过，因为 `DATABASE_URL` 未配置给 smoke。
- admin smoke 的登录/强平部分被跳过，因为没有配置 `ADMIN_SMOKE_EMAIL` / `ADMIN_SMOKE_PASSWORD`。

### 8. 优先 bug 修复状态

本轮已按优先级修复一个真实 API bug，并完成回归测试与 live API 验证。其余发现多为成熟度、结构和可维护性问题，建议分批处理，不在本轮顺手大改。

## 验证命令与结果

- `mvn -Dtest=QuoteServiceTest test`：通过，1 tests。
- `mvn test`：通过，140 tests。
- `npm run verify:architecture`：通过。
- `npm run smoke:backend`：通过。
- `npm run smoke:real-trading-loop`：通过。
- `mvn -DskipTests package`：通过。
- 临时 `java -jar target/fx-platform-backend-0.1.0.jar --server.port=18080`：health `UP`，`/api/market/quotes/EUR-USD` 返回 `EURUSD`。

## 优化建议优先级

### P0 / P1

1. 为 `UpdatePositionProtectionRequest` 加校验：SL/TP 正数、方向关系、是否允许立即触发。
2. `GlobalExceptionHandler.handleUnexpected` 改为通用错误消息，内部异常写日志。
3. 清理 `application-dev.yml` / `application-prod.yml` 的 JPA 遗留配置。
4. 为 admin smoke 配置稳定 admin 账号，补齐后台 API 真实链路验证。

### P2

1. 抽出 `TradingMath` 或 `MoneyAmount` 工具，统一 `amount/orZero/accountEquity`。
2. 抽出 `SymbolNormalizer`，交易、行情、K 线、WebSocket topic 共用。
3. 拆分 `AdminRbacService`，降低单类职责和测试维护成本。
4. 梳理后台 action handler 的 payload 校验，减少 `Map<String,Object>` 的弱类型边界。

### P3

1. 清理模板化“执行 xxx 业务流程”注释，保留能解释业务约束、并发边界、幂等语义的注释。
2. 将 demo quote / demo execution 和真实 provider 模式做明确 profile 隔离。
3. 增加数据库集成测试或 Testcontainers，覆盖 Flyway + MyBatis-Plus + PostgreSQL upsert。
4. 增加生产前压测、幂等重放测试、并发下单/撤单/平仓测试。
