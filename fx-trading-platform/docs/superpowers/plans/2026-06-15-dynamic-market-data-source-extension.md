# Dynamic Market Data Source Extension Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将外汇 Massive、加密 Binance、未来 OKX 统一改造成后台可配置的数据源体系，并支持后台管理每个品种的展示、关闭、图标、排序、行情绑定和 fallback。

**Architecture:** 外部数据源只负责提供原始行情和原始品种；平台品种负责用户端展示和交易配置；品种与数据源通过 binding 关联。后端用 `ProviderRegistry + ProviderResolver + MarketDataRouter` 替代当前硬编码的 `CompositeMarketDataProvider` 路由，后台通过独立页面管理 provider、provider instruments 和 platform symbols。

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis-Plus, PostgreSQL/Flyway, React 19, Vite, lucide-react, Node test runner.

---

## Execution Context

执行目录：

```powershell
cd C:\Users\User\Desktop\workspace\tradingView-KlineChart\fx-trading-platform
```

执行约束：

- 不要删除用户已有改动。
- 不要把 demo/test data 作为真实 provider 的静默 fallback，除非后台明确配置了 fallback。
- 不要把 provider symbol 暴露给用户端业务流程，用户端继续使用平台标准 symbol。
- 不要把 provider config 全塞进 `config.system_settings`，需要一等表结构、审计和查询能力。
- 不要自动提交；只有用户明确要求提交时才执行 `git add` 和 `git commit`。

---

## Current Baseline

当前关键文件：

- `backend/src/main/resources/db/migration/V4__market_tables.sql`
  - `market.symbols` 已有 `provider`, `provider_symbol`, `enabled`。
  - 缺少 `icon_url`, `display_enabled`, `quote_enabled`, `chart_enabled`, `order_book_enabled`, `tradable`, `display_order`。
- `backend/src/main/java/com/fxplatform/market/adapter/CompositeMarketDataProvider.java`
  - 当前按 `providerSymbol/symbol` 是否以 `USDT` 结尾来选择 Binance，否则走 Massive。
  - 这个规则必须移除。
- `backend/src/main/java/com/fxplatform/market/service/SymbolService.java`
  - 当前会混合数据库启用品种和 provider 拉回来的外汇列表。
  - 改造后用户端列表只返回平台发布的 symbol。
- `backend/src/main/java/com/fxplatform/chart/service/ChartService.java`
  - 当前依赖 `provider_symbol` 取 candles，需要改成按 capability 解析 provider binding。
- `backend/src/main/java/com/fxplatform/market/controller/MarketController.java`
  - `order-book` 和 `trades` 仍来自 `MarketTestDataService`，需要改为 provider-backed service。
- `apps/admin/src/pages/SymbolsPage.tsx`
  - 当前只是简单 symbol 表格，需要升级为可编辑的品种管理页面。
- `apps/admin/src/pages/MarketStatusPage.tsx`
  - 当前偏 Massive 状态，需要升级为 provider health dashboard。

---

## Data Model

### Provider

Create migration:

- `backend/src/main/resources/db/migration/V28__dynamic_market_data_sources.sql`

Schema:

```sql
CREATE TABLE market.data_providers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(32) NOT NULL UNIQUE,
  name VARCHAR(64) NOT NULL,
  provider_type VARCHAR(32) NOT NULL,
  asset_classes TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  rest_base_url TEXT,
  ws_url TEXT,
  enabled BOOLEAN NOT NULL DEFAULT true,
  priority INTEGER NOT NULL DEFAULT 100,
  timeout_ms INTEGER NOT NULL DEFAULT 5000,
  rate_limit_per_minute INTEGER NOT NULL DEFAULT 1200,
  health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  last_health_check_at TIMESTAMPTZ,
  config_json JSONB NOT NULL DEFAULT '{}'::JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE market.data_provider_capabilities (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider_id UUID NOT NULL REFERENCES market.data_providers(id) ON DELETE CASCADE,
  capability VARCHAR(48) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT true,
  config_json JSONB NOT NULL DEFAULT '{}'::JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(provider_id, capability)
);

CREATE TABLE market.provider_instruments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider_id UUID NOT NULL REFERENCES market.data_providers(id) ON DELETE CASCADE,
  provider_symbol VARCHAR(96) NOT NULL,
  asset_class VARCHAR(32) NOT NULL,
  base_asset VARCHAR(32),
  quote_asset VARCHAR(32),
  display_name VARCHAR(96),
  listed BOOLEAN NOT NULL DEFAULT true,
  raw_json JSONB NOT NULL DEFAULT '{}'::JSONB,
  last_synced_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(provider_id, provider_symbol)
);

CREATE TABLE market.symbol_provider_bindings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  symbol_id UUID NOT NULL REFERENCES market.symbols(id) ON DELETE CASCADE,
  provider_id UUID NOT NULL REFERENCES market.data_providers(id),
  provider_instrument_id UUID REFERENCES market.provider_instruments(id),
  provider_symbol VARCHAR(96) NOT NULL,
  priority INTEGER NOT NULL DEFAULT 100,
  enabled BOOLEAN NOT NULL DEFAULT true,
  config_json JSONB NOT NULL DEFAULT '{}'::JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(symbol_id, provider_id, provider_symbol)
);

ALTER TABLE market.symbols
  ADD COLUMN icon_url TEXT,
  ADD COLUMN icon_asset_id UUID,
  ADD COLUMN display_enabled BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN quote_enabled BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN chart_enabled BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN order_book_enabled BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN tradable BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN featured BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN display_group VARCHAR(64),
  ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_data_providers_enabled_priority
  ON market.data_providers(enabled, priority ASC);

CREATE INDEX idx_provider_instruments_provider_asset
  ON market.provider_instruments(provider_id, asset_class, listed);

CREATE INDEX idx_symbol_provider_bindings_symbol_priority
  ON market.symbol_provider_bindings(symbol_id, enabled, priority ASC);

CREATE INDEX idx_symbols_display
  ON market.symbols(display_enabled, asset_class, display_order ASC, symbol ASC);
```

Seed rules in the same migration:

- Insert `massive` provider with asset class `FOREX`.
- Insert `binance` provider with asset class `CRYPTO`.
- Insert `demo` provider with asset classes `FOREX`, `CRYPTO`, `METAL`.
- Insert capabilities for `QUOTE`, `SNAPSHOT`, `SYMBOLS`, `CANDLES`; add `ORDER_BOOK` and `TRADES` for `binance`.
- Backfill one binding for each existing `market.symbols` row using existing `provider` and `provider_symbol`.

### Display Semantics

Use these flags consistently:

- `enabled`: platform symbol master switch.
- `display_enabled`: symbol appears in user-facing lists.
- `quote_enabled`: backend fetches quote for symbol.
- `chart_enabled`: K-line endpoint can resolve provider candles.
- `order_book_enabled`: order book endpoint can resolve provider depth.
- `tradable`: order entry can use this symbol.

Do not use one boolean to represent all six states.

---

## File Structure

Create backend domain files:

- `backend/src/main/java/com/fxplatform/market/entity/DataProviderEntity.java`
- `backend/src/main/java/com/fxplatform/market/entity/DataProviderCapabilityEntity.java`
- `backend/src/main/java/com/fxplatform/market/entity/ProviderInstrumentEntity.java`
- `backend/src/main/java/com/fxplatform/market/entity/SymbolProviderBindingEntity.java`
- `backend/src/main/java/com/fxplatform/market/repository/DataProviderRepository.java`
- `backend/src/main/java/com/fxplatform/market/repository/DataProviderCapabilityRepository.java`
- `backend/src/main/java/com/fxplatform/market/repository/ProviderInstrumentRepository.java`
- `backend/src/main/java/com/fxplatform/market/repository/SymbolProviderBindingRepository.java`

Create backend provider routing files:

- `backend/src/main/java/com/fxplatform/market/provider/MarketDataCapability.java`
- `backend/src/main/java/com/fxplatform/market/provider/MarketDataProviderAdapter.java`
- `backend/src/main/java/com/fxplatform/market/provider/ProviderResolution.java`
- `backend/src/main/java/com/fxplatform/market/provider/ProviderRegistry.java`
- `backend/src/main/java/com/fxplatform/market/provider/ProviderResolver.java`
- `backend/src/main/java/com/fxplatform/market/provider/MarketDataRouter.java`
- `backend/src/main/java/com/fxplatform/market/provider/ProviderInstrumentSyncService.java`

Move or adapt existing providers:

- Modify `backend/src/main/java/com/fxplatform/market/adapter/massive/MassiveRestClient.java` to expose adapter code `massive`.
- Modify `backend/src/main/java/com/fxplatform/market/adapter/binance/BinanceSpotMarketDataProvider.java` to expose adapter code `binance`.
- Create `backend/src/main/java/com/fxplatform/market/adapter/demo/DemoMarketDataProvider.java` if demo fallback must be represented as a provider.
- Create `backend/src/main/java/com/fxplatform/market/adapter/okx/OkxSpotMarketDataProvider.java` in Task 9 after the resolver supports new providers.

Create admin backend files:

- `backend/src/main/java/com/fxplatform/admin/controller/AdminMarketDataProviderController.java`
- `backend/src/main/java/com/fxplatform/admin/dto/request/AdminDataProviderRequest.java`
- `backend/src/main/java/com/fxplatform/admin/dto/request/AdminProviderCapabilityRequest.java`
- `backend/src/main/java/com/fxplatform/admin/dto/request/AdminSymbolDisplayRequest.java`
- `backend/src/main/java/com/fxplatform/admin/dto/request/AdminSymbolProviderBindingRequest.java`
- `backend/src/main/java/com/fxplatform/admin/dto/response/AdminDataProviderResponse.java`
- `backend/src/main/java/com/fxplatform/admin/dto/response/AdminProviderInstrumentResponse.java`
- `backend/src/main/java/com/fxplatform/admin/dto/response/AdminSymbolProviderBindingResponse.java`
- `backend/src/main/java/com/fxplatform/admin/service/AdminMarketDataProviderService.java`

Modify existing admin files:

- `backend/src/main/java/com/fxplatform/admin/dto/request/AdminSymbolRequest.java`
- `backend/src/main/java/com/fxplatform/admin/dto/response/AdminSymbolResponse.java`
- `backend/src/main/java/com/fxplatform/admin/service/AdminMarketCommandService.java`
- `backend/src/main/java/com/fxplatform/admin/service/AdminMarketQueryService.java`
- `backend/src/main/java/com/fxplatform/admin/controller/AdminMarketController.java`

Modify user-facing market files:

- `backend/src/main/java/com/fxplatform/market/dto/SymbolResponse.java`
- `backend/src/main/java/com/fxplatform/market/service/SymbolService.java`
- `backend/src/main/java/com/fxplatform/market/service/QuoteService.java`
- `backend/src/main/java/com/fxplatform/chart/service/ChartService.java`
- `backend/src/main/java/com/fxplatform/market/controller/MarketController.java`

Create or modify admin frontend files:

- `apps/admin/src/pages/DataProvidersPage.tsx`
- `apps/admin/src/pages/ProviderInstrumentsPage.tsx`
- `apps/admin/src/pages/SymbolDataBindingsPage.tsx`
- `apps/admin/src/pages/SymbolsPage.tsx`
- `apps/admin/src/pages/MarketStatusPage.tsx`
- `apps/admin/src/services/adminApi.ts`
- `apps/admin/src/types.ts`
- `apps/admin/src/app/AdminApp.tsx`
- `apps/admin/src/app/adminMenu.ts`

---

## API Contract

Admin endpoints:

- `GET /api/admin/market/data-providers`
- `POST /api/admin/market/data-providers`
- `PUT /api/admin/market/data-providers/{providerId}`
- `POST /api/admin/market/data-providers/{providerId}/test`
- `POST /api/admin/market/data-providers/{providerId}/sync-instruments`
- `GET /api/admin/market/data-providers/{providerId}/instruments`
- `GET /api/admin/market/symbols/{symbolId}/bindings`
- `POST /api/admin/market/symbols/{symbolId}/bindings`
- `PUT /api/admin/market/symbols/{symbolId}/bindings/{bindingId}`
- `PUT /api/admin/market/symbols/{symbolId}/display`

User-facing behavior:

- `GET /api/market/symbols`
  - Return only rows where `enabled=true` and `display_enabled=true`.
  - Include `iconUrl`, `displayOrder`, `displayGroup`, `featured`, `tradable`, `chartEnabled`, `orderBookEnabled`.
- `GET /api/market/quotes/{symbol}`
  - Resolve provider binding with capability `QUOTE`.
  - Reject when `enabled=false` or `quote_enabled=false`.
- `GET /api/chart/candles`
  - Resolve provider binding with capability `CANDLES`.
  - Reject when `chart_enabled=false`.
- `GET /api/market/order-book/{symbol}`
  - Resolve provider binding with capability `ORDER_BOOK`.
  - Reject when `order_book_enabled=false`.
- `GET /api/market/trades/{symbol}`
  - Resolve provider binding with capability `TRADES`.

Error codes:

- `SYMBOL_NOT_ENABLED`
- `SYMBOL_NOT_DISPLAYED`
- `SYMBOL_QUOTE_DISABLED`
- `SYMBOL_CHART_DISABLED`
- `SYMBOL_ORDER_BOOK_DISABLED`
- `MARKET_PROVIDER_NOT_CONFIGURED`
- `MARKET_PROVIDER_CAPABILITY_DISABLED`
- `MARKET_PROVIDER_BINDING_NOT_FOUND`
- `MARKET_PROVIDER_UNAVAILABLE`

---

## Task 1: Database Migration

**Files:**

- Create: `backend/src/main/resources/db/migration/V28__dynamic_market_data_sources.sql`

- [ ] **Step 1: Write migration with tables, indexes, and backfill**

Use the SQL from the `Data Model` section. Add seed statements after the table definitions:

```sql
INSERT INTO market.data_providers (code, name, provider_type, asset_classes, rest_base_url, enabled, priority, health_status)
VALUES
  ('massive', 'Massive Forex', 'REST', ARRAY['FOREX'], 'https://api.massive.com', true, 10, 'UNKNOWN'),
  ('binance', 'Binance Spot', 'REST_WS', ARRAY['CRYPTO'], 'https://api.binance.com', true, 20, 'UNKNOWN'),
  ('demo', 'Demo Market Data', 'LOCAL', ARRAY['FOREX','CRYPTO','METAL'], null, false, 900, 'UNKNOWN')
ON CONFLICT (code) DO NOTHING;

INSERT INTO market.data_provider_capabilities (provider_id, capability, enabled)
SELECT id, capability, true
FROM market.data_providers
CROSS JOIN (
  VALUES ('SYMBOLS'), ('QUOTE'), ('SNAPSHOT'), ('CANDLES')
) AS caps(capability)
WHERE code IN ('massive', 'binance')
ON CONFLICT (provider_id, capability) DO NOTHING;

INSERT INTO market.data_provider_capabilities (provider_id, capability, enabled)
SELECT id, capability, true
FROM market.data_providers
CROSS JOIN (
  VALUES ('ORDER_BOOK'), ('TRADES')
) AS caps(capability)
WHERE code = 'binance'
ON CONFLICT (provider_id, capability) DO NOTHING;

INSERT INTO market.symbol_provider_bindings (symbol_id, provider_id, provider_symbol, priority, enabled)
SELECT s.id, p.id, s.provider_symbol, 100, true
FROM market.symbols s
JOIN market.data_providers p ON p.code = s.provider
ON CONFLICT (symbol_id, provider_id, provider_symbol) DO NOTHING;
```

- [ ] **Step 2: Verify migration compiles**

Run:

```powershell
cd backend
mvn test -DskipTests
```

Expected: Maven build reaches `BUILD SUCCESS`.

---

## Task 2: Provider Entities and Repositories

**Files:**

- Create: entity and repository files listed under `File Structure`.
- Modify: no existing service behavior in this task.
- Test: `backend/src/test/java/com/fxplatform/market/provider/ProviderResolverTest.java`

- [ ] **Step 1: Create capability enum**

Create `backend/src/main/java/com/fxplatform/market/provider/MarketDataCapability.java`:

```java
package com.fxplatform.market.provider;

public enum MarketDataCapability {
  SYMBOLS,
  QUOTE,
  SNAPSHOT,
  CANDLES,
  ORDER_BOOK,
  TRADES,
  STREAM_QUOTE,
  STREAM_CANDLE,
  STREAM_ORDER_BOOK,
  STREAM_TRADES
}
```

- [ ] **Step 2: Create entity classes**

Each entity uses `@TableName`, `@TableId`, Lombok `@Getter`, `@Setter`, `@NoArgsConstructor`, and fields matching the SQL columns. Use `UUID`, `String`, `Boolean`, `Integer`, `Instant`; use `String configJson` for JSONB in the first implementation to keep MyBatis mapping simple.

Example for `DataProviderEntity`:

```java
package com.fxplatform.market.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("market.data_providers")
public class DataProviderEntity {
  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String code;
  private String name;
  private String providerType;
  private String restBaseUrl;
  private String wsUrl;
  private Boolean enabled = true;
  private Integer priority = 100;
  private Integer timeoutMs = 5000;
  private Integer rateLimitPerMinute = 1200;
  private String healthStatus = "UNKNOWN";
  private Instant lastHealthCheckAt;
  private String configJson = "{}";
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
```

- [ ] **Step 3: Create repository interfaces**

Each repository extends `BaseMapper<Entity>`. Add default finders only where needed by services.

Example:

```java
package com.fxplatform.market.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fxplatform.market.entity.DataProviderEntity;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataProviderRepository extends BaseMapper<DataProviderEntity> {
  default Optional<DataProviderEntity> findByCode(String code) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<DataProviderEntity>()
        .eq(DataProviderEntity::getCode, code)));
  }
}
```

- [ ] **Step 4: Compile backend**

Run:

```powershell
cd backend
mvn test -DskipTests
```

Expected: `BUILD SUCCESS`.

---

## Task 3: Provider Registry and Resolver

**Files:**

- Create: `backend/src/main/java/com/fxplatform/market/provider/MarketDataProviderAdapter.java`
- Create: `backend/src/main/java/com/fxplatform/market/provider/ProviderResolution.java`
- Create: `backend/src/main/java/com/fxplatform/market/provider/ProviderRegistry.java`
- Create: `backend/src/main/java/com/fxplatform/market/provider/ProviderResolver.java`
- Test: `backend/src/test/java/com/fxplatform/market/provider/ProviderResolverTest.java`

- [ ] **Step 1: Write resolver tests**

Create tests for:

- primary binding is selected by lowest `priority`.
- disabled binding is skipped.
- provider without requested capability is skipped.
- missing binding throws `MARKET_PROVIDER_BINDING_NOT_FOUND`.

Test method names:

```java
selectsLowestPriorityEnabledBinding()
skipsDisabledBinding()
skipsProviderWithoutCapability()
throwsWhenNoBindingSupportsCapability()
```

- [ ] **Step 2: Create adapter interface**

```java
package com.fxplatform.market.provider;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.dto.SymbolResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface MarketDataProviderAdapter {
  String code();
  Set<MarketDataCapability> capabilities();
  boolean configured();
  boolean supports(MarketDataCapability capability);
  List<SymbolResponse> fetchSymbols(String assetClass, int limit);
  Map<String, QuoteResponse> fetchMarketSnapshots(String assetClass, int limit);
  Optional<QuoteResponse> fetchLatestQuote(String symbol, String providerSymbol);
  Optional<QuoteResponse> fetchIndicativeQuote(String symbol, String providerSymbol, Instant to);
  List<CandleResponse> fetchCandles(String symbol, String providerSymbol, String timeframe, Instant from, Instant to);
  Optional<MarketDepthResponse> fetchOrderBook(String symbol, String providerSymbol);
  List<RecentTradeResponse> fetchRecentTrades(String symbol, String providerSymbol, int limit);
}
```

- [ ] **Step 3: Create resolution record**

```java
package com.fxplatform.market.provider;

import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;

public record ProviderResolution(
    SymbolEntity symbol,
    DataProviderEntity provider,
    SymbolProviderBindingEntity binding,
    MarketDataProviderAdapter adapter
) {
  public String providerSymbol() {
    return binding.getProviderSymbol();
  }
}
```

- [ ] **Step 4: Create registry**

`ProviderRegistry` receives `List<MarketDataProviderAdapter>` through constructor and maps `adapter.code()` to adapter.

Behavior:

- lookup is case-insensitive.
- unknown provider returns `Optional.empty()`.
- duplicate adapter code throws `IllegalStateException` at startup.

- [ ] **Step 5: Create resolver**

`ProviderResolver.resolve(symbol, capability)` flow:

1. Normalize platform symbol with `SymbolNormalizer.normalize`.
2. Load `SymbolEntity` by symbol.
3. Reject disabled symbol with `SYMBOL_NOT_ENABLED`.
4. Load enabled bindings by `symbol_id`, ordered by priority.
5. For each binding, load provider.
6. Skip disabled provider.
7. Skip provider without enabled capability.
8. Skip missing adapter.
9. Return first configured adapter that supports capability.
10. Throw `MARKET_PROVIDER_BINDING_NOT_FOUND` if no binding is usable.

- [ ] **Step 6: Run tests**

Run:

```powershell
cd backend
mvn -Dtest=ProviderResolverTest test
```

Expected: resolver tests pass.

---

## Task 4: Adapt Massive and Binance to Registry

**Files:**

- Modify: `backend/src/main/java/com/fxplatform/market/adapter/massive/MassiveRestClient.java`
- Modify: `backend/src/main/java/com/fxplatform/market/adapter/binance/BinanceSpotMarketDataProvider.java`
- Modify or remove: `backend/src/main/java/com/fxplatform/market/adapter/CompositeMarketDataProvider.java`
- Test: `backend/src/test/java/com/fxplatform/market/adapter/massive/MassiveRestClientTest.java`
- Test: `backend/src/test/java/com/fxplatform/market/adapter/binance/BinanceSpotMarketDataProviderTest.java`

- [ ] **Step 1: Make Massive implement `MarketDataProviderAdapter`**

Add:

```java
@Override
public String code() {
  return "massive";
}

@Override
public Set<MarketDataCapability> capabilities() {
  return Set.of(
      MarketDataCapability.SYMBOLS,
      MarketDataCapability.QUOTE,
      MarketDataCapability.SNAPSHOT,
      MarketDataCapability.CANDLES);
}

@Override
public boolean configured() {
  return isConfigured();
}

@Override
public boolean supports(MarketDataCapability capability) {
  return capabilities().contains(capability);
}
```

For order book and trades in Massive, return empty values:

```java
@Override
public Optional<MarketDepthResponse> fetchOrderBook(String symbol, String providerSymbol) {
  return Optional.empty();
}

@Override
public List<RecentTradeResponse> fetchRecentTrades(String symbol, String providerSymbol, int limit) {
  return List.of();
}
```

- [ ] **Step 2: Make Binance implement `MarketDataProviderAdapter`**

Use capabilities:

```java
Set.of(
    MarketDataCapability.SYMBOLS,
    MarketDataCapability.QUOTE,
    MarketDataCapability.SNAPSHOT,
    MarketDataCapability.CANDLES,
    MarketDataCapability.ORDER_BOOK,
    MarketDataCapability.TRADES)
```

Add REST implementations:

- candles: `GET /api/v3/klines`
- order book: `GET /api/v3/depth`
- trades: `GET /api/v3/trades`

- [ ] **Step 3: Remove hardcoded provider selection**

Replace consumers of `CompositeMarketDataProvider` with `MarketDataRouter`.

Leave a compatibility class only if Spring injection requires it during the transition. The compatibility class must delegate to `MarketDataRouter` and must not inspect symbol suffixes.

- [ ] **Step 4: Run adapter tests**

Run:

```powershell
cd backend
mvn -Dtest=MassiveRestClientTest,BinanceSpotMarketDataProviderTest test
```

Expected: tests pass.

---

## Task 5: MarketDataRouter and User-Facing Services

**Files:**

- Create: `backend/src/main/java/com/fxplatform/market/provider/MarketDataRouter.java`
- Modify: `backend/src/main/java/com/fxplatform/market/service/QuoteService.java`
- Modify: `backend/src/main/java/com/fxplatform/chart/service/ChartService.java`
- Modify: `backend/src/main/java/com/fxplatform/market/controller/MarketController.java`
- Test: `backend/src/test/java/com/fxplatform/market/service/QuoteServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/chart/service/ChartServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/market/controller/MarketControllerTest.java`

- [ ] **Step 1: Create router methods**

`MarketDataRouter` methods:

```java
public QuoteResponse latestQuote(String symbol)
public List<CandleResponse> candles(String symbol, String timeframe, Instant from, Instant to)
public MarketDepthResponse orderBook(String symbol)
public List<RecentTradeResponse> recentTrades(String symbol, int limit)
public Map<String, QuoteResponse> snapshots(String assetClass, int limit)
```

Each method resolves the required capability through `ProviderResolver`.

- [ ] **Step 2: Apply symbol feature switches**

Before resolving provider:

- `latestQuote`: reject when `quoteEnabled=false`.
- `candles`: reject when `chartEnabled=false`.
- `orderBook`: reject when `orderBookEnabled=false`.
- `recentTrades`: reject when provider lacks `TRADES`.

- [ ] **Step 3: Update QuoteService**

Keep Redis cache behavior. Replace direct `MarketDataProvider` calls with:

```java
QuoteResponse quote = marketDataRouter.latestQuote(normalizedSymbol);
```

- [ ] **Step 4: Update ChartService**

Resolve provider through router and store candles with `source = provider.code`.

- [ ] **Step 5: Update MarketController**

Replace `MarketTestDataService` usage for:

- `/api/market/order-book/{symbol}`
- `/api/market/trades/{symbol}`

Use `MarketDataRouter`.

- [ ] **Step 6: Run targeted tests**

Run:

```powershell
cd backend
mvn -Dtest=QuoteServiceTest,ChartServiceTest,MarketControllerTest test
```

Expected: tests pass.

---

## Task 6: Symbol Display Model

**Files:**

- Modify: `backend/src/main/java/com/fxplatform/market/entity/SymbolEntity.java`
- Modify: `backend/src/main/java/com/fxplatform/market/dto/SymbolResponse.java`
- Modify: `backend/src/main/java/com/fxplatform/market/service/SymbolService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/dto/request/AdminSymbolRequest.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/dto/response/AdminSymbolResponse.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminMarketCommandService.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminMarketQueryService.java`
- Test: `backend/src/test/java/com/fxplatform/market/service/SymbolServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/admin/service/AdminMarketCommandServiceTest.java`

- [ ] **Step 1: Add display fields to entities and DTOs**

Add fields:

```java
private String iconUrl;
private UUID iconAssetId;
private Boolean displayEnabled = true;
private Boolean quoteEnabled = true;
private Boolean chartEnabled = true;
private Boolean orderBookEnabled = true;
private Boolean tradable = true;
private Boolean featured = false;
private String displayGroup;
private Integer displayOrder = 0;
```

Extend `SymbolResponse` with:

```java
String iconUrl,
boolean displayEnabled,
boolean quoteEnabled,
boolean chartEnabled,
boolean orderBookEnabled,
boolean featured,
String displayGroup,
Integer displayOrder
```

- [ ] **Step 2: Update SymbolService list query**

User-facing `enabledSymbols` must only include:

```java
entity.getEnabled() == true
entity.getDisplayEnabled() == true
```

Sort by:

1. `display_order ASC`
2. `symbol ASC`

Remove provider-discovered symbols from user list. Provider instruments are now visible only in admin provider instrument pages until explicitly published.

- [ ] **Step 3: Update admin create and edit**

Admin symbol create/edit must persist display fields. Default values:

- `displayEnabled=true`
- `quoteEnabled=true`
- `chartEnabled=true`
- `orderBookEnabled=true`
- `tradable=true`
- `featured=false`
- `displayOrder=0`

- [ ] **Step 4: Run symbol tests**

Run:

```powershell
cd backend
mvn -Dtest=SymbolServiceTest,AdminMarketCommandServiceTest test
```

Expected: tests pass and hidden symbols do not appear in user-facing list.

---

## Task 7: Admin Provider Management API

**Files:**

- Create: admin DTO/controller/service files listed under `File Structure`.
- Modify: `backend/src/main/java/com/fxplatform/admin/controller/AdminMarketController.java`
- Test: `backend/src/test/java/com/fxplatform/admin/service/AdminMarketDataProviderServiceTest.java`
- Test: `backend/src/test/java/com/fxplatform/admin/controller/AdminMarketDataProviderControllerTest.java`

- [ ] **Step 1: Add provider CRUD**

Request DTO fields:

```java
String code
String name
String providerType
String restBaseUrl
String wsUrl
Boolean enabled
Integer priority
Integer timeoutMs
Integer rateLimitPerMinute
String configJson
```

Validation:

- `code` required on create and immutable on update.
- `name` required.
- `priority` defaults to `100`.
- `timeoutMs` defaults to `5000`.
- `configJson` defaults to `{}`.

- [ ] **Step 2: Add provider test endpoint**

`POST /api/admin/market/data-providers/{providerId}/test`:

- loads provider
- resolves adapter by provider code
- calls `adapter.configured()`
- updates `health_status` to `UP` or `DOWN`
- returns provider response

- [ ] **Step 3: Add instrument sync endpoint**

`POST /api/admin/market/data-providers/{providerId}/sync-instruments`:

- calls adapter `fetchSymbols(assetClass, limit)`
- upserts into `market.provider_instruments`
- never publishes directly into `market.symbols`
- returns `{ providerId, syncedCount }`

- [ ] **Step 4: Add binding endpoints**

Binding create:

```json
{
  "providerId": "uuid",
  "providerInstrumentId": "uuid",
  "providerSymbol": "BTCUSDT",
  "priority": 100,
  "enabled": true
}
```

Binding update supports changing:

- `providerSymbol`
- `priority`
- `enabled`
- `configJson`

- [ ] **Step 5: Run admin API tests**

Run:

```powershell
cd backend
mvn -Dtest=AdminMarketDataProviderServiceTest,AdminMarketDataProviderControllerTest test
```

Expected: tests pass.

---

## Task 8: Admin Frontend Pages

**Files:**

- Create: `apps/admin/src/pages/DataProvidersPage.tsx`
- Create: `apps/admin/src/pages/ProviderInstrumentsPage.tsx`
- Create: `apps/admin/src/pages/SymbolDataBindingsPage.tsx`
- Modify: `apps/admin/src/pages/SymbolsPage.tsx`
- Modify: `apps/admin/src/pages/MarketStatusPage.tsx`
- Modify: `apps/admin/src/services/adminApi.ts`
- Modify: `apps/admin/src/types.ts`
- Modify: `apps/admin/src/app/AdminApp.tsx`
- Modify: `apps/admin/src/app/adminMenu.ts`
- Test: `apps/admin/src/services/adminApi.test.mjs`
- Test: `apps/admin/src/app/AdminApp.test.mjs`

- [ ] **Step 1: Extend admin API client**

Add functions:

```ts
export function getDataProviders(token: string, page = 0, size = 50)
export function createDataProvider(token: string, payload: DataProviderPayload)
export function updateDataProvider(token: string, providerId: string, payload: DataProviderPayload)
export function testDataProvider(token: string, providerId: string)
export function syncProviderInstruments(token: string, providerId: string)
export function getProviderInstruments(token: string, providerId: string, page = 0, size = 50)
export function getSymbolBindings(token: string, symbolId: string)
export function saveSymbolBinding(token: string, symbolId: string, payload: SymbolBindingPayload)
export function updateSymbolDisplay(token: string, symbolId: string, payload: SymbolDisplayPayload)
```

- [ ] **Step 2: Add `DataProvidersPage`**

Page controls:

- provider table
- status badge
- enable switch
- test connection button
- sync instruments button
- edit drawer/modal for REST URL, WS URL, timeout, rate limit

- [ ] **Step 3: Add `ProviderInstrumentsPage`**

Page controls:

- provider selector
- asset class filter
- search box
- table showing provider symbol, base, quote, listed, last synced
- action `发布为平台品种`

Publishing opens a form with:

- platform symbol
- display name
- icon URL
- display group
- display order
- feature switches

- [ ] **Step 4: Upgrade `SymbolsPage`**

Add columns:

- icon preview
- display name
- asset class
- display group
- display order
- display enabled
- quote enabled
- chart enabled
- order book enabled
- tradable
- provider binding summary

Actions:

- edit display config
- manage bindings
- hide/show
- enable/disable quote

- [ ] **Step 5: Upgrade `MarketStatusPage`**

Show one row per provider:

- provider name
- enabled
- health status
- last health check time
- supported capabilities
- synced instruments count

- [ ] **Step 6: Run admin tests and build**

Run:

```powershell
npm --workspace apps/admin run test
npm --workspace apps/admin run build
```

Expected: tests pass and build completes.

---

## Task 9: OKX Adapter Skeleton

**Files:**

- Create: `backend/src/main/java/com/fxplatform/market/adapter/okx/OkxSpotMarketDataProvider.java`
- Test: `backend/src/test/java/com/fxplatform/market/adapter/okx/OkxSpotMarketDataProviderTest.java`

- [ ] **Step 1: Create adapter with public REST endpoints**

Adapter code:

```java
@Override
public String code() {
  return "okx";
}
```

Use provider symbol format `BTC-USDT`.

Capabilities:

```java
Set.of(
    MarketDataCapability.SYMBOLS,
    MarketDataCapability.QUOTE,
    MarketDataCapability.SNAPSHOT,
    MarketDataCapability.CANDLES,
    MarketDataCapability.ORDER_BOOK,
    MarketDataCapability.TRADES)
```

Endpoint mapping:

- symbols: `GET /api/v5/public/instruments?instType=SPOT`
- ticker: `GET /api/v5/market/ticker?instId=BTC-USDT`
- candles: `GET /api/v5/market/candles?instId=BTC-USDT`
- order book: `GET /api/v5/market/books?instId=BTC-USDT`
- trades: `GET /api/v5/market/trades?instId=BTC-USDT`

- [ ] **Step 2: Seed OKX provider disabled by default**

Add migration `V29__okx_market_provider_seed.sql`:

```sql
INSERT INTO market.data_providers (code, name, provider_type, asset_classes, rest_base_url, ws_url, enabled, priority, health_status)
VALUES ('okx', 'OKX Spot', 'REST_WS', ARRAY['CRYPTO'], 'https://www.okx.com', 'wss://ws.okx.com:8443/ws/v5/public', false, 30, 'UNKNOWN')
ON CONFLICT (code) DO NOTHING;
```

Add capabilities for OKX as in Step 1.

- [ ] **Step 3: Run OKX tests**

Run:

```powershell
cd backend
mvn -Dtest=OkxSpotMarketDataProviderTest test
```

Expected: tests pass with fixture HTTP responses.

---

## Task 10: Realtime Stream Supervisor

**Files:**

- Create: `backend/src/main/java/com/fxplatform/market/stream/MarketStreamSupervisor.java`
- Create: `backend/src/main/java/com/fxplatform/market/stream/MarketStreamSubscription.java`
- Modify: `backend/src/main/java/com/fxplatform/market/service/QuoteBroadcastService.java`
- Modify: `backend/src/main/java/com/fxplatform/market/websocket/MarketWsPublisher.java`
- Test: `backend/src/test/java/com/fxplatform/market/service/QuoteBroadcastServiceTest.java`

- [ ] **Step 1: Create stream subscription model**

```java
public record MarketStreamSubscription(
    String symbol,
    String providerCode,
    String providerSymbol,
    MarketDataCapability capability,
    String timeframe
) {
}
```

- [ ] **Step 2: Add supervisor service**

Responsibilities:

- load symbols where `enabled=true`, `display_enabled=true`, `quote_enabled=true`
- resolve provider bindings with `STREAM_QUOTE` or REST polling fallback
- group subscriptions by provider
- publish normalized quotes through existing `MarketWsPublisher`
- restart affected subscriptions when admin changes provider config or symbol bindings

- [ ] **Step 3: Keep REST polling as first working realtime layer**

First implementation may poll provider quotes on interval and publish through existing WebSocket topics. Native provider WebSocket adapters can be added after REST provider routing is stable.

- [ ] **Step 4: Run broadcast tests**

Run:

```powershell
cd backend
mvn -Dtest=QuoteBroadcastServiceTest test
```

Expected: tests pass and disabled symbols are not broadcast.

---

## Task 11: Full Verification

**Files:**

- No new files required.
- Use existing scripts and targeted tests.

- [ ] **Step 1: Run backend targeted tests**

Run:

```powershell
cd backend
mvn -Dtest=ProviderResolverTest,QuoteServiceTest,SymbolServiceTest,MarketControllerTest,AdminMarketDataProviderServiceTest test
```

Expected: all selected tests pass.

- [ ] **Step 2: Run backend full tests**

Run:

```powershell
cd backend
mvn test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run admin tests and build**

Run:

```powershell
npm --workspace apps/admin run test
npm --workspace apps/admin run build
```

Expected: tests pass and build completes.

- [ ] **Step 4: Run architecture verification**

Run:

```powershell
npm run verify:architecture
```

Expected: architecture check exits with code `0`.

- [ ] **Step 5: Manual smoke scenario**

Use admin UI or API to validate:

1. Massive provider is enabled and has `FOREX` capabilities.
2. `EURUSD` is published as platform symbol with `display_enabled=true`.
3. `EURUSD` icon URL can be changed and appears in `/api/market/symbols`.
4. `EURUSD` can be hidden with `display_enabled=false` and disappears from `/api/market/symbols`.
5. Binance provider is enabled and `BTCUSDT` is bound to `BTCUSDT`.
6. `BTCUSDT` quote resolves through Binance.
7. Switch `BTCUSDT` binding to OKX `BTC-USDT` when OKX provider is enabled.
8. Order book endpoint returns provider-backed depth for a provider with `ORDER_BOOK`.
9. A symbol with `quote_enabled=false` returns `SYMBOL_QUOTE_DISABLED` for quote endpoint.

---

## Definition of Done

- 外汇 Massive、加密 Binance、未来 OKX 都通过同一套 provider registry 进入系统。
- 后台可以管理 provider 启用状态、健康检查、原始品种同步。
- 后台可以从 provider instruments 发布少量平台 symbol。
- 后台可以隐藏、关闭、排序、替换图标、切换 provider binding。
- 用户端 symbol 列表只展示后台发布且 `display_enabled=true` 的平台 symbol。
- Quote、chart、order book、trades 不再依赖 `USDT` 后缀或 `FOREX/CRYPTO` 硬编码选择 provider。
- Demo data 不会在真实 provider 失败时静默接管，除非后台明确配置 demo fallback。
- 后端 targeted tests、后端 full tests、admin tests、admin build、architecture verification 全部通过。
