package com.fxplatform.tradinglab.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizerTestFactory;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationReportTraceAdapterTest {

  @Test
  void reportDurationUsesTheSchemaIntegerMillisecondsContract() {
    ValidationHttpResult result = new ValidationHttpResult(
        7L,
        "validation",
        "POST",
        URI.create("http://127.0.0.1:18087/internal/validation/runs"),
        Instant.parse("2026-07-26T00:00:00Z"),
        Instant.parse("2026-07-26T00:00:01Z"),
        200,
        Duration.ofMillis(12L),
        Map.of("runId", "run-1"),
        Map.of("ok", true),
        "trace-1",
        "correlation-1",
        null);

    SafeTradingLabHttpTrace trace = ValidationReportTraceAdapter.rebuild(
        result,
        TradingLabHttpTraceSanitizerTestFactory.create(List.of(), 1_048_576));

    assertThat(trace.toSafeMap().get("responseBody"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("duration", 12L);
  }
}
