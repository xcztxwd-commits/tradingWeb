package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V50CrossLiquidationSettlementMigrationTest {

  @Test
  void migrationPersistsOneSettlementChargePerLiquidationOrder() throws Exception {
    String sql;
    try (var stream = getClass().getResourceAsStream(
        "/db/migration/V50__cross_liquidation_settlement.sql")) {
      assertThat(stream).isNotNull();
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          .replaceAll("\\s+", " ")
          .toUpperCase();
    }

    assertThat(sql)
        .contains("CREATE TABLE IF NOT EXISTS TRADING.CROSS_LIQUIDATION_CHARGES")
        .contains("ORDER_ID UUID PRIMARY KEY REFERENCES TRADING.ORDERS(ID)")
        .contains("ACCOUNT_ID UUID NOT NULL REFERENCES CORE.TRADING_ACCOUNTS(ID)")
        .contains("POSITION_ID UUID NOT NULL REFERENCES TRADING.POSITIONS(ID)")
        .contains("FEE_DUE NUMERIC(38, 8) NOT NULL DEFAULT 0 CHECK (FEE_DUE >= 0)")
        .contains("FEE_CHARGED NUMERIC(38, 8) NOT NULL DEFAULT 0 CHECK (FEE_CHARGED >= 0)")
        .contains("STATUS VARCHAR(20) NOT NULL DEFAULT 'PENDING'")
        .contains("STATUS IN ('PENDING', 'SETTLED')")
        .contains("SETTLED_AT TIMESTAMPTZ")
        .contains("IDX_CROSS_LIQUIDATION_CHARGES_ACCOUNT_STATUS")
        .contains("(ACCOUNT_ID, STATUS, ORDER_ID)");
  }
}
