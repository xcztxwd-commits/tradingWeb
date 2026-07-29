package com.fxplatform.trading.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

final class ScenarioTestArtifacts {

  private ScenarioTestArtifacts() {
  }

  static void write(
      ObjectMapper objectMapper,
      ScenarioDefinition scenario,
      List<ExpectedScenarioResult> expectations,
      ActualScenarioResult actual
  ) {
    Objects.requireNonNull(objectMapper, "objectMapper");
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(expectations, "expectations");
    if (expectations.isEmpty()) {
      throw new IllegalArgumentException("expectations must not be empty");
    }
    Path directory = Path.of(
        "target",
        "scenario-artifacts",
        scenario.caseId());
    try {
      Files.createDirectories(directory);
      objectMapper.writerWithDefaultPrettyPrinter()
          .writeValue(directory.resolve("expected.json").toFile(), expectations);
      objectMapper.writerWithDefaultPrettyPrinter()
          .writeValue(directory.resolve("actual.json").toFile(), actual);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Unable to write scenario artifacts for " + scenario.caseId(),
          exception);
    }
  }
}
