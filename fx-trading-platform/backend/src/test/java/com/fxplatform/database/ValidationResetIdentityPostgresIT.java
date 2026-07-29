package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationAdministrativeDatabase;
import com.fxplatform.validation.service.ValidationResetCommand;
import com.fxplatform.validation.service.ValidationResetOperations;
import com.fxplatform.validation.service.ValidationResetReceipt;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

class ValidationResetIdentityPostgresIT {

  private static final Instant START = Instant.parse("2026-07-23T08:00:00Z");

  @Test
  void exactReceiptSurvivesCleanAndReplaysBeforeTheNowStaleGeneration() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      migrateResetControl(jdbc);
      DataSource dataSource = jdbc.getDataSource();
      ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
      UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000731");
      UUID operationId = UUID.fromString("00000000-0000-0000-0000-000000000732");
      ValidationResetCommand command = new ValidationResetCommand(
          runId,
          operationId,
          ValidationResetCommand.Mode.INITIAL,
          0L);
      String databaseName = jdbc.queryForObject("SELECT current_database()", String.class);
      ValidationResetReceipt succeeded;

      try (ValidationAdministrativeDatabase database =
          new ValidationAdministrativeDatabase(dataSource, objectMapper)) {
        assertThat(database.resolveResetCommand(command).isReplay()).isFalse();
        database.markResetting(command, databaseName, START);
        long targetGeneration = database.currentResetPlan().targetGeneration();

        assertThat(jdbc.queryForMap(
            """
                SELECT status, run_id, reset_id, expected_generation, target_generation
                  FROM validation_control.reset_receipts
                 WHERE operation_id = ?
                """,
            operationId))
            .containsEntry("status", "IN_PROGRESS")
            .containsEntry("run_id", runId)
            .containsEntry("reset_id", operationId)
            .containsEntry("expected_generation", 0L)
            .containsEntry("target_generation", 1L);

        cleanResetSchemasAndReplayControlMigrations(jdbc);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM validation_control.reset_receipts WHERE operation_id = ?",
            Integer.class,
            operationId)).isEqualTo(1);

        succeeded = successfulReceipt(databaseName, targetGeneration);
        database.persistResetReceipt(command, succeeded);
      }

      try (ValidationAdministrativeDatabase replayDatabase =
          new ValidationAdministrativeDatabase(dataSource, objectMapper)) {
        assertThat(replayDatabase.resolveResetCommand(command).replayReceipt())
            .isEqualTo(succeeded);
        assertBusinessCode(
            () -> replayDatabase.resolveResetCommand(new ValidationResetCommand(
                UUID.fromString("00000000-0000-0000-0000-000000000733"),
                operationId,
                ValidationResetCommand.Mode.INITIAL,
                0L)),
            ValidationResetCommand.CONFLICT_ERROR);
        assertBusinessCode(
            () -> replayDatabase.resolveResetCommand(new ValidationResetCommand(
                runId,
                UUID.fromString("00000000-0000-0000-0000-000000000734"),
                ValidationResetCommand.Mode.INITIAL,
                0L)),
            ValidationResetCommand.CONFLICT_ERROR);

        ValidationResetCommand finalCleanup = new ValidationResetCommand(
            runId,
            UUID.fromString("00000000-0000-0000-0000-000000000735"),
            ValidationResetCommand.Mode.FINAL,
            1L);
        assertThat(replayDatabase.resolveResetCommand(finalCleanup).isReplay()).isFalse();
        replayDatabase.markResetting(finalCleanup, databaseName, START.plusSeconds(2));
        ValidationResetReceipt finalReceipt = successfulReceipt(
            databaseName,
            replayDatabase.currentResetPlan().targetGeneration());
        replayDatabase.persistResetReceipt(finalCleanup, finalReceipt);
        assertThat(replayDatabase.resolveResetCommand(finalCleanup).replayReceipt())
            .isEqualTo(finalReceipt);
      }
    }
  }

  @Test
  void twoRunsChainInitialAndFinalResetsAcrossNonZeroGenerationsWithoutRuntimeRows() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      migrateResetControl(jdbc);
      ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
      UUID firstRunId = UUID.fromString("00000000-0000-0000-0000-000000000761");
      UUID secondRunId = UUID.fromString("00000000-0000-0000-0000-000000000762");
      ValidationResetCommand firstInitial = command(
          firstRunId,
          "00000000-0000-0000-0000-000000000763",
          ValidationResetCommand.Mode.INITIAL,
          0L);
      ValidationResetCommand firstFinal = command(
          firstRunId,
          "00000000-0000-0000-0000-000000000764",
          ValidationResetCommand.Mode.FINAL,
          1L);
      ValidationResetCommand secondInitial = command(
          secondRunId,
          "00000000-0000-0000-0000-000000000765",
          ValidationResetCommand.Mode.INITIAL,
          2L);
      ValidationResetCommand secondFinal = command(
          secondRunId,
          "00000000-0000-0000-0000-000000000766",
          ValidationResetCommand.Mode.FINAL,
          3L);

      try (ValidationAdministrativeDatabase database =
          new ValidationAdministrativeDatabase(jdbc.getDataSource(), objectMapper)) {
        String databaseName = jdbc.queryForObject("SELECT current_database()", String.class);

        completeReset(database, firstInitial, databaseName, START);
        completeReset(database, firstFinal, databaseName, START.plusSeconds(2));
        completeReset(database, secondInitial, databaseName, START.plusSeconds(4));
        completeReset(database, secondFinal, databaseName, START.plusSeconds(6));
      }

      assertThat(jdbc.queryForObject(
          "SELECT generation FROM validation_control.reset_state WHERE singleton_key = 1",
          Long.class)).isEqualTo(4L);
      assertThat(jdbc.queryForList(
          """
              SELECT mode, expected_generation, target_generation, status
                FROM validation_control.reset_receipts
               ORDER BY target_generation
              """))
          .containsExactly(
              Map.of(
                  "mode", "INITIAL",
                  "expected_generation", 0L,
                  "target_generation", 1L,
                  "status", "SUCCEEDED"),
              Map.of(
                  "mode", "FINAL",
                  "expected_generation", 1L,
                  "target_generation", 2L,
                  "status", "SUCCEEDED"),
              Map.of(
                  "mode", "INITIAL",
                  "expected_generation", 2L,
                  "target_generation", 3L,
                  "status", "SUCCEEDED"),
              Map.of(
                  "mode", "FINAL",
                  "expected_generation", 3L,
                  "target_generation", 4L,
                  "status", "SUCCEEDED"));
      assertThat(jdbc.queryForObject(
          "SELECT COUNT(*) FROM validation_runtime.run_executions",
          Integer.class)).isZero();
    }
  }

  @Test
  void staleGenerationAndFinalResetWithAnotherRunFailClosed() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      migrateResetControl(jdbc);
      ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
      UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000741");

      try (ValidationAdministrativeDatabase database =
          new ValidationAdministrativeDatabase(jdbc.getDataSource(), objectMapper)) {
        assertBusinessCode(
            () -> database.resolveResetCommand(command(
                runId,
                "00000000-0000-0000-0000-000000000742",
                ValidationResetCommand.Mode.FINAL,
                1L)),
            ValidationResetCommand.STALE_GENERATION_ERROR);

        prepareReadyGeneration(jdbc, 1L);
        assertBusinessCode(
            () -> database.resolveResetCommand(command(
                runId,
                "00000000-0000-0000-0000-000000000744",
                ValidationResetCommand.Mode.FINAL,
                1L)),
            ValidationResetCommand.FINAL_RUN_MISMATCH_ERROR);
        insertRuntimeRun(jdbc, runId, 1L, "COMPLETED");

        assertBusinessCode(
            () -> database.resolveResetCommand(command(
                UUID.fromString("00000000-0000-0000-0000-000000000745"),
                "00000000-0000-0000-0000-000000000746",
                ValidationResetCommand.Mode.FINAL,
                1L)),
            ValidationResetCommand.FINAL_RUN_MISMATCH_ERROR);
        assertThat(database.resolveResetCommand(command(
            runId,
            "00000000-0000-0000-0000-000000000747",
            ValidationResetCommand.Mode.FINAL,
            1L)).isReplay()).isFalse();
      }
    }
  }

  @Test
  void finalResetRequiresTheSameTerminalRunAndInProgressReplayNeverCleansAgain() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      migrateResetControl(jdbc);
      ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
      UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000751");
      ValidationResetCommand command = command(
          runId,
          "00000000-0000-0000-0000-000000000752",
          ValidationResetCommand.Mode.FINAL,
          1L);
      prepareReadyGeneration(jdbc, 1L);
      insertRuntimeRun(jdbc, runId, 1L, "RUNNING");

      try (ValidationAdministrativeDatabase database =
          new ValidationAdministrativeDatabase(jdbc.getDataSource(), objectMapper)) {
        assertBusinessCode(
            () -> database.resolveResetCommand(command),
            ValidationResetCommand.FINAL_RUN_NOT_TERMINAL_ERROR);
        jdbc.update(
            "UPDATE validation_runtime.run_executions SET state = 'COMPLETED' WHERE id = ?",
            runId);
        assertThat(database.resolveResetCommand(command).isReplay()).isFalse();
        String databaseName = jdbc.queryForObject("SELECT current_database()", String.class);
        database.markResetting(command, databaseName, START);
      }

      try (ValidationAdministrativeDatabase replayDatabase =
          new ValidationAdministrativeDatabase(jdbc.getDataSource(), objectMapper)) {
        assertBusinessCode(
            () -> replayDatabase.resolveResetCommand(command),
            ValidationResetCommand.IN_PROGRESS_ERROR);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM validation_control.reset_receipts WHERE operation_id = ?",
            String.class,
            command.operationId())).isEqualTo("IN_PROGRESS");
      }
    }
  }

  @Test
  void abandonedInProgressResetCanBeReclaimedOnlyAsItsExactDurableOperation() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      migrateResetControl(jdbc);
      ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
      UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000771");
      ValidationResetCommand command = command(
          runId,
          "00000000-0000-0000-0000-000000000772",
          ValidationResetCommand.Mode.FINAL,
          1L);
      prepareReadyGeneration(jdbc, 1L);
      insertRuntimeRun(jdbc, runId, 1L, "COMPLETED");
      String databaseName = jdbc.queryForObject("SELECT current_database()", String.class);
      long interruptedEpoch;

      try (ValidationAdministrativeDatabase interrupted =
          new ValidationAdministrativeDatabase(jdbc.getDataSource(), objectMapper)) {
        assertThat(interrupted.resolveResetCommand(command).isReplay()).isFalse();
        interrupted.markResetting(command, databaseName, START);
        interruptedEpoch = interrupted.currentResetPlan().epoch();

        try (ValidationAdministrativeDatabase liveContender =
            new ValidationAdministrativeDatabase(jdbc.getDataSource(), objectMapper)) {
          assertThat(liveContender.interruptedResetCommand()).contains(command);
          assertThatThrownBy(() ->
              liveContender.recoverResetting(command, databaseName, START.plusSeconds(1)))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("ownership");
        }
      }

      long versionBeforeMismatch = jdbc.queryForObject(
          "SELECT version FROM validation_control.reset_state WHERE singleton_key = 1",
          Long.class);
      ValidationResetCommand mismatched = command(
          runId,
          "00000000-0000-0000-0000-000000000773",
          ValidationResetCommand.Mode.FINAL,
          1L);
      try (ValidationAdministrativeDatabase invalidRecovery =
          new ValidationAdministrativeDatabase(jdbc.getDataSource(), objectMapper)) {
        assertThatThrownBy(() ->
            invalidRecovery.recoverResetting(mismatched, databaseName, START.plusSeconds(1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("identity");
      }
      assertThat(jdbc.queryForObject(
          "SELECT version FROM validation_control.reset_state WHERE singleton_key = 1",
          Long.class)).isEqualTo(versionBeforeMismatch);
      assertThat(jdbc.queryForObject(
          "SELECT status FROM validation_control.reset_receipts WHERE operation_id = ?",
          String.class,
          command.operationId())).isEqualTo("IN_PROGRESS");

      try (ValidationAdministrativeDatabase recovered =
          new ValidationAdministrativeDatabase(jdbc.getDataSource(), objectMapper)) {
        assertBusinessCode(
            () -> recovered.resolveResetCommand(command),
            ValidationResetCommand.IN_PROGRESS_ERROR);
        assertThat(recovered.interruptedResetCommand()).contains(command);

        recovered.recoverResetting(command, databaseName, START.plusSeconds(1));

        assertThat(recovered.currentResetPlan().targetGeneration()).isEqualTo(2L);
        assertThat(recovered.currentResetPlan().epoch()).isEqualTo(interruptedEpoch);
        cleanResetSchemasAndReplayControlMigrations(jdbc);
        ValidationResetReceipt receipt = successfulReceipt(
            databaseName,
            2L,
            START.plusSeconds(1));
        recovered.persistResetReceipt(command, receipt);
        assertThat(recovered.resolveResetCommand(command).replayReceipt()).isEqualTo(receipt);
      }

      assertThat(jdbc.queryForObject(
          "SELECT COUNT(*) FROM validation_control.reset_receipts WHERE operation_id = ?",
          Integer.class,
          command.operationId())).isEqualTo(1);
      assertThat(jdbc.queryForMap(
          """
              SELECT state, generation, reset_id
                FROM validation_control.reset_state
               WHERE singleton_key = 1
              """))
          .containsEntry("state", "READY")
          .containsEntry("generation", 2L)
          .containsEntry("reset_id", command.operationId());
    }
  }

  private static ValidationResetCommand command(
      UUID runId,
      String operationId,
      ValidationResetCommand.Mode mode,
      long expectedGeneration
  ) {
    return new ValidationResetCommand(
        runId,
        UUID.fromString(operationId),
        mode,
        expectedGeneration);
  }

  private static void completeReset(
      ValidationAdministrativeDatabase database,
      ValidationResetCommand command,
      String databaseName,
      Instant startedAt
  ) {
    assertThat(database.resolveResetCommand(command).isReplay()).isFalse();
    database.markResetting(command, databaseName, startedAt);
    long targetGeneration = database.currentResetPlan().targetGeneration();
    assertThat(targetGeneration).isEqualTo(command.expectedGeneration() + 1L);
    database.persistResetReceipt(
        command,
        successfulReceipt(databaseName, targetGeneration, startedAt));
    assertThat(database.resolveResetCommand(command).replayReceipt())
        .isEqualTo(successfulReceipt(databaseName, targetGeneration, startedAt));
  }

  private static void cleanResetSchemasAndReplayControlMigrations(JdbcTemplate jdbc) {
    Flyway.configure()
        .dataSource(jdbc.getDataSource())
        .locations("classpath:db/migration")
        .schemas(ValidationResetOperations.RESET_SCHEMAS.toArray(String[]::new))
        .defaultSchema("public")
        .cleanDisabled(false)
        .load()
        .clean();
    migrateResetControl(jdbc);
  }

  private static void migrateResetControl(JdbcTemplate jdbc) {
    for (String schema : ValidationResetOperations.RESET_SCHEMAS) {
      jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
    }
    executeMigration(jdbc, "V66__validation_runtime.sql");
    executeMigration(jdbc, "V67__validation_reset_receipts.sql");
  }

  private static void executeMigration(JdbcTemplate jdbc, String migration) {
    try (Connection connection = jdbc.getDataSource().getConnection()) {
      ScriptUtils.executeSqlScript(
          connection,
          new FileSystemResource("src/main/resources/db/migration/" + migration));
    } catch (Exception failure) {
      throw new IllegalStateException("Reset control migration failed", failure);
    }
  }

  private static void prepareReadyGeneration(JdbcTemplate jdbc, long generation) {
    jdbc.update(
        """
            UPDATE validation_control.reset_state
               SET generation = ?,
                   state = 'READY',
                   redis_generation = ?,
                   memory_generation = ?,
                   version = version + 1
             WHERE singleton_key = 1
            """,
        generation,
        generation,
        generation);
  }

  private static void insertRuntimeRun(
      JdbcTemplate jdbc,
      UUID runId,
      long generation,
      String state
  ) {
    jdbc.update(
        """
            INSERT INTO validation_runtime.run_executions (
              id, generation, request_fingerprint, request_json, state
            )
            VALUES (?, ?, ?, CAST(? AS jsonb), ?)
            """,
        runId,
        generation,
        "reset-identity-postgres-test",
        "{}",
        state);
  }

  private static ValidationResetReceipt successfulReceipt(
      String databaseName,
      long generation
  ) {
    return successfulReceipt(databaseName, generation, START);
  }

  private static ValidationResetReceipt successfulReceipt(
      String databaseName,
      long generation,
      Instant startedAt
  ) {
    return new ValidationResetReceipt(
        ValidationResetReceipt.Status.SUCCEEDED,
        databaseName,
        generation,
        generation,
        startedAt,
        startedAt.plusSeconds(1),
        List.of(),
        null);
  }

  private static void assertBusinessCode(Runnable action, String expectedCode) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).getCode())
        .isEqualTo(expectedCode);
  }
}
