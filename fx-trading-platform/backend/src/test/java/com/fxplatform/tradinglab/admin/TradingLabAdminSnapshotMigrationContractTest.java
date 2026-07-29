package com.fxplatform.tradinglab.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TradingLabAdminSnapshotMigrationContractTest {

  @Test
  void v68AddsDurableLocalCalculationAndScenarioStatusCheck() throws Exception {
    String migration = Files.readString(Path.of(
        "src/main/resources/db/migration/V68__trading_lab_admin_snapshots.sql"));

    assertThat(migration)
        .contains("local_calculation_json JSONB NOT NULL DEFAULT '{}'::jsonb")
        .contains("status IN ('DRAFT', 'FROZEN')");
  }
}
