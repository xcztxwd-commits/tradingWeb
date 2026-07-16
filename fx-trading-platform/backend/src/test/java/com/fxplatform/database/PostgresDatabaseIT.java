package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.chart.entity.CandleEntity;
import com.fxplatform.chart.repository.CandleRepository;
import com.fxplatform.market.repository.RealtimeCandleRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("database-it")
@Testcontainers
@ExtendWith(PostgresDatabaseIT.DockerRequiredCondition.class)
class PostgresDatabaseIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private CandleRepository candleRepository;

  @Autowired
  private RealtimeCandleRepository realtimeCandleRepository;

  @Test
  void flywaySchemaMapperInsertAndPostgresUpsertRunAgainstRealPostgres() {
    assertThat(postgres.isRunning()).isTrue();
    assertThat(count("select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'flyway_schema_history'"))
        .isEqualTo(1);
    assertThat(count("select count(*) from information_schema.schemata where schema_name in ('auth', 'core', 'market')"))
        .isEqualTo(3);

    String symbol = "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    String timeframe = "1m";
    Instant openTime = Instant.parse("2026-06-13T00:00:00Z");

    CandleEntity initial = new CandleEntity();
    initial.setId(UUID.randomUUID());
    initial.setSymbol(symbol);
    initial.setTimeframe(timeframe);
    initial.setOpenTime(openTime);
    initial.setOpen(new BigDecimal("1.1000000000"));
    initial.setHigh(new BigDecimal("1.1000000000"));
    initial.setLow(new BigDecimal("1.1000000000"));
    initial.setClose(new BigDecimal("1.1000000000"));
    initial.setVolume(new BigDecimal("5.00000000"));
    initial.setSource("it-mapper");

    assertThat(candleRepository.insert(initial)).isEqualTo(1);
    assertThat(candleCount(symbol, timeframe, openTime)).isEqualTo(1);

    realtimeCandleRepository.upsert(
        symbol,
        timeframe,
        openTime,
        new BigDecimal("1.2500000000"),
        new BigDecimal("2.00000000"));

    CandleEntity updated = candleRepository.selectOne(new LambdaQueryWrapper<CandleEntity>()
        .eq(CandleEntity::getSymbol, symbol)
        .eq(CandleEntity::getTimeframe, timeframe)
        .eq(CandleEntity::getOpenTime, openTime));

    assertThat(candleCount(symbol, timeframe, openTime)).isEqualTo(1);
    assertThat(updated).isNotNull();
    assertThat(updated.getOpen()).isEqualByComparingTo("1.1000000000");
    assertThat(updated.getHigh()).isEqualByComparingTo("1.2500000000");
    assertThat(updated.getLow()).isEqualByComparingTo("1.1000000000");
    assertThat(updated.getClose()).isEqualByComparingTo("1.2500000000");
    assertThat(updated.getVolume()).isEqualByComparingTo("7.00000000");
  }

  @Test
  void springBootAppliesTheV46V47MigrationContract() {
    assertThat(count("""
        select count(*)
        from information_schema.tables
        where table_schema = 'trading' and table_name = 'account_symbol_settings'
        """)).isEqualTo(1);
    assertThat(count("""
        select count(*)
        from information_schema.columns
        where table_schema = 'trading'
          and table_name = 'orders'
          and column_name in ('position_side', 'margin_mode', 'quantity_unit', 'order_origin')
        """)).isEqualTo(4);
    assertThat(count("""
        select count(*)
        from market.symbols
        where tradable = true
          and symbol in (
            'BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT', 'XRPUSDT',
            'BTCUSDT-PERP', 'ETHUSDT-PERP', 'BNBUSDT-PERP', 'SOLUSDT-PERP', 'XRPUSDT-PERP'
          )
        """)).isEqualTo(10);
    assertThat(count("""
        select count(*)
        from market.symbols
        where tradable = true
          and product_type = 'LINEAR_PERP'
          and fixed_funding_rate = 0.0001
          and fixed_funding_interval_minutes = 480
          and funding_source_priority = ARRAY['BINANCE', 'OKX', 'FIXED']::TEXT[]
        """)).isEqualTo(5);
    assertThat(count("""
        select count(*)
        from flyway_schema_history
        where success = true and version = '47'
        """)).isEqualTo(1);
  }

  @Test
  void springBootAppliesTradingConcurrencyUniquenessGuards() {
    assertThat(count("""
        select count(*)
        from pg_indexes
        where schemaname = 'trading'
          and indexname in (
            'ux_trades_p0_order_full_fill',
            'ux_positions_one_way_open_both',
            'ux_positions_hedge_open_side',
            'ux_orders_user_account_client_order_id'
          )
        """)).isEqualTo(4);
    assertThat(count("""
        select count(*)
        from information_schema.table_constraints
        where constraint_schema = 'trading'
          and table_name = 'funding_settlements'
          and constraint_name = 'uq_funding_settlements_position_time'
          and constraint_type = 'UNIQUE'
        """)).isEqualTo(1);
    assertThat(count("""
        select count(*)
        from flyway_schema_history
        where success = true and version = '52'
        """)).isEqualTo(1);
    String tradeGuardDefinition = jdbcTemplate.queryForObject("""
        select pg_get_indexdef(index_relation.oid)
        from pg_class index_relation
        join pg_namespace namespace on namespace.oid = index_relation.relnamespace
        join pg_index index_metadata on index_metadata.indexrelid = index_relation.oid
        where namespace.nspname = 'trading'
          and index_relation.relname = 'ux_trades_p0_order_full_fill'
          and index_metadata.indisunique = true
        """, String.class);
    assertThat(tradeGuardDefinition)
        .containsIgnoringCase("CREATE UNIQUE INDEX")
        .containsIgnoringCase("ON trading.trades USING btree (order_id)")
        .containsIgnoringCase("WHERE (canonical_full_fill = true)");
  }

  @Test
  void productTypeConstraintsRejectNullAndInvalidWrites() {
    String nullProductTypeSymbol = "PTN" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    assertThatThrownBy(() -> insertSymbol(nullProductTypeSymbol, null))
        .hasMessageContaining("product_type");

    String invalidProductTypeSymbol = "PTI" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    assertThatThrownBy(() -> insertSymbol(invalidProductTypeSymbol, "BAD_PRODUCT"))
        .hasMessageContaining("product_type");
  }

  @Test
  void flywayBackfillsLegacySymbolsAndDefaultProductType() {
    assertThat(count("select count(*) from market.symbols where product_type is null"))
        .isZero();

    String defaultedSymbol = "PTD" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    jdbcTemplate.update("""
        insert into market.symbols (
          symbol, display_name, provider_symbol, asset_class, base_currency, quote_currency,
          pip_size, tick_size, lot_size, min_lot, max_lot, leverage
        ) values (?, ?, ?, 'FOREX', 'EUR', 'USD', 0.0001, 0.00001, 100000, 0.01, 100, 100)
        """, defaultedSymbol, defaultedSymbol, defaultedSymbol);

    String productType = jdbcTemplate.queryForObject(
        "select product_type from market.symbols where symbol = ?",
        String.class,
        defaultedSymbol);
    assertThat(productType).isEqualTo("FX_MARGIN");
  }

  private int candleCount(String symbol, String timeframe, Instant openTime) {
    return jdbcTemplate.queryForObject("""
        select count(*)
        from market.candles
        where symbol = ? and timeframe = ? and open_time = ?
        """, Integer.class, symbol, timeframe, Timestamp.from(openTime));
  }

  private int count(String sql) {
    return jdbcTemplate.queryForObject(sql, Integer.class);
  }

  private void insertSymbol(String symbol, String productType) {
    jdbcTemplate.update("""
        insert into market.symbols (
          symbol, display_name, provider_symbol, asset_class, product_type, base_currency, quote_currency,
          pip_size, tick_size, lot_size, min_lot, max_lot, leverage
        ) values (?, ?, ?, 'FOREX', ?, 'EUR', 'USD', 0.0001, 0.00001, 100000, 0.01, 100, 100)
        """, symbol, symbol, symbol, productType);
  }

  static class DockerRequiredCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
      try {
        if (DockerClientFactory.instance().isDockerAvailable()) {
          return ConditionEvaluationResult.enabled("Docker is available for PostgreSQL Testcontainers");
        }
        return blocked("Docker is not available");
      } catch (RuntimeException ex) {
        return blocked(ex.getMessage());
      }
    }

    private ConditionEvaluationResult blocked(String detail) {
      return ConditionEvaluationResult.disabled(
          "BLOCKED: Docker is required for PostgreSQL Testcontainers integration tests. " + detail);
    }
  }
}
