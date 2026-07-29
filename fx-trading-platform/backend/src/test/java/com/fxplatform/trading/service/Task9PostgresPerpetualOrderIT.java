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
import com.fxplatform.account.dto.AccountResponse;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.AccountService;
import com.fxplatform.account.service.DemoAccountLifecycleService;
import com.fxplatform.common.exception.BusinessException;
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
import com.fxplatform.trading.dto.request.AdjustPositionMarginRequest;
import com.fxplatform.trading.dto.request.AdjustPositionMarginRequest.Action;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
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
import java.util.Map;
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
class Task9PostgresPerpetualOrderIT {

  private static final String SYMBOL = "BTCUSDT-PERP";

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired AccountService accountService;
  @Autowired DemoAccountLifecycleService lifecycleService;
  @Autowired OrderService orderService;
  @Autowired PositionMarginService positionMarginService;
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
    for (Fixture fixture : fixtures) {
      UUID accountId = fixture.accountId();
      jdbcTemplate.update("DELETE FROM audit.audit_logs WHERE target_id = ?", accountId.toString());
      jdbcTemplate.update("DELETE FROM trading.order_events WHERE order_id IN (SELECT id FROM trading.orders WHERE account_id = ?)", accountId);
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
  void simpleAdvancedOrdersPreserveCrossTableSnapshotsAndIocOnlyAddsTerminalLifecycle() {
    Fixture fixture = createFixture("advanced-snapshots");
    stubBundle(bundle("99", "101", "100", "100"));

    DatabaseState beforePostOnly = databaseState(fixture.userId(), fixture.accountId());
    assertThatThrownBy(() -> orderService.createOrder(
        fixture.principal(),
        advancedLimit(
            fixture.accountId(),
            "101",
            TimeInForce.GTC,
            true,
            "task9-post-only-" + UUID.randomUUID())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("POST_ONLY_WOULD_TAKE"));
    assertThat(databaseState(fixture.userId(), fixture.accountId())).isEqualTo(beforePostOnly);

    DatabaseState beforeFok = databaseState(fixture.userId(), fixture.accountId());
    assertThatThrownBy(() -> orderService.createOrder(
        fixture.principal(),
        advancedLimit(
            fixture.accountId(),
            "98",
            TimeInForce.FOK,
            false,
            "task9-fok-" + UUID.randomUUID())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("FOK_NOT_FILLABLE"));
    assertThat(databaseState(fixture.userId(), fixture.accountId())).isEqualTo(beforeFok);

    DatabaseState beforeIoc = databaseState(fixture.userId(), fixture.accountId());
    OrderResponse canceled = orderService.createOrder(
        fixture.principal(),
        advancedLimit(
            fixture.accountId(),
            "98",
            TimeInForce.IOC,
            false,
            "task9-ioc-" + UUID.randomUUID()));
    DatabaseState afterIoc = databaseState(fixture.userId(), fixture.accountId());

    assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELLED.name());
    assertThat(canceled.filledQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(canceled.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(canceled.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(afterIoc.orders()).hasSize(beforeIoc.orders().size() + 1);
    assertThat(afterIoc.events()).hasSize(beforeIoc.events().size() + 1);
    assertThat(afterIoc.financial()).isEqualTo(beforeIoc.financial());
    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", canceled.id()))
        .isEqualTo("CANCELLED");
    assertDecimal("0.00000000",
        "SELECT filled_quantity FROM trading.orders WHERE id = ?", canceled.id());
    assertDecimal("0.00000000",
        "SELECT remaining_quantity FROM trading.orders WHERE id = ?", canceled.id());
    assertDecimal("0.00000000",
        "SELECT hold_amount FROM trading.orders WHERE id = ?", canceled.id());
    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE id = ? AND order_type = 'LIMIT' AND time_in_force = 'IOC'
          AND post_only = false AND canceled_at IS NOT NULL
        """, canceled.id())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'ORDER_CANCELED'
          AND from_status = 'ACCEPTED' AND to_status = 'CANCELLED'
          AND reason_code IS NULL
        """, canceled.id())).isEqualTo(1);
  }

  @Test
  void concurrentStopLimitActivationPersistsOneTriggerOneFillAndOneTrade() throws Exception {
    Fixture fixture = createFixture("stop-limit-race");
    stubBundle(bundle("99", "101", "100", "100"));
    OrderResponse activation = orderService.createOrder(
        fixture.principal(),
        stopLimit(fixture.accountId(), "task9-stop-limit-race-" + UUID.randomUUID()));
    assertThat(activation.status()).isEqualTo(OrderStatus.PENDING_ACTIVATION.name());

    reset(marketBundleResolver);
    CyclicBarrier bothResolved = new CyclicBarrier(2);
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenAnswer(invocation -> {
          bothResolved.await(5, SECONDS);
          return bundle("99", "101", "100", "100");
        });
    PendingOrderExecutionService service = pendingService();
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Integer> first = workers.submit(service::executePendingOrders);
    Future<Integer> second = workers.submit(service::executePendingOrders);
    int totalFills;
    try {
      totalFills = first.get(15, SECONDS) + second.get(15, SECONDS);
    } finally {
      workers.shutdownNow();
    }

    assertThat(totalFills).isEqualTo(1);
    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", activation.id()))
        .isEqualTo("FILLED");
    assertDecimal("0.00000000",
        "SELECT hold_amount FROM trading.orders WHERE id = ?", activation.id());
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", activation.id()))
        .isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'ORDER_TRIGGERED'
        """, activation.id())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'ORDER_FILLED'
        """, activation.id())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'ORDER' AND reference_id = ?
          AND operation_type = 'ORDER_RELEASE'
        """, fixture.accountId(), activation.id())).isEqualTo(1);
  }

  @Test
  void marketFillPersistsExactAccountPositionTradeAndLedger() {
    Fixture fixture = createFixture("market");
    stubBundle(bundle("99", "101", "100", "100"));

    OrderResponse response = createOrder(
        fixture, OrderSide.BUY, OrderType.MARKET, "1", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    UUID orderId = response.id();
    UUID positionId = uuid("""
        SELECT id FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL);
    UUID tradeId = uuid("SELECT id FROM trading.trades WHERE order_id = ?", orderId);

    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", orderId))
        .isEqualTo("FILLED");
    assertDecimal("101.01010000", "SELECT execution_price FROM trading.orders WHERE id = ?", orderId);
    assertDecimal("1.00000000", "SELECT filled_quantity FROM trading.orders WHERE id = ?", orderId);
    assertDecimal("0.00000000", "SELECT remaining_quantity FROM trading.orders WHERE id = ?", orderId);
    assertDecimal("0.00000000", "SELECT hold_amount FROM trading.orders WHERE id = ?", orderId);
    assertDecimal("0.05050505", "SELECT fee FROM trading.orders WHERE id = ?", orderId);
    assertThat(integer("SELECT leverage FROM trading.orders WHERE id = ?", orderId)).isEqualTo(10);

    assertThat(string("SELECT side FROM trading.positions WHERE id = ?", positionId)).isEqualTo("BUY");
    assertDecimal("1.00000000", "SELECT lots FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("101.01010000", "SELECT open_price FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("100.00000000", "SELECT mark_price FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("100.00000000", "SELECT notional FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("10.10101000", "SELECT initial_margin FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("10.10101000", "SELECT margin_held FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("0.50000000", "SELECT maintenance_margin FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("-1.01010000", "SELECT floating_pnl FROM trading.positions WHERE id = ?", positionId);
    assertThat(integer("SELECT leverage FROM trading.positions WHERE id = ?", positionId)).isEqualTo(10);

    assertDecimal("101.01010000", "SELECT price FROM trading.trades WHERE id = ?", tradeId);
    assertDecimal("0.05050505", "SELECT fee FROM trading.trades WHERE id = ?", tradeId);
    assertThat(string("SELECT liquidity_role FROM trading.trades WHERE id = ?", tradeId))
        .isEqualTo("TAKER");
    assertAccount(fixture, "49999.94949495", "49998.93939495", "10.10101000", "49988.83838495");

    assertLedger(orderId, "ORDER", "ORDER_HOLD", "10.15151505");
    assertLedger(orderId, "ORDER", "ORDER_RELEASE", "10.15151505");
    assertLedger(positionId, "POSITION", "MARGIN_HOLD", "10.10101000");
    assertLedger(tradeId, "TRADE", "TRADE_FEE", "-0.05050505");
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND operation_type IN
          ('ORDER_HOLD', 'ORDER_RELEASE', 'MARGIN_HOLD', 'MARGIN_RELEASE', 'TRADE_FEE')
        """, fixture.accountId())).isEqualTo(4);
  }

  @Test
  void restingLimitHoldsAndCancelReleasesExactlyOnce() {
    Fixture fixture = createFixture("limit-cancel");
    stubBundle(bundle("99", "101", "100", "100"));

    OrderResponse pending = createOrder(
        fixture, OrderSide.BUY, OrderType.LIMIT, "1", "98", null,
        PositionSide.BOTH, MarginMode.CROSS, false);

    assertThat(pending.status()).isEqualTo(OrderStatus.PENDING.name());
    assertDecimal("9.84900000", "SELECT hold_amount FROM trading.orders WHERE id = ?", pending.id());
    assertAccount(fixture, "50000.00000000", "50000.00000000", "9.84900000", "49990.15100000");
    assertLedger(pending.id(), "ORDER", "ORDER_HOLD", "9.84900000");

    OrderResponse canceled = orderService.cancelOrder(fixture.principal(), pending.id());

    assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name());
    assertDecimal("0.00000000", "SELECT hold_amount FROM trading.orders WHERE id = ?", pending.id());
    assertDecimal("0.00000000", "SELECT remaining_quantity FROM trading.orders WHERE id = ?", pending.id());
    assertAccount(fixture, "50000.00000000", "50000.00000000", "0.00000000", "50000.00000000");
    assertLedger(pending.id(), "ORDER", "ORDER_RELEASE", "9.84900000");

    assertThatThrownBy(() -> orderService.cancelOrder(fixture.principal(), pending.id()))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ORDER_NOT_CANCELABLE"));
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'ORDER' AND reference_id = ?
          AND operation_type = 'ORDER_RELEASE'
        """, fixture.accountId(), pending.id())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'ORDER_CANCELED'
        """, pending.id())).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", pending.id())).isZero();
  }

  @Test
  void stopUsesMarkTriggerButFillsAtExecutableAskNotTriggerPrice() {
    Fixture fixture = createFixture("mark-stop");
    stubBundle(bundle("109", "111", "90", "110"));

    OrderResponse pending = createOrder(
        fixture, OrderSide.BUY, OrderType.STOP_MARKET, "1", null, "100",
        PositionSide.BOTH, MarginMode.CROSS, false);

    assertThat(pending.status()).isEqualTo(OrderStatus.PENDING.name());
    assertDecimal("100.00000000", "SELECT trigger_price FROM trading.orders WHERE id = ?", pending.id());
    assertDecimal("11.15661555", "SELECT hold_amount FROM trading.orders WHERE id = ?", pending.id());
    assertThat(string("SELECT trigger_price_type FROM trading.orders WHERE id = ?", pending.id()))
        .isEqualTo("MARK_PRICE");

    assertThat(pendingService().executePendingOrders()).isEqualTo(1);

    UUID positionId = uuid("""
        SELECT id FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL);
    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", pending.id()))
        .isEqualTo("FILLED");
    assertDecimal("111.01110000", "SELECT execution_price FROM trading.orders WHERE id = ?", pending.id());
    assertDecimal("111.01110000", "SELECT avg_fill_price FROM trading.orders WHERE id = ?", pending.id());
    assertThat(decimal("SELECT execution_price FROM trading.orders WHERE id = ?", pending.id()))
        .isNotEqualByComparingTo("100.00000000");
    assertDecimal("111.01110000", "SELECT open_price FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("110.00000000", "SELECT mark_price FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("-1.01110000", "SELECT floating_pnl FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("11.10111000", "SELECT margin_held FROM trading.positions WHERE id = ?", positionId);
    assertAccount(fixture, "49999.94449445", "49998.93339445", "11.10111000", "49987.83228445");
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ? AND price = 111.0111", pending.id()))
        .isEqualTo(1);
  }

  @Test
  void oneWayReduceOnlyGuardRollsBackThenNonReduceOrderReversesSlot() {
    Fixture fixture = createFixture("one-way");
    stubBundle(bundle("99", "101", "100", "100"));

    createOrder(
        fixture, OrderSide.BUY, OrderType.MARKET, "2", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false);
    createOrder(
        fixture, OrderSide.SELL, OrderType.MARKET, "1", null, null,
        PositionSide.BOTH, MarginMode.CROSS, true);
    AccountState beforeRejected = accountState(fixture.accountId());
    long ledgerBeforeRejected = tradingLedgerCount(fixture.accountId());

    assertThatThrownBy(() -> createOrder(
        fixture, OrderSide.SELL, OrderType.MARKET, "2", null, null,
        PositionSide.BOTH, MarginMode.CROSS, true))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("REDUCE_ONLY_EXCEEDS_POSITION"));

    assertThat(count("SELECT count(*) FROM trading.orders WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(2);
    assertThat(accountState(fixture.accountId())).isEqualTo(beforeRejected);
    assertThat(tradingLedgerCount(fixture.accountId())).isEqualTo(ledgerBeforeRejected);

    createOrder(
        fixture, OrderSide.SELL, OrderType.MARKET, "2", null, null,
        PositionSide.BOTH, MarginMode.CROSS, false);

    UUID openPositionId = uuid("""
        SELECT id FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL);
    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL)).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'CLOSED'
        """, fixture.accountId(), SYMBOL)).isEqualTo(1);
    assertThat(string("SELECT side FROM trading.positions WHERE id = ?", openPositionId))
        .isEqualTo("SELL");
    assertDecimal("1.00000000", "SELECT lots FROM trading.positions WHERE id = ?", openPositionId);
    assertDecimal("98.99010000", "SELECT open_price FROM trading.positions WHERE id = ?", openPositionId);
    assertDecimal("-1.00990000", "SELECT floating_pnl FROM trading.positions WHERE id = ?", openPositionId);
    assertDecimal("9.89901000", "SELECT margin_held FROM trading.positions WHERE id = ?", openPositionId);
    assertThat(count("SELECT count(*) FROM trading.orders WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(3);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(3);
    assertDecimal("-4.04000000", """
        SELECT sum(realized_pnl) FROM trading.trades WHERE account_id = ?
        """, fixture.accountId());
    assertAccount(fixture, "49995.71050475", "49994.70060475", "9.89901000", "49984.80159475");
  }

  @Test
  void hedgeModePersistsIndependentLongAndShortSlots() {
    Fixture fixture = createFixture("hedge");
    settingsService.updatePositionMode(
        fixture.userId(), fixture.accountId(), new UpdatePositionModeRequest(PositionMode.HEDGE));
    stubBundle(bundle("99", "101", "100", "100"));

    createOrder(
        fixture, OrderSide.BUY, OrderType.MARKET, "1", null, null,
        PositionSide.LONG, MarginMode.CROSS, false);
    createOrder(
        fixture, OrderSide.SELL, OrderType.MARKET, "2", null, null,
        PositionSide.SHORT, MarginMode.CROSS, false);

    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND position_mode = 'HEDGE' AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL)).isEqualTo(2);
    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND position_side = 'LONG'
          AND side = 'BUY' AND lots = 1 AND open_price = 101.0101
          AND margin_held = 10.10101 AND floating_pnl = -1.0101 AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL)).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND position_side = 'SHORT'
          AND side = 'SELL' AND lots = 2 AND open_price = 98.9901
          AND margin_held = 19.79802 AND floating_pnl = -2.0198 AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(2);
    assertDecimal("29.89903000", """
        SELECT sum(margin_held) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL);
    assertAccount(fixture, "49999.85050485", "49996.82060485", "29.89903000", "49966.92157485");
  }

  @Test
  void isolatedAddAndSafeReduceCommitWhileUnsafeReduceRollsBackAllMutation() {
    Fixture fixture = createFixture("isolated-margin");
    settingsService.updateSymbolSettings(
        fixture.userId(), fixture.accountId(), SYMBOL,
        new UpdateSymbolSettingsRequest(null, MarginMode.ISOLATED, null, 0L));
    stubBundle(bundle("99", "101", "100", "100"));
    OrderResponse opened = createOrder(
        fixture, OrderSide.BUY, OrderType.MARKET, "1", null, null,
        PositionSide.BOTH, MarginMode.ISOLATED, false);
    UUID positionId = uuid("""
        SELECT id FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL);

    positionMarginService.adjust(
        fixture.userId(), positionId,
        new AdjustPositionMarginRequest(Action.ADD, new BigDecimal("5.00000000"), 0L));
    positionMarginService.adjust(
        fixture.userId(), positionId,
        new AdjustPositionMarginRequest(Action.REDUCE, new BigDecimal("2.00000000"), 1L));

    AccountState beforeUnsafe = accountState(fixture.accountId());
    PositionState positionBeforeUnsafe = positionState(positionId);
    long ledgerBeforeUnsafe = tradingLedgerCount(fixture.accountId());
    assertThatThrownBy(() -> positionMarginService.adjust(
        fixture.userId(), positionId,
        new AdjustPositionMarginRequest(Action.REDUCE, new BigDecimal("12.60000000"), 2L)))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MARGIN_REDUCTION_UNSAFE"));

    assertThat(accountState(fixture.accountId())).isEqualTo(beforeUnsafe);
    assertThat(positionState(positionId)).isEqualTo(positionBeforeUnsafe);
    assertThat(tradingLedgerCount(fixture.accountId())).isEqualTo(ledgerBeforeUnsafe);
    assertThat(positionBeforeUnsafe.marginHeld()).isEqualByComparingTo("13.10101000");
    assertThat(positionBeforeUnsafe.initialMargin()).isEqualByComparingTo("10.10101000");
    assertThat(positionBeforeUnsafe.maintenanceMargin()).isEqualByComparingTo("0.50000000");
    assertThat(positionBeforeUnsafe.floatingPnl()).isEqualByComparingTo("-1.01010000");
    assertThat(positionBeforeUnsafe.version()).isEqualTo(2L);
    assertThat(string("SELECT margin_mode FROM trading.positions WHERE id = ?", positionId))
        .isEqualTo("ISOLATED");
    assertAccount(fixture, "49999.94949495", "49998.93939495", "13.10101000", "49986.84848495");
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'POSITION' AND reference_id = ?
          AND operation_type = 'MARGIN_HOLD'
        """, fixture.accountId(), positionId)).isEqualTo(2);
    assertDecimal("15.10101000", """
        SELECT sum(amount) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'POSITION' AND reference_id = ?
          AND operation_type = 'MARGIN_HOLD'
        """, fixture.accountId(), positionId);
    assertLedger(positionId, "POSITION", "MARGIN_RELEASE", "2.00000000");
    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", opened.id())).isEqualTo("FILLED");
  }

  @Test
  void pendingFillUsesCurrentLeverageAndRejectsGapBeforeAnyWrite() {
    Fixture fixture = createFixture("pending-authority");
    stubBundle(bundle("99", "101", "100", "100"));
    OrderResponse pending = createOrder(
        fixture, OrderSide.BUY, OrderType.LIMIT, "1", "98", null,
        PositionSide.BOTH, MarginMode.CROSS, false);
    assertDecimal("9.84900000", "SELECT hold_amount FROM trading.orders WHERE id = ?", pending.id());

    settingsService.updateSymbolSettings(
        fixture.userId(), fixture.accountId(), SYMBOL,
        new UpdateSymbolSettingsRequest(5, null, null, 0L));
    assertThat(integer("""
        SELECT leverage FROM trading.account_symbol_settings
        WHERE account_id = ? AND symbol = ?
        """, fixture.accountId(), SYMBOL)).isEqualTo(5);
    assertThat(longValue("""
        SELECT version FROM trading.account_symbol_settings
        WHERE account_id = ? AND symbol = ?
        """, fixture.accountId(), SYMBOL)).isEqualTo(1L);
    stubBundle(bundle("97", "98", "100", "100"));
    AccountState beforeGap = accountState(fixture.accountId());
    long ledgerBeforeGap = tradingLedgerCount(fixture.accountId());

    assertThat(pendingService().executePendingOrders()).isZero();

    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", pending.id()))
        .isEqualTo("PENDING");
    assertDecimal("9.84900000", "SELECT hold_amount FROM trading.orders WHERE id = ?", pending.id());
    assertThat(integer("SELECT leverage FROM trading.orders WHERE id = ?", pending.id())).isEqualTo(10);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", pending.id())).isZero();
    assertThat(count("SELECT count(*) FROM trading.positions WHERE account_id = ?", fixture.accountId())).isZero();
    assertThat(accountState(fixture.accountId())).isEqualTo(beforeGap);
    assertThat(tradingLedgerCount(fixture.accountId())).isEqualTo(ledgerBeforeGap);

    settingsService.updateSymbolSettings(
        fixture.userId(), fixture.accountId(), SYMBOL,
        new UpdateSymbolSettingsRequest(20, null, null, 1L));
    assertThat(integer("""
        SELECT leverage FROM trading.account_symbol_settings
        WHERE account_id = ? AND symbol = ?
        """, fixture.accountId(), SYMBOL)).isEqualTo(20);
    assertThat(longValue("""
        SELECT version FROM trading.account_symbol_settings
        WHERE account_id = ? AND symbol = ?
        """, fixture.accountId(), SYMBOL)).isEqualTo(2L);
    assertThat(pendingService().executePendingOrders()).isEqualTo(1);

    UUID positionId = uuid("""
        SELECT id FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL);
    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", pending.id()))
        .isEqualTo("FILLED");
    assertThat(integer("SELECT leverage FROM trading.orders WHERE id = ?", pending.id())).isEqualTo(10);
    assertThat(integer("SELECT leverage FROM trading.positions WHERE id = ?", positionId)).isEqualTo(20);
    assertDecimal("98.00000000", "SELECT open_price FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("4.90000000", "SELECT initial_margin FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("4.90000000", "SELECT margin_held FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("2.00000000", "SELECT floating_pnl FROM trading.positions WHERE id = ?", positionId);
    assertDecimal("0.01960000", "SELECT fee FROM trading.orders WHERE id = ?", pending.id());
    assertThat(string("SELECT liquidity_role FROM trading.orders WHERE id = ?", pending.id()))
        .isEqualTo("MAKER");
    assertAccount(fixture, "49999.98040000", "50001.98040000", "4.90000000", "49997.08040000");
    assertLedger(pending.id(), "ORDER", "ORDER_HOLD", "9.84900000");
    assertLedger(pending.id(), "ORDER", "ORDER_RELEASE", "9.84900000");
    assertLedger(positionId, "POSITION", "MARGIN_HOLD", "4.90000000");
  }

  @Test
  void concurrentPendingWorkersPersistSingleTradeAndSingleRelease() throws Exception {
    Fixture fixture = createFixture("pending-race");
    stubBundle(bundle("99", "101", "100", "100"));
    OrderResponse pending = createOrder(
        fixture, OrderSide.BUY, OrderType.LIMIT, "1", "98", null,
        PositionSide.BOTH, MarginMode.CROSS, false);

    reset(marketBundleResolver);
    CyclicBarrier bothResolved = new CyclicBarrier(2);
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenAnswer(invocation -> {
          bothResolved.await(5, SECONDS);
          return bundle("97", "98", "100", "100");
        });
    PendingOrderExecutionService service = pendingService();
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Integer> first = workers.submit(service::executePendingOrders);
    Future<Integer> second = workers.submit(service::executePendingOrders);
    int totalFills;
    try {
      totalFills = first.get(15, SECONDS) + second.get(15, SECONDS);
    } finally {
      workers.shutdownNow();
    }

    UUID positionId = uuid("""
        SELECT id FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL);
    assertThat(totalFills).isEqualTo(1);
    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", pending.id()))
        .isEqualTo("FILLED");
    assertDecimal("0.00000000", "SELECT hold_amount FROM trading.orders WHERE id = ?", pending.id());
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", pending.id()))
        .isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'ORDER' AND reference_id = ?
          AND operation_type = 'ORDER_RELEASE'
        """, fixture.accountId(), pending.id())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'POSITION' AND reference_id = ?
          AND operation_type = 'MARGIN_HOLD'
        """, fixture.accountId(), positionId)).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'ORDER_FILLED'
        """, pending.id())).isEqualTo(1);
    assertAccount(fixture, "49999.98040000", "50001.98040000", "9.80000000", "49992.18040000");
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

  private Fixture createFixture(String suffix) {
    UUID userId = UUID.randomUUID();
    String email = "task9-it-" + suffix + "-" + userId + "@example.test";
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
      boolean reduceOnly
  ) {
    String key = "task9-" + UUID.randomUUID();
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
            List.of()));
  }

  private CreateOrderRequest advancedLimit(
      UUID accountId,
      String price,
      TimeInForce timeInForce,
      boolean postOnly,
      String key
  ) {
    return new CreateOrderRequest(
        accountId,
        SYMBOL,
        OrderSide.BUY,
        OrderType.LIMIT,
        null,
        null,
        null,
        null,
        key,
        key,
        BigDecimal.ONE,
        new BigDecimal(price),
        10,
        PositionSide.BOTH,
        QuantityUnit.BASE,
        MarginMode.CROSS,
        null,
        null,
        false,
        List.of(),
        timeInForce,
        postOnly,
        null,
        null,
        null);
  }

  private CreateOrderRequest stopLimit(UUID accountId, String key) {
    return new CreateOrderRequest(
        accountId,
        SYMBOL,
        OrderSide.BUY,
        OrderType.STOP_LIMIT,
        null,
        null,
        null,
        null,
        key,
        key,
        BigDecimal.ONE,
        new BigDecimal("101"),
        10,
        PositionSide.BOTH,
        QuantityUnit.BASE,
        MarginMode.CROSS,
        new BigDecimal("100"),
        TriggerPriceType.MARK_PRICE,
        false,
        List.of(),
        TimeInForce.GTC,
        false,
        null,
        null,
        null);
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

  private DatabaseState databaseState(UUID userId, UUID accountId) {
    FinancialState financial = new FinancialState(
        rows("SELECT * FROM trading.trades WHERE account_id = ? ORDER BY id", accountId),
        rows("SELECT * FROM trading.positions WHERE account_id = ? ORDER BY id", accountId),
        rows("SELECT * FROM trading.spot_positions WHERE account_id = ? ORDER BY id", accountId),
        rows("SELECT * FROM ledger.asset_ledger_entries WHERE account_id = ? ORDER BY id", accountId),
        rows("SELECT * FROM ledger.ledger_entries WHERE account_id = ? ORDER BY id", accountId),
        jdbcTemplate.query("""
            SELECT wallet_type, asset, total, available, locked
            FROM core.wallet_balances
            WHERE account_id = ?
            ORDER BY wallet_type, asset
            """, (rs, rowNum) -> new WalletState(
                rs.getString("wallet_type"),
                rs.getString("asset"),
                canonical(rs.getBigDecimal("total")),
                canonical(rs.getBigDecimal("available")),
                canonical(rs.getBigDecimal("locked"))), accountId),
        canonicalAccountState(accountState(accountId)),
        accountSummaryState(accountService.summary(userId, accountId)));
    return new DatabaseState(
        rows("SELECT * FROM trading.orders WHERE account_id = ? ORDER BY id", accountId),
        rows("""
            SELECT * FROM trading.order_events
            WHERE order_id IN (SELECT id FROM trading.orders WHERE account_id = ?)
            ORDER BY id
            """, accountId),
        financial);
  }

  private List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbcTemplate.queryForList(sql, args);
  }

  private AccountSummaryState accountSummaryState(AccountResponse response) {
    return new AccountSummaryState(
        canonical(response.balance()),
        canonical(response.equity()),
        canonical(response.usedMargin()),
        canonical(response.freeMargin()),
        canonical(response.marginLevel()),
        canonical(response.openFloatingPnl()),
        canonical(response.maintenanceMargin()),
        canonical(response.positionValue()),
        canonical(response.marginAvailable()));
  }

  private AccountState canonicalAccountState(AccountState state) {
    return new AccountState(
        canonical(state.balance()),
        canonical(state.equity()),
        canonical(state.usedMargin()),
        canonical(state.freeMargin()));
  }

  private PositionState positionState(UUID positionId) {
    return jdbcTemplate.queryForObject("""
        SELECT margin_held, initial_margin, maintenance_margin, floating_pnl, version
        FROM trading.positions WHERE id = ?
        """, (rs, rowNum) -> new PositionState(
            rs.getBigDecimal("margin_held"),
            rs.getBigDecimal("initial_margin"),
            rs.getBigDecimal("maintenance_margin"),
            rs.getBigDecimal("floating_pnl"),
            rs.getLong("version")), positionId);
  }

  private long tradingLedgerCount(UUID accountId) {
    return count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND operation_type <> 'DEMO_INIT'
        """, accountId);
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

  private Integer integer(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  private Long longValue(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Long.class, args);
  }

  private long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private static BigDecimal canonical(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros();
  }

  private record Fixture(UUID userId, UUID accountId, UserPrincipal principal) {
  }

  private record DatabaseState(
      List<Map<String, Object>> orders,
      List<Map<String, Object>> events,
      FinancialState financial
  ) {
  }

  private record FinancialState(
      List<Map<String, Object>> trades,
      List<Map<String, Object>> perpetualPositions,
      List<Map<String, Object>> spotPositions,
      List<Map<String, Object>> assetLedger,
      List<Map<String, Object>> cashLedger,
      List<WalletState> wallets,
      AccountState account,
      AccountSummaryState accountSummary
  ) {
  }

  private record WalletState(
      String walletType,
      String asset,
      BigDecimal total,
      BigDecimal available,
      BigDecimal locked
  ) {
  }

  private record AccountSummaryState(
      BigDecimal balance,
      BigDecimal equity,
      BigDecimal usedMargin,
      BigDecimal freeMargin,
      BigDecimal marginLevel,
      BigDecimal openFloatingPnl,
      BigDecimal maintenanceMargin,
      BigDecimal positionValue,
      BigDecimal marginAvailable
  ) {
  }

  private record AccountState(
      BigDecimal balance,
      BigDecimal equity,
      BigDecimal usedMargin,
      BigDecimal freeMargin
  ) {
  }

  private record PositionState(
      BigDecimal marginHeld,
      BigDecimal initialMargin,
      BigDecimal maintenanceMargin,
      BigDecimal floatingPnl,
      long version
  ) {
  }
}
