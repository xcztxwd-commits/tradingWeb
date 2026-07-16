package com.fxplatform.trading.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.account.dto.UpdatePositionModeRequest;
import com.fxplatform.account.dto.UpdateSymbolSettingsRequest;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.service.DemoAccountLifecycleService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.QuantityUnit;
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
import org.springframework.dao.DataIntegrityViolationException;
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
class Task8PostgresTradingSettingsIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired DemoAccountLifecycleService lifecycleService;
  @Autowired TradingSettingsService settingsService;

  private final List<Fixture> fixtures = new ArrayList<>();

  @AfterEach
  void cleanFixtures() {
    for (Fixture fixture : fixtures) {
      UUID accountId = fixture.accountId();
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
  void defaultsCompositeVersionNoOpAndStaleWriteAreAtomic() {
    Fixture fixture = createFixture("version");

    var defaults = settingsService.get(fixture.userId(), fixture.accountId());
    assertThat(defaults.symbols()).hasSize(5);
    assertThat(defaults.symbols()).extracting(item -> item.symbol())
        .containsExactly(
            "BNBUSDT-PERP", "BTCUSDT-PERP", "ETHUSDT-PERP", "SOLUSDT-PERP", "XRPUSDT-PERP");
    assertThat(defaults.symbols()).allSatisfy(item -> {
      assertThat(item.leverage()).isEqualTo(10);
      assertThat(item.marginMode()).isEqualTo(MarginMode.CROSS);
      assertThat(item.quantityUnit()).isEqualTo(QuantityUnit.BASE);
      assertThat(item.version()).isZero();
    });

    jdbcTemplate.update("""
        DELETE FROM trading.account_symbol_settings
        WHERE account_id = ? AND symbol = 'BTCUSDT-PERP'
        """, fixture.accountId());

    var updated = settingsService.updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "btc-usdt-perp",
        new UpdateSymbolSettingsRequest(null, null, QuantityUnit.QUOTE, 0L));
    var noOp = settingsService.updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(null, null, QuantityUnit.QUOTE, 1L));

    assertThat(symbol(updated, "BTCUSDT-PERP").version()).isEqualTo(1L);
    assertThat(symbol(noOp, "BTCUSDT-PERP").version()).isEqualTo(1L);
    assertThatThrownBy(() -> settingsService.updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(20, null, null, 0L)))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("SETTINGS_VERSION_CONFLICT"));
    assertThat(longValue("""
        SELECT version FROM trading.account_symbol_settings
        WHERE account_id = ? AND symbol = 'BTCUSDT-PERP'
        """, fixture.accountId())).isEqualTo(1L);
    assertThat(jdbcTemplate.queryForObject("""
        SELECT margin_mode || ':' || quantity_unit
        FROM trading.account_symbol_settings
        WHERE account_id = ? AND symbol = 'BTCUSDT-PERP'
        """, String.class, fixture.accountId())).isEqualTo("CROSS:QUOTE");
  }

  @Test
  void onlyLinearPerpOpenPositionsAndActiveOrdersBlockRelevantSwitches() {
    Fixture fixture = createFixture("scope");
    insertOrder(fixture, "BTCUSDT", "CRYPTO_SPOT", "PENDING");
    insertPosition(fixture, "EURUSD", "FX_MARGIN", "ONE_WAY", "BOTH");

    var hedge = settingsService.updatePositionMode(
        fixture.userId(), fixture.accountId(), new UpdatePositionModeRequest(PositionMode.HEDGE));
    assertThat(hedge.positionMode()).isEqualTo(PositionMode.HEDGE);

    UUID terminalPerp = insertOrder(fixture, "BTCUSDT-PERP", "LINEAR_PERP", "FILLED");
    settingsService.updatePositionMode(
        fixture.userId(), fixture.accountId(), new UpdatePositionModeRequest(PositionMode.ONE_WAY));
    settingsService.updatePositionMode(
        fixture.userId(), fixture.accountId(), new UpdatePositionModeRequest(PositionMode.HEDGE));

    UUID activePerp = insertOrder(fixture, "BTCUSDT-PERP", "LINEAR_PERP", "PENDING");
    assertThatThrownBy(() -> settingsService.updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(null, MarginMode.ISOLATED, null, 0L)))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MARGIN_MODE_SWITCH_BLOCKED"));
    jdbcTemplate.update("DELETE FROM trading.orders WHERE id IN (?, ?)", activePerp, terminalPerp);

    // Task 9 intentionally permits leverage changes with open Perpetual positions and
    // atomically recalculates both slots. Its PostgreSQL behavior belongs to the Task 9 gate;
    // Task 8 continues to own only mode/margin-mode blocking semantics.
  }

  @Test
  void concurrentSameVersionUpdatesHaveOneWinnerAndCanonicalSlotIndexesRejectDuplicates()
      throws Exception {
    Fixture fixture = createFixture("concurrency");
    jdbcTemplate.update("""
        DELETE FROM trading.account_symbol_settings
        WHERE account_id = ? AND symbol = 'BTCUSDT-PERP'
        """, fixture.accountId());
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<String> quote = workers.submit(() -> updateAttempt(
        fixture, start, QuantityUnit.QUOTE));
    Future<String> contracts = workers.submit(() -> updateAttempt(
        fixture, start, QuantityUnit.CONTRACTS));
    start.countDown();
    List<String> results;
    try {
      results = List.of(quote.get(15, SECONDS), contracts.get(15, SECONDS));
    } finally {
      workers.shutdownNow();
    }

    assertThat(results).containsExactlyInAnyOrder("UPDATED", "SETTINGS_VERSION_CONFLICT");
    assertThat(longValue("""
        SELECT version FROM trading.account_symbol_settings
        WHERE account_id = ? AND symbol = 'BTCUSDT-PERP'
        """, fixture.accountId())).isEqualTo(1L);

    insertPosition(fixture, "ETHUSDT-PERP", "LINEAR_PERP", "HEDGE", "LONG");
    insertPosition(fixture, "ETHUSDT-PERP", "LINEAR_PERP", "HEDGE", "SHORT");
    assertThatThrownBy(() -> insertPosition(
        fixture, "ETHUSDT-PERP", "LINEAR_PERP", "HEDGE", "LONG"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(count("""
        SELECT count(*) FROM trading.positions
        WHERE account_id = ? AND symbol = 'ETHUSDT-PERP' AND status = 'OPEN'
        """, fixture.accountId())).isEqualTo(2L);
  }

  private String updateAttempt(Fixture fixture, CountDownLatch start, QuantityUnit unit)
      throws Exception {
    start.await(5, SECONDS);
    try {
      settingsService.updateSymbolSettings(
          fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
          new UpdateSymbolSettingsRequest(null, null, unit, 0L));
      return "UPDATED";
    } catch (BusinessException exception) {
      return exception.getCode();
    }
  }

  private Fixture createFixture(String suffix) {
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO auth.users (id, email, password_hash, status, role, kyc_status, risk_level)
        VALUES (?, ?, 'not-used', 'ACTIVE', 'USER', 'NOT_SUBMITTED', 'NORMAL')
        """, userId, "task8-it-" + suffix + "-" + userId + "@example.test");
    TradingAccountEntity account = lifecycleService.getOrCreateDemoAccount(userId);
    Fixture fixture = new Fixture(userId, account.getId());
    fixtures.add(fixture);
    return fixture;
  }

  private UUID insertOrder(Fixture fixture, String symbol, String productType, String status) {
    UUID id = UUID.randomUUID();
    String key = "task8-" + id;
    jdbcTemplate.update("""
        INSERT INTO trading.orders (
          id, user_id, account_id, symbol, side, order_type, status, lots,
          idempotency_key, client_order_id, quantity, remaining_quantity,
          product_type, position_mode, position_side, margin_mode, quantity_unit,
          original_quantity, base_quantity, time_in_force, reduce_only, order_origin
        ) VALUES (
          ?, ?, ?, ?, 'BUY', 'LIMIT', ?, 0.01,
          ?, ?, 0.01, 0.01,
          ?, 'ONE_WAY', 'BOTH', 'CROSS', 'BASE',
          0.01, 0.01, 'GTC', false, 'USER'
        )
        """, id, fixture.userId(), fixture.accountId(), symbol, status, key, key, productType);
    return id;
  }

  private UUID insertPosition(
      Fixture fixture,
      String symbol,
      String productType,
      String positionMode,
      String positionSide
  ) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO trading.positions (
          id, account_id, symbol, product_type, position_mode, position_side,
          margin_mode, side, lots, open_price, current_price, status, leverage
        ) VALUES (
          ?, ?, ?, ?, ?, ?, 'CROSS',
          CASE WHEN ? = 'SHORT' THEN 'SELL' ELSE 'BUY' END,
          0.01, 50000, 50000, 'OPEN', 10
        )
        """, id, fixture.accountId(), symbol, productType, positionMode, positionSide, positionSide);
    return id;
  }

  private com.fxplatform.account.dto.TradingSettingsResponse.SymbolSettings symbol(
      com.fxplatform.account.dto.TradingSettingsResponse response,
      String code
  ) {
    return response.symbols().stream()
        .filter(item -> item.symbol().equals(code))
        .findFirst()
        .orElseThrow();
  }

  private long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private long longValue(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private record Fixture(UUID userId, UUID accountId) {
  }
}
