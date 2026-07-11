package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

class V46V47EmptyDatabaseIT {

  private static final Set<String> TRADABLE_SYMBOLS = Set.of(
      "BTCUSDT",
      "ETHUSDT",
      "BNBUSDT",
      "SOLUSDT",
      "XRPUSDT",
      "BTCUSDT-PERP",
      "ETHUSDT-PERP",
      "BNBUSDT-PERP",
      "SOLUSDT-PERP",
      "XRPUSDT-PERP");

  @Test
  void migrationResourcesDeclareTheV46V47Contract() throws IOException {
    String v46 = PostgresMigrationTestSupport.readMigration("V46__demo_spot_perp_core.sql");
    String v47 = PostgresMigrationTestSupport.readMigration("V47__demo_funding_sources_and_reseed.sql");

    assertThat(v46)
        .contains("account_symbol_settings")
        .contains("position_side")
        .contains("fee_asset")
        .contains("margin_mode")
        .contains("ux_positions_one_way_open_both")
        .contains("ux_positions_hedge_open_side")
        .doesNotContain("UPDATE trading.positions p")
        .doesNotContain("CREATE UNIQUE INDEX IF NOT EXISTS ux_trading_accounts_user_active_demo");
    assertThat(v47)
        .contains("fixed_funding_rate NUMERIC(18, 10) NOT NULL DEFAULT 0.0001")
        .contains("fixed_funding_interval_minutes INTEGER NOT NULL DEFAULT 480")
        .contains("funding_source_priority TEXT[] NOT NULL DEFAULT ARRAY['BINANCE', 'OKX', 'FIXED']")
        .contains("ux_funding_rates_symbol_time")
        .contains("ux_trading_accounts_user_active_demo")
        .contains("BTCUSDT-PERP")
        .contains("XRPUSDT-PERP")
        .contains("binance-usdm")
        .contains("okx-swap")
        .contains("local-perp")
        .contains("UPDATE market.data_providers\nSET enabled = true")
        .doesNotContain("'USDT_PERP'");
    assertThat(v47.indexOf("CREATE UNIQUE INDEX IF NOT EXISTS ux_trading_accounts_user_active_demo"))
        .isGreaterThan(v47.indexOf("DELETE FROM core.trading_accounts"));
    assertThat(v47.indexOf("DELETE FROM trading.funding_rates duplicate"))
        .isGreaterThanOrEqualTo(0)
        .isLessThan(v47.indexOf("CREATE UNIQUE INDEX IF NOT EXISTS ux_funding_rates_symbol_time"));
  }

  @Test
  void emptyDatabaseMigratesToV47WithExactSchemaAndTradableProducts() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);

      assertColumns(jdbc, "core", "trading_accounts", Set.of(
          "position_mode", "demo_generation", "reset_at"));
      assertColumns(jdbc, "trading", "orders", Set.of(
          "product_type", "position_mode", "position_side", "margin_mode", "quantity_unit",
          "original_quantity", "base_quantity", "time_in_force", "reduce_only", "order_origin",
          "system_reason", "trigger_price", "trigger_price_type", "trigger_execution_type",
          "protection_type", "parent_order_id", "parent_position_id", "contingency_group_id",
          "hold_owner_order_id", "liquidity_role", "fee_asset", "version"));
      assertColumns(jdbc, "trading", "trades", Set.of(
          "product_type", "position_side", "margin_mode", "fee", "fee_asset", "liquidity_role",
          "system_reason", "source_mode", "provider_code"));
      assertColumns(jdbc, "trading", "positions", Set.of(
          "product_type", "position_mode", "position_side", "margin_mode", "version"));
      assertColumns(jdbc, "market", "symbols", Set.of(
          "fixed_funding_rate", "fixed_funding_interval_minutes", "funding_source_priority",
          "funding_stale_seconds"));
      assertColumns(jdbc, "trading", "funding_rates", Set.of(
          "provider_code", "source_mode", "as_of", "interval_minutes", "raw_payload_hash"));
      assertColumns(jdbc, "trading", "funding_settlements", Set.of(
          "position_side", "margin_mode", "mark_price", "source", "balance_after",
          "isolated_margin_after", "shortfall"));
      assertTable(jdbc, "trading", "account_symbol_settings");
      assertFundingRateUniqueness(jdbc);

      Set<String> actualTradableSymbols = Set.copyOf(jdbc.queryForList(
          "select symbol from market.symbols where tradable = true",
          String.class));
      assertThat(actualTradableSymbols).isEqualTo(TRADABLE_SYMBOLS);
      assertNotTradable(jdbc, "EURUSD");
      assertNotTradable(jdbc, "BTCUSD-INVERSE");
      assertNotTradable(jdbc, "BTCUSDT-OPTION");

      assertThat(count(jdbc, """
          select count(*)
          from market.symbols
          where tradable = true
            and product_type = 'CRYPTO_SPOT'
            and leverage = 100
          """)).isEqualTo(5);
      assertThat(count(jdbc, """
          select count(*)
          from market.symbols
          where tradable = true
            and product_type = 'LINEAR_PERP'
            and leverage = 100
            and settlement_asset = 'USDT'
            and margin_asset = 'USDT'
          """)).isEqualTo(5);
      assertThat(count(jdbc, """
          select count(*)
          from market.symbols
          where tradable = true
            and product_type = 'LINEAR_PERP'
            and fixed_funding_rate = 0.0001
            and fixed_funding_interval_minutes = 480
            and funding_source_priority = ARRAY['BINANCE', 'OKX', 'FIXED']::TEXT[]
          """)).isEqualTo(5);
      assertThat(count(jdbc, """
          select count(*)
          from (
            select s.symbol
            from market.symbols s
            join market.symbol_provider_bindings b on b.symbol_id = s.id and b.enabled = true
            join market.data_providers p on p.id = b.provider_id
            where s.tradable = true
              and (
                (s.product_type = 'CRYPTO_SPOT' and p.code in ('binance', 'okx', 'local-spot'))
                or
                (s.product_type = 'LINEAR_PERP' and p.code in ('binance-usdm', 'okx-swap', 'local-perp'))
            )
            group by s.symbol
            having count(distinct p.code) = 3
               and max(case when p.code in ('binance', 'binance-usdm') then b.priority end) = 10
               and max(case when p.code in ('okx', 'okx-swap') then b.priority end) = 20
               and max(case when p.code in ('local-spot', 'local-perp') then b.priority end) = 900
          ) bound_symbols
          """)).isEqualTo(10);
      assertThat(count(jdbc, """
          select count(*)
          from market.data_providers
          where enabled = true
            and code in ('binance', 'okx', 'local-spot', 'binance-usdm', 'okx-swap', 'local-perp')
          """)).isEqualTo(6);
      assertThat(count(jdbc, """
          select count(distinct p.code)
          from market.data_providers p
          join market.data_provider_capabilities c on c.provider_id = p.id
          where p.enabled = true and c.enabled = true and c.capability = 'QUOTE'
            and p.code in ('binance', 'okx', 'local-spot', 'binance-usdm', 'okx-swap', 'local-perp')
          """)).isEqualTo(6);

      assertOneActiveDemoAccountAndSettingDefaults(jdbc);
      assertThat(count(jdbc, "select count(*) from core.wallet_balances where wallet_type = 'USDT_PERP'"))
          .isZero();
      assertThat(jdbc.queryForObject(
          "select max(version::integer) from flyway_schema_history where success = true",
          Integer.class)).isEqualTo(47);
    }
  }

  private void assertOneActiveDemoAccountAndSettingDefaults(JdbcTemplate jdbc) {
    UUID userId = UUID.randomUUID();
    UUID activeDemoId = UUID.randomUUID();
    jdbc.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values (?, ?, 'hash', 'ACTIVE', 'USER')
        """, userId, userId + "@migration.test");
    jdbc.update("""
        insert into core.trading_accounts (
          id, user_id, account_type, base_currency, balance, equity, used_margin,
          free_margin, leverage, status
        ) values (?, ?, 'DEMO', 'USDT', 50000, 50000, 0, 50000, 10, 'ACTIVE')
        """, activeDemoId, userId);

    assertThatThrownBy(() -> jdbc.update("""
        insert into core.trading_accounts (
          id, user_id, account_type, base_currency, balance, equity, used_margin,
          free_margin, leverage, status
        ) values (?, ?, 'DEMO', 'USDT', 50000, 50000, 0, 50000, 10, 'ACTIVE')
        """, UUID.randomUUID(), userId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ux_trading_accounts_user_active_demo");

    jdbc.update("""
        insert into core.trading_accounts (
          id, user_id, account_type, base_currency, balance, equity, used_margin,
          free_margin, leverage, status
        ) values (?, ?, 'DEMO', 'USDT', 0, 0, 0, 0, 10, 'CLOSED')
        """, UUID.randomUUID(), userId);
    assertThat(count(jdbc, "select count(*) from core.trading_accounts where user_id = '" + userId + "'"))
        .isEqualTo(2);

    jdbc.update(
        "insert into trading.account_symbol_settings (account_id, symbol) values (?, 'BTCUSDT-PERP')",
        activeDemoId);
    List<Object> defaults = jdbc.queryForList("""
        select leverage, margin_mode, quantity_unit, version
        from trading.account_symbol_settings
        where account_id = ? and symbol = 'BTCUSDT-PERP'
        """, activeDemoId).getFirst().values().stream().toList();
    assertThat(defaults).containsExactly(10, "CROSS", "BASE", 0L);

    assertThatThrownBy(() -> jdbc.update("""
        insert into trading.account_symbol_settings (account_id, symbol, leverage)
        values (?, 'ETHUSDT-PERP', 101)
        """, activeDemoId))
        .isInstanceOf(DataAccessException.class);

    assertPerpetualPositionSlotUniqueness(jdbc, activeDemoId);
  }

  private void assertPerpetualPositionSlotUniqueness(JdbcTemplate jdbc, UUID accountId) {
    insertPerpetualPosition(jdbc, accountId, "BTCUSDT-PERP", "LONG", "ONE_WAY", "BOTH");
    assertThatThrownBy(() -> insertPerpetualPosition(
        jdbc, accountId, "BTCUSDT-PERP", "LONG", "ONE_WAY", "BOTH"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ux_positions_one_way_open_both");

    insertPerpetualPosition(jdbc, accountId, "ETHUSDT-PERP", "LONG", "HEDGE", "LONG");
    insertPerpetualPosition(jdbc, accountId, "ETHUSDT-PERP", "SHORT", "HEDGE", "SHORT");
    assertThatThrownBy(() -> insertPerpetualPosition(
        jdbc, accountId, "ETHUSDT-PERP", "LONG", "HEDGE", "LONG"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ux_positions_hedge_open_side");
  }

  private void insertPerpetualPosition(
      JdbcTemplate jdbc,
      UUID accountId,
      String symbol,
      String side,
      String positionMode,
      String positionSide) {
    jdbc.update("""
        insert into trading.positions (
          id, account_id, symbol, side, lots, open_price, current_price,
          floating_pnl, realized_pnl, status, leverage, margin_held,
          product_type, position_mode, position_side, margin_mode
        ) values (?, ?, ?, ?, 1, 60000, 60000, 0, 0, 'OPEN', 10, 6000,
          'LINEAR_PERP', ?, ?, 'CROSS')
        """, UUID.randomUUID(), accountId, symbol, side, positionMode, positionSide);
  }

  private void assertFundingRateUniqueness(JdbcTemplate jdbc) {
    OffsetDateTime fundingTime = OffsetDateTime.of(2026, 7, 11, 8, 0, 0, 0, ZoneOffset.UTC);
    jdbc.update("""
        insert into trading.funding_rates (
          id, symbol, funding_rate, funding_time, next_funding_time, mark_price
        ) values (?, 'BTCUSDT-PERP', 0.0001, ?, ?, 60000)
        """,
        UUID.randomUUID(),
        fundingTime,
        fundingTime.plusHours(8));

    assertThatThrownBy(() -> jdbc.update("""
        insert into trading.funding_rates (
          id, symbol, funding_rate, funding_time, next_funding_time, mark_price
        ) values (?, 'BTCUSDT-PERP', 0.0002, ?, ?, 60001)
        """,
        UUID.randomUUID(),
        fundingTime,
        fundingTime.plusHours(8)))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ux_funding_rates_symbol_time");
  }

  private void assertColumns(JdbcTemplate jdbc, String schema, String table, Set<String> expected) {
    Set<String> actual = Set.copyOf(jdbc.queryForList("""
        select column_name
        from information_schema.columns
        where table_schema = ? and table_name = ?
        """, String.class, schema, table));
    assertThat(actual).containsAll(expected);
  }

  private void assertTable(JdbcTemplate jdbc, String schema, String table) {
    assertThat(count(jdbc, """
        select count(*)
        from information_schema.tables
        where table_schema = '%s' and table_name = '%s'
        """.formatted(schema, table))).isEqualTo(1);
  }

  private void assertNotTradable(JdbcTemplate jdbc, String symbol) {
    assertThat(jdbc.queryForObject(
        "select count(*) from market.symbols where symbol = ? and tradable = true",
        Integer.class,
        symbol)).isZero();
  }

  private int count(JdbcTemplate jdbc, String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }
}

final class PostgresMigrationTestSupport {

  private static final Path MIGRATION_DIRECTORY = Path.of("src/main/resources/db/migration");

  private PostgresMigrationTestSupport() {
  }

  static String readMigration(String fileName) throws IOException {
    Path path = MIGRATION_DIRECTORY.resolve(fileName);
    assertThat(path).exists().isRegularFile();
    return Files.readString(path);
  }

  static PostgreSQLContainer<?> startPostgresOrAbort() {
    try {
      DockerClientFactory.instance().client().versionCmd().exec();
      PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
      postgres.start();
      return postgres;
    } catch (RuntimeException ex) {
      Assumptions.assumeTrue(
          false,
          () -> "BLOCKED: Docker is required for PostgreSQL/Flyway migration tests. " + rootMessage(ex));
      throw ex;
    }
  }

  static void migrate(PostgreSQLContainer<?> postgres, String target) {
    var configuration = Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(MigrationVersion.fromVersion(target));
    }
    configuration.load().migrate();
  }

  static JdbcTemplate jdbc(PostgreSQLContainer<?> postgres) {
    return new JdbcTemplate(new DriverManagerDataSource(
        postgres.getJdbcUrl(),
        postgres.getUsername(),
        postgres.getPassword()));
  }

  private static String rootMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getClass().getSimpleName() + ": " + current.getMessage();
  }
}
