package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V49BatchActionRequestMigrationTest {

  @Test
  void migrationPersistsFrozenScopeFingerprintStatusAndFinalResponse() throws Exception {
    String sql;
    try (var stream = getClass().getResourceAsStream(
        "/db/migration/V49__batch_action_request_replay.sql")) {
      assertThat(stream).isNotNull();
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          .replaceAll("\\s+", " ")
          .toUpperCase();
    }

    assertThat(sql)
        .contains("CREATE TABLE TRADING.BATCH_ACTION_REQUESTS")
        .contains("REQUEST_FINGERPRINT")
        .contains("OWNER_TOKEN")
        .contains("LEASE_UNTIL")
        .contains("SCOPE_IDS")
        .contains("RESPONSE_PAYLOAD")
        .contains("PROCESSING", "COMPLETED")
        .contains("UNIQUE (ACCOUNT_ID, ACTION_TYPE, REQUEST_ID)");
  }
}
