package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class V69TradingLabStringSeedMigrationIT {

  @Test
  void migratesExistingBigintSeedsAndPreservesNewStringIdentity() {
    try (PostgreSQLContainer<?> postgres =
        PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, "68");
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      UUID actorId = UUID.randomUUID();
      jdbc.update("""
          insert into auth.users (id, email, password_hash, status, role)
          values (?, ?, 'hash', 'ACTIVE', 'ADMIN')
          """, actorId, "seed-migration-" + actorId + "@trading-lab.test");
      UUID numericId = insertScenario(jdbc, actorId, 7L, "{\"seed\":7}");
      UUID nullId = insertScenario(jdbc, actorId, null, "{}");
      UUID textualId = insertScenario(jdbc, actorId, 1L, "{\"seed\":\"001\"}");
      UUID numericRunId = insertRun(
          jdbc, actorId, numericId, "COMPLETED", "{\"seed\":7}");
      UUID nullRunId = insertRun(
          jdbc, actorId, nullId, "COMPLETED", "{}");
      UUID textualRunId = insertRun(
          jdbc, actorId, textualId, "RUNNING", "{\"seed\":\"001\"}");
      UUID validationRunId = insertValidationRun(
          jdbc, "RUNNING", "{\"seed\":\"001\",\"scenarioId\":\"scenario-1\"}");
      String validationRequestBefore = jdbc.queryForObject("""
          select request_json::text
          from validation_runtime.run_executions
          where id = ?
          """, String.class, validationRunId);

      PostgresMigrationTestSupport.migrate(postgres, "69");

      assertThat(jdbc.queryForObject("""
          select seed from trading_lab.scenarios where id = ?
          """, String.class, numericId)).isEqualTo("7");
      assertThat(jdbc.queryForObject("""
          select seed from trading_lab.scenarios where id = ?
          """, String.class, nullId)).isEqualTo("0");
      assertThat(jdbc.queryForObject("""
          select scenario_json ->> 'seed'
          from trading_lab.scenarios where id = ?
          """, String.class, numericId)).isEqualTo("7");
      assertThat(jdbc.queryForObject("""
          select jsonb_typeof(scenario_json -> 'seed')
          from trading_lab.scenarios where id = ?
          """, String.class, numericId)).isEqualTo("string");
      assertThat(jdbc.queryForObject("""
          select scenario_json ->> 'seed'
          from trading_lab.scenarios where id = ?
          """, String.class, nullId)).isEqualTo("0");
      assertThat(jdbc.queryForObject("""
          select seed
          from trading_lab.scenarios where id = ?
          """, String.class, textualId)).isEqualTo("001");
      assertThat(jdbc.queryForObject("""
          select scenario_json ->> 'seed'
          from trading_lab.scenarios where id = ?
          """, String.class, textualId)).isEqualTo("001");
      assertThat(jdbc.queryForObject("""
          select jsonb_typeof(scenario_snapshot_json -> 'seed')
          from trading_lab.runs where id = ?
          """, String.class, numericRunId)).isEqualTo("number");
      assertThat(jdbc.queryForObject("""
          select jsonb_exists(scenario_snapshot_json, 'seed')
          from trading_lab.runs where id = ?
          """, Boolean.class, nullRunId)).isFalse();
      assertThat(jdbc.queryForObject("""
          select scenario_snapshot_json::text
          from trading_lab.runs where id = ?
          """, String.class, textualRunId)).isEqualTo("{\"seed\": \"001\"}");
      assertThat(jdbc.queryForObject("""
          select request_json::text
          from validation_runtime.run_executions
          where id = ?
          """, String.class, validationRunId)).isEqualTo(validationRequestBefore);
      assertThat(jdbc.queryForObject("""
          select character_maximum_length
          from information_schema.columns
          where table_schema = 'trading_lab'
            and table_name = 'scenarios'
            and column_name = 'seed'
          """, Integer.class)).isEqualTo(256);
      assertThat(jdbc.queryForObject("""
          select is_nullable
          from information_schema.columns
          where table_schema = 'trading_lab'
            and table_name = 'scenarios'
            and column_name = 'seed'
          """, String.class)).isEqualTo("NO");

      UUID stringId = insertScenario(jdbc, actorId, "001", "{\"seed\":\"001\"}");
      assertThat(jdbc.queryForObject("""
          select seed from trading_lab.scenarios where id = ?
          """, String.class, stringId)).isEqualTo("001");
      assertThatThrownBy(() -> insertScenario(jdbc, actorId, "   ", "{\"seed\":\"   \"}"))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(() -> insertScenario(jdbc, actorId, "001", "{\"seed\":\"1\"}"))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(() -> insertScenario(jdbc, actorId, null, "{\"seed\":\"0\"}"))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(() -> jdbc.update("""
          update validation_runtime.run_executions
          set request_json = '{}'::jsonb
          where id = ?
          """, validationRunId))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("ck_validation_runtime_run_string_seed");
    }
  }

  @Test
  void refusesToRewriteNonTerminalLegacyRunInputs() {
    try (PostgreSQLContainer<?> postgres =
        PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, "68");
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      UUID actorId = UUID.randomUUID();
      jdbc.update("""
          insert into auth.users (id, email, password_hash, status, role)
          values (?, ?, 'hash', 'ACTIVE', 'ADMIN')
          """, actorId, "seed-active-" + actorId + "@trading-lab.test");
      UUID scenarioId = insertScenario(jdbc, actorId, 7L, "{\"seed\":7}");
      insertRun(jdbc, actorId, scenarioId, "RUNNING", "{\"seed\":7}");

      assertThatThrownBy(() -> PostgresMigrationTestSupport.migrate(postgres, "69"))
          .isInstanceOf(Exception.class)
          .hasMessageContaining("non-terminal");
    }
  }

  @Test
  void refusesNonTerminalValidationRuntimeRequestsWithoutExactStringSeed() {
    try (PostgreSQLContainer<?> postgres =
        PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, "68");
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      UUID validationRunId = insertValidationRun(
          jdbc, "RECOVERY_BLOCKED", "{\"seed\":7,\"scenarioId\":\"scenario-1\"}");
      String requestBefore = jdbc.queryForObject("""
          select request_json::text
          from validation_runtime.run_executions
          where id = ?
          """, String.class, validationRunId);

      assertThatThrownBy(() -> PostgresMigrationTestSupport.migrate(postgres, "69"))
          .isInstanceOf(Exception.class)
          .hasMessageContaining("validation runtime");

      assertThat(jdbc.queryForObject("""
          select request_json::text
          from validation_runtime.run_executions
          where id = ?
          """, String.class, validationRunId)).isEqualTo(requestBefore);
      assertThat(jdbc.queryForObject("""
          select data_type
          from information_schema.columns
          where table_schema = 'trading_lab'
            and table_name = 'scenarios'
            and column_name = 'seed'
          """, String.class)).isEqualTo("bigint");
    }
  }

  @Test
  void preservesTerminalLegacyValidationRuntimeRequests() {
    try (PostgreSQLContainer<?> postgres =
        PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, "68");
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      UUID validationRunId = insertValidationRun(
          jdbc, "COMPLETED", "{\"scenarioId\":\"scenario-1\"}");
      String requestBefore = jdbc.queryForObject("""
          select request_json::text
          from validation_runtime.run_executions
          where id = ?
          """, String.class, validationRunId);

      PostgresMigrationTestSupport.migrate(postgres, "69");

      assertThat(jdbc.queryForObject("""
          select request_json::text
          from validation_runtime.run_executions
          where id = ?
          """, String.class, validationRunId)).isEqualTo(requestBefore);
      assertThatThrownBy(() -> jdbc.update("""
          update validation_runtime.run_executions
          set state = 'RUNNING'
          where id = ?
          """, validationRunId))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("ck_validation_runtime_run_string_seed");
    }
  }

  private static UUID insertScenario(
      JdbcTemplate jdbc,
      UUID actorId,
      Object seed,
      String scenarioJson
  ) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into trading_lab.scenarios (
          id, name, status, negative_mode, seed, model_version,
          scenario_json, config_snapshot_json, config_snapshot_hash,
          symbol_config_version, code_version, created_by, updated_by, version
        ) values (
          ?, 'Seed migration', 'DRAFT', false, ?, 'model-v1',
          ?::jsonb, '{}'::jsonb, ?,
          'symbols-v1', 'code-v1', ?, ?, 0
        )
        """, id, seed, scenarioJson, "a".repeat(64), actorId, actorId);
    return id;
  }

  private static UUID insertRun(
      JdbcTemplate jdbc,
      UUID actorId,
      UUID scenarioId,
      String state,
      String scenarioSnapshotJson
  ) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into trading_lab.runs (
          id, scenario_id, state, scenario_snapshot_json, config_snapshot_json,
          config_snapshot_hash, model_version, symbol_config_version, code_version,
          created_by, version
        ) values (
          ?, ?, ?, ?::jsonb, '{}'::jsonb,
          ?, 'model-v1', 'symbols-v1', 'code-v1',
          ?, 0
        )
        """, id, scenarioId, state, scenarioSnapshotJson, "a".repeat(64), actorId);
    return id;
  }

  private static UUID insertValidationRun(
      JdbcTemplate jdbc,
      String state,
      String requestJson
  ) {
    long generation = 1L;
    jdbc.update("""
        update validation_control.reset_state
        set generation = ?, state = 'READY'
        where singleton_key = 1
        """, generation);
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into validation_runtime.run_executions (
          id, generation, request_fingerprint, request_json, state
        ) values (?, ?, 'seed-migration-runtime', ?::jsonb, ?)
        """, id, generation, requestJson, state);
    return id;
  }
}
