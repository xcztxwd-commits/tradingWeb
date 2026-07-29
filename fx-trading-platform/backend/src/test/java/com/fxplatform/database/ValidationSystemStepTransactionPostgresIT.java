package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.persistence.JdbcValidationSystemStepReceiptStore;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import com.fxplatform.validation.service.ValidationResetGate;
import com.fxplatform.validation.service.ValidationSystemStepOperations;
import com.fxplatform.validation.service.ValidationSystemStepService;
import com.fxplatform.validation.service.ValidationSystemStepService.Phase;
import com.fxplatform.validation.service.ValidationSystemStepService.Receipt;
import com.fxplatform.validation.service.ValidationSystemStepService.Request;
import com.fxplatform.validation.service.ValidationSystemStepService.SubStepResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;

class ValidationSystemStepTransactionPostgresIT {

  private static final UUID RUN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000592");
  private static final long GENERATION = 59L;
  private static final Instant START = Instant.parse("2026-07-23T00:00:00Z");
  private static final Instant TICK_TIME = START.plusSeconds(7);

  @Test
  void postgresRunLockPrecedesWorkAndOneTransactionRollsBackEffectsWithTheReceipt() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      prepareDatabase(jdbc);

      ValidationResetGate gate = new ValidationResetGate();
      gate.hydrateReady(GENERATION);
      ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
      JdbcValidationSystemStepReceiptStore receipts =
          new JdbcValidationSystemStepReceiptStore(jdbc, objectMapper, gate);
      DatabaseOperations operations = new DatabaseOperations(jdbc);

      try (AnnotationConfigApplicationContext context =
          transactionalContext(jdbc.getDataSource(), operations, receipts)) {
        ValidationSystemStepService service =
            context.getBean(ValidationSystemStepService.class);
        assertThat(AopUtils.isAopProxy(service)).isTrue();

        operations.failAt("update-trailing-extrema");
        assertBusinessCode(
            () -> service.execute(request(RUN_ID, Phase.PRE_ACTIONS, "pre-v1")),
            "SYSTEM_STEP_PROBE_FAILURE");
        assertThat(count(jdbc, "validation_runtime.system_step_test_effect")).isZero();
        assertThat(count(jdbc, "validation_runtime.system_step_receipts")).isZero();

        operations.failAt(null);
        Receipt receipt = service.execute(request(RUN_ID, Phase.PRE_ACTIONS, "pre-v1"));
        assertThat(count(jdbc, "validation_runtime.system_step_test_effect")).isEqualTo(4);
        assertThat(count(jdbc, "validation_runtime.system_step_receipts")).isEqualTo(1);
        assertThat(receipt.virtualTime()).isEqualTo(TICK_TIME);
        Instant storedVirtualTime = jdbc.queryForObject(
            """
                SELECT virtual_time
                  FROM validation_runtime.system_step_receipts
                 WHERE run_id = ? AND tick_sequence = ? AND phase = 'PRE_ACTIONS'
                """,
            (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(),
            RUN_ID,
            7L);
        assertThat(storedVirtualTime).isEqualTo(TICK_TIME);

        int callsBeforeMissingRun = operations.invocations();
        assertBusinessCode(
            () -> service.execute(request(UUID.randomUUID(), Phase.PRE_ACTIONS, "missing-run")),
            "VALIDATION_RUN_NOT_FOUND");
        assertThat(operations.invocations()).isEqualTo(callsBeforeMissingRun);
      }
    }
  }

  private static AnnotationConfigApplicationContext transactionalContext(
      DataSource dataSource,
      ValidationSystemStepOperations operations,
      JdbcValidationSystemStepReceiptStore receipts
  ) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.getEnvironment().setActiveProfiles("validation");
    context.register(TransactionConfiguration.class);
    context.registerBean(
        "transactionManager",
        PlatformTransactionManager.class,
        () -> new DataSourceTransactionManager(dataSource));
    context.registerBean(
        ValidationSystemStepService.class,
        () -> new ValidationSystemStepService(operations, receipts));
    context.refresh();
    return context;
  }

  private static void prepareDatabase(JdbcTemplate jdbc) {
    jdbc.update(
        """
            UPDATE validation_control.reset_state
               SET generation = ?, state = 'READY', redis_generation = ?,
                   memory_generation = ?, updated_at = now()
             WHERE singleton_key = 1
            """,
        GENERATION,
        GENERATION,
        GENERATION);
    jdbc.update(
        """
            INSERT INTO validation_runtime.run_executions (
              id, generation, request_fingerprint, request_json, state,
              virtual_started_at, speed_multiplier
            ) VALUES (?, ?, 'transaction-probe', '{}'::jsonb, 'RUNNING', ?, 1)
            """,
        RUN_ID,
        GENERATION,
        Timestamp.from(START));
    jdbc.execute(
        """
            CREATE TABLE validation_runtime.system_step_test_effect (
              id UUID PRIMARY KEY,
              run_id UUID NOT NULL,
              tick_sequence BIGINT NOT NULL,
              marker VARCHAR(80) NOT NULL
            )
            """);
  }

  private static long count(JdbcTemplate jdbc, String table) {
    Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    return count == null ? 0L : count;
  }

  private static Request request(UUID runId, Phase phase, String fingerprint) {
    return new Request(
        new CompositeTick(
            runId,
            GENERATION,
            7L,
            TICK_TIME,
            "tick-v7",
            List.of(),
            List.of()),
        phase,
        fingerprint);
  }

  private static void assertBusinessCode(Runnable action, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement(proxyTargetClass = true)
  static class TransactionConfiguration {
  }

  private static final class DatabaseOperations implements ValidationSystemStepOperations {

    private final JdbcTemplate jdbc;
    private String failAt;
    private int invocations;

    private DatabaseOperations(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public SubStepResult publishTick(Request request) {
      return record(request, "publish-tick");
    }

    @Override
    public SubStepResult updateTrailingExtrema(Request request) {
      return record(request, "update-trailing-extrema");
    }

    @Override
    public SubStepResult matchRestingOrders(Request request) {
      return record(request, "match-resting-orders");
    }

    @Override
    public SubStepResult triggerProtectionOrders(Request request) {
      return record(request, "trigger-protection-orders");
    }

    @Override
    public SubStepResult settlePersistedFunding(Request request) {
      return record(request, "settle-persisted-funding");
    }

    @Override
    public SubStepResult scanLiquidations(Request request) {
      return record(request, "scan-liquidations");
    }

    @Override
    public SubStepResult captureCheckpoint(Request request) {
      return record(request, "capture-checkpoint");
    }

    private SubStepResult record(Request request, String marker) {
      invocations++;
      jdbc.update(
          """
              INSERT INTO validation_runtime.system_step_test_effect (
                id, run_id, tick_sequence, marker
              ) VALUES (?, ?, ?, ?)
              """,
          UUID.randomUUID(),
          request.tick().runId(),
          request.tick().sequence(),
          marker);
      if (marker.equals(failAt)) {
        throw new BusinessException(
            "SYSTEM_STEP_PROBE_FAILURE",
            "Synthetic late substep failure");
      }
      return new SubStepResult(marker, "correlation-" + marker, Map.of());
    }

    private void failAt(String marker) {
      failAt = marker;
    }

    private int invocations() {
      return invocations;
    }
  }
}
