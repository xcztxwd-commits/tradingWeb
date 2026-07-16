package com.fxplatform.database;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.UpdateSymbolSettingsRequest;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.DemoAccountLifecycleService;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.trading.dto.request.ClosePositionRequest;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.OrderService;
import com.fxplatform.trading.service.SystemCloseOrderService;
import com.fxplatform.trading.service.TradingSettingsService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
    "trading.protective-order-execution-enabled=false"
})
@ActiveProfiles("database-it")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PerpetualPositionConcurrencyIT {

  private static final String SYMBOL = "BTCUSDT-PERP";

  /**
   * Perpetual mutation specialization of the global order. The wallet phase is absent here:
   * account -> symbol setting -> positions(symbol, position_side, id) -> orders(id)
   * -> ledger/events.
   */
  private static final String PERPETUAL_LOCK_ORDER =
      "account -> symbol setting -> sorted positions -> UUID-sorted orders -> ledger/events";
  private static final List<SerializedOutcome> LEGAL_SERIALIZED_OUTCOMES = List.of(
      // OPEN -> CLOSE, with SETTINGS before, between or after them.
      new SerializedOutcome(true, 3L, null),
      // CLOSE -> OPEN -> SETTINGS.
      new SerializedOutcome(false, 1L, 1L),
      // CLOSE -> SETTINGS -> OPEN.
      new SerializedOutcome(false, 0L, 1L),
      // SETTINGS -> CLOSE -> OPEN.
      new SerializedOutcome(false, 0L, 2L));

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired DemoAccountLifecycleService lifecycleService;
  @Autowired OrderService orderService;
  @Autowired SystemCloseOrderService systemCloseOrderService;
  @Autowired TradingSettingsService settingsService;

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
      jdbcTemplate.update(
          "DELETE FROM trading.account_symbol_settings WHERE account_id = ?", accountId);
      jdbcTemplate.update("DELETE FROM core.trading_accounts WHERE id = ?", accountId);
      jdbcTemplate.update("DELETE FROM auth.users WHERE id = ?", fixture.userId());
    }
    fixtures.clear();
  }

  @Test
  void accountFirstLockSerializesOpenCloseAndSettingsWithoutReversalOrVersionDrift()
      throws Exception {
    Fixture fixture = createFixture("open-close-settings");
    stubBundle(bundle());
    createMarketBuy(fixture, "baseline-open");
    UUID baselinePositionId = uuid("""
        SELECT id FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL);
    OrderResponse activeOrder = createPendingLimitBuy(fixture, "active-order-lock-stage");
    assertThat(activeOrder.status()).isEqualTo(OrderStatus.PENDING.name());
    assertDecimal(
        "9.84900000", "SELECT hold_amount FROM trading.orders WHERE id = ?", activeOrder.id());

    CountDownLatch accountLocked = new CountDownLatch(1);
    CountDownLatch releaseAccount = new CountDownLatch(1);
    CountDownLatch allMarketSnapshotsResolved = new CountDownLatch(3);
    AtomicInteger accountLockHolderPid = new AtomicInteger();
    ExecutorService workers = Executors.newFixedThreadPool(4);
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

    Future<OrderResponse> open = null;
    Future<SystemCloseOrderService.CloseResult> close = null;
    Future<?> settings = null;
    try {
      assertThat(accountLocked.await(5, SECONDS)).isTrue();
      assertThat(accountLockHolderPid.get()).isPositive();
      stubConcurrentBundle(allMarketSnapshotsResolved);

      open = workers.submit(() -> createMarketBuy(fixture, "raced-open"));
      close = workers.submit(() -> systemCloseOrderService.closeUser(
          fixture.userId(),
          fixture.accountId(),
          baselinePositionId,
          new ClosePositionRequest(
              BigDecimal.ONE,
              QuantityUnit.BASE,
              "raced-reduce-only-close")));
      settings = workers.submit(() -> settingsService.updateSymbolSettings(
          fixture.userId(),
          fixture.accountId(),
          SYMBOL,
          new UpdateSymbolSettingsRequest(20, null, null, 0L)));

      assertThat(allMarketSnapshotsResolved.await(5, SECONDS)).isTrue();
      assertThat(open.isDone()).as(PERPETUAL_LOCK_ORDER).isFalse();
      assertThat(close.isDone()).as(PERPETUAL_LOCK_ORDER).isFalse();
      assertThat(settings.isDone()).as(PERPETUAL_LOCK_ORDER).isFalse();

      awaitAccountLockWaiters(accountLockHolderPid.get(), 3);
      assertDownstreamRowLockAvailable(
          "position phase must not precede the account lock",
          "SELECT id FROM trading.positions WHERE id = ? FOR UPDATE",
          baselinePositionId);
      assertDownstreamRowLockAvailable(
          "active-order phase must not precede the account lock",
          "SELECT id FROM trading.orders WHERE id = ? FOR UPDATE",
          activeOrder.id());

      // Every flow has resolved market data, but the account-first lock prevents all writes.
      assertThat(count(
          "SELECT count(*) FROM trading.orders WHERE account_id = ?", fixture.accountId()))
          .isEqualTo(2);
      assertThat(count(
          "SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
          .isEqualTo(1);
      assertDecimal("1", "SELECT lots FROM trading.positions WHERE id = ?", baselinePositionId);
      assertThat(longValue("SELECT version FROM trading.positions WHERE id = ?", baselinePositionId))
          .isZero();
      assertThat(longValue("""
          SELECT version FROM trading.account_symbol_settings
          WHERE account_id = ? AND symbol = ?
          """, fixture.accountId(), SYMBOL)).isZero();

      releaseAccount.countDown();
      lockHolder.get(10, SECONDS);
      open.get(20, SECONDS);
      close.get(20, SECONDS);
      settings.get(20, SECONDS);
    } finally {
      releaseAccount.countDown();
      workers.shutdown();
      if (!workers.awaitTermination(30, SECONDS)) {
        workers.shutdownNow();
      }
    }

    PositionState finalPosition = jdbcTemplate.queryForObject("""
        SELECT id, side, lots, leverage, initial_margin, margin_held, floating_pnl, version
        FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND status = 'OPEN'
        """, (rs, rowNum) -> new PositionState(
        rs.getObject("id", UUID.class),
        rs.getString("side"),
        rs.getBigDecimal("lots"),
        rs.getInt("leverage"),
        rs.getBigDecimal("initial_margin"),
        rs.getBigDecimal("margin_held"),
        rs.getBigDecimal("floating_pnl"),
        rs.getLong("version")), fixture.accountId(), SYMBOL);

    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND product_type = 'LINEAR_PERP'
          AND position_mode = 'ONE_WAY' AND position_side = 'BOTH' AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL)).isEqualTo(1);
    assertThat(finalPosition.side()).isEqualTo("BUY");
    assertThat(finalPosition.lots()).isEqualByComparingTo("1");
    assertThat(finalPosition.leverage()).isEqualTo(20);
    assertThat(finalPosition.initialMargin()).isEqualByComparingTo("5.05050500");
    assertThat(finalPosition.marginHeld()).isEqualByComparingTo("5.05050500");
    assertThat(finalPosition.floatingPnl()).isEqualByComparingTo("-1.01010000");
    boolean baselineRemainsOpen = finalPosition.id().equals(baselinePositionId);
    Long closedBaselineVersion = baselineRemainsOpen
        ? null
        : longValue("""
            SELECT version FROM trading.positions
            WHERE id = ? AND status = 'CLOSED'
            """, baselinePositionId);
    SerializedOutcome observedOutcome = new SerializedOutcome(
        baselineRemainsOpen,
        finalPosition.version(),
        closedBaselineVersion);
    assertThat(LEGAL_SERIALIZED_OUTCOMES)
        .as("only the six account-serialized OPEN/CLOSE/SETTINGS schedules are legal")
        .contains(observedOutcome);

    assertThat(longValue("""
        SELECT version FROM trading.account_symbol_settings
        WHERE account_id = ? AND symbol = ? AND leverage = 20
        """, fixture.accountId(), SYMBOL)).isEqualTo(1L);
    assertThat(count("SELECT count(*) FROM trading.orders WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(4);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(3);
    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE id = ? AND status = 'PENDING' AND hold_amount = 9.849
          AND leverage = 10 AND version = 0
        """, activeOrder.id())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE account_id = ? AND reduce_only = true AND side = 'SELL'
          AND base_quantity = 1 AND status = 'FILLED'
        """, fixture.accountId())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = ? AND side = 'SELL' AND status = 'OPEN'
        """, fixture.accountId(), SYMBOL)).isZero();

    assertDecimal("49997.82949485", """
        SELECT balance FROM core.trading_accounts WHERE id = ?
        """, fixture.accountId());
    assertDecimal("49996.81939485", """
        SELECT equity FROM core.trading_accounts WHERE id = ?
        """, fixture.accountId());
    assertDecimal("14.89950500", """
        SELECT used_margin FROM core.trading_accounts WHERE id = ?
        """, fixture.accountId());
    assertDecimal("49981.91988985", """
        SELECT free_margin FROM core.trading_accounts WHERE id = ?
        """, fixture.accountId());
    assertDecimal("0", """
        SELECT used_margin
          - (SELECT margin_held FROM trading.positions WHERE id = ?)
          - (SELECT hold_amount FROM trading.orders WHERE id = ?)
        FROM core.trading_accounts WHERE id = ?
        """, finalPosition.id(), activeOrder.id(), fixture.accountId());
    assertDecimal("0", """
        SELECT free_margin - (equity - used_margin)
        FROM core.trading_accounts WHERE id = ?
        """, fixture.accountId());
  }

  @Test
  void partialUniqueIndexesRemainTheFinalGuardForOneWayAndHedgeOpenSlots() {
    Fixture fixture = createFixture("slot-uniqueness");

    insertPosition(fixture, "ETHUSDT-PERP", "ONE_WAY", "BOTH");
    assertThatThrownBy(() -> insertPosition(
        fixture, "ETHUSDT-PERP", "ONE_WAY", "BOTH"))
        .isInstanceOf(DataIntegrityViolationException.class);

    insertPosition(fixture, "XRPUSDT-PERP", "HEDGE", "LONG");
    insertPosition(fixture, "XRPUSDT-PERP", "HEDGE", "SHORT");
    assertThatThrownBy(() -> insertPosition(
        fixture, "XRPUSDT-PERP", "HEDGE", "LONG"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertPosition(
        fixture, "XRPUSDT-PERP", "HEDGE", "SHORT"))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = 'ETHUSDT-PERP' AND status = 'OPEN'
        """, fixture.accountId())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = 'XRPUSDT-PERP' AND status = 'OPEN'
        """, fixture.accountId())).isEqualTo(2);
  }

  @Test
  void repositoryLocksEncodeAccountSettingSortedPositionAndSortedOrderPhases()
      throws Exception {
    assertForUpdate(
        TradingAccountRepository.class,
        "findByIdAndUserIdForUpdate",
        UUID.class,
        UUID.class);
    assertForUpdate(
        AccountSymbolSettingRepository.class,
        "findByAccountIdAndSymbolForUpdate",
        UUID.class,
        String.class);
    assertSortedForUpdate(
        PositionRepository.class,
        "findOpenLinearPerpByAccountIdForUpdate",
        "ORDER BY symbol, position_side, id",
        UUID.class);
    assertSortedForUpdate(
        OrderRepository.class,
        "findActiveLinearPerpByAccountIdForUpdate",
        "ORDER BY id",
        UUID.class);
  }

  private Fixture createFixture(String suffix) {
    UUID userId = UUID.randomUUID();
    String email = "task17-perp-it-" + suffix + "-" + userId + "@example.test";
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

  private OrderResponse createMarketBuy(Fixture fixture, String keySuffix) {
    String key = "task17-perp-" + keySuffix + "-" + UUID.randomUUID();
    return orderService.createOrder(
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
  }

  private OrderResponse createPendingLimitBuy(Fixture fixture, String keySuffix) {
    String key = "task17-perp-" + keySuffix + "-" + UUID.randomUUID();
    return orderService.createOrder(
        fixture.principal(),
        new CreateOrderRequest(
            fixture.accountId(),
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
            new BigDecimal("98"),
            10,
            PositionSide.BOTH,
            QuantityUnit.BASE,
            MarginMode.CROSS,
            null,
            null,
            false,
            List.of()));
  }

  private void insertPosition(
      Fixture fixture,
      String symbol,
      String positionMode,
      String positionSide
  ) {
    jdbcTemplate.update("""
        INSERT INTO trading.positions (
          id, account_id, symbol, product_type, position_mode, position_side,
          margin_mode, side, lots, open_price, current_price, status, leverage
        ) VALUES (
          ?, ?, ?, 'LINEAR_PERP', ?, ?, 'CROSS',
          CASE WHEN ? = 'SHORT' THEN 'SELL' ELSE 'BUY' END,
          1, 100, 100, 'OPEN', 10
        )
        """,
        UUID.randomUUID(),
        fixture.accountId(),
        symbol,
        positionMode,
        positionSide,
        positionSide);
  }

  private void stubBundle(PerpetualMarketBundle market) {
    reset(marketBundleResolver);
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(market);
  }

  private void stubConcurrentBundle(CountDownLatch allResolved) {
    reset(marketBundleResolver);
    PerpetualMarketBundle market = bundle();
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenAnswer(invocation -> {
          allResolved.countDown();
          await(allResolved, "all three market snapshots");
          return market;
        });
  }

  private PerpetualMarketBundle bundle() {
    Instant now = Instant.now();
    return new PerpetualMarketBundle(
        SYMBOL,
        "BTCUSDT",
        "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"),
        new BigDecimal("101"),
        new BigDecimal("100"),
        new BigDecimal("100"),
        new BigDecimal("100"),
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
        .as("exactly the three service flows must descend from this account lock holder")
        .isEqualTo(expected);
  }

  private void assertDownstreamRowLockAvailable(String description, String sql, UUID id) {
    assertThatCode(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      jdbcTemplate.execute("SET LOCAL lock_timeout = '500ms'");
      jdbcTemplate.queryForObject(sql, UUID.class, id);
    }))
        .as(description + " (" + PERPETUAL_LOCK_ORDER + ")")
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

  private void assertDecimal(String expected, String sql, Object... args) {
    assertThat(decimal(sql, args)).isEqualByComparingTo(expected);
  }

  private BigDecimal decimal(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
  }

  private UUID uuid(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, UUID.class, args);
  }

  private Long longValue(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Long.class, args);
  }

  private long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private static void assertForUpdate(
      Class<?> repositoryType,
      String methodName,
      Class<?>... parameterTypes
  ) throws Exception {
    String sql = selectSql(repositoryType.getMethod(methodName, parameterTypes));
    assertThat(sql).containsIgnoringCase("FOR UPDATE");
  }

  private static void assertSortedForUpdate(
      Class<?> repositoryType,
      String methodName,
      String expectedOrderBy,
      Class<?>... parameterTypes
  ) throws Exception {
    String sql = selectSql(repositoryType.getMethod(methodName, parameterTypes));
    assertThat(sql).containsIgnoringCase(expectedOrderBy);
    assertThat(sql).containsIgnoringCase("FOR UPDATE");
  }

  private static String selectSql(Method method) {
    Select select = method.getAnnotation(Select.class);
    assertThat(select).as("%s must declare mapper SQL", method).isNotNull();
    return String.join(" ", select.value()).replaceAll("\\s+", " ");
  }

  private record Fixture(UUID userId, UUID accountId, UserPrincipal principal) {
  }

  private record PositionState(
      UUID id,
      String side,
      BigDecimal lots,
      int leverage,
      BigDecimal initialMargin,
      BigDecimal marginHeld,
      BigDecimal floatingPnl,
      long version
  ) {
  }

  private record SerializedOutcome(
      boolean baselineRemainsOpen,
      long openVersion,
      Long closedBaselineVersion
  ) {
  }
}
