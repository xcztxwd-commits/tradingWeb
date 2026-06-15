# WH 后台 MyBatis-Plus 改进完成报告

生成时间：2026-06-10 05:21:56 +08:00

## 改进范围

本次改造覆盖 Java 后端数据库访问层、后台管理 API、后台前端页面和验证脚本。

核心目标已经完成：

- 添加 MyBatis + MyBatis-Plus，并将原数据库访问方式迁移为 MyBatis-Plus。
- 不保留 JPA 共存路径，移除 `spring-boot-starter-data-jpa`。
- 添加 Hutool `cn.hutool:hutool-all:5.8.46`，并在通用工具能力上使用 Hutool。
- 添加 Knife4j starter，保留并继续使用 Lombok。
- 增加成交记录与风控配置后台 API 和前端页面。
- 补充中文注释，重点覆盖 Controller、Service 和 MyBatis 关键逻辑。

## 后端改动

| 类型 | 改动 |
| --- | --- |
| 依赖 | 新增 MyBatis-Plus、Hutool、Knife4j；PostgreSQL 驱动改为编译期可见；移除 JPA starter |
| 配置 | `application.yml` 增加 `mybatis-plus` 配置，保留 Flyway 管 schema |
| Mapper | 新增 `FxBaseMapper`，统一提供 `save/findById/findAll/count` 兼容语义 |
| 分页 | 新增 MyBatis-Plus PostgreSQL 分页插件 |
| 类型处理 | 新增 `UuidTypeHandler` 与 `JsonbStringTypeHandler`，解决 PostgreSQL `uuid/jsonb` 写入 |
| 实体 | 实体注解迁移为 `@TableName`、`@TableId` |
| Repository | 迁移为 MyBatis-Plus Mapper 接口 |
| Admin API | 增加 `GET /api/admin/trading/trades` 与 `GET /api/admin/risk/configs` |
| Hutool | 使用 `DateUtil`、`IdUtil`、`StrUtil`、`JSONUtil`、`MapUtil`、`ReflectUtil` 等 |

## 前端改动

| 文件 | 改动 |
| --- | --- |
| `apps/admin/src/types.ts` | 新增 `TradeRow`、`RiskConfigRow` |
| `apps/admin/src/services/adminApi.ts` | 新增 `getTradesPage`、`getRiskConfigs`，snapshot 纳入新增数据 |
| `apps/admin/src/pages/TradesPage.tsx` | 从占位页改为真实成交记录表格 |
| `apps/admin/src/pages/RiskPage.tsx` | 从占位页改为真实风控配置表格 |

## 验证结果

| 验证项 | 结果 |
| --- | --- |
| 后端单元测试 | 通过，72 tests |
| 前端单元测试 | 通过，10 tests |
| 前端构建 | 通过，`tsc -b && vite build` |
| 架构校验 | 通过，确认无 JPA 访问层残留 |
| 用户侧 smoke | 通过，注册、账户、订单、持仓、流水闭环 |
| 后台 smoke | 通过，登录、后台读写、审计、资金、内容、配置、成交记录、风控配置 |
| 数据库点检 | PostgreSQL 实库中 `users/accounts/orders/trades/risk_configs/audit_logs` 均可读 |
| 前后端联调 | 通过，后台页面能读取当前后端 API |

## 运行态确认

- 后端：`http://localhost:8080/actuator/health` 返回 `UP`。
- 后台前端：`http://localhost:5174/` 可访问。
- 登录账号：`admin@gmail.com / admin`。
- 成交记录页：登录后访问 `/trading/trades`，可渲染真实成交数据。
- 风控配置页：登录后访问 `/risk`，可渲染 `risk.risk_configs` 数据。

## 关键注意事项

- `JsonbStringTypeHandler` 不能全局扫描为 String TypeHandler，否则普通字符串会被误写成 `jsonb`。当前实现只通过字段注解按需使用。
- `UuidTypeHandler` 需要全局注册，保证 MyBatis-Plus 对 PostgreSQL `uuid` 的参数映射稳定。
- `Flyway` 仍是 schema 真源，不能启用 ORM 自动建表。
