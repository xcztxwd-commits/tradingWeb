package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V58TrailingStopOrderTypeWidthMigrationTest {

  @Test
  void migrationWidensOrderTypeWithoutDroppingConstraintsOrData() throws Exception {
    String sql = Files.readString(Path.of(
        "src/main/resources/db/migration/V58__trailing_stop_order_type_width.sql"));
    String normalized = sql.replaceAll("\\s+", " ").toUpperCase();

    assertThat(normalized)
        .contains("ALTER TABLE TRADING.ORDERS ALTER COLUMN ORDER_TYPE TYPE VARCHAR(32)")
        .doesNotContain(
            "DROP CONSTRAINT",
            "DROP TABLE",
            "DELETE FROM",
            "TRUNCATE");
  }
}
