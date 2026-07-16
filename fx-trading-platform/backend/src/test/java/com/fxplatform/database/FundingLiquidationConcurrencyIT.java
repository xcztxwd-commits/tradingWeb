package com.fxplatform.database;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.DemoAccountLifecycleService;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.FundingService;
import com.fxplatform.trading.service.FundingService.FundingSettlementOutcome;
import com.fxplatform.trading.service.LiquidationSettlementService;
import com.fxplatform.trading.service.OrderService;
import com.fxplatform.trading.service.SystemCloseOrderService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.annotations.Select;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "execution.mode=demo",
    "spring.task.scheduling.enabled=false",
    "trading.pending-order-execution-enabled=false",
    "trading.protective-order-execution-enabled=false",
    "trading.funding.enabled=false"
})
@ActiveProfiles("database-it")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FundingLiquidationConcurrencyIT {

  private static final String SYMBOL = "BTCUSDT-PERP";
  private static final String FUNDING_LIQUIDATION_LOCK_ORDER =
      "account -> symbol setting when required -> sorted positions -> UUID-sorted orders"
          + " -> ledger/events";

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired DemoAccountLifecycleService lifecycleService;
  @Autowired OrderService orderService;
  @Autowired FundingService fundingService;
  @Autowired SystemCloseOrderService systemCloseOrderService;
  @Autowired LiquidationSettlementService liquidationSettlementService;
  @Autowired PositionRepository positionRepository;

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
      jdbcTemplate.update("DELETE FROM trading.cross_liquidation_charges WHERE account_id = ?", accountId);
      jdbcTemplate.update("DELETE FROM audit.audit_logs WHERE target_id = ?", accountId.toString());
      jdbcTemplate.update("""
          DELETE FROM trading.order_events
          WHERE order_id IN (SELECT id FROM trading.orders WHERE account_id = ?)
          """, accountId);
      jdbcTemplate.update("DELETE FROM trading.funding_settlements WHERE account_id = ?", accountId);
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
  void concurrentFundingForOnePositionAndPeriodSettlesExactlyOnce() throws Exception {
    Fixture fixture = createFixture("funding-once");
    stubBundle(bundle("99", "101", "100", "100"));
    UUID positionId = openLong(fixture);
    PositionEntity position = positionRepository.selectById(positionId);
    FundingRateEntity rate = fundingRate(Instant.now());
    long initialVersion = position.getVersion();
    BigDecimal balanceBefore = decimal(
        "SELECT balance FROM core.trading_accounts WHERE id = ?", fixture.accountId());

    List<Attempt> attempts = runConcurrently(
        () -> fundingService.settleFundingForPositionOutcome(position, rate),
        () -> fundingService.settleFundingForPositionOutcome(position, rate),
        () -> fundingService.settleFundingForPositionOutcome(position, rate),
        () -> fundingService.settleFundingForPositionOutcome(position, rate));

    assertThat(attempts)
        .as(FUNDING_LIQUIDATION_LOCK_ORDER)
        .allMatch(Attempt::succeeded);
    assertThat(attempts.stream()
        .map(Attempt::value)
        .map(FundingSettlementOutcome.class::cast)
        .filter(FundingSettlementOutcome::inserted)).hasSize(1);
    assertThat(count("""
        SELECT count(*) FROM trading.funding_settlements
        WHERE position_id = ? AND funding_time = ?
        """, positionId, rate.getFundingTime())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries l
        JOIN trading.funding_settlements s ON s.id = l.reference_id
        WHERE s.position_id = ? AND s.funding_time = ?
          AND l.reference_type = 'FUNDING_SETTLEMENT'
          AND l.operation_type = 'FUNDING_FEE'
        """, positionId, rate.getFundingTime())).isEqualTo(1);
    assertThat(decimal("""
        SELECT amount FROM trading.funding_settlements
        WHERE position_id = ? AND funding_time = ?
        """, positionId, rate.getFundingTime())).isEqualByComparingTo("-0.10000000");
    assertThat(decimal("SELECT balance FROM core.trading_accounts WHERE id = ?", fixture.accountId()))
        .isEqualByComparingTo(balanceBefore.subtract(new BigDecimal("0.10000000")));
    assertThat(decimal("SELECT funding_pnl FROM trading.positions WHERE id = ?", positionId))
        .isEqualByComparingTo("-0.10000000");
    assertThat(longValue("SELECT version FROM trading.positions WHERE id = ?", positionId))
        .isEqualTo(initialVersion + 1L);
  }

  @Test
  void fundingAndCanonicalLiquidationShareGlobalAccountFirstLockOrder() throws Exception {
    Fixture fixture = createFixture("funding-liquidation");
    stubBundle(bundle("99", "101", "100", "100"));
    UUID positionId = openLong(fixture);
    PositionEntity position = positionRepository.selectById(positionId);
    long initialVersion = position.getVersion();
    FundingRateEntity rate = fundingRate(Instant.now());
    stubBundle(bundle("89", "91", "90", "90"));
    CountDownLatch accountLocked = new CountDownLatch(1);
    CountDownLatch releaseAccount = new CountDownLatch(1);
    AtomicInteger accountLockHolderPid = new AtomicInteger();
    ExecutorService workers = Executors.newFixedThreadPool(3);
    Future<Void> lockHolder = workers.submit(() -> {
      new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
        jdbcTemplate.queryForObject(
            "SELECT id FROM core.trading_accounts WHERE id = ? FOR UPDATE",
            UUID.class,
            fixture.accountId());
        accountLockHolderPid.set(
            jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
        accountLocked.countDown();
        await(releaseAccount, "release account row lock");
      });
      return null;
    });
    Future<FundingSettlementOutcome> funding = null;
    Future<SystemCloseOrderService.CloseResult> liquidation = null;
    FundingSettlementOutcome fundingOutcome;
    SystemCloseOrderService.CloseResult liquidationOutcome;
    try {
      assertThat(accountLocked.await(5, SECONDS)).isTrue();
      assertThat(accountLockHolderPid.get()).isPositive();
      funding = workers.submit(
          () -> fundingService.settleFundingForPositionOutcome(position, rate));
      liquidation = workers.submit(() -> systemCloseOrderService.closeWhole(
          fixture.accountId(),
          positionId,
          OrderOrigin.LIQUIDATION,
          "CROSS_MAINTENANCE_MARGIN",
          "task17-funding-liquidation-" + positionId));

      awaitAccountLockWaiters(accountLockHolderPid.get(), 2);
      assertThat(funding.isDone()).as(FUNDING_LIQUIDATION_LOCK_ORDER).isFalse();
      assertThat(liquidation.isDone()).as(FUNDING_LIQUIDATION_LOCK_ORDER).isFalse();
      assertDownstreamRowLockAvailable(
          "symbol-setting phase must not precede the account lock",
          """
              SELECT 1 FROM trading.account_symbol_settings
              WHERE account_id = ? AND symbol = ? FOR UPDATE
              """,
          fixture.accountId(),
          SYMBOL);
      assertDownstreamRowLockAvailable(
          "position phase must not precede the account lock",
          "SELECT 1 FROM trading.positions WHERE id = ? FOR UPDATE",
          positionId);

      releaseAccount.countDown();
      lockHolder.get(10, SECONDS);
      fundingOutcome = funding.get(20, SECONDS);
      liquidationOutcome = liquidation.get(20, SECONDS);
    } finally {
      releaseAccount.countDown();
      cancelAndShutdown(workers, Arrays.asList(lockHolder, funding, liquidation));
    }

    assertThat(liquidationOutcome.replayed()).isFalse();
    assertThat(liquidationSettlementService.settleIfReady(fixture.accountId())).isTrue();
    long settlementCount = count("""
        SELECT count(*) FROM trading.funding_settlements
        WHERE position_id = ? AND funding_time = ?
        """, positionId, rate.getFundingTime());
    long fundingLedgerCount = count("""
        SELECT count(*) FROM ledger.ledger_entries l
        JOIN trading.funding_settlements s ON s.id = l.reference_id
        WHERE s.position_id = ? AND s.funding_time = ?
          AND l.reference_type = 'FUNDING_SETTLEMENT'
          AND l.operation_type = 'FUNDING_FEE'
        """, positionId, rate.getFundingTime());

    assertThat(string("SELECT status FROM trading.positions WHERE id = ?", positionId))
        .isEqualTo("CLOSED");
    FundingLiquidationOutcome observed = new FundingLiquidationOutcome(
        fundingOutcome.inserted(),
        fundingOutcome.cashflow(),
        settlementCount,
        fundingLedgerCount,
        decimal("SELECT funding_pnl FROM trading.positions WHERE id = ?", positionId),
        longValue("SELECT version FROM trading.positions WHERE id = ?", positionId),
        decimal("SELECT balance FROM core.trading_accounts WHERE id = ?", fixture.accountId()),
        decimal("SELECT equity FROM core.trading_accounts WHERE id = ?", fixture.accountId()),
        decimal("SELECT used_margin FROM core.trading_accounts WHERE id = ?", fixture.accountId()),
        decimal("SELECT free_margin FROM core.trading_accounts WHERE id = ?", fixture.accountId()));
    assertThat(List.of(
        new FundingLiquidationOutcome(
            false,
            new BigDecimal("0.00000000"),
            0L,
            0L,
            new BigDecimal("0.00000000"),
            initialVersion + 1L,
            new BigDecimal("49987.44104390"),
            new BigDecimal("49987.44104390"),
            new BigDecimal("0.00000000"),
            new BigDecimal("49987.44104390")),
        new FundingLiquidationOutcome(
            true,
            new BigDecimal("-0.10000000"),
            1L,
            1L,
            new BigDecimal("-0.10000000"),
            initialVersion + 2L,
            new BigDecimal("49987.34104390"),
            new BigDecimal("49987.34104390"),
            new BigDecimal("0.00000000"),
            new BigDecimal("49987.34104390"))))
        .as("only funding-first and liquidation-first serializations are legal")
        .contains(observed);
    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE account_id = ? AND order_origin = 'LIQUIDATION'
        """, fixture.accountId())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.trades t
        JOIN trading.orders o ON o.id = t.order_id
        WHERE o.account_id = ? AND o.order_origin = 'LIQUIDATION'
        """, fixture.accountId())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.cross_liquidation_charges
        WHERE account_id = ? AND status = 'SETTLED'
        """, fixture.accountId())).isEqualTo(1);
    assertThat(decimal("""
        SELECT fee_due FROM trading.cross_liquidation_charges WHERE account_id = ?
        """, fixture.accountId())).isEqualByComparingTo("0.44495550");
    assertThat(decimal("""
        SELECT fee_charged FROM trading.cross_liquidation_charges WHERE account_id = ?
        """, fixture.accountId())).isEqualByComparingTo("0.44495550");
  }

  @Test
  void fundingSettlementBusinessKeyIsFinallyGuardedByPostgresUniqueness()
      throws Exception {
    Fixture fixture = createFixture("funding-unique");
    stubBundle(bundle("99", "101", "100", "100"));
    UUID positionId = openLong(fixture);
    Instant fundingTime = Instant.now();

    List<Attempt> attempts = runConcurrently(
        () -> insertFundingSettlement(
            UUID.randomUUID(), positionId, fixture.accountId(), fundingTime),
        () -> insertFundingSettlement(
            UUID.randomUUID(), positionId, fixture.accountId(), fundingTime));

    assertThat(attempts.stream().filter(Attempt::succeeded)).singleElement()
        .satisfies(attempt -> assertThat(attempt.value()).isEqualTo(1));
    assertThat(attempts.stream().filter(attempt -> !attempt.succeeded())).singleElement()
        .satisfies(attempt -> {
          assertThat(attempt.error()).isInstanceOf(DataIntegrityViolationException.class);
          assertThat(rootMessage(attempt.error()))
              .contains("uq_funding_settlements_position_time");
        });
    assertThat(count("""
        SELECT count(*) FROM trading.funding_settlements
        WHERE position_id = ? AND funding_time = ?
        """, positionId, fundingTime)).isEqualTo(1);
  }

  @Test
  void concurrentOpenPositionSlotInsertsAreFinallyGuardedByPostgresUniqueness() throws Exception {
    Fixture fixture = createFixture("slot-unique");
    stubBundle(bundle("99", "101", "100", "100"));
    UUID originalPositionId = openLong(fixture);
    jdbcTemplate.update("DELETE FROM trading.positions WHERE id = ?", originalPositionId);
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();

    List<Attempt> attempts = runConcurrently(
        () -> insertOpenOneWayPosition(firstId, fixture.accountId()),
        () -> insertOpenOneWayPosition(secondId, fixture.accountId()));

    assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
    assertThat(attempts.stream().filter(attempt -> !attempt.succeeded())).singleElement()
        .satisfies(attempt -> assertThat(rootMessage(attempt.error()))
            .contains("ux_positions_one_way_open_both"));
    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND product_type = 'LINEAR_PERP'
          AND position_mode = 'ONE_WAY' AND position_side = 'BOTH' AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL)).isEqualTo(1);
  }

  @Test
  void fundingAndLiquidationRepositoriesDeclareCompatibleAccountFirstLocks() throws Exception {
    assertForUpdate(
        TradingAccountRepository.class,
        "findByIdForUpdate",
        UUID.class);
    assertForUpdate(
        PositionRepository.class,
        "findByIdForUpdate",
        UUID.class);
    assertForUpdate(
        AccountSymbolSettingRepository.class,
        "findByAccountIdAndSymbolForUpdate",
        UUID.class,
        String.class);
    assertSortedForUpdate(
        PositionRepository.class,
        "findOpenLinearPerpBySymbolForUpdate",
        "ORDER BY symbol, position_side, id",
        UUID.class,
        String.class);
    assertSortedForUpdate(
        OrderRepository.class,
        "findActiveLinearPerpBySymbolForUpdate",
        "ORDER BY id",
        UUID.class,
        String.class);
  }

  private Fixture createFixture(String suffix) {
    UUID userId = UUID.randomUUID();
    String email = "task17-funding-" + suffix + '-' + userId + "@example.test";
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

  private UUID openLong(Fixture fixture) {
    String key = "task17-funding-open-" + UUID.randomUUID();
    orderService.createOrder(
        fixture.principal(),
        new CreateOrderRequest(
            fixture.accountId(),
            SYMBOL,
            OrderSide.BUY,
            OrderType.MARKET,
            null,
            null,
            null,
            null,
            key,
            key,
            BigDecimal.ONE,
            null,
            10,
            PositionSide.BOTH,
            QuantityUnit.BASE,
            MarginMode.CROSS,
            null,
            null,
            false,
            List.of()));
    return jdbcTemplate.queryForObject("""
        SELECT id FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND position_side = 'BOTH' AND status = 'OPEN'
        """, UUID.class, fixture.accountId(), SYMBOL);
  }

  private int insertOpenOneWayPosition(UUID positionId, UUID accountId) {
    return jdbcTemplate.update("""
        INSERT INTO trading.positions (
          id, account_id, symbol, product_type, position_mode, position_side,
          margin_mode, side, lots, open_price, current_price, mark_price,
          settlement_asset, margin_asset, leverage, status, opened_at, version
        ) VALUES (
          ?, ?, ?, 'LINEAR_PERP', 'ONE_WAY', 'BOTH',
          'CROSS', 'BUY', 1, 100, 100, 100,
          'USDT', 'USDT', 10, 'OPEN', now(), 0
        )
        """, positionId, accountId, SYMBOL);
  }

  private int insertFundingSettlement(
      UUID settlementId,
      UUID positionId,
      UUID accountId,
      Instant fundingTime
  ) {
    return jdbcTemplate.update("""
        INSERT INTO trading.funding_settlements (
          id, position_id, account_id, symbol, funding_time, funding_rate,
          amount, asset, position_side, margin_mode, mark_price, source,
          balance_after, isolated_margin_after, shortfall
        ) VALUES (
          ?, ?, ?, ?, ?, 0.001, -0.1, 'USDT', 'BOTH', 'CROSS', 100,
          'task17-direct', 49999.9, 0, 0
        )
        """, settlementId, positionId, accountId, SYMBOL, fundingTime);
  }

  private FundingRateEntity fundingRate(Instant fundingTime) {
    FundingRateEntity rate = new FundingRateEntity();
    rate.setId(UUID.randomUUID());
    rate.setSymbol(SYMBOL);
    rate.setFundingRate(new BigDecimal("0.0010000000"));
    rate.setFundingTime(fundingTime);
    rate.setNextFundingTime(fundingTime.plusSeconds(8 * 60 * 60));
    rate.setMarkPrice(new BigDecimal("100.0000000000"));
    rate.setProviderCode("binance-usdm");
    rate.setSourceMode("PUBLIC_EXTERNAL");
    rate.setAsOf(fundingTime.minusSeconds(1));
    rate.setIntervalMinutes(480);
    rate.setRawPayloadHash("task17-" + UUID.randomUUID());
    return rate;
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

  private void awaitAccountLockWaiters(int lockHolderPid, int expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + SECONDS.toNanos(5);
    long observed;
    do {
      observed = count("""
          WITH RECURSIVE blocking_chain(waiter_pid, blocker_pid) AS (
            SELECT activity.pid, blocker.pid
            FROM pg_stat_activity activity
            CROSS JOIN LATERAL
              unnest(pg_blocking_pids(activity.pid)) AS blocker(pid)
            WHERE activity.datname = current_database()
              AND activity.state = 'active'
              AND activity.wait_event_type = 'Lock'
              AND activity.query ILIKE '%core.trading_accounts%'
              AND activity.query ILIKE '%FOR UPDATE%'
            UNION
            SELECT chain.waiter_pid, blocker.pid
            FROM blocking_chain chain
            CROSS JOIN LATERAL
              unnest(pg_blocking_pids(chain.blocker_pid)) AS blocker(pid)
          )
          SELECT count(DISTINCT waiter_pid)
          FROM blocking_chain
          WHERE blocker_pid = ?
          """, lockHolderPid);
      if (observed == expected) {
        return;
      }
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);

    assertThat(observed)
        .as("exactly the funding and liquidation flows must wait behind this account lock")
        .isEqualTo(expected);
  }

  private void assertDownstreamRowLockAvailable(
      String description,
      String sql,
      Object... args
  ) {
    assertThatCode(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      jdbcTemplate.execute("SET LOCAL lock_timeout = '500ms'");
      jdbcTemplate.queryForObject(sql, Integer.class, args);
    }))
        .as(description + " (" + FUNDING_LIQUIDATION_LOCK_ORDER + ")")
        .doesNotThrowAnyException();
  }

  private static void await(CountDownLatch latch, String description) {
    try {
      if (!latch.await(10, SECONDS)) {
        throw new AssertionError("Timed out waiting for " + description);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for " + description, exception);
    }
  }

  @SafeVarargs
  private static List<Attempt> runConcurrently(Callable<?>... calls) throws Exception {
    CyclicBarrier start = new CyclicBarrier(calls.length);
    ExecutorService workers = Executors.newFixedThreadPool(calls.length);
    List<Future<Attempt>> futures = new ArrayList<>();
    try {
      futures = Arrays.stream(calls)
          .map(call -> workers.submit(() -> {
            start.await(5, SECONDS);
            try {
              return new Attempt(call.call(), null);
            } catch (Throwable error) {
              return new Attempt(null, error);
            }
          }))
          .toList();
      List<Attempt> attempts = new ArrayList<>();
      for (Future<Attempt> future : futures) {
        attempts.add(future.get(30, SECONDS));
      }
      return attempts;
    } finally {
      cancelAndShutdown(workers, futures);
    }
  }

  private static void cancelAndShutdown(
      ExecutorService workers,
      List<? extends Future<?>> futures
  ) {
    for (Future<?> future : futures) {
      if (future != null && !future.isDone()) {
        future.cancel(true);
      }
    }
    workers.shutdownNow();
    try {
      if (!workers.awaitTermination(10, SECONDS)) {
        throw new AssertionError("Timed out waiting for concurrency workers to terminate");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(
          "Interrupted while waiting for concurrency workers to terminate",
          exception);
    }
  }

  private long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private long longValue(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private BigDecimal decimal(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
  }

  private String string(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, String.class, args);
  }

  private static String rootMessage(Throwable error) {
    Throwable current = error;
    while (current != null && current.getCause() != null) {
      current = current.getCause();
    }
    return current == null || current.getMessage() == null ? "" : current.getMessage();
  }

  private static void assertForUpdate(
      Class<?> repositoryType,
      String methodName,
      Class<?>... parameterTypes
  ) throws Exception {
    assertThat(selectSql(repositoryType.getMethod(methodName, parameterTypes)))
        .as(FUNDING_LIQUIDATION_LOCK_ORDER)
        .containsIgnoringCase("FOR UPDATE");
  }

  private static void assertSortedForUpdate(
      Class<?> repositoryType,
      String methodName,
      String expectedOrderBy,
      Class<?>... parameterTypes
  ) throws Exception {
    String sql = selectSql(repositoryType.getMethod(methodName, parameterTypes));
    assertThat(sql)
        .as(FUNDING_LIQUIDATION_LOCK_ORDER)
        .containsIgnoringCase(expectedOrderBy)
        .containsIgnoringCase("FOR UPDATE");
  }

  private static String selectSql(Method method) {
    Select select = method.getAnnotation(Select.class);
    assertThat(select).as("%s must declare mapper SQL", method).isNotNull();
    return String.join(" ", select.value()).replaceAll("\\s+", " ");
  }

  private record Fixture(UUID userId, UUID accountId, UserPrincipal principal) {
  }

  private record FundingLiquidationOutcome(
      boolean fundingInserted,
      BigDecimal fundingCashflow,
      long settlementCount,
      long fundingLedgerCount,
      BigDecimal fundingPnl,
      long positionVersion,
      BigDecimal balance,
      BigDecimal equity,
      BigDecimal usedMargin,
      BigDecimal freeMargin
  ) {
  }

  private record Attempt(Object value, Throwable error) {

    boolean succeeded() {
      return error == null;
    }
  }
}
