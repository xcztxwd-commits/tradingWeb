package com.fxplatform.validation.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fxplatform.validation.http.ValidationHttpIntegrationSupport.RunResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("validation-fixed-runtime")
class ValidationNegativeReplayIT {

  @Test
  void exactExpectedStatusAndCodeAreAcceptedWithoutTreatingArbitrary4xxAsSuccess() {
    ValidationHttpIntegrationSupport http = new ValidationHttpIntegrationSupport();
    long generation = http.reset().generation();
    var request = ValidationHttpIntegrationSupport.negativeReplay(generation);

    RunResult run = http.startAndAwait(request);
    RunResult replay = http.startAndAwait(request);

    assertThat(run.apiTraces("PUBLIC_ACTION")).singleElement().satisfies(trace -> {
      http.assertSanitized(trace);
      JsonNode payload = trace.path("payload");
      assertThat(payload.path("outcome").asText()).isEqualTo("EXPECTED_ERROR");
      assertThat(payload.path("expectedStatus").asInt()).isEqualTo(400);
      assertThat(payload.path("expectedCode").asText()).isEqualTo("VALIDATION_ERROR");
      assertThat(payload.path("status").asInt()).isEqualTo(400);
      assertThat(payload.path("response").path("success").asBoolean()).isFalse();
      assertThat(payload.path("response").path("code").asText())
          .isEqualTo("VALIDATION_ERROR");
      assertThat(trace.path("correlationId").asText()).isNotBlank();
    });
    assertThat(run.items("orders")).isEmpty();
    assertThat(run.items("trades")).isEmpty();
    assertThat(run.items("positions")).isEmpty();
    assertThat(run.items("fundingSettlements")).isEmpty();
    assertThat(replay.allEvents()).isEqualTo(run.allEvents());
    assertThat(replay.finalBusinessState()).isEqualTo(run.finalBusinessState());
    assertThat(replay.apiTraces("PUBLIC_ACTION")).singleElement().satisfies(trace ->
        assertThat(trace).isEqualTo(run.apiTraces("PUBLIC_ACTION").getFirst()));
    assertThat(replay.items("orders")).isEmpty();
    assertThat(replay.items("trades")).isEmpty();
    assertThat(replay.items("positions")).isEmpty();
    assertThat(replay.items("fundingSettlements")).isEmpty();
  }

  @Test
  void sameErrorWithoutExpectationFailsWithDurableActualResponse() {
    ValidationHttpIntegrationSupport http = new ValidationHttpIntegrationSupport();
    long generation = http.reset().generation();
    var request = ValidationHttpIntegrationSupport.negativeWithoutExpectation(generation);

    http.start(request);
    RunResult run = http.awaitTerminal(request, "FAILED");

    assertThat(run.failureCode()).isEqualTo("VALIDATION_LOOPBACK_FAILED");
    assertThat(run.apiTraces("PUBLIC_ACTION")).singleElement().satisfies(trace -> {
      http.assertSanitized(trace);
      assertThat(trace.path("payload").path("outcome").asText()).isEqualTo("FAILED");
      assertThat(trace.path("payload").path("status").asInt()).isEqualTo(400);
      assertThat(trace.path("payload").path("response").path("code").asText())
          .isEqualTo("VALIDATION_ERROR");
      assertThat(trace.path("correlationId").asText()).isNotBlank();
    });
    assertThat(run.events("STATE_SNAPSHOT")).isEmpty();
  }

  @Test
  void statusMismatchFailsClosedEvenWhenTheBusinessCodeMatches() {
    ValidationHttpIntegrationSupport http = new ValidationHttpIntegrationSupport();
    long generation = http.reset().generation();
    var request = ValidationHttpIntegrationSupport.negativeStatusMismatch(generation);

    http.start(request);
    RunResult run = http.awaitTerminal(request, "FAILED");

    assertThat(run.failureCode())
        .isEqualTo("VALIDATION_EXPECTED_HTTP_ERROR_MISMATCH");
    assertThat(run.apiTraces("PUBLIC_ACTION")).singleElement().satisfies(trace -> {
      http.assertSanitized(trace);
      JsonNode payload = trace.path("payload");
      assertThat(payload.path("outcome").asText()).isEqualTo("FAILED");
      assertThat(payload.path("expectedStatus").asInt()).isEqualTo(409);
      assertThat(payload.path("expectedCode").asText()).isEqualTo("VALIDATION_ERROR");
      assertThat(payload.path("status").asInt()).isEqualTo(400);
      assertThat(payload.path("response").path("success").asBoolean()).isFalse();
      assertThat(payload.path("response").path("code").asText())
          .isEqualTo("VALIDATION_ERROR");
      assertThat(trace.path("correlationId").asText()).isNotBlank();
    });
  }

  @Test
  void codeMismatchFailsClosedEvenWhenTheHttpStatusMatches() {
    ValidationHttpIntegrationSupport http = new ValidationHttpIntegrationSupport();
    long generation = http.reset().generation();
    var request = ValidationHttpIntegrationSupport.negativeCodeMismatch(generation);

    http.start(request);
    RunResult run = http.awaitTerminal(request, "FAILED");

    assertThat(run.failureCode())
        .isEqualTo("VALIDATION_EXPECTED_HTTP_ERROR_MISMATCH");
    assertThat(run.apiTraces("PUBLIC_ACTION")).singleElement().satisfies(trace -> {
      http.assertSanitized(trace);
      JsonNode payload = trace.path("payload");
      assertThat(payload.path("outcome").asText()).isEqualTo("FAILED");
      assertThat(payload.path("expectedStatus").asInt()).isEqualTo(400);
      assertThat(payload.path("expectedCode").asText()).isEqualTo("INSUFFICIENT_BALANCE");
      assertThat(payload.path("status").asInt()).isEqualTo(400);
      assertThat(payload.path("response").path("success").asBoolean()).isFalse();
      assertThat(payload.path("response").path("code").asText())
          .isEqualTo("VALIDATION_ERROR");
      assertThat(trace.path("correlationId").asText()).isNotBlank();
    });
  }

  @Test
  void unexpected2xxFailsAndNextResetRemovesItsPartialMutation() {
    ValidationHttpIntegrationSupport http = new ValidationHttpIntegrationSupport();
    long generation = http.reset().generation();
    var request = ValidationHttpIntegrationSupport.negativeUnexpectedSuccess(generation);

    http.start(request);
    RunResult failed = http.awaitTerminal(request, "FAILED");

    assertThat(failed.failureCode())
        .isEqualTo("VALIDATION_EXPECTED_HTTP_ERROR_NOT_OBSERVED");
    assertThat(failed.apiTraces("PUBLIC_ACTION")).singleElement().satisfies(trace -> {
      http.assertSanitized(trace);
      JsonNode payload = trace.path("payload");
      assertThat(payload.path("outcome").asText()).isEqualTo("FAILED");
      assertThat(payload.path("expectedStatus").asInt()).isEqualTo(400);
      assertThat(payload.path("expectedCode").asText()).isEqualTo("VALIDATION_ERROR");
      assertThat(payload.path("status").asInt()).isEqualTo(200);
      assertThat(trace.path("correlationId").asText()).isNotBlank();
    });

    long nextGeneration = http.reset().generation();
    assertThat(http.rejectedEvents(request.runId()).code())
        .isEqualTo("VALIDATION_RUN_NOT_FOUND");
    RunResult clean = http.startAndAwait(
        ValidationHttpIntegrationSupport.resetProbe(nextGeneration));
    assertThat(clean.items("orders")).isEmpty();
    assertThat(clean.items("trades")).isEmpty();
    assertThat(clean.items("positions")).isEmpty();
    assertThat(clean.items("fundingSettlements")).isEmpty();

    List<JsonNode> wallets = clean.items("walletBalances");
    assertUniqueIds(wallets);
    assertThat(wallets).singleElement().satisfies(wallet -> {
      assertThat(wallet.path("walletType").asText()).isEqualTo("SPOT");
      assertThat(wallet.path("asset").asText()).isEqualTo("USDT");
      assertThat(ValidationHttpIntegrationSupport.decimalField(wallet, "total"))
          .isEqualByComparingTo("100000.00000000");
      assertThat(ValidationHttpIntegrationSupport.decimalField(wallet, "available"))
          .isEqualByComparingTo("100000.00000000");
      assertThat(ValidationHttpIntegrationSupport.decimalField(wallet, "locked")).isZero();
    });

    List<JsonNode> assetLedger = clean.items("assetLedger");
    assertUniqueIds(assetLedger);
    assertThat(assetLedger).hasSize(2).allSatisfy(entry -> {
      assertThat(entry.path("walletType").asText()).isEqualTo("SPOT");
      assertThat(entry.path("asset").asText()).isEqualTo("USDT");
    });
    assertThat(assetLedger.stream().map(entry -> entry.path("entryType").asText()).toList())
        .containsExactlyInAnyOrder("DEMO_INIT", "VALIDATION_SEED");

    List<JsonNode> visibleLedger = clean.items("cashLedger");
    assertUniqueIds(visibleLedger);
    assertThat(visibleLedger).hasSize(4).allSatisfy(entry ->
        assertThat(entry.path("currency").asText()).isEqualTo("USDT"));
    assertThat(visibleLedger.stream().map(entry -> entry.path("entryType").asText()).toList())
        .containsExactlyInAnyOrder(
            "DEMO_INIT", "DEMO_INIT", "VALIDATION_SEED", "VALIDATION_SEED");
    assertThat(visibleLedger.stream().map(entry -> entry.path("entryType").asText()).toList())
        .doesNotContain(
            "SPOT_ORDER_LOCK",
            "SPOT_BUY_DEBIT",
            "SPOT_BUY_CREDIT",
            "TRADE_FEE");

    JsonNode summary = clean.finalBusinessState().path("summary");
    assertThat(summary.path("accountType").asText()).isEqualTo("DEMO");
    assertThat(summary.path("baseCurrency").asText()).isEqualTo("USDT");
    assertThat(summary.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "balance"))
        .isEqualByComparingTo("100000.00000000");
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "equity"))
        .isEqualByComparingTo("100000.00000000");
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "usedMargin")).isZero();
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "freeMargin"))
        .isEqualByComparingTo("100000.00000000");
  }

  private static void assertUniqueIds(List<JsonNode> items) {
    List<String> ids = items.stream().map(item -> item.path("id").asText()).toList();
    assertThat(ids).allSatisfy(id -> assertThat(id).isNotBlank()).doesNotHaveDuplicates();
  }
}
