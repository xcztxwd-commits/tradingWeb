# WH 外汇后台实现差距计划（评审后执行版）

更新时间：2026-06-10 05:21:56 +08:00

## 评审结论

原文档的方向基本正确：后台应继续围绕 `apps/admin` 和 Spring Boot 后端扩展，不应另起一套后台系统。但文档中的技术基线已经过期：后端数据库访问层已按本次要求从 Spring Data JPA 全量迁移到 MyBatis-Plus，后续计划必须以 MyBatis-Plus、Flyway、PostgreSQL 为基准。

本次已执行的优先事项：

| 模块 | 原差距 | 本次状态 |
| --- | --- | --- |
| 数据访问层 | 使用 JPA Repository | 已迁移为 MyBatis-Plus Mapper，不与 JPA 共存 |
| 工具类 | 自定义/原生工具分散 | 已引入 Hutool，并在日期、UUID、字符串、JSON、反射等关键处替换 |
| API 文档 | Springdoc 基线 | 已加入 Knife4j starter |
| Lombok | 局部使用 | 继续保留并在新增类按现有风格使用 |
| 成交记录后台页 | `/api/admin/trading/trades` 缺失 | 已补齐后端分页接口和前端页面 |
| 风控配置后台页 | `/api/admin/risk/configs` 缺失 | 已补齐后端只读接口和前端页面 |
| 数据库联调 | 仅有设计说明 | 已用 PostgreSQL 实库完成 smoke 和 UI 联调 |

## 当前技术基线

- Java 21
- Spring Boot 3.5.7
- MyBatis-Plus 3.5.12
- PostgreSQL + Flyway
- Hutool 5.8.46
- Knife4j 4.5.0
- Lombok
- React 19 + Vite 7 + TypeScript

## 后续实施原则

1. 后端只使用 MyBatis-Plus Mapper 做数据库访问，不再新增 JPA Repository。
2. 数据库 schema 仍由 Flyway 管理，禁止用 ORM 自动建表。
3. Controller 只负责参数接收、权限入口和统一响应包装。
4. Service 承载业务逻辑，写操作必须保留事务与审计。
5. DTO 与 Entity 分离，前端不得直接依赖实体结构。
6. Hutool 可覆盖的通用能力优先使用 Hutool，避免重复工具类。
7. 每个新增后台页面必须同时补 API 测试、前端构建验证和联调 smoke。

## 剩余高优先级差距

| 优先级 | 模块 | 建议落点 |
| --- | --- | --- |
| P0 | RBAC、菜单权限、按钮权限 | `admin` schema + `AdminRbacController` |
| P0 | 数据权限 | QueryService 中统一接入数据范围条件 |
| P0 | 资金审核订单 | `finance` schema 扩展充值/提现订单与审核流 |
| P1 | 会员详情、KYC、银行卡/钱包 | `auth`/`finance` 扩展表 + 独立会员后台页 |
| P1 | 产品分类、涨跌、风控 CRUD | `market`/`risk` 扩展接口与页面 |
| P1 | 请求日志、验证码日志 | `audit` schema 扩展 |
| P2 | 表格列设置、导入导出、批量操作 | 前端通用表格组件 + 后端导出任务 |

## 当前验收基线

已通过：

- `mvn.cmd test`：72 个后端测试通过。
- `npm.cmd --workspace apps/admin run test`：10 个前端测试通过。
- `npm.cmd --workspace apps/admin run build`：TypeScript 与 Vite 构建通过。
- `node scripts/verify-architecture.mjs`：架构规则通过。
- `node scripts/smoke-backend.mjs`：用户侧注册、账户、订单、持仓、流水闭环通过。
- `node scripts/smoke-admin.mjs`：后台登录、读写接口、审计、资金、内容、配置、成交记录、风控配置通过。
- UI 联调：`/trading/trades` 与 `/risk` 页面可通过 `http://localhost:5174` 登录后正常读取 `http://localhost:8080` 后端数据。
