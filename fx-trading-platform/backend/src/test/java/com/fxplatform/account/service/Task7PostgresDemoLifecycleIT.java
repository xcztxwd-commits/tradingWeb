package com.fxplatform.account.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.account.dto.AccountTransferRequest.Direction;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.service.AssetConversionService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class Task7PostgresDemoLifecycleIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired DemoAccountLifecycleService lifecycleService;
  @Autowired AccountTransferService transferService;
  @Autowired AssetConversionService assetConversionService;

  private final List<Fixture> fixtures = new ArrayList<>();

  @AfterEach
  void cleanFixtures() {
    for (Fixture fixture : fixtures) {
      UUID accountId = fixture.accountId();
      if (accountId != null) {
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
      }
      jdbcTemplate.update("DELETE FROM auth.users WHERE id = ?", fixture.userId());
    }
    fixtures.clear();
  }

  @Test
  void concurrentGetOrCreateProducesOneActiveDemoAndOneInitializationPair() throws Exception {
    UUID userId = insertUser("create-race");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<TradingAccountEntity> first = workers.submit(() -> {
      start.await(5, SECONDS);
      return lifecycleService.getOrCreateDemoAccount(userId);
    });
    Future<TradingAccountEntity> second = workers.submit(() -> {
      start.await(5, SECONDS);
      return lifecycleService.getOrCreateDemoAccount(userId);
    });
    start.countDown();
    TradingAccountEntity firstAccount;
    TradingAccountEntity secondAccount;
    try {
      firstAccount = first.get(15, SECONDS);
      secondAccount = second.get(15, SECONDS);
    } finally {
      workers.shutdownNow();
    }
    fixtures.add(new Fixture(userId, firstAccount.getId()));

    assertThat(secondAccount.getId()).isEqualTo(firstAccount.getId());
    assertThat(count("""
        SELECT count(*) FROM core.trading_accounts
        WHERE user_id = ? AND account_type = 'DEMO' AND status = 'ACTIVE'
        """, userId)).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM core.wallet_balances
        WHERE account_id = ? AND wallet_type = 'SPOT' AND asset = 'USDT'
          AND total = 50000 AND available = 50000 AND locked = 0
        """, firstAccount.getId())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND operation_type = 'DEMO_INIT'
        """, firstAccount.getId())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND operation_type = 'DEMO_INIT'
        """, firstAccount.getId())).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trading.account_symbol_settings WHERE account_id = ?",
        firstAccount.getId())).isEqualTo(10);
  }

  @Test
  void concurrentTransfersAboveSpotAvailableAllowOneWinnerAndPreserveConservation() throws Exception {
    Fixture fixture = createFixture("transfer-race");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Boolean> first = workers.submit(() -> transferAttempt(fixture, start, UUID.randomUUID()));
    Future<Boolean> second = workers.submit(() -> transferAttempt(fixture, start, UUID.randomUUID()));
    start.countDown();
    List<Boolean> results;
    try {
      results = List.of(first.get(15, SECONDS), second.get(15, SECONDS));
    } finally {
      workers.shutdownNow();
    }

    assertThat(results).containsExactlyInAnyOrder(true, false);
    BigDecimal spot = decimal("""
        SELECT available FROM core.wallet_balances
        WHERE account_id = ? AND wallet_type = 'SPOT' AND asset = 'USDT'
        """, fixture.accountId());
    BigDecimal perp = decimal("SELECT balance FROM core.trading_accounts WHERE id = ?", fixture.accountId());
    assertThat(spot).isEqualByComparingTo("10000.00000000");
    assertThat(perp).isEqualByComparingTo("90000.00000000");
    assertThat(spot.add(perp)).isEqualByComparingTo("100000.00000000");
    assertThat(count("""
        SELECT count(*) FROM ledger.asset_ledger_entries
        WHERE account_id = ? AND reference_type = 'TRANSFER'
        """, fixture.accountId())).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'TRANSFER'
        """, fixture.accountId())).isEqualTo(1);
  }

  @Test
  void resetBlocksActiveOrderThenPreservesHistoryAndRepairsSettingsWithCompositeUpdates() {
    Fixture fixture = createFixture("reset");
    UUID activeOrderId = insertOrder(fixture, "PENDING", "active");
    long resetLedgerBefore = count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND operation_type = 'DEMO_RESET'
        """, fixture.accountId());

    assertThatThrownBy(() -> lifecycleService.reset(
        fixture.userId(), fixture.accountId(), UUID.randomUUID()))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("DEMO_RESET_BLOCKED"));
    assertThat(count("SELECT count(*) FROM trading.orders WHERE id = ?", activeOrderId)).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND operation_type = 'DEMO_RESET'
        """, fixture.accountId())).isEqualTo(resetLedgerBefore);

    jdbcTemplate.update("UPDATE trading.orders SET status = 'FILLED' WHERE id = ?", activeOrderId);
    transferService.transfer(
        fixture.userId(), fixture.accountId(), Direction.SPOT_TO_PERP,
        new BigDecimal("1000.00000000"), UUID.randomUUID());
    jdbcTemplate.update("DELETE FROM trading.account_symbol_settings WHERE account_id = ? AND symbol = 'XRPUSDT-PERP'",
        fixture.accountId());
    jdbcTemplate.update("""
        UPDATE trading.account_symbol_settings
        SET leverage = 50, margin_mode = 'ISOLATED', quantity_unit = 'CONTRACTS'
        WHERE account_id = ? AND symbol = 'BTCUSDT-PERP'
        """, fixture.accountId());
    jdbcTemplate.update("""
        INSERT INTO core.wallet_balances (account_id, wallet_type, asset, total, available, locked)
        VALUES (?, 'SPOT', 'BTC', 0.25, 0.25, 0)
        """, fixture.accountId());
    jdbcTemplate.update("""
        INSERT INTO trading.spot_positions (
          account_id, wallet_type, asset, quantity, average_cost, cost_asset,
          realized_pnl, unrealized_pnl, fee_cost
        ) VALUES (?, 'SPOT', 'BTC', 0.25, 40000, 'USDT', 123, 5, 2)
        """, fixture.accountId());
    long transferCashBefore = count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'TRANSFER'
        """, fixture.accountId());
    UUID resetId = UUID.randomUUID();

    var reset = lifecycleService.reset(fixture.userId(), fixture.accountId(), resetId);
    var replay = lifecycleService.reset(fixture.userId(), fixture.accountId(), resetId);

    assertThat(reset.demoGeneration()).isEqualTo(2L);
    assertThat(replay.replayed()).isTrue();
    assertThat(decimal("SELECT balance FROM core.trading_accounts WHERE id = ?", fixture.accountId()))
        .isEqualByComparingTo("50000.00000000");
    assertThat(decimal("""
        SELECT available FROM core.wallet_balances
        WHERE account_id = ? AND wallet_type = 'SPOT' AND asset = 'USDT'
        """, fixture.accountId())).isEqualByComparingTo("50000.00000000");
    assertThat(decimal("""
        SELECT total FROM core.wallet_balances
        WHERE account_id = ? AND wallet_type = 'SPOT' AND asset = 'BTC'
        """, fixture.accountId())).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(decimal("SELECT quantity FROM trading.spot_positions WHERE account_id = ? AND asset = 'BTC'",
        fixture.accountId())).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(decimal("SELECT realized_pnl FROM trading.spot_positions WHERE account_id = ? AND asset = 'BTC'",
        fixture.accountId())).isEqualByComparingTo("123.00000000");
    assertThat(count("""
        SELECT count(*) FROM trading.account_symbol_settings
        WHERE account_id = ? AND leverage = 10 AND margin_mode = 'CROSS' AND quantity_unit = 'BASE'
        """, fixture.accountId())).isEqualTo(10);
    assertThat(count("SELECT count(*) FROM trading.orders WHERE id = ? AND status = 'FILLED'", activeOrderId))
        .isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'TRANSFER'
        """, fixture.accountId())).isEqualTo(transferCashBefore);
    assertThat(count("""
        SELECT count(*) FROM ledger.ledger_entries
        WHERE account_id = ? AND reference_type = 'DEMO_RESET' AND reference_id = ?
        """, fixture.accountId(), resetId)).isEqualTo(1);
  }

  @Test
  void conversionAndResetSerializeWithoutWalletOrderDeadlockAndPreserveCurrentFunds() throws Exception {
    Fixture fixture = createFixture("conversion-reset-race");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<?> conversion = workers.submit(() -> {
      await(start);
      assetConversionService.convert(
          fixture.userId(),
          fixture.accountId(),
          WalletType.SPOT,
          "USDT",
          WalletType.FX_MARGIN,
          "USD",
          new BigDecimal("1000.00000000"),
          UUID.randomUUID());
    });
    Future<?> reset = workers.submit(() -> {
      await(start);
      lifecycleService.reset(fixture.userId(), fixture.accountId(), UUID.randomUUID());
    });
    start.countDown();
    try {
      conversion.get(15, SECONDS);
      reset.get(15, SECONDS);
    } finally {
      workers.shutdownNow();
    }

    BigDecimal spot = decimal("""
        SELECT available FROM core.wallet_balances
        WHERE account_id = ? AND wallet_type = 'SPOT' AND asset = 'USDT'
        """, fixture.accountId());
    BigDecimal fx = decimal("""
        SELECT available FROM core.wallet_balances
        WHERE account_id = ? AND wallet_type = 'FX_MARGIN' AND asset = 'USD'
        """, fixture.accountId());
    assertThat(spot.add(fx)).isEqualByComparingTo("50000.00000000");
    assertThat(List.of(spot, fx)).satisfiesAnyOf(
        balances -> {
          assertThat(balances.get(0)).isEqualByComparingTo("50000.00000000");
          assertThat(balances.get(1)).isEqualByComparingTo("0.00000000");
        },
        balances -> {
          assertThat(balances.get(0)).isEqualByComparingTo("49000.00000000");
          assertThat(balances.get(1)).isEqualByComparingTo("1000.00000000");
        });
    assertThat(count("""
        SELECT count(*) FROM core.wallet_balances
        WHERE account_id = ? AND locked <> 0
        """, fixture.accountId())).isZero();
  }

  private boolean transferAttempt(Fixture fixture, CountDownLatch start, UUID requestId) throws Exception {
    start.await(5, SECONDS);
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

  private static void await(CountDownLatch start) {
    try {
      if (!start.await(5, SECONDS)) {
        throw new IllegalStateException("Timed out waiting for race start");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for race start", exception);
    }
  }

  private Fixture createFixture(String prefix) {
    UUID userId = insertUser(prefix);
    TradingAccountEntity account = lifecycleService.getOrCreateDemoAccount(userId);
    Fixture fixture = new Fixture(userId, account.getId());
    fixtures.add(fixture);
    return fixture;
  }

  private UUID insertUser(String prefix) {
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO auth.users (id, email, password_hash, status, role, kyc_status, risk_level)
        VALUES (?, ?, 'not-used', 'ACTIVE', 'USER', 'NOT_SUBMITTED', 'NORMAL')
        """, userId, "task7-it-" + prefix + "-" + userId + "@example.test");
    return userId;
  }

  private UUID insertOrder(Fixture fixture, String status, String suffix) {
    UUID orderId = UUID.randomUUID();
    String key = "task7-" + suffix + "-" + orderId;
    jdbcTemplate.update("""
        INSERT INTO trading.orders (
          id, user_id, account_id, symbol, side, order_type, status, lots,
          requested_price, idempotency_key, client_order_id, quantity, price,
          remaining_quantity, hold_amount, hold_currency, leverage,
          product_type, position_mode, position_side, margin_mode, quantity_unit,
          original_quantity, base_quantity, time_in_force, reduce_only, order_origin
        ) VALUES (
          ?, ?, ?, 'BTCUSDT', 'BUY', 'LIMIT', ?, 0.01,
          40000, ?, ?, 0.01, 40000,
          0.01, 0, 'USDT', 10,
          'CRYPTO_SPOT', 'ONE_WAY', 'BOTH', 'CASH', 'BASE',
          0.01, 0.01, 'GTC', false, 'USER'
        )
        """, orderId, fixture.userId(), fixture.accountId(), status, key, key);
    return orderId;
  }

  private long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private BigDecimal decimal(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
  }

  private record Fixture(UUID userId, UUID accountId) {
  }
}
