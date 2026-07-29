# FX Trading Platform

这是在现有 `tradingView-KlineChart` 仓库内新增的独立交易平台子项目，避免改动原 KLineCharts 库源码。

## 模块

- `backend/`: Java 21 + Spring Boot 3.5.x 后端，负责认证、账户、行情、交易、风控、资金流水、后台与审计。
- `apps/web/`: React + TypeScript + KLineCharts 的单构建 PC/Mobile 交易端；路由 Controller 共享，平台 View 独立动态加载。
- `apps/admin/`: React + TypeScript 后台管理端骨架。
- `packages/ui/`: `@fx-platform/ui` 公共主题、语义 token 和无业务 UI 原语。
- `packages/frontend-core/`: `@fx-platform/frontend-core` 公共 API、认证、行情、账户、钱包和交易前端业务层。
- `infra/`: PostgreSQL、Redis 与后端服务的本地 Docker Compose。
- `docs/`: 架构、API、数据库与事件说明。

## 启动

```powershell
cd fx-trading-platform
copy .env.example .env
cd infra
docker compose up -d postgres redis
cd ..\backend
mvn spring-boot:run
```

前端：

```powershell
cd fx-trading-platform
npm install
npm run web:dev
npm run admin:dev
```

Web 端以 `900px` 为设备边界：视口宽度不超过 `900px` 时加载 Mobile View，从 `901px` 起加载 PC View。两端共用同一个路由 Controller，不同时挂载后用 CSS 隐藏。最终目录、依赖边界和包导出见 [架构说明](./docs/architecture.md)。

## 验证

完整前端静态门禁：

```powershell
npm run frontend:check
```

也可独立运行各层和架构门禁：

```powershell
npm run ui:test
npm run ui:typecheck
npm run frontend-core:test
npm run frontend-core:typecheck
npm run web:test
npm run web:build
npm run verify:frontend-boundaries
npm run verify:frontend-styles
npm run verify:architecture
npm run web:bundle-budget
npm run audit:large-files
```

本地 PostgreSQL、Redis 与 `dev` profile 后端就绪后，运行 PC/Mobile 视觉与核心业务验收；`dev` profile 只使用 demo execution，不连接真实 broker、FIX 或 LP：

```powershell
npm run smoke:visual-qa
npm run smoke:user-core-pages
npm run web:smoke:trading
npm run acceptance:p0-user-trading
```

后端具备 Maven 后：

```powershell
cd backend
mvn test
```

当前机器已放置用于验证的本地工具：

```powershell
$env:JAVA_HOME='C:\soft\fx-platform-tools\jdk-21'
$env:Path="$env:JAVA_HOME\bin;C:\soft\fx-platform-tools\apache-maven-3.9.9\bin;$env:Path"
cd backend
mvn test
```

后端运行后可执行真实链路烟测：

```powershell
npm run smoke:backend
npm run smoke:admin
```

本地后台登录需要显式启用管理员引导：

```powershell
$env:ADMIN_BOOTSTRAP_ENABLED='true'
$env:ADMIN_BOOTSTRAP_EMAIL='admin@gmail.com'
$env:ADMIN_BOOTSTRAP_PASSWORD='admin'
cd backend
mvn spring-boot:run
```

当前实现遵守关键边界：前端不直接调用 Massive，交易通过 `RiskCheckService` 和 `ExecutionAdapter`，资金变化通过 `LedgerService` 写流水，数据库结构由 Flyway 管理。
