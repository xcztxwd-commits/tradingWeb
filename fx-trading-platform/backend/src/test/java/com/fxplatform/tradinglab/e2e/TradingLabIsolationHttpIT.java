package com.fxplatform.tradinglab.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradingLabIsolationHttpIT extends TradingLabHttpIntegrationSupport {

  @Test
  void consecutiveRunsAdvanceTheCoherentGenerationAndExposeNoPriorBusinessState() {
    var fixture = TradingLabHttpScenarioFactory.spot(currentConfig());
    UUID firstRunId = null;
    UUID secondRunId = null;
    try {
      RunProof first = executeSpot(fixture);
      firstRunId = first.runId();
      RunProof second = executeSpot(fixture);
      secondRunId = second.runId();

      assertThat(second.runId()).isNotEqualTo(first.runId());
      assertThat(second.report().reportId()).isNotEqualTo(first.report().reportId());
      assertThat(second.accountId()).isNotEqualTo(first.accountId());
      assertThat(second.initialResetGeneration())
          .as("run N+1 initial reset follows run N final reset")
          .isEqualTo(first.cleanupGeneration() + 1L);
      assertThat(second.validationGeneration())
          .isEqualTo(second.initialResetGeneration());

      assertThat(second.orderIds()).doesNotContainAnyElementsOf(first.orderIds());
      assertThat(second.tradeIds()).doesNotContainAnyElementsOf(first.tradeIds());
      assertThat(second.positionIds()).doesNotContainAnyElementsOf(first.positionIds());
      assertThat(second.quoteAvailable())
          .as("run N+1 starts from the same frozen wallet seed")
          .isEqualByComparingTo(first.quoteAvailable());

      String secondState = second.report().root().path("actualState").path("state").toString();
      for (String priorId : first.allBusinessIds()) {
        assertThat(secondState)
            .as("run N+1 state must not contain run N business identity " + priorId)
            .doesNotContain(priorId);
      }
    } finally {
      if (secondRunId != null) {
        cancelAndRetainIfActive(secondRunId);
      }
      if (firstRunId != null) {
        cancelAndRetainIfActive(firstRunId);
      }
    }
  }

  private RunProof executeSpot(TradingLabHttpScenarioFactory.SpotScenario fixture) {
    JsonNode scenario = createScenario(fixture.scenarioRequest());
    UUID scenarioId = UUID.fromString(scenario.path("id").asText());
    RunView accepted = startRun(
        scenarioId,
        fixture.runRequest(scenario.path("version").asLong()));
    UUID runId = accepted.id();
    try {
      RunView terminal = awaitTerminal(runId);
      assertThat(terminal.state())
          .as("isolation Spot run failureCode=%s", terminal.failureCode())
          .isEqualTo("COMPLETED");
      assertThat(terminal.processedTicks())
          .isEqualTo(terminal.totalTicks())
          .isEqualTo(fixture.totalTicks());

      RawReport report = downloadAndValidate(runId);
      JsonNode root = report.root();
      JsonNode actual = root.path("actualState");
      assertThat(actual.path("terminalState").asText()).isEqualTo("COMPLETED");
      assertThat(actual.path("stateAvailable").asBoolean()).isTrue();
      JsonNode state = actual.path("state");
      assertThat(state.isObject()).isTrue();

      JsonNode reset = resetReceipt(root, "initial-reset");
      long redisGeneration = exactNonNegativeLong(reset, "redisGeneration");
      long memoryGeneration = exactNonNegativeLong(reset, "memoryGeneration");
      assertThat(reset.path("status").asText()).isEqualTo("SUCCEEDED");
      assertThat(reset.path("databaseName").asText()).isEqualTo("fx_validation_lab");
      assertThat(redisGeneration).isPositive().isEqualTo(memoryGeneration);
      assertResetStepSucceeded(reset, "DATABASE");
      assertResetStepSucceeded(reset, "REDIS");
      assertResetStepSucceeded(reset, "MEMORY_GENERATION");

      long validationGeneration = exactNonNegativeLong(actual, "validationGeneration");
      assertThat(validationGeneration).isEqualTo(memoryGeneration);
      JsonNode cleanup = root.path("cleanup");
      assertThat(cleanup.path("status").asText()).isEqualTo("SUCCEEDED");
      assertThat(exactNonNegativeLong(cleanup, "expectedGeneration"))
          .isEqualTo(validationGeneration);
      long cleanupGeneration = exactNonNegativeLong(cleanup, "resultGeneration");
      assertThat(cleanupGeneration).isEqualTo(validationGeneration + 1L);

      List<JsonNode> orders = stateItems(state, "orders");
      List<JsonNode> trades = stateItems(state, "trades");
      List<JsonNode> positions = stateItems(state, "positions");
      assertThat(orders).hasSize(1);
      assertThat(trades).hasSize(1);
      assertThat(positions).hasSize(1);

      JsonNode quoteWallet = item(
          stateItems(state, "walletBalances"),
          candidate -> fixture.quoteAsset().equals(candidate.path("asset").asText()),
          "isolated USDT wallet");
      UUID accountId = UUID.fromString(state.path("summary").path("id").asText());
      Set<String> orderIds = ids(orders);
      Set<String> tradeIds = ids(trades);
      Set<String> positionIds = ids(positions);
      LinkedHashSet<String> allIds = new LinkedHashSet<>();
      allIds.add(accountId.toString());
      allIds.addAll(orderIds);
      allIds.addAll(tradeIds);
      allIds.addAll(positionIds);
      allIds.addAll(ids(stateItems(state, "walletBalances")));
      allIds.addAll(ids(stateItems(state, "assetLedger")));
      allIds.addAll(ids(stateItems(state, "cashLedger")));
      allIds.addAll(ids(stateItems(state, "fundingSettlements")));
      return new RunProof(
          runId,
          report,
          accountId,
          validationGeneration,
          memoryGeneration,
          cleanupGeneration,
          orderIds,
          tradeIds,
          positionIds,
          decimal(quoteWallet, "available"),
          Set.copyOf(allIds));
    } finally {
      cancelAndRetainIfActive(runId);
    }
  }

  private static JsonNode resetReceipt(JsonNode report, String sourceKey) {
    JsonNode latest = null;
    long latestSequence = -1L;
    for (JsonNode event : report.path("lifecycle")) {
      if ("VALIDATION_HTTP_RESULT".equals(event.path("eventType").asText())
          && sourceKey.equals(event.path("payload").path("sourceKey").asText())) {
        JsonNode sequence = event.path("sequence");
        JsonNode evidence = event.path("payload").path("evidence");
        JsonNode status = evidence.path("status");
        JsonNode data = evidence
            .path("sanitizedResponse")
            .path("body")
            .path("data");
        if (sequence.isIntegralNumber()
            && sequence.canConvertToLong()
            && sequence.longValue() > latestSequence
            && status.isIntegralNumber()
            && status.canConvertToInt()
            && status.intValue() >= 200
            && status.intValue() < 300
            && evidence.path("exception").isNull()
            && data.isObject()) {
          latest = data.deepCopy();
          latestSequence = sequence.longValue();
        }
      }
    }
    if (latest != null) {
      return latest;
    }
    throw new AssertionError("Missing successful durable reset receipt for " + sourceKey);
  }

  private static void assertResetStepSucceeded(JsonNode receipt, String expectedStep) {
    for (JsonNode step : receipt.path("steps")) {
      if (expectedStep.equals(step.path("step").asText())) {
        assertThat(step.path("status").asText())
            .as(expectedStep + " reset step")
            .isEqualTo("SUCCEEDED");
        return;
      }
    }
    throw new AssertionError("Missing reset step " + expectedStep);
  }

  private static long exactNonNegativeLong(JsonNode object, String field) {
    JsonNode value = object.get(field);
    assertThat(value).as(field).isNotNull();
    assertThat(value.isIntegralNumber()).as(field).isTrue();
    assertThat(value.canConvertToLong()).as(field).isTrue();
    long result = value.longValue();
    assertThat(result).as(field).isNotNegative();
    return result;
  }

  private static Set<String> ids(List<JsonNode> values) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (JsonNode value : values) {
      String id = value.path("id").asText();
      assertThat(UUID.fromString(id).toString()).isEqualTo(id);
      assertThat(ids.add(id)).as("unique business ID").isTrue();
    }
    return Set.copyOf(ids);
  }

  private record RunProof(
      UUID runId,
      RawReport report,
      UUID accountId,
      long validationGeneration,
      long initialResetGeneration,
      long cleanupGeneration,
      Set<String> orderIds,
      Set<String> tradeIds,
      Set<String> positionIds,
      BigDecimal quoteAvailable,
      Set<String> allBusinessIds
  ) {
  }
}
