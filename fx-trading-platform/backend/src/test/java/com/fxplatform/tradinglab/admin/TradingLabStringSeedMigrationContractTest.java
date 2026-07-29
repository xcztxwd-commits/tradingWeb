package com.fxplatform.tradinglab.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TradingLabStringSeedMigrationContractTest {

  @Test
  void v69EstablishesExactStringSeedIdentityForScenariosAndFrozenInputs() throws Exception {
    String migration = Files.readString(Path.of(
        "src/main/resources/db/migration/V69__trading_lab_string_seed.sql"));

    assertThat(migration)
        .contains("""
            LOCK TABLE trading_lab.scenarios, trading_lab.runs,
              validation_runtime.run_executions
            """)
        .contains("IN ACCESS EXCLUSIVE MODE")
        .contains("ALTER COLUMN seed TYPE VARCHAR(256)")
        .contains("USING seed::text")
        .contains("UPDATE trading_lab.scenarios")
        .contains("jsonb_set")
        .contains("ALTER COLUMN seed SET NOT NULL")
        .contains("char_length(seed) BETWEEN 1 AND 256")
        .contains("btrim(seed) <> ''")
        .contains("scenario_json ? 'seed'")
        .contains("jsonb_typeof(scenario_json -> 'seed') = 'string'")
        .contains("scenario_json ->> 'seed' = seed")
        .contains(") IS TRUE")
        .contains("state NOT IN ('CANCELLED', 'FAILED', 'COMPLETED')")
        .contains("FROM validation_runtime.run_executions")
        .contains("request_json ? 'seed'")
        .contains("jsonb_typeof(request_json -> 'seed') = 'string'")
        .contains("char_length(request_json ->> 'seed') BETWEEN 1 AND 256")
        .contains("btrim(request_json ->> 'seed') <> ''")
        .contains("ADD CONSTRAINT ck_validation_runtime_run_string_seed")
        .contains("state IN ('CANCELLED', 'FAILED', 'COMPLETED')")
        .contains(") IS NOT TRUE")
        .doesNotContain(
            "UPDATE trading_lab.runs",
            "UPDATE validation_runtime.run_executions",
            "V60__");
  }
}
