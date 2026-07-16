package com.fxplatform.database;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.UpdateSymbolSettingsRequest;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.DemoAccountLifecycleService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.request.CreateProtectionRequest;
import com.fxplatform.trading.dto.request.UpdateProtectionRequest;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.OrderService;
import com.fxplatform.trading.service.ProtectionOrderService;
import com.fxplatform.trading.service.TradingSettingsService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
class ProtectionOrderConcurrencyIT {

  private static final String SYMBOL = "BTCUSDT-PERP";
  private static final String PROTECTION_LOCK_ORDER =
      "account -> symbol setting -> sorted positions -> UUID-sorted orders -> ledger/events";

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired DemoAccountLifecycleService lifecycleService;
  @Autowired OrderService orderService;
  @Autowired ProtectionOrderService protectionOrderService;
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
  void concurrentReplayCreatesOneProtectionCarrierAndOneCreationEvent() throws Exception {
    Fixture fixture = createFixture("replay");
    stubBundle(bundle("99", "101", "100", "100"));
    UUID positionId = openLong(fixture, "2");
    String clientOrderId = "task17-protection-replay-" + UUID.randomUUID();
    CreateProtectionRequest request = protectionRequest(
        ProtectionType.TAKE_PROFIT, "1", "110", clientOrderId);

    List<Attempt<OrderResponse>> attempts = runConcurrently(
        () -> protectionOrderService.create(fixture.userId(), positionId, request),
        () -> protectionOrderService.create(fixture.userId(), positionId, request));

    assertThat(attempts).allMatch(Attempt::succeeded);
    assertThat(attempts.stream().map(attempt -> attempt.value().id()).distinct()).hasSize(1);
    UUID protectionId = attempts.get(0).value().id();
    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE account_id = ? AND parent_position_id = ?
          AND protection_type = 'TAKE_PROFIT' AND idempotency_key = ?
        """, fixture.accountId(), positionId, clientOrderId)).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'PROTECTION_CREATED'
        """, protectionId)).isEqualTo(1);
    assertThat(longValue("SELECT version FROM trading.orders WHERE id = ?", protectionId))
        .isZero();
  }

  @Test
  void concurrentDistinctProtectionsCannotOverbookOnePositionQuantitySlot() throws Exception {
    Fixture fixture = createFixture("budget");
    stubBundle(bundle("99", "101", "100", "100"));
    UUID positionId = openLong(fixture, "2");

    List<Attempt<OrderResponse>> attempts = runConcurrently(
        () -> protectionOrderService.create(
            fixture.userId(),
            positionId,
            protectionRequest(
                ProtectionType.TAKE_PROFIT,
                "2",
                "110",
                "task17-protection-budget-a-" + UUID.randomUUID())),
        () -> protectionOrderService.create(
            fixture.userId(),
            positionId,
            protectionRequest(
                ProtectionType.TAKE_PROFIT,
                "2",
                "111",
                "task17-protection-budget-b-" + UUID.randomUUID())));

    assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
    assertThat(attempts.stream().filter(attempt -> !attempt.succeeded())).singleElement()
        .satisfies(attempt -> assertBusinessCode(
            attempt.error(), ErrorCode.PROTECTION_QUANTITY_EXCEEDED));
    assertThat(count("""
        SELECT count(*) FROM trading.orders
        WHERE parent_position_id = ? AND protection_type = 'TAKE_PROFIT'
          AND status = 'PENDING_ACTIVATION'
        """, positionId)).isEqualTo(1);
    assertThat(decimal("""
        SELECT sum(base_quantity) FROM trading.orders
        WHERE parent_position_id = ? AND protection_type = 'TAKE_PROFIT'
          AND status = 'PENDING_ACTIVATION'
        """, positionId)).isEqualByComparingTo("2");
    assertThat(longValue("SELECT version FROM trading.positions WHERE id = ?", positionId))
        .isZero();
  }

  @Test
  void concurrentExpectedVersionUpdatesSerializeWithoutDeadlockAndMutateOnce() throws Exception {
    Fixture fixture = createFixture("version");
    stubBundle(bundle("99", "101", "100", "100"));
    UUID positionId = openLong(fixture, "2");
    OrderResponse protection = protectionOrderService.create(
        fixture.userId(),
        positionId,
        protectionRequest(
            ProtectionType.TAKE_PROFIT,
            "1",
            "110",
            "task17-protection-version-" + UUID.randomUUID()));
    long positionVersion = longValue(
        "SELECT version FROM trading.positions WHERE id = ?", positionId);

    // Both mutations traverse account -> setting -> sorted position -> sorted active orders.
    // The bounded Future wait turns a lock-order inversion into an explicit test failure.
    List<Attempt<OrderResponse>> attempts = runConcurrently(
        () -> protectionOrderService.update(
            fixture.userId(), protection.id(), updateRequest("1", "111", 0L)),
        () -> protectionOrderService.update(
            fixture.userId(), protection.id(), updateRequest("1", "112", 0L)));

    assertThat(attempts.stream().filter(Attempt::succeeded))
        .as(PROTECTION_LOCK_ORDER)
        .hasSize(1);
    assertThat(attempts.stream().filter(attempt -> !attempt.succeeded())).singleElement()
        .satisfies(attempt -> assertBusinessCode(
            attempt.error(), ErrorCode.PROTECTION_VERSION_CONFLICT));
    assertThat(longValue("SELECT version FROM trading.orders WHERE id = ?", protection.id()))
        .isEqualTo(1L);
    assertThat(decimal("SELECT trigger_price FROM trading.orders WHERE id = ?", protection.id())
        .stripTrailingZeros().toPlainString()).isIn("111", "112");
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'PROTECTION_UPDATED'
        """, protection.id())).isEqualTo(1);
    assertThat(longValue("SELECT version FROM trading.positions WHERE id = ?", positionId))
        .isEqualTo(positionVersion);
  }

  @Test
  void accountFirstProtectionAndSettingsRaceLeavesDownstreamRowsUnlocked() throws Exception {
    Fixture fixture = createFixture("protection-settings-lock-order");
    stubBundle(bundle("99", "101", "100", "100"));
    UUID positionId = openLong(fixture, "2");
    OrderResponse protection = protectionOrderService.create(
        fixture.userId(),
        positionId,
        protectionRequest(
            ProtectionType.TAKE_PROFIT,
            "1",
            "110",
            "task17-protection-settings-" + UUID.randomUUID()));
    long positionVersion = longValue(
        "SELECT version FROM trading.positions WHERE id = ?", positionId);

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

    Future<OrderResponse> protectionUpdate = null;
    Future<?> settingsUpdate = null;
    try {
      assertThat(accountLocked.await(5, SECONDS)).isTrue();
      assertThat(accountLockHolderPid.get()).isPositive();

      protectionUpdate = workers.submit(() -> protectionOrderService.update(
          fixture.userId(), protection.id(), updateRequest("1", "111", 0L)));
      settingsUpdate = workers.submit(() -> settingsService.updateSymbolSettings(
          fixture.userId(),
          fixture.accountId(),
          SYMBOL,
          new UpdateSymbolSettingsRequest(null, null, QuantityUnit.QUOTE, 0L)));

      awaitAccountLockWaiters(accountLockHolderPid.get(), 2);
      assertThat(protectionUpdate.isDone()).as(PROTECTION_LOCK_ORDER).isFalse();
      assertThat(settingsUpdate.isDone()).as(PROTECTION_LOCK_ORDER).isFalse();
      assertDownstreamRowLockAvailable(
          "protection must not lock its position before the account row",
          "SELECT id FROM trading.positions WHERE id = ? FOR UPDATE",
          positionId);
      assertDownstreamRowLockAvailable(
          "protection must not lock its order before the account row",
          "SELECT id FROM trading.orders WHERE id = ? FOR UPDATE",
          protection.id());

      assertThat(longValue("SELECT version FROM trading.orders WHERE id = ?", protection.id()))
          .isZero();
      assertThat(longValue("SELECT version FROM trading.positions WHERE id = ?", positionId))
          .isEqualTo(positionVersion);
      assertThat(longValue("""
          SELECT version FROM trading.account_symbol_settings
          WHERE account_id = ? AND symbol = ?
          """, fixture.accountId(), SYMBOL)).isZero();

      releaseAccount.countDown();
      lockHolder.get(10, SECONDS);
      OrderResponse updated = protectionUpdate.get(20, SECONDS);
      settingsUpdate.get(20, SECONDS);
      assertThat(updated.id()).isEqualTo(protection.id());
    } finally {
      releaseAccount.countDown();
      cancelAndAwait(workers, lockHolder, protectionUpdate, settingsUpdate);
    }

    assertThat(longValue("SELECT version FROM trading.orders WHERE id = ?", protection.id()))
        .isEqualTo(1L);
    assertThat(decimal("SELECT trigger_price FROM trading.orders WHERE id = ?", protection.id()))
        .isEqualByComparingTo("111");
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id = ? AND event_type = 'PROTECTION_UPDATED'
        """, protection.id())).isEqualTo(1);
    assertThat(longValue("SELECT version FROM trading.positions WHERE id = ?", positionId))
        .isEqualTo(positionVersion);
    assertThat(count("""
        SELECT count(*) FROM trading.account_symbol_settings
        WHERE account_id = ? AND symbol = ? AND quantity_unit = 'QUOTE' AND version = 1
        """, fixture.accountId(), SYMBOL)).isEqualTo(1);
  }

  @Test
  void protectionRepositoriesDeclareTheCanonicalSortedLockScope() throws Exception {
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
        "findActiveLinearPerpBySymbolForUpdate",
        "ORDER BY id",
        UUID.class,
        String.class);
  }

  private Fixture createFixture(String suffix) {
    UUID userId = UUID.randomUUID();
    String email = "task17-protection-" + suffix + '-' + userId + "@example.test";
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

  private UUID openLong(Fixture fixture, String quantity) {
    String key = "task17-protection-open-" + UUID.randomUUID();
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
            new BigDecimal(quantity),
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

  private CreateProtectionRequest protectionRequest(
      ProtectionType protectionType,
      String quantity,
      String trigger,
      String clientOrderId
  ) {
    return new CreateProtectionRequest(
        protectionType,
        new BigDecimal(quantity),
        QuantityUnit.BASE,
        new BigDecimal(trigger),
        TriggerExecutionType.MARKET,
        null,
        clientOrderId);
  }

  private UpdateProtectionRequest updateRequest(
      String quantity,
      String trigger,
      long expectedVersion
  ) {
    return new UpdateProtectionRequest(
        new BigDecimal(quantity),
        QuantityUnit.BASE,
        new BigDecimal(trigger),
        TriggerExecutionType.MARKET,
        null,
        expectedVersion);
  }

  private void stubBundle(PerpetualMarketBundle market) {
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
        .as("exactly the protection and settings flows must wait behind the account holder")
        .isEqualTo(expected);
  }

  private void assertDownstreamRowLockAvailable(String description, String sql, UUID id) {
    assertThatCode(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      jdbcTemplate.execute("SET LOCAL lock_timeout = '500ms'");
      jdbcTemplate.queryForObject(sql, UUID.class, id);
    }))
        .as(description + " (" + PROTECTION_LOCK_ORDER + ")")
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
  private static <T> List<Attempt<T>> runConcurrently(Callable<T>... calls) throws Exception {
    CyclicBarrier start = new CyclicBarrier(calls.length);
    ExecutorService workers = Executors.newFixedThreadPool(calls.length);
    List<Future<Attempt<T>>> futures = new ArrayList<>();
    try {
      for (Callable<T> call : calls) {
        futures.add(workers.submit(() -> {
          start.await(5, SECONDS);
          try {
            return new Attempt<T>(call.call(), null);
          } catch (Throwable error) {
            return new Attempt<T>(null, error);
          }
        }));
      }
      List<Attempt<T>> attempts = new ArrayList<>();
      for (Future<Attempt<T>> future : futures) {
        attempts.add(future.get(20, SECONDS));
      }
      return attempts;
    } finally {
      cancelAndAwait(workers, futures.toArray(Future<?>[]::new));
    }
  }

  private static void cancelAndAwait(ExecutorService workers, Future<?>... futures) {
    for (Future<?> future : futures) {
      if (future != null) {
        future.cancel(true);
      }
    }
    workers.shutdownNow();
    try {
      if (!workers.awaitTermination(10, SECONDS)) {
        throw new AssertionError("Timed out waiting for concurrency workers to terminate");
      }
    } catch (InterruptedException exception) {
      workers.shutdownNow();
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while terminating concurrency workers", exception);
    }
  }

  private static void assertBusinessCode(Throwable error, String expectedCode) {
    assertThat(error).isInstanceOf(BusinessException.class);
    assertThat(((BusinessException) error).getCode()).isEqualTo(expectedCode);
  }

  private static void assertForUpdate(
      Class<?> repositoryType,
      String methodName,
      Class<?>... parameterTypes
  ) throws Exception {
    assertThat(selectSql(repositoryType.getMethod(methodName, parameterTypes)))
        .as(PROTECTION_LOCK_ORDER)
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
        .as(PROTECTION_LOCK_ORDER)
        .containsIgnoringCase(expectedOrderBy)
        .containsIgnoringCase("FOR UPDATE");
  }

  private static String selectSql(Method method) {
    Select select = method.getAnnotation(Select.class);
    assertThat(select).as("%s must declare mapper SQL", method).isNotNull();
    return String.join(" ", select.value()).replaceAll("\\s+", " ");
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

  private record Fixture(UUID userId, UUID accountId, UserPrincipal principal) {
  }

  private record Attempt<T>(T value, Throwable error) {

    boolean succeeded() {
      return error == null;
    }
  }
}
