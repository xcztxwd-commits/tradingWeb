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
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.trading.dto.request.CreateOcoOrderRequest;
import com.fxplatform.trading.dto.response.OcoOrderGroupResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
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
    "trading.protective-order-execution-enabled=false"
})
@ActiveProfiles("database-it")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class Task6PostgresSpotIT {

  private static final String SYMBOL = "BTCUSDT";
  private static final BigDecimal ZERO = new BigDecimal("0.00000000");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TradingAccountRepository accountRepository;
  @Autowired private OrderRepository orderRepository;
  @Autowired private TradeRepository tradeRepository;
  @Autowired private WalletBalanceRepository walletBalanceRepository;
  @Autowired private OcoOrderService ocoOrderService;
  @Autowired private PendingOrderExecutionProcessor pendingOrderExecutionProcessor;
  @Autowired private InstrumentRulesEngine instrumentRulesEngine;

  @MockBean private MarketBundleResolver marketBundleResolver;

  private final List<Fixture> fixtures = new ArrayList<>();

  @BeforeEach
  void resetProvider() {
    reset(marketBundleResolver);
  }

  @AfterEach
  void cleanTestData() {
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS task6_fail_trade_insert ON trading.trades");
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS public.task6_fail_trade_insert()");
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
  void concurrentOcoCreationReplaysOneGroupTwoOrdersAndOneSharedHold() throws Exception {
    Fixture fixture = createFixture(new BigDecimal("1000.00000000"), new BigDecimal("0.20000000"));
    CreateOcoOrderRequest request = sellOco(fixture.accountId(), uniqueKey("create-race"));
    CyclicBarrier bothResolved = new CyclicBarrier(2);
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any(CandleRequest.class)))
        .thenAnswer(invocation -> {
          bothResolved.await(5, SECONDS);
          return creationBundle(Instant.now().plusSeconds(30));
        });

    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<OcoOrderGroupResponse> first = workers.submit(
        () -> ocoOrderService.create(fixture.principal(), request));
    Future<OcoOrderGroupResponse> second = workers.submit(
        () -> ocoOrderService.create(fixture.principal(), request));
    OcoOrderGroupResponse firstResponse;
    OcoOrderGroupResponse secondResponse;
    try {
      firstResponse = first.get(12, SECONDS);
      secondResponse = second.get(12, SECONDS);
    } finally {
      workers.shutdownNow();
    }

    assertThat(firstResponse.contingencyGroupId()).isEqualTo(secondResponse.contingencyGroupId());
    List<OrderEntity> legs = group(firstResponse.contingencyGroupId());
    assertThat(legs).hasSize(2);
    assertThat(legs).filteredOn(order -> positive(order.getHoldAmount())).hasSize(1);
    assertThat(count("SELECT count(*) FROM trading.orders WHERE contingency_group_id = ?",
        firstResponse.contingencyGroupId())).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM ledger.asset_ledger_entries WHERE account_id = ? AND entry_type = 'SPOT_ORDER_LOCK'",
        fixture.accountId())).isEqualTo(1);
    assertWalletInvariant(wallet(fixture.accountId(), "BTC"));
    verify(marketBundleResolver, times(2)).resolveSpot(eq(SYMBOL), any(CandleRequest.class));
  }

  @Test
  void dualLegTriggerClaimsOneWinnerAndPersistsAtMostOneTrade() throws Exception {
    Fixture fixture = createFixture(new BigDecimal("1000.00000000"), new BigDecimal("0.20000000"));
    OcoOrderGroupResponse created = createSellOco(fixture, uniqueKey("dual-trigger"));
    List<OrderEntity> legs = group(created.contingencyGroupId());
    OrderEntity limit = leg(legs, OrderType.LIMIT);
    OrderEntity stop = leg(legs, OrderType.STOP_MARKET);
    ExecutableMarketSnapshot dualTrigger = snapshot("56000", "56010", "48000");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Boolean> first = workers.submit(() -> {
      await(start);
      return pendingOrderExecutionProcessor.process(limit, dualTrigger);
    });
    Future<Boolean> second = workers.submit(() -> {
      await(start);
      return pendingOrderExecutionProcessor.process(stop, dualTrigger);
    });
    start.countDown();
    int winners;
    try {
      winners = (first.get(12, SECONDS) ? 1 : 0) + (second.get(12, SECONDS) ? 1 : 0);
    } finally {
      workers.shutdownNow();
    }

    List<OrderEntity> reloaded = group(created.contingencyGroupId());
    assertThat(winners).isEqualTo(1);
    assertThat(reloaded).extracting(OrderEntity::getStatus)
        .containsExactlyInAnyOrder(OrderStatus.FILLED, OrderStatus.CANCELED);
    assertThat(reloaded).allSatisfy(order -> assertThat(orZero(order.getHoldAmount())).isZero());
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trading.order_events WHERE order_id IN (?, ?) AND event_type = 'ORDER_FILLED'",
        limit.getId(), stop.getId())).isEqualTo(1);
    assertWalletInvariant(wallet(fixture.accountId(), "BTC"));
    assertWalletInvariant(wallet(fixture.accountId(), "USDT"));
  }

  @Test
  void fillVersusCancelSerializesWithoutDuplicateTradeOrHoldRelease() throws Exception {
    Fixture fixture = createFixture(new BigDecimal("1000.00000000"), new BigDecimal("0.20000000"));
    OcoOrderGroupResponse created = createSellOco(fixture, uniqueKey("fill-cancel"));
    List<OrderEntity> legs = group(created.contingencyGroupId());
    OrderEntity limit = leg(legs, OrderType.LIMIT);
    OrderEntity stop = leg(legs, OrderType.STOP_MARKET);
    ExecutableMarketSnapshot stopTrigger = snapshot("48000", "48010", "48000");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Boolean> fill = workers.submit(() -> {
      await(start);
      return pendingOrderExecutionProcessor.process(stop, stopTrigger);
    });
    Future<OcoOrderGroupResponse> cancel = workers.submit(() -> {
      await(start);
      return ocoOrderService.cancelByLeg(fixture.principal(), limit.getId());
    });
    start.countDown();
    try {
      fill.get(12, SECONDS);
      assertThat(cancel.get(12, SECONDS).contingencyGroupId())
          .isEqualTo(created.contingencyGroupId());
    } finally {
      workers.shutdownNow();
    }

    List<OrderEntity> reloaded = group(created.contingencyGroupId());
    assertThat(reloaded).allSatisfy(order -> assertThat(order.getStatus())
        .isIn(OrderStatus.FILLED, OrderStatus.CANCELED));
    assertThat(reloaded).allSatisfy(order -> assertThat(orZero(order.getHoldAmount())).isZero());
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
        .isLessThanOrEqualTo(1);
    assertThat(count("SELECT count(*) FROM ledger.asset_ledger_entries WHERE account_id = ? AND entry_type = 'SPOT_ORDER_RELEASE'",
        fixture.accountId())).isLessThanOrEqualTo(1);
    assertWalletInvariant(wallet(fixture.accountId(), "BTC"));
    assertWalletInvariant(wallet(fixture.accountId(), "USDT"));
  }

  @Test
  void nonOwnerLegFillConsumesOnlyTheOwnersLockedHold() {
    Fixture fixture = createFixture(new BigDecimal("1000.00000000"), new BigDecimal("0.20000000"));
    OcoOrderGroupResponse created = createSellOco(fixture, uniqueKey("non-owner"));
    List<OrderEntity> legs = group(created.contingencyGroupId());
    UUID ownerId = legs.getFirst().getHoldOwnerOrderId();
    OrderEntity nonOwner = legs.stream().filter(order -> !order.getId().equals(ownerId))
        .findFirst().orElseThrow();
    ExecutableMarketSnapshot trigger = nonOwner.getOrderType() == OrderType.LIMIT
        ? snapshot("56000", "56010", "50000")
        : snapshot("48000", "48010", "48000");

    assertThat(pendingOrderExecutionProcessor.process(nonOwner, trigger)).isTrue();

    List<OrderEntity> reloaded = group(created.contingencyGroupId());
    assertThat(count("SELECT count(*) FROM trading.trades WHERE order_id = ?", nonOwner.getId()))
        .isEqualTo(1);
    assertThat(reloaded.stream().filter(order -> order.getId().equals(ownerId)).findFirst().orElseThrow()
        .getHoldAmount()).isEqualByComparingTo(ZERO);
    WalletBalanceEntity btc = wallet(fixture.accountId(), "BTC");
    assertThat(btc.getAvailable()).isEqualByComparingTo("0.10000000");
    assertThat(btc.getLocked()).isEqualByComparingTo(ZERO);
    assertWalletInvariant(btc);
  }

  @Test
  void injectedTradeFailureRollsBackClaimPeerCancelHoldTradeAndEvents() {
    Fixture fixture = createFixture(new BigDecimal("1000.00000000"), new BigDecimal("0.20000000"));
    OcoOrderGroupResponse created = createSellOco(fixture, uniqueKey("rollback"));
    List<OrderEntity> before = group(created.contingencyGroupId());
    OrderEntity stop = leg(before, OrderType.STOP_MARKET);
    UUID ownerId = before.getFirst().getHoldOwnerOrderId();
    BigDecimal heldBefore = before.stream().filter(order -> order.getId().equals(ownerId))
        .findFirst().orElseThrow().getHoldAmount();
    installTradeFailureTrigger(stop.getId());

    assertThatThrownBy(() -> pendingOrderExecutionProcessor.process(
        stop, snapshot("48000", "48010", "48000")))
        .isInstanceOf(RuntimeException.class);

    List<OrderEntity> reloaded = group(created.contingencyGroupId());
    assertThat(reloaded).extracting(OrderEntity::getStatus).containsOnly(OrderStatus.PENDING);
    assertThat(reloaded.stream().filter(order -> order.getId().equals(ownerId)).findFirst().orElseThrow()
        .getHoldAmount()).isEqualByComparingTo(heldBefore);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
        .isZero();
    assertThat(count("SELECT count(*) FROM trading.order_events WHERE order_id IN (?, ?) AND event_type IN ('ORDER_FILLED', 'ORDER_CANCELED')",
        before.get(0).getId(), before.get(1).getId())).isZero();
    WalletBalanceEntity btc = wallet(fixture.accountId(), "BTC");
    assertThat(btc.getAvailable()).isEqualByComparingTo("0.10000000");
    assertThat(btc.getLocked()).isEqualByComparingTo("0.10000000");
    assertWalletInvariant(btc);
  }

  @Test
  void staleAccountLockWaitRollsBackFirstAttemptAndRefetchesOneBundle() throws Exception {
    Fixture fixture = createFixture(new BigDecimal("1000.00000000"), new BigDecimal("0.20000000"));
    CountDownLatch accountLocked = new CountDownLatch(1);
    CountDownLatch releaseAccount = new CountDownLatch(1);
    CountDownLatch firstBundleResolved = new CountDownLatch(1);
    AtomicInteger resolutions = new AtomicInteger();
    AtomicBoolean cleanBeforeRetry = new AtomicBoolean();
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any(CandleRequest.class)))
        .thenAnswer(invocation -> {
          int resolution = resolutions.incrementAndGet();
          if (resolution == 1) {
            firstBundleResolved.countDown();
            return creationBundle(Instant.now().plusMillis(250));
          }
          assertThat(count("SELECT count(*) FROM trading.orders WHERE account_id = ?", fixture.accountId()))
              .isZero();
          assertThat(count("SELECT count(*) FROM trading.spot_positions WHERE account_id = ?", fixture.accountId()))
              .isZero();
          cleanBeforeRetry.set(true);
          return creationBundle(Instant.now().plusSeconds(30));
        });

    ExecutorService workers = Executors.newFixedThreadPool(2);
    TransactionTemplate locker = new TransactionTemplate(transactionManager);
    Future<?> lockFuture = workers.submit(() -> locker.executeWithoutResult(status -> {
      assertThat(accountRepository.findByIdForUpdate(fixture.accountId())).isPresent();
      accountLocked.countDown();
      await(releaseAccount);
    }));
    OcoOrderGroupResponse response;
    try {
      assertThat(accountLocked.await(5, SECONDS)).isTrue();
      Future<OcoOrderGroupResponse> create = workers.submit(() -> ocoOrderService.create(
          fixture.principal(), sellOco(fixture.accountId(), uniqueKey("stale-lock"))));
      assertThat(firstBundleResolved.await(5, SECONDS)).isTrue();
      Thread.sleep(450);
      releaseAccount.countDown();
      response = create.get(12, SECONDS);
      lockFuture.get(12, SECONDS);
    } finally {
      releaseAccount.countDown();
      workers.shutdownNow();
    }

    assertThat(cleanBeforeRetry).isTrue();
    assertThat(resolutions).hasValue(2);
    assertThat(group(response.contingencyGroupId())).hasSize(2);
    verify(marketBundleResolver, times(2)).resolveSpot(eq(SYMBOL), any(CandleRequest.class));
  }

  @Test
  void buyJumpBeyondHoldRollsBackAndNeverDebitsAvailableFunds() {
    Fixture fixture = createFixture(new BigDecimal("10000.00000000"), ZERO);
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(creationBundle(Instant.now().plusSeconds(30)));
    OcoOrderGroupResponse created = ocoOrderService.create(
        fixture.principal(), buyOco(fixture.accountId(), uniqueKey("jump")));
    List<OrderEntity> before = group(created.contingencyGroupId());
    OrderEntity stop = leg(before, OrderType.STOP_MARKET);
    WalletBalanceEntity walletBefore = wallet(fixture.accountId(), "USDT");
    BigDecimal totalBefore = walletBefore.getTotal();
    BigDecimal availableBefore = walletBefore.getAvailable();
    BigDecimal lockedBefore = walletBefore.getLocked();

    assertThatThrownBy(() -> pendingOrderExecutionProcessor.process(
        stop, snapshot("59990", "60000", "60000")))
        .isInstanceOfSatisfying(com.fxplatform.common.exception.BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("LOCKED_BALANCE_NOT_ENOUGH"));

    List<OrderEntity> reloaded = group(created.contingencyGroupId());
    assertThat(reloaded).extracting(OrderEntity::getStatus).containsOnly(OrderStatus.PENDING);
    WalletBalanceEntity walletAfter = wallet(fixture.accountId(), "USDT");
    assertThat(walletAfter.getTotal()).isEqualByComparingTo(totalBefore);
    assertThat(walletAfter.getAvailable()).isEqualByComparingTo(availableBefore);
    assertThat(walletAfter.getLocked()).isEqualByComparingTo(lockedBefore);
    assertThat(count("SELECT count(*) FROM trading.trades WHERE account_id = ?", fixture.accountId()))
        .isZero();
    assertThat(count("SELECT count(*) FROM trading.order_events WHERE order_id IN (?, ?) AND event_type IN ('ORDER_FILLED', 'ORDER_CANCELED')",
        before.get(0).getId(), before.get(1).getId())).isZero();
    assertWalletInvariant(walletAfter);
  }

  @Test
  void freshV47SpotRulesExposeStorageSafeStepsForAllFiveSymbols() {
    java.util.Map<String, BigDecimal> expectedSteps = java.util.Map.of(
        "BTCUSDT", new BigDecimal("0.0001"),
        "ETHUSDT", new BigDecimal("0.0001"),
        "BNBUSDT", new BigDecimal("0.001"),
        "SOLUSDT", new BigDecimal("0.01"),
        "XRPUSDT", BigDecimal.ONE);

    expectedSteps.forEach((symbol, expectedStep) -> {
      InstrumentRules rules = instrumentRulesEngine.rules(symbol);
      assertThat(rules.exists()).isTrue();
      assertThat(rules.productType()).isEqualTo(ProductType.CRYPTO_SPOT);
      assertThat(rules.stepSize()).isEqualByComparingTo(expectedStep);
      assertThat(rules.stepSize().remainder(new BigDecimal("0.0001"))).isZero();
    });
  }

  @Test
  void ocoCanonicalQuantityPersistsExactlyAcrossOrdersTradeAndWallet() {
    Fixture fixture = createFixture(new BigDecimal("1000.00000000"), new BigDecimal("0.20000000"));
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(creationBundle(Instant.now().plusSeconds(30)));
    String key = uniqueKey("precision");
    CreateOcoOrderRequest request = new CreateOcoOrderRequest(
        fixture.accountId(), SYMBOL, OrderSide.SELL, new BigDecimal("0.1234"),
        QuantityUnit.BASE, new BigDecimal("55000"), new BigDecimal("49000"),
        TriggerPriceType.LAST_PRICE, key, key);

    OcoOrderGroupResponse created = ocoOrderService.create(fixture.principal(), request);
    List<OrderEntity> legs = group(created.contingencyGroupId());
    assertThat(legs).allSatisfy(order -> {
      assertThat(order.getLots()).isEqualByComparingTo("0.1234");
      assertThat(order.getQuantity()).isEqualByComparingTo("0.1234");
      assertThat(order.getBaseQuantity()).isEqualByComparingTo("0.12340000");
    });

    OrderEntity limit = leg(legs, OrderType.LIMIT);
    assertThat(pendingOrderExecutionProcessor.process(
        limit, snapshot("56000", "56010", "50000"))).isTrue();

    BigDecimal persistedFilled = jdbcTemplate.queryForObject(
        "SELECT filled_quantity FROM trading.orders WHERE id = ?", BigDecimal.class, limit.getId());
    BigDecimal persistedTrade = jdbcTemplate.queryForObject(
        "SELECT lots FROM trading.trades WHERE order_id = ?", BigDecimal.class, limit.getId());
    assertThat(persistedFilled).isEqualByComparingTo("0.1234");
    assertThat(persistedTrade).isEqualByComparingTo("0.1234");
    assertWalletInvariant(wallet(fixture.accountId(), "BTC"));

    CreateOcoOrderRequest unsafePrecision = new CreateOcoOrderRequest(
        fixture.accountId(), SYMBOL, OrderSide.SELL, new BigDecimal("0.12345"),
        QuantityUnit.BASE, new BigDecimal("55000"), new BigDecimal("49000"),
        TriggerPriceType.LAST_PRICE, uniqueKey("unsafe-precision"), uniqueKey("unsafe-client"));
    assertThatThrownBy(() -> ocoOrderService.create(fixture.principal(), unsafePrecision))
        .isInstanceOfSatisfying(com.fxplatform.common.exception.BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("QUANTITY_STEP_MISMATCH"));
  }

  private OcoOrderGroupResponse createSellOco(Fixture fixture, String key) {
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(creationBundle(Instant.now().plusSeconds(30)));
    return ocoOrderService.create(fixture.principal(), sellOco(fixture.accountId(), key));
  }

  private Fixture createFixture(BigDecimal usdtAvailable, BigDecimal btcAvailable) {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    String email = "task6-it-" + userId + "@example.test";
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
    saveWallet(accountId, "USDT", usdtAvailable);
    saveWallet(accountId, "BTC", btcAvailable);
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

  private CreateOcoOrderRequest sellOco(UUID accountId, String key) {
    return oco(accountId, OrderSide.SELL, "55000", "49000", key);
  }

  private CreateOcoOrderRequest buyOco(UUID accountId, String key) {
    return oco(accountId, OrderSide.BUY, "49000", "51000", key);
  }

  private CreateOcoOrderRequest oco(
      UUID accountId,
      OrderSide side,
      String limit,
      String stop,
      String key
  ) {
    return new CreateOcoOrderRequest(
        accountId, SYMBOL, side, new BigDecimal("0.1000"), QuantityUnit.BASE,
        new BigDecimal(limit), new BigDecimal(stop), TriggerPriceType.LAST_PRICE,
        key, key);
  }

  private SpotMarketBundle creationBundle(Instant expiresAt) {
    return new SpotMarketBundle(
        SYMBOL, SYMBOL, "binance", MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("49990"), new BigDecimal("50010"), new BigDecimal("50000"),
        null, List.of(), List.of(), Instant.now(), expiresAt);
  }

  private ExecutableMarketSnapshot snapshot(String bid, String ask, String last) {
    Instant now = Instant.now();
    return new ExecutableMarketSnapshot(
        SYMBOL, ProductType.CRYPTO_SPOT, "binance", SYMBOL,
        MarketSourceMode.PUBLIC_EXTERNAL, new BigDecimal(bid), new BigDecimal(ask),
        new BigDecimal(last), null, null, now.minusSeconds(1), now.plusSeconds(30));
  }

  private List<OrderEntity> group(UUID groupId) {
    return orderRepository.findByContingencyGroupId(groupId);
  }

  private OrderEntity leg(List<OrderEntity> legs, OrderType type) {
    return legs.stream().filter(order -> order.getOrderType() == type)
        .findFirst().orElseThrow();
  }

  private WalletBalanceEntity wallet(UUID accountId, String asset) {
    return walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(
        accountId, "SPOT", asset).orElseThrow();
  }

  private void assertWalletInvariant(WalletBalanceEntity wallet) {
    assertThat(wallet.getTotal()).isEqualByComparingTo(
        wallet.getAvailable().add(wallet.getLocked()));
    assertThat(wallet.getTotal()).isGreaterThanOrEqualTo(ZERO);
    assertThat(wallet.getAvailable()).isGreaterThanOrEqualTo(ZERO);
    assertThat(wallet.getLocked()).isGreaterThanOrEqualTo(ZERO);
  }

  private void installTradeFailureTrigger(UUID orderId) {
    jdbcTemplate.execute("""
        CREATE OR REPLACE FUNCTION public.task6_fail_trade_insert()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
          IF NEW.order_id = '%s'::uuid THEN
            RAISE EXCEPTION 'task6 injected failure after peer cancellation';
          END IF;
          RETURN NEW;
        END;
        $$
        """.formatted(orderId));
    jdbcTemplate.execute("""
        CREATE TRIGGER task6_fail_trade_insert
        BEFORE INSERT ON trading.trades
        FOR EACH ROW EXECUTE FUNCTION public.task6_fail_trade_insert()
        """);
  }

  private int count(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static boolean positive(BigDecimal value) {
    return value != null && value.signum() > 0;
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
    return "task6-it-" + prefix + "-" + UUID.randomUUID();
  }

  private record Fixture(UUID userId, UUID accountId, UserPrincipal principal) {
  }
}
