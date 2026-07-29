package com.fxplatform.trading.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class ScenarioMatrixArtifacts {

  public static final String JSON_FILE = "spot-perp-scenario-matrix.json";
  public static final String CSV_FILE = "spot-perp-scenario-matrix.csv";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .findAndRegisterModules()
      .enable(SerializationFeature.INDENT_OUTPUT);

  private ScenarioMatrixArtifacts() {
  }

  public static void write(Path docsTesting, List<ScenarioDefinition> scenarios)
      throws IOException {
    Files.createDirectories(docsTesting);
    List<ScenarioDefinition> ordered = scenarios.stream()
        .sorted(Comparator.comparing(ScenarioDefinition::caseId))
        .toList();
    Files.writeString(
        docsTesting.resolve(JSON_FILE),
        normalizeLf(OBJECT_MAPPER.writeValueAsString(ordered)) + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        docsTesting.resolve(CSV_FILE),
        renderCsv(ordered),
        StandardCharsets.UTF_8);
  }

  private static String renderCsv(List<ScenarioDefinition> scenarios)
      throws JsonProcessingException {
    StringBuilder csv = new StringBuilder(String.join(",", ScenarioDefinition.COLUMNS))
        .append('\n');
    for (ScenarioDefinition scenario : scenarios) {
      JsonNode row = OBJECT_MAPPER.valueToTree(scenario);
      for (int index = 0; index < ScenarioDefinition.COLUMNS.size(); index++) {
        if (index > 0) {
          csv.append(',');
        }
        JsonNode value = row.get(ScenarioDefinition.COLUMNS.get(index));
        csv.append(quote(csvValue(value)));
      }
      csv.append('\n');
    }
    return csv.toString();
  }

  private static String csvValue(JsonNode value) {
    if (value == null || value.isNull()) {
      return "";
    }
    if (value.isTextual()) {
      return value.textValue();
    }
    return value.toString();
  }

  private static String quote(String value) {
    if (value.indexOf(',') < 0
        && value.indexOf('"') < 0
        && value.indexOf('\r') < 0
        && value.indexOf('\n') < 0) {
      return value;
    }
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static String normalizeLf(String value) {
    return value.replace("\r\n", "\n").replace('\r', '\n');
  }
}
