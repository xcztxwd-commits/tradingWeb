package com.fxplatform.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ValidationRuntimeMigrationContractTest {

  private static final Path MIGRATION =
      Path.of("src/main/resources/db/migration/V66__validation_runtime.sql");

  @Test
  void persistentGenerationFenceIsSeparateFromTheCleanableRuntime() throws Exception {
    String sql = Files.readString(MIGRATION);

    assertThat(sql)
        .contains("CREATE SCHEMA IF NOT EXISTS validation_control")
        .contains("CREATE TABLE IF NOT EXISTS validation_control.reset_state")
        .contains("UNIQUE (singleton_key, generation)")
        .contains("CREATE SCHEMA IF NOT EXISTS validation_runtime")
        .contains("FOREIGN KEY (reset_key, generation)")
        .contains("REFERENCES validation_control.reset_state(singleton_key, generation)");
  }

  @Test
  void runtimeTablesCarryDurableOrderingAndIdempotencyConstraints() throws Exception {
    String sql = Files.readString(MIGRATION);

    assertThat(sql)
        .contains("validation_runtime.run_executions")
        .contains("validation_runtime.run_events")
        .contains("validation_runtime.operations")
        .contains("validation_runtime.seed_receipts")
        .contains("validation_runtime.system_step_receipts")
        .contains("UNIQUE (run_id, sequence)")
        .contains("UNIQUE (run_id, durable_event_key)")
        .contains("UNIQUE (run_id, operation_sequence)")
        .contains("UNIQUE (run_id, idempotency_key)")
        .contains("UNIQUE (account_id, generation)")
        .contains("UNIQUE (run_id, tick_sequence, phase)")
        .contains("'ACCEPTED', 'STARTING', 'RUNNING', 'PAUSED', 'RECOVERING',")
        .contains("'RECOVERY_BLOCKED', 'CANCELLING'");
  }

  @Test
  void validationRuntimeDoesNotAcquireProductIdentityForeignKeys() throws Exception {
    String sql = Files.readString(MIGRATION);

    assertThat(sql)
        .doesNotContain("REFERENCES auth.")
        .doesNotContain("REFERENCES core.")
        .doesNotContain("REFERENCES trading_lab.");
  }
}
