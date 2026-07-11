# USDT Spot and Perpetual Demo P0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 完成五个 USDT 现货与五个 USDT 永续产品的持久化模拟交易闭环，并在 Binance→OKX→本地行情、桌面/移动 Web 和 Admin 中通过真实验收。

**Architecture:** 保留现有 Spring Boot 模块化单体、React 应用、MyBatis-Plus、PostgreSQL、Redis 和 STOMP。所有用户及系统成交统一进入 DemoExecutionGuard、authoritative market bundle、FullFillCoordinator、OrderFillService，再路由到 SpotSettlementService 或 PositionEngine；不建立真实撮合、保险基金或 ADL。

**Tech Stack:** Java 21、Spring Boot 3.5.7、MyBatis-Plus 3.5.12、PostgreSQL 16、Redis 7、Flyway、React 19、TypeScript 5.8、Vite 7、STOMP、KLineCharts。

## Global Constraints

- 权威规格：docs/superpowers/specs/2026-07-10-usdt-spot-perpetual-demo-scheme-a-design-and-implementation.md。
- JDK：C:\workspace\.tools\microsoft-jdk-21\jdk-21.0.11+10。
- Maven：C:\workspace\.tools\apache-maven-3.9.9\bin\mvn.cmd。
- Node：C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe，版本 24.14.0。
- npm CLI：E:\software\nodejs\node_modules\npm\bin\npm-cli.js。
- Docker Desktop：C:\Program Files\Docker\Docker\Docker Desktop.exe；启动时必须使用隐藏窗口。
- 数据库只由 Flyway 管理；不引入 JPA repository。
- execution.mode 非 demo 或 account_type 非 DEMO 时，所有交易性写入必须为零。
- 所有 scheduler 默认关闭；仅 dev/demo 验收 profile 显式开启。
- 新成交必须 full-fill-only；不写 PARTIALLY_FILLED。
- 不调用任何真实 private exchange、broker、FIX 或 LP API。
- 修改交易 API 必须同步 Web、Admin、OpenAPI 和 shared-types。
- 每个实现任务采用 RED → GREEN → 相关回归 → commit。
- 不删除测试来隐藏失败；每个 bug/回归必须有复现测试。
- 所有命令从 worktree 的 fx-trading-platform 目录执行；Maven 统一使用 & $mvn -f backend/pom.xml，Node/npm 统一使用 & $node 和 & $node $npmCli。
- 全局数据库锁序唯一固定为 account → account_symbol_setting → wallet balances（asset key 排序）→ positions（slot/UUID 排序）→ orders（UUID 排序）→ ledger；任何流程只能跳过不需要的层级，不得逆序。
- 行情 HTTP/本地生成必须发生在取得数据库锁之前；取得锁后复验 expiresAt，过期则零 mutation 回滚并在事务外重新取价。

## File Structure and Ownership

### Backend new units

| 文件 | 单一职责 |
|---|---|
| backend/src/main/java/com/fxplatform/execution/DemoExecutionGuard.java | 校验 execution mode、DEMO account、产品白名单 |
| backend/src/main/java/com/fxplatform/execution/ExecutableMarketSnapshot.java | 不可变成交行情快照 |
| backend/src/main/java/com/fxplatform/execution/FullFillCoordinator.java | 计算单次全量成交并调用 OrderFillService |
| backend/src/main/java/com/fxplatform/market/model/SpotMarketBundle.java | 同 venue 的 Spot quote/depth/trades bundle |
| backend/src/main/java/com/fxplatform/market/model/PerpetualMarketBundle.java | 同 venue 的 Perp quote/mark/index bundle |
| backend/src/main/java/com/fxplatform/market/provider/MarketBundleResolver.java | Binance→OKX→local 整包 fallback |
| backend/src/main/java/com/fxplatform/market/adapter/binance/BinanceUsdMMarketDataProvider.java | Binance USD-M public quote/reference/funding |
| backend/src/main/java/com/fxplatform/market/adapter/okx/OkxSwapMarketDataProvider.java | OKX SWAP public quote/reference/funding |
| backend/src/main/java/com/fxplatform/market/adapter/local/LocalSpotMarketDataProvider.java | Spot 离线 bundle |
| backend/src/main/java/com/fxplatform/market/adapter/local/LocalPerpMarketDataProvider.java | Perp 离线 bundle |
| backend/src/main/java/com/fxplatform/trading/entity/AccountSymbolSettingEntity.java | account+symbol leverage/margin/unit |
| backend/src/main/java/com/fxplatform/trading/repository/AccountSymbolSettingRepository.java | settings 持久化与锁 |
| backend/src/main/java/com/fxplatform/trading/service/TradingSettingsService.java | position mode 与 symbol settings |
| backend/src/main/java/com/fxplatform/trading/service/QuantityConversionService.java | BASE/QUOTE/CONTRACTS → base quantity |
| backend/src/main/java/com/fxplatform/trading/service/OcoOrderService.java | Spot OCO group、shared hold、对腿取消 |
| backend/src/main/java/com/fxplatform/trading/service/SystemCloseOrderService.java | 手动/保护/强平/Admin 统一平仓 |
| backend/src/main/java/com/fxplatform/trading/service/ProtectionOrderService.java | 最多 10 档保护单和 resize |
| backend/src/main/java/com/fxplatform/risk/service/PerpetualRiskService.java | Cross/Isolated risk 与预计强平价 |
| backend/src/main/java/com/fxplatform/trading/service/FundingRateIngestionService.java | funding source priority/current/history |
| backend/src/main/java/com/fxplatform/account/service/AccountTransferService.java | Spot↔Perp USDT 原子划转 |
| backend/src/main/java/com/fxplatform/account/service/DemoAccountLifecycleService.java | 幂等创建、reset gate 和历史保留 |

### Web new/focused units

| 文件 | 单一职责 |
|---|---|
| apps/web/src/features/trading/types/tradingSettings.ts | position/margin/leverage/unit contract |
| apps/web/src/features/trading/components/TradingSettingsBar.tsx | position mode、margin、leverage、unit |
| apps/web/src/features/trading/components/MultiLevelProtectionEditor.tsx | 最多 10 档 TP/SL |
| apps/web/src/features/trading/components/PositionActionDialog.tsx | 部分平仓、margin adjustment |
| apps/web/src/features/trading/components/MarketSourceBadge.tsx | LIVE/OKX/LOCAL/stale |
| apps/web/src/app/tradingRoutes.ts | 路由到产品/symbol 的唯一映射 |

### Admin new/focused units

| 文件 | 单一职责 |
|---|---|
| apps/admin/src/pages/AccountDetailPage.tsx | balance/order/trade/position/funding/transfer |
| apps/admin/src/pages/FundingConfigPage.tsx | 每 symbol funding priority/config |
| apps/admin/src/components/HighRiskActionDialog.tsx | reason、pending、防双击、audit id |

---

### Task 0: Create isolated execution environment and capture baseline

**Files:**
- Read: AGENTS.md
- Read: fx-trading-platform/package.json
- Read: fx-trading-platform/backend/pom.xml
- Create in worktree later: docs/superpowers/reports/2026-07-11-p0-baseline.md

**Interfaces:**
- Consumes: JDK/Maven/Node/Docker paths from Global Constraints.
- Produces: isolated codex branch/worktree and a truthful baseline report.

- [ ] **Step 1: Create the isolated worktree**

Use superpowers:using-git-worktrees. Choose a branch named codex/usdt-spot-perp-p0 and a workspace outside the current dirty tree. Copy only the approved spec, analysis report and this plan into the worktree, then commit those documents before code.

After creation, run:

    Set-Location '<worktree>\fx-trading-platform'

- [ ] **Step 2: Start Docker Desktop and wait for readiness**

Run in PowerShell:

    Start-Process -FilePath 'C:\Program Files\Docker\Docker\Docker Desktop.exe' -WindowStyle Hidden
    docker version
    docker compose -f infra/docker-compose.yml up -d
    docker compose -f infra/docker-compose.yml ps

Expected: Docker client and server versions are present; postgres and redis are running/healthy enough to accept connections.

- [ ] **Step 3: Set Java/Maven runtime**

    $env:JAVA_HOME='C:\workspace\.tools\microsoft-jdk-21\jdk-21.0.11+10'
    $env:Path="$env:JAVA_HOME\bin;C:\workspace\.tools\apache-maven-3.9.9\bin;$env:Path"
    $mvn='C:\workspace\.tools\apache-maven-3.9.9\bin\mvn.cmd'
    $node='C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
    $npmCli='E:\software\nodejs\node_modules\npm\bin\npm-cli.js'
    & $env:JAVA_HOME\bin\java.exe -version
    & $mvn -version
    & $node --version
    & $node $npmCli --version

Expected: Java 21.0.11 and Maven 3.9.9.

- [ ] **Step 4: Run the untouched baseline**

    & $mvn -f backend/pom.xml test
    & $node $npmCli run web:test
    & $node $npmCli --workspace apps/admin test
    & $node $npmCli run verify:architecture

Expected: record exact pass/fail counts. Do not repair unrelated failures in this task.

- [ ] **Step 5: Write and commit the baseline**

The report must list tool versions, commands, exit codes and pre-existing failures. Commit only docs:

    git add docs/superpowers
    git commit -m "docs: define USDT spot and perpetual P0 baseline"

### Task 1: Add focused V46/V47 migration contracts

**Files:**
- Create: backend/src/main/resources/db/migration/V46__demo_spot_perp_core.sql
- Create: backend/src/main/resources/db/migration/V47__demo_funding_sources_and_reseed.sql
- Create: backend/src/test/java/com/fxplatform/database/V46V47EmptyDatabaseIT.java
- Create: backend/src/test/java/com/fxplatform/database/V45ToV47DemoResetIT.java
- Modify: backend/src/test/java/com/fxplatform/database/PostgresDatabaseIT.java

**Interfaces:**
- Consumes: V1–V45 schemas.
- Produces: account_symbol_settings; order/trade/position snapshots; funding source columns; ten product seeds; one active DEMO constraint.

- [ ] **Step 1: Write empty-database RED test**

Add assertions equivalent to:

    assertColumn("trading", "orders", "position_side");
    assertColumn("trading", "trades", "fee_asset");
    assertColumn("trading", "positions", "margin_mode");
    assertTable("trading", "account_symbol_settings");
    assertExactTradableSymbols(Set.of(
        "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
        "BTCUSDT-PERP", "ETHUSDT-PERP", "BNBUSDT-PERP",
        "SOLUSDT-PERP", "XRPUSDT-PERP"));
    assertNotTradable("EURUSD");
    assertNotTradable("BTCUSD-INVERSE");
    assertNotTradable("BTCUSDT-OPTION");
    assertUniqueActiveDemoAccount();

- [ ] **Step 2: Write V45 upgrade/destructive DEMO RED test**

Fixture:

    create USER and ADMIN
    create two DEMO accounts for USER with orders/trades/positions/wallet/ledger
    create one LIVE account with history
    migrate to V47

Assertions:

    one new DEMO account remains for USER
    new DEMO balance=50000 USDT
    SPOT/USDT total=available=50000
    old DEMO trading rows removed
    LIVE rows unchanged
    ADMIN/provider/config rows unchanged

- [ ] **Step 3: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=V46V47EmptyDatabaseIT,V45ToV47DemoResetIT" test

Expected: FAIL because V46/V47 and new columns do not exist.

- [ ] **Step 4: Implement V46**

Add exactly the fields and indexes in spec section 21.1. Reuse existing legacy columns; do not drop them yet. Create account_symbol_settings with:

    account_id UUID REFERENCES core.trading_accounts(id)
    symbol VARCHAR(32)
    leverage INTEGER DEFAULT 10 CHECK (leverage BETWEEN 1 AND 100)
    margin_mode VARCHAR(16) DEFAULT 'CROSS'
    quantity_unit VARCHAR(16) DEFAULT 'BASE'
    version BIGINT DEFAULT 0
    UNIQUE(account_id, symbol)

- [ ] **Step 5: Implement V47**

Add funding source columns, clean DEMO rows in FK-safe order, recreate one DEMO per active non-admin user, seed Spot/Perp balances and ten symbols/bindings. Preserve LIVE and all non-account configuration.

- [ ] **Step 6: Run GREEN**

    & $mvn -f backend/pom.xml "-Dtest=V46V47EmptyDatabaseIT,V45ToV47DemoResetIT,PostgresDatabaseIT" test

Expected: PASS.

- [ ] **Step 7: Commit**

    git add backend/src/main/resources/db/migration backend/src/test/java/com/fxplatform/database
    git commit -m "feat: add demo spot and perpetual database semantics"

### Task 2: Add domain enums, entities and generated API contract

**Files:**
- Reuse: backend/src/main/java/com/fxplatform/market/model/ProductType.java
- Create: backend/src/main/java/com/fxplatform/trading/enums/PositionMode.java
- Create: backend/src/main/java/com/fxplatform/trading/enums/PositionSide.java
- Create: backend/src/main/java/com/fxplatform/trading/enums/MarginMode.java
- Create: backend/src/main/java/com/fxplatform/trading/enums/QuantityUnit.java
- Create: backend/src/main/java/com/fxplatform/trading/enums/TimeInForce.java
- Create: backend/src/main/java/com/fxplatform/trading/enums/OrderOrigin.java
- Create: backend/src/main/java/com/fxplatform/trading/enums/ProtectionType.java
- Create: backend/src/main/java/com/fxplatform/trading/enums/TriggerPriceType.java
- Create: backend/src/main/java/com/fxplatform/trading/enums/TriggerExecutionType.java
- Create: backend/src/main/java/com/fxplatform/trading/enums/LiquidityRole.java
- Modify: backend/src/main/java/com/fxplatform/trading/enums/OrderType.java
- Modify: backend/src/main/java/com/fxplatform/trading/enums/OrderStatus.java
- Modify: backend/src/main/java/com/fxplatform/trading/entity/OrderEntity.java
- Modify: backend/src/main/java/com/fxplatform/trading/entity/TradeEntity.java
- Modify: backend/src/main/java/com/fxplatform/trading/entity/PositionEntity.java
- Modify: backend/src/main/java/com/fxplatform/account/entity/TradingAccountEntity.java
- Create: backend/src/main/java/com/fxplatform/trading/entity/AccountSymbolSettingEntity.java
- Create: backend/src/main/java/com/fxplatform/trading/repository/AccountSymbolSettingRepository.java
- Modify: backend/src/main/java/com/fxplatform/trading/dto/request/CreateOrderRequest.java
- Modify: backend/src/main/java/com/fxplatform/trading/dto/response/OrderResponse.java
- Modify: backend/src/main/java/com/fxplatform/trading/dto/response/PositionResponse.java
- Modify: backend/src/main/java/com/fxplatform/common/market/SymbolNormalizer.java
- Test: backend/src/test/java/com/fxplatform/DomainEnumContractTest.java
- Test: backend/src/test/java/com/fxplatform/trading/dto/request/CreateOrderRequestTest.java
- Test: backend/src/test/java/com/fxplatform/common/market/SymbolNormalizerTest.java

**Interfaces:**
- Produces: immutable order/position snapshots and canonical BTCUSDT-PERP.
- Consumes: V46/V47 columns.

- [ ] **Step 1: Write RED enum and DTO tests**

Required assertions:

    PositionMode.valueOf("HEDGE")
    MarginMode.valueOf("ISOLATED")
    OrderType.valueOf("STOP_MARKET")
    OrderStatus.valueOf("PENDING_ACTIVATION")
    CreateOrderRequest accepts positionSide, quantityUnit, reduceOnly, attachedProtections

- [ ] **Step 2: Write RED symbol normalization test**

    assertEquals("BTCUSDT-PERP", normalize("btc-usdt-perp"));
    assertEquals("BTCUSDT", normalize("btc/usdt"));

- [ ] **Step 3: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=DomainEnumContractTest,CreateOrderRequestTest,SymbolNormalizerTest" test

Expected: FAIL on missing enum/fields and stripped PERP suffix.

- [ ] **Step 4: Implement minimal enums/entities/contracts**

Do not add IOC/FOK/ADL/insurance fields. Every OrderResponse must expose:

    productType, positionMode, positionSide, marginMode,
    quantityUnit, originalQuantity, baseQuantity,
    reduceOnly, origin, systemReason,
    fee, feeAsset, liquidityRole,
    trigger and parent/group identifiers.

- [ ] **Step 5: Fix SymbolNormalizer/provider boundary**

Platform normalizer preserves -PERP. Provider adapters alone map:

    BTCUSDT-PERP -> BTCUSDT       for Binance USD-M
    BTCUSDT-PERP -> BTC-USDT-SWAP for OKX

- [ ] **Step 6: Run GREEN and contract export**

    & $mvn -f backend/pom.xml "-Dtest=DomainEnumContractTest,CreateOrderRequestTest,PositionResponseContractTest,SymbolNormalizerTest" test
    & $node $npmCli run contract:export
    & $node $npmCli run contract:generate

Expected: tests pass and packages/shared-types/src/generated/openapi.ts changes.

- [ ] **Step 7: Commit**

    git add backend/src/main packages/shared-types
    git commit -m "feat: add spot and perpetual trading contracts"

### Task 3: Enforce Demo execution and account-first locking

**Files:**
- Create: backend/src/main/java/com/fxplatform/execution/DemoExecutionGuard.java
- Modify: backend/src/main/java/com/fxplatform/account/repository/TradingAccountRepository.java
- Modify: backend/src/main/java/com/fxplatform/wallet/repository/WalletBalanceRepository.java
- Modify: backend/src/main/java/com/fxplatform/trading/repository/OrderRepository.java
- Modify: backend/src/main/java/com/fxplatform/trading/repository/PositionRepository.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/OrderService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/FundingService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/LiquidationService.java
- Test: backend/src/test/java/com/fxplatform/execution/DemoExecutionGuardTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/OrderPositionConcurrencyTest.java

**Interfaces:**
- Produces: requireDemo(TradingAccountEntity, ProductType) and FOR UPDATE account/wallet/position methods.
- Guarantees: every repository lock follows the single Global Constraints order; each flow may skip unused lock classes but never reorder them.

- [ ] **Step 1: Write RED guard matrix**

Cases:

    demo mode + DEMO + allowed product => allowed
    disabled + DEMO => EXECUTION_DISABLED
    demo + LIVE => DEMO_ACCOUNT_REQUIRED
    demo + FX_MARGIN/INVERSE_PERP => PRODUCT_NOT_ALLOWED
    demo + CRYPTO_SPOT/LINEAR_PERP + non-whitelist symbol => SYMBOL_NOT_ALLOWED
    each of the exact ten whitelist symbols => allowed when tradable=true

- [ ] **Step 2: Write RED PostgreSQL lock test**

Two threads attempt the same account debit/fill. Assert:

    final balance never negative
    one order fills or both serialize safely
    no duplicate Trade/Ledger

- [ ] **Step 3: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=DemoExecutionGuardTest,OrderPositionConcurrencyTest" test

- [ ] **Step 4: Implement guard and lock repositories**

Repository methods must use SELECT ... FOR UPDATE via mapper SQL or MyBatis-Plus-compatible custom query. Services must re-read account under lock before mutation.

- [ ] **Step 5: Add guard to every execution entry**

Static search must show guard use in MARKET, pending, protection, funding, liquidation, Admin force close and reset/transfer writers.

- [ ] **Step 6: Run GREEN**

    & $mvn -f backend/pom.xml "-Dtest=DemoExecutionGuardTest,OrderPositionConcurrencyTest,ExecutionModeStartupValidatorTest" test

- [ ] **Step 7: Commit**

    git add backend/src/main backend/src/test
    git commit -m "feat: guard and serialize demo trading writes"

### Task 4: Build authoritative market bundles and provider fallback

**Files:**
- Create: backend/src/main/java/com/fxplatform/market/model/MarketSourceMode.java
- Create: backend/src/main/java/com/fxplatform/market/model/SpotMarketBundle.java
- Create: backend/src/main/java/com/fxplatform/market/model/PerpetualMarketBundle.java
- Create: backend/src/main/java/com/fxplatform/market/dto/PerpetualReferenceResponse.java
- Create: backend/src/main/java/com/fxplatform/market/provider/MarketBundleResolver.java
- Create: backend/src/main/java/com/fxplatform/market/provider/MarketBundleValidator.java
- Create: backend/src/main/java/com/fxplatform/market/service/MarketSourceSelectionTracker.java
- Create: backend/src/main/java/com/fxplatform/market/adapter/binance/BinanceUsdMMarketDataProvider.java
- Create: backend/src/main/java/com/fxplatform/market/adapter/okx/OkxSwapMarketDataProvider.java
- Create: backend/src/main/java/com/fxplatform/market/adapter/local/LocalSpotMarketDataProvider.java
- Create: backend/src/main/java/com/fxplatform/market/adapter/local/LocalPerpMarketDataProvider.java
- Modify: backend/src/main/java/com/fxplatform/market/provider/ProviderResolver.java
- Modify: backend/src/main/java/com/fxplatform/market/provider/MarketDataRouter.java
- Modify: backend/src/main/java/com/fxplatform/market/service/QuoteService.java
- Modify: backend/src/main/java/com/fxplatform/market/service/DemoMarketDataGenerator.java
- Modify: backend/src/main/java/com/fxplatform/market/controller/MarketController.java
- Test: backend/src/test/java/com/fxplatform/market/provider/MarketBundleResolverTest.java
- Test: backend/src/test/java/com/fxplatform/market/adapter/binance/BinanceUsdMMarketDataProviderTest.java
- Test: backend/src/test/java/com/fxplatform/market/adapter/okx/OkxSwapMarketDataProviderTest.java
- Test: backend/src/test/java/com/fxplatform/market/service/LocalPerpMarketDataProviderTest.java

**Interfaces:**
- Produces:

    SpotMarketBundle resolveSpot(String platformSymbol, CandleRequest candleRequest)
    PerpetualMarketBundle resolvePerp(String platformSymbol, CandleRequest candleRequest)

- Both bundles contain platformSymbol, providerSymbol, providerCode, sourceMode, bid, ask, last, orderBook, recentTrades, candles, asOf and expiresAt. PerpetualMarketBundle additionally contains mark and index. Every field in one returned bundle is from one provider candidate; its freshness is the most conservative child timestamp.

- [ ] **Step 1: Write RED fallback tests**

Scenarios:

    Binance complete => BINANCE selected
    Binance throws, OKX complete => OKX selected
    Binance quote only/no mark, OKX complete => entire OKX bundle
    both fail => LOCAL_SIMULATED
    no cross-provider field mixing
    quote/depth/trades/candles all carry the selected provider identity
    exact platform symbol BTCUSDT-PERP and venue-specific providerSymbol coexist

- [ ] **Step 2: Write RED local mark test**

    index = local spot reference
    mark = index * (1 + clampedPremium)
    bid <= last <= ask
    all fields share generation/asOf

- [ ] **Step 3: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=MarketBundleResolverTest,BinanceUsdMMarketDataProviderTest,OkxSwapMarketDataProviderTest,LocalPerpMarketDataProviderTest" test

- [ ] **Step 4: Implement provider adapters with injected HTTP clients**

Tests must use fixture responses; unit tests may not access the internet. Map provider symbol only inside the adapter.

- [ ] **Step 5: Implement candidate fallback and freshness**

ProviderResolver returns ordered candidates. Any missing required quote/depth/trades/candles/reference field, exception or expired snapshot moves to the next entire provider. MarketSourceSelectionTracker emits one source-change event on fallback and recovery.

- [ ] **Step 6: Extend REST/status**

Add /api/market/perpetuals/{symbol}/reference and sourceMode/providerCode/asOf/expiresAt/stale fields to existing responses.

- [ ] **Step 7: Run GREEN**

    & $mvn -f backend/pom.xml "-Dtest=MarketBundleResolverTest,BinanceUsdMMarketDataProviderTest,OkxSwapMarketDataProviderTest,LocalPerpMarketDataProviderTest,MarketDataRouterTest,QuoteServiceTest,MarketControllerTest" test

- [ ] **Step 8: Commit**

    git add backend/src/main backend/src/test
    git commit -m "feat: add Binance OKX and local market bundle fallback"

### Task 5: Extract the shared full-fill coordinator

**Files:**
- Create: backend/src/main/java/com/fxplatform/execution/ExecutableMarketSnapshot.java
- Create: backend/src/main/java/com/fxplatform/execution/FullFillRequest.java
- Create: backend/src/main/java/com/fxplatform/execution/FullFillResult.java
- Create: backend/src/main/java/com/fxplatform/execution/FullFillCoordinator.java
- Modify: backend/src/main/java/com/fxplatform/execution/SimulatedExecutionAdapter.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/OrderFillService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/OrderService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java
- Test: backend/src/test/java/com/fxplatform/execution/FullFillCoordinatorTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/TradingWorkflowRegressionProtectionTest.java

**Interfaces:**
- Consumes: DemoExecutionGuard and MarketBundleResolver.
- Produces:

    FullFillResult execute(FullFillRequest request, ExecutableMarketSnapshot snapshot)

- [ ] **Step 1: Write RED price/fee matrix**

Assert exact BigDecimal results for:

    MARKET BUY ask*(1+0.0001), taker 0.0005
    MARKET SELL bid*(1-0.0001), taker
    immediate LIMIT uses best-or-limit and taker
    resting LIMIT uses best-or-limit and maker 0.0002
    STOP_MARKET uses trigger snapshot then MARKET price

- [ ] **Step 2: Write RED no-partial invariant**

Pass a fake adapter result with filledQuantity != quantity. Expected:

    BusinessException("PARTIAL_FILL_NOT_SUPPORTED")
    no Order/Trade/Wallet/Position/Ledger mutation

- [ ] **Step 3: Write RED lock-wait freshness test**

Resolve a valid snapshot, block on the account lock until expiresAt passes, then release the lock. Assert the coordinator makes zero mutation with the expired snapshot and the caller retries outside the transaction with a newly resolved whole bundle (or returns MARKET_DATA_STALE when no fresh candidate exists).

- [ ] **Step 4: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=FullFillCoordinatorTest,TradingWorkflowRegressionProtectionTest" test

- [ ] **Step 5: Implement coordinator**

The coordinator computes price, fee rate, liquidity role and slippage once, then calls OrderFillService. Pending and MARKET paths must no longer duplicate fee math.

- [ ] **Step 6: Make each pending order its own transaction**

The scheduler loop must invoke a separate transactional worker per order so one failure cannot roll back the batch.

- [ ] **Step 7: Run GREEN**

    & $mvn -f backend/pom.xml "-Dtest=FullFillCoordinatorTest,SimulatedExecutionAdapterFeeTest,OrderFillServiceTest,PendingOrderExecutionServiceTest,TradingWorkflowRegressionProtectionTest" test

- [ ] **Step 8: Commit**

    git add backend/src/main backend/src/test
    git commit -m "refactor: unify demo full-fill execution"

### Task 6: Complete Spot MARKET, LIMIT, STOP_MARKET and OCO

**Files:**
- Create: backend/src/main/java/com/fxplatform/trading/dto/request/CreateOcoOrderRequest.java
- Create: backend/src/main/java/com/fxplatform/trading/dto/response/OcoOrderResponse.java
- Create: backend/src/main/java/com/fxplatform/trading/service/QuantityConversionService.java
- Create: backend/src/main/java/com/fxplatform/trading/service/OrderHoldCalculator.java
- Create: backend/src/main/java/com/fxplatform/trading/service/OcoOrderService.java
- Create: backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionProcessor.java
- Modify: backend/src/main/java/com/fxplatform/risk/service/RiskCheckService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/OrderService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/SpotSettlementService.java
- Modify: backend/src/main/java/com/fxplatform/wallet/service/WalletService.java
- Modify: backend/src/main/java/com/fxplatform/trading/repository/OrderRepository.java
- Modify: backend/src/main/java/com/fxplatform/trading/controller/TradingController.java
- Test: backend/src/test/java/com/fxplatform/trading/service/QuantityConversionServiceTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/OrderHoldCalculatorTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/OcoOrderServiceTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/SpotSettlementServiceTest.java

**Interfaces:**
- Spot MARKET BUY consumes QUOTE USDT budget; MARKET SELL consumes BASE quantity.
- LIMIT, STOP_MARKET and both OCO legs consume BASE quantity.
- OCO is two rows in trading.orders with one contingencyGroupId and one holdOwnerOrderId.

- [ ] **Step 1: Write RED quantity and hold tests**

Cover:

    MARKET BUY QUOTE budget is not converted on the client contract
    MARKET SELL, LIMIT, STOP_MARKET and OCO use BASE
    BUY hold includes worst-case spend; SELL hold is base quantity
    tick/step/min notional come from market.symbols

- [ ] **Step 2: Write RED Spot fee tests**

Assert BUY receives base minus 0.05% taker or 0.02% maker fee with feeAsset=base; SELL receives USDT minus fee with feeAsset=USDT. Assert wallet total=available+locked after every path.

- [ ] **Step 3: Write RED immediate-limit and pending tests**

    BUY limit >= current ask => immediate full fill
    SELL limit <= current bid => immediate full fill
    otherwise PENDING
    STOP_MARKET uses last price trigger
    cancelled pending order releases hold exactly once

- [ ] **Step 4: Write RED OCO matrix**

Cover BUY and SELL price validation, one shared hold, one-leg-fill-cancels-other, manual cancel cancels group, replay returns same group, and concurrent leg triggers create at most one Trade.

- [ ] **Step 5: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=QuantityConversionServiceTest,OrderHoldCalculatorTest,SpotSettlementServiceTest,OcoOrderServiceTest,PendingOrderExecutionServiceTest" test

- [ ] **Step 6: Implement Spot semantics**

Perform hold and order creation in one account/wallet transaction. PendingOrderExecutionProcessor uses REQUIRES_NEW per order. OCO group claim and peer cancellation occur under ordered row locks.

- [ ] **Step 7: Run GREEN and wallet regression**

    & $mvn -f backend/pom.xml "-Dtest=QuantityConversionServiceTest,OrderHoldCalculatorTest,SpotSettlementServiceTest,OcoOrderServiceTest,PendingOrderExecutionServiceTest,WalletServiceTest,SpotPositionServiceTest,WalletReconciliationServiceTest" test

- [ ] **Step 8: Commit**

    git add backend/src/main backend/src/test
    git commit -m "feat: complete spot demo orders and OCO"

### Task 7: Rebuild the DEMO lifecycle and add Spot-Perp transfer

**Files:**
- Create: backend/src/main/java/com/fxplatform/account/dto/AccountTransferRequest.java
- Create: backend/src/main/java/com/fxplatform/account/dto/AccountTransferResponse.java
- Create: backend/src/main/java/com/fxplatform/account/dto/DemoResetRequest.java
- Create: backend/src/main/java/com/fxplatform/account/dto/DemoResetResponse.java
- Create: backend/src/main/java/com/fxplatform/account/service/AccountTransferService.java
- Create: backend/src/main/java/com/fxplatform/account/service/DemoAccountLifecycleService.java
- Modify: backend/src/main/java/com/fxplatform/account/service/AccountService.java
- Modify: backend/src/main/java/com/fxplatform/auth/service/AuthService.java
- Modify: backend/src/main/java/com/fxplatform/account/controller/AccountController.java
- Modify: backend/src/main/java/com/fxplatform/ledger/service/LedgerService.java
- Modify: backend/src/main/java/com/fxplatform/wallet/service/WalletService.java
- Test: backend/src/test/java/com/fxplatform/account/service/DemoAccountLifecycleServiceTest.java
- Test: backend/src/test/java/com/fxplatform/account/service/AccountTransferServiceTest.java
- Test: backend/src/test/java/com/fxplatform/account/AccountWalletInitializationTest.java

**Interfaces:**
- getOrCreateDemoAccount(userId) is idempotent.
- transfer(accountId, direction, amount, requestId) is atomic and idempotent.
- reset(accountId, requestId) rejects active orders or positions and preserves history.

- [ ] **Step 1: Write RED initialization tests**

Assert registration and repeated creation produce exactly one active DEMO account, Spot available/total=50,000 USDT, Perp balance/equity/free=50,000 USDT, leverage=10, and no USDT_PERP wallet.

- [ ] **Step 2: Write RED transfer tests**

Cover SPOT_TO_PERP and PERP_TO_SPOT, available/free-only checks, paired ledgers with one transferId, 1:1/no fee, conservation, duplicate requestId, and concurrent insufficient-funds rejection.

- [ ] **Step 3: Write RED reset gate tests**

Active normal order, OCO leg, protection order or open position must reject. A clean reset restores current balances/settings but preserves old orders, trades, funding, transfers and ledger history.

- [ ] **Step 4: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=DemoAccountLifecycleServiceTest,AccountTransferServiceTest,AccountWalletInitializationTest,AuthServiceTest" test

- [ ] **Step 5: Implement lifecycle and transfer**

Reuse WalletService and LedgerService. Follow the global lock order; transfer uses only account → sorted Spot wallet → ledger, while reset additionally takes sorted positions/orders at their defined positions in that order. Do not route transfer through AssetConversionService and do not create a transfer table.

- [ ] **Step 6: Run GREEN and reconciliation**

    & $mvn -f backend/pom.xml "-Dtest=DemoAccountLifecycleServiceTest,AccountTransferServiceTest,AccountWalletInitializationTest,AccountServiceRegressionProtectionTest,AuthServiceTest,LedgerServiceTest,WalletReconciliationServiceTest,AccountSummarySnapshotIntegrationTest" test

- [ ] **Step 7: Commit**

    git add backend/src/main backend/src/test
    git commit -m "feat: add demo lifecycle and spot perp transfers"

### Task 8: Implement Perpetual settings, quantity units and position modes

**Files:**
- Create: backend/src/main/java/com/fxplatform/trading/service/TradingSettingsService.java
- Create: backend/src/main/java/com/fxplatform/account/controller/AccountTradingSettingsController.java
- Create: backend/src/main/java/com/fxplatform/account/dto/TradingSettingsResponse.java
- Create: backend/src/main/java/com/fxplatform/account/dto/UpdatePositionModeRequest.java
- Create: backend/src/main/java/com/fxplatform/account/dto/UpdateSymbolSettingsRequest.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/QuantityConversionService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/PositionEngine.java
- Modify: backend/src/main/java/com/fxplatform/trading/repository/PositionRepository.java
- Modify: backend/src/main/java/com/fxplatform/trading/repository/AccountSymbolSettingRepository.java
- Test: backend/src/test/java/com/fxplatform/trading/service/TradingSettingsServiceTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/PositionEngineTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/QuantityConversionServiceTest.java

**Interfaces:**
- Position mode is account-level ONE_WAY/HEDGE.
- Margin mode, leverage and preferred quantity unit are account+symbol settings.
- BASE, QUOTE notional and CONTRACTS normalize to base quantity before risk/fill.

- [ ] **Step 1: Write RED unit-conversion tests**

Use backend contractSize, step size and mark price. Assert exact normalized base quantity for BASE, USDT notional and CONTRACTS and reject non-integral contract/step/min-notional violations.

- [ ] **Step 2: Write RED ONE_WAY matrix**

Cover BOTH slot increase, reduce, close and reversal. A reduce-only quantity greater than the current position rejects without clamping or reverse opening.

- [ ] **Step 3: Write RED HEDGE matrix**

Cover LONG and SHORT slots, open/close side combinations, simultaneous legs, shared symbol leverage/margin mode, and uniqueness of each slot.

- [ ] **Step 4: Write RED settings guards**

Position mode switch rejects when any Perp active order or open position exists. Margin mode switch rejects when that symbol has either. Default leverage=10 and valid range=1..min(symbol max,100).

- [ ] **Step 5: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=QuantityConversionServiceTest,TradingSettingsServiceTest,PositionEngineTest" test

- [ ] **Step 6: Implement settings and position slots**

Follow the global lock order, skipping wallets when unused. Persist BOTH for ONE_WAY and LONG/SHORT for HEDGE. Preserve old rows for read compatibility but write only canonical slots.

- [ ] **Step 7: Run GREEN**

    & $mvn -f backend/pom.xml "-Dtest=QuantityConversionServiceTest,TradingSettingsServiceTest,PositionEngineTest,PositionServiceTest,OrderPositionConcurrencyTest" test

- [ ] **Step 8: Commit**

    git add backend/src/main backend/src/test
    git commit -m "feat: add perpetual modes settings and quantity units"

### Task 9: Complete Perpetual orders, Cross/Isolated risk and margin

**Files:**
- Create: backend/src/main/java/com/fxplatform/risk/service/PerpetualRiskService.java
- Create: backend/src/main/java/com/fxplatform/trading/service/PerpetualOrderRiskService.java
- Create: backend/src/main/java/com/fxplatform/trading/service/PositionMarginService.java
- Create: backend/src/main/java/com/fxplatform/trading/dto/request/AdjustPositionMarginRequest.java
- Create: backend/src/main/java/com/fxplatform/trading/dto/response/AdjustPositionMarginResponse.java
- Modify: backend/src/main/java/com/fxplatform/risk/service/PerpMarginCalculator.java
- Modify: backend/src/main/java/com/fxplatform/risk/service/TradingAlgorithmEngine.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/TradingSettingsService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/OrderHoldCalculator.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/OrderService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionProcessor.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/PositionEngine.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/PositionService.java
- Modify: backend/src/main/java/com/fxplatform/trading/controller/TradingController.java
- Test: backend/src/test/java/com/fxplatform/trading/service/PerpetualRiskServiceTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/PerpetualOrderRiskServiceTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/PositionMarginServiceTest.java
- Test: backend/src/test/java/com/fxplatform/risk/service/PerpMarginCalculatorTest.java

**Interfaces:**
- Initial margin = abs(baseQty)*mark/leverage.
- Perp MARKET/LIMIT/STOP_MARKET all normalize quantity, reserve/release hold and execute through FullFillCoordinator.
- Cross shares account equity; Isolated consumes a slot-specific risk pool.
- UPL, ROI and liquidation estimate use mark, never last/mid.

- [ ] **Step 1: Write RED Perpetual order matrix**

Cover MARKET open immediately, marketable LIMIT immediately, non-marketable LIMIT pending, STOP_MARKET pending and mark-price trigger, GTC cancellation, ONE_WAY/HEDGE sides and reduce-only. LIMIT price checks use bid/ask; STOP trigger and all risk use mark. Trigger price is never used as fill price.

- [ ] **Step 2: Write RED open-hold and release tests**

For Cross and Isolated, assert:

    perpOpenHold = initialMargin(worstPrice, leverage)
                 + fullOrderNotional*worstFeeRate
                 + adverseCloseLoss

Pure reduce-only orders reserve no initial margin. Immediate fill consumes the hold once; cancel/reject releases it once; replay and concurrent cancel/fill never double release.

- [ ] **Step 3: Write RED exact formula tests**

Assert LONG/SHORT UPL, realized PnL, ROI, initial margin and maintenance margin using BigDecimal fixtures. Ensure contractSize is applied only during quantity normalization, not twice in risk.

- [ ] **Step 4: Write RED Cross/Isolated tests**

Cross uses account-level free/equity and aggregate maintenance. Isolated uses position margin plus its funding cashflow only; no automatic top-up from account.

- [ ] **Step 5: Write RED leverage-change tests**

Increasing leverage releases initial-margin difference. Decreasing leverage atomically reserves the difference or rejects with zero mutation. In HEDGE mode both legs are recalculated together.

- [ ] **Step 6: Write RED isolated-margin adjustment tests**

User may ADD or REDUCE. REDUCE that makes maintenance+estimated taker close fee unsafe rejects. Active auto top-up is not available.

- [ ] **Step 7: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=PerpetualOrderRiskServiceTest,PerpMarginCalculatorTest,PerpetualRiskServiceTest,PositionMarginServiceTest,TradingSettingsServiceTest,PendingOrderExecutionServiceTest" test

- [ ] **Step 8: Implement order risk, margin and release semantics**

Following the global lock order, reserve Cross holds in the Perp account and Isolated holds in the position risk pool. Use MMR=0.005 and liquidationFee=0.005 from backend symbol configuration. Liquidation fee is excluded from trigger threshold and charged only after liquidation fill.

- [ ] **Step 9: Run GREEN**

    & $mvn -f backend/pom.xml "-Dtest=PerpetualOrderRiskServiceTest,PerpMarginCalculatorTest,PerpetualRiskServiceTest,PositionMarginServiceTest,TradingSettingsServiceTest,PositionEngineTest,PositionServiceTest,PendingOrderExecutionServiceTest,OrderPositionConcurrencyTest" test

- [ ] **Step 10: Commit**

    git add backend/src/main backend/src/test
    git commit -m "feat: complete perpetual orders margin and risk"

### Task 10: Unify partial close and multi-level TP/SL

**Files:**
- Create: backend/src/main/java/com/fxplatform/trading/service/SystemCloseOrderService.java
- Create: backend/src/main/java/com/fxplatform/trading/service/ProtectionOrderService.java
- Create: backend/src/main/java/com/fxplatform/trading/dto/request/ClosePositionRequest.java
- Create: backend/src/main/java/com/fxplatform/trading/dto/request/CreateProtectionRequest.java
- Create: backend/src/main/java/com/fxplatform/trading/dto/request/UpdateProtectionRequest.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/PositionService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/PositionEngine.java
- Modify: backend/src/main/java/com/fxplatform/trading/controller/TradingController.java
- Test: backend/src/test/java/com/fxplatform/trading/service/SystemCloseOrderServiceTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/ProtectionOrderServiceTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/ProtectiveOrderExecutionServiceTest.java

**Interfaces:**
- Every manual/protection/system close creates a reduce-only Order, Trade, fee, realized PnL, ledger and event.
- At most 10 active protection orders per position across TP and SL.
- Each protection owns its quantity and MARKET or post-trigger LIMIT execution.

- [ ] **Step 1: Write RED partial-close tests**

Explicit quantity less than position closes only that amount. Equal quantity closes the slot. Greater quantity rejects. Verify realized PnL, fee and remaining entry price.

- [ ] **Step 2: Write RED protection validation tests**

Cover LONG/SHORT TP and SL directions, mark trigger source, per-type active quantity <= position quantity, total active order count <=10, and attached PENDING_ACTIVATION behavior.

- [ ] **Step 3: Write RED trigger tests**

MARKET protection fills through FullFillCoordinator. LIMIT protection becomes a normal reduce-only GTC order if not immediately marketable. A triggered/fill/cancel race produces at most one Trade.

- [ ] **Step 4: Write RED resize tests**

After position reduction, calculate excess independently for TP and SL. Preserve oldest first; shrink/cancel newest first; zero quantity becomes EXPIRED; never reverse the position.

- [ ] **Step 5: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=SystemCloseOrderServiceTest,ProtectionOrderServiceTest,ProtectiveOrderExecutionServiceTest,PositionServiceTest" test

- [ ] **Step 6: Replace every direct-close mutation**

PositionService.closePosition and closeSystemPosition delegate to SystemCloseOrderService. Remove position-level scalar TP/SL from active execution while retaining legacy reads.

- [ ] **Step 7: Run GREEN**

    & $mvn -f backend/pom.xml "-Dtest=SystemCloseOrderServiceTest,ProtectionOrderServiceTest,ProtectiveOrderExecutionServiceTest,PositionServiceTest,PositionEngineTest,OrderFillServiceTest" test

- [ ] **Step 8: Commit**

    git add backend/src/main backend/src/test
    git commit -m "feat: unify perpetual closes and split protection orders"

### Task 11: Add funding-source fallback, settlement and catch-up

**Files:**
- Create: backend/src/main/java/com/fxplatform/market/funding/FundingRateProvider.java
- Create: backend/src/main/java/com/fxplatform/market/funding/FundingRateSnapshot.java
- Create: backend/src/main/java/com/fxplatform/market/funding/BinanceFundingRateProvider.java
- Create: backend/src/main/java/com/fxplatform/market/funding/OkxFundingRateProvider.java
- Create: backend/src/main/java/com/fxplatform/market/funding/FixedFundingRateProvider.java
- Create: backend/src/main/java/com/fxplatform/trading/service/FundingRateIngestionService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/FundingService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/FundingSettlementScheduler.java
- Modify: backend/src/main/java/com/fxplatform/admin/controller/AdminMarketController.java
- Test: backend/src/test/java/com/fxplatform/trading/service/FundingRateIngestionServiceTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/FundingServiceTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/FundingSettlementSchedulerTest.java

**Interfaces:**
- Per symbol priority is configurable; default BINANCE → OKX → FIXED.
- FIXED defaults to 0.0001 every 480 minutes.
- Canonical rate is unique by symbol+fundingTime; settlement by positionId+fundingTime.

- [ ] **Step 1: Write RED source-fallback tests**

Cover healthy Binance, Binance stale/failed→OKX, both failed→FIXED, Admin priority reorder, actual provider persisted, and source change only for strictly later funding periods.

- [ ] **Step 2: Write RED settlement-sign tests**

    positive rate: LONG pays, SHORT receives
    negative rate: LONG receives, SHORT pays
    amount=abs(baseQty)*settlementMark*rate

Assert source, rate, mark, amount, balanceAfter and isolatedMarginAfter.

- [ ] **Step 3: Write RED Cross/Isolated tests**

Cross cashflow mutates Perp account once. Isolated cashflow mutates only the slot risk pool and fundingPnl once. Negative cashflow can make a slot/account eligible for liquidation.

- [ ] **Step 4: Write RED catch-up/idempotency tests**

Remove process-memory lastScanTime. On restart, derive missing periods from persistent rates/settlements, fill fixed 8h gaps, and make duplicate scheduler/provider callbacks zero-mutation.

- [ ] **Step 5: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=FundingRateIngestionServiceTest,FundingServiceTest,FundingSettlementSchedulerTest" test

- [ ] **Step 6: Implement providers and persistence workflow**

Use public endpoints only. Unit tests use fixture HTTP clients. All scheduler properties remain disabled by default.

- [ ] **Step 7: Run GREEN**

    & $mvn -f backend/pom.xml "-Dtest=FundingRateIngestionServiceTest,FundingServiceTest,FundingSettlementSchedulerTest,PerpetualRiskServiceTest,LedgerServiceTest" test

- [ ] **Step 8: Commit**

    git add backend/src/main backend/src/test
    git commit -m "feat: settle perpetual funding with provider fallback"

### Task 12: Complete liquidation, batch actions and Admin force cleanup

**Files:**
- Create: backend/src/main/java/com/fxplatform/trading/service/CancelAllOrderService.java
- Create: backend/src/main/java/com/fxplatform/trading/service/CloseAllPositionService.java
- Create: backend/src/main/java/com/fxplatform/admin/service/AdminAccountCleanupService.java
- Create: backend/src/main/java/com/fxplatform/trading/dto/response/BatchActionResponse.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/LiquidationService.java
- Modify: backend/src/main/java/com/fxplatform/trading/service/LiquidationScanScheduler.java
- Modify: backend/src/main/java/com/fxplatform/admin/service/AdminTradingCommandService.java
- Modify: backend/src/main/java/com/fxplatform/admin/service/AdminAccountQueryService.java
- Modify: backend/src/main/java/com/fxplatform/admin/controller/AdminTradingController.java
- Modify: backend/src/main/java/com/fxplatform/admin/controller/AdminAccountController.java
- Modify: backend/src/main/java/com/fxplatform/trading/controller/TradingController.java
- Test: backend/src/test/java/com/fxplatform/trading/service/LiquidationWorkflowTest.java
- Test: backend/src/test/java/com/fxplatform/trading/service/CloseAllPositionServiceTest.java
- Test: backend/src/test/java/com/fxplatform/admin/service/AdminAccountCleanupServiceTest.java
- Test: backend/src/test/java/com/fxplatform/admin/controller/AdminAccountControllerTest.java

**Interfaces:**
- Cancel-all is atomic per account scope and releases every hold once.
- Close-all is best effort per position and returns item-level outcomes.
- Admin force cleanup is cancel-all plus close-all; reset remains a separate call.
- Account-scoped Admin wallet, ledger, funding, force-cleanup and demo-reset routes remain under the existing /api/admin/accounts/{id}/... controller; AdminTradingController keeps only trading-resource commands.

- [ ] **Step 1: Replace conflicting liquidation RED tests**

Change old tests that included liquidation fee in the trigger. New threshold is maintenance margin + estimated close taker fee only; liquidation fee is charged after fill.

- [ ] **Step 2: Write RED Isolated liquidation workflow**

Cancel only risk-increasing orders for the slot, release holds, recompute with fresh mark, and if still unsafe submit one whole-slot LIQUIDATION MARKET reduce-only order.

- [ ] **Step 3: Write RED Cross liquidation workflow**

Mark account LIQUIDATION_PENDING, cancel all Perp active orders, recompute all Cross positions with fresh bundles, then close every Cross position. Any failed item keeps pending status for a disabled-by-default retry worker.

- [ ] **Step 4: Write RED shortfall tests**

Gap loss cannot make user balance negative. Record BANKRUPTCY_SHORTFALL for the deficit and charge liquidation fee only up to available balance; do not create insurance or ADL state.

- [ ] **Step 5: Write RED batch/Admin tests**

Cancel-all, user close-all, Admin force cleanup reason/requestId/audit record, item-level errors, replay idempotency, and reset still blocked until cleanup completes.

- [ ] **Step 6: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=LiquidationServiceTest,LiquidationWorkflowTest,CloseAllPositionServiceTest,AdminAccountCleanupServiceTest,AdminTradingCommandServiceTest" test

- [ ] **Step 7: Implement via SystemCloseOrderService**

No direct position/account mutation is permitted for a fill. Preserve scheduler default-off behavior.

- [ ] **Step 8: Run GREEN**

    & $mvn -f backend/pom.xml "-Dtest=LiquidationServiceTest,LiquidationWorkflowTest,CloseAllPositionServiceTest,AdminAccountCleanupServiceTest,AdminTradingCommandServiceTest" test

- [ ] **Step 9: Commit**

    git add backend/src/main backend/src/test
    git commit -m "feat: add liquidation batch and admin cleanup workflows"

### Task 13: Publish authenticated events and freeze the API contract

**Files:**
- Modify: backend/src/main/java/com/fxplatform/trading/websocket/TradingWsPublisher.java
- Modify: backend/src/main/java/com/fxplatform/market/websocket/MarketWsPublisher.java
- Modify: backend/src/main/java/com/fxplatform/common/websocket/MarketWebSocketConfig.java
- Modify: backend/src/main/java/com/fxplatform/market/controller/MarketController.java
- Modify: backend/src/main/java/com/fxplatform/trading/controller/TradingController.java
- Modify: backend/src/main/java/com/fxplatform/account/controller/AccountController.java
- Modify: backend/src/main/java/com/fxplatform/admin/controller/AdminAccountController.java
- Modify: backend/src/main/java/com/fxplatform/admin/service/AdminAccountQueryService.java
- Modify: packages/shared-types/src/apiTypes.ts
- Create: packages/shared-types/src/tradingTypes.ts
- Modify: packages/shared-types/src/errorCodes.ts
- Modify: packages/shared-types/src/index.ts
- Modify generated: packages/shared-types/src/generated/openapi.ts
- Create: scripts/trading-contract.test.mjs
- Modify: scripts/contract-governance.test.mjs

**Interfaces:**
- Account events publish only to authenticated /user/queue/trading-events.
- Public market topics include sourceMode/providerCode/asOf/expiresAt/stale.
- Events cover order, trade, wallet, position, funding, transfer, liquidation and MARKET_SOURCE_CHANGED.

- [ ] **Step 1: Write RED authorization and event tests**

Assert user A cannot subscribe to user B state. Assert account mutations publish identifiers/version, not a mutable unauthenticated account topic.

- [ ] **Step 2: Write RED contract-governance tests**

Require every endpoint in spec section 20, including /api/admin/accounts/{id}/wallet-balances, /asset-ledger, /funding-settlements, /force-cleanup and /demo-reset, every DTO/enum, and literal BTCUSDT-PERP. Assert no STOP-only or stripped BTCUSDTPERP contract is generated.

- [ ] **Step 3: Run RED**

    & $mvn -f backend/pom.xml "-Dtest=WebSocketSecurityTest,TradingWsPublisherTest,MarketWsPublisherTest" test
    & $node --test scripts/trading-contract.test.mjs

- [ ] **Step 4: Implement publishers and controllers**

Account events use authenticated principal routing. Source changes are explicit events. REST queries expose orders/trades/positions/funding/transfers with stable paging/filter semantics.

- [ ] **Step 5: Export and generate OpenAPI**

    & $node $npmCli run contract:export
    & $node $npmCli run contract:generate
    & $node $npmCli run contract:check

- [ ] **Step 6: Run GREEN**

    & $mvn -f backend/pom.xml "-Dtest=WebSocketSecurityTest,TradingWsPublisherTest,MarketWsPublisherTest,TradingControllerTest,AccountControllerTest,AdminAccountControllerTest" test
    & $node --test scripts/trading-contract.test.mjs scripts/contract-governance.test.mjs

- [ ] **Step 7: Commit**

    git add backend/src packages/shared-types scripts
    git commit -m "feat: publish secure trading events and contracts"

### Task 14: Build canonical Spot and Perpetual Web routes and order controls

**Files:**
- Create: apps/web/src/app/tradingRoutes.ts
- Create: apps/web/src/app/LegacyTradingRedirect.tsx
- Modify: apps/web/src/app/App.tsx
- Modify: apps/web/src/app/navigation.ts
- Modify: apps/web/src/app/components/TradingNavMenu.tsx
- Modify: apps/web/src/app/hooks/useLastTradingSymbol.ts
- Modify: apps/web/src/pages/trading/TradingPage.tsx
- Modify: apps/web/src/pages/trading/tradingPageMarketSelection.ts
- Create: apps/web/src/pages/trading/components/PerpetualReferenceStrip.tsx
- Create: apps/web/src/features/trading/components/MarketSourceBadge.tsx
- Create: apps/web/src/features/trading/components/SimplifiedLiquidationDisclaimer.tsx
- Create: apps/web/src/features/trading-settings/useTradingSettings.ts
- Create: apps/web/src/features/trading/components/TradingSettingsBar.tsx
- Create: apps/web/src/features/trading/components/MultiLevelProtectionEditor.tsx
- Create: apps/web/src/features/trading/components/PositionActionDialog.tsx
- Modify: apps/web/src/features/trading/hooks/useTradeForm.ts
- Modify: apps/web/src/features/trading/services/orderAdapter.ts
- Modify: apps/web/src/features/trading/components/TradePanel.tsx
- Modify: apps/web/src/services/tradingApi.ts
- Test: apps/web/src/app/tradingRoutes.test.ts
- Test: apps/web/src/pages/trading/tradingProductVisibility.test.ts
- Test: apps/web/src/features/trading/components/SimplifiedLiquidationDisclaimer.test.ts
- Test: apps/web/src/features/trading/services/orderAdapter.test.ts
- Test: apps/web/src/features/trading-settings/useTradingSettings.test.ts

**Interfaces:**
- /trade/spot/:symbol? and /trade/perpetual/:symbol? are canonical; /trading redirects.
- Default symbols are BTCUSDT and BTCUSDT-PERP.
- Forms send quantity plus quantityUnit without provider-symbol rewriting.

- [ ] **Step 1: Write RED route tests**

Cover Spot/Perp defaults, explicit symbols, legacy redirect, invalid product, last-symbol storage per product, and exact preservation of BTCUSDT-PERP. Assert the UI-visible tradable set is exactly the five Spot plus five Perp symbols and FX, inverse and options routes/actions are hidden or disabled.

- [ ] **Step 2: Write RED payload tests**

Cover Spot MARKET BUY QUOTE, Spot sell/limit/OCO BASE, Perp BASE/QUOTE/CONTRACTS, HEDGE position side, reduceOnly, STOP_MARKET and attached multi-level protections.

- [ ] **Step 3: Write RED settings/control tests**

Cover ONE_WAY/HEDGE, CROSS/ISOLATED, leverage 1–100, backend max, blocked switches, partial close input, manual isolated margin and max-ten protections. The displayed liquidation-price estimate must be accompanied by a visible statement that it is a simplified Demo formula, not a real-exchange risk model and not suitable for real funds.

- [ ] **Step 4: Run RED**

    & $node $npmCli --workspace apps/web test -- tradingRoutes.test.ts tradingProductVisibility.test.ts orderAdapter.test.ts useTradingSettings.test.ts SimplifiedLiquidationDisclaimer.test.ts

- [ ] **Step 5: Implement routes and controls**

Only render MARKET/LIMIT/STOP_MARKET/GTC/OCO and required controls. Remove Trailing Stop, Post-only, IOC, FOK, Iceberg and TWAP from normal entry points.

- [ ] **Step 6: Run GREEN**

    & $node $npmCli --workspace apps/web test -- tradingRoutes.test.ts tradingProductVisibility.test.ts orderAdapter.test.ts useTradingSettings.test.ts SimplifiedLiquidationDisclaimer.test.ts
    & $node $npmCli --prefix apps/web run build

- [ ] **Step 7: Commit**

    git add apps/web
    git commit -m "feat: add spot and perpetual web trading controls"

### Task 15: Make Web market/account state real-time and mobile-equivalent

**Files:**
- Modify: apps/web/src/features/market/tradingModels.ts
- Modify: apps/web/src/features/market/tradingMarketAdapters.ts
- Modify: apps/web/src/features/market/tradingMarketApi.ts
- Modify: apps/web/src/features/market/quoteMarketDataAdapter.ts
- Modify: apps/web/src/features/market/quoteMarketDataSnapshot.ts
- Modify: apps/web/src/pages/trading/useTradingQuotes.ts
- Modify: apps/web/src/services/marketStream.ts
- Create: apps/web/src/features/trading-session/accountRefreshCoordinator.ts
- Modify: apps/web/src/features/trading-session/useTradingSession.ts
- Modify: apps/web/src/pages/trading/components/BottomAccountPanel.tsx
- Create: apps/web/src/pages/trading/components/BottomAccountTradesGrid.tsx
- Create: apps/web/src/pages/trading/components/BottomAccountFundingGrid.tsx
- Create: apps/web/src/pages/trading/components/BottomAccountTransfersGrid.tsx
- Create: apps/web/src/pages/wallet/TransferDialog.tsx
- Create: apps/web/src/pages/wallet/DemoResetDialog.tsx
- Modify: apps/web/src/pages/wallet/WalletPage.tsx
- Modify: apps/web/src/pages/account/AccountPages.tsx
- Modify: apps/web/src/pages/trading/components/TradingMobileView.tsx
- Modify: apps/web/src/pages/trading/mobile/MobileTradingTerminal.tsx
- Test: apps/web/src/features/trading-session/accountRefreshCoordinator.test.ts
- Test: apps/web/src/pages/trading/mobile/MobileTradingTerminal.test.ts

**Interfaces:**
- Event coalescing=200ms, single-flight, poll fallback=15s.
- Empty API arrays render empty states; they never inject fake balances/orders/positions.
- Mobile renders the same controllers, forms, settings and data as desktop.

- [ ] **Step 1: Write RED market-trust tests**

Incomplete/stale bundles disable trading. Quote/depth/trade/reference show one source and preserve source metadata. Provider failure may not create a front-end tradable price.

- [ ] **Step 2: Write RED refresh-coordinator tests**

Cover 200ms coalesce, single-flight, 15s fallback, reconnect refresh and unmount cleanup. Initial account/order/trade/position/funding/transfer requests run in parallel.

- [ ] **Step 3: Write RED no-mock tests**

Empty arrays produce empty state. Remove ready-path fallback from mockTradingMarkets, useMockBalances, quote snapshot synthesis, fixed depth and static mobile values.

- [ ] **Step 4: Write RED transfer/reset/mobile tests**

Cover direction/available limits/requestId, reset blocked error, absence of USDT_PERP mirror, and desktop/mobile use of the same TradePanel/settings/reference state.

- [ ] **Step 5: Run RED**

    & $node $npmCli --workspace apps/web test -- accountRefreshCoordinator.test.ts MobileTradingTerminal.test.ts

- [ ] **Step 6: Implement session, wallet and mobile parity**

Subscribe to /user/queue/trading-events. Keep explicit loading/error/offline/source-change states. Funding records and deposit/withdrawal records remain semantically separate.

- [ ] **Step 7: Run GREEN**

    & $node $npmCli --workspace apps/web test
    & $node $npmCli --prefix apps/web run build

- [ ] **Step 8: Commit**

    git add apps/web
    git commit -m "feat: connect realtime account state and mobile trading"

### Task 16: Add Admin account operations, funding configuration and mobile layout

**Files:**
- Create: apps/admin/src/services/adminTradingApi.ts
- Create: apps/admin/src/pages/AccountDetailPage.tsx
- Create: apps/admin/src/pages/FundingSettlementsPage.tsx
- Create: apps/admin/src/pages/FundingConfigPage.tsx
- Create: apps/admin/src/components/HighRiskActionDialog.tsx
- Modify: apps/admin/src/app/AdminApp.tsx
- Modify: apps/admin/src/app/adminMenu.ts
- Modify: apps/admin/src/app/AdminLayout.tsx
- Modify: apps/admin/src/pages/AccountsPage.tsx
- Modify: apps/admin/src/pages/MarketStatusPage.tsx
- Modify: apps/admin/src/types.ts
- Modify: apps/admin/src/styles.css
- Test: apps/admin/src/services/adminTradingApi.test.mjs
- Test: apps/admin/src/pages/tradingOperations.test.mjs
- Test: apps/admin/src/components/HighRiskActionDialog.test.mjs

**Interfaces:**
- Admin displays Spot/Perp balances, orders, trades, positions, funding, transfers and ledger for an account.
- Funding config edits ordered sources, fixed rate, interval and stale threshold per symbol, and displays the currently selected actual source, fallback state and next funding time.
- Force cleanup and reset are distinct high-risk actions with reason, requestId and auditId.

- [ ] **Step 1: Write RED Admin API tests**

Assert authenticated methods and exact routes for account detail, funding settlements/config, force cleanup and reset.

- [ ] **Step 2: Write RED menu/page tests**

Require explicit menu routes for accounts, orders, positions, trades, funding settlements, funding config, market status, risk and audit. Funding status assertions include actual selected source (BINANCE/OKX/FIXED), fallback reason, last asOf and nextFundingTime for every Perp symbol.

- [ ] **Step 3: Write RED high-risk dialog tests**

Require second confirmation, mandatory reason, pending double-click prevention and visible requestId/auditId. Reset is disabled while cleanup blockers exist.

- [ ] **Step 4: Write RED 390px layout test**

Assert menus, tables and dialogs remain reachable without hiding high-risk context; horizontal table scroll is acceptable.

- [ ] **Step 5: Run RED**

    & $node $npmCli --workspace apps/admin test

- [ ] **Step 6: Implement Admin pages**

Load account detail sections in parallel. Aggregate transfer history from paired ledger references. Never bypass /api/admin/** backend authorization.

- [ ] **Step 7: Run GREEN**

    & $node $npmCli --workspace apps/admin test
    & $node $npmCli --prefix apps/admin run build

- [ ] **Step 8: Commit**

    git add apps/admin
    git commit -m "feat: add admin demo trading operations"

### Task 17: Add PostgreSQL concurrency and end-to-end backend regression

**Files:**
- Create: backend/src/test/java/com/fxplatform/database/DemoTradingConcurrencyIT.java
- Create: backend/src/test/java/com/fxplatform/database/PerpetualPositionConcurrencyIT.java
- Create: backend/src/test/java/com/fxplatform/database/ProtectionOrderConcurrencyIT.java
- Create: backend/src/test/java/com/fxplatform/database/FundingLiquidationConcurrencyIT.java
- Modify: backend/src/test/java/com/fxplatform/database/PostgresDatabaseIT.java
- Modify: backend/src/test/java/com/fxplatform/trading/TradingWorkflowRegressionProtectionTest.java

**Interfaces:**
- Every concurrency fixture asserts the one global lock order, including cross-flow transfer/fill/cancel/funding/liquidation races.
- Database uniqueness is the final guard for one Trade/order, one settlement/period and one position slot.

- [ ] **Step 1: Write concurrent Spot/OCO/transfer cases**

Race fill versus cancel, OCO leg versus leg, and two transfers. Assert no negative balance, duplicate Trade, double hold release or conservation drift.

- [ ] **Step 2: Write concurrent Perp cases**

Race open/close/protection/funding/liquidation/settings. Assert unique slots, no reverse from reduce-only, exactly-once funding and consistent account/position versions.

- [ ] **Step 3: Run RED or expose race**

    & $mvn -f backend/pom.xml "-Dtest=DemoTradingConcurrencyIT,PerpetualPositionConcurrencyIT,ProtectionOrderConcurrencyIT,FundingLiquidationConcurrencyIT" test

- [ ] **Step 4: Fix only demonstrated locking/idempotency defects**

Use the global lock order and database constraints. Do not add a queue, distributed transaction or event-sourcing layer.

- [ ] **Step 5: Run GREEN and complete backend suite**

    & $mvn -f backend/pom.xml "-Dtest=DemoTradingConcurrencyIT,PerpetualPositionConcurrencyIT,ProtectionOrderConcurrencyIT,FundingLiquidationConcurrencyIT" test
    & $mvn -f backend/pom.xml test

- [ ] **Step 6: Commit**

    git add backend
    git commit -m "test: verify demo trading concurrency and invariants"

### Task 18: Add real browser smoke coverage for public, failover and offline modes

**Files:**
- Create: scripts/smoke-usdt-demo-browser.mjs
- Create: scripts/smoke-usdt-demo-browser.test.mjs
- Modify: package.json
- Modify or retire: scripts/smoke-btcusdt-perp-50x.mjs
- Reuse: scripts/smoke-visual-qa.mjs only for layout helpers, not trading API mocks.

**Interfaces:**
- Runs against real backend/PostgreSQL/Redis; no intercept/mock for trading APIs.
- Viewports: Web/Admin 1440x900 and 390x844.
- Modes: BINANCE public, Binance failure→OKX, both failure→LOCAL_SIMULATED, and recovery with visible source jump.

- [ ] **Step 1: Write RED script contract tests**

Assert the smoke script starts real services, never intercepts /api/trading or /api/account, checks source metadata, and contains all four viewports/modes.

- [ ] **Step 2: Implement deterministic fixture controls**

Use backend test/admin provider availability controls, not browser mocks. Ensure local mode remains tradable and source recovery may jump immediately while emitting a visible notification.

- [ ] **Step 3: Implement the P0 browser journey**

Journey:

    register/login → verify 50k Spot + 50k Perp
    verify exactly 5 Spot + 5 Perp symbols; FX/inverse/options cannot trade
    Spot MARKET BUY(QUOTE) + SELL(BASE)
    Spot LIMIT immediate + LIMIT pending/cancel + STOP_MARKET(last trigger)
    Spot BUY OCO + SELL OCO; fill one leg and verify peer cancelled/hold released
    transfer Spot→Perp→Spot and verify both ledgers/conservation
    Perp ONE_WAY+CROSS MARKET(BASE), LIMIT(USDT notional), STOP_MARKET(CONTRACTS)
    cancel pending Perp order and verify margin hold release
    clear active state → switch HEDGE → open simultaneous LONG and SHORT
    switch clean symbol CROSS↔ISOLATED; leverage 1↔100 within backend max
    add/reduce Isolated margin; unsafe reduction visibly rejects
    partial close by typed quantity → full close; reduce-only over-close rejects
    create 10 mixed TP/SL levels with MARKET and LIMIT execution
    trigger one protection, verify LIMIT may remain GTC, then partial close and verify newest-first auto-resize
    settle positive and negative funding using selected/fallback source and verify actual balance changes
    cancel-all → close-all with per-position outcomes
    deterministic Isolated liquidation → Order/Trade/Fee/PnL/Ledger/notification
    deterministic Cross liquidation closes all Cross positions; shortfall keeps balance at zero
    reset reject while active → Admin force cleanup → reset succeeds

Repeat a minimal executable Spot MARKET/LIMIT/cancel and Perp MARKET/STOP/close loop under BINANCE, Binance→OKX fallback and LOCAL_SIMULATED. Run all user and Admin critical paths at 1440x900 and 390x844; assertions must inspect resulting REST/database state, not only visible text.

- [ ] **Step 4: Run script unit test**

    & $node --test scripts/smoke-usdt-demo-browser.test.mjs

- [ ] **Step 5: Run real public/failover/offline smoke**

    & $node $npmCli run smoke:usdt-demo-browser

Capture screenshots and logs for every viewport/mode. A public-network failure is acceptable only if the tested fallback succeeds and the UI displays the actual source.

- [ ] **Step 6: Commit**

    git add package.json scripts
    git commit -m "test: add real USDT demo browser smoke"

### Task 19: Final architecture, security and acceptance verification

**Files:**
- Create: docs/superpowers/reports/2026-07-11-usdt-demo-p0-verification.md
- Modify if needed: docs/superpowers/specs/2026-07-10-usdt-spot-perpetual-demo-scheme-a-design-and-implementation.md

**Interfaces:**
- Produces a command-by-command verification report and traceability from every spec DoD item to test evidence.

- [ ] **Step 1: Run static scope checks**

    rg -n "PARTIALLY_FILLED|USDT_PERP|BTCUSDTPERP|/topic/trading/accounts|mockTradingMarkets|useMockBalances" backend apps packages scripts
    rg -n "broker|FIX|insurance|ADL|Kafka|RabbitMQ" backend/src/main

Classify legacy read compatibility separately; no target ready path may write/use forbidden behavior.

- [ ] **Step 2: Run all backend verification**

    & $mvn -f backend/pom.xml test

Record totals, failures and duration. Do not claim success from a truncated log.

- [ ] **Step 3: Run all frontend/contract verification**

    & $node $npmCli run contract:check
    & $node $npmCli --workspace apps/web test
    & $node $npmCli --workspace apps/admin test
    & $node $npmCli --prefix apps/web run build
    & $node $npmCli --prefix apps/admin run build
    & $node $npmCli run verify:architecture

- [ ] **Step 4: Run database and real-browser acceptance**

    docker compose -f infra/docker-compose.yml ps
    & $node $npmCli run smoke:usdt-demo-browser

Verify all four viewports and public/failover/local modes.

- [ ] **Step 5: Perform security and invariant audit**

Confirm /api/admin/** role checks, no private exchange keys/calls, scheduler default-off, DEMO/LIVE isolation, one-account constraint, full-fill-only, ledger conservation and authenticated account events.

- [ ] **Step 6: Request independent code review**

Use superpowers:requesting-code-review. Resolve P0/P1 correctness findings with RED tests before final verification.

- [ ] **Step 7: Write verification report**

For each DoD item in spec section 27, link exact test, command, result and any deliberately excluded legacy/P2 behavior.

- [ ] **Step 8: Run verification-before-completion**

Use superpowers:verification-before-completion and re-run the exact final commands after the last code change.

- [ ] **Step 9: Commit**

    git add docs
    git commit -m "docs: record USDT demo P0 verification"

---

## Execution order and checkpoints

1. Tasks 0–5 establish migration, domain, guard, market truth and a single full-fill path.
2. Tasks 6–7 complete Spot, DEMO account and transfer.
3. Tasks 8–12 complete Perp, funding, liquidation and operational controls.
4. Task 13 freezes authenticated events and OpenAPI before UI integration.
5. Tasks 14–16 implement Web/Admin with shared contracts.
6. Tasks 17–19 prove concurrency, real browser behavior and final invariants.

After each numbered task, stop only long enough to inspect the diff, run the named tests and commit the bounded change. If a step uncovers a conflict with the authoritative specification, the specification wins; choose the smallest Binance/OKX-compatible behavior and record it in the verification report without asking the user.
