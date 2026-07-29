package com.fxplatform.validation.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fxplatform.validation.http.ValidationHttpIntegrationSupport.RunResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("validation-fixed-runtime")
class ValidationCrossMultiSymbolReplayIT {

  @Test
  void cancelIsTerminalAfterDeterministicCrossMultiSymbolState() {
    ValidationHttpIntegrationSupport http = new ValidationHttpIntegrationSupport();
    long generation = http.reset().generation();
    var request = ValidationHttpIntegrationSupport.crossMultiSymbolReplay(generation);

    http.start(request);
    http.awaitCompletedTick(request, 1L);
    http.pause(request.runId());
    JsonNode paused = http.awaitRunState(request, "PAUSED", 1L);
    long boundaryBeforeCancel =
        paused.path("run").path("lastCompletedTickSequence").asLong();
    assertThat(boundaryBeforeCancel).isPositive();
    assertThat(boundaryBeforeCancel).isLessThan((long) request.ticks().size());
    JsonNode cancelReceipt = http.cancel(request.runId());
    assertThat(cancelReceipt.path("state").asText()).isEqualTo("CANCELLED");
    RunResult run = http.awaitTerminal(request, "CANCELLED");

    assertThat(run.controlState().path("run").path("lastCompletedTickSequence").asLong())
        .isEqualTo(boundaryBeforeCancel);
    assertThat(run.events("MARKET_TICK")).hasSize((int) boundaryBeforeCancel);
    assertThat(run.events("CHECKPOINT")).hasSize((int) boundaryBeforeCancel);

    List<JsonNode> orders = run.items("orders");
    List<JsonNode> trades = run.items("trades");
    assertUniqueIds(orders);
    assertUniqueIds(trades);
    assertThat(orders).hasSize(2);
    assertThat(trades).hasSize(2);
    List<JsonNode> positions = run.items("positions");
    assertUniqueIds(positions);
    assertThat(positions).hasSize(2);
    assertThat(positions.stream().map(position -> position.path("symbol").asText()).toList())
        .containsExactlyInAnyOrder(
            ValidationHttpIntegrationSupport.BTC_PERPETUAL,
            ValidationHttpIntegrationSupport.ETH_PERPETUAL);
    assertThat(positions.stream()
        .map(position -> position.path("positionSide").asText())
        .toList()).containsExactlyInAnyOrder("LONG", "SHORT");
    assertThat(positions).allSatisfy(position -> {
      assertThat(position.path("marginMode").asText()).isEqualTo("CROSS");
      assertThat(position.path("status").asText()).isEqualTo("OPEN");
      assertThat(position.path("positionMode").asText()).isEqualTo("HEDGE");
      assertThat(ValidationHttpIntegrationSupport.decimalField(position, "marginHeld")).isPositive();
    });
    assertSymbolState(
        orders,
        trades,
        positions,
        ValidationHttpIntegrationSupport.BTC_PERPETUAL,
        "BUY",
        "LONG",
        "0.01000000");
    assertSymbolState(
        orders,
        trades,
        positions,
        ValidationHttpIntegrationSupport.ETH_PERPETUAL,
        "SELL",
        "SHORT",
        "0.10000000");
    assertThat(run.apiTraces("PUBLIC_ACTION")).hasSize(2);

    JsonNode cancelled = run.events("RUN_STATE_CHANGED").stream()
        .filter(event -> "CANCELLED".equals(
            event.path("payload").path("state").asText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing durable CANCELLED event"));
    long cancelSequence = cancelled.path("sequence").asLong();
    assertThat(run.allEvents().stream()
        .filter(event -> event.path("sequence").asLong() > cancelSequence)
        .map(event -> event.path("type").asText())
        .toList()).doesNotContain("MARKET_TICK", "CHECKPOINT", "STATE_SNAPSHOT");
  }

  private static void assertSymbolState(
      List<JsonNode> orders,
      List<JsonNode> trades,
      List<JsonNode> positions,
      String symbol,
      String side,
      String positionSide,
      String quantity
  ) {
    JsonNode order = itemForSymbol(orders, symbol);
    assertThat(order.path("side").asText()).isEqualTo(side);
    assertThat(order.path("orderType").asText()).isEqualTo("MARKET");
    assertThat(order.path("status").asText()).isEqualTo("FILLED");
    assertThat(order.path("productType").asText()).isEqualTo("LINEAR_PERP");
    assertThat(order.path("positionMode").asText()).isEqualTo("HEDGE");
    assertThat(order.path("positionSide").asText()).isEqualTo(positionSide);
    assertThat(order.path("marginMode").asText()).isEqualTo("CROSS");
    assertThat(order.path("quantityUnit").asText()).isEqualTo("BASE");
    assertThat(order.path("reduceOnly").asBoolean()).isFalse();
    assertThat(order.path("leverage").asInt()).isEqualTo(10);
    for (String field : List.of(
        "lots", "quantity", "originalQuantity", "baseQuantity", "filledQuantity")) {
      assertThat(ValidationHttpIntegrationSupport.decimalField(order, field))
          .as(symbol + " order " + field)
          .isEqualByComparingTo(quantity);
    }
    assertThat(ValidationHttpIntegrationSupport.decimalField(order, "remainingQuantity"))
        .isZero();

    JsonNode trade = itemForSymbol(trades, symbol);
    assertThat(trade.path("orderId").asText()).isEqualTo(order.path("id").asText());
    assertThat(trade.path("side").asText()).isEqualTo(side);
    assertThat(trade.path("positionSide").asText()).isEqualTo(positionSide);
    assertThat(trade.path("marginMode").asText()).isEqualTo("CROSS");
    assertThat(ValidationHttpIntegrationSupport.decimalField(trade, "lots"))
        .isEqualByComparingTo(quantity);

    JsonNode position = itemForSymbol(positions, symbol);
    assertThat(position.path("side").asText()).isEqualTo(side);
    assertThat(position.path("positionSide").asText()).isEqualTo(positionSide);
    assertThat(position.path("marginMode").asText()).isEqualTo("CROSS");
    assertThat(ValidationHttpIntegrationSupport.decimalField(position, "lots"))
        .isEqualByComparingTo(quantity);
  }

  private static JsonNode itemForSymbol(List<JsonNode> items, String symbol) {
    List<JsonNode> matches = items.stream()
        .filter(item -> symbol.equals(item.path("symbol").asText()))
        .toList();
    assertThat(matches).as(symbol).singleElement();
    return matches.getFirst();
  }

  private static void assertUniqueIds(List<JsonNode> items) {
    List<String> ids = items.stream().map(item -> item.path("id").asText()).toList();
    assertThat(ids).allSatisfy(id -> assertThat(id).isNotBlank()).doesNotHaveDuplicates();
  }
}
