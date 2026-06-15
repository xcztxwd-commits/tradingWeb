# 前后端联调与真实下单验证说明

日期：2026-06-06

本文档说明本轮完成的前后端联调能力、如何启动和使用、验证步骤，以及当前已经达成的效果。

## 1. 本轮完成了什么

本轮目标是让前端真实调用 Spring Boot 后端接口，并通过前端页面完成一次可验证的下单流程。

完成内容分为四部分：

1. 后端本地 CORS 支持动态前端端口。
2. 前端 demo session 在受限浏览器环境下可降级运行。
3. 前端下单 payload 补齐后端 OMS 主字段。
4. 前端页面通过后端接口完成登录、账户加载、订单提交和订单回读。

## 2. 后端完成内容

后端仍按当前 Spring Boot 架构执行，没有新增独立服务，也没有改变已有 API 路径。

### 2.1 CORS 配置调整

修改文件：

- `backend/src/main/resources/application.yml`
- `backend/src/main/java/com/fxplatform/common/security/SecurityConfig.java`
- `backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java`

原问题：

- 前端默认端口 `5173` 被占用后，本次前端跑在 `http://127.0.0.1:5193`。
- 浏览器请求 `/api/auth/login`、`/api/auth/register` 时会带 `Origin: http://127.0.0.1:5193`。
- 后端旧 CORS 白名单只包含少数固定端口，导致浏览器 POST 请求被 Spring CORS 拦截为 `403 Forbidden`。

当前方案：

```yaml
app:
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:}
    allowed-origin-patterns: ${CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*,http://127.0.0.1:*}
```

效果：

- 本地开发时，`localhost:*` 和 `127.0.0.1:*` 的前端端口都可以调用后端。
- 生产环境仍可通过 `CORS_ALLOWED_ORIGINS` 或 `CORS_ALLOWED_ORIGIN_PATTERNS` 收紧来源。
- 新增架构测试确保配置继续使用 `setAllowedOriginPatterns`，避免后续回退成固定端口。

## 3. 前端完成内容

### 3.1 demo token 存储降级

修改文件：

- `apps/web/src/features/trading-session/tradingSession.ts`
- `apps/web/src/features/trading-session/tradingSessionStorage.ts`
- `apps/web/src/features/trading-session/tradingSession.test.ts`

原问题：

- 某些浏览器自动化或受限上下文中，`localStorage` 可能不可用或抛错。
- demo session 初始化依赖 `localStorage`，失败后前端拿不到 token 和 account。
- 页面会显示基础 fallback 数据，但 `sessionReady` 为 false，下单按钮不可用。

当前方案：

- 把 demo token 读写抽到 `tradingSessionStorage.ts`。
- `readStoredDemoToken`、`writeStoredDemoToken`、`clearStoredDemoToken` 都是 best effort。
- 如果本地存储不可用，仍然可以重新登录 demo 用户并继续创建交易会话。

效果：

- 浏览器限制不再阻断 demo session。
- 前端可以继续拿到后端账户、订单、仓位和流水数据。

### 3.2 `/trade` 下单 payload 补齐 OMS 字段

修改文件：

- `apps/web/src/components/order-panel/OrderPanel.tsx`

原问题：

- 后端新 OMS 契约主字段是 `quantity`、`price`、`clientOrderId`。
- 旧 `/trade` 页面只提交 `lots`、`requestedPrice`、`idempotencyKey`。
- 后端运行时兼容旧字段，所以真实请求能成功；但 TypeScript 构建失败，因为 `OrderPayload` 已经要求新字段。

当前方案：

`OrderPanel` 同时提交新字段和兼容字段：

```ts
{
  quantity: lots,
  price: requiresRequestedPrice ? requestedPrice : undefined,
  clientOrderId,
  lots,
  requestedPrice: requiresRequestedPrice ? requestedPrice : undefined,
  idempotencyKey: clientOrderId
}
```

效果：

- 前端类型和后端 OMS 契约一致。
- 旧字段仍保留，避免破坏现有后端兼容逻辑和本地预览逻辑。
- `npm.cmd --workspace apps/web run build` 可以通过。

### 3.3 `/trading` 专业交易面板接入后端回调

修改文件：

- `apps/web/src/features/trading/components/TradePanel.tsx`
- `apps/web/src/features/trading/components/TradePanel.test.ts`

原问题：

- `TradePanel` 已经声明了 `accountId`、`sessionReady`、`onSubmitOrder`。
- 但组件内部没有使用这些 props，提交订单时仍然走 mock `submitOrder`。

当前方案：

- 当 `accountId + sessionReady + onSubmitOrder` 同时存在时，使用 `toOrderPayload(accountId, form, market)` 生成后端订单 DTO。
- 调用父级传入的 `onSubmitOrder(payload)`。
- 如果没有真实后端 session，仍保留 mock 下单能力。

效果：

- `/trading` 的专业交易面板具备真实后端下单链路。
- mock 演示能力仍然保留，方便 UI 独立开发。
- 源码测试覆盖了“有后端 session 时必须调用后端回调”的约束。

## 4. 如何启动

以下命令均在 Windows PowerShell 执行。

### 4.1 确认依赖服务

后端依赖 PostgreSQL 和 Redis。先确认服务可用：

```powershell
pg_isready -h 127.0.0.1 -p 5432
redis-cli ping
```

期望结果：

- PostgreSQL 返回 accepting connections。
- Redis 返回 `PONG`。

### 4.2 启动后端

进入后端目录：

```powershell
cd C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
```

启动 Spring Boot：

```powershell
$env:JAVA_HOME='C:\soft\fx-platform-tools\jdk-21'
C:\soft\fx-platform-tools\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

验证后端：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8080/actuator/health
```

期望结果：

```json
{"status":"UP"}
```

### 4.3 启动前端

进入平台根目录：

```powershell
cd C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform
```

默认启动：

```powershell
npm.cmd --workspace apps/web run dev
```

如果 `5173` 被占用，可以指定端口，例如本轮使用的 `5193`：

```powershell
npm.cmd --workspace apps/web run dev -- --host 127.0.0.1 --port 5193 --strictPort
```

访问：

```text
http://127.0.0.1:5193/trade
```

## 5. 如何使用前端完成下单

本轮完整浏览器验证使用的是 `/trade` 页面。

操作步骤：

1. 打开 `http://127.0.0.1:5193/trade`。
2. 等待页面顶部账户区域从 fallback 数据切换为后端 demo 账户状态。
3. 在右侧 `Order Panel` 中选择交易品种，默认是 `EURUSD`。
4. 下单类型选择 `Limit`。
5. 在 `Price` 输入框填写价格，例如 `1.07000`。
6. `Lots` 默认是 `0.01`，也可以自行调整。
7. 点击 `Buy`。
8. 查看下方 `Orders` 表，新订单应显示为 `EURUSD BUY LIMIT 0.01 PENDING`。

接口调用链路：

```text
前端页面
  -> POST /api/auth/login
  -> GET /api/accounts
  -> GET /api/accounts/{accountId}/summary
  -> GET /api/trading/orders
  -> GET /api/trading/positions?accountId=...
  -> GET /api/ledger?accountId=...
  -> POST /api/trading/orders
  -> 再次刷新账户、订单、仓位、流水
```

## 6. 达成的效果

本轮实际达成了以下效果：

- 前端可以通过 Vite proxy 调用后端 `/api/**`。
- 浏览器登录 demo 用户不再被 CORS 阻断。
- 页面能拿到真实后端账户数据。
- 下单按钮在 session ready 后可用。
- 从前端提交的 LIMIT 单能进入后端交易服务。
- 后端返回订单数据，订单状态为 `PENDING`。
- 前端 Orders 表能看到新增委托。
- 后端 API 可回读同一个订单。

本轮真实验证订单示例：

```json
{
  "id": "836369f2-fc2e-461e-93f4-c759930957b2",
  "symbol": "EURUSD",
  "side": "BUY",
  "orderType": "LIMIT",
  "status": "PENDING",
  "lots": 0.0100,
  "quantity": 0.0100,
  "price": 1.0700000000,
  "remainingQuantity": 0.0100
}
```

截图证据：

```text
C:\Users\User\AppData\Local\Temp\fx-frontend-trade-order-regression.png
```

## 7. 验证命令

### 7.1 后端测试

```powershell
cd C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\backend
$env:JAVA_HOME='C:\soft\fx-platform-tools\jdk-21'
C:\soft\fx-platform-tools\apache-maven-3.9.9\bin\mvn.cmd clean test
```

本轮结果：

```text
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 7.2 前端目标测试

```powershell
cd C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform\apps\web
node --test src/features/trading-session/tradingSession.test.ts src/features/trading/components/TradePanel.test.ts src/features/trading/services/orderAdapter.test.ts
```

本轮结果：

```text
tests 13
pass 13
fail 0
```

### 7.3 前端生产构建

```powershell
cd C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform
npm.cmd --workspace apps/web run build
```

本轮结果：

```text
✓ built
```

说明：构建时仍有一个 Vite chunk 拆分警告，原因是同一个 `tradingMarketApi.ts` 同时被静态 import 和动态 import。该警告不影响本轮前后端联调和下单。

## 8. 常见问题

### 8.1 登录或注册返回 403

优先检查是否是 CORS：

```powershell
$body = @{ email='local-demo-trader@example.com'; password='Password123!' } | ConvertTo-Json
Invoke-WebRequest -Uri http://127.0.0.1:5193/api/auth/login -Method Post -ContentType 'application/json' -Body $body -Headers @{ Origin='http://127.0.0.1:5193' } -UseBasicParsing
```

期望状态码是 `200`。

如果仍是 `403`，检查后端是否加载了新配置，必要时重启后端。

### 8.2 Buy/Sell 按钮一直禁用

可能原因：

- 后端没有启动。
- `/api/auth/login` 被 CORS 拦截。
- 前端没有拿到 `accountId`。
- LIMIT/STOP 单没有填写 `Price`。

处理步骤：

1. 检查 `http://127.0.0.1:8080/actuator/health`。
2. 检查浏览器网络面板里 `/api/auth/login` 和 `/api/accounts` 是否为 `200`。
3. LIMIT/STOP 单填写 `Price`。
4. 刷新页面重新初始化 demo session。

### 8.3 WebSocket 出现一次 closed warning

本轮浏览器自动化回归时出现过：

```text
WebSocket is closed before the connection is established.
```

这不影响 REST 登录、账户、下单和订单回读。本轮核心交易链路通过 REST 已验证成功。后续如果要做实时行情和私有交易事件推送，需要单独做 WebSocket 稳定性验证。

## 9. 当前边界和后续建议

当前已完整验证：

- `/trade` 页面通过前端真实调用后端接口完成 LIMIT 下单。
- 后端返回订单并可通过 API 回读。
- 前端 Orders 表能显示新增委托。

当前已接线但建议后续继续验证：

- `/trading` 专业交易面板已经在代码层接入 `onSubmitOrder` 后端回调。
- 本轮通过源码测试保证它在 session ready 时走 `toOrderPayload + onSubmitOrder`。
- 后续建议增加专门的浏览器 E2E，覆盖 `/trading` 页面完整下单、当前委托刷新、余额展示和错误提示。

下一轮建议：

1. 给 `/trading` 页面补真实浏览器 E2E。
2. 把当前 REST 轮询逐步升级为订单事件推送或快照加事件增量。
3. 统一 `/trade` 和 `/trading` 的订单适配层，减少重复下单入口。
4. 明确 `PENDING` 与后续 `WORKING` 状态的前端展示策略，避免用户误解挂单状态。
