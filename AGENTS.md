# AGENTS.md

本文件是 Codex 在本仓库工作的长期规则。默认使用简体中文沟通；代码、命令、路径、配置键、API 名称和终端输出保留英文。

## 项目结构

- 外层仓库是 KLineCharts 图表库。
- `fx-trading-platform` 是业务主体。
- `fx-trading-platform/apps/web` 是用户端。
- `fx-trading-platform/apps/admin` 是后台端。
- `fx-trading-platform/backend` 是 Spring Boot 后端。
- `fx-trading-platform/infra/docker-compose.yml` 提供本地 PostgreSQL/Redis。
- `fx-trading-platform/scripts` 存放验证脚本。

## 运行命令

```powershell
docker compose -f fx-trading-platform/infra/docker-compose.yml up -d
cd fx-trading-platform/backend && mvn test
cd fx-trading-platform/backend && mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web run build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

## 工程约束

- 数据库由 Flyway 管理。
- 后端使用 MyBatis-Plus。
- 不引入 JPA repository。
- 改 API 必须检查 `apps/web` 和 `apps/admin`。
- 改交易逻辑必须补测试。
- 改钱包逻辑必须验证 `wallet balance`、`asset ledger`、`account summary`。
- 改行情 provider 必须验证 `provider sync`、`symbol binding`、`quote`、`candles`。
- 改 scheduler 必须默认关闭，避免测试环境意外跑批。
- 交易风控必须以后端为准。
- `demo` 和 `live` 不得混淆。

## 测试要求

- 每个 bug 必须有复现步骤。
- 优先写失败测试，再修复。
- 修复后必须运行相关测试。
- 不能删除测试来隐藏问题。
- 最终输出测试命令和结果。

## 安全边界

- 不要提交真实密钥。
- 不要连接真实 `broker`/`fix`/`lp`。
- `dev` profile 使用 `demo execution`。
- `production broker adapter` 占位时不能假装真实交易成功。
- `/api/admin/**` 必须保持后端权限校验。
