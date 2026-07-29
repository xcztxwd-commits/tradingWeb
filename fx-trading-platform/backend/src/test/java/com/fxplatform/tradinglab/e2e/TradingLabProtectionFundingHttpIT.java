package com.fxplatform.tradinglab.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradingLabProtectionFundingHttpIT extends TradingLabHttpIntegrationSupport {

  @Test
  void attachedStopLossTriggersInTheNextPreActionSystemPhase() {
    assertAttachedProtection(TradingLabAdvancedHttpScenarioFactory.stopLoss(currentConfig()));
  }

  @Test
  void attachedTakeProfitTriggersInTheNextPreActionSystemPhase() {
    assertAttachedProtection(TradingLabAdvancedHttpScenarioFactory.takeProfit(currentConfig()));
  }

  @Test
  void trailingStopUpdatesItsExtremeBeforeTheLaterSystemTrigger() {
    var fixture = TradingLabAdvancedHttpScenarioFactory.trailing(currentConfig());
    JsonNode scenario = createScenario(fixture.scenarioRequest());
    UUID scenarioId = UUID.fromString(scenario.path("id").asText());
    RunView accepted = startRun(
        scenarioId,
        fixture.runRequest(scenario.path("version").asLong()));
    UUID runId = accepted.id();

    try {
      assertThat(accepted.totalTicks()).isEqualTo(fixture.totalTicks());
      RunView terminal = awaitTerminal(runId);
      assertThat(terminal.state())
          .as("trailing run failureCode=%s", terminal.failureCode())
          .isEqualTo("COMPLETED");
      assertThat(terminal.processedTicks())
          .isEqualTo(terminal.totalTicks())
          .isEqualTo(fixture.totalTicks());

      JsonNode report = downloadAndValidate(runId).root();
      JsonNode state = report.path("actualState").path("state");
      List<JsonNode> orders = stateItems(state, "orders");
      JsonNode parent = item(
          orders,
          candidate -> "USER".equals(candidate.path("origin").asText()),
          "trailing parent order");
      JsonNode trailing = item(
          orders,
          candidate -> "PROTECTIVE".equals(candidate.path("origin").asText())
              && candidate.hasNonNull("trailingDelta"),
          "trailing stop order");
      assertThat(parent.path("status").asText()).isEqualTo("FILLED");
      assertThat(trailing.path("origin").asText()).isEqualTo("PROTECTIVE");
      assertThat(trailing.path("protectionType").asText()).isEqualTo("STOP_LOSS");
      assertThat(trailing.path("orderType").asText()).isEqualTo("MARKET");
      assertThat(trailing.path("status").asText()).isEqualTo("FILLED");
      assertThat(trailing.path("parentPositionId").asText()).isNotBlank();
      assertThat(decimal(trailing, "lots")).isEqualByComparingTo(fixture.quantity());
      assertThat(decimal(trailing, "activationPrice"))
          .isEqualByComparingTo(fixture.activationPrice());
      assertThat(decimal(trailing, "trailingExtreme"))
          .isEqualByComparingTo(fixture.extremePrice());
      assertThat(decimal(trailing, "triggerPrice"))
          .isEqualByComparingTo(fixture.triggerPrice());
      assertThat(stateItems(state, "trades")).hasSize(2);
      assertThat(stateItems(state, "positions")).isEmpty();

      JsonNode activationStep = systemStep(report, 3L, "PRE_ACTIONS");
      assertThat(subStep(activationStep, "updateTrailingExtrema")
          .path("details").path("updated").asInt()).isEqualTo(1);
      JsonNode triggerStep = systemStep(report, 4L, "PRE_ACTIONS");
      assertThat(subStep(triggerStep, "triggerProtectionOrders")
          .path("details").path("trailing").asInt()).isEqualTo(1);
      assertSystemStepBetweenTickAndCheckpoint(report, 3L, activationStep);
      assertSystemStepBetweenTickAndCheckpoint(report, 4L, triggerStep);
      assertThat(stateItems(checkpointState(report, 3L), "positions")).singleElement();
      assertThat(stateItems(checkpointState(report, 4L), "positions")).isEmpty();
      assertThat(report.path("errors")).isEmpty();
      assertThat(report.path("actualState").path("terminalState").asText())
          .isEqualTo("COMPLETED");
      assertThat(report.path("cleanup").path("status").asText())
          .isEqualTo("SUCCEEDED");
    } finally {
      cancelAndRetainIfActive(runId);
    }
  }

  @Test
  void fundingAuthorityIsPublishedBeforeTheFillAndSettledAfterIt() {
    var fixture = TradingLabAdvancedHttpScenarioFactory.funding(currentConfig());
    JsonNode scenario = createScenario(fixture.scenarioRequest());
    UUID scenarioId = UUID.fromString(scenario.path("id").asText());
    RunView accepted = startRun(
        scenarioId,
        fixture.runRequest(scenario.path("version").asLong()));
    UUID runId = accepted.id();

    try {
      assertThat(accepted.totalTicks()).isEqualTo(fixture.totalTicks());
      RunView terminal = awaitTerminal(runId);
      assertThat(terminal.state())
          .as("funding run failureCode=%s", terminal.failureCode())
          .isEqualTo("COMPLETED");
      assertThat(terminal.processedTicks())
          .isEqualTo(terminal.totalTicks())
          .isEqualTo(fixture.totalTicks());

      JsonNode report = downloadAndValidate(runId).root();
      JsonNode state = report.path("actualState").path("state");
      List<JsonNode> settlements = stateItems(state, "fundingSettlements");
      assertThat(settlements).singleElement();
      JsonNode settlement = item(
          settlements,
          candidate -> "BTCUSDT-PERP".equals(candidate.path("symbol").asText()),
          "BTCUSDT-PERP funding settlement");
      assertThat(decimal(settlement, "fundingRate"))
          .isEqualByComparingTo(fixture.fundingRate());
      assertThat(decimal(settlement, "amount"))
          .isEqualByComparingTo(fixture.expectedAmount());
      assertThat(decimal(settlement, "markPrice")).isEqualByComparingTo("50000");
      JsonNode position = item(
          stateItems(state, "positions"),
          candidate -> "BTCUSDT-PERP".equals(candidate.path("symbol").asText()),
          "funded long position");
      assertThat(decimal(position, "fundingPnl"))
          .isEqualByComparingTo(fixture.expectedAmount());
      List<JsonNode> fundingLedger = stateItems(state, "cashLedger").stream()
          .filter(candidate -> "FUNDING_FEE".equals(
              candidate.path("entryType").asText()))
          .toList();
      assertThat(fundingLedger).singleElement();
      JsonNode fundingEntry = fundingLedger.getFirst();
      assertThat(fundingEntry.path("referenceId").asText())
          .isEqualTo(settlement.path("id").asText());
      assertThat(decimal(fundingEntry, "amount"))
          .isEqualByComparingTo(fixture.expectedAmount());

      JsonNode pre = systemStep(report, 1L, "PRE_ACTIONS");
      JsonNode action = onlyCommand(report, "PUBLIC_ACTION");
      JsonNode post = systemStep(report, 1L, "POST_ACTIONS");
      assertThat(subStep(pre, "publishTick")
          .path("details").path("fundingRates").asInt()).isEqualTo(1);
      assertThat(subStep(post, "settlePersistedFunding")
          .path("details").path("settled").asInt()).isEqualTo(1);
      assertThat(pre.path("sequence").asLong())
          .isLessThan(action.path("sequence").asLong());
      assertThat(action.path("sequence").asLong())
          .isLessThan(post.path("sequence").asLong());
      assertThat(report.path("errors")).isEmpty();
      assertThat(report.path("actualState").path("terminalState").asText())
          .isEqualTo("COMPLETED");
      assertThat(report.path("cleanup").path("status").asText())
          .isEqualTo("SUCCEEDED");
    } finally {
      cancelAndRetainIfActive(runId);
    }
  }

  private void assertAttachedProtection(
      TradingLabAdvancedHttpScenarioFactory.ProtectionScenario fixture
  ) {
    JsonNode scenario = createScenario(fixture.scenarioRequest());
    UUID scenarioId = UUID.fromString(scenario.path("id").asText());
    RunView accepted = startRun(
        scenarioId,
        fixture.runRequest(scenario.path("version").asLong()));
    UUID runId = accepted.id();

    try {
      assertThat(accepted.totalTicks()).isEqualTo(fixture.totalTicks());
      RunView terminal = awaitTerminal(runId);
      assertThat(terminal.state())
          .as("%s run failureCode=%s", fixture.protectionType(), terminal.failureCode())
          .isEqualTo("COMPLETED");
      assertThat(terminal.processedTicks())
          .isEqualTo(terminal.totalTicks())
          .isEqualTo(fixture.totalTicks());

      JsonNode report = downloadAndValidate(runId).root();
      JsonNode state = report.path("actualState").path("state");
      List<JsonNode> orders = stateItems(state, "orders");
      JsonNode parent = item(
          orders,
          candidate -> "USER".equals(candidate.path("origin").asText()),
          "protection parent order");
      JsonNode protection = item(
          orders,
          candidate -> fixture.protectionType()
              .equals(candidate.path("protectionType").asText()),
          fixture.protectionType() + " order");
      assertThat(parent.path("status").asText()).isEqualTo("FILLED");
      assertThat(protection.path("origin").asText()).isEqualTo("PROTECTIVE");
      assertThat(protection.path("status").asText()).isEqualTo("FILLED");
      assertThat(protection.path("side").asText()).isEqualTo("SELL");
      assertThat(protection.path("positionSide").asText()).isEqualTo("LONG");
      assertThat(protection.path("reduceOnly").asBoolean()).isTrue();
      assertThat(protection.path("triggerPriceType").asText()).isEqualTo("MARK_PRICE");
      assertThat(protection.path("triggerExecutionType").asText()).isEqualTo("MARKET");
      assertThat(protection.path("parentOrderId").asText())
          .isEqualTo(parent.path("id").asText());
      assertThat(decimal(protection, "triggerPrice"))
          .isEqualByComparingTo(fixture.triggerPrice());
      assertThat(decimal(protection, "lots"))
          .isEqualByComparingTo(fixture.quantity());
      assertThat(stateItems(state, "trades")).hasSize(2);
      assertThat(stateItems(state, "positions")).isEmpty();
      assertThat(stateItems(checkpointState(report, 1L), "positions")).singleElement();
      assertThat(stateItems(checkpointState(report, 2L), "positions")).isEmpty();

      JsonNode pre = systemStep(report, 2L, "PRE_ACTIONS");
      assertThat(subStep(pre, "triggerProtectionOrders")
          .path("details").path("protections").asInt()).isEqualTo(1);
      assertSystemStepBetweenTickAndCheckpoint(report, 2L, pre);
      assertThat(report.path("errors")).isEmpty();
      assertThat(report.path("actualState").path("terminalState").asText())
          .isEqualTo("COMPLETED");
      assertThat(report.path("cleanup").path("status").asText())
          .isEqualTo("SUCCEEDED");
    } finally {
      cancelAndRetainIfActive(runId);
    }
  }

  private static JsonNode systemStep(JsonNode report, long tick, String phase) {
    List<JsonNode> matches = commandEvents(report, "SYSTEM_STEP").stream()
        .filter(event -> responseData(event).path("tickSequence").asLong() == tick)
        .filter(event -> phase.equals(responseData(event).path("phase").asText()))
        .toList();
    assertThat(matches).as("Tick %s %s system step", tick, phase).singleElement();
    JsonNode event = matches.getFirst();
    assertThat(event.path("payload").path("outcome").asText()).isEqualTo("SUCCEEDED");
    assertThat(event.path("payload").path("status").asInt()).isEqualTo(200);
    JsonNode response = event.path("payload").path("trace")
        .path("responseBody").path("sanitizedResponse");
    assertThat(response.path("success").asBoolean()).isTrue();
    assertThat(response.path("code").asText()).isEqualTo("OK");
    return event;
  }

  private static JsonNode subStep(JsonNode systemStep, String name) {
    List<JsonNode> matches = new ArrayList<>();
    responseData(systemStep).path("subSteps").forEach(candidate -> {
      if (name.equals(candidate.path("name").asText())) {
        matches.add(candidate.deepCopy());
      }
    });
    assertThat(matches).as(name + " system sub-step").singleElement();
    return matches.getFirst();
  }

  private static JsonNode responseData(JsonNode commandEvent) {
    return commandEvent.path("payload").path("trace")
        .path("responseBody").path("sanitizedResponse").path("data");
  }

  private static JsonNode onlyCommand(JsonNode report, String operation) {
    List<JsonNode> matches = commandEvents(report, operation);
    assertThat(matches).as(operation + " command event").singleElement();
    return matches.getFirst();
  }

  private static List<JsonNode> commandEvents(JsonNode report, String operation) {
    return validationEvents(report, "API_TRACE").stream()
        .filter(event -> operation.equals(event.path("payload").path("operation").asText()))
        .filter(event -> "COMMAND".equals(
            event.path("payload").path("traceScope").asText()))
        .toList();
  }

  private static JsonNode checkpointState(JsonNode report, long tick) {
    List<JsonNode> matches = validationEvents(report, "CHECKPOINT").stream()
        .filter(event -> event.path("payload").path("tickSequence").asLong() == tick)
        .toList();
    assertThat(matches).as("Tick " + tick + " checkpoint").singleElement();
    JsonNode state = matches.getFirst().path("payload").path("state");
    assertThat(state.isObject()).isTrue();
    return state;
  }

  private static void assertSystemStepBetweenTickAndCheckpoint(
      JsonNode report,
      long tick,
      JsonNode systemStep
  ) {
    JsonNode marketTick = onlyValidationEvent(report, "MARKET_TICK", tick);
    JsonNode checkpoint = onlyValidationEvent(report, "CHECKPOINT", tick);
    assertThat(marketTick.path("sequence").asLong())
        .isLessThan(systemStep.path("sequence").asLong());
    assertThat(systemStep.path("sequence").asLong())
        .isLessThan(checkpoint.path("sequence").asLong());
  }

  private static JsonNode onlyValidationEvent(JsonNode report, String type, long tick) {
    List<JsonNode> matches = validationEvents(report, type).stream()
        .filter(event -> event.path("payload").path("tickSequence").asLong() == tick)
        .toList();
    assertThat(matches).as(type + " at Tick " + tick).singleElement();
    return matches.getFirst();
  }

  private static List<JsonNode> validationEvents(JsonNode report, String type) {
    ArrayList<JsonNode> matches = new ArrayList<>();
    report.path("lifecycle").forEach(lifecycle -> {
      if (!"VALIDATION_EVENT".equals(lifecycle.path("eventType").asText())) {
        return;
      }
      JsonNode event = lifecycle.path("payload").path("evidence");
      if (type.equals(event.path("type").asText())) {
        matches.add(event.deepCopy());
      }
    });
    return List.copyOf(matches);
  }
}
