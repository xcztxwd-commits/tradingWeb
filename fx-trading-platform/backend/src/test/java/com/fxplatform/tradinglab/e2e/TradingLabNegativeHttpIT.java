package com.fxplatform.tradinglab.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradingLabNegativeHttpIT extends TradingLabHttpIntegrationSupport {

  private static final Set<String> ERROR_ENVELOPE_FIELDS =
      Set.of("success", "code", "message", "data", "timestamp");

  @Test
  void expectedNegativeActionPreservesTheExactRealHttpErrorWithoutMutation() {
    var fixture = TradingLabAdvancedHttpScenarioFactory.negative(currentConfig());
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
          .as("negative run failureCode=%s", terminal.failureCode())
          .isEqualTo("COMPLETED");
      assertThat(terminal.processedTicks())
          .isEqualTo(terminal.totalTicks())
          .isEqualTo(fixture.totalTicks());

      JsonNode report = downloadAndValidate(runId).root();
      JsonNode commandEvent = onlyPublicActionCommand(report);
      JsonNode payload = commandEvent.path("payload");
      assertThat(payload.path("outcome").asText()).isEqualTo("EXPECTED_ERROR");
      assertThat(payload.path("expectedStatus").asInt())
          .isEqualTo(fixture.expectedStatus());
      assertThat(payload.path("expectedCode").asText())
          .isEqualTo(fixture.expectedCode());
      assertThat(payload.path("status").asInt()).isEqualTo(fixture.expectedStatus());
      assertExactErrorEnvelope(payload.path("response"), fixture);
      assertThat(commandEvent.path("correlationId").asText()).isNotBlank();

      JsonNode commandTrace = onlyCommandTrace(report);
      assertThat(commandTrace.path("url").asText()).endsWith("/api/trading/orders");
      assertThat(commandTrace.path("requestBody").path("sanitizedRequest")
          .path("scope").asText()).isEqualTo("COMMAND");
      assertThat(commandTrace.path("requestBody").path("sanitizedRequest")
          .path("operation").asText()).isEqualTo("PUBLIC_ACTION");
      assertThat(commandTrace.path("requestBody").path("sanitizedRequest")
          .path("outcome").asText()).isEqualTo("EXPECTED_ERROR");
      JsonNode commandResponse = commandTrace.path("responseBody");
      assertThat(commandResponse.path("status").asInt())
          .isEqualTo(fixture.expectedStatus());
      assertExactErrorEnvelope(
          commandResponse.path("sanitizedResponse"),
          fixture);

      JsonNode hopTrace = onlyRejectedOrderHop(report);
      assertThat(hopTrace.path("requestBody").path("sanitizedRequest")
          .path("quantity").asText()).isEqualTo("0");
      assertThat(hopTrace.path("responseBody").path("status").asInt())
          .isEqualTo(fixture.expectedStatus());
      assertExactErrorEnvelope(
          hopTrace.path("responseBody").path("sanitizedResponse"),
          fixture);

      JsonNode state = report.path("actualState").path("state");
      assertThat(stateItems(state, "orders")).isEmpty();
      assertThat(stateItems(state, "trades")).isEmpty();
      assertThat(stateItems(state, "positions")).isEmpty();
      assertThat(stateItems(state, "fundingSettlements")).isEmpty();
      assertUnchangedSeedState(state);
      assertThat(report.path("errors")).isEmpty();
      assertThat(report.path("actualState").path("terminalState").asText())
          .isEqualTo("COMPLETED");
      assertThat(report.path("cleanup").path("status").asText())
          .isEqualTo("SUCCEEDED");
    } finally {
      cancelAndRetainIfActive(runId);
    }
  }

  private static JsonNode onlyPublicActionCommand(JsonNode report) {
    List<JsonNode> matches = validationEvents(report, "API_TRACE").stream()
        .filter(event -> "PUBLIC_ACTION".equals(
            event.path("payload").path("operation").asText()))
        .filter(event -> "COMMAND".equals(
            event.path("payload").path("traceScope").asText()))
        .toList();
    assertThat(matches).as("negative PUBLIC_ACTION command API_TRACE").singleElement();
    return matches.getFirst();
  }

  private static JsonNode onlyCommandTrace(JsonNode report) {
    List<JsonNode> matches = traces(report).stream()
        .filter(trace -> "COMMAND".equals(trace.path("requestBody")
            .path("sanitizedRequest").path("scope").asText()))
        .filter(trace -> "PUBLIC_ACTION".equals(trace.path("requestBody")
            .path("sanitizedRequest").path("operation").asText()))
        .toList();
    assertThat(matches).as("safe negative command trace").singleElement();
    return matches.getFirst();
  }

  private static JsonNode onlyRejectedOrderHop(JsonNode report) {
    List<JsonNode> matches = traces(report).stream()
        .filter(trace -> trace.path("url").asText().endsWith("/api/trading/orders"))
        .filter(trace -> !trace.path("requestBody").path("sanitizedRequest")
            .has("scope"))
        .filter(trace -> trace.path("responseBody").path("status").asInt() == 400)
        .toList();
    assertThat(matches).as("real rejected order HTTP hop").singleElement();
    return matches.getFirst();
  }

  private static List<JsonNode> traces(JsonNode report) {
    ArrayList<JsonNode> traces = new ArrayList<>();
    report.path("apiTrace").forEach(trace -> traces.add(trace.deepCopy()));
    return List.copyOf(traces);
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

  private static void assertExactErrorEnvelope(
      JsonNode envelope,
      TradingLabAdvancedHttpScenarioFactory.NegativeScenario fixture
  ) {
    assertThat(envelope.isObject()).isTrue();
    assertThat(fieldSet(envelope))
        .containsExactlyInAnyOrderElementsOf(ERROR_ENVELOPE_FIELDS);
    assertThat(envelope.path("success").asBoolean()).isFalse();
    assertThat(envelope.path("code").asText()).isEqualTo(fixture.expectedCode());
    assertThat(envelope.path("message").asText())
        .isEqualTo(fixture.expectedMessage());
    assertThat(envelope.path("data").isNull()).isTrue();
    assertThat(envelope.path("timestamp").asText()).isNotBlank();
  }

  private static void assertUnchangedSeedState(JsonNode state) {
    List<JsonNode> wallets = stateItems(state, "walletBalances");
    assertThat(wallets).singleElement().satisfies(wallet -> {
      assertThat(wallet.path("walletType").asText()).isEqualTo("SPOT");
      assertThat(wallet.path("asset").asText()).isEqualTo("USDT");
      assertThat(decimal(wallet, "total")).isEqualByComparingTo("100000.00000000");
      assertThat(decimal(wallet, "available")).isEqualByComparingTo("100000.00000000");
      assertThat(decimal(wallet, "locked")).isZero();
    });

    List<JsonNode> assetLedger = stateItems(state, "assetLedger");
    assertThat(assetLedger).hasSize(2);
    assertThat(assetLedger.stream()
        .map(entry -> entry.path("entryType").asText())
        .toList()).containsExactlyInAnyOrder("DEMO_INIT", "VALIDATION_SEED");

    List<JsonNode> cashLedger = stateItems(state, "cashLedger");
    assertThat(cashLedger).hasSize(4);
    assertThat(cashLedger.stream()
        .map(entry -> entry.path("entryType").asText())
        .toList()).containsExactlyInAnyOrder(
            "DEMO_INIT", "DEMO_INIT", "VALIDATION_SEED", "VALIDATION_SEED");

    JsonNode summary = state.path("summary");
    assertThat(summary.path("accountType").asText()).isEqualTo("DEMO");
    assertThat(summary.path("baseCurrency").asText()).isEqualTo("USDT");
    assertThat(summary.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(decimal(summary, "balance")).isEqualByComparingTo("100000.00000000");
    assertThat(decimal(summary, "equity")).isEqualByComparingTo("100000.00000000");
    assertThat(decimal(summary, "usedMargin")).isZero();
    assertThat(decimal(summary, "freeMargin")).isEqualByComparingTo("100000.00000000");
  }

  private static Set<String> fieldSet(JsonNode object) {
    LinkedHashSet<String> fields = new LinkedHashSet<>();
    object.fieldNames().forEachRemaining(fields::add);
    return fields;
  }
}
