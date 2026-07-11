package com.fxplatform.market.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class DataProviderRepositorySqlContractTest {

  @Test
  void quoteSuccessUpdatesOnlyHealthTelemetryColumns() throws Exception {
    String sql = updateSql("updateQuoteSuccessHealth");

    assertThat(sql)
        .contains("health_status = 'up'", "last_health_check_at", "last_success_at",
            "last_quote_success_at", "avg_latency_ms", "quote_staleness_ms", "where id =")
        .doesNotContain("enabled =", "priority =", "rest_base_url =", "config_json =");
  }

  @Test
  void failureUpdateIncrementsFailureCountAtomicallyWithoutWritingProviderConfiguration() throws Exception {
    String sql = updateSql("updateFailureHealth");

    assertThat(sql)
        .contains("health_status = 'down'", "last_health_check_at", "last_failure_at",
            "failure_count = coalesce(failure_count, 0) + 1", "avg_latency_ms", "where id =")
        .doesNotContain("enabled =", "priority =", "rest_base_url =", "config_json =");
  }

  @Test
  void instrumentSyncSuccessUpdatesOnlyHealthTelemetryColumns() throws Exception {
    String sql = updateSql("updateInstrumentSyncSuccessHealth");

    assertThat(sql)
        .contains("health_status = 'up'", "last_health_check_at", "last_success_at",
            "last_instrument_sync_at", "last_instrument_sync_count", "where id =")
        .doesNotContain("enabled =", "priority =", "rest_base_url =", "config_json =");
  }

  private String updateSql(String methodName) throws Exception {
    Method method = java.util.Arrays.stream(DataProviderRepository.class.getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals(methodName))
        .findFirst()
        .orElseThrow();
    Update update = method.getAnnotation(Update.class);
    assertThat(update).as(methodName + " must use directed SQL").isNotNull();
    return String.join(" ", update.value())
        .replaceAll("\\s+", " ")
        .trim()
        .toLowerCase(java.util.Locale.ROOT);
  }
}
