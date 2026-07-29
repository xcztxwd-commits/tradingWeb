package com.fxplatform.tradinglab.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fxplatform.tradinglab.client.ValidationRunStartRequest;
import com.fxplatform.tradinglab.report.TradingLabReportProperties;
import com.fxplatform.tradinglab.repository.TradingLabWorkerRunSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SnapshotTradingLabValidationStartRequestFactoryTest {

  private static final UUID RUN_ID = UUID.fromString(
      "00000000-0000-0000-0000-000000000751");

  @Test
  void freezesOneDeterministicCredentialFreeWireDocumentForTheGeneration() {
    var factory = factory();
    TradingLabWorkerRunSnapshot snapshot = snapshot(validScenario());

    ValidationRunStartRequest first = factory.create(snapshot, 7L);
    ValidationRunStartRequest replay = factory.create(snapshot, 7L);
    ValidationRunStartRequest nextGeneration = factory.create(snapshot, 8L);

    assertThat(replay).isEqualTo(first);
    assertThat(first.seed()).isEqualTo("seed-001");
    assertThat(first.speedMultiplier()).isEqualTo(new BigDecimal("2.500000"));
    assertThat(first.initialBalances().get("USDT"))
        .isEqualTo(new BigDecimal("50000.00000000"));
    assertThat(first.requestFingerprint()).matches("[0-9a-f]{64}");
    assertThat(first.ticks()).singleElement().satisfies(tick -> {
      assertThat(tick).containsEntry("runId", RUN_ID.toString());
      assertThat(tick).containsEntry("generation", 7L);
    });
    assertThat(nextGeneration.requestFingerprint())
        .isNotEqualTo(first.requestFingerprint());
    assertThat(nextGeneration.ticks().getFirst())
        .containsEntry("generation", 8L);
    ValidationRunStartRequest otherSeed = factory.create(
        snapshot(validScenario().replace("seed-001", "seed-002")),
        7L);
    assertThat(otherSeed.requestFingerprint()).isNotEqualTo(first.requestFingerprint());
  }

  @Test
  void rejectsCredentialMaterialInsteadOfSilentlySendingIt() {
    String unsafe = validScenario().replace(
        "\"matchingMode\":\"SIMPLE\"",
        "\"matchingMode\":\"SIMPLE\",\"password\":\"raw-secret\"");

    assertThatThrownBy(() -> factory().create(snapshot(unsafe), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("validationRun");
  }

  @Test
  void rejectsDuplicateJsonKeysBeforeBuildingTheStartRequest() {
    String duplicate = validScenario().replace(
        "\"positionMode\":\"HEDGE\"",
        "\"positionMode\":\"HEDGE\",\"positionMode\":\"ONE_WAY\"");

    assertThatThrownBy(() -> factory().create(snapshot(duplicate), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scenarioSnapshotJson");
  }

  @Test
  void acceptsTheHistoricalRootWireDespiteItsOverlappingStartFields() {
    String root = """
        {
          "seed":"seed-root",
          "virtualStart":"2026-07-23T18:00:00Z",
          "speedMultiplier":"3",
          "executionPolicy":{"matchingMode":"SIMPLE"},
          "initialBalances":{"USDT":"50000"},
          "accountSettings":{"positionMode":"HEDGE"},
          "ticks":[{"sequence":1}],
          "actions":[]
        }
        """;

    ValidationRunStartRequest request = factory().compile(
        new TradingLabValidationStartRequestFactory.TradingLabValidationStartSource(
            RUN_ID,
            root,
            "{}",
            "c".repeat(64),
            "model-v1",
            "symbols-v1",
            "code-v1"),
        1L);

    assertThat(request.seed()).isEqualTo("seed-root");
    assertThat(request.speedMultiplier()).isEqualTo(new BigDecimal("3.000000"));
    assertThat(request.requestFingerprint())
        .isEqualTo("6a3c2488f9c45436a84059ef826e87ea0d6f76ace5e7f9224d095e55b45f1236");
    assertThat(request.ticks()).singleElement().satisfies(tick ->
        assertThat(tick).containsEntry("runId", RUN_ID.toString()));
  }

  @Test
  void finalSizeGateUsesTheSameIsoInstantWireAsTheHttpClient()
      throws Exception {
    ValidationRunStartRequest request = factory().create(
        snapshot(validScenario()),
        Long.MAX_VALUE);
    ObjectMapper timestampMapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
        .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    ObjectMapper transportMapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    int timestampBytes = timestampMapper.writeValueAsBytes(request).length;
    int transportBytes = transportMapper.writeValueAsBytes(request).length;
    assertThat(transportBytes).isGreaterThan(timestampBytes);

    TradingLabReportProperties properties = new TradingLabReportProperties();
    properties.setMaxLogicalValueBytes(timestampBytes);
    SnapshotTradingLabValidationStartRequestFactory bounded =
        new SnapshotTradingLabValidationStartRequestFactory(
            new TradingLabCredentialSanitizer(),
            properties);

    assertThatThrownBy(() -> bounded.create(
        snapshot(validScenario()),
        Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("validationRun");
  }

  @Test
  void finalWriterUsesTheHttpTransportNestingLimit() throws Exception {
    SnapshotTradingLabValidationStartRequestFactory factory = factory();
    var jsonField =
        SnapshotTradingLabValidationStartRequestFactory.class.getDeclaredField("json");
    assertThat(jsonField.trySetAccessible()).isTrue();
    ObjectMapper writer = (ObjectMapper) jsonField.get(factory);

    assertThat(writer.getFactory()
        .streamWriteConstraints()
        .getMaxNestingDepth())
        .isEqualTo(64);
  }

  @Test
  void workerRecompileFailsClosedWhenPersistedShapeDiffers() {
    TradingLabWorkerRunSnapshot mismatched = snapshot(validScenario(), 2L);

    assertThatThrownBy(() -> factory().create(mismatched, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("shape");
  }

  @Test
  void rejectsAmbiguousNestedAndRootLegacyTemplates() {
    String ambiguous = validScenario().replace(
        "\"seed\": \"seed-001\",",
        "\"seed\":\"seed-001\",\"ticks\":[],");

    assertThatThrownBy(() -> factory().create(snapshot(ambiguous), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mode");
  }

  @Test
  void rejectsNestedLegacyWithAnyPartialBrowserMarker() {
    for (String rootField : new String[] {
        "\"executionPolicy\":{\"matchingMode\":\"SIMPLE\"}",
        "\"initialBalances\":{\"USDT\":\"1\"}"
    }) {
      String ambiguous = validScenario().replace(
          "\"seed\": \"seed-001\",",
          "\"seed\":\"seed-001\"," + rootField + ",");

      assertThatThrownBy(() -> factory().create(snapshot(ambiguous), 1L))
          .as(rootField)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("mode");
    }
  }

  private static SnapshotTradingLabValidationStartRequestFactory factory() {
    return new SnapshotTradingLabValidationStartRequestFactory(
        new TradingLabCredentialSanitizer(),
        new TradingLabReportProperties());
  }

  private static TradingLabWorkerRunSnapshot snapshot(String scenarioJson) {
    return snapshot(scenarioJson, 1L);
  }

  private static TradingLabWorkerRunSnapshot snapshot(
      String scenarioJson,
      long totalTicks
  ) {
    Instant now = Instant.parse("2026-07-23T18:00:00Z");
    return new TradingLabWorkerRunSnapshot(
        RUN_ID,
        UUID.fromString("00000000-0000-0000-0000-000000000752"),
        UUID.fromString("00000000-0000-0000-0000-000000000753"),
        "WRITING",
        "RESETTING",
        4L,
        false,
        false,
        now,
        now,
        0L,
        totalTicks,
        new BigDecimal("2.5"),
        scenarioJson,
        "{}",
        "c".repeat(64),
        "model-v1",
        "symbols-v1",
        "code-v1",
        UUID.fromString("00000000-0000-0000-0000-000000000754"));
  }

  private static String validScenario() {
    return """
        {
          "seed": "seed-001",
          "validationRun": {
            "virtualStart": "2026-07-23T18:00:00Z",
            "executionPolicy": {"matchingMode":"SIMPLE"},
            "initialBalances": {"USDT":50000.00000000},
            "accountSettings": {"positionMode":"HEDGE"},
            "ticks": [{
              "sequence":1,
              "runId":"caller-value-must-be-replaced",
              "generation":999,
              "symbol":"BTCUSDT",
              "price":"50000.00"
            }],
            "actions": [{"type":"PLACE_ORDER","tickSequence":1}]
          }
        }
        """;
  }
}
