package com.fxplatform.trading.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.UpdatePositionModeRequest;
import com.fxplatform.account.dto.UpdateSymbolSettingsRequest;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.DemoAccountLifecycleService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.ClosePositionRequest;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.request.CreateOrderRequest.AttachedProtectionRequest;
import com.fxplatform.trading.dto.request.CreateProtectionRequest;
import com.fxplatform.trading.dto.request.UpdateProtectionRequest;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "execution.mode=demo",
    "spring.task.scheduling.enabled=false",
    "trading.pending-order-execution-enabled=false",
    "trading.protective-order-execution-enabled=false"
})
@ActiveProfiles("database-it")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class Task10PostgresProtectionIT {

  private static final String SYMBOL = "BTCUSDT-PERP";
  private static final String FAILURE_TRIGGER = "task10_fail_order_event";
  private static final String FAILURE_FUNCTION = "trading.task10_fail_order_event";

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired DemoAccountLifecycleService lifecycleService;
  @Autowired OrderService orderService;
  @Autowired SystemCloseOrderService systemCloseOrderService;
  @Autowired ProtectionOrderService protectionOrderService;
  @Autowired TradingSettingsService settingsService;
  @Autowired OrderRepository orderRepository;
  @Autowired TradingAccountRepository accountRepository;
  @Autowired QuoteService quoteService;
  @Autowired RiskCheckService riskCheckService;
  @Autowired OrderFillService orderFillService;
  @Autowired OrderEventService orderEventService;
  @Autowired DemoExecutionGuard demoExecutionGuard;
  @Autowired WalletBalanceRepository walletBalanceRepository;
  @Autowired WalletService walletService;
  @Autowired SpotPositionService spotPositionService;
  @Autowired PositionRepository positionRepository;
  @Autowired TradingTransactionExecutor transactionExecutor;
  @Autowired FullFillCoordinator fullFillCoordinator;
  @Autowired PerpetualOrderRiskService perpetualOrderRiskService;
  @Autowired AccountSymbolSettingRepository accountSymbolSettingRepository;
  @Autowired SymbolRepository symbolRepository;
  @Autowired PerpetualAccountRiskSnapshotService perpetualAccountRiskSnapshotService;

  @MockBean
  MarketBundleResolver marketBundleResolver;

  private final List<Fixture> fixtures = new ArrayList<>();

  @BeforeEach
  void resetMarket() {
    reset(marketBundleResolver);
  }

  @AfterEach
  void cleanFixtures() {
    dropEventFailureTrigger();
    for (Fixture fixture : fixtures) {
      UUID accountId = fixture.accountId();
      jdbcTemplate.update("DELETE FROM audit.audit_logs WHERE target_id = ?", accountId.toString());
      jdbcTemplate.update("""
          DELETE FROM trading.order_events
          WHERE order_id IN (SELECT id FROM trading.orders WHERE account_id = ?)
          """, accountId);
      jdbcTemplate.update("DELETE FROM trading.trades WHERE account_id = ?", accountId);
      jdbcTemplate.update("DELETE FROM trading.orders WHERE account_id = ?", accountId);
      jdbcTemplate.update("DELETE FROM trading.positions WHERE account_id = ?", accountId);
      jdbcTemplate.update("DELETE FROM trading.spot_positions WHERE account_id = ?", accountId);
      jdbcTemplate.update("DELETE FROM trading.funding_settlements WHERE account_id = ?", accountId);
      jdbcTemplate.update("DELETE FROM ledger.asset_ledger_entries WHERE account_id = ?", accountId);
      jdbcTemplate.update("DELETE FROM ledger.ledger_entries WHERE account_id = ?", accountId);
      jdbcTemplate.update("DELETE FROM core.wallet_balances WHERE account_id = ?", accountId);
      jdbcTemplate.update("DELETE FROM trading.account_symbol_settings WHERE account_id = ?", accountId);
      jdbcTemplate.update("DELETE FROM core.trading_accounts WHERE id = ?", accountId);
      jdbcTemplate.update("DELETE FROM auth.users WHERE id = ?", fixture.userId());
    }
    fixtures.clear();
  }

  @Test
  void partialClosePersistsExactOrderTradeFeePnlMarginAndEntry() {
    Fixture fixture = createFixture("partial");
    stubBundle(bundle("99", "101", "100", "100"));
    createOrder(
        fixture, OrderSide.BUY, OrderType.MARKET, "2", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false, List.of());
    UUID positionId = openPositionId(fixture.accountId(), PositionSide.BOTH);

    SystemCloseOrderService.CloseResult result = systemCloseOrderService.closeUser(
        fixture.userId(), fixture.accountId(), positionId,
        closeRequest("1", "partial-close"));
    UUID closeOrderId = result.order().getId();
    UUID closeTradeId = uuid(
        "SELECT id FROM trading.trades WHERE order_id = ?", closeOrderId);

    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", closeOrderId))
        .isEqualTo("FILLED");
    assertThat(string("SELECT side FROM trading.orders WHERE id = ?", closeOrderId))
        .isEqualTo("SELL");
    assertThat(bool("SELECT reduce_only FROM trading.orders WHERE id = ?", closeOrderId)).isTrue();
    assertThat(string("SELECT order_origin FROM trading.orders WHERE id = ?", closeOrderId))
        .isEqualTo("USER");
    assertDecimal("1.00000000", "SELECT base_quantity FROM trading.orders WHERE id = ?", closeOrderId);
    assertDecimal("98.99010000", "SELECT execution_price FROM trading.orders WHERE id = ?", closeOrderId);
    assertDecimal("0.04949505", "SELECT fee FROM trading.orders WHERE id = ?", closeOrderId);
    assertDecimal("0.00000000", "SELECT hold_amount FROM trading.orders WHERE id = ?", closeOrderId);

    assertDecimal("1.00000000", "SELECT lots FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("101.01010000", "SELECT open_price FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("10.10101000", "SELECT margin_held FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("-2.02000000", "SELECT realized_pnl FROM trading.positions WHERE id = ?", positionId);
    assertThat(string("SELECT status FROM trading.positions WHERE id = ?", positionId))
        .isEqualTo("OPEN");

    assertDecimal("1.00000000", "SELECT lots FROM trading.trades WHERE id = ?", closeTradeId);
    assertDecimal("98.99010000", "SELECT price FROM trading.trades WHERE id = ?", closeTradeId);
    assertDecimal("0.04949505", "SELECT fee FROM trading.trades WHERE id = ?", closeTradeId);
    assertDecimal("-2.02000000", "SELECT realized_pnl FROM trading.trades WHERE id = ?", closeTradeId);
    assertThat(string("SELECT liquidity_role FROM trading.trades WHERE id = ?", closeTradeId))
        .isEqualTo("TAKER");

    assertAccount(
        fixture, "49997.82949485", "49996.81939485", "10.10101000", "49986.71838485");
    assertLedger(closeOrderId, "ORDER", "ORDER_HOLD", "1.05939505");
    assertLedger(closeOrderId, "ORDER", "ORDER_RELEASE", "1.05939505");
    assertLedger(positionId, "POSITION", "MARGIN_RELEASE", "10.10101000");
    assertLedger(positionId, "POSITION", "TRADE_PNL", "-2.02000000");
    assertLedger(closeTradeId, "TRADE", "TRADE_FEE", "-0.04949505");
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'ORDER_FILLED'
        """, closeOrderId)).isEqualTo(1);
  }

  @Test
  void fullCloseExpiresEveryProtectionWithEvents() {
    Fixture fixture = createFixture("full-expire");
    stubBundle(bundle("99", "101", "100", "100"));
    createOrder(
        fixture, OrderSide.BUY, OrderType.MARKET, "1", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false, List.of());
    UUID positionId = openPositionId(fixture.accountId(), PositionSide.BOTH);
    OrderResponse takeProfit = createProtection(
        fixture, positionId, ProtectionType.TAKE_PROFIT, "1", "110",
        TriggerExecutionType.MARKET, null);
    OrderResponse stopLoss = createProtection(
        fixture, positionId, ProtectionType.STOP_LOSS, "1", "90",
        TriggerExecutionType.LIMIT, "89");

    SystemCloseOrderService.CloseResult result = systemCloseOrderService.closeWhole(
        fixture.accountId(), positionId, OrderOrigin.BATCH_CLOSE,
        "task10 full close", "task10-full-close");
    UUID closeOrderId = result.order().getId();

    assertThat(string("SELECT status FROM trading.positions WHERE id = ?", positionId))
        .isEqualTo("CLOSED");
    assertDecimal("1.00000000", "SELECT lots FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("0.00000000", "SELECT margin_held FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("0.00000000", "SELECT initial_margin FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("0.00000000", "SELECT maintenance_margin FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("0.00000000", "SELECT notional FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("100.0000000000", "SELECT mark_price FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("-2.02000000", "SELECT realized_pnl FROM trading.positions WHERE id = ?", positionId);
    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE id IN (?, ?) AND status = 'EXPIRED'
          AND remaining_quantity = 0 AND hold_amount = 0
        """, takeProfit.id(), stopLoss.id())).isEqualTo(2);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id IN (?, ?) AND event_type = 'PROTECTION_EXPIRED'
          AND from_status = 'PENDING_ACTIVATION' AND to_status = 'EXPIRED'
        """, takeProfit.id(), stopLoss.id())).isEqualTo(2);
    assertThat(string("SELECT order_origin FROM trading.orders WHERE id = ?", closeOrderId))
        .isEqualTo("BATCH_CLOSE");
    assertThat(string("SELECT system_reason FROM trading.orders WHERE id = ?", closeOrderId))
        .isEqualTo("task10 full close");
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", closeOrderId))
        .isEqualTo(1);
    assertThat(string("SELECT system_reason FROM trading.trades WHERE order_id = ?", closeOrderId))
        .isEqualTo("task10 full close");
    assertDecimal("-2.02000000", "SELECT realized_pnl FROM trading.trades WHERE order_id = ?", closeOrderId);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'POSITION' AND reference_id = ?
          AND operation_type = 'MARGIN_RELEASE'
        """, fixture.accountId(), positionId)).isEqualTo(1);
    assertAccount(
        fixture, "49997.87999990", "49997.87999990", "0.00000000", "49997.87999990");
  }

  @Test
  void overCloseAndInjectedEventFailureRollbackEveryTable() {
    Fixture fixture = createFixture("rollback");
    stubBundle(bundle("99", "101", "100", "100"));
    createOrder(
        fixture, OrderSide.BUY, OrderType.MARKET, "1", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false, List.of());
    UUID positionId = openPositionId(fixture.accountId(), PositionSide.BOTH);
    MutationState baseline = mutationState(fixture.accountId(), positionId);

    assertThatThrownBy(() -> systemCloseOrderService.closeUser(
        fixture.userId(), fixture.accountId(), positionId,
        closeRequest("2", "over-close")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION));
    assertThat(mutationState(fixture.accountId(), positionId)).isEqualTo(baseline);

    installEventFailureTrigger("ORDER_FILLED");
    try {
      assertThatThrownBy(() -> systemCloseOrderService.closeUser(
          fixture.userId(), fixture.accountId(), positionId,
          closeRequest("1", "injected-close")))
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("task10 injected ORDER_FILLED failure");
    } finally {
      dropEventFailureTrigger();
    }

    assertThat(mutationState(fixture.accountId(), positionId)).isEqualTo(baseline);
    assertThat(string("SELECT status FROM trading.positions WHERE id = ?", positionId))
        .isEqualTo("OPEN");
    assertDecimal("1.00000000", "SELECT lots FROM trading.positions WHERE id = ?", positionId);
    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE account_id = ? AND client_order_id IN ('over-close', 'injected-close')
        """, fixture.accountId())).isZero();
  }

  @Test
  void hedgeCloseIsolatesLongLegAndNeverReverses() {
    Fixture fixture = createFixture("hedge-close");
    settingsService.updatePositionMode(
        fixture.userId(), fixture.accountId(), new UpdatePositionModeRequest(PositionMode.HEDGE));
    stubBundle(bundle("99", "101", "100", "100"));
    createOrder(
        fixture, OrderSide.BUY, OrderType.MARKET, "2", null, null,
        PositionSide.LONG, MarginMode.CROSS, false, List.of());
    createOrder(
        fixture, OrderSide.SELL, OrderType.MARKET, "1", null, null,
        PositionSide.SHORT, MarginMode.CROSS, false, List.of());
    UUID longPositionId = openPositionId(fixture.accountId(), PositionSide.LONG);
    UUID shortPositionId = openPositionId(fixture.accountId(), PositionSide.SHORT);
    PositionState shortBefore = positionState(shortPositionId);

    SystemCloseOrderService.CloseResult result = systemCloseOrderService.closeUser(
        fixture.userId(), fixture.accountId(), longPositionId,
        closeRequest("1", "hedge-long-close"));
    UUID closeOrderId = result.order().getId();

    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL)).isEqualTo(2);
    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'CLOSED'
        """, fixture.accountId(), SYMBOL)).isZero();
    assertDecimal("1.00000000", "SELECT lots FROM trading.positions WHERE id = ?", longPositionId);
    assertDecimal("101.01010000", "SELECT open_price FROM trading.positions WHERE id = ?", longPositionId);
    assertThat(positionState(shortPositionId)).isEqualTo(shortBefore);
    assertThat(string("SELECT position_side FROM trading.orders WHERE id = ?", closeOrderId))
        .isEqualTo("LONG");
    assertThat(string("SELECT side FROM trading.orders WHERE id = ?", closeOrderId))
        .isEqualTo("SELL");
    assertThat(bool("SELECT reduce_only FROM trading.orders WHERE id = ?", closeOrderId)).isTrue();
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(3);
    assertDecimal("-2.02000000", "SELECT realized_pnl FROM trading.trades WHERE order_id = ?", closeOrderId);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'POSITION' AND reference_id = ?
          AND operation_type = 'MARGIN_RELEASE'
        """, fixture.accountId(), longPositionId)).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'ORDER_FILLED'
        """, closeOrderId)).isEqualTo(1);
  }

  @Test
  void tenProtectionsAllowTask9InternalParentButEleventhRejects() {
    Fixture fixture = createFixture("ten-limit");
    settingsService.updateSymbolSettings(
        fixture.userId(), fixture.accountId(), SYMBOL,
        new UpdateSymbolSettingsRequest(null, MarginMode.ISOLATED, null, 0L));
    stubBundle(bundle("99", "101", "100", "100"));
    createOrder(
        fixture, OrderSide.BUY, OrderType.MARKET, "10", null, null,
        PositionSide.BOTH, MarginMode.ISOLATED, false, List.of());
    UUID positionId = openPositionId(fixture.accountId(), PositionSide.BOTH);
    OrderResponse task9Internal = createOrder(
        fixture, OrderSide.SELL, OrderType.LIMIT, "1", "110", null,
        PositionSide.BOTH, MarginMode.ISOLATED, true, List.of());
    assertThat(task9Internal.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(uuid("SELECT parent_position_id FROM trading.orders WHERE id = ?", task9Internal.id()))
        .isEqualTo(positionId);
    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE id = ? AND protection_type IS NULL
        """, task9Internal.id())).isEqualTo(1);

    for (int index = 0; index < 10; index++) {
      createProtection(
          fixture, positionId, ProtectionType.TAKE_PROFIT, "1",
          Integer.toString(111 + index), TriggerExecutionType.MARKET, null);
    }
    MutationState beforeEleventh = mutationState(fixture.accountId(), positionId);

    assertThatThrownBy(() -> createProtection(
        fixture, positionId, ProtectionType.STOP_LOSS, "1", "90",
        TriggerExecutionType.MARKET, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.PROTECTION_LIMIT_EXCEEDED));

    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE parent_position_id = ? AND protection_type IS NOT NULL
          AND status = 'PENDING_ACTIVATION'
        """, positionId)).isEqualTo(10);
    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE parent_position_id = ? AND protection_type IS NULL AND status = 'PENDING'
        """, positionId)).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events e
        JOIN trading.orders o ON o.id = e.order_id
        WHERE o.parent_position_id = ? AND o.protection_type IS NOT NULL
          AND e.event_type = 'PROTECTION_CREATED'
        """, positionId)).isEqualTo(10);
    assertThat(mutationState(fixture.accountId(), positionId)).isEqualTo(beforeEleventh);
  }

  @Test
  void tpSlTotalsAreIndependentAndUpdateUsesOptimisticVersion() {
    Fixture fixture = createFixture("quantity-version");
    stubBundle(bundle("99", "101", "100", "100"));
    createOrder(
        fixture, OrderSide.BUY, OrderType.MARKET, "2", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false, List.of());
    UUID positionId = openPositionId(fixture.accountId(), PositionSide.BOTH);
    OrderResponse takeProfit = createProtection(
        fixture, positionId, ProtectionType.TAKE_PROFIT, "2", "110",
        TriggerExecutionType.MARKET, null);
    OrderResponse stopLoss = createProtection(
        fixture, positionId, ProtectionType.STOP_LOSS, "2", "90",
        TriggerExecutionType.MARKET, null);

    assertThatThrownBy(() -> createProtection(
        fixture, positionId, ProtectionType.TAKE_PROFIT, "0.1", "120",
        TriggerExecutionType.MARKET, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.PROTECTION_QUANTITY_EXCEEDED));
    assertDecimal("2.00000000", "SELECT base_quantity FROM trading.orders WHERE id = ?", takeProfit.id());
    assertDecimal("2.00000000", "SELECT base_quantity FROM trading.orders WHERE id = ?", stopLoss.id());

    OrderResponse updated = protectionOrderService.update(
        fixture.userId(), takeProfit.id(),
        new UpdateProtectionRequest(
            new BigDecimal("1"), QuantityUnit.BASE, new BigDecimal("120"),
            TriggerExecutionType.MARKET, null, 0L));
    assertThat(updated.baseQuantity()).isEqualByComparingTo("1");
    assertThat(longValue("SELECT version FROM trading.orders WHERE id = ?", takeProfit.id()))
        .isEqualTo(1L);

    MutationState beforeStale = mutationState(fixture.accountId(), positionId);
    assertThatThrownBy(() -> protectionOrderService.update(
        fixture.userId(), takeProfit.id(),
        new UpdateProtectionRequest(
            null, null, new BigDecimal("130"), null, null, 0L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.PROTECTION_VERSION_CONFLICT));
    assertThat(mutationState(fixture.accountId(), positionId)).isEqualTo(beforeStale);
    assertDecimal("120.0000000000", "SELECT trigger_price FROM trading.orders WHERE id = ?", takeProfit.id());
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'PROTECTION_UPDATED'
        """, takeProfit.id())).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND operation_type <> 'DEMO_INIT'
        """, fixture.accountId())).isEqualTo(4);
  }

  @Test
  void marketProtectionUsesMarkNotLastAndExecutableBid() {
    Fixture fixture = createFixture("market-trigger");
    stubBundle(bundle("99", "101", "100", "100"));
    createOrder(
        fixture, OrderSide.BUY, OrderType.MARKET, "1", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false, List.of());
    UUID positionId = openPositionId(fixture.accountId(), PositionSide.BOTH);
    OrderResponse protection = createProtection(
        fixture, positionId, ProtectionType.TAKE_PROFIT, "1", "110",
        TriggerExecutionType.MARKET, null);

    stubBundle(bundle("119", "121", "90", "120"));
    assertThat(protectiveService().executeProtectiveOrders()).isEqualTo(1);

    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", protection.id()))
        .isEqualTo("FILLED");
    assertThat(string("SELECT order_type FROM trading.orders WHERE id = ?", protection.id()))
        .isEqualTo("MARKET");
    assertDecimal("110.00000000", "SELECT trigger_price FROM trading.orders WHERE id = ?", protection.id());
    assertDecimal("118.98810000", "SELECT execution_price FROM trading.orders WHERE id = ?", protection.id());
    assertThat(decimal("SELECT execution_price FROM trading.orders WHERE id = ?", protection.id()))
        .isNotEqualByComparingTo("110");
    assertDecimal("118.98810000", "SELECT price FROM trading.trades WHERE order_id = ?", protection.id());
    assertDecimal("17.97800000", "SELECT realized_pnl FROM trading.trades WHERE order_id = ?", protection.id());
    assertDecimal("0.05949405", "SELECT fee FROM trading.trades WHERE order_id = ?", protection.id());
    assertThat(string("SELECT status FROM trading.positions WHERE id = ?", positionId))
        .isEqualTo("CLOSED");
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'PROTECTION_TRIGGERED'
        """, protection.id())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'ORDER_FILLED'
        """, protection.id())).isEqualTo(1);
    assertLedger(positionId, "POSITION", "MARGIN_RELEASE", "10.10101000");
    assertAccount(
        fixture, "50017.86800090", "50017.86800090", "0.00000000", "50017.86800090");
  }

  @Test
  void limitProtectionRestsThenMakerFillsOrCancelsWithOneRelease() {
    Fixture makerFixture = createFixture("limit-maker");
    stubBundle(bundle("99", "101", "100", "100"));
    createOrder(
        makerFixture, OrderSide.BUY, OrderType.MARKET, "1", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false, List.of());
    UUID makerPositionId = openPositionId(makerFixture.accountId(), PositionSide.BOTH);
    OrderResponse makerProtection = createProtection(
        makerFixture, makerPositionId, ProtectionType.TAKE_PROFIT, "1", "110",
        TriggerExecutionType.LIMIT, "121");

    stubBundle(bundle("119", "121", "90", "120"));
    assertThat(protectiveService().executeProtectiveOrders()).isEqualTo(1);
    BigDecimal makerHold = decimal(
        "SELECT hold_amount FROM trading.orders WHERE id = ?", makerProtection.id());
    assertThat(makerHold).isEqualByComparingTo("0.06050000");
    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", makerProtection.id()))
        .isEqualTo("PENDING");
    assertThat(string("SELECT order_type FROM trading.orders WHERE id = ?", makerProtection.id()))
        .isEqualTo("LIMIT");
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'PROTECTION_TRIGGERED'
          AND from_status = 'PENDING_ACTIVATION' AND to_status = 'PENDING'
        """, makerProtection.id())).isEqualTo(1);

    stubBundle(bundle("122", "123", "122", "120"));
    assertThat(pendingService().executePendingOrders()).isEqualTo(1);
    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", makerProtection.id()))
        .isEqualTo("FILLED");
    assertDecimal("0.00000000", "SELECT hold_amount FROM trading.orders WHERE id = ?", makerProtection.id());
    assertDecimal("122.00000000", "SELECT price FROM trading.trades WHERE order_id = ?", makerProtection.id());
    assertThat(string("SELECT liquidity_role FROM trading.trades WHERE order_id = ?", makerProtection.id()))
        .isEqualTo("MAKER");
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'ORDER' AND reference_id = ?
          AND operation_type = 'ORDER_HOLD'
        """, makerFixture.accountId(), makerProtection.id())).isEqualTo(2);
    assertDecimal("0.06100000", """
        SELECT sum(amount) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'ORDER' AND reference_id = ?
          AND operation_type = 'ORDER_HOLD'
        """, makerFixture.accountId(), makerProtection.id());
    assertSingleOrderRelease(
        makerFixture.accountId(), makerProtection.id(), new BigDecimal("0.06100000"));

    Fixture cancelFixture = createFixture("limit-cancel");
    stubBundle(bundle("99", "101", "100", "100"));
    createOrder(
        cancelFixture, OrderSide.BUY, OrderType.MARKET, "1", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false, List.of());
    UUID cancelPositionId = openPositionId(cancelFixture.accountId(), PositionSide.BOTH);
    OrderResponse cancelProtection = createProtection(
        cancelFixture, cancelPositionId, ProtectionType.TAKE_PROFIT, "1", "110",
        TriggerExecutionType.LIMIT, "121");
    AccountState beforeTrigger = accountState(cancelFixture.accountId());

    stubBundle(bundle("119", "121", "90", "120"));
    assertThat(protectiveService().executeProtectiveOrders()).isEqualTo(1);
    BigDecimal cancelHold = decimal(
        "SELECT hold_amount FROM trading.orders WHERE id = ?", cancelProtection.id());
    assertThat(cancelHold).isPositive();
    orderService.cancelOrder(cancelFixture.principal(), cancelProtection.id());

    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", cancelProtection.id()))
        .isEqualTo("CANCELED");
    assertDecimal("0.00000000", "SELECT hold_amount FROM trading.orders WHERE id = ?", cancelProtection.id());
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", cancelProtection.id()))
        .isZero();
    assertSingleOrderRelease(cancelFixture.accountId(), cancelProtection.id(), cancelHold);
    assertThat(accountState(cancelFixture.accountId())).isEqualTo(beforeTrigger);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'PROTECTION_CANCELED'
        """, cancelProtection.id())).isEqualTo(1);
  }

  @Test
  void attachedProtectionsBindAndParentFillFailureRollsBack() {
    Fixture committed = createFixture("attached-commit");
    stubBundle(bundle("99", "101", "100", "100"));
    OrderResponse parent = createOrder(
        committed, OrderSide.BUY, OrderType.MARKET, "1", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false,
        List.of(
            attached(ProtectionType.TAKE_PROFIT, "110", TriggerExecutionType.MARKET, null),
            attached(ProtectionType.STOP_LOSS, "90", TriggerExecutionType.LIMIT, "89")));
    UUID positionId = openPositionId(committed.accountId(), PositionSide.BOTH);

    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE parent_order_id = ? AND parent_position_id = ?
          AND protection_type IS NOT NULL AND status = 'PENDING_ACTIVATION'
          AND order_origin = 'PROTECTIVE'
        """, parent.id(), positionId)).isEqualTo(2);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events e
        JOIN trading.orders o ON o.id = e.order_id
        WHERE o.parent_order_id = ? AND e.event_type = 'PROTECTION_CREATED'
        """, parent.id())).isEqualTo(2);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events e
        JOIN trading.orders o ON o.id = e.order_id
        WHERE o.parent_order_id = ? AND e.event_type = 'PROTECTION_ACTIVATED'
        """, parent.id())).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", parent.id()))
        .isEqualTo(1);

    Fixture rolledBack = createFixture("attached-rollback");
    stubBundle(bundle("99", "101", "100", "100"));
    installEventFailureTrigger("PROTECTION_ACTIVATED");
    try {
      assertThatThrownBy(() -> createOrder(
          rolledBack, OrderSide.BUY, OrderType.MARKET, "1", null, null,
          PositionSide.BOTH, MarginMode.CROSS, false,
          List.of(attached(
              ProtectionType.TAKE_PROFIT, "110", TriggerExecutionType.MARKET, null))))
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("task10 injected PROTECTION_ACTIVATED failure");
    } finally {
      dropEventFailureTrigger();
    }

    assertThat(count("SELECT count(*) FROM trading.orders WHERE account_id = ?", rolledBack.accountId()))
        .isZero();
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", rolledBack.accountId()))
        .isZero();
    assertThat(count("SELECT count(*) FROM trading.positions WHERE account_id = ?", rolledBack.accountId()))
        .isZero();
    assertThat(count("""
        SELECT count(*) FROM trading.order_events e
        JOIN trading.orders o ON o.id = e.order_id
        WHERE o.account_id = ?
        """, rolledBack.accountId())).isZero();
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND operation_type <> 'DEMO_INIT'
        """, rolledBack.accountId())).isZero();
    assertAccount(
        rolledBack, "50000.00000000", "50000.00000000", "0.00000000", "50000.00000000");
  }

  @Test
  void newestProtectionsResizeFirstAndRaceWritesSingleCloseTrade() throws Exception {
    Fixture resizeFixture = createFixture("newest-resize");
    stubBundle(bundle("99", "101", "100", "100"));
    createOrder(
        resizeFixture, OrderSide.BUY, OrderType.MARKET, "3", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false, List.of());
    UUID resizePositionId = openPositionId(resizeFixture.accountId(), PositionSide.BOTH);
    createProtection(
        resizeFixture, resizePositionId, ProtectionType.TAKE_PROFIT, "1", "130",
        TriggerExecutionType.MARKET, null);
    createProtection(
        resizeFixture, resizePositionId, ProtectionType.TAKE_PROFIT, "1", "131",
        TriggerExecutionType.MARKET, null);
    createProtection(
        resizeFixture, resizePositionId, ProtectionType.TAKE_PROFIT, "1", "132",
        TriggerExecutionType.MARKET, null);

    systemCloseOrderService.closeUser(
        resizeFixture.userId(), resizeFixture.accountId(), resizePositionId,
        closeRequest("1.5", "resize-close"));
    List<ProtectionRow> resized = protectionRows(resizePositionId);

    assertThat(resized).hasSize(3);
    assertThat(resized.get(0).status()).isEqualTo("PENDING_ACTIVATION");
    assertThat(resized.get(0).baseQuantity()).isEqualByComparingTo("1");
    assertThat(resized.get(1).status()).isEqualTo("PENDING_ACTIVATION");
    assertThat(resized.get(1).baseQuantity()).isEqualByComparingTo("0.5");
    assertThat(resized.get(1).remainingQuantity()).isEqualByComparingTo("0.5");
    assertThat(resized.get(1).version()).isEqualTo(1L);
    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE id = ? AND quantity = 0.5 AND original_quantity = 0.5
          AND base_quantity = 0.5 AND lots = 0.5 AND remaining_quantity = 0.5
        """, resized.get(1).id())).isEqualTo(1);
    assertThat(resized.get(2).status()).isEqualTo("EXPIRED");
    assertThat(resized.get(2).baseQuantity()).isEqualByComparingTo("0");
    assertThat(resized.get(2).remainingQuantity()).isEqualByComparingTo("0");
    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE id = ? AND quantity = 0 AND original_quantity = 0
          AND base_quantity = 0 AND lots = 0 AND remaining_quantity = 0
          AND hold_amount = 0
        """, resized.get(2).id())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'PROTECTION_RESIZED'
        """, resized.get(1).id())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'PROTECTION_EXPIRED'
        """, resized.get(2).id())).isEqualTo(1);

    Fixture raceFixture = createFixture("terminal-race");
    stubBundle(bundle("99", "101", "100", "100"));
    createOrder(
        raceFixture, OrderSide.BUY, OrderType.MARKET, "1", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false, List.of());
    UUID racePositionId = openPositionId(raceFixture.accountId(), PositionSide.BOTH);
    OrderResponse racedProtection = createProtection(
        raceFixture, racePositionId, ProtectionType.TAKE_PROFIT, "1", "110",
        TriggerExecutionType.MARKET, null);
    stubBundle(bundle("119", "121", "90", "120"));

    CyclicBarrier start = new CyclicBarrier(3);
    ExecutorService workers = Executors.newFixedThreadPool(3);
    Future<Integer> trigger = workers.submit(() -> {
      start.await(5, SECONDS);
      return protectiveService().executeProtectiveOrders();
    });
    Future<Void> cancel = workers.submit(() -> {
      start.await(5, SECONDS);
      try {
        protectionOrderService.cancel(raceFixture.userId(), racedProtection.id());
      } catch (BusinessException ignored) {
        // A committed trigger or manual close is the expected race loser.
      }
      return null;
    });
    Future<Void> manualClose = workers.submit(() -> {
      start.await(5, SECONDS);
      try {
        systemCloseOrderService.closeUser(
            raceFixture.userId(), raceFixture.accountId(), racePositionId,
            closeRequest("1", "manual-race-close"));
      } catch (BusinessException ignored) {
        // A committed protection close is the expected race loser.
      }
      return null;
    });
    try {
      trigger.get(20, SECONDS);
      cancel.get(20, SECONDS);
      manualClose.get(20, SECONDS);
    } finally {
      workers.shutdownNow();
    }

    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", raceFixture.accountId()))
        .isEqualTo(2);
    assertThat(count("""
        SELECT count(*) FROM trading.trades
        WHERE account_id = ? AND side = 'SELL' AND lots = 1
        """, raceFixture.accountId())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'OPEN'
        """, raceFixture.accountId(), SYMBOL)).isZero();
    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'CLOSED'
        """, raceFixture.accountId(), SYMBOL)).isEqualTo(1);
    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", racedProtection.id()))
        .isIn("FILLED", "CANCELED", "EXPIRED");
    assertThat(count("""
        SELECT count(*) FROM trading.order_events e
        JOIN trading.orders o ON o.id = e.order_id
        WHERE o.account_id = ? AND e.event_type = 'ORDER_FILLED'
        """, raceFixture.accountId())).isEqualTo(2);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND operation_type = 'MARGIN_RELEASE'
        """, raceFixture.accountId())).isEqualTo(1);
    assertDecimal("0.00000000", """
        SELECT used_margin FROM core.trading_accounts WHERE id = ?
        """, raceFixture.accountId());
  }

  private PendingOrderExecutionService pendingService() {
    return new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        orderFillService,
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor,
        marketBundleResolver,
        fullFillCoordinator,
        perpetualOrderRiskService,
        accountSymbolSettingRepository,
        symbolRepository,
        perpetualAccountRiskSnapshotService);
  }

  private ProtectiveOrderExecutionService protectiveService() {
    return new ProtectiveOrderExecutionService(
        orderRepository,
        marketBundleResolver,
        protectionOrderService,
        systemCloseOrderService,
        accountRepository,
        demoExecutionGuard,
        orderEventService);
  }

  private Fixture createFixture(String suffix) {
    UUID userId = UUID.randomUUID();
    String email = "task10-it-" + suffix + "-" + userId + "@example.test";
    jdbcTemplate.update("""
        INSERT INTO auth.users (id, email, password_hash, status, role, kyc_status, risk_level)
        VALUES (?, ?, 'not-used', 'ACTIVE', 'USER', 'NOT_SUBMITTED', 'NORMAL')
        """, userId, email);
    TradingAccountEntity account = lifecycleService.getOrCreateDemoAccount(userId);
    Fixture fixture = new Fixture(
        userId,
        account.getId(),
        new UserPrincipal(userId, email, "USER"));
    fixtures.add(fixture);
    return fixture;
  }

  private OrderResponse createOrder(
      Fixture fixture,
      OrderSide side,
      OrderType orderType,
      String quantity,
      String price,
      String trigger,
      PositionSide positionSide,
      MarginMode marginMode,
      boolean reduceOnly,
      List<AttachedProtectionRequest> protections
  ) {
    String key = "task10-" + UUID.randomUUID();
    return orderService.createOrder(
        fixture.principal(),
        new CreateOrderRequest(
            fixture.accountId(),
            SYMBOL,
            side,
            orderType,
            null,
            null,
            null,
            null,
            key,
            key,
            new BigDecimal(quantity),
            price == null ? null : new BigDecimal(price),
            10,
            positionSide,
            QuantityUnit.BASE,
            marginMode,
            trigger == null ? null : new BigDecimal(trigger),
            trigger == null ? null : TriggerPriceType.MARK_PRICE,
            reduceOnly,
            protections));
  }

  private OrderResponse createProtection(
      Fixture fixture,
      UUID positionId,
      ProtectionType type,
      String quantity,
      String trigger,
      TriggerExecutionType executionType,
      String price
  ) {
    return protectionOrderService.create(
        fixture.userId(),
        positionId,
        new CreateProtectionRequest(
            type,
            new BigDecimal(quantity),
            QuantityUnit.BASE,
            new BigDecimal(trigger),
            executionType,
            price == null ? null : new BigDecimal(price),
            "task10-protection-" + UUID.randomUUID()));
  }

  private AttachedProtectionRequest attached(
      ProtectionType type,
      String trigger,
      TriggerExecutionType executionType,
      String price
  ) {
    return new AttachedProtectionRequest(
        type,
        new BigDecimal(trigger),
        TriggerPriceType.MARK_PRICE,
        executionType,
        price == null ? null : new BigDecimal(price));
  }

  private ClosePositionRequest closeRequest(String quantity, String key) {
    return new ClosePositionRequest(new BigDecimal(quantity), QuantityUnit.BASE, key);
  }

  private UUID openPositionId(UUID accountId, PositionSide positionSide) {
    return uuid("""
        SELECT id FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND position_side = ? AND status = 'OPEN'
        """, accountId, SYMBOL, positionSide.name());
  }

  private void stubBundle(PerpetualMarketBundle market) {
    reset(marketBundleResolver);
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(market);
  }

  private PerpetualMarketBundle bundle(String bid, String ask, String last, String mark) {
    Instant now = Instant.now();
    return new PerpetualMarketBundle(
        SYMBOL,
        "BTCUSDT",
        "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal(bid),
        new BigDecimal(ask),
        new BigDecimal(last),
        new BigDecimal(mark),
        new BigDecimal(mark),
        null,
        List.of(),
        List.of(),
        now.minusSeconds(1),
        now.plusSeconds(30));
  }

  private void installEventFailureTrigger(String eventType) {
    if (!"ORDER_FILLED".equals(eventType) && !"PROTECTION_ACTIVATED".equals(eventType)) {
      throw new IllegalArgumentException("Unsupported injected event type");
    }
    dropEventFailureTrigger();
    jdbcTemplate.execute(("""
        CREATE FUNCTION %s() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.event_type = '%s' THEN
            RAISE EXCEPTION 'task10 injected %s failure';
          END IF;
          RETURN NEW;
        END;
        $$
        """).formatted(FAILURE_FUNCTION, eventType, eventType));
    jdbcTemplate.execute(("""
        CREATE TRIGGER %s
        BEFORE INSERT ON trading.order_events
        FOR EACH ROW EXECUTE FUNCTION %s()
        """).formatted(FAILURE_TRIGGER, FAILURE_FUNCTION));
  }

  private void dropEventFailureTrigger() {
    if (jdbcTemplate == null) {
      return;
    }
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + FAILURE_TRIGGER + " ON trading.order_events");
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + FAILURE_FUNCTION + "()");
  }

  private MutationState mutationState(UUID accountId, UUID positionId) {
    return new MutationState(
        count("SELECT count(*) FROM trading.orders WHERE account_id = ?", accountId),
        count("SELECT count(*) FROM trading.trades WHERE account_id = ?", accountId),
        count("""
            SELECT count(*) FROM trading.order_events e
            JOIN trading.orders o ON o.id = e.order_id
            WHERE o.account_id = ?
            """, accountId),
        count("SELECT count(*) FROM ledger.ledger_entries WHERE account_id = ?", accountId),
        accountState(accountId),
        positionState(positionId));
  }

  private AccountState accountState(UUID accountId) {
    return jdbcTemplate.queryForObject("""
        SELECT balance, equity, used_margin, free_margin
        FROM core.trading_accounts WHERE id = ?
        """, (rs, rowNum) -> new AccountState(
        rs.getBigDecimal("balance"),
        rs.getBigDecimal("equity"),
        rs.getBigDecimal("used_margin"),
        rs.getBigDecimal("free_margin")), accountId);
  }

  private PositionState positionState(UUID positionId) {
    return jdbcTemplate.queryForObject("""
        SELECT status, lots, open_price, margin_held, realized_pnl, version
        FROM trading.positions WHERE id = ?
        """, (rs, rowNum) -> new PositionState(
        rs.getString("status"),
        rs.getBigDecimal("lots"),
        rs.getBigDecimal("open_price"),
        rs.getBigDecimal("margin_held"),
        rs.getBigDecimal("realized_pnl"),
        rs.getLong("version")), positionId);
  }

  private List<ProtectionRow> protectionRows(UUID positionId) {
    return jdbcTemplate.query("""
        SELECT id, status, base_quantity, remaining_quantity, version
        FROM trading.orders
        WHERE parent_position_id = ? AND protection_type = 'TAKE_PROFIT'
        ORDER BY created_at, id
        """, (rs, rowNum) -> new ProtectionRow(
        rs.getObject("id", UUID.class),
        rs.getString("status"),
        rs.getBigDecimal("base_quantity"),
        rs.getBigDecimal("remaining_quantity"),
        rs.getLong("version")), positionId);
  }

  private void assertSingleOrderRelease(UUID accountId, UUID orderId, BigDecimal amount) {
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'ORDER' AND reference_id = ?
          AND operation_type = 'ORDER_RELEASE'
        """, accountId, orderId)).isEqualTo(1);
    assertThat(decimal("""
        SELECT amount FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'ORDER' AND reference_id = ?
          AND operation_type = 'ORDER_RELEASE'
        """, accountId, orderId)).isEqualByComparingTo(amount);
  }

  private void assertAccount(
      Fixture fixture,
      String balance,
      String equity,
      String usedMargin,
      String freeMargin
  ) {
    assertDecimal(balance, "SELECT balance FROM core.trading_accounts WHERE id = ?", fixture.accountId());
    assertDecimal(equity, "SELECT equity FROM core.trading_accounts WHERE id = ?", fixture.accountId());
    assertDecimal(usedMargin, "SELECT used_margin FROM core.trading_accounts WHERE id = ?", fixture.accountId());
    assertDecimal(freeMargin, "SELECT free_margin FROM core.trading_accounts WHERE id = ?", fixture.accountId());
  }

  private void assertLedger(
      UUID referenceId,
      String referenceType,
      String operationType,
      String amount
  ) {
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE reference_type = ? AND reference_id = ? AND operation_type = ?
        """, referenceType, referenceId, operationType)).isEqualTo(1);
    assertDecimal(amount, """
        SELECT amount FROM ledger.ledger_entries
        WHERE reference_type = ? AND reference_id = ? AND operation_type = ?
        """, referenceType, referenceId, operationType);
  }

  private void assertDecimal(String expected, String sql, Object... args) {
    assertThat(decimal(sql, args)).isEqualByComparingTo(expected);
  }

  private BigDecimal decimal(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
  }

  private UUID uuid(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, UUID.class, args);
  }

  private String string(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, String.class, args);
  }

  private Boolean bool(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Boolean.class, args);
  }

  private Long longValue(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Long.class, args);
  }

  private long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private record Fixture(UUID userId, UUID accountId, UserPrincipal principal) {
  }

  private record AccountState(
      BigDecimal balance,
      BigDecimal equity,
      BigDecimal usedMargin,
      BigDecimal freeMargin
  ) {
  }

  private record PositionState(
      String status,
      BigDecimal lots,
      BigDecimal openPrice,
      BigDecimal marginHeld,
      BigDecimal realizedPnl,
      long version
  ) {
  }

  private record MutationState(
      long orders,
      long trades,
      long events,
      long ledgerEntries,
      AccountState account,
      PositionState position
  ) {
  }

  private record ProtectionRow(
      UUID id,
      String status,
      BigDecimal baseQuantity,
      BigDecimal remainingQuantity,
      long version
  ) {
  }
}
