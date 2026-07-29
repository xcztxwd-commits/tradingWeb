package com.fxplatform.tradinglab.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("database-it")
@Testcontainers(disabledWithoutDocker = true)
class TradingLabOperationGatePostgresIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TradingLabOperationGate operationGate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean(name = "taskScheduler")
  private TaskScheduler taskScheduler;

  @Test
  void environmentPermitIsGlobalSingleFlightAndRunCreationWaitsForItsTransaction()
      throws Exception {
    CountDownLatch environmentHasPermit = new CountDownLatch(1);
    CountDownLatch releaseEnvironment = new CountDownLatch(1);
    AtomicBoolean runCreationEntered = new AtomicBoolean();
    try (var executor = Executors.newFixedThreadPool(3)) {
      Future<?> environment = executor.submit(() -> transaction().executeWithoutResult(status -> {
        operationGate.acquireEnvironmentMutationPermit();
        environmentHasPermit.countDown();
        await(releaseEnvironment);
      }));
      assertThat(environmentHasPermit.await(5, TimeUnit.SECONDS)).isTrue();

      Future<String> overlappingMutation = executor.submit(() -> {
        try {
          return transaction().execute(status -> {
            operationGate.acquireEnvironmentMutationPermit();
            return "unexpected-success";
          });
        } catch (TradingLabEnvironmentException failure) {
          return failure.getCode();
        }
      });
      assertThat(overlappingMutation.get(5, TimeUnit.SECONDS))
          .isEqualTo("TRADING_LAB_ENVIRONMENT_BUSY");

      Future<?> runCreation = executor.submit(() -> transaction().executeWithoutResult(status -> {
        operationGate.awaitRunCreationPermit();
        runCreationEntered.set(true);
      }));
      Thread.sleep(150);
      assertThat(runCreationEntered).isFalse();

      releaseEnvironment.countDown();
      environment.get(5, TimeUnit.SECONDS);
      runCreation.get(5, TimeUnit.SECONDS);
      assertThat(runCreationEntered).isTrue();
    } finally {
      releaseEnvironment.countDown();
    }
  }

  @Test
  void activeRunQueryIncludesQueuedAndCleaningWithoutDependingOnLeaseState() {
    UUID actorId = insertUser();
    UUID scenarioId = insertScenario(actorId);
    insertRun(actorId, scenarioId, "COMPLETED");
    insertRun(actorId, scenarioId, "CANCELLED");
    insertRun(actorId, scenarioId, "FAILED");

    boolean terminalOnly = Boolean.TRUE.equals(
        transaction().execute(status -> operationGate.hasNonTerminalRuns()));
    assertThat(terminalOnly).isFalse();

    UUID queued = insertRun(actorId, scenarioId, "QUEUED");
    UUID cleaning = insertRun(actorId, scenarioId, "CLEANING");
    boolean activePresent = Boolean.TRUE.equals(
        transaction().execute(status -> operationGate.hasNonTerminalRuns()));
    assertThat(activePresent).isTrue();

    jdbcTemplate.update(
        "delete from trading_lab.runs where id in (?, ?)",
        queued,
        cleaning);
    boolean activeRemoved = Boolean.TRUE.equals(
        transaction().execute(status -> operationGate.hasNonTerminalRuns()));
    assertThat(activeRemoved).isFalse();
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(transactionManager);
  }

  private UUID insertUser() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values (?, ?, 'test-hash', 'ACTIVE', 'ADMIN')
        """, id, "environment-gate-" + id + "@trading-lab.test");
    return id;
  }

  private UUID insertScenario(UUID actorId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.scenarios (
          id, name, status, negative_mode, seed, model_version, scenario_json,
          config_snapshot_json, config_snapshot_hash, symbol_config_version,
          code_version, created_by, updated_by
        )
        values (?, 'environment gate', 'FROZEN', false, 'gate-seed', 'model-gate',
                '{"seed":"gate-seed"}'::jsonb, '{}'::jsonb, ?,
                'symbols-gate', 'code-gate', ?, ?)
        """, id, "a".repeat(64), actorId, actorId);
    return id;
  }

  private UUID insertRun(UUID actorId, UUID scenarioId, String state) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.runs (
          id, scenario_id, state, scenario_snapshot_json, config_snapshot_json,
          config_snapshot_hash, model_version, symbol_config_version,
          code_version, created_by
        )
        values (?, ?, ?, '{}'::jsonb, '{}'::jsonb, ?,
                'model-gate', 'symbols-gate', 'code-gate', ?)
        """, id, scenarioId, state, "b".repeat(64), actorId);
    return id;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for environment gate release");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
