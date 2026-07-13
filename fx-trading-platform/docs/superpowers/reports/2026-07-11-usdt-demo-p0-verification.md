# USDT Demo P0 最终验证报告

- 验证日期：2026-07-13（Asia/Shanghai）
- 分支：`codex/usdt-spot-perp-p0`
- 验证实现 HEAD：`e3337275 fix: reject stale funding snapshots`
- 规格：`docs/superpowers/specs/2026-07-10-usdt-spot-perpetual-demo-scheme-a-design-and-implementation.md`
- 实施计划：`docs/superpowers/plans/2026-07-11-usdt-spot-perpetual-demo-p0-implementation.md`
- 交接计划：`docs/superpowers/plans/2026-07-12-usdt-spot-perpetual-demo-session-handoff.md`

## 1. 最终结论

**整体状态：BLOCKED（实现、静态、单元、契约、构建和未登录四视口浏览器门禁通过；真实 PostgreSQL/Redis 与登录态全栈验收受环境阻塞）。**

已通过：

- 后端全量单元/非 Docker 测试：`1373/1373`。
- Web：`530/530`；Admin：`76/76`。
- OpenAPI 类型检查、Node 契约测试、Web/Admin production build、architecture verification。
- 静态架构、安全和资金不变量审计。
- Web/Admin 在 `1440×900` 与 `390×844` 的未登录真实渲染、响应式布局、权限跳转和 console 检查。
- 独立代码复核；复核发现的 P0/P1/P2 均以 RED 测试复现并修复，组件级最终复核未发现剩余阻断。

未通过且不得误报：

- Docker Desktop Linux engine 无法启动；日志明确为 `Virtual Machine Platform not enabled` / `No virtualization available`，Testcontainers 无法创建 PostgreSQL。
- 显式 PostgreSQL 集成门禁共发现 `69` 项：`1` 项不依赖容器的静态检查执行通过，`68` 项容器测试跳过。
- canonical 登录态浏览器 smoke 在启动 PostgreSQL/Redis 前置步骤失败；真实下单、划转、批量动作、Admin 登录态操作及 PUBLIC/OKX fallback/LOCAL 三模式未实际执行。
- 因规格第 27 节要求全部满足，本报告不宣称 P0 已完成验收；DoD 15、16 保持 BLOCKED。

## 2. 命令级验证结果

所有 Node/npm 成功结果均显式把 bundled Node 24 目录放在 `PATH` 首位。第一次直接调用 npm 时，生命周期解析到旧 Node；此外 Windows 将带引号的 `src/**/*.test.*` 当作字面路径。Web/Admin 的测试脚本已改为 Node 原生 `node --test` discovery，随后在 Node 24 下全量通过。

| 门禁 | 命令 | 结果 |
|---|---|---|
| 静态禁用项 | `rg -n "PARTIALLY_FILLED\|USDT_PERP\|BTCUSDTPERP\|/topic/trading/accounts\|mockTradingMarkets\|useMockBalances" backend apps packages scripts` | 命中均已逐项分类，见第 3 节；无目标 ready-path 禁用写入 |
| 越界架构 | `rg -n "broker\|FIX\|insurance\|ADL\|Kafka\|RabbitMQ" backend/src/main` | 仅 fail-closed broker/FIX 占位、配置空槽、`FIXED` funding 和 websocket broker 术语；无真实私有执行、保险/ADL/MQ |
| 后端全量 | `mvn -f backend/pom.xml test` | PASS；`1373` tests，0 failures，0 errors，0 skipped；最终复测 `44.380 s` |
| Attached protection focused | `mvn -f backend/pom.xml "-Dtest=CreateOrderRequestTest,ProtectionOrderServiceTest,PerpetualOrderServiceTest,PositionServiceTest,OrderResponseMapperTest" test` | PASS；`121/121`；覆盖 BASE/QUOTE/CONTRACTS、同类型预算、mark authority、nullable version 和 legacy Perp 拒绝 |
| Funding freshness focused | `mvn -f backend/pom.xml "-Dtest=FundingRateFreshnessPolicyTest,MarketDataRouterTest,AdminFundingConfigServiceTest" test` | PASS；`26/26`；覆盖 active/fresh、边界等号、slow-query rollover、默认阈值和 Instant.MIN saturation |
| PostgreSQL/并发 | `mvn -f backend/pom.xml "-Dtest=PostgresDatabaseIT,V46V47EmptyDatabaseIT,V45ToV47DemoResetIT,Task5PostgresFullFillIT,Task6PostgresSpotIT,Task7PostgresDemoLifecycleIT,Task8PostgresTradingSettingsIT,Task9PostgresPerpetualOrderIT,Task10PostgresProtectionIT,Task11PostgresFundingIT,DemoTradingConcurrencyIT,PerpetualPositionConcurrencyIT,ProtectionOrderConcurrencyIT,FundingLiquidationConcurrencyIT" test` | ENV BLOCKED；69 discovered，68 skipped，1 static pass；Testcontainers 报 `Could not find a valid Docker environment` |
| OpenAPI | `node npm-cli.js run contract:check` | PASS；generated OpenAPI types current |
| Node 契约 | `node --test scripts/smoke-backend.test.mjs scripts/trading-contract.test.mjs scripts/contract-governance.test.mjs` | PASS；`16/16` |
| Web test | `node npm-cli.js --prefix apps/web test` | PASS；`530/530`，78 suites，0 skipped |
| Admin test | `node npm-cli.js --prefix apps/admin test` | PASS；`76/76`，14 suites，0 skipped |
| Web build | `node npm-cli.js --prefix apps/web run build` | PASS；1833 modules，最终复测 Vite build `8.00 s` |
| Admin build | `node npm-cli.js --prefix apps/admin run build` | PASS；1630 modules，Vite build `8.32 s` |
| 架构 | `node npm-cli.js run verify:architecture` | PASS；`Architecture verification passed.` |
| 外层 lint | `node npm-cli.js run code-lint` | PASS；ESLint 0 error |
| Docker 状态 | Docker Desktop Linux engine / compose preflight | FAIL；WSL engine precondition 为 `Virtual Machine Platform not enabled`，宿主机报告 `No virtualization available` |
| 浏览器四视口 | in-app browser，Web/Admin `1440×900`、`390×844` | PARTIAL PASS；Web Spot/Perp 离线降级、产品语义、无横向溢出、console clean；Admin `/accounts` 未登录跳转 `/login`、无横向溢出、console clean |
| 登录态真实浏览器 | `node npm-cli.js run smoke:usdt-demo-browser` | ENV BLOCKED；真实基础设施前置条件失败，未产生登录态业务断言 PASS |

最新真实 smoke 证据：

- `artifacts/smoke-usdt-demo-browser/2026-07-13T13-27-19-405Z/report.json`
- `status=FAIL`
- `sourceEvidence=[]`
- `screenshots=[]`
- 唯一执行步骤 `start real PostgreSQL Redis backend Web and Admin services=FAIL`
- 原因：Docker Desktop Linux/WSL engine 因 `Virtual Machine Platform not enabled` 无法启动，无法拉取/启动 `postgres:16`

本轮浏览器补充证据（不替代登录态 smoke）：

- Web Perpetual 桌面：`/trade/perpetual/BTCUSDT-PERP` 显示 `永续 / 全仓`，五个 canonical Perpetual 产品可见；行情不可用时交易禁用且明确显示错误。
- Web Spot 桌面：`/trade/spot/BTCUSDT` 显示 `现货 / 现金`，证明修复未把产品模式反向污染。
- Web 移动端：`390×844` 使用独立移动终端和底部导航，`scrollWidth <= clientWidth`，无 console warning/error。
- Admin 桌面/移动端：未登录访问 `/accounts` 均跳转 `/login`；1440 与 390 宽度均无横向溢出或 console warning/error。

## 3. 静态范围分类

### 3.1 `PARTIALLY_FILLED`

保留项属于旧数据读取/展示兼容：enum、OpenAPI DTO、活动订单查询、迁移约束、Admin/Web 历史状态显示以及旧 smoke 的宽容读取。

额外 writer 检查：

- `rg -F 'setStatus(OrderStatus.PARTIALLY_FILLED' ...`：无命中。
- SQL `status = 'PARTIALLY_FILLED'` writer 检查：无命中。
- P0 canonical execution 由 `FullFillCoordinatorTest#rejectsEveryNonFullAdapterResultBeforeItCanBecomeCanonical` 锁定为单次全量成交。

结论：保留 read compatibility；目标 P0 路径不写 partial fill。

### 3.2 `USDT_PERP`

保留项：V40 历史数据库 enum/check constraint、Java/TypeScript legacy 类型和升级测试。V47 active Demo seed 只创建 `SPOT/USDT` wallet，Perp 真值位于 `core.trading_accounts`。

本轮补强：

- `AssetConversionService` 明确拒绝 `USDT_PERP` 作为转换来源或目标。
- `AssetConversionServiceTest#rejectsTheRetiredPerpetualWalletBeforeCreatingOrMutatingWallets` 证明拒绝发生在 wallet create/mutation 前。
- `Task7PostgresDemoLifecycleIT#conversionRequestIdCannotMintAssetsWhenItsFingerprintChanges` 覆盖同 conversionId 变更 wallet/amount 不得增发；该真实 PostgreSQL 用例当前因 Docker 跳过。
- 针对 `getOrCreateBalance/credit/debit/lock` 的 `USDT_PERP` writer 搜索无命中。

结论：保留 schema/read compatibility；目标 ready path 无 Perp wallet 镜像 writer。

### 3.3 symbol、WebSocket 与 mock

- `BTCUSDTPERP` 仅存在于旧 chart setting alias 清理列表；canonical route/settings/contract 测试均要求 `BTCUSDT-PERP`，且禁止 stripped provider spelling。
- `/topic/trading/accounts/**` 仅在 `WebSocketSecurityTest#legacyMutableAccountTopicsAreNeverSubscribable` 作为拒绝样例；真实账户流为 authenticated `/user/queue/trading-events`。
- `mockTradingMarkets`、`useMockBalances` 在目标源代码无命中。
- 旧 smoke 可读取 `PARTIALLY_FILLED`，但 canonical `smoke:usdt-demo-browser` 契约要求真实 HTTP、WebSocket 和 PostgreSQL 状态，不允许 browser interception/mock。

### 3.4 broker/FIX/insurance/ADL/MQ

- `BrokerExecutionAdapter`、`FixExecutionAdapter` 永远抛 `*_NOT_ENABLED`，`readyForLiveTrading=false`。
- `execution.mode` 默认 `disabled`；dev 使用 demo execution；配置中的 endpoint/api-key/account-id 为空槽，没有 execution HTTP/socket client。
- `FIXED` 是 funding fallback source，不是 FIX 私有交易连接。
- websocket `broker` 指 STOMP broker destination 权限，不是外部交易经纪商。
- `insurance`、`ADL`、`Kafka`、`RabbitMQ` 在 `backend/src/main` 无业务实现。

## 4. 安全与不变量审计

| 检查项 | 结论与证据 |
|---|---|
| `/api/admin/**` | `SecurityConfig` 要求 Admin role；高风险账户动作还有 authority 校验；`AdminAccountControllerTest`、`AdminActionPermissionContractTest`、Admin 前端 authority 测试通过 |
| DEMO/LIVE | `DemoExecutionGuard` 后端统一判定；LIVE、非 allowlist、非 ACTIVE 被拒；broker/FIX 占位 fail closed；`DemoExecutionGuardTest`、execution context/startup validator tests 通过 |
| 单 Demo | V46/V47 约束与 repository conflict handling；`DemoAccountLifecycleServiceTest#repeatedAndUniqueIndexRaceCreationReturnTheExistingAccountWithoutDoubleInit` 通过；真实 DB constraint 测试因 Docker 跳过 |
| full-fill-only | coordinator 拒绝非 full adapter result；无 partial writer；V52 并发 guard migration contract 通过 |
| ledger conservation | Spot/Perp transfer 成对流水；wallet/cash reconciliation 单测通过；Asset Conversion replay 校验完整 wallet/asset/op/amount 指纹；Admin finance 使用 account row lock 和 delta 更新，保留 UPL/used/free margin |
| funding | 合同金额、实际现金流、shortfall 分离；Cross balance/Isolated effective margin floor；shortfall Ledger/Audit；已提交负 funding 即使同账户另一结算失败也保留 liquidation scan |
| liquidation | Cross 部分成功、部分失败后仍 floor balance/equity/freeMargin 并写幂等 partial shortfall，状态保持 `LIQUIDATION_PENDING`；final 与 partial settlement id 分域 |
| worker 失败 | Pending/Protection transient failure 保持 pending，并由 `REQUIRES_NEW` 写安全、固定文案的 order event；Demo guard 拒绝不写事件；终态订单复查后跳过 |
| account events | STOMP JWT principal + user destination ownership；匿名仅可读公共 market topics；direct broker queue/topic spoof 被拒 |
| scheduler | 本轮 trading execution/funding/liquidation、market test/realtime/provider sync 均默认关闭；provider instrument sync 已改为 `matchIfMissing=false` 且 env default false |

Scheduler 声明边界：本报告不宣称仓库所有历史 `@Scheduled` 均已改造。`HomeCountersService.growUsersCounter`、`WalletDailySnapshotJob`、`WalletReconciliationJob` 是未在本 P0 变更的 legacy/P2 范围；`MarketTestData/QuoteBroadcast` 默认方法内 no-op。本 P0 新增或依赖的交易/provider scheduler 已验证 default-off。

## 5. 独立代码复核与修复闭环

组件级独立复核关闭了后端 attached protection 两项 P2，Web 订单/批量动作和产品头部复核均给出 Ready。最终整体复核随后发现 3 个 Important 和 1 个 Minor；下列第 18–21 项均已按 RED→GREEN 修复。closure review 结果为 `0 Critical / 0 Important / 0 Minor，Ready: Yes`。

复核期间发现并以 RED 测试修复：

1. Cross/Isolated 资金费可把资金池写成负数：增加 actual cashflow 与 `BANKRUPTCY_SHORTFALL` 账审分离。
2. 负资金费结算后未触发 liquidation scan：按账户批后去重扫描；另一 settlement 失败不能吞掉已提交触发。
3. Pending/Protection worker transient failure 只打日志：增加独立事务 order event，保持 pending 状态。
4. Cross 多仓强平第一仓成功、后续失败时负余额可长期提交：失败批次仍执行稳定结算，并使用幂等 partial shortfall。
5. Asset Conversion 信任 legacy wallet type 且同 conversionId 可变更指纹增发：拒绝 `USDT_PERP`，exact replay only，冲突零 mutation。
6. Admin finance 普通读改写导致并发 lost update，且用 balance 重建 equity/freeMargin 会抹掉 UPL：改为 account row lock，并对三个字段累加同一 delta。
7. Provider instrument sync 缺省开启：annotation 与 application default 均改为显式 opt-in。
8. `database-it` profile 仍使用 production broker execution：改为显式 Demo execution，并以配置契约测试锁定。
9. backend smoke 仍提交 legacy FX 字段、只读第一页且不验证真实 Spot wallet：改为 canonical Spot payload、全分页和 wallet/account summary 守恒断言。
10. attached TP/SL 每档都隐式占用父单全量，导致多档同类型无法成立：请求增加可选数量/单位，复用现有 quantity conversion 和捕获的 authority mark，按 protection type 汇总 canonical BASE。
11. `OrderResponse` 未暴露 protection optimistic-lock `version`：mapper/OpenAPI/生成类型补齐 nullable version；Web 不再猜测 `expectedVersion=0`。
12. legacy position TP/SL PATCH 可命中 Linear Perpetual：后端同时检查 position 与 symbol product type 并 fail closed；Web 引导至 canonical protection order 流程。
13. Admin 账户页只加载第一页、funding fallback 原因由前端猜测：改为复用全分页 helper，并只显示后端 `fallbackReason/sourceMode`。
14. Web 选取任意首账户、混用 Spot wallet/Perp 仓位推算、只读历史第一页：只选择 ACTIVE DEMO；Spot 读 SPOT wallet、Perp 读 free margin；订单/仓位/成交/funding/transfer 全分页。
15. Web 缺少 cancel-all/close-all、完整订单状态与 protection 专用 PATCH/DELETE：补齐 canonical API、确认/刷新/错误状态和桌面移动共用动作。
16. Perpetual funding 永远显示 `--`、attached protection UI 无每档数量：展示后端有效 funding cycle 并轮询刷新；每档显式数量/单位并校验同类型总量。
17. 桌面 Perpetual 页头硬编码 `现货/现金`：route-authoritative product 驱动页头，浏览器复测为 `永续/全仓`，Spot 保持 `现货/现金`。
18. Web funding countdown 使用 `nextFundingTime`，比实际结算点多一个周期：改用当前 active cycle 的 `fundingTime`，补不同双边界和过期 RED。
19. `close-all` HTTP 200 中的 `items[].status=FAILED` 被 UI 静默忽略：消费单次 `BatchActionResponse`，显示失败数、总数及 error code/message，仍复用 session finally refresh。
20. Router/Admin 把历史 funding row 当当前权威值：新增共享 `FundingRateFreshnessPolicy`，要求 `fundingTime > now` 且 `asOf >= now-staleSeconds`；过期/陈旧映射现有 unavailable，并修复慢查询跨边界与 Instant.MIN 下溢。
21. canonical backend smoke 只读 `page=0` 且 wallet 检查宽松：以 `size=1` 遍历 `totalPages`，验证每个 Spot wallet 的 `total=available+locked`、非负、精确释放，并关联检查 asset ledger 与不被 Spot 交易改写的 account summary。

## 6. Definition of Done 追踪

表中 PASS 指实现及当前可执行单元/契约证据通过；真实数据库/登录态浏览器覆盖的缺口单独以 BLOCKED 标注。所有后端类均包含在 `mvn test` 的 `1373/1373` 结果中。

| # | DoD | 精确证据 | 状态 |
|---:|---|---|---|
| 1 | 十个产品可见且其他不可交易 | `DemoExecutionGuardTest#exactTenTradableProductsAreAccepted`、`#nonWhitelistSymbolIsRejected`；`tradingProductVisibility.test.ts` | PASS |
| 2 | 每用户一个 Demo；Spot/Perp 各 50,000 | `DemoAccountLifecycleServiceTest#createsOneV47DemoAccountWithExactSpotPerpTruthAndTenSettings`、`#repeatedAndUniqueIndexRaceCreationReturnTheExistingAccountWithoutDoubleInit` | PASS；DB constraint execution blocked |
| 3 | PUBLIC Binance→OKX→LOCAL | `MarketBundleResolverTest` fallback/whole-bundle cases、`tradingQuoteMap.test.ts` source transition cases、browser smoke contract | PASS（unit/contract）；真实三模式 browser blocked |
| 4 | Spot MARKET/LIMIT/STOP/OCO | `Task6SpotOrderServiceTest`、`OcoOrderServiceTest`、`PendingOrderExecutionServiceTest` | PASS |
| 5 | Perp order/mode/margin 闭环 | `PerpetualOrderServiceTest`、`PositionEngineTest`、`TradingSettingsServiceTest` | PASS |
| 6 | 1–100×、三单位、逐仓调整 | `TradingSettingsServiceTest#missingVersionZeroRowUsesInsertAndLeverageHonorsEffectiveMaximum`、`QuantityConversionServiceTest`、`PositionMarginServiceTest` | PASS |
| 7 | 部分/全部平仓、cancel-all、close-all | `SystemCloseOrderServiceTest`、`CancelAllOrderServiceTest`、`CloseAllPositionServiceTest`；Web `runTradingBatchAction` / `BottomAccountBatchAction` tests | PASS（unit/contract）；真实登录态 browser blocked |
| 8 | 最多 10 个 TP/SL、MARKET/LIMIT、resize | `ProtectionOrderServiceTest#attachedCreateEnforcesTenRowsAndIndependentSameTypeQuantityBudgets`（含 BASE/QUOTE/CONTRACTS）、`#afterReductionKeepsOldestPerTypeAndShrinksOrExpiresNewestFirst`、`ProtectiveOrderExecutionServiceTest`；Web per-level quantity tests | PASS |
| 9 | Funding fallback、实际结算、补结算、幂等 | `FundingRateIngestionServiceTest#staleBinanceFallsBackToFreshOkxAtTheConfiguredThreshold`、`#bothExternalSourcesUnavailableUseFixedRateAndOneWholeAuthorityMark`、`FundingServiceTest#repeatedFundingSettlementForSamePositionAndFundingTimeIsNoOpAfterRestart`；`FundingRateFreshnessPolicyTest` 与 Router/Admin active/fresh tests | PASS |
| 10 | Isolated/Cross 强平标准记录 | `LiquidationWorkflowTest#isolatedSlotStillUnsafeAfterCancellationSubmitsExactlyOneWholeLiquidationClose`、`#crossLiquidationClosesEveryCrossSlotAndRestoresActiveAfterAllItemsSucceed`、`SystemCloseOrderServiceTest`、ledger/event tests | PASS |
| 11 | 穿仓不负且有 shortfall | `FundingServiceTest#crossFundingFloorsThePerpBalanceAndPersistsTheUnpaidShortfall`、`#isolatedFundingFloorsTheEffectiveSlotAndPersistsTheUnpaidShortfall`、`LiquidationSettlementServiceTest#pendingCrossLossIsFlooredAndRecordedOnceAcrossRetry` | PASS |
| 12 | Spot↔Perp 划转守恒 | `AccountTransferServiceTest#spotToPerpUsesOnlyAvailableAndWritesOppositePairedLedgers`、`#perpToSpotUsesOnlyFreeMarginAndConservesTheTwoBalances` | PASS；real DB execution blocked |
| 13 | reset gate；Admin cleanup/reset 分离 | `DemoAccountLifecycleServiceTest#activeNormalOcoOrProtectionOrderBlocksResetWithZeroMutation`、`AdminAccountCleanupServiceTest`、Admin dialog/API tests | PASS |
| 14 | WebSocket 所有权 | `WebSocketSecurityTest#authenticatedClientsMaySubscribeOnlyToTheirResolvedPrivateQueue`、`#legacyMutableAccountTopicsAreNeverSubscribable`、`#directBrokerQueuesCannotBypassUserDestinationIsolation` | PASS |
| 15 | Web/Admin 四视口真实操作 | 未登录 Web/Admin 在 1440×900、390×844 的真实渲染、响应式、权限跳转、offline fail-closed 和 console 检查通过；canonical 登录态 smoke 在基础设施启动前 FAIL，0 authenticated business PASS | **BLOCKED** |
| 16 | Java/Maven/Node/Docker 全量命令 | Maven 1373、Web 530、Admin 76、Node contract、build、architecture PASS；Docker engine FAIL；68 PostgreSQL tests skipped | **BLOCKED** |
| 17 | 无真实 private exchange/broker/FIX/LP | fail-closed adapter/context/startup tests；execution package 无 HTTP/socket client；静态审计 | PASS |
| 18 | 无保险/ADL/真实撮合/微服务/MQ | `backend/src/main` 静态审计无实现；canonical full-fill 是 Demo 单次全量执行 | PASS |

## 7. deliberately excluded legacy/P2

- `PARTIALLY_FILLED` 读取、筛选和 UI 展示兼容；P0 writer 禁止。
- `USDT_PERP` schema/type 兼容用于历史升级；active Demo 不 seed，转换 writer 禁止。
- 旧 chart stripped symbol 仅用于清除历史 local settings；canonical trading route/API 不接受。
- 旧 smoke 仍可读取 partial status；最终验收只认 `smoke:usdt-demo-browser`。
- 上述历史通用 scheduler 未纳入本 P0 改造，不得据本报告声称全仓 scheduler default-off。
- 公共 Binance/OKX market data HTTP/WebSocket 是允许的数据源，不属于 private execution。

## 8. 环境恢复后的强制补跑

在 Docker Desktop 切换为 Linux containers 且 `dockerDesktopLinuxEngine` 可访问后，必须按顺序执行：

```powershell
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml ps

$env:JAVA_HOME = 'C:\workspace\.tools\microsoft-jdk-21\jdk-21.0.11+10'
$node = 'C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
$npmCli = 'E:\software\nodejs\node_modules\npm\bin\npm-cli.js'
$env:Path = "$(Split-Path -Parent $node);$env:Path"
& 'C:\workspace\.tools\apache-maven-3.9.9\bin\mvn.cmd' -f backend/pom.xml '-Dtest=PostgresDatabaseIT,V46V47EmptyDatabaseIT,V45ToV47DemoResetIT,Task5PostgresFullFillIT,Task6PostgresSpotIT,Task7PostgresDemoLifecycleIT,Task8PostgresTradingSettingsIT,Task9PostgresPerpetualOrderIT,Task10PostgresProtectionIT,Task11PostgresFundingIT,DemoTradingConcurrencyIT,PerpetualPositionConcurrencyIT,ProtectionOrderConcurrencyIT,FundingLiquidationConcurrencyIT' test

& $node $npmCli run smoke:usdt-demo-browser
```

验收补跑必须满足：

- PostgreSQL/Testcontainers：0 skipped、0 failures、0 errors。
- smoke report：`status=PASS`。
- 四视口均有真实截图和 REST/PostgreSQL 关联断言。
- PUBLIC primary、OKX fallback、LOCAL_SIMULATED、recovery 均有 source evidence。
- provider 状态恢复、浏览器和服务全部清理后才允许最终 PASS。
