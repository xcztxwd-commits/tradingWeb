package com.fxplatform.database;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AccountTransferRequest.Direction;
import com.fxplatform.account.dto.AccountTransferResponse;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.AccountTransferService;
import com.fxplatform.account.service.DemoAccountLifecycleService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.trading.dto.request.CreateOcoOrderRequest;
import com.fxplatform.trading.dto.response.OcoOrderGroupResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.trading.service.OcoOrderService;
import com.fxplatform.trading.service.PendingOrderExecutionProcessor;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL races for the demo Spot/OCO and Spot-to-Perpetual transfer paths. */
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
class DemoTradingConcurrencyIT {

  private static final String SYMBOL = "BTCUSDT";
  private static final BigDecimal ZERO = new BigDecimal("0.00000000");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DemoAccountLifecycleService lifecycleService;
  @Autowired private AccountTransferService transferService;
  @Autowired private WalletBalanceRepository walletBalanceRepository;
  @Autowired private OrderRepository orderRepository;
  @Autowired private OcoOrderService ocoOrderService;
  @Autowired private PendingOrderExecutionProcessor pendingOrderExecutionProcessor;

  @MockBean private MarketBundleResolver marketBundleResolver;

  private final List<Fixture> fixtures = new ArrayList<>();

  @BeforeEach
  void resetMarketProvider() {
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
      jdbcTemplate.update("DELETE FROM trading.account_symbol_settings WHERE account_id = ?", accountId);
      jdbcTemplate.update("DELETE FROM core.trading_accounts WHERE id = ?", accountId);
      jdbcTemplate.update("DELETE FROM auth.users WHERE id = ?", fixture.userId());
    }
    fixtures.clear();
  }

  @Test
  void fillVersusCancelCorrelatesWinnerTradeEventReleaseAndConservationExactly()
      throws Exception {
    Fixture fixture = createFixture("fill-cancel", true);
    BigDecimal btcBefore = wallet(fixture.accountId(), "BTC").getTotal();
    BigDecimal usdtBefore = wallet(fixture.accountId(), "USDT").getTotal();
    OcoOrderGroupResponse created = createSellOco(fixture, uniqueKey("fill-cancel"));
    List<OrderEntity> legs = group(created.contingencyGroupId());
    OrderEntity limit = leg(legs, OrderType.LIMIT);
    OrderEntity stop = leg(legs, OrderType.STOP_MARKET);
    ExecutableMarketSnapshot trigger = snapshot("48000", "48010", "48000");
    RaceGate gate = new RaceGate(2);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Boolean> fill = workers.submit(() -> {
      gate.awaitStart();
      return pendingOrderExecutionProcessor.process(stop, trigger);
    });
    Future<OcoOrderGroupResponse> cancel = workers.submit(() -> {
      gate.awaitStart();
      return ocoOrderService.cancelByLeg(fixture.principal(), limit.getId());
    });
    boolean fillReturned;
    try {
      gate.release();
      fillReturned = fill.get(15, SECONDS);
      assertThat(cancel.get(15, SECONDS).contingencyGroupId())
          .isEqualTo(created.contingencyGroupId());
    } finally {
      workers.shutdownNow();
    }

    assertOcoOutcome(fixture.accountId(), created.contingencyGroupId(), fillReturned);
    assertSpotConservation(fixture.accountId(), btcBefore, usdtBefore);
    assertWalletAndAccountInvariants(fixture.accountId());
  }

  @Test
  void simultaneousOcoLegsClaimExactlyOneWinnerAndSettleExactlyOnce() throws Exception {
    Fixture fixture = createFixture("leg-race", true);
    BigDecimal btcBefore = wallet(fixture.accountId(), "BTC").getTotal();
    BigDecimal usdtBefore = wallet(fixture.accountId(), "USDT").getTotal();
    OcoOrderGroupResponse created = createSellOco(fixture, uniqueKey("leg-race"));
    List<OrderEntity> legs = group(created.contingencyGroupId());
    OrderEntity limit = leg(legs, OrderType.LIMIT);
    OrderEntity stop = leg(legs, OrderType.STOP_MARKET);
    ExecutableMarketSnapshot dualTrigger = snapshot("56000", "56010", "48000");
    RaceGate gate = new RaceGate(2);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Boolean> limitFill = workers.submit(() -> {
      gate.awaitStart();
      return pendingOrderExecutionProcessor.process(limit, dualTrigger);
    });
    Future<Boolean> stopFill = workers.submit(() -> {
      gate.awaitStart();
      return pendingOrderExecutionProcessor.process(stop, dualTrigger);
    });
    int winners;
    try {
      gate.release();
      winners = (limitFill.get(15, SECONDS) ? 1 : 0)
          + (stopFill.get(15, SECONDS) ? 1 : 0);
    } finally {
      workers.shutdownNow();
    }

    assertThat(winners).isEqualTo(1);
    assertOcoOutcome(fixture.accountId(), created.contingencyGroupId(), true);
    assertTradeUniquenessGuardRejectsDuplicate(fixture.accountId());
    assertSpotConservation(fixture.accountId(), btcBefore, usdtBefore);
    assertWalletAndAccountInvariants(fixture.accountId());
  }

  @Test
  void simultaneousTransfersAllowOneWinnerAndConserveSpotPlusPerpetualUsdt() throws Exception {
    Fixture fixture = createFixture("transfer-race", false);
    RaceGate gate = new RaceGate(2);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Boolean> first = workers.submit(
        () -> transferAttempt(fixture, gate, UUID.randomUUID()));
    Future<Boolean> second = workers.submit(
        () -> transferAttempt(fixture, gate, UUID.randomUUID()));
    List<Boolean> outcomes;
    try {
      gate.release();
      outcomes = List.of(first.get(15, SECONDS), second.get(15, SECONDS));
    } finally {
      workers.shutdownNow();
    }

    assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    WalletBalanceEntity spot = wallet(fixture.accountId(), "USDT");
    BigDecimal perpetual = decimal(
        "SELECT balance FROM core.trading_accounts WHERE id = ?", fixture.accountId());
    assertThat(spot.getAvailable()).isEqualByComparingTo("10000.00000000");
    assertThat(spot.getTotal()).isEqualByComparingTo("10000.00000000");
    assertThat(spot.getLocked()).isEqualByComparingTo(ZERO);
    assertThat(perpetual).isEqualByComparingTo("90000.00000000");
    assertThat(spot.getTotal().add(perpetual)).isEqualByComparingTo("100000.00000000");
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND reference_type = 'TRANSFER'
        """, fixture.accountId())).isEqualTo(1L);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'TRANSFER'
        """, fixture.accountId())).isEqualTo(1L);
    assertWalletAndAccountInvariants(fixture.accountId());
  }

  @Test
  void transferFillAndCancelShareOneAccountWithoutLockInversionOrConservationDrift()
      throws Exception {
    Fixture fixture = createFixture("transfer-fill-cancel", true);
    BigDecimal btcBefore = wallet(fixture.accountId(), "BTC").getTotal();
    BigDecimal spotUsdtBefore = wallet(fixture.accountId(), "USDT").getTotal();
    BigDecimal perpUsdtBefore = decimal(
        "SELECT balance FROM core.trading_accounts WHERE id = ?", fixture.accountId());
    OcoOrderGroupResponse created = createSellOco(
        fixture, uniqueKey("transfer-fill-cancel"));
    List<OrderEntity> legs = group(created.contingencyGroupId());
    OrderEntity limit = leg(legs, OrderType.LIMIT);
    OrderEntity stop = leg(legs, OrderType.STOP_MARKET);
    UUID transferId = UUID.randomUUID();
    BigDecimal transferAmount = new BigDecimal("40000.00000000");
    ExecutableMarketSnapshot trigger = snapshot("48000", "48010", "48000");
    RaceGate gate = new RaceGate(3);
    ExecutorService workers = Executors.newFixedThreadPool(3);
    Future<AccountTransferResponse> transfer = workers.submit(() -> {
      gate.awaitStart();
      return transferService.transfer(
          fixture.userId(), fixture.accountId(), Direction.SPOT_TO_PERP,
          transferAmount, transferId);
    });
    Future<Boolean> fill = workers.submit(() -> {
      gate.awaitStart();
      return pendingOrderExecutionProcessor.process(stop, trigger);
    });
    Future<OcoOrderGroupResponse> cancel = workers.submit(() -> {
      gate.awaitStart();
      return ocoOrderService.cancelByLeg(fixture.principal(), limit.getId());
    });
    AccountTransferResponse transferred;
    boolean fillReturned;
    try {
      gate.release();
      transferred = transfer.get(20, SECONDS);
      fillReturned = fill.get(20, SECONDS);
      assertThat(cancel.get(20, SECONDS).contingencyGroupId())
          .isEqualTo(created.contingencyGroupId());
    } finally {
      workers.shutdownNow();
    }

    assertThat(transferred.transferId()).isEqualTo(transferId);
    assertThat(transferred.direction()).isEqualTo(Direction.SPOT_TO_PERP);
    assertThat(transferred.amount()).isEqualByComparingTo(transferAmount);
    assertThat(transferred.replayed()).isFalse();
    assertOcoOutcome(fixture.accountId(), created.contingencyGroupId(), fillReturned);
    assertTransferLedger(fixture.accountId(), transferId, transferAmount);
    assertCrossFlowConservation(
        fixture.accountId(), btcBefore, spotUsdtBefore, perpUsdtBefore, transferAmount);
    assertWalletAndAccountInvariants(fixture.accountId());
  }

  @Test
  void repositoryLocksKeepTheGlobalAccountWalletPositionOrderSequenceDeterministic()
      throws Exception {
    String accountLock = selectSql(
        TradingAccountRepository.class, "findByIdForUpdate", UUID.class);
    String walletLocks = selectSql(
        WalletBalanceRepository.class, "findByAccountIdForUpdate", UUID.class);
    String transferWalletLock = selectSql(
        WalletBalanceRepository.class,
        "findByAccountIdAndWalletTypeAndAssetForUpdate",
        UUID.class,
        String.class,
        String.class);
    String spotPositionLocks = selectSql(
        SpotPositionRepository.class, "findByAccountIdForUpdate", UUID.class);
    String perpPositionLocks = selectSql(
        PositionRepository.class, "findOpenLinearPerpByAccountIdForUpdate", UUID.class);
    String groupOrderLocks = selectSql(
        OrderRepository.class, "findByContingencyGroupIdForUpdate", UUID.class);
    String perpOrderLocks = selectSql(
        OrderRepository.class, "findActiveLinearPerpByAccountIdForUpdate", UUID.class);

    assertThat(accountLock).contains("FROM CORE.TRADING_ACCOUNTS", "FOR UPDATE");
    assertThat(walletLocks).contains("ORDER BY WALLET_TYPE, ASSET", "FOR UPDATE");
    assertThat(transferWalletLock)
        .contains("FROM CORE.WALLET_BALANCES", "WALLET_TYPE =", "ASSET =", "FOR UPDATE");
    assertThat(spotPositionLocks)
        .contains("ORDER BY WALLET_TYPE, ASSET, COST_ASSET, ID", "FOR UPDATE");
    assertThat(perpPositionLocks)
        .contains("ORDER BY SYMBOL, POSITION_SIDE, ID", "FOR UPDATE");
    assertThat(groupOrderLocks).contains("ORDER BY ID", "FOR UPDATE");
    assertThat(perpOrderLocks).contains("ORDER BY ID", "FOR UPDATE");
  }

  private boolean transferAttempt(Fixture fixture, RaceGate gate, UUID requestId) {
    gate.awaitStart();
    try {
      transferService.transfer(
          fixture.userId(), fixture.accountId(), Direction.SPOT_TO_PERP,
          new BigDecimal("40000.00000000"), requestId);
      return true;
    } catch (BusinessException exception) {
      assertThat(exception.getCode()).isEqualTo("TRANSFER_AMOUNT_UNAVAILABLE");
      return false;
    }
  }

  private Fixture createFixture(String prefix, boolean includeBtc) {
    UUID userId = UUID.randomUUID();
    String email = "task17-demo-it-" + prefix + "-" + userId + "@example.test";
    jdbcTemplate.update("""
        INSERT INTO auth.users (id, email, password_hash, status, role, kyc_status, risk_level)
        VALUES (?, ?, 'not-used', 'ACTIVE', 'USER', 'NOT_SUBMITTED', 'NORMAL')
        """, userId, email);
    TradingAccountEntity account = lifecycleService.getOrCreateDemoAccount(userId);
    if (includeBtc) {
      WalletBalanceEntity btc = new WalletBalanceEntity();
      btc.setId(UUID.randomUUID());
      btc.setAccountId(account.getId());
      btc.setWalletType("SPOT");
      btc.setAsset("BTC");
      btc.setTotal(new BigDecimal("0.20000000"));
      btc.setAvailable(new BigDecimal("0.20000000"));
      btc.setLocked(ZERO);
      walletBalanceRepository.save(btc);
    }
    Fixture fixture = new Fixture(
        userId, account.getId(), new UserPrincipal(userId, email, "USER"));
    fixtures.add(fixture);
    return fixture;
  }

  private OcoOrderGroupResponse createSellOco(Fixture fixture, String key) {
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(creationBundle());
    return ocoOrderService.create(
        fixture.principal(),
        new CreateOcoOrderRequest(
            fixture.accountId(), SYMBOL, OrderSide.SELL, new BigDecimal("0.1000"),
            QuantityUnit.BASE, new BigDecimal("55000"), new BigDecimal("49000"),
            TriggerPriceType.LAST_PRICE, key, key));
  }

  private SpotMarketBundle creationBundle() {
    Instant now = Instant.now();
    return new SpotMarketBundle(
        SYMBOL, SYMBOL, "binance", MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("49990"), new BigDecimal("50010"), new BigDecimal("50000"),
        null, List.of(), List.of(), now, now.plusSeconds(30));
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

  private OrderEntity leg(List<OrderEntity> legs, OrderType orderType) {
    return legs.stream()
        .filter(order -> order.getOrderType() == orderType)
        .findFirst()
        .orElseThrow();
  }

  private WalletBalanceEntity wallet(UUID accountId, String asset) {
    return walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(
        accountId, "SPOT", asset).orElseThrow();
  }

  private void assertOcoOutcome(UUID accountId, UUID groupId, boolean fillReturned) {
    List<OrderEntity> legs = group(groupId);
    long expectedFills = fillReturned ? 1L : 0L;
    long expectedCancellations = fillReturned ? 1L : 2L;
    long filledLegs = legs.stream()
        .filter(order -> order.getStatus() == OrderStatus.FILLED)
        .count();
    long canceledLegs = legs.stream()
        .filter(order -> order.getStatus() == OrderStatus.CANCELED)
        .count();

    assertThat(legs).hasSize(2);
    assertThat(filledLegs).isEqualTo(expectedFills);
    assertThat(canceledLegs).isEqualTo(expectedCancellations);
    assertThat(legs).allSatisfy(order -> assertThat(orZero(order.getHoldAmount())).isZero());
    assertThat(count("""
        SELECT count(*) FROM trading.trades
        WHERE order_id IN (
          SELECT id FROM trading.orders WHERE contingency_group_id = ?
        )
        """, groupId)).isEqualTo(expectedFills);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id IN (
          SELECT id FROM trading.orders WHERE contingency_group_id = ?
        ) AND event_type = 'ORDER_FILLED'
        """, groupId)).isEqualTo(expectedFills);
    assertThat(count("""
        SELECT count(*) FROM trading.order_events
        WHERE order_id IN (
          SELECT id FROM trading.orders WHERE contingency_group_id = ?
        ) AND event_type = 'ORDER_CANCELED'
        """, groupId)).isEqualTo(expectedCancellations);
    assertNoDuplicateTradeForEitherLeg(legs);
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND entry_type = 'SPOT_ORDER_LOCK'
        """, accountId)).isEqualTo(1L);
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND entry_type = 'SPOT_ORDER_RELEASE'
        """, accountId)).isEqualTo(fillReturned ? 0L : 1L);
    for (String entryType : List.of("SPOT_SELL_DEBIT", "SPOT_SELL_CREDIT", "TRADE_FEE")) {
      assertThat(count("""
          SELECT count(*) FROM ledger.asset_ledger_entries
          WHERE account_id = ? AND entry_type = ?
          """, accountId, entryType)).isEqualTo(expectedFills);
    }
  }

  private void assertNoDuplicateTradeForEitherLeg(List<OrderEntity> legs) {
    legs.forEach(order -> assertThat(count(
        "SELECT count(*) FROM trading.trades WHERE order_id = ?", order.getId()))
        .isLessThanOrEqualTo(1L));
  }

  private void assertTradeUniquenessGuardRejectsDuplicate(UUID accountId) {
    assertThatThrownBy(() -> jdbcTemplate.update("""
        INSERT INTO trading.trades (
          id, order_id, account_id, symbol, product_type, canonical_full_fill,
          position_side, margin_mode,
          side, lots, price, realized_pnl, fee, fee_asset, liquidity_role,
          system_reason, source_mode, provider_code, executed_at
        )
        SELECT ?, order_id, account_id, symbol, product_type, canonical_full_fill,
          position_side, margin_mode,
          side, lots, price, realized_pnl, fee, fee_asset, liquidity_role,
          system_reason, source_mode, provider_code, executed_at
        FROM trading.trades
        WHERE account_id = ?
        """, UUID.randomUUID(), accountId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ux_trades_p0_order_full_fill");
    assertThat(count(
        "SELECT count(*) FROM trading.trades WHERE account_id = ?", accountId))
        .isEqualTo(1L);

    UUID legacyTradeId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO trading.trades (
          id, order_id, account_id, symbol, product_type, canonical_full_fill,
          position_side, margin_mode, side, lots, price, realized_pnl, fee,
          fee_asset, liquidity_role, system_reason, source_mode, provider_code, executed_at
        )
        SELECT ?, order_id, account_id, symbol, product_type, FALSE,
          position_side, margin_mode, side, lots, price, realized_pnl, fee,
          fee_asset, liquidity_role, system_reason, source_mode, provider_code, executed_at
        FROM trading.trades
        WHERE account_id = ?
        """, legacyTradeId, accountId);
    assertThat(count(
        "SELECT count(*) FROM trading.trades WHERE account_id = ?", accountId))
        .as("legacy/LIVE multi-fill rows remain outside the canonical Demo guard")
        .isEqualTo(2L);
    jdbcTemplate.update("DELETE FROM trading.trades WHERE id = ?", legacyTradeId);
  }

  private void assertTransferLedger(
      UUID accountId,
      UUID transferId,
      BigDecimal transferAmount
  ) {
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND reference_type = 'TRANSFER' AND reference_id = ?
          AND entry_type = 'TRANSFER_OUT'
        """, accountId, transferId)).isEqualTo(1L);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'TRANSFER' AND reference_id = ?
          AND operation_type = 'TRANSFER_IN'
        """, accountId, transferId)).isEqualTo(1L);
    assertThat(decimal("""
        SELECT amount FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND reference_type = 'TRANSFER' AND reference_id = ?
        """, accountId, transferId)).isEqualByComparingTo(transferAmount.negate());
    assertThat(decimal("""
        SELECT amount FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'TRANSFER' AND reference_id = ?
        """, accountId, transferId)).isEqualByComparingTo(transferAmount);
  }

  private void assertSpotConservation(
      UUID accountId,
      BigDecimal btcBefore,
      BigDecimal usdtBefore
  ) {
    WalletBalanceEntity btcAfter = wallet(accountId, "BTC");
    WalletBalanceEntity usdtAfter = wallet(accountId, "USDT");
    long trades = count("SELECT count(*) FROM trading.trades WHERE account_id = ?", accountId);
    if (trades == 0) {
      assertThat(btcAfter.getTotal()).isEqualByComparingTo(btcBefore);
      assertThat(usdtAfter.getTotal()).isEqualByComparingTo(usdtBefore);
      return;
    }

    assertThat(trades).isEqualTo(1L);
    BigDecimal quantity = decimal(
        "SELECT lots FROM trading.trades WHERE account_id = ?", accountId);
    BigDecimal price = decimal(
        "SELECT price FROM trading.trades WHERE account_id = ?", accountId);
    BigDecimal fee = decimal(
        "SELECT fee FROM trading.trades WHERE account_id = ?", accountId);
    assertThat(string(
        "SELECT fee_asset FROM trading.trades WHERE account_id = ?", accountId))
        .isEqualTo("USDT");
    assertThat(btcAfter.getTotal()).isEqualByComparingTo(btcBefore.subtract(quantity));
    assertThat(usdtAfter.getTotal()).isEqualByComparingTo(
        usdtBefore.add(quantity.multiply(price)).subtract(fee));
  }

  private void assertCrossFlowConservation(
      UUID accountId,
      BigDecimal btcBefore,
      BigDecimal spotUsdtBefore,
      BigDecimal perpUsdtBefore,
      BigDecimal transferAmount
  ) {
    WalletBalanceEntity btcAfter = wallet(accountId, "BTC");
    WalletBalanceEntity spotUsdtAfter = wallet(accountId, "USDT");
    BigDecimal perpUsdtAfter = decimal(
        "SELECT balance FROM core.trading_accounts WHERE id = ?", accountId);
    long trades = count("SELECT count(*) FROM trading.trades WHERE account_id = ?", accountId);

    assertThat(perpUsdtAfter).isEqualByComparingTo(perpUsdtBefore.add(transferAmount));
    if (trades == 0) {
      assertThat(btcAfter.getTotal()).isEqualByComparingTo(btcBefore);
      assertThat(spotUsdtAfter.getTotal())
          .isEqualByComparingTo(spotUsdtBefore.subtract(transferAmount));
      assertThat(spotUsdtAfter.getTotal().add(perpUsdtAfter))
          .isEqualByComparingTo(spotUsdtBefore.add(perpUsdtBefore));
      return;
    }

    assertThat(trades).isEqualTo(1L);
    BigDecimal quantity = decimal(
        "SELECT lots FROM trading.trades WHERE account_id = ?", accountId);
    BigDecimal price = decimal(
        "SELECT price FROM trading.trades WHERE account_id = ?", accountId);
    BigDecimal fee = decimal(
        "SELECT fee FROM trading.trades WHERE account_id = ?", accountId);
    assertThat(string(
        "SELECT fee_asset FROM trading.trades WHERE account_id = ?", accountId))
        .isEqualTo("USDT");
    BigDecimal netQuote = quantity.multiply(price).subtract(fee);
    assertThat(btcAfter.getTotal()).isEqualByComparingTo(btcBefore.subtract(quantity));
    assertThat(spotUsdtAfter.getTotal()).isEqualByComparingTo(
        spotUsdtBefore.subtract(transferAmount).add(netQuote));
    assertThat(spotUsdtAfter.getTotal().add(perpUsdtAfter)).isEqualByComparingTo(
        spotUsdtBefore.add(perpUsdtBefore).add(netQuote));
  }

  private void assertWalletAndAccountInvariants(UUID accountId) {
    assertThat(count("""
        SELECT count(*) FROM core.wallet_balances
        WHERE account_id = ?
          AND (total < 0 OR available < 0 OR locked < 0 OR total <> available + locked)
        """, accountId)).isZero();
    assertThat(count("""
        SELECT count(*) FROM core.trading_accounts
        WHERE id = ?
          AND (balance < 0 OR equity < 0 OR used_margin < 0 OR free_margin < 0
            OR free_margin <> equity - used_margin)
        """, accountId)).isZero();
  }

  private String selectSql(
      Class<?> repository,
      String methodName,
      Class<?>... parameterTypes
  ) throws NoSuchMethodException {
    Select select = repository.getMethod(methodName, parameterTypes).getAnnotation(Select.class);
    assertThat(select).isNotNull();
    return String.join(" ", select.value())
        .replaceAll("\\s+", " ")
        .trim()
        .toUpperCase(Locale.ROOT);
  }

  private long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private BigDecimal decimal(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
  }

  private String string(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, String.class, args);
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
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

  private static String uniqueKey(String prefix) {
    return "task17-demo-it-" + prefix + "-" + UUID.randomUUID();
  }

  private static final class RaceGate {

    private final CountDownLatch ready;
    private final CountDownLatch start = new CountDownLatch(1);

    private RaceGate(int workers) {
      this.ready = new CountDownLatch(workers);
    }

    private void awaitStart() {
      ready.countDown();
      await(start, "race start");
    }

    private void release() {
      await(ready, "all race workers to become ready");
      start.countDown();
    }
  }

  private record Fixture(UUID userId, UUID accountId, UserPrincipal principal) {
  }
}
