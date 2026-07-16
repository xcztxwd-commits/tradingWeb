package com.fxplatform.trading.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
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
@Import(Task5PostgresFullFillIT.ProbeConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class Task5PostgresFullFillIT {

  private static final String SYMBOL = "BTCUSDT";
  private static final BigDecimal ZERO = new BigDecimal("0.00000000");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private TradingTransactionExecutor transactionExecutor;

  @Autowired
  private OuterRollbackProbe outerRollbackProbe;

  @Autowired
  private TradingAccountRepository accountRepository;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private TradeRepository tradeRepository;

  @Autowired
  private WalletBalanceRepository walletBalanceRepository;

  @Autowired
  private SpotPositionRepository spotPositionRepository;

  @Autowired
  private PositionRepository positionRepository;

  @Autowired
  private OrderService orderService;

  @Autowired
  private OrderFillService orderFillService;

  @Autowired
  private OrderEventService orderEventService;

  @Autowired
  private DemoExecutionGuard demoExecutionGuard;

  @Autowired
  private WalletService walletService;

  @Autowired
  private SpotPositionService spotPositionService;

  @Autowired
  private FullFillCoordinator fullFillCoordinator;

  @MockBean
  private MarketBundleResolver marketBundleResolver;

  @MockBean
  private RiskCheckService riskCheckService;

  @MockBean
  private QuoteService quoteService;

  private final List<Fixture> fixtures = new ArrayList<>();

  @BeforeEach
  void prepareTestInfrastructure() {
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS public.task5_transaction_probe (
          id UUID PRIMARY KEY,
          label VARCHAR(32) NOT NULL
        )
        """);
    reset(marketBundleResolver, riskCheckService, quoteService);
    when(riskCheckService.checkOrder(any(TradingAccountEntity.class), any(CreateOrderRequest.class)))
        .thenReturn(BigDecimal.ZERO);
    when(riskCheckService.resolveEffectiveLeverage(
        any(TradingAccountEntity.class), any(CreateOrderRequest.class)))
        .thenReturn(1);
    when(riskCheckService.isSpotSymbol(SYMBOL)).thenReturn(true);
    when(riskCheckService.resolveHoldCurrency(
        any(TradingAccountEntity.class), any(CreateOrderRequest.class)))
        .thenReturn("USDT");
  }

  @AfterEach
  void cleanTestData() {
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS task5_fail_trade_insert ON trading.trades");
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS public.task5_fail_trade_insert()");
    for (Fixture fixture : fixtures) {
      UUID accountId = fixture.accountId();
      jdbcTemplate.update("DELETE FROM trading.order_events WHERE order_id IN (SELECT id FROM trading.orders WHERE account_id = ?)", accountId);
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
  void springProxyCommitsRequiresNewMutationWhileOuterTransactionRollsBack() {
    UUID outerId = UUID.randomUUID();
    UUID innerId = UUID.randomUUID();

    assertThat(AopUtils.isAopProxy(transactionExecutor)).isTrue();
    assertThat(AopUtils.isAopProxy(outerRollbackProbe)).isTrue();
    assertThatThrownBy(() -> outerRollbackProbe.writeOuterThenInnerAndRollback(outerId, innerId))
        .isInstanceOf(OuterRollbackProbe.ExpectedRollback.class);

    assertThat(probeCount(outerId)).isZero();
    assertThat(probeCount(innerId)).isEqualTo(1);
    jdbcTemplate.update("DELETE FROM public.task5_transaction_probe WHERE id IN (?, ?)", outerId, innerId);
  }

  @Test
  void accountForUpdateWaitExpiresFirstSnapshotThenOrderServiceRefetchesOutsideTransaction()
      throws Exception {
    Fixture fixture = createFixture(new BigDecimal("1000.00000000"), ZERO);
    CountDownLatch accountLocked = new CountDownLatch(1);
    CountDownLatch releaseAccount = new CountDownLatch(1);
    CountDownLatch firstBundleResolved = new CountDownLatch(1);
    AtomicInteger resolutions = new AtomicInteger();
    AtomicBoolean observedCleanRollbackBeforeRetry = new AtomicBoolean();

    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any(CandleRequest.class)))
        .thenAnswer(invocation -> {
          int resolution = resolutions.incrementAndGet();
          if (resolution == 1) {
            firstBundleResolved.countDown();
            return spotBundle(Instant.now().plusMillis(250));
          }
          assertThat(count("SELECT count(*) FROM trading.orders WHERE account_id = ?", fixture.accountId()))
              .as("the expired attempt must not leave an order")
              .isZero();
          assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
              .as("the expired attempt must not leave a trade")
              .isZero();
          assertThat(count("SELECT count(*) FROM trading.spot_positions WHERE account_id = ?", fixture.accountId()))
              .as("the expired attempt must not leave ensure-created spot state")
              .isZero();
          observedCleanRollbackBeforeRetry.set(true);
          return spotBundle(Instant.now().plusSeconds(30));
        });

    ExecutorService workers = Executors.newFixedThreadPool(2);
    TransactionTemplate lockerTransaction = new TransactionTemplate(transactionManager);
    Future<?> locker = workers.submit(() -> lockerTransaction.executeWithoutResult(status -> {
      assertThat(accountRepository.findByIdForUpdate(fixture.accountId())).isPresent();
      accountLocked.countDown();
      await(releaseAccount);
    }));

    OrderResponse response;
    try {
      assertThat(accountLocked.await(5, SECONDS)).isTrue();
      Future<OrderResponse> creation = workers.submit(() -> orderService.createOrder(
          fixture.principal(), marketRequest(fixture.accountId(), uniqueKey("stale-retry"))));
      assertThat(firstBundleResolved.await(5, SECONDS)).isTrue();
      Thread.sleep(450);
      releaseAccount.countDown();
      response = creation.get(10, SECONDS);
      locker.get(10, SECONDS);
    } finally {
      releaseAccount.countDown();
      workers.shutdownNow();
    }

    assertThat(observedCleanRollbackBeforeRetry).isTrue();
    assertThat(resolutions).hasValue(2);
    verify(marketBundleResolver, times(2)).resolveSpot(eq(SYMBOL), any(CandleRequest.class));
    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(count("SELECT count(*) FROM trading.orders WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trading.spot_positions WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(1);
  }

  @Test
  void databaseFailureAfterPendingClaimRollsBackStatusAndPreservesWalletHold() {
    Fixture fixture = createFixture(new BigDecimal("900.00000000"), new BigDecimal("100.00000000"));
    OrderEntity pending = savePendingLimit(fixture, uniqueKey("claim-rollback"));
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(spotBundle(Instant.now().plusSeconds(30)));
    installTradeFailureTrigger(pending.getId());

    int fills = pendingService().executePendingOrders();

    OrderEntity reloaded = orderRepository.findById(pending.getId()).orElseThrow();
    WalletBalanceEntity usdt = walletBalanceRepository
        .findByAccountIdAndWalletTypeAndAsset(fixture.accountId(), "SPOT", "USDT")
        .orElseThrow();
    assertThat(fills).isZero();
    assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(reloaded.getHoldAmount()).isEqualByComparingTo("100.00000000");
    assertThat(reloaded.getFilledQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(reloaded.getRemainingQuantity()).isEqualByComparingTo("0.01000000");
    assertThat(usdt.getAvailable()).isEqualByComparingTo("900.00000000");
    assertThat(usdt.getLocked()).isEqualByComparingTo("100.00000000");
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", pending.getId())).isZero();
    assertThat(count("SELECT count(*) FROM ledger.asset_ledger_entries WHERE account_id = ?", fixture.accountId()))
        .isZero();
  }

  @Test
  void twoPendingWorkersClaimOneOrderAndPersistAtMostOneTrade() throws Exception {
    Fixture fixture = createFixture(new BigDecimal("900.00000000"), new BigDecimal("100.00000000"));
    OrderEntity pending = savePendingLimit(fixture, uniqueKey("two-pending-workers"));
    CyclicBarrier bothResolved = new CyclicBarrier(2);
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any(CandleRequest.class)))
        .thenAnswer(invocation -> {
          bothResolved.await(5, SECONDS);
          return spotBundle(Instant.now().plusSeconds(30));
        });

    PendingOrderExecutionService service = pendingService();
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Integer> first = workers.submit(service::executePendingOrders);
    Future<Integer> second = workers.submit(service::executePendingOrders);
    int totalFills;
    try {
      totalFills = first.get(10, SECONDS) + second.get(10, SECONDS);
    } finally {
      workers.shutdownNow();
    }

    OrderEntity reloaded = orderRepository.findById(pending.getId()).orElseThrow();
    assertThat(totalFills).isEqualTo(1);
    assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(reloaded.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(reloaded.getFilledQuantity()).isEqualByComparingTo("0.01000000");
    assertThat(reloaded.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", pending.getId()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trading.order_events WHERE order_id = ? AND event_type = 'ORDER_FILLED'", pending.getId()))
        .isEqualTo(1);
  }

  @Test
  void concurrentSameIdempotencyKeyReturnsTheUniqueCommittedOrderAndSingleTrade() throws Exception {
    Fixture fixture = createFixture(new BigDecimal("1000.00000000"), ZERO);
    String key = uniqueKey("idempotent-race");
    CreateOrderRequest request = marketRequest(fixture.accountId(), key);
    CyclicBarrier bothResolved = new CyclicBarrier(2);
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any(CandleRequest.class)))
        .thenAnswer(invocation -> {
          bothResolved.await(5, SECONDS);
          return spotBundle(Instant.now().plusSeconds(30));
        });

    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<OrderResponse> first = workers.submit(() -> orderService.createOrder(fixture.principal(), request));
    Future<OrderResponse> second = workers.submit(() -> orderService.createOrder(fixture.principal(), request));
    OrderResponse firstResponse;
    OrderResponse secondResponse;
    try {
      firstResponse = first.get(10, SECONDS);
      secondResponse = second.get(10, SECONDS);
    } finally {
      workers.shutdownNow();
    }

    assertThat(firstResponse.id()).isEqualTo(secondResponse.id());
    assertThat(firstResponse.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(secondResponse.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(count("SELECT count(*) FROM trading.orders WHERE user_id = ? AND idempotency_key = ?", fixture.userId(), key))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", firstResponse.id()))
        .isEqualTo(1);
    verify(marketBundleResolver, times(2)).resolveSpot(eq(SYMBOL), any(CandleRequest.class));
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
        fullFillCoordinator);
  }

  private Fixture createFixture(BigDecimal usdtAvailable, BigDecimal usdtLocked) {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    String email = "task5-it-" + userId + "@example.test";
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
    account.setLeverage(10);
    account.setStatus(AccountStatus.ACTIVE);
    accountRepository.save(account);

    saveWallet(accountId, "USDT", usdtAvailable.add(usdtLocked), usdtAvailable, usdtLocked);
    saveWallet(accountId, "BTC", ZERO, ZERO, ZERO);
    Fixture fixture = new Fixture(userId, accountId, new UserPrincipal(userId, email, "USER"));
    fixtures.add(fixture);
    return fixture;
  }

  private void saveWallet(
      UUID accountId,
      String asset,
      BigDecimal total,
      BigDecimal available,
      BigDecimal locked
  ) {
    WalletBalanceEntity wallet = new WalletBalanceEntity();
    wallet.setId(UUID.randomUUID());
    wallet.setAccountId(accountId);
    wallet.setWalletType("SPOT");
    wallet.setAsset(asset);
    wallet.setTotal(total);
    wallet.setAvailable(available);
    wallet.setLocked(locked);
    walletBalanceRepository.save(wallet);
  }

  private OrderEntity savePendingLimit(Fixture fixture, String key) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(fixture.userId());
    order.setAccountId(fixture.accountId());
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CASH);
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal("0.0100"));
    order.setQuantity(new BigDecimal("0.0100"));
    order.setOriginalQuantity(new BigDecimal("0.01000000"));
    order.setBaseQuantity(new BigDecimal("0.01000000"));
    order.setRequestedPrice(new BigDecimal("10000.0000000000"));
    order.setPrice(new BigDecimal("10000.0000000000"));
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(new BigDecimal("0.0100"));
    order.setHoldAmount(new BigDecimal("100.00000000"));
    order.setHoldCurrency("USDT");
    order.setClientOrderId(key);
    order.setIdempotencyKey(key);
    order.setLeverage(1);
    order.setReduceOnly(false);
    return orderRepository.save(order);
  }

  private CreateOrderRequest marketRequest(UUID accountId, String key) {
    BigDecimal quantity = new BigDecimal("100.00000000");
    return new CreateOrderRequest(
        accountId,
        SYMBOL,
        OrderSide.BUY,
        OrderType.MARKET,
        quantity,
        null,
        null,
        null,
        key,
        key,
        quantity,
        null,
        1,
        PositionSide.BOTH,
        QuantityUnit.QUOTE,
        MarginMode.CASH,
        null,
        null,
        false,
        List.of());
  }

  private SpotMarketBundle spotBundle(Instant expiresAt) {
    Instant asOf = Instant.now();
    return new SpotMarketBundle(
        SYMBOL,
        SYMBOL,
        "binance",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("8999.0000000000"),
        new BigDecimal("9000.0000000000"),
        new BigDecimal("8999.5000000000"),
        null,
        null,
        null,
        asOf,
        expiresAt);
  }

  private void installTradeFailureTrigger(UUID orderId) {
    jdbcTemplate.execute("""
        CREATE OR REPLACE FUNCTION public.task5_fail_trade_insert()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
          IF NEW.order_id = '%s'::uuid THEN
            RAISE EXCEPTION 'task5 injected failure after pending claim';
          END IF;
          RETURN NEW;
        END;
        $$
        """.formatted(orderId));
    jdbcTemplate.execute("""
        CREATE TRIGGER task5_fail_trade_insert
        BEFORE INSERT ON trading.trades
        FOR EACH ROW EXECUTE FUNCTION public.task5_fail_trade_insert()
        """);
  }

  private int probeCount(UUID id) {
    return count("SELECT count(*) FROM public.task5_transaction_probe WHERE id = ?", id);
  }

  private int count(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, SECONDS)) {
        throw new IllegalStateException("Timed out waiting for test coordination latch");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for test coordination latch", exception);
    }
  }

  private static String uniqueKey(String prefix) {
    return "task5-it-" + prefix + "-" + UUID.randomUUID();
  }

  private record Fixture(UUID userId, UUID accountId, UserPrincipal principal) {
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeConfiguration {

    @Bean
    OuterRollbackProbe outerRollbackProbe(
        JdbcTemplate jdbcTemplate,
        TradingTransactionExecutor transactionExecutor
    ) {
      return new OuterRollbackProbe(jdbcTemplate, transactionExecutor);
    }
  }

  static class OuterRollbackProbe {

    private final JdbcTemplate jdbcTemplate;
    private final TradingTransactionExecutor transactionExecutor;

    OuterRollbackProbe(
        JdbcTemplate jdbcTemplate,
        TradingTransactionExecutor transactionExecutor
    ) {
      this.jdbcTemplate = jdbcTemplate;
      this.transactionExecutor = transactionExecutor;
    }

    @Transactional
    public void writeOuterThenInnerAndRollback(UUID outerId, UUID innerId) {
      jdbcTemplate.update(
          "INSERT INTO public.task5_transaction_probe (id, label) VALUES (?, 'OUTER')",
          outerId);
      transactionExecutor.execute(() -> {
        jdbcTemplate.update(
            "INSERT INTO public.task5_transaction_probe (id, label) VALUES (?, 'INNER')",
            innerId);
        return null;
      });
      throw new ExpectedRollback();
    }

    static class ExpectedRollback extends RuntimeException {
    }
  }
}
