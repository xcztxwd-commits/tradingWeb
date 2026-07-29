package com.fxplatform.validation.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fxplatform.validation.http.ValidationHttpIntegrationSupport.RunResult;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("validation-fixed-runtime")
class ValidationProtectionReplayIT {

  @Test
  void nextTickMarkCrossingExecutesTheAttachedStopLossAndClosesThePosition() {
    ValidationHttpIntegrationSupport http = new ValidationHttpIntegrationSupport();
    long generation = http.reset().generation();
    var request = ValidationHttpIntegrationSupport.protectionReplay(generation);

    RunResult run = http.startAndAwait(request);
    RunResult replay = http.startAndAwait(request);

    List<JsonNode> orders = run.items("orders");
    assertUniqueIds(orders);
    assertThat(orders).hasSize(2);
    JsonNode parent = orders.stream()
        .filter(order -> "USER".equals(order.path("origin").asText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing user parent order"));
    JsonNode stopLoss = orders.stream()
        .filter(order -> "STOP_LOSS".equals(order.path("protectionType").asText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing protective stop-loss order"));
    JsonNode openedPosition = positionAtTick(run, 1L);

    assertThat(parent.path("status").asText()).isEqualTo("FILLED");
    assertThat(parent.path("side").asText()).isEqualTo("BUY");
    assertThat(parent.path("orderType").asText()).isEqualTo("MARKET");
    assertThat(parent.path("positionSide").asText()).isEqualTo("LONG");
    assertThat(parent.path("marginMode").asText()).isEqualTo("CROSS");
    assertThat(parent.path("reduceOnly").asBoolean()).isFalse();
    assertThat(stopLoss.path("origin").asText()).isEqualTo("PROTECTIVE");
    assertThat(stopLoss.path("status").asText()).isEqualTo("FILLED");
    assertThat(stopLoss.path("side").asText()).isEqualTo("SELL");
    assertThat(stopLoss.path("positionSide").asText()).isEqualTo("LONG");
    assertThat(stopLoss.path("marginMode").asText()).isEqualTo("CROSS");
    assertThat(stopLoss.path("reduceOnly").asBoolean()).isTrue();
    assertThat(stopLoss.path("triggerPriceType").asText()).isEqualTo("MARK_PRICE");
    assertThat(stopLoss.path("triggerExecutionType").asText()).isEqualTo("MARKET");
    assertThat(ValidationHttpIntegrationSupport.decimalField(stopLoss, "triggerPrice"))
        .isEqualByComparingTo("49000.00000000");
    assertThat(stopLoss.path("parentOrderId").asText()).isEqualTo(parent.path("id").asText());
    assertThat(stopLoss.path("parentPositionId").asText())
        .isEqualTo(openedPosition.path("id").asText());
    for (JsonNode order : orders) {
      for (String field : List.of(
          "lots", "quantity", "originalQuantity", "baseQuantity", "filledQuantity")) {
        assertThat(ValidationHttpIntegrationSupport.decimalField(order, field))
            .as(order.path("origin").asText() + " " + field)
            .isEqualByComparingTo("0.01000000");
      }
      assertThat(ValidationHttpIntegrationSupport.decimalField(order, "remainingQuantity"))
          .isZero();
    }

    List<JsonNode> trades = run.items("trades");
    assertUniqueIds(trades);
    assertThat(trades).hasSize(2);
    JsonNode parentTrade = tradeForOrder(trades, parent);
    JsonNode protectionTrade = tradeForOrder(trades, stopLoss);
    assertTrade(parentTrade, "BUY");
    assertTrade(protectionTrade, "SELL");
    assertThat(run.items("positions")).isEmpty();
    assertThat(run.events("MARKET_TICK")).hasSize(2);

    List<JsonNode> wallets = run.items("walletBalances");
    assertThat(wallets).singleElement().satisfies(wallet -> {
      assertThat(wallet.path("walletType").asText()).isEqualTo("SPOT");
      assertThat(wallet.path("asset").asText()).isEqualTo("USDT");
      assertThat(ValidationHttpIntegrationSupport.decimalField(wallet, "total"))
          .isEqualByComparingTo("100000.00000000");
      assertThat(ValidationHttpIntegrationSupport.decimalField(wallet, "available"))
          .isEqualByComparingTo("100000.00000000");
      assertThat(ValidationHttpIntegrationSupport.decimalField(wallet, "locked")).isZero();
    });
    List<JsonNode> assetLedger = run.items("assetLedger");
    assertUniqueIds(assetLedger);
    assertThat(assetLedger).hasSize(2).allSatisfy(entry -> {
      assertThat(entry.path("walletType").asText()).isEqualTo("SPOT");
      assertThat(entry.path("asset").asText()).isEqualTo("USDT");
    });
    assertThat(assetLedger.stream().map(entry -> entry.path("entryType").asText()).toList())
        .containsExactlyInAnyOrder("DEMO_INIT", "VALIDATION_SEED");

    List<JsonNode> visibleLedger = run.items("cashLedger");
    assertUniqueIds(visibleLedger);
    Set<String> assetIds = new HashSet<>(
        assetLedger.stream().map(entry -> entry.path("id").asText()).toList());
    List<JsonNode> cashOnly = visibleLedger.stream()
        .filter(entry -> !assetIds.contains(entry.path("id").asText()))
        .toList();
    assertUniqueIds(cashOnly);
    assertThat(cashOnly).hasSize(11);
    JsonNode parentOrderHold =
        singleEntry(cashOnly, "ORDER_HOLD", "ORDER", parent.path("id").asText());
    JsonNode parentOrderRelease =
        singleEntry(cashOnly, "ORDER_RELEASE", "ORDER", parent.path("id").asText());
    JsonNode protectionOrderHold =
        singleEntry(cashOnly, "ORDER_HOLD", "ORDER", stopLoss.path("id").asText());
    JsonNode protectionOrderRelease =
        singleEntry(cashOnly, "ORDER_RELEASE", "ORDER", stopLoss.path("id").asText());
    JsonNode marginHold = singleEntry(
        cashOnly, "MARGIN_HOLD", "POSITION", openedPosition.path("id").asText());
    JsonNode marginRelease = singleEntry(
        cashOnly, "MARGIN_RELEASE", "POSITION", openedPosition.path("id").asText());
    JsonNode realizedPnl = singleEntry(
        cashOnly, "TRADE_PNL", "POSITION", openedPosition.path("id").asText());
    JsonNode parentFee = singleEntry(
        cashOnly, "TRADE_FEE", "TRADE", parentTrade.path("id").asText());
    JsonNode protectionFee = singleEntry(
        cashOnly, "TRADE_FEE", "TRADE", protectionTrade.path("id").asText());
    singleEntry(cashOnly, "DEMO_INIT", "DEMO_ACCOUNT", parent.path("accountId").asText());
    singleEntry(cashOnly, "VALIDATION_SEED", "VALIDATION_SEED", null);
    assertThat(ValidationHttpIntegrationSupport.decimalField(parentOrderRelease, "amount"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(parentOrderHold, "amount"));
    assertThat(ValidationHttpIntegrationSupport.decimalField(protectionOrderRelease, "amount"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(protectionOrderHold, "amount"));
    assertThat(ValidationHttpIntegrationSupport.decimalField(marginRelease, "amount"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(marginHold, "amount"));
    assertThat(ValidationHttpIntegrationSupport.decimalField(realizedPnl, "amount"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(protectionTrade, "realizedPnl"));
    assertThat(ValidationHttpIntegrationSupport.decimalField(parentFee, "amount"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(parentTrade, "fee").negate());
    assertThat(ValidationHttpIntegrationSupport.decimalField(protectionFee, "amount"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(protectionTrade, "fee").negate());

    JsonNode summary = run.finalBusinessState().path("summary");
    assertThat(summary.path("id").asText()).isNotBlank();
    BigDecimal expectedBalance = new BigDecimal("100000.00000000")
        .add(ValidationHttpIntegrationSupport.decimalField(realizedPnl, "amount"))
        .add(ValidationHttpIntegrationSupport.decimalField(parentFee, "amount"))
        .add(ValidationHttpIntegrationSupport.decimalField(protectionFee, "amount"));
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "balance"))
        .isEqualByComparingTo(expectedBalance);
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "equity"))
        .isEqualByComparingTo(expectedBalance);
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "usedMargin")).isZero();
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "freeMargin"))
        .isEqualByComparingTo(expectedBalance);

    assertThat(replay.allEvents()).isEqualTo(run.allEvents());
    assertThat(replay.finalBusinessState()).isEqualTo(run.finalBusinessState());
    assertThat(replay.items("orders").stream().map(item -> item.path("id").asText()).toList())
        .containsExactlyInAnyOrderElementsOf(
            run.items("orders").stream().map(item -> item.path("id").asText()).toList());
    assertThat(replay.items("trades").stream().map(item -> item.path("id").asText()).toList())
        .containsExactlyInAnyOrderElementsOf(
            run.items("trades").stream().map(item -> item.path("id").asText()).toList());
    assertThat(replay.items("walletBalances")).isEqualTo(run.items("walletBalances"));
    assertThat(replay.items("assetLedger")).isEqualTo(run.items("assetLedger"));
    assertThat(replay.items("cashLedger")).isEqualTo(run.items("cashLedger"));
    assertThat(replay.finalBusinessState().path("summary"))
        .isEqualTo(run.finalBusinessState().path("summary"));
  }

  private static JsonNode positionAtTick(RunResult run, long tick) {
    JsonNode state = run.events("STATE_SNAPSHOT").stream()
        .filter(event -> event.path("payload").path("tickSequence").asLong() == tick)
        .map(event -> event.path("payload").path("state"))
        .reduce((left, right) -> right)
        .orElseThrow(() -> new AssertionError("Missing state snapshot at Tick " + tick));
    List<JsonNode> positions = ValidationHttpIntegrationSupport.stateItems(state, "positions");
    assertThat(positions).singleElement();
    return positions.getFirst();
  }

  private static JsonNode tradeForOrder(List<JsonNode> trades, JsonNode order) {
    List<JsonNode> matches = trades.stream()
        .filter(trade -> order.path("id").asText().equals(trade.path("orderId").asText()))
        .toList();
    assertThat(matches).singleElement();
    return matches.getFirst();
  }

  private static void assertTrade(JsonNode trade, String side) {
    assertThat(trade.path("side").asText()).isEqualTo(side);
    assertThat(trade.path("positionSide").asText()).isEqualTo("LONG");
    assertThat(trade.path("marginMode").asText()).isEqualTo("CROSS");
    assertThat(ValidationHttpIntegrationSupport.decimalField(trade, "lots"))
        .isEqualByComparingTo("0.01000000");
  }

  private static JsonNode singleEntry(
      List<JsonNode> entries,
      String entryType,
      String referenceType,
      String referenceId
  ) {
    List<JsonNode> matches = entries.stream()
        .filter(entry -> entryType.equals(entry.path("entryType").asText()))
        .filter(entry -> referenceType.equals(entry.path("referenceType").asText()))
        .filter(entry -> referenceId == null
            || referenceId.equals(entry.path("referenceId").asText()))
        .toList();
    assertThat(matches).as(entryType + " " + referenceType).singleElement();
    return matches.getFirst();
  }

  private static void assertUniqueIds(List<JsonNode> items) {
    List<String> ids = items.stream().map(item -> item.path("id").asText()).toList();
    assertThat(ids).allSatisfy(id -> assertThat(id).isNotBlank()).doesNotHaveDuplicates();
  }
}
