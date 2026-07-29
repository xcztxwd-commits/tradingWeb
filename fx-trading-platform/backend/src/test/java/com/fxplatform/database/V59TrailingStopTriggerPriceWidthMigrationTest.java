package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V59TrailingStopTriggerPriceWidthMigrationTest {

  @Test
  void migrationWidensTriggerPriceWithoutDroppingConstraintsOrData() throws Exception {
    String sql = Files.readString(Path.of(
        "src/main/resources/db/migration/V59__trailing_stop_trigger_price_width.sql"));
    String normalized = sql.replaceAll("\\s+", " ").toUpperCase();

    assertThat(normalized)
        .contains("ALTER TABLE TRADING.ORDERS ALTER COLUMN TRIGGER_PRICE TYPE NUMERIC(31, 10)")
        .contains(
            "ADD CONSTRAINT CK_ORDERS_STATIC_TRIGGER_PRICE_NUMERIC24",
            "ORDER_TYPE = 'TRAILING_STOP_MARKET'",
            "PRODUCT_TYPE = 'LINEAR_PERP'",
            "ORDER_ORIGIN = 'PROTECTIVE'",
            "PROTECTION_TYPE = 'STOP_LOSS'",
            "TRAILING_DELTA IS NOT NULL",
            "TRAILING_RATE IS NOT NULL")
        .doesNotContain(
            "DROP CONSTRAINT",
            "DROP TABLE",
            "DELETE FROM",
            "TRUNCATE");
  }
}
