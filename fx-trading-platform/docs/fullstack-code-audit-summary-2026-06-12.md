# 前后端全量代码审计总结

审计日期：2026-06-12  
审计范围：根目录 KLineCharts 图表库、`fx-trading-platform/apps/web` 前端、`fx-trading-platform/backend` 后端。  
审计方式：Superpowers 并行子 agent、主线程静态复核、全局搜索、测试和构建验证。

## 验证命令

| 命令 | 结果 |
| --- | --- |
| `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"` | 通过 |
| `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"` | 210 个测试通过 |
| `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"` | 通过 |
| `cmd.exe /d /s /c "pnpm.cmd type-check"` | 通过 |
| `mvn test` in `fx-trading-platform/backend` | 122 个测试通过 |

## 9 项要求逐项结论

### 1. 是否符合解耦合规范，是否功能拆分完全

结论：基本符合，但不完全。

- 后端分域清楚：`account/admin/audit/auth/chart/common/config/content/execution/finance/ledger/market/risk/trading`。
- 后端交易路径拆得较好：`OrderService` 编排，`RiskCheckService` 风控，`ExecutionAdapter` 执行适配，`OrderFillService` 成交落库，`PositionService` 平仓，`LedgerService` 流水。
- 前端新交易终端按 `features/trading`、`features/trading-session`、`services`、`pages/trading` 拆分。
- 不完全处：前端 `components/market-side-panel` 反向依赖 `pages/trading`；后端 `AdminFeatureOperationService` 和 `AdminFeatureCatalogService` 偏大。

### 2. 是否复用代码都抽离出来，减少冗余

结论：已有部分抽离，但仍有明确冗余。

- 前端重复：`parseSymbolAssets`、`formatDecimal` 各有多份实现。
- 前端冗余：旧 `/trade` 页面和旧 `OrderPanel` 已被 `/trading` 替代但仍存在。
- 后端重复：审计 JSON 的 `details/escape` 多处手写。
- 后端可复用较好：排序白名单、分页、交易响应映射、订单状态策略已有抽离。

### 3. 包名、类名、方法名是否符合拆分规范，是否都有详细中文注释

结论：命名基本合格；“详细中文注释”不达标。

- Java 包名和类名基本符合领域与分层规范。
- React 目录命名基本符合 `pages/components/features/services/stores` 习惯。
- 后端 299 个生产 Java 文件中有 15 个没有中文字符。
- 后端命中 142 处模板化注释，例如“执行 xxx 业务流程”，不算详细说明。
- 前端/图表库 337 个 TS/TSX 文件中 236 个没有中文字符，不能认为全量中文注释覆盖。

建议：只给高风险业务规则补中文注释，不要机械给所有 getter、mapper、组件补无效注释。

### 4. 数据库操作是否都是使用 MyBatis-Plus，是否有 SQL 注入风险

结论：不是 100% MyBatis-Plus；当前未发现明确 SQL 注入漏洞。

- 39/39 个 repository 继承 `FxBaseMapper`。
- mapper XML 数为 0。
- `MarketTestDataService` 使用 `JdbcTemplate`，不是 MyBatis-Plus；但 upsert SQL 使用 `?` 参数绑定。
- 动态排序总体使用白名单，但 `FxBaseMapper.findAll(Page, orderColumn, asc)` 暴露原始列名入口，是未来误用风险。

### 5. 代码是否符合扩展性，是否符合可读性

结论：核心交易路径可读性尚可；后台通用能力和图表核心类扩展成本偏高。

- 后端交易服务通过工厂、策略、适配器、mapper 分担职责，可扩展性较好。
- 后台 admin 元数据和操作分发是主要可读性压力。
- 根目录图表库模块分层清楚，但 `Store.ts`、`Chart.ts`、`EventHandler.ts` 过大。
- 前端 `TradingPage` 和 `TradePanel` 功能密度高，测试较多，但后续要继续下沉纯业务逻辑。

### 6. 检查业务流程是否符合闭环，是否有 bug

结论：主流程有闭环和测试证明，但存在高风险业务 bug。

已闭环：

- 登录 session 探测、guest 登录提示、真实下单、订单列表、持仓、平仓、资金流水已有前后端测试。
- 后端 `mvn test` 122 个测试通过，前端 `web:test` 210 个测试通过。

发现的 bug/风险：

- 前端无行情快照时，下单品种可能回退为 `BTCUSDT`。
- 后端平仓缺少 `OPEN` 终态保护，可能重复结算。
- 挂单/止盈止损调度缺少状态抢占，多实例下可能重复执行。
- 后台资金写操作有 `idempotencyKey` 但未真正幂等。
- 订单幂等存在并发窗口，唯一约束冲突未被转换为稳定幂等响应。

### 7. Java 代码是否有用到设计方法去简化代码，优雅化代码

结论：有使用，但不均衡。

已使用：

- Adapter/Strategy：`ExecutionAdapter` 多实现。
- Factory：`OrderCommandFactory`、`OrderEntityFactory`。
- Policy：`OrderStatusPolicy`。
- Mapper：`OrderResponseMapper`。
- Command/Query 分离：后台多个模块已有 `CommandService`、`QueryService`。

不足：

- admin 通用动作仍是大量 if 分发，建议改成 handler 注册表。
- 幂等、并发状态抢占、审计 details 构造缺少统一设计模式。

### 8. 前端优化方向文档

已输出：`fx-trading-platform/docs/frontend-code-audit-optimization-2026-06-12.md`

优先级：

1. 修复下单品种 fallback bug。
2. 明确或删除不可达 `offline-preview`。
3. 清理旧 `/trade` 页面。
4. 抽 `features/market`，消除组件到页面目录的反向依赖。
5. 合并重复工具函数。
6. 限价输入接入行情跟随策略。
7. 收敛行情订阅范围。
8. 继续拆分 `TradingPage` chunk。

### 9. 后端优化方向文档

已输出：`fx-trading-platform/docs/backend-code-audit-optimization-2026-06-12.md`

优先级：

1. 平仓终态保护。
2. 挂单/止盈止损状态抢占或乐观锁。
3. 后台资金幂等。
4. 订单唯一冲突回读。
5. 收窄动态排序入口。
6. 统一审计 JSON。
7. 封装或标注 `JdbcTemplate` 例外。
8. 拆 admin action handler。
9. 补高价值中文注释。

## 总体评分

| 维度 | 评分 | 说明 |
| --- | --- | --- |
| 模块拆分 | 7/10 | 主体清楚，admin 和 market 前端边界需收敛 |
| 复用与冗余 | 6/10 | 有抽离，但旧页面、重复工具、手写 JSON 仍存在 |
| 命名规范 | 8/10 | 基本符合 Java/React 习惯 |
| 中文注释 | 4/10 | 覆盖不全且模板化注释多 |
| MyBatis-Plus 与 SQL 安全 | 7/10 | 主体合格，`JdbcTemplate` 与动态排序需治理 |
| 扩展性 | 6/10 | 交易核心较好，后台通用能力偏硬编码 |
| 业务闭环 | 6/10 | 测试多，但并发、幂等、终态保护有缺口 |
| Java 设计方法 | 7/10 | 有 adapter/factory/policy，但 admin 分发仍需优化 |
| 前端可维护性 | 6/10 | 新终端能力完整，但页面边界和 chunk 需优化 |

## 建议执行顺序

1. 用最小改动修复真实 bug：前端 symbol fallback、后端平仓 OPEN 保护。
2. 补业务回归测试：重复平仓、无行情快照下单 symbol、资金幂等重试。
3. 做安全收敛：动态排序入口白名单化、资金 idempotency unique。
4. 再做结构优化：前端 `features/market`、后端 admin action handler。
5. 最后处理注释质量：删模板化注释，补业务约束注释。
