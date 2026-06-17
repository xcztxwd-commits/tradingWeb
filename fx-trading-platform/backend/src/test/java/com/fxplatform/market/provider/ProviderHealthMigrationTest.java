package com.fxplatform.market.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProviderHealthMigrationTest {

  @Test
  void providerHealthMigrationAddsTelemetryColumns() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V44__provider_health_metrics.sql"));

    assertThat(sql).contains("last_success_at");
    assertThat(sql).contains("last_failure_at");
    assertThat(sql).contains("failure_count");
    assertThat(sql).contains("avg_latency_ms");
    assertThat(sql).contains("last_quote_success_at");
    assertThat(sql).contains("quote_staleness_ms");
    assertThat(sql).contains("last_instrument_sync_at");
    assertThat(sql).contains("last_instrument_sync_count");
  }
}
