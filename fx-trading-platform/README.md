# FX Trading Platform

这是在现有 `tradingView-KlineChart` 仓库内新增的独立交易平台子项目，避免改动原 KLineCharts 库源码。

## 模块

- `backend/`: Java 21 + Spring Boot 3.5.x 后端，负责认证、账户、行情、交易、风控、资金流水、后台与审计。
- `apps/web/`: React + TypeScript + KLineCharts 的 PC/H5 交易端骨架。
- `apps/admin/`: React + TypeScript 后台管理端骨架。
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

## 验证

```powershell
node scripts/verify-architecture.mjs
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
