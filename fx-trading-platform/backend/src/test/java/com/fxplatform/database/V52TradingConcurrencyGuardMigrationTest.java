package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V52TradingConcurrencyGuardMigrationTest {

  @Test
  void migrationBackfillsOnlyExistingDemoP0TradesBeforeInstallingTheFinalGuard()
      throws Exception {
    String sql;
    try (var stream = getClass().getResourceAsStream(
        "/db/migration/V52__trading_concurrency_guards.sql")) {
      assertThat(stream).isNotNull();
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          .replaceAll("\\s+", " ")
          .toUpperCase();
    }

    assertThat(sql)
        .contains("ADD COLUMN IF NOT EXISTS CANONICAL_FULL_FILL BOOLEAN NOT NULL DEFAULT FALSE")
        .contains("CHECK (NOT CANONICAL_FULL_FILL OR PRODUCT_TYPE IN ('CRYPTO_SPOT', 'LINEAR_PERP'))")
        .contains("UPDATE TRADING.TRADES AS TRADE_ROW SET CANONICAL_FULL_FILL = TRUE")
        .contains("FROM CORE.TRADING_ACCOUNTS AS ACCOUNT_ROW")
        .contains("ACCOUNT_ROW.ID = TRADE_ROW.ACCOUNT_ID")
        .contains("ACCOUNT_ROW.ACCOUNT_TYPE = 'DEMO'")
        .contains("TRADE_ROW.PRODUCT_TYPE IN ('CRYPTO_SPOT', 'LINEAR_PERP')")
        .contains("TRADE_ROW.CANONICAL_FULL_FILL = FALSE")
        .contains("CREATE UNIQUE INDEX IF NOT EXISTS UX_TRADES_P0_ORDER_FULL_FILL")
        .contains("ON TRADING.TRADES(ORDER_ID)")
        .contains("WHERE CANONICAL_FULL_FILL = TRUE");

    assertThat(sql.indexOf("UPDATE TRADING.TRADES AS TRADE_ROW"))
        .as("all matching historical DEMO trades must be marked before uniqueness is enforced")
        .isLessThan(sql.indexOf("CREATE UNIQUE INDEX IF NOT EXISTS UX_TRADES_P0_ORDER_FULL_FILL"));
    assertThat(sql)
        .as("existing duplicate DEMO full fills must make index creation fail, not be hidden")
        .doesNotContain("DELETE FROM TRADING.TRADES", "ROW_NUMBER()", "ON CONFLICT");
  }
}
