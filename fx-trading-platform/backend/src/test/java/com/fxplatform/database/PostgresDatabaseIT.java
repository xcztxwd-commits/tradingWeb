package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

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
