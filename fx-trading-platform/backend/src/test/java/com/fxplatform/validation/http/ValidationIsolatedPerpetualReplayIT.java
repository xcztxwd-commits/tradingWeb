package com.fxplatform.validation.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fxplatform.validation.http.ValidationHttpIntegrationSupport.RunResult;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("validation-fixed-runtime")
class ValidationIsolatedPerpetualReplayIT {

  @Test
  void pauseResumeCrossesABoundaryWhileIsolatedOpenReduceCloseStaysExact() {
    ValidationHttpIntegrationSupport http = new ValidationHttpIntegrationSupport();
    long generation = http.reset().generation();
    var request = ValidationHttpIntegrationSupport.isolatedPerpetualReplay(generation);

    http.start(request);
    http.awaitCompletedTick(request, 1L);
    http.pause(request.runId());
    JsonNode paused = http.awaitRunState(request, "PAUSED", 1L);
    assertThat(paused.path("run").path("pauseRequested").asBoolean()).isTrue();
    long pausedBoundary =
        paused.path("run").path("lastCompletedTickSequence").asLong();
    assertThat(pausedBoundary).isPositive();
    assertThat(pausedBoundary).isLessThan((long) request.ticks().size());
    assertThat(http.resume(request.runId()).path("state").asText()).isEqualTo("ACCEPTED");
    RunResult run = http.awaitTerminal(request, "COMPLETED");

    List<JsonNode> orders = run.items("orders");
    assertUniqueIds(orders);
    assertThat(orders).hasSize(3).allSatisfy(order -> {
      assertThat(order.path("symbol").asText())
          .isEqualTo(ValidationHttpIntegrationSupport.BTC_PERPETUAL);
      assertThat(order.path("productType").asText()).isEqualTo("LINEAR_PERP");
      assertThat(order.path("positionMode").asText()).isEqualTo("HEDGE");
      assertThat(order.path("positionSide").asText()).isEqualTo("LONG");
      assertThat(order.path("marginMode").asText()).isEqualTo("ISOLATED");
      assertThat(order.path("quantityUnit").asText()).isEqualTo("BASE");
      assertThat(order.path("orderType").asText()).isEqualTo("MARKET");
      assertThat(order.path("status").asText()).isEqualTo("FILLED");
      assertThat(order.path("leverage").asInt()).isEqualTo(10);
      assertThat(ValidationHttpIntegrationSupport.decimalField(order, "remainingQuantity"))
          .isZero();
    });
    JsonNode buy = orders.stream()
        .filter(order -> "BUY".equals(order.path("side").asText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing isolated open order"));
    List<JsonNode> sells = orders.stream()
        .filter(order -> "SELL".equals(order.path("side").asText()))
        .toList();
    assertThat(sells).hasSize(2);
    assertOrderQuantity(buy, "0.02000000", false);
    sells.forEach(order -> assertOrderQuantity(order, "0.01000000", true));

    List<JsonNode> trades = run.items("trades");
    assertUniqueIds(trades);
    assertThat(trades).hasSize(3);
    orders.forEach(order -> {
      JsonNode trade = trades.stream()
          .filter(item -> order.path("id").asText().equals(item.path("orderId").asText()))
          .findFirst()
          .orElseThrow(() -> new AssertionError(
              "Missing trade for isolated order " + order.path("id").asText()));
      assertThat(trade.path("side").asText()).isEqualTo(order.path("side").asText());
      assertThat(trade.path("positionSide").asText()).isEqualTo("LONG");
      assertThat(trade.path("marginMode").asText()).isEqualTo("ISOLATED");
      assertThat(ValidationHttpIntegrationSupport.decimalField(trade, "lots"))
          .isEqualByComparingTo(
              ValidationHttpIntegrationSupport.decimalField(order, "filledQuantity"));
    });
    assertThat(run.items("positions")).isEmpty();

    JsonNode tickOnePosition = singlePositionAtTick(run, 1L);
    JsonNode tickTwoPosition = singlePositionAtTick(run, 2L);
    assertPosition(tickOnePosition, "0.02000000");
    assertPosition(tickTwoPosition, "0.01000000");
    assertThat(tickTwoPosition.path("id").asText())
        .isEqualTo(tickOnePosition.path("id").asText());
    assertThat(positionsAtTick(run, 3L)).isEmpty();

    assertThat(run.events("RUN_STATE_CHANGED").stream()
        .map(event -> event.path("payload").path("state").asText())
        .toList()).contains("PAUSED", "RECOVERING", "RUNNING", "COMPLETED");
    JsonNode restored = run.events("RECOVERY_BOUNDARY_RESTORED").getFirst();
    assertThat(run.events("RECOVERY_BOUNDARY_RESTORED")).singleElement().satisfies(event ->
        assertThat(event.path("payload").path("tickSequence").asLong())
            .isEqualTo(pausedBoundary));
    List<Long> completedBeforeResume =
        LongStream.rangeClosed(1L, pausedBoundary).boxed().toList();
    for (String type : List.of("MARKET_TICK", "CHECKPOINT")) {
      assertThat(run.events(type).stream()
          .filter(event ->
              event.path("payload").path("tickSequence").asLong() <= pausedBoundary)
          .map(event -> event.path("payload").path("tickSequence").asLong())
          .toList()).as(type + " at or before restored boundary")
          .containsExactlyElementsOf(completedBeforeResume);
    }
    assertThat(run.events("MARKET_TICK").stream()
        .map(event -> event.path("payload").path("tickSequence").asLong())
        .toList()).containsExactlyElementsOf(
            LongStream.rangeClosed(1L, request.ticks().size()).boxed().toList());
    JsonNode firstResumedTick = run.events("MARKET_TICK").stream()
        .filter(event ->
            event.path("payload").path("tickSequence").asLong() > pausedBoundary)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing resumed market tick"));
    assertThat(restored.path("sequence").asLong())
        .isLessThan(firstResumedTick.path("sequence").asLong());
  }

  private static void assertOrderQuantity(
      JsonNode order,
      String quantity,
      boolean reduceOnly
  ) {
    assertThat(order.path("reduceOnly").asBoolean()).isEqualTo(reduceOnly);
    for (String field : List.of(
        "lots", "quantity", "originalQuantity", "baseQuantity", "filledQuantity")) {
      assertThat(ValidationHttpIntegrationSupport.decimalField(order, field))
          .as(order.path("side").asText() + " " + field)
          .isEqualByComparingTo(quantity);
    }
  }

  private static JsonNode singlePositionAtTick(RunResult run, long tick) {
    List<JsonNode> positions = positionsAtTick(run, tick);
    assertThat(positions).as("positions after Tick " + tick).singleElement();
    return positions.getFirst();
  }

  private static List<JsonNode> positionsAtTick(RunResult run, long tick) {
    JsonNode latest = run.events("STATE_SNAPSHOT").stream()
        .filter(event -> event.path("payload").path("tickSequence").asLong() == tick)
        .map(event -> event.path("payload").path("state"))
        .reduce((left, right) -> right)
        .orElseThrow(() -> new AssertionError("Missing state snapshot for Tick " + tick));
    return ValidationHttpIntegrationSupport.stateItems(latest, "positions");
  }

  private static void assertPosition(JsonNode position, String lots) {
    assertThat(position.path("symbol").asText())
        .isEqualTo(ValidationHttpIntegrationSupport.BTC_PERPETUAL);
    assertThat(position.path("side").asText()).isEqualTo("BUY");
    assertThat(position.path("positionMode").asText()).isEqualTo("HEDGE");
    assertThat(position.path("positionSide").asText()).isEqualTo("LONG");
    assertThat(position.path("marginMode").asText()).isEqualTo("ISOLATED");
    assertThat(position.path("status").asText()).isEqualTo("OPEN");
    assertThat(ValidationHttpIntegrationSupport.decimalField(position, "marginHeld")).isPositive();
    assertThat(ValidationHttpIntegrationSupport.decimalField(position, "lots"))
        .isEqualByComparingTo(lots);
  }

  private static void assertUniqueIds(List<JsonNode> items) {
    List<String> ids = items.stream().map(item -> item.path("id").asText()).toList();
    assertThat(ids).allSatisfy(id -> assertThat(id).isNotBlank()).doesNotHaveDuplicates();
  }
}
