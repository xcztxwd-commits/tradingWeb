package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V51IdempotentRequestAuditMigrationTest {

  @Test
  void migrationDeduplicatesAndUniquelyIndexesAuditedRequestTargets() throws Exception {
    String sql;
    try (var stream = getClass().getResourceAsStream(
        "/db/migration/V51__idempotent_request_audit.sql")) {
      assertThat(stream).isNotNull();
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          .replaceAll("\\s+", " ")
          .toUpperCase();
    }

    assertThat(sql)
        .contains("PARTITION BY ACTION, TARGET_TYPE, TARGET_ID, REQUEST_ID")
        .contains("DELETE FROM AUDIT.AUDIT_LOGS")
        .contains("DUPLICATE_RANK > 1")
        .contains("CREATE UNIQUE INDEX IF NOT EXISTS UQ_AUDIT_LOGS_REQUEST_ACTION_TARGET")
        .contains("ON AUDIT.AUDIT_LOGS(ACTION, TARGET_TYPE, TARGET_ID, REQUEST_ID)")
        .contains("WHERE REQUEST_ID IS NOT NULL");
  }
}
