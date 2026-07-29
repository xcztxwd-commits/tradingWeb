# Trading Path Lab Phase 1: Demo Engine Capability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing platform-owned Demo trading model with the exact order, fee, slippage, trailing-stop, and partial-fill capabilities required by the Admin trading path lab.

**Architecture:** Keep the current `OrderService`/`PendingOrderExecutionService`/`OrderFillService` chain authoritative. Introduce a small immutable Demo execution policy and a deterministic matching engine, while preserving `SIMPLE` full-fill behavior as the default. The validation environment will override the policy later, but no trading path may bypass existing risk, wallet, ledger, position, or transaction services.

**Tech Stack:** Java 21, Spring Boot 3.5.7, MyBatis-Plus 3.5.12, Flyway, PostgreSQL 16, JUnit Jupiter, Mockito, `BigDecimal`.

## Global Constraints

- Preserve all current modified and untracked files; inspect the current diff before editing an overlapping file.
- Do not create a branch, Commit, Push, or PR.
- Write a failing regression test before each behavior change.
- Use `BigDecimal` with explicit scale and `RoundingMode`; never use `double` for money.
- Keep `execution.mode=demo` as the only successful execution mode.
- Keep normal Demo matching in `SIMPLE` mode unless an explicit validation-only policy override selects `DEPTH`.
- Do not add JPA.
- Do not connect to real broker, FIX, LP, private exchange API, or production market data.
- Check `apps/web` and `apps/admin` compatibility whenever the public API contract changes.
- Run wallet balance, asset ledger, cash ledger, and account summary assertions for every fee or settlement change.

---

## File Map

### Public contract and persistence

- Modify `backend/src/main/java/com/fxplatform/trading/enums/OrderType.java`
- Modify `backend/src/main/java/com/fxplatform/trading/enums/TimeInForce.java`
- Create `backend/src/main/java/com/fxplatform/execution/DemoMatchingMode.java`
- Modify `backend/src/main/java/com/fxplatform/trading/dto/request/CreateOrderRequest.java`
- Modify `backend/src/main/java/com/fxplatform/trading/dto/request/UpdateOrderRequest.java`
- Modify `backend/src/main/java/com/fxplatform/trading/dto/response/OrderResponse.java`
- Modify `backend/src/main/java/com/fxplatform/trading/entity/OrderEntity.java`
- Modify `backend/src/main/java/com/fxplatform/trading/service/OrderCommand.java`
- Modify `backend/src/main/java/com/fxplatform/trading/service/OrderCommandFactory.java`
- Modify `backend/src/main/java/com/fxplatform/trading/service/OrderEntityFactory.java`
- Modify `backend/src/main/java/com/fxplatform/trading/service/OrderRequestFingerprint.java`
- Modify `backend/src/main/java/com/fxplatform/trading/service/OrderResponseMapper.java`
- Create `backend/src/main/resources/db/migration/V54__demo_advanced_order_fields.sql`

### Execution policy and matching

- Create `backend/src/main/java/com/fxplatform/execution/DemoExecutionPolicy.java`
- Create `backend/src/main/java/com/fxplatform/execution/DemoExecutionPolicyProvider.java`
- Create `backend/src/main/java/com/fxplatform/execution/PropertyDemoExecutionPolicyProvider.java`
- Create `backend/src/main/java/com/fxplatform/execution/DemoBookLevel.java`
- Create `backend/src/main/java/com/fxplatform/execution/DemoMatchingRequest.java`
- Create `backend/src/main/java/com/fxplatform/execution/DemoMatchFill.java`
- Create `backend/src/main/java/com/fxplatform/execution/DemoMatchingResult.java`
- Create `backend/src/main/java/com/fxplatform/execution/DemoMatchingEngine.java`
- Modify `backend/src/main/java/com/fxplatform/execution/FullFillCoordinator.java`
- Modify `backend/src/main/java/com/fxplatform/execution/SimulatedExecutionAdapter.java`

### Trading behavior

- Modify `backend/src/main/java/com/fxplatform/trading/service/OrderService.java`
- Modify `backend/src/main/java/com/fxplatform/trading/service/OrderFillService.java`
- Modify `backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionProcessor.java`
- Modify `backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java`
- Create `backend/src/main/java/com/fxplatform/trading/service/TrailingStopService.java`
- Modify `backend/src/main/java/com/fxplatform/trading/controller/TradingController.java`
- Modify `backend/src/main/java/com/fxplatform/common/exception/ErrorCode.java`

### Tests

- Create `backend/src/test/java/com/fxplatform/trading/AdvancedOrderContractTest.java`
- Create `backend/src/test/java/com/fxplatform/execution/DemoExecutionPolicyTest.java`
- Create `backend/src/test/java/com/fxplatform/execution/DemoMatchingEngineTest.java`
- Modify `backend/src/test/java/com/fxplatform/execution/FullFillCoordinatorTest.java`
- Modify `backend/src/test/java/com/fxplatform/trading/service/OrderServiceTest.java`
- Modify `backend/src/test/java/com/fxplatform/trading/service/PendingOrderExecutionProcessorTest.java`
- Create `backend/src/test/java/com/fxplatform/trading/service/TrailingStopServiceTest.java`
- Modify `backend/src/test/java/com/fxplatform/trading/service/Task6SpotOrderServiceTest.java`
- Modify `backend/src/test/java/com/fxplatform/trading/service/PerpetualOrderServiceTest.java`
- Modify `backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java`

### Frontend compatibility

- Regenerate `packages/shared-types/src/generated/openapi.ts`
- Modify only the existing Web order request mapping files that fail contract/build checks.
- Add no UI controls for advanced order types in `apps/web` unless required to keep current behavior compiling.

---

### Task 1: Lock the advanced order HTTP and database contract

**Interfaces:**

- `OrderType`: add `STOP_LIMIT`, `TRAILING_STOP_MARKET`.
- `TimeInForce`: add `IOC`, `FOK`.
- `CreateOrderRequest`: add `TimeInForce timeInForce`, `Boolean postOnly`, `BigDecimal activationPrice`, `BigDecimal trailingDelta`, `BigDecimal trailingRate`.
- `OrderResponse`: expose the same normalized fields plus `BigDecimal trailingExtreme`.
- `OrderEntity`: persist the same fields.

- [ ] **Step 1: Write the failing contract test**

Create `AdvancedOrderContractTest` with exact enum and validation assertions:

```java
@Test
void exposes_platform_advanced_order_contract() {
  assertThat(OrderType.values())
      .contains(OrderType.STOP_LIMIT, OrderType.TRAILING_STOP_MARKET);
  assertThat(TimeInForce.values())
      .contains(TimeInForce.GTC, TimeInForce.IOC, TimeInForce.FOK);
}

@Test
void trailing_order_requires_exactly_one_callback_contract() {
  CreateOrderRequest invalid = Requests.trailing(null, null);
  Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(invalid);
  assertThat(violations)
      .extracting(ConstraintViolation::getMessage)
      .contains("trailingDelta or trailingRate is required, but not both");
}
```

- [ ] **Step 2: Run the contract test and confirm RED**

Run:

```powershell
cd fx-trading-platform/backend
mvn "-Dtest=AdvancedOrderContractTest" test
```

Expected: test compilation fails because the new enum constants and request fields do not exist.

- [ ] **Step 3: Add the migration**

`V54__demo_advanced_order_fields.sql` must contain:

```sql
ALTER TABLE trading.orders
  ADD COLUMN IF NOT EXISTS post_only BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS activation_price NUMERIC(30, 12),
  ADD COLUMN IF NOT EXISTS trailing_delta NUMERIC(30, 12),
  ADD COLUMN IF NOT EXISTS trailing_rate NUMERIC(18, 10),
  ADD COLUMN IF NOT EXISTS trailing_extreme NUMERIC(30, 12);

ALTER TABLE trading.orders
  ADD CONSTRAINT ck_orders_trailing_callback
  CHECK (
    order_type <> 'TRAILING_STOP_MARKET'
    OR ((trailing_delta IS NOT NULL) <> (trailing_rate IS NOT NULL))
  ) NOT VALID;
```

Use a guarded PostgreSQL block if the constraint name already exists in a local development database.

- [ ] **Step 4: Extend enums, request validation, entity, command, factory, fingerprint, and response mapping**

Normalize request defaults in the compact constructor:

```java
timeInForce = timeInForce == null ? TimeInForce.GTC : timeInForce;
postOnly = postOnly == null ? Boolean.FALSE : postOnly;
```

Add bean validation methods:

```java
@AssertTrue(message = "STOP_LIMIT requires triggerPrice and price")
public boolean hasValidStopLimitContract() {
  return orderType != OrderType.STOP_LIMIT || (triggerPrice != null && price() != null);
}

@AssertTrue(message = "trailingDelta or trailingRate is required, but not both")
public boolean hasValidTrailingContract() {
  if (orderType != OrderType.TRAILING_STOP_MARKET) return true;
  return (trailingDelta != null) ^ (trailingRate != null);
}

@AssertTrue(message = "postOnly requires LIMIT and GTC")
public boolean hasValidPostOnlyContract() {
  return !Boolean.TRUE.equals(postOnly)
      || (orderType == OrderType.LIMIT && timeInForce == TimeInForce.GTC);
}
```

Include every new field in `OrderRequestFingerprint` so reusing a client key with changed execution semantics returns the existing fingerprint conflict code.

- [ ] **Step 5: Run contract and mapper tests**

Run:

```powershell
mvn "-Dtest=AdvancedOrderContractTest,OrderResponseMapperTest,OrderCommandFactoryTest" test
```

Expected: all selected tests pass with zero skipped tests.

### Task 2: Introduce immutable Demo execution policy and USDT-only fees

**Interfaces:**

```java
public record DemoExecutionPolicy(
    DemoMatchingMode matchingMode,
    BigDecimal makerFeeRate,
    BigDecimal takerFeeRate,
    BigDecimal liquidationFeeRate,
    BigDecimal slippageRate,
    List<DemoBookLevel> bids,
    List<DemoBookLevel> asks,
    BigDecimal maxFillQuantityPerTick
) {}

public interface DemoExecutionPolicyProvider {
  DemoExecutionPolicy current();
}
```

- [ ] **Step 1: Write failing policy and Spot fee tests**

Add tests proving:

```java
assertThat(provider.current().matchingMode()).isEqualTo(DemoMatchingMode.SIMPLE);
assertThat(provider.current().makerFeeRate()).isEqualByComparingTo("0.0002");
assertThat(provider.current().takerFeeRate()).isEqualByComparingTo("0.0005");
assertThat(provider.current().slippageRate()).isEqualByComparingTo("0.0001");
```

Update `FullFillCoordinatorTest` with:

```java
@Test
void spot_buy_charges_quote_fee_in_usdt() {
  FullFillResult result = coordinator.execute(spotBuy("1.00000000"), snapshot("100.00", "101.00"));
  assertThat(result.feeAsset()).isEqualTo("USDT");
  assertThat(result.fee()).isEqualByComparingTo("0.05050500");
}
```

The expected fee is `baseQuantity × fillPrice × takerFeeRate`, rounded to the platform money scale.

- [ ] **Step 2: Run the tests and confirm RED**

Run:

```powershell
mvn "-Dtest=DemoExecutionPolicyTest,FullFillCoordinatorTest" test
```

Expected: failures show hard-coded rates and base-asset Spot BUY fee behavior.

- [ ] **Step 3: Implement the property-backed default provider**

Use configuration keys:

```yaml
execution:
  demo:
    matching-mode: ${DEMO_MATCHING_MODE:SIMPLE}
    maker-fee-rate: ${DEMO_MAKER_FEE_RATE:0.0002}
    taker-fee-rate: ${DEMO_TAKER_FEE_RATE:0.0005}
    liquidation-fee-rate: ${DEMO_LIQUIDATION_FEE_RATE:0.001}
    slippage-rate: ${DEMO_SLIPPAGE_RATE:0.0001}
```

Validate all rates are non-negative and below `1`.

Register the property-backed provider with `@ConditionalOnMissingBean(DemoExecutionPolicyProvider.class)` so the validation profile can supply one explicit run-scoped override without creating two competing beans.

- [ ] **Step 4: Replace `FullFillCoordinator` constants with the provider**

`FullFillCoordinator` must read one policy snapshot at the start of `execute`/`project` and use it consistently. Change `fee(...)` and `feeAsset(...)` so every Spot and Perpetual fee is USDT notional:

```java
BigDecimal quoteNotional = request.requestedBaseQuantity().multiply(filledPrice);
return money(quoteNotional.multiply(feeRate));
```

- [ ] **Step 5: Update Spot settlement and ledger assertions**

For Spot BUY:

- Credit the full filled base quantity to the Spot wallet.
- Debit quote notional plus USDT fee from the USDT wallet.
- Write fee ledger entries in USDT.
- Recalculate Spot average cost using quote cost plus USDT fee when computing break-even, while preserving separately reported raw acquisition price.

Update existing Spot tests to assert wallet balance, asset ledger, cash ledger, and account summary together.

- [ ] **Step 6: Run the focused financial suite**

Run:

```powershell
mvn "-Dtest=DemoExecutionPolicyTest,FullFillCoordinatorTest,Task6SpotOrderServiceTest,SpotSettlementServiceTest,WalletLedgerReconciliationTest" test
```

Expected: all selected tests pass; no assertion expects a base-asset fee.

### Task 3: Implement Post Only, IOC, FOK, and Stop Limit in simple matching

**Interfaces:**

- `OrderService` decides whether an order is immediately marketable using the same executable snapshot later used for fill.
- `PendingOrderExecutionProcessor` converts a triggered `STOP_LIMIT` to a working `LIMIT` without changing its client identity.
- Error codes:
  - `POST_ONLY_WOULD_TAKE`
  - `FOK_NOT_FILLABLE`
  - `STOP_LIMIT_CONTRACT_INVALID`

- [ ] **Step 1: Add failing service tests**

Cover:

```java
@Test void post_only_marketable_limit_is_rejected_without_mutation() {}
@Test void post_only_resting_limit_enters_pending_state() {}
@Test void ioc_simple_market_order_fills_fully() {}
@Test void ioc_non_marketable_limit_cancels_without_fill() {}
@Test void fok_simple_fillable_order_fills_fully() {}
@Test void fok_non_fillable_order_rejects_without_hold() {}
@Test void stop_limit_trigger_becomes_limit_and_waits_when_not_marketable() {}
@Test void stop_limit_trigger_fills_when_marketable() {}
```

Each rejection test must snapshot orders, trades, positions, wallet balances, asset ledger, cash ledger, and account summary before and after.

- [ ] **Step 2: Run focused tests and confirm RED**

Run:

```powershell
mvn "-Dtest=OrderServiceTest,PendingOrderExecutionProcessorTest" test
```

Expected: new cases fail because only GTC and STOP_MARKET are accepted.

- [ ] **Step 3: Implement simple-mode semantics**

Use one method:

```java
private boolean isMarketable(OrderSide side, BigDecimal limitPrice, ExecutableMarketSnapshot snapshot) {
  return side == OrderSide.BUY
      ? limitPrice.compareTo(snapshot.ask()) >= 0
      : limitPrice.compareTo(snapshot.bid()) <= 0;
}
```

Rules:

- Post Only + marketable: reject before hold/persistence mutation.
- IOC + non-marketable: persist terminal `CANCELLED` with zero fill and zero hold.
- FOK + non-marketable: return `FOK_NOT_FILLABLE` with zero mutation.
- STOP_LIMIT: initially `PENDING_ACTIVATION`; on trigger, set order type execution state to working limit semantics while preserving public `orderType=STOP_LIMIT`.

- [ ] **Step 4: Make pending modification rules explicit**

`UpdateOrderRequest` may update:

- LIMIT: quantity and price.
- STOP_LIMIT before trigger: quantity, triggerPrice, and price.
- Triggered STOP_LIMIT: quantity and limit price only.
- IOC/FOK terminal orders: never modifiable.
- TRAILING_STOP_MARKET: callback fields are handled by Task 5.

- [ ] **Step 5: Run focused and existing order suites**

Run:

```powershell
mvn "-Dtest=OrderServiceTest,PendingOrderExecutionProcessorTest,PendingOrderExecutionServiceTest,OcoOrderServiceTest,PerpetualOrderServiceTest,Task6SpotOrderServiceTest" test
```

Expected: zero failures and zero errors.

### Task 4: Add deterministic depth matching and partial-fill settlement

**Interfaces:**

```java
public record DemoMatchingRequest(
    String symbol,
    OrderSide side,
    OrderType orderType,
    TimeInForce timeInForce,
    BigDecimal requestedBaseQuantity,
    BigDecimal limitPrice,
    DemoExecutionPolicy policy
) {}

public record DemoMatchFill(
    BigDecimal quantity,
    BigDecimal price,
    LiquidityRole liquidityRole,
    BigDecimal feeRate
) {}

public record DemoMatchingResult(
    List<DemoMatchFill> fills,
    BigDecimal filledQuantity,
    BigDecimal remainingQuantity,
    OrderStatus terminalOrWorkingStatus
) {}
```

- [ ] **Step 1: Write the matching-engine RED tests**

Cover exact deterministic cases:

```java
@Test void buy_sweeps_asks_in_price_order_and_stops_at_quantity() {}
@Test void per_tick_cap_produces_partial_fill() {}
@Test void ioc_cancels_remainder_after_available_depth() {}
@Test void fok_returns_zero_fills_when_full_quantity_is_unavailable() {}
@Test void post_only_never_consumes_depth() {}
@Test void simple_mode_returns_one_full_fill() {}
```

Use fixed decimal levels such as asks `100@1`, `101@2`, `102@5`.

- [ ] **Step 2: Run and confirm RED**

Run:

```powershell
mvn "-Dtest=DemoMatchingEngineTest" test
```

Expected: compilation failure because the matching types do not exist.

- [ ] **Step 3: Implement the pure matching engine**

The engine must:

- Be side-effect free.
- Sort asks ascending and bids descending.
- Respect limit price.
- Respect `maxFillQuantityPerTick`.
- Return deterministic fills in level order.
- Apply no wallet or order mutation.

- [ ] **Step 4: Add transactional partial-fill application**

Add an `OrderFillService.applyFill(...)` method that:

```java
OrderEntity applyFill(
    OrderEntity lockedOrder,
    DemoMatchFill fill,
    ExecutableMarketSnapshot snapshot,
    DemoExecutionPolicy policy
)
```

For every fill:

- Insert exactly one `TradeEntity`.
- Apply fee, wallet, ledger, position, margin, and realized PnL for only that fill quantity.
- Increment `filledQuantity`.
- Decrement `remainingQuantity`.
- Recalculate `avgFillPrice` as weighted average.
- Set `PARTIALLY_FILLED` or `FILLED`.
- Release only the hold no longer required.

Keep all mutations in the existing trading transaction boundary.

- [ ] **Step 5: Integrate with new and pending orders**

- SIMPLE mode keeps the existing full-fill path.
- DEPTH marketable orders call the matching engine.
- Resting DEPTH orders can receive one or more fills per virtual Tick.
- IOC applies available fills and cancels the remainder.
- FOK checks full availability before applying any fill.

- [ ] **Step 6: Add rollback and concurrency tests**

Cover:

- Failure after first proposed fill but before commit produces no Trade or ledger mutation.
- Reprocessing the same Tick/fill identity is idempotent.
- Fill/cancel race produces one legal winner.
- Two partial fills followed by final fill preserve exact average price and fee sum.

- [ ] **Step 7: Run the depth and financial suites**

Run:

```powershell
mvn "-Dtest=DemoMatchingEngineTest,OrderServiceTest,OrderPositionConcurrencyTest,PendingOrderExecutionProcessorTest,Task6SpotOrderServiceTest,PerpetualOrderServiceTest" test
```

Expected: all selected tests pass and at least one test observes `PARTIALLY_FILLED`.

### Task 5: Implement deterministic trailing stop

**Interfaces:**

```java
public record TrailingStopUpdate(
    UUID orderId,
    BigDecimal nextExtreme,
    boolean activated,
    boolean triggered
) {}

public TrailingStopUpdate evaluate(OrderEntity order, BigDecimal triggerPrice);
```

- [ ] **Step 1: Write failing trailing tests**

Cover:

- SELL trailing stop activates, tracks the highest price, and triggers on retracement.
- BUY trailing stop tracks the lowest price.
- Activation price prevents early extreme updates.
- Percentage and absolute callbacks are mutually exclusive.
- Replaying the same Tick does not trigger twice.
- Trigger creates a system STOP_MARKET close order through the existing trading service.

- [ ] **Step 2: Run and confirm RED**

Run:

```powershell
mvn "-Dtest=TrailingStopServiceTest" test
```

Expected: compilation failure because `TrailingStopService` does not exist.

- [ ] **Step 3: Implement pure evaluation and locked persistence**

For SELL:

```text
nextExtreme = max(previousExtreme, currentPrice)
triggerPrice = nextExtreme - trailingDelta
or
triggerPrice = nextExtreme * (1 - trailingRate)
```

For BUY, use `min` and add the callback. Persist `trailingExtreme` with optimistic locking.

- [ ] **Step 4: Integrate into the pending/system-order processor**

Evaluate trailing orders once per virtual/system Tick after the quote is updated and before ordinary conditional orders. Trigger through the same `SystemCloseOrderService` path used by TP/SL so risk, fees, wallet, ledger, and audit remain canonical.

- [ ] **Step 5: Run trailing, protection, and liquidation suites**

Run:

```powershell
mvn "-Dtest=TrailingStopServiceTest,ProtectiveOrderExecutionServiceTest,SystemCloseOrderServiceTest,LiquidationWorkflowTest" test
```

Expected: zero failures and no duplicate system close.

### Task 6: Regenerate contracts and verify frontend compatibility

**Interfaces:**

- OpenAPI generated types expose the new request/response fields.
- Existing Web controls continue sending `GTC`, `postOnly=false`, and no trailing fields.

- [ ] **Step 1: Export and generate the contract**

Run from `fx-trading-platform`:

```powershell
npm run contract:export
npm run contract:generate
```

Expected: generated TypeScript includes `STOP_LIMIT`, `TRAILING_STOP_MARKET`, `IOC`, `FOK`, `postOnly`, and trailing fields.

- [ ] **Step 2: Run contract and frontend tests**

```powershell
npm run contract:check
cmd.exe /d /s /c "npm.cmd --prefix apps/web test"
cmd.exe /d /s /c "npm.cmd --prefix apps/admin test"
```

Expected: all tests pass. If a current Web mapper exhaustively switches on enum values, add explicit disabled/default handling without exposing unsupported user UI controls.

- [ ] **Step 3: Run builds**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix apps/web run build"
cmd.exe /d /s /c "npm.cmd --prefix apps/admin run build"
```

Expected: both builds pass.

- [ ] **Step 4: Run backend and architecture gates**

```powershell
cd backend
mvn test
cd ..
npm run verify:architecture
```

Expected: backend tests and architecture verification pass. Record any Docker-dependent skipped tests separately; no new Phase 1 unit test may be skipped.
