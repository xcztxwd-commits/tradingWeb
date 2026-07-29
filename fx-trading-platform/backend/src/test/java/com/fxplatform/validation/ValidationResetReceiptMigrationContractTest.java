package com.fxplatform.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ValidationResetReceiptMigrationContractTest {

  private static final Path MIGRATION =
      Path.of("src/main/resources/db/migration/V67__validation_reset_receipts.sql");

  @Test
  void resetReceiptsSurviveBusinessSchemaCleanAndAreReplayable() throws Exception {
    String sql = Files.readString(MIGRATION);

    assertThat(sql)
        .contains("CREATE TABLE IF NOT EXISTS validation_control.reset_receipts")
        .contains("operation_id UUID")
        .contains("run_id UUID")
        .contains("mode VARCHAR(16)")
        .contains("expected_generation BIGINT")
        .contains("request_fingerprint CHAR(64)")
        .contains("status VARCHAR(16)")
        .contains("receipt_json JSONB")
        .contains("CREATE UNIQUE INDEX IF NOT EXISTS")
        .contains("(run_id, mode)")
        .contains("'IN_PROGRESS', 'SUCCEEDED', 'FAILED'")
        .doesNotContain("REFERENCES validation_runtime");
  }

  @Test
  void migrationCanReplayWithoutReplacingDurableReceipts() throws Exception {
    String sql = Files.readString(MIGRATION);

    assertThat(sql)
        .contains("CREATE TABLE IF NOT EXISTS")
        .contains("CREATE INDEX IF NOT EXISTS")
        .contains("CREATE UNIQUE INDEX IF NOT EXISTS")
        .doesNotContain("DROP TABLE", "TRUNCATE", "DELETE FROM");
  }
}
