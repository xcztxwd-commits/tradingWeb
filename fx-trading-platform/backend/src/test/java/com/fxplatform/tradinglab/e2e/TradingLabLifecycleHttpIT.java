package com.fxplatform.tradinglab.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradingLabLifecycleHttpIT extends TradingLabHttpIntegrationSupport {

  @Test
  void pauseFreezesProgressResumeAdvancesAndCancelRetainsPartialReport() {
    var fixture = TradingLabHttpScenarioFactory.lifecycle(currentConfig());
    JsonNode scenario = createScenario(fixture.scenarioRequest());
    UUID scenarioId = UUID.fromString(scenario.path("id").asText());
    long scenarioVersion = scenario.path("version").asLong();
    RunView accepted = startRun(scenarioId, fixture.runRequest(scenarioVersion));
    UUID runId = accepted.id();

    assertThat(accepted.totalTicks()).isEqualTo(fixture.totalTicks());
    try {
      RunView progressing = awaitRun(
          runId,
          candidate ->
              "RUNNING".equals(candidate.state()) && candidate.processedTicks() > 0L,
          Duration.ofMinutes(4),
          "observable processed Tick growth through the main Admin run endpoint");
      assertThat(progressing.processedTicks()).isLessThan(progressing.totalTicks());

      pauseRun(runId);
      RunView paused = awaitRun(
          runId,
          candidate ->
              "PAUSED".equals(candidate.state()) && candidate.pauseRequested(),
          Duration.ofSeconds(20),
          "PAUSED with the pause flag retained");
      long pauseBoundary = paused.processedTicks();
      assertThat(pauseBoundary).isPositive();
      assertThat(pauseBoundary).isLessThan(paused.totalTicks());

      long stableDeadline = System.nanoTime() + Duration.ofMillis(1200).toNanos();
      while (System.nanoTime() < stableDeadline) {
        RunView stable = run(runId);
        assertThat(stable.state()).isEqualTo("PAUSED");
        assertThat(stable.pauseRequested()).isTrue();
        assertThat(stable.processedTicks())
            .as("processed Tick count while paused")
            .isEqualTo(pauseBoundary);
        pause(Duration.ofMillis(100));
      }

      resumeRun(runId);
      RunView resumed = awaitRun(
          runId,
          candidate ->
              "RUNNING".equals(candidate.state())
                  && !candidate.pauseRequested()
                  && candidate.processedTicks() > pauseBoundary,
          Duration.ofSeconds(20),
          "resumed processed Tick growth");
      assertThat(resumed.processedTicks()).isGreaterThan(pauseBoundary);
      assertThat(resumed.processedTicks()).isLessThan(resumed.totalTicks());

      cancelRun(runId);
      RunView cancelled = awaitTerminal(runId);
      assertThat(cancelled.state()).isEqualTo("CANCELLED");
      assertThat(cancelled.cancelRequested()).isTrue();
      assertThat(cancelled.totalTicks()).isEqualTo(fixture.totalTicks());
      assertThat(cancelled.processedTicks()).isPositive();
      assertThat(cancelled.processedTicks()).isLessThan(cancelled.totalTicks());

      RawReport report = downloadAndValidate(runId);
      JsonNode root = report.root();
      assertThat(root.path("actualState").path("terminalState").asText())
          .isEqualTo("CANCELLED");
      assertThat(root.path("marketTicks").size())
          .isEqualTo(Math.toIntExact(cancelled.processedTicks()));
      assertThat(root.path("cleanup").path("status").asText()).isEqualTo("SUCCEEDED");
    } finally {
      cancelAndRetainIfActive(runId);
    }
  }
}
