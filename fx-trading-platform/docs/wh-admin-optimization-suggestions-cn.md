# WH 后台后续优化建议

生成时间：2026-06-10 05:21:56 +08:00

## P0：必须优先补齐

1. RBAC 与按钮权限  
   建议新增 `admin.roles`、`admin.menus`、`admin.role_menu_permissions`、`admin.user_roles`，并在后端接口层校验权限，不能只依赖前端隐藏按钮。

2. 数据权限  
   建议增加 `AdminDataScopeService`，由各 QueryService 统一接入数据范围条件，避免每个模块各写一套过滤逻辑。

3. 资金审核订单  
   当前资金流水和后台资金操作已可用，但充值/提现审核订单还不完整。建议单独建充值、提现订单表，并让审核通过动作统一写 ledger。

4. MyBatis 集成测试  
   当前服务单测和 smoke 已覆盖主路径。建议增加少量 `@SpringBootTest` 或 Testcontainers 测试，专门覆盖 UUID、JSONB、分页和审计写入。

## P1：功能完整性优化

1. 风控配置 CRUD  
   当前 `/api/admin/risk/configs` 是只读接口。建议新增新增、编辑、启停和审计日志，前端提供风控编辑弹窗。

2. 成交记录查询条件  
   建议 `GET /api/admin/trading/trades` 增加账号、品种、方向、时间区间筛选，并在前端增加搜索栏。

3. 后台表格组件升级  
   当前表格可用但偏基础。建议抽取 `CrudTable`，支持分页、列宽、横向滚动、固定操作列、空状态、错误重试。

4. Knife4j 注解完善  
   已加入 Knife4j 依赖，建议后续按模块补 `@Tag`、`@Operation`、请求/响应说明，让接口文档可直接交付给前端和测试。

5. Redis 降级策略  
   行情缓存依赖 Redis。建议在 Redis 不可用时降级到 demo quote 或 DB fallback，并避免计划任务刷屏错误日志。

## P2：工程质量优化

1. Mapper 代码生成规范  
   建议建立模板：Entity、Mapper、DTO、QueryService、CommandService、Controller、Test 成套生成，避免迁移后重复手写。

2. DTO 包结构统一  
   当前 `AdminUserResponse` 仍在 `admin/dto`，多数响应 DTO 在 `admin/dto/response`。建议后续小步迁移并保留兼容 import，统一包结构。

3. 中文编码治理  
   本次已修复核心新增文件和交付文档。建议后续用脚本检查源码中是否存在明显乱码字符，逐步修复历史注释与页面文案。

4. 后台 E2E 常态化  
   建议把登录、成交记录、风控配置、资金审核、内容配置纳入固定 E2E 脚本，并在本地和 CI 都可运行。

5. 数据库字段说明同步  
   建议把 Flyway migration、实体字段、接口 DTO、中文数据库设计文档建立同步检查，避免字段含义漂移。

## 推荐下一步顺序

1. 补 RBAC 与数据权限基础表。
2. 将后台菜单与按钮绑定权限码。
3. 补风控配置 CRUD 和成交记录筛选。
4. 扩展充值/提现审核流。
5. 抽取后台通用表格、搜索表单、弹窗表单。
6. 增加 MyBatis-Plus 集成测试与 Knife4j 注解。
