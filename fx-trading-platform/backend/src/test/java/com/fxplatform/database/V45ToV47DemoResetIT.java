package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.enums.ProviderHealthStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class V45ToV47DemoResetIT {

  @Test
  void v45UpgradeRebuildsOnlyDemoAccountsAndPreservesLiveAndConfiguration() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, "45");
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      UpgradeFixture fixture = createV45Fixture(jdbc);

      PostgresMigrationTestSupport.migrate(postgres, "47");

      assertDemoWasRebuilt(jdbc, fixture);
      assertOldDemoDataWasRemoved(jdbc, fixture.firstDemo());
      assertOldDemoDataWasRemoved(jdbc, fixture.secondDemo());
      assertLiveDataWasPreserved(jdbc, fixture);
      assertUsersProvidersConfigAndContentWerePreserved(jdbc, fixture);
    }
  }

  private UpgradeFixture createV45Fixture(JdbcTemplate jdbc) {
    UUID userId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    UUID firstDemoAccountId = UUID.randomUUID();
    UUID secondDemoAccountId = UUID.randomUUID();
    UUID liveAccountId = UUID.randomUUID();

    jdbc.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values
          (?, ?, 'hash', 'ACTIVE', 'USER'),
          (?, ?, 'hash', 'ACTIVE', 'ADMIN')
        """,
        userId, userId + "@migration.test",
        adminId, adminId + "@migration.test");

    insertAccount(jdbc, firstDemoAccountId, userId, "DEMO", new BigDecimal("111.00000000"));
    insertAccount(jdbc, secondDemoAccountId, userId, "DEMO", new BigDecimal("222.00000000"));
    insertAccount(jdbc, liveAccountId, userId, "LIVE", new BigDecimal("777.00000000"));

    AccountArtifacts firstDemo = insertAccountArtifacts(
        jdbc, firstDemoAccountId, userId, adminId, "demo-one", new BigDecimal("111.00000000"));
    AccountArtifacts secondDemo = insertAccountArtifacts(
        jdbc, secondDemoAccountId, userId, adminId, "demo-two", new BigDecimal("222.00000000"));
    AccountArtifacts live = insertAccountArtifacts(
        jdbc, liveAccountId, userId, adminId, "live", new BigDecimal("777.00000000"));
    UUID conflictingLivePositionId = insertConflictingLivePerpPosition(
        jdbc, liveAccountId, live.positionId());

    UUID providerId = UUID.randomUUID();
    UUID settingId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    UUID adminNoteId = UUID.randomUUID();
    UUID fundingRateId = UUID.randomUUID();
    UUID duplicateFundingRateId = UUID.randomUUID();
    jdbc.update("""
        insert into market.data_providers (
          id, code, name, provider_type, asset_classes, enabled, priority, health_status, config_json
        ) values (?, 'fixture-provider', 'Fixture Provider', 'LOCAL', ARRAY['CRYPTO'], false, 777,
          'DOWN', '{"fixture":true}'::jsonb)
        """, providerId);
    jdbc.update("""
        insert into config.system_settings (
          id, setting_key, setting_value, value_type, description, editable
        ) values (?, 'fixture.migration.key', 'keep-me', 'STRING', 'migration fixture', false)
        """, settingId);
    jdbc.update("""
        insert into content.articles (
          id, article_type, title, body, status, language, sort_order
        ) values (?, 'NOTICE', 'Migration fixture', 'keep-me', 'PUBLISHED', 'en', 77)
        """, articleId);
    jdbc.update("""
        insert into admin.user_notes (id, user_id, admin_user_id, note)
        values (?, ?, ?, 'keep-me')
        """, adminNoteId, userId, adminId);
    OffsetDateTime fundingTime = OffsetDateTime.of(2026, 7, 11, 0, 0, 0, 0, ZoneOffset.UTC);
    jdbc.update("""
        insert into trading.funding_rates (
          id, symbol, funding_rate, funding_time, next_funding_time, mark_price, created_at
        ) values
          (?, 'BTCUSDT-PERP', 0.0001, ?, ?, 60000, ?),
          (?, 'BTCUSDT-PERP', 0.0002, ?, ?, 60001, ?)
        """,
        duplicateFundingRateId, fundingTime, fundingTime.plusHours(8), fundingTime.minusMinutes(1),
        fundingRateId, fundingTime, fundingTime.plusHours(8), fundingTime);

    return new UpgradeFixture(
        userId,
        adminId,
        firstDemo,
        secondDemo,
        live,
        conflictingLivePositionId,
        providerId,
        settingId,
        articleId,
        adminNoteId,
        fundingRateId,
        duplicateFundingRateId);
  }

  private UUID insertConflictingLivePerpPosition(
      JdbcTemplate jdbc,
      UUID accountId,
      UUID existingPositionId) {
    String symbol = "LEGACYUSDT-PERP";
    jdbc.update("""
        insert into market.symbols (
          symbol, display_name, provider, provider_symbol, asset_class, product_type,
          base_currency, quote_currency, pip_size, tick_size, lot_size, min_lot, max_lot,
          leverage, contract_size, settlement_asset, margin_asset, tradable
        ) values (?, 'Legacy Live Perpetual', 'demo', ?, 'LINEAR_PERP', 'LINEAR_PERP',
          'LEGACY', 'USDT', 0.01, 0.01, 1, 0.01, 100, 20, 1, 'USDT', 'USDT', true)
        """, symbol, symbol);
    jdbc.update("update trading.positions set symbol = ? where id = ?", symbol, existingPositionId);

    UUID conflictingPositionId = UUID.randomUUID();
    jdbc.update("""
        insert into trading.positions (
          id, account_id, symbol, side, lots, open_price, current_price,
          floating_pnl, realized_pnl, status, leverage, margin_held
        ) values (?, ?, ?, 'LONG', 2, 59000, 61000, 4000, 0, 'OPEN', 20, 5900)
        """, conflictingPositionId, accountId, symbol);
    return conflictingPositionId;
  }

  private void insertAccount(
      JdbcTemplate jdbc,
      UUID accountId,
      UUID userId,
      String accountType,
      BigDecimal balance) {
    jdbc.update("""
        insert into core.trading_accounts (
          id, user_id, account_type, base_currency, balance, equity, used_margin,
          free_margin, margin_level, leverage, status
        ) values (?, ?, ?, 'USD', ?, ?, 0, ?, null, 20, 'ACTIVE')
        """, accountId, userId, accountType, balance, balance, balance);
  }

  private AccountArtifacts insertAccountArtifacts(
      JdbcTemplate jdbc,
      UUID accountId,
      UUID userId,
      UUID adminId,
      String key,
      BigDecimal balance) {
    UUID orderId = UUID.randomUUID();
    UUID tradeId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    UUID orderEventId = UUID.randomUUID();
    UUID walletId = UUID.randomUUID();
    UUID assetLedgerId = UUID.randomUUID();
    UUID cashLedgerId = UUID.randomUUID();
    UUID walletSnapshotId = UUID.randomUUID();
    UUID accountSnapshotId = UUID.randomUUID();
    UUID spotPositionId = UUID.randomUUID();
    UUID fundingSettlementId = UUID.randomUUID();
    UUID financingSettlementId = UUID.randomUUID();
    UUID fundOperationId = UUID.randomUUID();
    UUID fundOrderId = UUID.randomUUID();
    OffsetDateTime eventTime = OffsetDateTime.of(2026, 7, 10, 0, 0, 0, 0, ZoneOffset.UTC);

    jdbc.update("""
        insert into trading.orders (
          id, user_id, account_id, symbol, side, order_type, status, lots,
          requested_price, execution_price, idempotency_key, leverage
        ) values (?, ?, ?, 'BTCUSDT', 'BUY', 'MARKET', 'FILLED', 1,
          60000, 60000, ?, 20)
        """, orderId, userId, accountId, key + "-order");
    jdbc.update("""
        insert into trading.trades (
          id, order_id, account_id, symbol, side, lots, price, realized_pnl, executed_at
        ) values (?, ?, ?, 'BTCUSDT', 'BUY', 1, 60000, 5, ?)
        """, tradeId, orderId, accountId, eventTime);
    jdbc.update("""
        insert into trading.positions (
          id, account_id, symbol, side, lots, open_price, current_price,
          floating_pnl, realized_pnl, status, leverage, margin_held
        ) values (?, ?, 'BTCUSDT', 'LONG', 1, 60000, 61000, 1000, 0, 'OPEN', 20, 3000)
        """, positionId, accountId);
    jdbc.update("""
        insert into trading.order_events (
          id, order_id, event_type, from_status, to_status, reason_code, message, created_at
        ) values (?, ?, 'FILLED', 'WORKING', 'FILLED', 'FIXTURE', 'fixture', ?)
        """, orderEventId, orderId, eventTime);
    jdbc.update("""
        insert into core.wallet_balances (
          id, account_id, wallet_type, asset, total, available, locked
        ) values (?, ?, 'SPOT', 'USDT', ?, ?, 0)
        """, walletId, accountId, balance, balance);
    jdbc.update("""
        insert into ledger.asset_ledger_entries (
          id, account_id, wallet_type, asset, amount, balance_after, entry_type,
          operation_type, reference_type, reference_id, description
        ) values (?, ?, 'SPOT', 'USDT', ?, ?, 'FIXTURE', 'FIXTURE', 'TRADE', ?, 'fixture')
        """, assetLedgerId, accountId, balance, balance, tradeId);
    jdbc.update("""
        insert into ledger.ledger_entries (
          id, account_id, entry_type, operation_type, amount, balance_after,
          currency, reference_type, reference_id, description
        ) values (?, ?, 'FIXTURE', 'FIXTURE', ?, ?, 'USD', 'TRADE', ?, 'fixture')
        """, cashLedgerId, accountId, balance, balance, tradeId);
    jdbc.update("""
        insert into core.wallet_daily_snapshots (
          id, account_id, wallet_type, asset, total, available, locked, snapshot_date
        ) values (?, ?, 'SPOT', 'USDT', ?, ?, 0, ?)
        """, walletSnapshotId, accountId, balance, balance, LocalDate.of(2026, 7, 10));
    jdbc.update("""
        insert into core.account_daily_snapshots (
          id, account_id, wallet_type, asset, balance, equity, used_margin,
          free_margin, open_pnl, realized_pnl, snapshot_date
        ) values (?, ?, 'MARGIN', 'USD', ?, ?, 0, ?, 0, 0, ?)
        """, accountSnapshotId, accountId, balance, balance, balance, LocalDate.of(2026, 7, 10));
    jdbc.update("""
        insert into trading.spot_positions (
          id, account_id, wallet_type, asset, quantity, average_cost, cost_asset,
          realized_pnl, unrealized_pnl, fee_cost
        ) values (?, ?, 'SPOT', 'BTC', 1, 60000, 'USDT', 0, 1000, 30)
        """, spotPositionId, accountId);
    jdbc.update("""
        insert into trading.funding_settlements (
          id, position_id, account_id, symbol, funding_time, funding_rate,
          amount, asset, ledger_entry_id
        ) values (?, ?, ?, 'BTCUSDT-PERP', ?, 0.0001, 6, 'USDT', ?)
        """, fundingSettlementId, positionId, accountId, eventTime, cashLedgerId);
    jdbc.update("""
        insert into trading.fx_financing_settlements (
          id, position_id, account_id, symbol, settlement_date, days_charged,
          rate, amount, asset, ledger_entry_id
        ) values (?, ?, ?, 'EURUSD', ?, 1, 0.01, 2, 'USD', ?)
        """, financingSettlementId, positionId, accountId, LocalDate.of(2026, 7, 10), cashLedgerId);
    jdbc.update("""
        insert into finance.admin_fund_operations (
          id, account_id, user_id, operation_type, amount, currency, before_balance,
          after_balance, status, admin_user_id, reason, idempotency_key
        ) values (?, ?, ?, 'DEPOSIT', 1, 'USD', ?, ?, 'COMPLETED', ?, 'fixture', ?)
        """, fundOperationId, accountId, userId, balance, balance.add(BigDecimal.ONE), adminId,
        key + "-fund-operation");
    jdbc.update("""
        insert into finance.fund_orders (
          id, user_id, account_id, order_type, amount, currency, status,
          applicant_note, fund_operation_id, created_by
        ) values (?, ?, ?, 'DEPOSIT', 1, 'USD', 'COMPLETED', 'fixture', ?, ?)
        """, fundOrderId, userId, accountId, fundOperationId, userId);

    return new AccountArtifacts(
        accountId,
        orderId,
        tradeId,
        positionId,
        orderEventId,
        walletId,
        assetLedgerId,
        cashLedgerId,
        walletSnapshotId,
        accountSnapshotId,
        spotPositionId,
        fundingSettlementId,
        financingSettlementId,
        fundOperationId,
        fundOrderId);
  }

  private void assertDemoWasRebuilt(JdbcTemplate jdbc, UpgradeFixture fixture) {
    assertThat(count(jdbc, """
        select count(*)
        from core.trading_accounts
        where user_id = ? and account_type = 'DEMO' and status = 'ACTIVE'
        """, fixture.userId())).isEqualTo(1);
    assertThat(count(jdbc, """
        select count(*)
        from core.trading_accounts
        where user_id = ? and account_type = 'DEMO'
        """, fixture.adminId())).isZero();

    UUID rebuiltAccountId = jdbc.queryForObject("""
        select id
        from core.trading_accounts
        where user_id = ? and account_type = 'DEMO' and status = 'ACTIVE'
        """, UUID.class, fixture.userId());
    assertThat(rebuiltAccountId)
        .isNotEqualTo(fixture.firstDemo().accountId())
        .isNotEqualTo(fixture.secondDemo().accountId());

    AccountBalance balance = jdbc.queryForObject("""
        select base_currency, balance, equity, used_margin, free_margin, leverage,
               position_mode, demo_generation
        from core.trading_accounts
        where id = ?
        """, (rs, rowNum) -> new AccountBalance(
            rs.getString("base_currency"),
            rs.getBigDecimal("balance"),
            rs.getBigDecimal("equity"),
            rs.getBigDecimal("used_margin"),
            rs.getBigDecimal("free_margin"),
            rs.getInt("leverage"),
            rs.getString("position_mode"),
            rs.getLong("demo_generation")), rebuiltAccountId);
    assertThat(balance).isEqualTo(new AccountBalance(
        "USDT",
        new BigDecimal("50000.00000000"),
        new BigDecimal("50000.00000000"),
        new BigDecimal("0.00000000"),
        new BigDecimal("50000.00000000"),
        10,
        "ONE_WAY",
        1L));

    WalletBalance wallet = jdbc.queryForObject("""
        select wallet_type, asset, total, available, locked
        from core.wallet_balances
        where account_id = ?
        """, (rs, rowNum) -> new WalletBalance(
            rs.getString("wallet_type"),
            rs.getString("asset"),
            rs.getBigDecimal("total"),
            rs.getBigDecimal("available"),
            rs.getBigDecimal("locked")), rebuiltAccountId);
    assertThat(wallet).isEqualTo(new WalletBalance(
        "SPOT",
        "USDT",
        new BigDecimal("50000.00000000"),
        new BigDecimal("50000.00000000"),
        new BigDecimal("0.00000000")));
    assertThat(count(jdbc, """
        select count(*) from core.wallet_balances
        where account_id = ? and wallet_type = 'USDT_PERP'
        """, rebuiltAccountId)).isZero();

    assertThat(count(jdbc, """
        select count(*) from trading.account_symbol_settings
        where account_id = ? and leverage = 10 and margin_mode = 'CROSS'
        """, rebuiltAccountId)).isEqualTo(10);
    assertThat(count(jdbc, """
        select count(*) from ledger.asset_ledger_entries
        where account_id = ? and wallet_type = 'SPOT' and asset = 'USDT'
          and operation_type = 'DEMO_INIT' and balance_after = 50000
        """, rebuiltAccountId)).isEqualTo(1);
    assertThat(count(jdbc, """
        select count(*) from ledger.ledger_entries
        where account_id = ? and currency = 'USDT'
          and operation_type = 'DEMO_INIT' and balance_after = 50000
        """, rebuiltAccountId)).isEqualTo(1);
  }

  private void assertOldDemoDataWasRemoved(JdbcTemplate jdbc, AccountArtifacts artifacts) {
    assertMissing(jdbc, "core.trading_accounts", artifacts.accountId());
    assertMissing(jdbc, "trading.orders", artifacts.orderId());
    assertMissing(jdbc, "trading.trades", artifacts.tradeId());
    assertMissing(jdbc, "trading.positions", artifacts.positionId());
    assertMissing(jdbc, "trading.order_events", artifacts.orderEventId());
    assertMissing(jdbc, "core.wallet_balances", artifacts.walletId());
    assertMissing(jdbc, "ledger.asset_ledger_entries", artifacts.assetLedgerId());
    assertMissing(jdbc, "ledger.ledger_entries", artifacts.cashLedgerId());
    assertMissing(jdbc, "core.wallet_daily_snapshots", artifacts.walletSnapshotId());
    assertMissing(jdbc, "core.account_daily_snapshots", artifacts.accountSnapshotId());
    assertMissing(jdbc, "trading.spot_positions", artifacts.spotPositionId());
    assertMissing(jdbc, "trading.funding_settlements", artifacts.fundingSettlementId());
    assertMissing(jdbc, "trading.fx_financing_settlements", artifacts.financingSettlementId());
    assertMissing(jdbc, "finance.admin_fund_operations", artifacts.fundOperationId());
    assertMissing(jdbc, "finance.fund_orders", artifacts.fundOrderId());
  }

  private void assertLiveDataWasPreserved(JdbcTemplate jdbc, UpgradeFixture fixture) {
    AccountArtifacts live = fixture.live();
    assertPresent(jdbc, "core.trading_accounts", live.accountId());
    assertPresent(jdbc, "trading.orders", live.orderId());
    assertPresent(jdbc, "trading.trades", live.tradeId());
    assertPresent(jdbc, "trading.positions", live.positionId());
    assertPresent(jdbc, "trading.positions", fixture.conflictingLivePositionId());
    assertThat(count(jdbc, """
        select count(*) from trading.positions
        where account_id = ? and symbol = 'LEGACYUSDT-PERP' and status = 'OPEN'
        """, live.accountId())).isEqualTo(2);
    assertLegacyLivePositionWasPreserved(
        jdbc, live.positionId(), "1", "60000", "1000");
    assertLegacyLivePositionWasPreserved(
        jdbc, fixture.conflictingLivePositionId(), "2", "59000", "4000");
    assertPresent(jdbc, "trading.order_events", live.orderEventId());
    assertPresent(jdbc, "core.wallet_balances", live.walletId());
    assertPresent(jdbc, "ledger.asset_ledger_entries", live.assetLedgerId());
    assertPresent(jdbc, "ledger.ledger_entries", live.cashLedgerId());
    assertPresent(jdbc, "core.wallet_daily_snapshots", live.walletSnapshotId());
    assertPresent(jdbc, "core.account_daily_snapshots", live.accountSnapshotId());
    assertPresent(jdbc, "trading.spot_positions", live.spotPositionId());
    assertPresent(jdbc, "trading.funding_settlements", live.fundingSettlementId());
    assertPresent(jdbc, "trading.fx_financing_settlements", live.financingSettlementId());
    assertPresent(jdbc, "finance.admin_fund_operations", live.fundOperationId());
    assertPresent(jdbc, "finance.fund_orders", live.fundOrderId());
    assertThat(jdbc.queryForObject(
        "select balance from core.trading_accounts where id = ?",
        BigDecimal.class,
        live.accountId())).isEqualByComparingTo("777");
  }

  private void assertLegacyLivePositionWasPreserved(
      JdbcTemplate jdbc,
      UUID positionId,
      String expectedLots,
      String expectedOpenPrice,
      String expectedFloatingPnl) {
    LegacyPositionState position = jdbc.queryForObject("""
        select symbol, side, lots, open_price, current_price, floating_pnl, status,
               product_type, position_mode, position_side, margin_mode
        from trading.positions
        where id = ?
        """, (rs, rowNum) -> new LegacyPositionState(
            rs.getString("symbol"),
            rs.getString("side"),
            rs.getBigDecimal("lots"),
            rs.getBigDecimal("open_price"),
            rs.getBigDecimal("current_price"),
            rs.getBigDecimal("floating_pnl"),
            rs.getString("status"),
            rs.getString("product_type"),
            rs.getString("position_mode"),
            rs.getString("position_side"),
            rs.getString("margin_mode")), positionId);

    assertThat(position.symbol()).isEqualTo("LEGACYUSDT-PERP");
    assertThat(position.side()).isEqualTo("LONG");
    assertThat(position.lots()).isEqualByComparingTo(expectedLots);
    assertThat(position.openPrice()).isEqualByComparingTo(expectedOpenPrice);
    assertThat(position.currentPrice()).isEqualByComparingTo("61000");
    assertThat(position.floatingPnl()).isEqualByComparingTo(expectedFloatingPnl);
    assertThat(position.status()).isEqualTo("OPEN");
    assertThat(position.productType()).isEqualTo("FX_MARGIN");
    assertThat(position.positionMode()).isEqualTo("ONE_WAY");
    assertThat(position.positionSide()).isEqualTo("BOTH");
    assertThat(position.marginMode()).isEqualTo("CROSS");
  }

  private void assertUsersProvidersConfigAndContentWerePreserved(
      JdbcTemplate jdbc,
      UpgradeFixture fixture) {
    assertPresent(jdbc, "auth.users", fixture.userId());
    assertPresent(jdbc, "auth.users", fixture.adminId());
    assertPresent(jdbc, "admin.user_notes", fixture.adminNoteId());
    assertPresent(jdbc, "config.system_settings", fixture.settingId());
    assertPresent(jdbc, "content.articles", fixture.articleId());
    assertPresent(jdbc, "trading.funding_rates", fixture.fundingRateId());
    assertMissing(jdbc, "trading.funding_rates", fixture.duplicateFundingRateId());
    assertThat(count(jdbc, """
        select count(*) from trading.funding_rates
        where symbol = 'BTCUSDT-PERP' and funding_time = ?
        """, OffsetDateTime.of(2026, 7, 11, 0, 0, 0, 0, ZoneOffset.UTC))).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select role from auth.users where id = ?",
        String.class,
        fixture.adminId())).isEqualTo("ADMIN");
    assertThat(jdbc.queryForObject(
        "select priority from market.data_providers where id = ?",
        Integer.class,
        fixture.providerId())).isEqualTo(777);
    assertThat(jdbc.queryForObject(
        "select health_status from market.data_providers where id = ?",
        String.class,
        fixture.providerId())).isEqualTo(ProviderHealthStatus.DOWN.name());
    Map<String, String> localProviderHealth = jdbc.query(
        """
            select code, health_status
            from market.data_providers
            where code in ('local-spot', 'local-perp')
            order by code
            """,
        rs -> {
          java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
          while (rs.next()) {
            values.put(rs.getString("code"), rs.getString("health_status"));
          }
          return values;
        });
    assertThat(localProviderHealth)
        .containsOnly(
            Map.entry("local-perp", ProviderHealthStatus.UP.name()),
            Map.entry("local-spot", ProviderHealthStatus.UP.name()));
    Set<String> supportedHealthStatuses = java.util.Arrays.stream(ProviderHealthStatus.values())
        .map(Enum::name)
        .collect(java.util.stream.Collectors.toSet());
    assertThat(localProviderHealth.values()).allMatch(supportedHealthStatuses::contains);
    assertThat(jdbc.queryForObject(
        "select setting_value from config.system_settings where id = ?",
        String.class,
        fixture.settingId())).isEqualTo("keep-me");
    assertThat(jdbc.queryForObject(
        "select body from content.articles where id = ?",
        String.class,
        fixture.articleId())).isEqualTo("keep-me");
  }

  private void assertMissing(JdbcTemplate jdbc, String table, UUID id) {
    assertThat(count(jdbc, "select count(*) from " + table + " where id = ?", id)).isZero();
  }

  private void assertPresent(JdbcTemplate jdbc, String table, UUID id) {
    assertThat(count(jdbc, "select count(*) from " + table + " where id = ?", id)).isEqualTo(1);
  }

  private int count(JdbcTemplate jdbc, String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private record AccountArtifacts(
      UUID accountId,
      UUID orderId,
      UUID tradeId,
      UUID positionId,
      UUID orderEventId,
      UUID walletId,
      UUID assetLedgerId,
      UUID cashLedgerId,
      UUID walletSnapshotId,
      UUID accountSnapshotId,
      UUID spotPositionId,
      UUID fundingSettlementId,
      UUID financingSettlementId,
      UUID fundOperationId,
      UUID fundOrderId) {
  }

  private record UpgradeFixture(
      UUID userId,
      UUID adminId,
      AccountArtifacts firstDemo,
      AccountArtifacts secondDemo,
      AccountArtifacts live,
      UUID conflictingLivePositionId,
      UUID providerId,
      UUID settingId,
      UUID articleId,
      UUID adminNoteId,
      UUID fundingRateId,
      UUID duplicateFundingRateId) {
  }

  private record AccountBalance(
      String baseCurrency,
      BigDecimal balance,
      BigDecimal equity,
      BigDecimal usedMargin,
      BigDecimal freeMargin,
      int leverage,
      String positionMode,
      long demoGeneration) {
  }

  private record WalletBalance(
      String walletType,
      String asset,
      BigDecimal total,
      BigDecimal available,
      BigDecimal locked) {
  }

  private record LegacyPositionState(
      String symbol,
      String side,
      BigDecimal lots,
      BigDecimal openPrice,
      BigDecimal currentPrice,
      BigDecimal floatingPnl,
      String status,
      String productType,
      String positionMode,
      String positionSide,
      String marginMode) {
  }
}
