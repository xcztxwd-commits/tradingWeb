package com.fxplatform.trading.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AccountResponse;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.AccountService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoExecutionPolicyProvider;
import com.fxplatform.execution.DemoFillIdentity;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL transaction and locking proofs for resting Spot orders in DEPTH mode. */
@SpringBootTest(properties = {
    "execution.mode=demo",
    "execution.demo.matching-mode=DEPTH",
    "spring.datasource.password=database-it-testcontainers-password",
    "spring.task.scheduling.enabled=false",
    "trading.pending-order-execution-enabled=false",
    "trading.protective-order-execution-enabled=false"
})
@ActiveProfiles("database-it")
@Testcontainers(disabledWithoutDocker = true)
@Import(DepthPendingExecutionPostgresIT.DepthPolicyConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class DepthPendingExecutionPostgresIT {

  private static final String SYMBOL = "BTCUSDT";
  private static final BigDecimal ZERO = new BigDecimal("0.00000000");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TradingAccountRepository accountRepository;
  @Autowired private WalletBalanceRepository walletBalanceRepository;
  @Autowired private OrderRepository orderRepository;
  @Autowired private OrderService orderService;
  @Autowired private PendingOrderExecutionProcessor pendingOrderExecutionProcessor;
  @Autowired private AccountService accountService;
  @Autowired private MutableDepthPolicyProvider policyProvider;

  @MockBean private MarketBundleResolver marketBundleResolver;

  private final List<Fixture> fixtures = new ArrayList<>();

  @BeforeEach
  void resetDepthTick() {
    reset(marketBundleResolver);
    policyProvider.clearInsideLockProbe();
    policyProvider.set(restingPolicy());
    dropSecondFillFailureTrigger();
  }

  @AfterEach
  void cleanFixtures() {
    dropSecondFillFailureTrigger();
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
  void secondFillDatabaseFailureRollsBackTheWholeDepthTick() {
    Fixture fixture = createFixture("second-fill-rollback", "200.00000000");
    OrderEntity pending = placeRestingBuy(fixture, "1.0000", "99");
    DatabaseState before = databaseState(fixture);
    policyProvider.set(twoLevelFillPolicy(null));
    installSecondFillFailureTrigger(pending.getId());

    assertThatThrownBy(() -> pendingOrderExecutionProcessor.process(pending, tick()))
        .hasStackTraceContaining("depth pending IT injected second fill failure");

    assertThat(databaseState(fixture)).isEqualTo(before);
    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", pending.getId()))
        .isEqualTo(OrderStatus.PENDING.name());
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", pending.getId()))
        .isZero();
  }

  @Test
  void twoWorkersOnOneOrderAndOneTickCommitExactlyOneBatch() throws Exception {
    Fixture fixture = createFixture("same-order-workers", "200.00000000");
    AccountSummaryState summaryBefore = accountSummary(fixture);
    OrderEntity pending = placeRestingBuy(fixture, "1.0000", "99");
    policyProvider.set(twoLevelFillPolicy(null));
    ExecutableMarketSnapshot snapshot = tick();
    policyProvider.armInsideLockProbe();
    RaceGate gate = new RaceGate(2);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Boolean> first = workers.submit(() -> processAfterGate(pending, snapshot, gate));
    Future<Boolean> second = workers.submit(() -> processAfterGate(pending, snapshot, gate));

    List<Boolean> outcomes;
    try {
      gate.release();
      outcomes = List.of(first.get(20, SECONDS), second.get(20, SECONDS));
    } finally {
      workers.shutdownNow();
    }

    assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    assertThat(policyProvider.insideLockArrivals())
        .as("only the lock winner may reach DEPTH planning with the stale candidate")
        .isEqualTo(1);
    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", pending.getId()))
        .isEqualTo(OrderStatus.FILLED.name());
    assertDecimal("1.00000000",
        "SELECT filled_quantity FROM trading.orders WHERE id = ?", pending.getId());
    assertDecimal("0.00000000",
        "SELECT remaining_quantity FROM trading.orders WHERE id = ?", pending.getId());
    assertDecimal("0.00000000",
        "SELECT hold_amount FROM trading.orders WHERE id = ?", pending.getId());
    assertDecimal("98.60000000",
        "SELECT avg_fill_price FROM trading.orders WHERE id = ?", pending.getId());
    assertDecimal("0.09860000",
        "SELECT fee FROM trading.orders WHERE id = ?", pending.getId());
    assertThat(fillIdentities(pending.getId())).containsExactlyInAnyOrder(
        DemoFillIdentity.forSnapshot(pending.getId(), snapshot, 0),
        DemoFillIdentity.forSnapshot(pending.getId(), snapshot, 1));
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", pending.getId()))
        .isEqualTo(2);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type IN ('ORDER_PARTIALLY_FILLED', 'ORDER_FILLED')
        """, pending.getId())).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM trading.spot_positions WHERE account_id = ?",
        fixture.accountId())).isEqualTo(1);
    assertDecimal("1.00000000",
        "SELECT quantity FROM trading.spot_positions WHERE account_id = ?", fixture.accountId());
    assertDecimal("98.60000000",
        "SELECT average_cost FROM trading.spot_positions WHERE account_id = ?", fixture.accountId());
    assertDecimal("0.09860000",
        "SELECT fee_cost FROM trading.spot_positions WHERE account_id = ?", fixture.accountId());
    assertThat(count("SELECT count(*) FROM ledger.asset_ledger_entries WHERE account_id = ?",
        fixture.accountId())).isEqualTo(8);
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND operation_type = 'SPOT_ORDER_LOCK'
        """, fixture.accountId())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND operation_type = 'SPOT_BUY_DEBIT'
        """, fixture.accountId())).isEqualTo(2);
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND operation_type = 'TRADE_FEE'
        """, fixture.accountId())).isEqualTo(2);
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND operation_type = 'SPOT_BUY_CREDIT'
        """, fixture.accountId())).isEqualTo(2);
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND operation_type = 'SPOT_ORDER_RELEASE'
        """, fixture.accountId())).isEqualTo(1);
    assertDecimal("0.49940000", """
        SELECT sum(amount) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND operation_type = 'SPOT_ORDER_RELEASE'
        """, fixture.accountId());
    assertWallet(fixture.accountId(), "USDT", "101.30140000", "101.30140000", "0.00000000");
    assertWallet(fixture.accountId(), "BTC", "1.00000000", "1.00000000", "0.00000000");
    assertWalletInvariants(fixture.accountId());
    assertThat(accountSummary(fixture)).isEqualTo(summaryBefore);
  }

  @Test
  void concurrentMarketBuysOnOneAccountCannotOverspend() throws Exception {
    Fixture fixture = createFixture("same-account-buys", "100.20000000");
    AccountSummaryState summaryBefore = accountSummary(fixture);
    policyProvider.set(marketBuyPolicy());
    CyclicBarrier bothResolved = new CyclicBarrier(2);
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any(CandleRequest.class)))
        .thenAnswer(invocation -> {
          bothResolved.await(10, SECONDS);
          return spotBundle();
        });
    RaceGate gate = new RaceGate(2);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<CreateAttempt> first = workers.submit(
        () -> createAfterGate(fixture, marketBuy(fixture.accountId(), uniqueKey("market-a")), gate));
    Future<CreateAttempt> second = workers.submit(
        () -> createAfterGate(fixture, marketBuy(fixture.accountId(), uniqueKey("market-b")), gate));

    List<CreateAttempt> attempts;
    try {
      gate.release();
      attempts = List.of(first.get(20, SECONDS), second.get(20, SECONDS));
    } finally {
      workers.shutdownNow();
    }

    assertThat(attempts).filteredOn(CreateAttempt::succeeded).singleElement()
        .satisfies(attempt -> assertThat(attempt.response().status())
            .isEqualTo(OrderStatus.FILLED.name()));
    assertThat(attempts).filteredOn(attempt -> !attempt.succeeded()).singleElement()
        .satisfies(attempt -> assertThat(attempt.failure().getCode())
            .isEqualTo("INSUFFICIENT_BALANCE"));
    assertThat(count("SELECT count(*) FROM trading.orders WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND entry_type = 'SPOT_ORDER_LOCK'
        """, fixture.accountId())).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM ledger.asset_ledger_entries WHERE account_id = ?",
        fixture.accountId())).isEqualTo(4);
    assertWallet(fixture.accountId(), "USDT", "0.00000000", "0.00000000", "0.00000000");
    assertWallet(fixture.accountId(), "BTC", "1.00000000", "1.00000000", "0.00000000");
    assertWalletInvariants(fixture.accountId());
    assertThat(accountSummary(fixture)).isEqualTo(summaryBefore);
  }

  @Test
  void fillVersusCancelCommitsPartialFillFirstAndReleasesOnlyTheRemainingHold() throws Exception {
    Fixture fixture = createFixture("fill-cancel", "200.00000000");
    AccountSummaryState summaryBefore = accountSummary(fixture);
    OrderEntity pending = placeRestingBuy(fixture, "1.0000", "99");
    policyProvider.set(partialFillPolicy());
    ExecutableMarketSnapshot snapshot = tick();
    policyProvider.armInsideLockHold();
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Boolean> fill = workers.submit(
        () -> pendingOrderExecutionProcessor.process(pending, snapshot));
    policyProvider.awaitInsideLockArrival();
    CountDownLatch cancelInvoking = new CountDownLatch(1);
    Future<OrderResponse> cancel = workers.submit(() -> {
      cancelInvoking.countDown();
      return orderService.cancelOrder(fixture.principal(), pending.getId());
    });

    boolean fillWon;
    OrderResponse canceled;
    try {
      await(cancelInvoking, "cancel worker invocation");
      assertThat(cancel.isDone()).isFalse();
      policyProvider.releaseInsideLockHold();
      fillWon = fill.get(20, SECONDS);
      canceled = cancel.get(20, SECONDS);
    } finally {
      policyProvider.releaseInsideLockHold();
      workers.shutdownNow();
    }

    long trades = count("SELECT count(*) FROM trading.trades WHERE order_id = ?", pending.getId());
    assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name());
    assertThat(fillWon).isTrue();
    assertThat(trades).isEqualTo(1);
    assertThat(string("SELECT status FROM trading.orders WHERE id = ?", pending.getId()))
        .isEqualTo(OrderStatus.CANCELED.name());
    assertDecimal("0.40000000",
        "SELECT filled_quantity FROM trading.orders WHERE id = ?", pending.getId());
    assertDecimal("0.00000000",
        "SELECT remaining_quantity FROM trading.orders WHERE id = ?", pending.getId());
    assertDecimal("0.00000000",
        "SELECT hold_amount FROM trading.orders WHERE id = ?", pending.getId());
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'ORDER_CANCELED'
        """, pending.getId())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND entry_type = 'SPOT_ORDER_RELEASE'
          AND reference_type = 'ORDER' AND reference_id = ?
        """, fixture.accountId(), pending.getId())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND entry_type = 'SPOT_ORDER_RELEASE'
        """, fixture.accountId())).isEqualTo(2);
    assertDecimal("59.51880000", """
        SELECT amount FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND entry_type = 'SPOT_ORDER_RELEASE'
          AND reference_type = 'ORDER' AND reference_id = ?
        """, fixture.accountId(), pending.getId());
    assertDecimal("0.44000000", """
        SELECT amount FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND entry_type = 'SPOT_ORDER_RELEASE'
          AND reference_type = 'TRADE'
        """, fixture.accountId());
    assertWallet(fixture.accountId(), "USDT", "160.76080000", "160.76080000", "0.00000000");
    assertWallet(fixture.accountId(), "BTC", "0.40000000", "0.40000000", "0.00000000");
    assertWalletInvariants(fixture.accountId());
    assertThat(accountSummary(fixture)).isEqualTo(summaryBefore);
  }

  private boolean processAfterGate(
      OrderEntity pending,
      ExecutableMarketSnapshot snapshot,
      RaceGate gate
  ) {
    gate.awaitStart();
    return pendingOrderExecutionProcessor.process(pending, snapshot);
  }

  private CreateAttempt createAfterGate(
      Fixture fixture,
      CreateOrderRequest request,
      RaceGate gate
  ) {
    gate.awaitStart();
    try {
      return CreateAttempt.success(orderService.createOrder(fixture.principal(), request));
    } catch (BusinessException exception) {
      return CreateAttempt.failure(exception);
    }
  }

  private Fixture createFixture(String suffix, String usdtAvailable) {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    String email = "depth-pending-it-" + suffix + "-" + userId + "@example.test";
    jdbcTemplate.update("""
        INSERT INTO auth.users (id, email, password_hash, status, role, kyc_status, risk_level)
        VALUES (?, ?, 'not-used', 'ACTIVE', 'USER', 'NOT_SUBMITTED', 'NORMAL')
        """, userId, email);

    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("10000.00000000"));
    account.setEquity(new BigDecimal("10000.00000000"));
    account.setUsedMargin(ZERO);
    account.setFreeMargin(new BigDecimal("10000.00000000"));
    account.setLeverage(1);
    account.setStatus(AccountStatus.ACTIVE);
    accountRepository.save(account);
    saveWallet(accountId, "USDT", new BigDecimal(usdtAvailable));
    saveWallet(accountId, "BTC", ZERO);

    Fixture fixture = new Fixture(
        userId, accountId, new UserPrincipal(userId, email, "USER"));
    fixtures.add(fixture);
    return fixture;
  }

  private void saveWallet(UUID accountId, String asset, BigDecimal available) {
    WalletBalanceEntity wallet = new WalletBalanceEntity();
    wallet.setId(UUID.randomUUID());
    wallet.setAccountId(accountId);
    wallet.setWalletType("SPOT");
    wallet.setAsset(asset);
    wallet.setTotal(available);
    wallet.setAvailable(available);
    wallet.setLocked(ZERO);
    walletBalanceRepository.save(wallet);
  }

  private OrderEntity placeRestingBuy(Fixture fixture, String quantity, String price) {
    policyProvider.set(restingPolicy());
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(spotBundle());
    OrderResponse response = orderService.createOrder(
        fixture.principal(),
        limitBuy(fixture.accountId(), quantity, price, uniqueKey("resting")));
    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    return orderRepository.findById(response.id()).orElseThrow();
  }

  private CreateOrderRequest limitBuy(
      UUID accountId,
      String quantity,
      String price,
      String key
  ) {
    return request(
        accountId,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        new BigDecimal(quantity),
        new BigDecimal(price),
        key);
  }

  private CreateOrderRequest marketBuy(UUID accountId, String key) {
    return request(
        accountId,
        OrderType.MARKET,
        QuantityUnit.QUOTE,
        new BigDecimal("100.20000000"),
        null,
        key);
  }

  private CreateOrderRequest request(
      UUID accountId,
      OrderType orderType,
      QuantityUnit quantityUnit,
      BigDecimal quantity,
      BigDecimal price,
      String key
  ) {
    return new CreateOrderRequest(
        accountId,
        SYMBOL,
        OrderSide.BUY,
        orderType,
        null,
        null,
        null,
        null,
        key,
        key,
        quantity,
        price,
        1,
        PositionSide.BOTH,
        quantityUnit,
        MarginMode.CASH,
        null,
        null,
        false,
        List.of(),
        TimeInForce.GTC,
        false,
        null,
        null,
        null);
  }

  private DemoExecutionPolicy restingPolicy() {
    return policy(
        List.of(new DemoBookLevel(new BigDecimal("100"), new BigDecimal("10"))),
        null);
  }

  private DemoExecutionPolicy twoLevelFillPolicy(BigDecimal cap) {
    return policy(
        List.of(
            new DemoBookLevel(new BigDecimal("98"), new BigDecimal("0.4")),
            new DemoBookLevel(new BigDecimal("99"), new BigDecimal("0.6"))),
        cap);
  }

  private DemoExecutionPolicy partialFillPolicy() {
    return policy(
        List.of(new DemoBookLevel(new BigDecimal("98"), new BigDecimal("10"))),
        new BigDecimal("0.4"));
  }

  private DemoExecutionPolicy marketBuyPolicy() {
    return policy(
        List.of(new DemoBookLevel(new BigDecimal("100"), BigDecimal.ONE)),
        null);
  }

  private DemoExecutionPolicy policy(List<DemoBookLevel> asks, BigDecimal cap) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"),
        new BigDecimal("0.002"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("97"), new BigDecimal("10"))),
        asks,
        cap);
  }

  private SpotMarketBundle spotBundle() {
    Instant now = Instant.now();
    return new SpotMarketBundle(
        SYMBOL,
        SYMBOL,
        "binance",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("97"),
        new BigDecimal("100"),
        new BigDecimal("99"),
        null,
        List.of(),
        List.of(),
        now.minusMillis(10),
        now.plusSeconds(60));
  }

  private ExecutableMarketSnapshot tick() {
    return ExecutableMarketSnapshot.from(spotBundle());
  }

  private void installSecondFillFailureTrigger(UUID orderId) {
    jdbcTemplate.execute("""
        CREATE OR REPLACE FUNCTION public.depth_pending_it_fail_second_fill()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
          IF NEW.order_id = '%s'::uuid
             AND (SELECT count(*) FROM trading.trades WHERE order_id = NEW.order_id) = 1 THEN
            RAISE EXCEPTION 'depth pending IT injected second fill failure';
          END IF;
          RETURN NEW;
        END;
        $$
        """.formatted(orderId));
    jdbcTemplate.execute("""
        CREATE TRIGGER depth_pending_it_fail_second_fill
        BEFORE INSERT ON trading.trades
        FOR EACH ROW EXECUTE FUNCTION public.depth_pending_it_fail_second_fill()
        """);
  }

  private void dropSecondFillFailureTrigger() {
    jdbcTemplate.execute(
        "DROP TRIGGER IF EXISTS depth_pending_it_fail_second_fill ON trading.trades");
    jdbcTemplate.execute(
        "DROP FUNCTION IF EXISTS public.depth_pending_it_fail_second_fill()");
  }

  private DatabaseState databaseState(Fixture fixture) {
    UUID accountId = fixture.accountId();
    return new DatabaseState(
        rows("SELECT * FROM trading.orders WHERE account_id = ? ORDER BY id", accountId),
        rows("SELECT * FROM trading.trades WHERE account_id = ? ORDER BY id", accountId),
        rows("SELECT * FROM trading.spot_positions WHERE account_id = ? ORDER BY id", accountId),
        rows("""
            SELECT * FROM trading.order_events
            WHERE order_id IN (SELECT id FROM trading.orders WHERE account_id = ?)
            ORDER BY id
            """, accountId),
        rows("SELECT * FROM ledger.asset_ledger_entries WHERE account_id = ? ORDER BY id", accountId),
        rows("SELECT * FROM ledger.ledger_entries WHERE account_id = ? ORDER BY id", accountId),
        rows("SELECT * FROM core.wallet_balances WHERE account_id = ? ORDER BY wallet_type, asset", accountId),
        rows("SELECT * FROM core.trading_accounts WHERE id = ?", accountId),
        accountSummary(fixture));
  }

  private List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbcTemplate.queryForList(sql, args);
  }

  private List<String> fillIdentities(UUID orderId) {
    return jdbcTemplate.queryForList(
        "SELECT fill_identity FROM trading.trades WHERE order_id = ? ORDER BY price",
        String.class,
        orderId);
  }

  private AccountSummaryState accountSummary(Fixture fixture) {
    AccountResponse response = accountService.summary(fixture.userId(), fixture.accountId());
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

  private void assertWallet(
      UUID accountId,
      String asset,
      String total,
      String available,
      String locked
  ) {
    WalletBalanceEntity wallet = walletBalanceRepository
        .findByAccountIdAndWalletTypeAndAsset(accountId, "SPOT", asset)
        .orElseThrow();
    assertThat(wallet.getTotal()).isEqualByComparingTo(total);
    assertThat(wallet.getAvailable()).isEqualByComparingTo(available);
    assertThat(wallet.getLocked()).isEqualByComparingTo(locked);
  }

  private void assertWalletInvariants(UUID accountId) {
    assertThat(count("""
        SELECT count(*) FROM core.wallet_balances
        WHERE account_id = ?
          AND (total < 0 OR available < 0 OR locked < 0 OR total <> available + locked)
        """, accountId)).isZero();
  }

  private void assertDecimal(String expected, String sql, Object... args) {
    assertThat(jdbcTemplate.queryForObject(sql, BigDecimal.class, args))
        .isEqualByComparingTo(expected);
  }

  private long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private String string(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, String.class, args);
  }

  private static BigDecimal canonical(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros();
  }

  private static String uniqueKey(String prefix) {
    return "depth-pending-it-" + prefix + "-" + UUID.randomUUID();
  }

  private static void await(CountDownLatch latch, String description) {
    try {
      if (!latch.await(10, SECONDS)) {
        throw new IllegalStateException("Timed out waiting for " + description);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for " + description, exception);
    }
  }

  private static final class RaceGate {

    private final CountDownLatch ready;
    private final CountDownLatch start = new CountDownLatch(1);

    private RaceGate(int workers) {
      ready = new CountDownLatch(workers);
    }

    private void awaitStart() {
      ready.countDown();
      await(start, "race start");
    }

    private void release() {
      await(ready, "all race workers");
      start.countDown();
    }
  }

  private record Fixture(UUID userId, UUID accountId, UserPrincipal principal) {
  }

  private record CreateAttempt(OrderResponse response, BusinessException failure) {

    private static CreateAttempt success(OrderResponse response) {
      return new CreateAttempt(response, null);
    }

    private static CreateAttempt failure(BusinessException failure) {
      return new CreateAttempt(null, failure);
    }

    private boolean succeeded() {
      return response != null;
    }
  }

  private record DatabaseState(
      List<Map<String, Object>> orders,
      List<Map<String, Object>> trades,
      List<Map<String, Object>> spotPositions,
      List<Map<String, Object>> orderEvents,
      List<Map<String, Object>> assetLedger,
      List<Map<String, Object>> cashLedger,
      List<Map<String, Object>> wallets,
      List<Map<String, Object>> accounts,
      AccountSummaryState accountSummary
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

  @TestConfiguration(proxyBeanMethods = false)
  static class DepthPolicyConfiguration {

    @Bean
    @Primary
    MutableDepthPolicyProvider depthPendingExecutionPolicyProvider() {
      return new MutableDepthPolicyProvider();
    }
  }

  static final class MutableDepthPolicyProvider implements DemoExecutionPolicyProvider {

    private final AtomicReference<DemoExecutionPolicy> policy = new AtomicReference<>();
    private final AtomicReference<InsideLockProbe> insideLockProbe = new AtomicReference<>();
    private final ThreadLocal<Integer> threadInvocations = ThreadLocal.withInitial(() -> 0);

    void set(DemoExecutionPolicy next) {
      policy.set(next);
    }

    void armInsideLockProbe() {
      insideLockProbe.set(new InsideLockProbe(false));
    }

    void armInsideLockHold() {
      insideLockProbe.set(new InsideLockProbe(true));
    }

    void awaitInsideLockArrival() {
      InsideLockProbe probe = insideLockProbe.get();
      if (probe == null) {
        throw new IllegalStateException("Inside-lock hold is not armed");
      }
      probe.awaitArrival();
    }

    void releaseInsideLockHold() {
      InsideLockProbe probe = insideLockProbe.get();
      if (probe != null) {
        probe.release();
      }
    }

    void clearInsideLockProbe() {
      InsideLockProbe probe = insideLockProbe.getAndSet(null);
      if (probe != null) {
        probe.release();
      }
      threadInvocations.remove();
    }

    int insideLockArrivals() {
      InsideLockProbe probe = insideLockProbe.get();
      return probe == null ? 0 : probe.arrivals();
    }

    @Override
    public DemoExecutionPolicy current() {
      int invocation = threadInvocations.get() + 1;
      threadInvocations.set(invocation);
      InsideLockProbe probe = insideLockProbe.get();
      if (probe != null && invocation == 2) {
        probe.arriveAndWaitForCompetitor();
      }
      return policy.get();
    }
  }

  private static final class InsideLockProbe {

    private final boolean explicitHold;
    private final AtomicInteger arrivals = new AtomicInteger();
    private final CountDownLatch firstInside = new CountDownLatch(1);
    private final CountDownLatch bothInside = new CountDownLatch(2);
    private final CountDownLatch release = new CountDownLatch(1);

    private InsideLockProbe(boolean explicitHold) {
      this.explicitHold = explicitHold;
    }

    private void arriveAndWaitForCompetitor() {
      arrivals.incrementAndGet();
      firstInside.countDown();
      if (explicitHold) {
        await(release, "inside-lock hold release");
        return;
      }
      bothInside.countDown();
      try {
        bothInside.await(2, SECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while probing the pending-order row lock", exception);
      }
    }

    private void awaitArrival() {
      await(firstInside, "inside-lock probe arrival");
    }

    private void release() {
      release.countDown();
    }

    private int arrivals() {
      return arrivals.get();
    }
  }
}
