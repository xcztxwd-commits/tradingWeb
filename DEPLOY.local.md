# 本地 Docker 部署

源码仓库为 https://github.com/xcztxwd-commits/tradingWeb，业务主体位于 `fx-trading-platform`。本方案使用 Java 21、Maven、Docker Compose；前端在 Docker 内构建。主机工具安装目录和 Maven 缓存位置不属于部署前提。

部署仅使用 `dev` profile、模拟行情和 `demo` 交易执行，不接入真实交易通道。用户端、后台、API 分别绑定 `127.0.0.1:5180`、`127.0.0.1:5181`、`127.0.0.1:18080`；PostgreSQL 和 Redis 不发布主机端口。

## 首次准备

从仓库根目录打开 PowerShell，确认 `java -version` 为 Java 21，且 `mvn.cmd`、`docker` 可用。然后设置路径和项目名：

```powershell
$repoRoot = (Get-Location).Path
$backend = Join-Path $repoRoot 'fx-trading-platform/backend'
$infra = Join-Path $repoRoot 'fx-trading-platform/infra'
$composeFile = Join-Path $infra 'docker-compose.local.yml'
$envFile = Join-Path $infra '.env.local'
$projectName = 'tradingweb-local'
if (-not (Test-Path -LiteralPath $composeFile)) { throw '请在仓库根目录执行。' }
```

项目名决定数据卷名称：沿用项目名会复用已有数据库、Redis 和上传文件卷；更换项目名会创建另一组卷。启动前核对现有容器、项目名和端口占用。

首次创建外置环境文件；已有文件保持不变。所有密码与密钥均由本机随机生成，管理员邮箱可自行修改：

```powershell
function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes); [Convert]::ToBase64String($bytes) }
    finally { $random.Dispose() }
}
if (-not (Test-Path -LiteralPath $envFile)) {
    @(
        "DATABASE_PASSWORD=$(New-LocalSecret)"
        "REDIS_PASSWORD=$(New-LocalSecret)"
        "JWT_SECRET=$(New-LocalSecret)"
        "CONFIG_ENCRYPTION_KEY=$(New-LocalSecret)"
        'ADMIN_BOOTSTRAP_EMAIL=admin@local.test'
        "ADMIN_BOOTSTRAP_PASSWORD=$(New-LocalSecret)"
    ) | Set-Content -LiteralPath $envFile -Encoding utf8
}
```

`.env.local` 含敏感信息，不提交 Git，也不放入 Docker 构建上下文。Compose 在任一必要变量缺失或为空时拒绝解析。重装前如需恢复原数据，应另行保存环境文件与对应数据卷；仅保存源码无法恢复账户和数据库。

## 构建与启动

后端镜像只接收本机生成的 JAR，因此先打包后端。此命令编译主程序和测试源码，但 `-DskipTests` 不执行单元测试：

```powershell
mvn.cmd -B -ntp -f (Join-Path $backend 'pom.xml') -s (Join-Path $backend 'maven-settings.local.xml') -DskipTests package
if ($LASTEXITCODE -ne 0) { throw '后端打包失败。' }
docker compose -p $projectName --env-file $envFile -f $composeFile config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose 配置校验失败。' }
docker compose -p $projectName --env-file $envFile -f $composeFile up -d --build --wait --wait-timeout 300
```

Maven 配置使用文件中声明的 Central 镜像；如需自定义缓存，可额外传入自己的 `-Dmaven.repo.local=...`。原后端 `Dockerfile` 保留，本方案使用 `Dockerfile.local`。

启动后可访问 API 健康检查 `http://localhost:18080/actuator/health` 和 API 文档 `http://localhost:18080/swagger-ui/index.html`。首次管理员凭据来自自己的环境文件；这些访问地址和命令不代表当前环境已经通过验收。

## 日常维护

```powershell
docker compose -p $projectName --env-file $envFile -f $composeFile ps
docker compose -p $projectName --env-file $envFile -f $composeFile logs --tail 100 backend
# 停止服务并保留命名数据卷；不要添加 -v，除非确实要删除数据。
docker compose -p $projectName --env-file $envFile -f $composeFile down
```

服务设置 `unless-stopped`，Docker 启动后会恢复未手动停止的容器。Docker 数据目录由当前主机配置决定，不依赖旧工作站路径。

## 历史本机记录

旧环境曾记录源码版本 `adaa5ede407a2d1152bcb5acd3f63d6882a1ca39` 的镜像构建、五服务健康检查、69 项 Flyway 迁移、模拟交易和页面验收成功。这些仅为历史记录，不是本次验证结果，也不保证新环境启动成功；新环境应重新检查构建、健康状态、鉴权及模拟业务流程。
