package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V48LiquidationPendingMigrationTest {

  @Test
  void activeDemoUniquenessAlsoCoversLiquidationPendingAccounts() throws Exception {
    String sql;
    try (var stream = getClass().getResourceAsStream(
        "/db/migration/V48__liquidation_pending_demo_uniqueness.sql")) {
      assertThat(stream).isNotNull();
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          .replaceAll("\\s+", " ")
          .toUpperCase();
    }

    assertThat(sql)
        .contains("DROP INDEX IF EXISTS CORE.UX_TRADING_ACCOUNTS_USER_ACTIVE_DEMO")
        .contains("CREATE UNIQUE INDEX UX_TRADING_ACCOUNTS_USER_ACTIVE_DEMO")
        .contains("'ACTIVE', 'RISK_REDUCTION_PENDING',")
        .contains("'ISOLATED_LIQUIDATION_PENDING', 'LIQUIDATION_PENDING'");
  }
}
